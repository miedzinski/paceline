package paceline.device.rest

import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import paceline.device.domain.AdvertisementOption
import paceline.device.domain.AlreadyConnectedException
import paceline.device.domain.ConnectionCoordinator
import paceline.device.domain.ConnectionFailure
import paceline.device.domain.ConnectionState
import paceline.device.domain.DeviceEndpoint
import paceline.device.domain.DeviceTransport
import paceline.device.domain.DiscoverySnapshot
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
    fun getDeviceConnection(): DeviceConnectionResponse = coordinator.current().toResponse(coordinator.currentTelemetry())

    @PostMapping("/{deviceId}/connection", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun connectDevice(
        @PathVariable("deviceId") deviceId: String,
    ): DeviceConnectionResponse =
        try {
            coordinator.connect(deviceId).toResponse(coordinator.currentTelemetry())
        } catch (exception: NotDiscoveredException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, exception.message, exception)
        } catch (exception: AlreadyConnectedException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, exception.message, exception)
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
)

data class ConnectedDeviceResponse(
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

data class IndoorBikeTelemetryResponse(
    val powerWatts: Int?,
    val cadenceRpm: Double?,
    val speedKph: Double?,
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
    ConnectedDeviceResponse(
        name = device.name,
        transport = device.endpoint.transport,
        host = device.endpoint.hostOrNull(),
        port = device.endpoint.portOrNull(),
        address = device.endpoint.addressOrNull(),
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
