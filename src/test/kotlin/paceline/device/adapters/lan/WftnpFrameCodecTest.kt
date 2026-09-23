package paceline.device.adapters.lan

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class WftnpFrameCodecTest {
    @Test
    fun `encodes and decodes a frame with a network byte order data length`() {
        // given a WFTNP write request with three payload bytes:
        val frame =
            WftnpFrame(
                version = 1,
                messageType = WftnpMessageType.WRITE_CHARACTERISTIC,
                sequence = 7,
                responseCode = 0,
                data = byteArrayOf(0x01, 0x02, 0x03),
            )

        // when the frame is encoded and decoded:
        val encoded = WftnpFrameCodec.encode(frame)
        val decoded = WftnpFrameCodec.decode(encoded)

        // then the fixed header and payload are preserved:
        assertContentEquals(byteArrayOf(1, 4, 7, 0, 0, 3, 1, 2, 3), encoded)
        assertEquals(frame.version, decoded.version)
        assertEquals(frame.messageType, decoded.messageType)
        assertEquals(frame.sequence, decoded.sequence)
        assertEquals(frame.responseCode, decoded.responseCode)
        assertContentEquals(frame.data, decoded.data)
    }

    @Test
    fun `reads a frame when the tcp stream returns partial chunks`() {
        // given an encoded service-discovery response and a stream that returns one byte at a time:
        val encoded =
            WftnpFrameCodec.encode(
                WftnpFrame(
                    version = 1,
                    messageType = WftnpMessageType.DISCOVER_SERVICES,
                    sequence = 1,
                    responseCode = 0,
                    data = UUID.fromString("00001826-0000-1000-8000-00805f9b34fb").toWftnpBytes(),
                ),
            )

        // when the frame is read from the partial stream:
        val decoded = WftnpFrameCodec.read(SingleByteInputStream(encoded))

        // then all bytes arrive in the same logical frame:
        assertIs<WftnpFrame>(decoded)
        assertContentEquals(encoded, WftnpFrameCodec.encode(decoded))
    }

    @Test
    fun `rejects a truncated frame`() {
        // given a frame whose header promises four payload bytes but only two arrive:
        val truncated = byteArrayOf(1, 1, 1, 0, 0, 4, 1, 2)

        // when the truncated frame is decoded:
        // then the protocol boundary reports the incomplete input:
        assertFailsWith<java.io.EOFException> {
            WftnpFrameCodec.read(ByteArrayInputStream(truncated))
        }
    }

    @Test
    fun `rejects payloads larger than the protocol length field`() {
        // given a frame payload that cannot fit in the WFTNP uint16 length:
        val frame =
            WftnpFrame(
                version = 1,
                messageType = WftnpMessageType.WRITE_CHARACTERISTIC,
                sequence = 1,
                responseCode = 0,
                data = ByteArray(WftnpFrameCodec.MAX_DATA_LENGTH + 1),
            )

        // when the frame is encoded:
        // then the invalid payload is rejected before any bytes are sent:
        assertFailsWith<IllegalArgumentException> { WftnpFrameCodec.encode(frame) }
    }

    private class SingleByteInputStream(
        private val bytes: ByteArray,
    ) : InputStream() {
        private var offset = 0

        override fun read(): Int =
            if (offset == bytes.size) {
                -1
            } else {
                bytes[offset++].toInt() and 0xff
            }

        override fun read(
            target: ByteArray,
            targetOffset: Int,
            length: Int,
        ): Int {
            if (offset == bytes.size) {
                return -1
            }
            target[targetOffset] = bytes[offset++]
            return 1
        }
    }
}
