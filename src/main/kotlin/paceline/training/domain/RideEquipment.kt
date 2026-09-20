package paceline.training.domain

import paceline.device.domain.ConnectionPhase
import paceline.device.domain.ConnectionSnapshot
import paceline.device.domain.DeviceAdvertisement
import paceline.device.domain.DeviceCapabilityType

enum class RideRole {
    RESISTANCE_CONTROL,
    POWER,
    CADENCE,
    HEART_RATE,
    ;

    companion object {
        val CONTROL: RideRole
            get() = RESISTANCE_CONTROL
    }
}

enum class RideSourceCapability {
    RESISTANCE_CONTROL,
    POWER,
    CADENCE,
    HEART_RATE,
}

enum class RideRoleStatus {
    SELECTED,
    AMBIGUOUS,
    UNAVAILABLE,
    OPTIONAL,
}

enum class RideReadiness {
    READY,
    SELECTION_REQUIRED,
    UNAVAILABLE,
}

enum class RideReadinessReasonCode {
    ROLE_SELECTION_REQUIRED,
    REQUIRED_ROLE_UNAVAILABLE,
    SELECTED_SOURCE_UNAVAILABLE,
}

data class RideReadinessReason(
    val code: RideReadinessReasonCode,
    val role: RideRole,
    val sourceId: String? = null,
    val compatibleSourceIds: List<String> = emptyList(),
) {
    val message: String
        get() =
            when (code) {
                RideReadinessReasonCode.ROLE_SELECTION_REQUIRED -> {
                    "Select a source for the $role ride role"
                }

                RideReadinessReasonCode.REQUIRED_ROLE_UNAVAILABLE -> {
                    "No connected source can provide the $role ride role"
                }

                RideReadinessReasonCode.SELECTED_SOURCE_UNAVAILABLE -> {
                    "Selected source $sourceId is unavailable for the $role ride role"
                }
            }
}

data class RideSourceDescriptor(
    val id: String,
    val state: ConnectionPhase,
    val capabilities: Set<RideSourceCapability>,
    val device: DeviceAdvertisement? = null,
) {
    init {
        require(id.isNotBlank()) { "Ride source ID must not be blank" }
    }

    fun supports(role: RideRole): Boolean =
        when (role) {
            RideRole.RESISTANCE_CONTROL -> RideSourceCapability.RESISTANCE_CONTROL in capabilities
            RideRole.POWER -> RideSourceCapability.POWER in capabilities
            RideRole.CADENCE -> RideSourceCapability.CADENCE in capabilities
            RideRole.HEART_RATE -> RideSourceCapability.HEART_RATE in capabilities
        }

    val connected: Boolean
        get() = state == ConnectionPhase.CONNECTED
}

data class RideEquipmentSelection(
    val controlSourceId: String? = null,
    val powerSourceId: String? = null,
    val cadenceSourceId: String? = null,
    val heartRateSourceId: String? = null,
) {
    fun sourceIdFor(role: RideRole): String? =
        when (role) {
            RideRole.RESISTANCE_CONTROL -> controlSourceId
            RideRole.POWER -> powerSourceId
            RideRole.CADENCE -> cadenceSourceId
            RideRole.HEART_RATE -> heartRateSourceId
        }

    fun withSource(
        role: RideRole,
        sourceId: String?,
    ): RideEquipmentSelection =
        when (role) {
            RideRole.RESISTANCE_CONTROL -> copy(controlSourceId = sourceId)
            RideRole.POWER -> copy(powerSourceId = sourceId)
            RideRole.CADENCE -> copy(cadenceSourceId = sourceId)
            RideRole.HEART_RATE -> copy(heartRateSourceId = sourceId)
        }
}

data class RideRoleState(
    val role: RideRole,
    val sourceId: String?,
    val status: RideRoleStatus,
    val compatibleSourceIds: List<String>,
)

data class RideEquipmentState(
    val selection: RideEquipmentSelection,
    val sources: List<RideSourceDescriptor>,
    val roles: Map<RideRole, RideRoleState>,
    val readiness: RideReadiness,
    val readinessReasons: List<RideReadinessReason>,
) {
    val ready: Boolean
        get() = readiness == RideReadiness.READY

    fun role(role: RideRole): RideRoleState = roles[role] ?: error("Ride role $role is not part of the equipment state")

    val control: RideRoleState
        get() = role(RideRole.RESISTANCE_CONTROL)

    val power: RideRoleState
        get() = role(RideRole.POWER)

    val cadence: RideRoleState
        get() = role(RideRole.CADENCE)

    val heartRate: RideRoleState
        get() = role(RideRole.HEART_RATE)
}

class RideSourceNotFoundException(
    val sourceId: String,
) : IllegalArgumentException("Ride source $sourceId is not connected")

class RideSourceUnavailableException(
    val sourceId: String,
) : IllegalArgumentException("Ride source $sourceId is unavailable")

class RideSourceIncompatibleException(
    val sourceId: String,
    val role: RideRole,
) : IllegalArgumentException("Ride source $sourceId cannot provide the $role role")

class RideEquipmentSelectionRequiredException(
    val role: RideRole? = null,
) : IllegalStateException(
        role?.let { "Select a source for the $it ride role before starting" }
            ?: "Select sources for the ambiguous ride roles before starting",
    )

class RideEquipmentUnavailableException(
    val state: RideEquipmentState,
) : IllegalStateException(
        (
            state.readinessReasons
                .joinToString("; ") { reason -> reason.message }
        ).ifBlank { "The selected ride equipment is unavailable or incomplete" },
    )

class RideEquipmentSelector {
    private var selection = RideEquipmentSelection()
    private val explicitlyClearedRoles = mutableSetOf<RideRole>()

    @Synchronized
    fun selected(): RideEquipmentSelection = selection

    @Synchronized
    fun current(sources: List<RideSourceDescriptor>): RideEquipmentState {
        val normalizedSources = normalizeSources(sources)
        autoAssign(normalizedSources)
        return snapshot(normalizedSources)
    }

    @Synchronized
    fun select(
        role: RideRole,
        sourceId: String,
        sources: List<RideSourceDescriptor>,
    ): RideEquipmentState {
        val normalizedSources = normalizeSources(sources)
        validateSelection(role, sourceId, normalizedSources)
        explicitlyClearedRoles.remove(role)
        selection = selection.withSource(role, sourceId)
        return current(normalizedSources)
    }

    @Synchronized
    fun apply(
        requested: RideEquipmentSelection,
        sources: List<RideSourceDescriptor>,
    ): RideEquipmentState {
        val normalizedSources = normalizeSources(sources)
        requested.controlSourceId?.let { validateSelection(RideRole.RESISTANCE_CONTROL, it, normalizedSources) }
        requested.powerSourceId?.let { validateSelection(RideRole.POWER, it, normalizedSources) }
        requested.cadenceSourceId?.let { validateSelection(RideRole.CADENCE, it, normalizedSources) }
        requested.heartRateSourceId?.let { validateSelection(RideRole.HEART_RATE, it, normalizedSources) }
        requested.controlSourceId?.let { explicitlyClearedRoles.remove(RideRole.RESISTANCE_CONTROL) }
        requested.powerSourceId?.let { explicitlyClearedRoles.remove(RideRole.POWER) }
        requested.cadenceSourceId?.let { explicitlyClearedRoles.remove(RideRole.CADENCE) }
        requested.heartRateSourceId?.let { explicitlyClearedRoles.remove(RideRole.HEART_RATE) }
        selection =
            selection.copy(
                controlSourceId = requested.controlSourceId ?: selection.controlSourceId,
                powerSourceId = requested.powerSourceId ?: selection.powerSourceId,
                cadenceSourceId = requested.cadenceSourceId ?: selection.cadenceSourceId,
                heartRateSourceId = requested.heartRateSourceId ?: selection.heartRateSourceId,
            )
        return current(normalizedSources)
    }

    @Synchronized
    fun clear(
        role: RideRole,
        sources: List<RideSourceDescriptor>,
    ): RideEquipmentState {
        selection = selection.withSource(role, null)
        explicitlyClearedRoles.add(role)
        return current(sources)
    }

    private fun autoAssign(sources: List<RideSourceDescriptor>) {
        if (selection.controlSourceId == null && RideRole.RESISTANCE_CONTROL !in explicitlyClearedRoles) {
            connectedCandidates(RideRole.RESISTANCE_CONTROL, sources)
                .singleOrNull()
                ?.let { source -> selection = selection.copy(controlSourceId = source.id) }
        }

        assignTelemetryRole(RideRole.POWER, sources) { current ->
            selection = selection.copy(powerSourceId = current)
        }
        assignTelemetryRole(RideRole.CADENCE, sources) { current ->
            selection = selection.copy(cadenceSourceId = current)
        }

        if (
            selection.heartRateSourceId == null &&
            RideRole.HEART_RATE !in explicitlyClearedRoles
        ) {
            connectedCandidates(RideRole.HEART_RATE, sources)
                .singleOrNull()
                ?.let { source -> selection = selection.copy(heartRateSourceId = source.id) }
        }
    }

    private fun assignTelemetryRole(
        role: RideRole,
        sources: List<RideSourceDescriptor>,
        assign: (String) -> Unit,
    ) {
        if (selection.sourceIdFor(role) != null || role in explicitlyClearedRoles) {
            return
        }

        val preferredControlSource =
            selection.controlSourceId
                ?.let { sourceId ->
                    sources.firstOrNull { source ->
                        source.id == sourceId && source.connected && source.supports(role)
                    }
                }
        val source = preferredControlSource ?: connectedCandidates(role, sources).singleOrNull()
        source?.let { assign(it.id) }
    }

    private fun snapshot(sources: List<RideSourceDescriptor>): RideEquipmentState {
        val roles =
            RideRole.entries.associateWith { role ->
                roleState(role, sources)
            }
        val requiredControl = roles.getValue(RideRole.RESISTANCE_CONTROL)
        val readiness =
            when {
                requiredControl.status == RideRoleStatus.UNAVAILABLE -> {
                    RideReadiness.UNAVAILABLE
                }

                requiredControl.status == RideRoleStatus.AMBIGUOUS -> {
                    RideReadiness.SELECTION_REQUIRED
                }

                else -> {
                    RideReadiness.READY
                }
            }
        return RideEquipmentState(
            selection = selection,
            sources = sources,
            roles = roles,
            readiness = readiness,
            readinessReasons =
                roles.values.mapNotNull { role ->
                    role.readinessReason()
                },
        )
    }

    private fun RideRoleState.readinessReason(): RideReadinessReason? =
        when (status) {
            RideRoleStatus.SELECTED,
            RideRoleStatus.OPTIONAL,
            -> {
                null
            }

            RideRoleStatus.AMBIGUOUS -> {
                RideReadinessReason(
                    code = RideReadinessReasonCode.ROLE_SELECTION_REQUIRED,
                    role = role,
                    compatibleSourceIds = compatibleSourceIds,
                ).takeUnless { role != RideRole.RESISTANCE_CONTROL }
            }

            RideRoleStatus.UNAVAILABLE -> {
                RideReadinessReason(
                    code =
                        if (sourceId == null) {
                            RideReadinessReasonCode.REQUIRED_ROLE_UNAVAILABLE
                        } else {
                            RideReadinessReasonCode.SELECTED_SOURCE_UNAVAILABLE
                        },
                    role = role,
                    sourceId = sourceId,
                    compatibleSourceIds = compatibleSourceIds,
                ).takeUnless { role != RideRole.RESISTANCE_CONTROL }
            }
        }

    private fun roleState(
        role: RideRole,
        sources: List<RideSourceDescriptor>,
    ): RideRoleState {
        val compatibleSourceIds = connectedCandidates(role, sources).map(RideSourceDescriptor::id)
        val selectedSourceId = selection.sourceIdFor(role)
        if (selectedSourceId != null) {
            val selected = sources.firstOrNull { source -> source.id == selectedSourceId }
            val selectedAvailable = selected?.connected == true && selected.supports(role)
            return RideRoleState(
                role = role,
                sourceId = selectedSourceId,
                status = if (selectedAvailable) RideRoleStatus.SELECTED else RideRoleStatus.UNAVAILABLE,
                compatibleSourceIds = compatibleSourceIds,
            )
        }
        if (
            role in explicitlyClearedRoles &&
            compatibleSourceIds.isNotEmpty()
        ) {
            return RideRoleState(
                role = role,
                sourceId = null,
                status = RideRoleStatus.AMBIGUOUS,
                compatibleSourceIds = compatibleSourceIds,
            )
        }
        if (role != RideRole.RESISTANCE_CONTROL && compatibleSourceIds.isEmpty()) {
            return RideRoleState(
                role = role,
                sourceId = null,
                status = RideRoleStatus.OPTIONAL,
                compatibleSourceIds = emptyList(),
            )
        }
        return RideRoleState(
            role = role,
            sourceId = null,
            status =
                when (compatibleSourceIds.size) {
                    0 -> RideRoleStatus.UNAVAILABLE
                    1 -> RideRoleStatus.UNAVAILABLE
                    else -> RideRoleStatus.AMBIGUOUS
                },
            compatibleSourceIds = compatibleSourceIds,
        )
    }

    private fun validateSelection(
        role: RideRole,
        sourceId: String,
        sources: List<RideSourceDescriptor>,
    ) {
        val source =
            sources.firstOrNull { candidate -> candidate.id == sourceId }
                ?: throw RideSourceNotFoundException(sourceId)
        if (!source.connected) {
            throw RideSourceUnavailableException(sourceId)
        }
        if (!source.supports(role)) {
            throw RideSourceIncompatibleException(sourceId, role)
        }
    }

    private fun connectedCandidates(
        role: RideRole,
        sources: List<RideSourceDescriptor>,
    ): List<RideSourceDescriptor> = sources.filter { source -> source.connected && source.supports(role) }

    private fun normalizeSources(sources: List<RideSourceDescriptor>): List<RideSourceDescriptor> =
        sources.distinctBy(RideSourceDescriptor::id)
}

fun ConnectionSnapshot.toRideSourceDescriptor(): RideSourceDescriptor {
    val sourceCapabilities =
        buildSet {
            if (DeviceCapabilityType.ERG_POWER_CONTROL in capabilities) {
                add(RideSourceCapability.RESISTANCE_CONTROL)
            }
            if (DeviceCapabilityType.CYCLING_TELEMETRY in capabilities) {
                add(RideSourceCapability.POWER)
                add(RideSourceCapability.CADENCE)
            }
            if (DeviceCapabilityType.POWER_TELEMETRY in capabilities) {
                add(RideSourceCapability.POWER)
            }
            if (DeviceCapabilityType.CADENCE_TELEMETRY in capabilities) {
                add(RideSourceCapability.CADENCE)
            }
            if (DeviceCapabilityType.HEART_RATE in capabilities) {
                add(RideSourceCapability.HEART_RATE)
            }
        }
    return RideSourceDescriptor(
        id = id,
        state = phase,
        capabilities = sourceCapabilities,
        device = device,
    )
}
