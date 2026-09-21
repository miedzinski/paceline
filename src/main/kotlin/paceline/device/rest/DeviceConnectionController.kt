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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import paceline.device.domain.AdvertisementOption
import paceline.device.domain.ConnectionCoordinator
import paceline.device.domain.ConnectionFailure
import paceline.device.domain.ConnectionPhase
import paceline.device.domain.ConnectionSnapshot
import paceline.device.domain.DeviceCapabilityType
import paceline.device.domain.DeviceEndpoint
import paceline.device.domain.DeviceTransport
import paceline.device.domain.DiscoveryFailure
import paceline.device.domain.DiscoveryPhase
import paceline.device.domain.DiscoverySnapshot
import paceline.device.domain.NotDiscoveredException
import paceline.telemetry.domain.CyclingTelemetry
import paceline.telemetry.domain.HeartRateTelemetry
import paceline.telemetry.rest.TelemetryProjectionResponse
import paceline.telemetry.rest.toResponse
import java.time.Instant

@RestController
@RequestMapping("/devices")
class DeviceConnectionController(
    private val coordinator: ConnectionCoordinator,
) {
    @GetMapping("/discovery", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getDiscovery(): DeviceDiscoveryResponse = coordinator.discoverySnapshot().toResponse()

    @PostMapping("/discovery", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun startDiscovery(): DeviceDiscoveryResponse = coordinator.startDiscovery().toResponse()

    @GetMapping("/discovery/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun discoveryEvents(): SseEmitter {
        val emitter = SseEmitter(Long.MAX_VALUE)
        var registration: AutoCloseable? = null
        var listenerFailed = false
        val closeRegistration = {
            registration?.let { registered ->
                registration = null
                runCatching { registered.close() }
            }
            Unit
        }
        val sendSnapshot: (DiscoverySnapshot) -> Unit = { snapshot ->
            try {
                emitter.send(
                    SseEmitter
                        .event()
                        .name("discovery")
                        .data(snapshot.toResponse(), MediaType.APPLICATION_JSON),
                )
            } catch (exception: Exception) {
                listenerFailed = true
                closeRegistration()
                emitter.completeWithError(exception)
            }
        }
        emitter.onCompletion(closeRegistration)
        emitter.onTimeout {
            closeRegistration()
            emitter.complete()
        }
        emitter.onError { closeRegistration() }
        registration = coordinator.addDiscoveryListener(sendSnapshot)
        if (listenerFailed) {
            closeRegistration()
        }
        return emitter
    }

    @GetMapping("/connections", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getDeviceConnections(): DeviceConnectionsResponse = coordinator.toResponse()

    @PostMapping("/{deviceId}/connection", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun connectDevice(
        @PathVariable("deviceId") deviceId: String,
    ): DeviceConnectionsResponse =
        try {
            coordinator.connect(deviceId)
            coordinator.toResponse()
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
    val state: DiscoveryPhase,
    val changedAt: Instant,
    val lastScanAt: Instant?,
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

data class DeviceConnectionsResponse(
    val connections: List<DeviceConnectionResponse>,
)

data class DeviceConnectionResponse(
    val id: String,
    val state: ConnectionPhase,
    val changedAt: Instant,
    val device: DeviceIdentityResponse,
    val failure: DeviceFailureResponse?,
    val telemetry: TelemetryProjectionResponse<CyclingTelemetryResponse>,
    val heartRate: TelemetryProjectionResponse<DeviceHeartRateResponse>,
    val capabilities: Set<DeviceCapabilityType>,
)

data class DeviceIdentityResponse(
    val name: String,
    val transport: DeviceTransport,
    val host: String?,
    val port: Int?,
    val address: String?,
)

data class DeviceFailureResponse(
    val code: String,
    val message: String,
)

data class CyclingTelemetryResponse(
    val powerWatts: Int?,
    val cadenceRpm: Double?,
    val speedKph: Double?,
    val distanceMeters: Double?,
    val receivedAt: Instant,
)

data class DeviceHeartRateResponse(
    val heartRateBpm: Int,
    val receivedAt: Instant,
)

private fun DiscoverySnapshot.toResponse(): DeviceDiscoveryResponse =
    DeviceDiscoveryResponse(
        state = state.phase,
        changedAt = state.changedAt,
        lastScanAt = lastScanAt,
        devices = devices.map(AdvertisementOption::toResponse),
        failure = (failure ?: state.failure)?.toResponse(),
    )

internal fun ConnectionCoordinator.toResponse(): DeviceConnectionsResponse =
    DeviceConnectionsResponse(
        connections = connectionSnapshots().map(ConnectionSnapshot::toResponse),
    )

private fun ConnectionSnapshot.toResponse(): DeviceConnectionResponse =
    DeviceConnectionResponse(
        id = id,
        state = phase,
        changedAt = changedAt,
        device = device.toResponse(),
        failure = failure?.toResponse(),
        telemetry = telemetry.toResponse(CyclingTelemetry::toResponse),
        heartRate = heartRate.toResponse(HeartRateTelemetry::toResponse),
        capabilities = capabilities,
    )

private fun ConnectionFailure.toResponse(): DeviceFailureResponse =
    DeviceFailureResponse(
        code = code.name,
        message = message,
    )

private fun DiscoveryFailure.toResponse(): DeviceFailureResponse =
    DeviceFailureResponse(
        code = code.name,
        message = message,
    )

private fun CyclingTelemetry.toResponse(): CyclingTelemetryResponse =
    CyclingTelemetryResponse(
        powerWatts = powerWatts,
        cadenceRpm = cadenceRpm,
        speedKph = speedKph,
        distanceMeters = distanceMeters,
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

private fun paceline.device.domain.DeviceAdvertisement.toResponse(): DeviceIdentityResponse =
    DeviceIdentityResponse(
        name = name,
        transport = endpoint.transport,
        host = endpoint.hostOrNull(),
        port = endpoint.portOrNull(),
        address = endpoint.addressOrNull(),
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
