package paceline.device.adapters.gatt.cyclingspeedcadence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CyclingSpeedCadenceMeasurementDecoderTest {
    private val decoder = CyclingSpeedCadenceMeasurementDecoder()

    @Test
    fun `decodes crank and wheel fields according to the flags`() {
        // given a CSC Measurement containing cumulative wheel and crank data:
        val value =
            byteArrayOf(
                0x03,
                0x78,
                0x56,
                0x34,
                0x12,
                0x04,
                0x00,
                0x00,
                0x00,
                0xE8.toByte(),
                0x03,
            )

        // when the standard profile payload is decoded:
        val measurement = decoder.decode(value)

        // then each present field is decoded and absent fields stay absent:
        assertEquals(0x12345678L, measurement.cumulativeWheelRevolutions)
        assertEquals(4, measurement.lastWheelEventTime)
        assertEquals(0, measurement.cumulativeCrankRevolutions)
        assertEquals(1000, measurement.lastCrankEventTime)
    }

    @Test
    fun `does not invent crank data for a wheel-only notification`() {
        // given a CSC Measurement that reports only wheel revolution data:
        val value = byteArrayOf(0x01, 0x01, 0x00, 0x00, 0x00, 0x10, 0x00)

        // when the payload is decoded:
        val measurement = decoder.decode(value)

        // then crank fields remain absent instead of being filled with zero:
        assertNull(measurement.cumulativeCrankRevolutions)
        assertNull(measurement.lastCrankEventTime)
    }

    @Test
    fun `rejects a measurement truncated at a flagged field`() {
        // given a CSC Measurement whose crank fields are incomplete:
        val value = byteArrayOf(0x02, 0x01)

        // when the malformed payload is decoded:
        // then the decoder reports the protocol error:
        assertFailsWith<CyclingSpeedCadenceDataException> { decoder.decode(value) }
    }
}
