package paceline.device.adapters.gatt.cyclingpower

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CyclingPowerMeasurementDecoderTest {
    private val decoder = CyclingPowerMeasurementDecoder()

    @Test
    fun `decodes signed instantaneous power without filling unrelated fields`() {
        // given a Cycling Power Measurement with a negative instantaneous power value:
        val value = byteArrayOf(0x00, 0x00, 0xD4.toByte(), 0xFF.toByte())

        // when the standard profile payload is decoded:
        val measurement = decoder.decode(value)

        // then the signed power field is preserved:
        assertEquals(-44, measurement.instantaneousPowerWatts)
    }

    @Test
    fun `rejects a measurement without flags and power`() {
        // given a truncated Cycling Power Measurement:
        val value = byteArrayOf(0x00, 0x00, 0x2C)

        // when the malformed payload is decoded:
        // then no partial measurement is returned:
        assertFailsWith<CyclingPowerDataException> { decoder.decode(value) }
    }
}
