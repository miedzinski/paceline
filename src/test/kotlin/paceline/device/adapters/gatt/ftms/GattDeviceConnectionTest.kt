package paceline.device.adapters.gatt.ftms

import paceline.device.adapters.gatt.GattCharacteristic
import paceline.device.adapters.gatt.GattCharacteristicProperty
import paceline.device.adapters.gatt.GattDeviceConnection
import paceline.device.adapters.gatt.GattService
import paceline.device.adapters.gatt.SupportedGattCapabilityFactories
import paceline.device.adapters.gatt.cyclingpower.CyclingPowerUuid
import paceline.device.adapters.gatt.cyclingspeedcadence.CyclingSpeedCadenceUuid
import paceline.device.adapters.gatt.heartrate.HeartRateUuid
import paceline.device.ports.CyclingTelemetrySource
import paceline.device.ports.HeartRateTelemetrySource
import paceline.device.ports.TrainerControl
import paceline.telemetry.domain.CyclingTelemetry
import paceline.telemetry.domain.HeartRateTelemetry
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

class GattDeviceConnectionTest {
    private val receivedAt = Instant.parse("2026-01-02T03:04:05Z")
    private val clock = Clock.fixed(receivedAt, ZoneOffset.UTC)

    @Test
    fun `subscribes to indoor bike data and publishes normalized telemetry`() {
        // given a GATT client exposing a notifiable FTMS Indoor Bike Data characteristic:
        val gattClient = fakeGattClient()
        val connection =
            GattDeviceConnection(
                device = kickrCore2Device(),
                gattClient = gattClient,
                capabilityFactories = SupportedGattCapabilityFactories.all,
                clock = clock,
            )
        val bikeTelemetry = assertNotNull(connection.capabilities().filterIsInstance<CyclingTelemetrySource>().single())
        val telemetry = mutableListOf<CyclingTelemetry>()
        bikeTelemetry.addTelemetryListener { telemetry += it }

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
        assertEquals(200, bikeTelemetry.latestTelemetry()?.powerWatts)
        assertEquals(90.0, bikeTelemetry.latestTelemetry()?.cadenceRpm)
        assertEquals(25.0, bikeTelemetry.latestTelemetry()?.speedKph)
        assertEquals(receivedAt, bikeTelemetry.latestTelemetry()?.receivedAt)
        assertEquals(1, telemetry.size)

        connection.close()
        assertFalse(connection.isOpen())
    }

    @Test
    fun `publishes heart rate from a second capability on the same GATT connection`() {
        // given a GATT client exposing FTMS and the standard Heart Rate Service:
        val gattClient = fakeGattClient(withHeartRate = true)
        val connection =
            GattDeviceConnection(
                device = kickrCore2Device(),
                gattClient = gattClient,
                capabilityFactories = SupportedGattCapabilityFactories.all,
                clock = clock,
            )
        val heartRateSource = assertNotNull(connection.capabilities().filterIsInstance<HeartRateTelemetrySource>().single())
        val heartRates = mutableListOf<HeartRateTelemetry>()
        heartRateSource.addHeartRateListener { heartRates += it }

        // when a Heart Rate Measurement notification arrives:
        gattClient.emit(HeartRateUuid.HEART_RATE_MEASUREMENT, byteArrayOf(0x00, 0x75))

        // then the shared connection exposes the selected capability independently:
        assertEquals(
            listOf(FtmsUuid.INDOOR_BIKE_DATA, HeartRateUuid.HEART_RATE_MEASUREMENT),
            gattClient.enabledNotifications,
        )
        assertEquals(117, heartRateSource.latestHeartRate()?.heartRateBpm)
        assertEquals(listOf(117), heartRates.map { it.heartRateBpm })
        connection.close()
    }

    @Test
    fun `shares one GATT connection across FTMS power control CPS CSC and heart rate profiles`() {
        // given one physical GATT client exposing every supported profile:
        val gattClient =
            FakeGattClient(
                listOf(
                    GattService(
                        FtmsUuid.FITNESS_MACHINE_SERVICE,
                        listOf(
                            GattCharacteristic(
                                FtmsUuid.INDOOR_BIKE_DATA,
                                setOf(GattCharacteristicProperty.NOTIFY),
                            ),
                            GattCharacteristic(
                                FtmsUuid.FITNESS_MACHINE_CONTROL_POINT,
                                setOf(GattCharacteristicProperty.WRITE, GattCharacteristicProperty.INDICATE),
                            ),
                        ),
                    ),
                    GattService(
                        CyclingPowerUuid.CYCLING_POWER_SERVICE,
                        listOf(
                            GattCharacteristic(
                                CyclingPowerUuid.CYCLING_POWER_MEASUREMENT,
                                setOf(GattCharacteristicProperty.NOTIFY),
                            ),
                        ),
                    ),
                    GattService(
                        CyclingSpeedCadenceUuid.CYCLING_SPEED_CADENCE_SERVICE,
                        listOf(
                            GattCharacteristic(
                                CyclingSpeedCadenceUuid.CSC_MEASUREMENT,
                                setOf(GattCharacteristicProperty.NOTIFY),
                            ),
                        ),
                    ),
                    GattService(
                        HeartRateUuid.HEART_RATE_SERVICE,
                        listOf(
                            GattCharacteristic(
                                HeartRateUuid.HEART_RATE_MEASUREMENT,
                                setOf(GattCharacteristicProperty.NOTIFY),
                            ),
                        ),
                    ),
                ),
            )
        val connection =
            GattDeviceConnection(
                device = kickrCore2Device(),
                gattClient = gattClient,
                capabilityFactories = SupportedGattCapabilityFactories.all,
                clock = clock,
            )

        // when all profile notifications arrive through the shared client:
        val cyclingSources = connection.capabilities().filterIsInstance<CyclingTelemetrySource>()
        val received = mutableListOf<CyclingTelemetry>()
        cyclingSources.forEach { source -> source.addTelemetryListener { received += it } }
        gattClient.emit(CyclingPowerUuid.CYCLING_POWER_MEASUREMENT, byteArrayOf(0x00, 0x00, 0x2C, 0x01))
        gattClient.emit(
            CyclingSpeedCadenceUuid.CSC_MEASUREMENT,
            byteArrayOf(0x02, 0x0A, 0x00, 0xE8.toByte(), 0x03),
        )
        gattClient.emit(
            CyclingSpeedCadenceUuid.CSC_MEASUREMENT,
            byteArrayOf(0x02, 0x0B, 0x00, 0xE8.toByte(), 0x07),
        )

        // then each profile remains separate while the physical connection is initialized once:
        assertEquals(1, gattClient.discoverServicesCalls)
        assertEquals(3, cyclingSources.size)
        assertEquals(
            setOf(
                FtmsUuid.INDOOR_BIKE_DATA,
                FtmsUuid.FITNESS_MACHINE_CONTROL_POINT,
                CyclingPowerUuid.CYCLING_POWER_MEASUREMENT,
                CyclingSpeedCadenceUuid.CSC_MEASUREMENT,
                HeartRateUuid.HEART_RATE_MEASUREMENT,
            ),
            gattClient.enabledNotifications.toSet(),
        )
        assertEquals(
            300,
            cyclingSources
                .single { it.measurements == setOf(paceline.device.domain.CyclingMeasurement.POWER) }
                .latestTelemetry()
                ?.powerWatts,
        )
        assertEquals(
            60.0,
            cyclingSources
                .single { it.measurements == setOf(paceline.device.domain.CyclingMeasurement.CADENCE) }
                .latestTelemetry()
                ?.cadenceRpm,
        )
        assertEquals(3, received.size)
        connection.close()
    }

    @Test
    fun `opens a heart-rate-only GATT device without inventing trainer capabilities`() {
        // given a GATT client exposing only the standard Heart Rate Service:
        val gattClient = fakeGattClient(withIndoorBike = false, withHeartRate = true)
        val connection =
            GattDeviceConnection(
                device = kickrCore2Device(),
                gattClient = gattClient,
                capabilityFactories = SupportedGattCapabilityFactories.all,
                clock = clock,
            )

        // when the profile capabilities are inspected:
        // then only heart rate is available:
        assertEquals(null, connection.capabilities().filterIsInstance<CyclingTelemetrySource>().singleOrNull())
        assertEquals(null, connection.capabilities().filterIsInstance<TrainerControl>().singleOrNull())
        assertNotNull(connection.capabilities().filterIsInstance<HeartRateTelemetrySource>().single())
        connection.close()
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
            GattDeviceConnection(
                device = kickrCore2Device(),
                gattClient = gattClient,
                capabilityFactories = SupportedGattCapabilityFactories.all,
                clock = clock,
            )
        }
        assertFalse(gattClient.isOpen())
    }

    @Test
    fun `exposes FTMS power resistance release stop pause and resume procedures`() {
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
        val connection =
            GattDeviceConnection(
                device = kickrCore2Device(),
                gattClient = gattClient,
                capabilityFactories = SupportedGattCapabilityFactories.all,
                clock = clock,
            )
        val powerControl = assertNotNull(connection.capabilities().filterIsInstance<TrainerControl>().single())

        // when the session acquires control and submits its target and trainer commands:
        powerControl.requestControl()
        powerControl.setFreeRide()
        powerControl.setTargetPower(300)
        powerControl.setTargetPower(0)
        assertEquals(4, gattClient.writes.size)
        powerControl.releaseResistance()
        powerControl.stop()
        powerControl.pause()
        powerControl.startOrResume()

        // then the control point receives the explicit zero-watt ERG target and lifecycle commands:
        assertEquals(
            listOf(FtmsUuid.INDOOR_BIKE_DATA, FtmsUuid.FITNESS_MACHINE_CONTROL_POINT),
            gattClient.enabledNotifications,
        )
        assertEquals(8, gattClient.writes.size)
        assertEquals(FtmsUuid.FITNESS_MACHINE_CONTROL_POINT, gattClient.writes[0].first)
        assertContentEquals(byteArrayOf(FtmsErgControl.OPCODE_REQUEST_CONTROL.toByte()), gattClient.writes[0].second)
        assertContentEquals(
            byteArrayOf(FtmsErgControl.OPCODE_SET_TARGET_RESISTANCE_LEVEL.toByte(), 0x00, 0x00),
            gattClient.writes[1].second,
        )
        assertContentEquals(
            byteArrayOf(FtmsErgControl.OPCODE_SET_TARGET_POWER.toByte(), 0x2c, 0x01),
            gattClient.writes[2].second,
        )
        assertContentEquals(
            byteArrayOf(FtmsErgControl.OPCODE_SET_TARGET_POWER.toByte(), 0x00, 0x00),
            gattClient.writes[3].second,
        )
        assertContentEquals(
            byteArrayOf(FtmsErgControl.OPCODE_SET_TARGET_RESISTANCE_LEVEL.toByte(), 0x00, 0x00),
            gattClient.writes[4].second,
        )
        assertContentEquals(
            byteArrayOf(FtmsErgControl.OPCODE_STOP_OR_PAUSE.toByte(), 0x01),
            gattClient.writes[5].second,
        )
        assertContentEquals(
            byteArrayOf(FtmsErgControl.OPCODE_STOP_OR_PAUSE.toByte(), 0x02),
            gattClient.writes[6].second,
        )
        assertContentEquals(
            byteArrayOf(FtmsErgControl.OPCODE_START_OR_RESUME.toByte()),
            gattClient.writes[7].second,
        )

        connection.close()
    }

    @Test
    fun `opens a control-only FTMS device without Indoor Bike Data`() {
        // given a GATT client exposing only a writable and indicatable FTMS control point:
        val gattClient = fakeGattClient(withIndoorBike = false, withPowerControl = true)
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
        val connection =
            GattDeviceConnection(
                device = kickrCore2Device(),
                gattClient = gattClient,
                capabilityFactories = SupportedGattCapabilityFactories.all,
                clock = clock,
            )
        val powerControl = assertNotNull(connection.capabilities().filterIsInstance<TrainerControl>().single())

        // when the control-only connection acquires control and sets an ERG target:
        powerControl.requestControl()
        powerControl.setTargetPower(250)

        // then the connection is usable without inventing a telemetry source:
        assertTrue(connection.isOpen())
        assertEquals(null, connection.capabilities().filterIsInstance<CyclingTelemetrySource>().singleOrNull())
        assertEquals(listOf(FtmsUuid.FITNESS_MACHINE_CONTROL_POINT), gattClient.enabledNotifications)
        assertEquals(2, gattClient.writes.size)
        connection.close()
    }

    @Test
    fun `does not expose ERG control when the control point is absent`() {
        // given a connected FTMS device that only exposes telemetry:
        val connection =
            GattDeviceConnection(
                device = kickrCore2Device(),
                gattClient = fakeGattClient(),
                capabilityFactories = SupportedGattCapabilityFactories.all,
                clock = clock,
            )

        // when the connection capabilities are inspected:
        // then telemetry remains available without inventing a control capability:
        assertEquals(null, connection.capabilities().filterIsInstance<TrainerControl>().singleOrNull())
        connection.close()
    }

    @Test
    fun `requires control ownership before sending a target`() {
        // given a device with an available ERG control point:
        val gattClient = fakeGattClient(withPowerControl = true)
        val connection =
            GattDeviceConnection(
                device = kickrCore2Device(),
                gattClient = gattClient,
                capabilityFactories = SupportedGattCapabilityFactories.all,
                clock = clock,
            )
        val powerControl = assertNotNull(connection.capabilities().filterIsInstance<TrainerControl>().single())

        // when a target is submitted before control is requested:
        // then the adapter rejects the command without writing to the trainer:
        assertFailsWith<IllegalStateException> {
            powerControl.setTargetPower(300)
        }
        assertFailsWith<IllegalStateException> {
            powerControl.setFreeRide()
        }
        assertFailsWith<IllegalStateException> {
            powerControl.releaseResistance()
        }
        assertFailsWith<IllegalStateException> {
            powerControl.stop()
        }
        assertFailsWith<IllegalStateException> {
            powerControl.pause()
        }
        assertFailsWith<IllegalStateException> {
            powerControl.startOrResume()
        }
        assertEquals(emptyList(), gattClient.writes)
        connection.close()
    }

    private fun fakeGattClient(
        withIndoorBike: Boolean = true,
        withPowerControl: Boolean = false,
        withHeartRate: Boolean = false,
    ): FakeGattClient =
        FakeGattClient(
            buildList {
                if (withIndoorBike || withPowerControl) {
                    add(
                        GattService(
                            FtmsUuid.FITNESS_MACHINE_SERVICE,
                            buildList {
                                if (withIndoorBike) {
                                    GattCharacteristic(
                                        FtmsUuid.INDOOR_BIKE_DATA,
                                        setOf(GattCharacteristicProperty.NOTIFY),
                                    ).also(::add)
                                }
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
                    )
                }
                if (withHeartRate) {
                    add(
                        GattService(
                            HeartRateUuid.HEART_RATE_SERVICE,
                            listOf(
                                GattCharacteristic(
                                    HeartRateUuid.HEART_RATE_MEASUREMENT,
                                    setOf(GattCharacteristicProperty.NOTIFY),
                                ),
                            ),
                        ),
                    )
                }
            },
        )
}
