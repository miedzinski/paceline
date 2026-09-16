package paceline.device.adapters.gatt.ftms

import org.slf4j.LoggerFactory
import paceline.device.adapters.gatt.GattClient
import paceline.device.adapters.gatt.GattNotification
import paceline.device.ports.DeviceCommunicationException
import paceline.device.ports.IndoorBikePowerControl
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class FtmsErgControl(
    private val gattClient: GattClient,
    private val controlPointCharacteristic: UUID,
    private val responseTimeout: Duration = Duration.ofSeconds(3),
) : IndoorBikePowerControl {
    private data class PendingCommand(
        val opcode: Int,
        val response: CompletableFuture<ControlPointResponse>,
    )

    private data class ControlPointResponse(
        val requestedOpcode: Int,
        val resultCode: Int,
    )

    private val closed = AtomicBoolean(false)
    private val controlGranted = AtomicBoolean(false)
    private val pending = AtomicReference<PendingCommand?>(null)
    private val commandLock = Any()
    private val logger = LoggerFactory.getLogger(javaClass)
    private val notificationRegistration: AutoCloseable

    init {
        require(!responseTimeout.isNegative && !responseTimeout.isZero) {
            "FTMS control-point response timeout must be positive"
        }

        notificationRegistration = gattClient.addNotificationListener(::handleNotification)
        try {
            gattClient.enableNotifications(controlPointCharacteristic)
        } catch (exception: Exception) {
            notificationRegistration.close()
            throw exception
        }
    }

    override fun requestControl() {
        execute(opcode = OPCODE_REQUEST_CONTROL)
        controlGranted.set(true)
    }

    override fun setTargetPower(powerWatts: Int) {
        require(powerWatts in Short.MIN_VALUE.toInt()..Short.MAX_VALUE.toInt()) {
            "ERG target power must fit the FTMS signed 16-bit watt field"
        }
        check(controlGranted.get()) { "FTMS control has not been acquired" }

        val parameter = littleEndianShort(powerWatts.toShort())
        execute(opcode = OPCODE_SET_TARGET_POWER, parameter = parameter)
    }

    override fun setFreeRide() {
        check(controlGranted.get()) { "FTMS control has not been acquired" }

        // FTMS resistance level uses a 0.1-unit SINT16 field. Zero selects the
        // trainer's neutral manual-resistance mode without creating an ERG target.
        execute(
            opcode = OPCODE_SET_TARGET_RESISTANCE_LEVEL,
            parameter = littleEndianShort(0),
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        controlGranted.set(false)
        notificationRegistration.close()
        pending.getAndSet(null)?.response?.completeExceptionally(
            FtmsControlException("FTMS control point closed"),
        )
    }

    private fun execute(
        opcode: Int,
        parameter: ByteArray = byteArrayOf(),
    ) {
        check(!closed.get()) { "FTMS control point is closed" }
        check(gattClient.isOpen()) { "GATT connection is closed" }

        synchronized(commandLock) {
            val command =
                PendingCommand(opcode, CompletableFuture()).also {
                    check(pending.compareAndSet(null, it)) {
                        "Another FTMS control-point procedure is already in progress"
                    }
                }
            try {
                logger.debug(
                    "Sending FTMS control-point command: opcode=0x{}",
                    opcode.toString(16),
                )
                gattClient.writeCharacteristic(
                    controlPointCharacteristic,
                    byteArrayOf(opcode.toByte()) + parameter,
                )
                val response =
                    try {
                        command.response.get(responseTimeout.toMillis(), TimeUnit.MILLISECONDS)
                    } catch (exception: TimeoutException) {
                        throw FtmsControlException(
                            "Timed out waiting for FTMS control-point response to opcode 0x${opcode.toString(16)}",
                            exception,
                        )
                    } catch (exception: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw FtmsControlException(
                            "Interrupted while waiting for FTMS control-point response",
                            exception,
                        )
                    } catch (exception: ExecutionException) {
                        throw (exception.cause as? FtmsControlException)
                            ?: FtmsControlException(
                                "FTMS control-point response failed",
                                exception.cause ?: exception,
                            )
                    }

                if (response.requestedOpcode != opcode) {
                    throw FtmsControlException(
                        "FTMS control-point response opcode 0x${response.requestedOpcode.toString(16)} " +
                            "did not match request opcode 0x${opcode.toString(16)}",
                    )
                }
                if (response.resultCode != RESULT_SUCCESS) {
                    throw FtmsControlException(
                        "FTMS control-point opcode 0x${opcode.toString(16)} was rejected with result " +
                            "0x${response.resultCode.toString(16)}",
                    )
                }
                logger.debug(
                    "FTMS control-point command accepted: opcode=0x{} resultCode=0x{}",
                    opcode.toString(16),
                    response.resultCode.toString(16),
                )
            } catch (exception: FtmsControlException) {
                logger.warn(
                    "FTMS control-point command failed: opcode=0x{} error={}",
                    opcode.toString(16),
                    exception.message,
                    exception,
                )
                if (opcode == OPCODE_REQUEST_CONTROL) {
                    controlGranted.set(false)
                }
                throw exception
            } catch (exception: Exception) {
                logger.warn(
                    "FTMS control-point command failed before a protocol response: opcode=0x{}",
                    opcode.toString(16),
                    exception,
                )
                if (opcode == OPCODE_REQUEST_CONTROL) {
                    controlGranted.set(false)
                }
                throw FtmsControlException(
                    "Unable to send FTMS control-point opcode 0x${opcode.toString(16)}",
                    exception,
                )
            } finally {
                pending.compareAndSet(command, null)
            }
        }
    }

    private fun handleNotification(notification: GattNotification) {
        if (closed.get() || notification.characteristic != controlPointCharacteristic) {
            return
        }

        val response =
            try {
                decodeResponse(notification.value)
            } catch (exception: Exception) {
                pending.get()?.response?.complete(
                    ControlPointResponse(
                        requestedOpcode = -1,
                        resultCode = RESULT_MALFORMED,
                    ),
                )
                return
            }
        val command = pending.get() ?: return
        command.response.complete(response)
    }

    private fun decodeResponse(value: ByteArray): ControlPointResponse {
        if (value.size < 3 || value[0].toInt() and 0xff != OPCODE_RESPONSE_CODE) {
            throw FtmsControlException("Malformed FTMS control-point response")
        }
        return ControlPointResponse(
            requestedOpcode = value[1].toInt() and 0xff,
            resultCode = value[2].toInt() and 0xff,
        )
    }

    private fun littleEndianShort(value: Short): ByteArray =
        ByteBuffer
            .allocate(2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(value)
            .array()

    companion object {
        const val OPCODE_REQUEST_CONTROL = 0x00
        const val OPCODE_SET_TARGET_RESISTANCE_LEVEL = 0x04
        const val OPCODE_SET_TARGET_POWER = 0x05
        const val OPCODE_RESPONSE_CODE = 0x80
        const val RESULT_SUCCESS = 0x01

        private const val RESULT_MALFORMED = 0xff
    }
}

class FtmsControlException(
    message: String,
    cause: Throwable? = null,
) : DeviceCommunicationException(message, cause)
