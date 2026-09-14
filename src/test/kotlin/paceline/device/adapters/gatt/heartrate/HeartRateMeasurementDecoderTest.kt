package paceline.device.adapters.gatt.heartrate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HeartRateMeasurementDecoderTest {
    private val decoder = HeartRateMeasurementDecoder()

    @Test
    fun `decodes the compact heart-rate measurement format`() {
        // given a Bluetooth Heart Rate Measurement containing a one-byte value:
        val value = byteArrayOf(0x00, 0x48)

        // when the measurement is decoded:
        val measurement = decoder.decode(value)

        // then the heart rate is exposed in beats per minute:
        assertEquals(72, measurement.heartRateBpm)
    }

    @Test
    fun `decodes the extended value and skips optional fields`() {
        // given a measurement with a two-byte value, energy expenditure, and RR intervals:
        val value = byteArrayOf(0x19, 0x2c, 0x01, 0x34, 0x12, 0x20, 0x01, 0x22, 0x01)

        // when the measurement is decoded:
        val measurement = decoder.decode(value)

        // then the extended heart-rate value is returned:
        assertEquals(300, measurement.heartRateBpm)
    }

    @Test
    fun `rejects a truncated optional field`() {
        // given a measurement that declares energy expenditure without providing its bytes:
        val value = byteArrayOf(0x08, 0x48)

        // when the malformed measurement is decoded:
        // then the protocol payload is rejected:
        assertFailsWith<HeartRateDataException> {
            decoder.decode(value)
        }
    }
}
