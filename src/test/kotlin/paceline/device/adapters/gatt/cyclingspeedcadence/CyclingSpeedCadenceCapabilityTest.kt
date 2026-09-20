package paceline.device.adapters.gatt.cyclingspeedcadence

import paceline.device.adapters.gatt.GattCharacteristic
import paceline.device.adapters.gatt.GattCharacteristicProperty
import paceline.device.adapters.gatt.GattDeviceConnection
import paceline.device.adapters.gatt.GattService
import paceline.device.domain.CyclingMeasurement
import paceline.device.ports.CyclingTelemetrySource
import paceline.testsupport.FakeGattClient
import paceline.testsupport.kickrCore2Device
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CyclingSpeedCadenceCapabilityTest {
    private val clock = Clock.fixed(Instant.parse("2026-09-14T12:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `wheel-only CSC notifications do not expose a cadence capability`() {
        // given a connected CSC profile that only reports wheel revolutions:
        val gattClient = fakeGattClient()
        val connection = connection(gattClient)
        val source = connection.capabilities().filterIsInstance<CyclingTelemetrySource>().single()

        // when the wheel-only notification arrives:
        gattClient.emit(
            CyclingSpeedCadenceUuid.CSC_MEASUREMENT,
            byteArrayOf(0x01, 0x01, 0x00, 0x00, 0x00, 0x10, 0x00),
        )

        // then the connection remains usable but no cadence source is advertised:
        assertTrue(connection.isOpen())
        assertEquals(emptySet<CyclingMeasurement>(), source.measurements)
        assertNull(source.latestTelemetry())
        connection.close()
    }

    @Test
    fun `crank-bearing CSC notifications activate the cadence capability`() {
        // given a connected CSC profile that reports crank revolutions:
        val gattClient = fakeGattClient()
        val connection = connection(gattClient)
        val source = connection.capabilities().filterIsInstance<CyclingTelemetrySource>().single()

        // when two crank notifications establish a revolution delta:
        gattClient.emit(
            CyclingSpeedCadenceUuid.CSC_MEASUREMENT,
            byteArrayOf(0x02, 0x00, 0x00, 0xE8.toByte(), 0x03),
        )
        gattClient.emit(
            CyclingSpeedCadenceUuid.CSC_MEASUREMENT,
            byteArrayOf(0x02, 0x01, 0x00, 0xE8.toByte(), 0x07),
        )

        // then the source is explicitly cadence-capable and derives cadence from crank data:
        assertEquals(setOf(CyclingMeasurement.CADENCE), source.measurements)
        assertEquals(60.0, source.latestTelemetry()?.cadenceRpm)
        connection.close()
    }

    private fun connection(gattClient: FakeGattClient): GattDeviceConnection =
        GattDeviceConnection(
            device = kickrCore2Device(),
            gattClient = gattClient,
            capabilityFactories = listOf(CyclingSpeedCadenceGattCapabilityFactory),
            clock = clock,
        )

    private fun fakeGattClient(): FakeGattClient =
        FakeGattClient(
            listOf(
                GattService(
                    CyclingSpeedCadenceUuid.CYCLING_SPEED_CADENCE_SERVICE,
                    listOf(
                        GattCharacteristic(
                            CyclingSpeedCadenceUuid.CSC_MEASUREMENT,
                            setOf(GattCharacteristicProperty.NOTIFY),
                        ),
                    ),
                ),
            ),
        )
}
