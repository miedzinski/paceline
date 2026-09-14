package paceline.device.rest

import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import paceline.device.domain.AdvertisementOption
import paceline.device.domain.ConnectedDeviceSnapshot
import paceline.device.domain.ConnectionCoordinator
import paceline.device.domain.ConnectionFailure
import paceline.device.domain.ConnectionState
import paceline.device.domain.DeviceCapabilityType
import paceline.device.domain.DeviceEndpoint
import paceline.device.domain.DeviceTransport
import paceline.device.domain.DiscoverySnapshot
import paceline.device.domain.HeartRateSourceDescriptor
import paceline.device.domain.HeartRateTelemetry
import paceline.device.domain.IndoorBikeTelemetry
import paceline.device.domain.NotDiscoveredException
import java.time.Instant

@RestController
@RequestMapping("/devices")
class DeviceConnectionController(
    private val coordinator: ConnectionCoordinator,
) {
    @GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    fun discoverDevices(): DeviceDiscoveryResponse = coordinator.discover().toResponse()

    @GetMapping("/connection", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getDeviceConnection(): DeviceConnectionResponse = coordinator.toResponse()

    @GetMapping("/connections", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getDeviceConnections(): DeviceConnectionResponse = coordinator.toResponse()

    @PostMapping("/{deviceId}/connection", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun connectDevice(
        @PathVariable("deviceId") deviceId: String,
    ): DeviceConnectionResponse =
        try {
            coordinator.toResponse(coordinator.connect(deviceId))
        } catch (exception: NotDiscoveredException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, exception.message, exception)
        }

    @DeleteMapping("/connections/{connectionId}")
    fun disconnectDevice(
        @PathVariable connectionId: String,
    ) {
        try {
            coordinator.disconnect(connectionId)
        } catch (exception: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, exception.message, exception)
        }
    }
}

data class DeviceDiscoveryResponse(
    val state: String,
    val changedAt: Instant,
    val devices: List<DeviceResponse>,
    val failure: DeviceFailureResponse?,
)

data class DeviceResponse(
    val id: String,
    val name: String,
    val transport: DeviceTransport,
    val host: String?,
    val port: Int?,
    val address: String?,
)

data class DeviceConnectionResponse(
    val state: String,
    val changedAt: Instant,
    val device: ConnectedDeviceResponse?,
    val failure: DeviceFailureResponse?,
    val telemetry: IndoorBikeTelemetryResponse?,
    val connections: List<ConnectedConnectionResponse> = emptyList(),
    val heartRateSources: List<HeartRateSourceResponse> = emptyList(),
)

data class ConnectedDeviceResponse(
    val name: String,
    val transport: DeviceTransport,
    val host: String?,
    val port: Int?,
    val address: String?,
    val id: String? = null,
)

data class ConnectedConnectionResponse(
    val id: String,
    val state: String,
    val changedAt: Instant,
    val device: ConnectedDeviceResponse,
    val failure: DeviceFailureResponse?,
    val telemetry: IndoorBikeTelemetryResponse?,
    val heartRate: DeviceHeartRateResponse?,
    val capabilities: Set<String>,
)

data class HeartRateSourceResponse(
    val id: String,
    val device: ConnectedDeviceResponse,
    val state: String,
    val heartRate: DeviceHeartRateResponse?,
)

data class DeviceFailureResponse(
    val code: String,
    val message: String,
)

data class IndoorBikeTelemetryResponse(
    val powerWatts: Int?,
    val cadenceRpm: Double?,
    val speedKph: Double?,
    val receivedAt: Instant,
)

data class DeviceHeartRateResponse(
    val heartRateBpm: Int,
    val receivedAt: Instant,
)

private fun DiscoverySnapshot.toResponse(): DeviceDiscoveryResponse =
    DeviceDiscoveryResponse(
        state = state.phase.name,
        changedAt = state.changedAt,
        devices = devices.map(AdvertisementOption::toResponse),
        failure = (failure ?: state.failure)?.toResponse(),
    )

internal fun ConnectionState.toResponse(telemetry: IndoorBikeTelemetry? = null): DeviceConnectionResponse =
    DeviceConnectionResponse(
        state = phase.name,
        changedAt = changedAt,
        device = device?.let(::toConnectedDeviceResponse),
        telemetry = telemetry?.toResponse(),
        failure = failure?.toResponse(),
    )

internal fun ConnectionCoordinator.toResponse(stateOverride: ConnectionState? = null): DeviceConnectionResponse {
    val currentState = stateOverride ?: current()
    val snapshots = connectedDevices()
    val snapshotForCurrentDevice =
        snapshots.firstOrNull { snapshot ->
            snapshot.state.device == currentState.device &&
                snapshot.state.phase == currentState.phase
        }
    return DeviceConnectionResponse(
        state = currentState.phase.name,
        changedAt = currentState.changedAt,
        device =
            currentState.device?.let { device ->
                toConnectedDeviceResponse(
                    device = device,
                    id = snapshotForCurrentDevice?.id,
                )
            },
        failure = currentState.failure?.toResponse(),
        telemetry =
            (snapshotForCurrentDevice?.telemetry ?: if (stateOverride == null) currentTelemetry() else null)
                ?.toResponse(),
        connections = snapshots.map(ConnectedDeviceSnapshot::toResponse),
        heartRateSources = heartRateSources().map { source -> source.toResponse(snapshots) },
    )
}

private fun ConnectedDeviceSnapshot.toResponse(): ConnectedConnectionResponse =
    ConnectedConnectionResponse(
        id = id,
        state = state.phase.name,
        changedAt = state.changedAt,
        device = toConnectedDeviceResponse(state.device!!, id),
        failure = state.failure?.toResponse(),
        telemetry = telemetry?.toResponse(),
        heartRate = heartRate?.toResponse(),
        capabilities = capabilities.map(DeviceCapabilityType::name).toSet(),
    )

private fun HeartRateSourceDescriptor.toResponse(snapshots: List<ConnectedDeviceSnapshot>): HeartRateSourceResponse =
    HeartRateSourceResponse(
        id = id,
        device = toConnectedDeviceResponse(device, id),
        state = state.name,
        heartRate = snapshots.firstOrNull { snapshot -> snapshot.id == id }?.heartRate?.toResponse(),
    )

private fun ConnectionFailure.toResponse(): DeviceFailureResponse =
    DeviceFailureResponse(
        code = code.name,
        message = message,
    )

private fun IndoorBikeTelemetry.toResponse(): IndoorBikeTelemetryResponse =
    IndoorBikeTelemetryResponse(
        powerWatts = powerWatts,
        cadenceRpm = cadenceRpm,
        speedKph = speedKph,
        receivedAt = receivedAt,
    )

private fun HeartRateTelemetry.toResponse(): DeviceHeartRateResponse =
    DeviceHeartRateResponse(
        heartRateBpm = heartRateBpm,
        receivedAt = receivedAt,
    )

private fun AdvertisementOption.toResponse(): DeviceResponse =
    DeviceResponse(
        id = id,
        name = device.name,
        transport = device.endpoint.transport,
        host = device.endpoint.hostOrNull(),
        port = device.endpoint.portOrNull(),
        address = device.endpoint.addressOrNull(),
    )

private fun toConnectedDeviceResponse(device: paceline.device.domain.DeviceAdvertisement): ConnectedDeviceResponse =
    toConnectedDeviceResponse(device, null)

private fun toConnectedDeviceResponse(
    device: paceline.device.domain.DeviceAdvertisement,
    id: String?,
): ConnectedDeviceResponse =
    ConnectedDeviceResponse(
        name = device.name,
        transport = device.endpoint.transport,
        host = device.endpoint.hostOrNull(),
        port = device.endpoint.portOrNull(),
        address = device.endpoint.addressOrNull(),
        id = id,
    )

private fun DeviceEndpoint.hostOrNull(): String? =
    when (this) {
        is DeviceEndpoint.Wifi -> host
        is DeviceEndpoint.Bluetooth -> null
    }

private fun DeviceEndpoint.portOrNull(): Int? =
    when (this) {
        is DeviceEndpoint.Wifi -> port
        is DeviceEndpoint.Bluetooth -> null
    }

private fun DeviceEndpoint.addressOrNull(): String? =
    when (this) {
        is DeviceEndpoint.Wifi -> null
        is DeviceEndpoint.Bluetooth -> address
    }
