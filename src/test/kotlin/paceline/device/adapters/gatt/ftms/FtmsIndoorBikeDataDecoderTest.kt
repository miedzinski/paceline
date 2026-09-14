package paceline.device.adapters.gatt.ftms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FtmsIndoorBikeDataDecoderTest {
    private val decoder = FtmsIndoorBikeDataDecoder()

    @Test
    fun `decodes instantaneous speed cadence and power`() {
        // given an Indoor Bike Data notification with the required instantaneous values:
        val value =
            byteArrayOf(
                0x44,
                0x00,
                0xC4.toByte(),
                0x09,
                0xB4.toByte(),
                0x00,
                0xC8.toByte(),
                0x00,
            )

        // when the FTMS value is decoded:
        val data = decoder.decode(value)

        // then the protocol units are converted to device units:
        assertEquals(25.0, data.instantaneousSpeedKph)
        assertEquals(90.0, data.instantaneousCadenceRpm)
        assertEquals(200, data.instantaneousPowerWatts)
        assertNull(data.averageSpeedKph)
        assertNull(data.averageCadenceRpm)
        assertNull(data.averagePowerWatts)
    }

    @Test
    fun `skips optional fields before instantaneous power`() {
        // given a notification containing average speed, distance, resistance, and power:
        val value =
            byteArrayOf(
                0x72,
                0x00,
                0xC4.toByte(),
                0x09,
                0xD0.toByte(),
                0x07,
                0x10,
                0x27,
                0x00,
                0xF4.toByte(),
                0x01,
                0x2C,
                0x01,
            )

        // when the FTMS value is decoded:
        val data = decoder.decode(value)

        // then the fields after the optional values are read at the correct offsets:
        assertEquals(25.0, data.instantaneousSpeedKph)
        assertEquals(20.0, data.averageSpeedKph)
        assertEquals(10_000.0, data.totalDistanceMeters)
        assertEquals(300, data.instantaneousPowerWatts)
    }

    @Test
    fun `honors the more-data flag when instantaneous speed is absent`() {
        // given a notification that omits instantaneous speed but includes cadence and power:
        val value =
            byteArrayOf(
                0x45,
                0x00,
                0xB4.toByte(),
                0x00,
                0xFA.toByte(),
                0x00,
            )

        // when the FTMS value is decoded:
        val data = decoder.decode(value)

        // then cadence and power are not shifted by the absent speed field:
        assertNull(data.instantaneousSpeedKph)
        assertEquals(90.0, data.instantaneousCadenceRpm)
        assertEquals(250, data.instantaneousPowerWatts)
    }

    @Test
    fun `rejects a notification truncated at a flagged field`() {
        // given a notification that flags instantaneous power but does not contain it:
        val value = byteArrayOf(0x40, 0x00)

        // when the FTMS value is decoded:
        // then the malformed payload is rejected instead of producing a partial measurement:
        assertFailsWith<FtmsDataException> { decoder.decode(value) }
    }
}
