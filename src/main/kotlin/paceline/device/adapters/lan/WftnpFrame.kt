package paceline.device.adapters.lan

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

enum class WftnpMessageType(
    val code: Int,
) {
    DISCOVER_SERVICES(1),
    DISCOVER_CHARACTERISTICS(2),
    READ_CHARACTERISTIC(3),
    WRITE_CHARACTERISTIC(4),
    ENABLE_NOTIFICATIONS(5),
    CHARACTERISTIC_NOTIFICATION(6),
    ;

    companion object {
        fun fromCode(code: Int): WftnpMessageType =
            entries.firstOrNull { it.code == code }
                ?: throw WftnpProtocolException("Unsupported WFTNP message type $code")
    }
}

data class WftnpFrame(
    val version: Int,
    val messageType: WftnpMessageType,
    val sequence: Int,
    val responseCode: Int,
    val data: ByteArray,
)

open class WftnpProtocolException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class WftnpResponseException(
    val messageType: WftnpMessageType,
    val sequence: Int,
    val responseCode: Int,
) : WftnpProtocolException(
        "WFTNP request ${messageType.name} with sequence $sequence failed with response code $responseCode",
    )

class WftnpTimeoutException(
    message: String,
    cause: Throwable? = null,
) : WftnpProtocolException(message, cause)

object WftnpFrameCodec {
    const val VERSION = 1
    const val HEADER_SIZE = 6
    const val MAX_DATA_LENGTH = 0xffff

    fun encode(frame: WftnpFrame): ByteArray {
        require(frame.version in 0..0xff) { "WFTNP version must fit in one byte" }
        require(frame.sequence in 0..0xff) { "WFTNP sequence must fit in one byte" }
        require(frame.responseCode in 0..0xff) { "WFTNP response code must fit in one byte" }
        require(frame.data.size <= MAX_DATA_LENGTH) {
            "WFTNP data length must not exceed $MAX_DATA_LENGTH bytes"
        }

        val encoded =
            ByteBuffer
                .allocate(HEADER_SIZE + frame.data.size)
                .order(ByteOrder.BIG_ENDIAN)
                .put(frame.version.toByte())
                .put(frame.messageType.code.toByte())
                .put(frame.sequence.toByte())
                .put(frame.responseCode.toByte())
                .putShort(frame.data.size.toShort())

        encoded.put(frame.data)
        return encoded.array()
    }

    fun read(input: InputStream): WftnpFrame? {
        val header = ByteArray(HEADER_SIZE)
        if (!readFully(input, header, allowEmpty = true)) {
            return null
        }

        val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val version = buffer.get().toInt() and 0xff
        val messageType = WftnpMessageType.fromCode(buffer.get().toInt() and 0xff)
        val sequence = buffer.get().toInt() and 0xff
        val responseCode = buffer.get().toInt() and 0xff
        val dataLength = buffer.short.toInt() and 0xffff
        val data = ByteArray(dataLength)
        readFully(input, data)

        return WftnpFrame(
            version = version,
            messageType = messageType,
            sequence = sequence,
            responseCode = responseCode,
            data = data,
        )
    }

    fun decode(bytes: ByteArray): WftnpFrame {
        val input = ByteArrayInputStream(bytes)
        val frame = read(input) ?: throw WftnpProtocolException("WFTNP frame is empty")
        if (input.available() != 0) {
            throw WftnpProtocolException("WFTNP frame contains trailing bytes")
        }
        return frame
    }

    private fun readFully(
        input: InputStream,
        target: ByteArray,
        allowEmpty: Boolean = false,
    ): Boolean {
        var offset = 0
        while (offset < target.size) {
            val count = input.read(target, offset, target.size - offset)
            if (count < 0) {
                if (allowEmpty && offset == 0) {
                    return false
                }
                throw EOFException("Unexpected end of WFTNP frame after $offset of ${target.size} bytes")
            }
            if (count == 0) {
                continue
            }
            offset += count
        }
        return true
    }
}

internal fun UUID.toWftnpBytes(): ByteArray =
    ByteBuffer
        .allocate(16)
        .order(ByteOrder.BIG_ENDIAN)
        .putLong(mostSignificantBits)
        .putLong(leastSignificantBits)
        .array()

internal fun uuidFromWftnpBytes(
    bytes: ByteArray,
    offset: Int = 0,
): UUID {
    require(offset >= 0 && offset + 16 <= bytes.size) {
        "A WFTNP UUID requires 16 bytes starting at offset $offset"
    }
    val buffer = ByteBuffer.wrap(bytes, offset, 16).order(ByteOrder.BIG_ENDIAN)
    return UUID(buffer.long, buffer.long)
}
