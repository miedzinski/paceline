package paceline.device.adapters.profiles.ftms

import paceline.device.adapters.GattCharacteristic
import paceline.device.adapters.GattCharacteristicProperty
import paceline.device.adapters.GattService
import paceline.testsupport.FakeGattClient
import paceline.testsupport.kickrCore2Device
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FtmsDeviceConnectionTest {
    private val receivedAt = Instant.parse("2026-01-02T03:04:05Z")
    private val clock = Clock.fixed(receivedAt, ZoneOffset.UTC)

    @Test
    fun `subscribes to indoor bike data and publishes normalized telemetry`() {
        // given a GATT client exposing a notifiable FTMS Indoor Bike Data characteristic:
        val gattClient = fakeGattClient()
        val connection = FtmsDeviceConnection(kickrCore2Device(), gattClient, clock)
        val telemetry = mutableListOf<paceline.device.domain.IndoorBikeTelemetry>()
        connection.addTelemetryListener { telemetry += it }

        // when an Indoor Bike Data notification arrives:
        gattClient.emit(
            FtmsUuid.INDOOR_BIKE_DATA,
            byteArrayOf(
                0x44,
                0x00,
                0xC4.toByte(),
                0x09,
                0xB4.toByte(),
                0x00,
                0xC8.toByte(),
                0x00,
            ),
        )

        // then the connected session exposes the normalized measurement:
        assertEquals(listOf(FtmsUuid.INDOOR_BIKE_DATA), gattClient.enabledNotifications)
        assertTrue(connection.isOpen())
        assertEquals(200, connection.latestTelemetry()?.powerWatts)
        assertEquals(90.0, connection.latestTelemetry()?.cadenceRpm)
        assertEquals(25.0, connection.latestTelemetry()?.speedKph)
        assertEquals(receivedAt, connection.latestTelemetry()?.receivedAt)
        assertEquals(1, telemetry.size)

        connection.close()
        assertFalse(connection.isOpen())
    }

    @Test
    fun `does not accept a non-notifiable indoor bike data characteristic`() {
        // given a GATT client exposing FTMS without a notification-capable data characteristic:
        val gattClient =
            FakeGattClient(
                listOf(
                    GattService(
                        FtmsUuid.FITNESS_MACHINE_SERVICE,
                        listOf(GattCharacteristic(FtmsUuid.INDOOR_BIKE_DATA, emptySet())),
                    ),
                ),
            )

        // when the common FTMS session is opened:
        // then connection setup fails before a telemetry subscription is reported:
        kotlin.test.assertFailsWith<FtmsProtocolException> {
            FtmsDeviceConnection(kickrCore2Device(), gattClient, clock)
        }
        assertFalse(gattClient.isOpen())
    }

    private fun fakeGattClient(): FakeGattClient =
        FakeGattClient(
            listOf(
                GattService(
                    FtmsUuid.FITNESS_MACHINE_SERVICE,
                    listOf(
                        GattCharacteristic(
                            FtmsUuid.INDOOR_BIKE_DATA,
                            setOf(GattCharacteristicProperty.NOTIFY),
                        ),
                    ),
                ),
            ),
        )
}
