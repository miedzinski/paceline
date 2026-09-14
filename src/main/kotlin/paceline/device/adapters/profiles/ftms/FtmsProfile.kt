package paceline.device.adapters.profiles.ftms

import paceline.device.domain.IndoorBikeTelemetry
import java.time.Instant
import java.util.UUID

object FtmsUuid {
    val FITNESS_MACHINE_SERVICE: UUID = uuid16(0x1826)
    val INDOOR_BIKE_DATA: UUID = uuid16(0x2ad2)
    val FITNESS_MACHINE_CONTROL_POINT: UUID = uuid16(0x2ad9)

    private fun uuid16(value: Int): UUID = UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(value))
}

fun FtmsIndoorBikeData.toIndoorBikeTelemetry(receivedAt: Instant): IndoorBikeTelemetry =
    IndoorBikeTelemetry(
        powerWatts = instantaneousPowerWatts,
        cadenceRpm = instantaneousCadenceRpm,
        speedKph = instantaneousSpeedKph,
        receivedAt = receivedAt,
    )
