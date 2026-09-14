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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

    @Test
    fun `exposes optional ERG control and sends FTMS target power procedures`() {
        // given a GATT client exposing a writable and indicatable FTMS control point:
        val gattClient = fakeGattClient(withPowerControl = true)
        gattClient.onWrite = { characteristic, value ->
            assertEquals(FtmsUuid.FITNESS_MACHINE_CONTROL_POINT, characteristic)
            gattClient.emit(
                characteristic,
                byteArrayOf(
                    FtmsErgControl.OPCODE_RESPONSE_CODE.toByte(),
                    value.first(),
                    FtmsErgControl.RESULT_SUCCESS.toByte(),
                ),
            )
        }
        val connection = FtmsDeviceConnection(kickrCore2Device(), gattClient, clock)
        val powerControl = assertNotNull(connection.powerControl)

        // when the session acquires control and sets a target power:
        powerControl.requestControl()
        powerControl.setTargetPower(300)

        // then the control point is enabled and receives the expected little-endian commands:
        assertEquals(
            listOf(FtmsUuid.INDOOR_BIKE_DATA, FtmsUuid.FITNESS_MACHINE_CONTROL_POINT),
            gattClient.enabledNotifications,
        )
        assertEquals(2, gattClient.writes.size)
        assertEquals(FtmsUuid.FITNESS_MACHINE_CONTROL_POINT, gattClient.writes[0].first)
        assertContentEquals(byteArrayOf(FtmsErgControl.OPCODE_REQUEST_CONTROL.toByte()), gattClient.writes[0].second)
        assertContentEquals(
            byteArrayOf(FtmsErgControl.OPCODE_SET_TARGET_POWER.toByte(), 0x2c, 0x01),
            gattClient.writes[1].second,
        )

        connection.close()
    }

    @Test
    fun `does not expose ERG control when the control point is absent`() {
        // given a connected FTMS device that only exposes telemetry:
        val connection = FtmsDeviceConnection(kickrCore2Device(), fakeGattClient(), clock)

        // when the connection capabilities are inspected:
        // then telemetry remains available without inventing a control capability:
        assertEquals(null, connection.powerControl)
        connection.close()
    }

    @Test
    fun `requires control ownership before sending a target`() {
        // given a device with an available ERG control point:
        val gattClient = fakeGattClient(withPowerControl = true)
        val connection = FtmsDeviceConnection(kickrCore2Device(), gattClient, clock)
        val powerControl = assertNotNull(connection.powerControl)

        // when a target is submitted before control is requested:
        // then the adapter rejects the command without writing to the trainer:
        assertFailsWith<IllegalStateException> {
            powerControl.setTargetPower(300)
        }
        assertEquals(emptyList(), gattClient.writes)
        connection.close()
    }

    private fun fakeGattClient(withPowerControl: Boolean = false): FakeGattClient =
        FakeGattClient(
            listOf(
                GattService(
                    FtmsUuid.FITNESS_MACHINE_SERVICE,
                    buildList {
                        GattCharacteristic(
                            FtmsUuid.INDOOR_BIKE_DATA,
                            setOf(GattCharacteristicProperty.NOTIFY),
                        ).also(::add)
                        if (withPowerControl) {
                            add(
                                GattCharacteristic(
                                    FtmsUuid.FITNESS_MACHINE_CONTROL_POINT,
                                    setOf(
                                        GattCharacteristicProperty.WRITE,
                                        GattCharacteristicProperty.INDICATE,
                                    ),
                                ),
                            )
                        }
                    },
                ),
            ),
        )
}
