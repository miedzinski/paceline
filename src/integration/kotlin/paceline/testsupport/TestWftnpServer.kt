package paceline.testsupport

import paceline.device.adapters.gatt.ftms.FtmsUuid
import paceline.device.adapters.gatt.heartrate.HeartRateUuid
import paceline.device.adapters.lan.WftnpFrame
import paceline.device.adapters.lan.WftnpFrameCodec
import paceline.device.adapters.lan.WftnpMessageType
import paceline.device.adapters.lan.toWftnpBytes
import paceline.device.adapters.lan.uuidFromWftnpBytes
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class TestWftnpServer(
    val address: InetAddress,
) : AutoCloseable {
    private val serverSocket = ServerSocket(0, 1, address)
    private val closed = AtomicBoolean(false)
    private val acceptedConnectionCount = AtomicInteger(0)

    @Volatile
    private var acceptedSocket: Socket? = null

    private val serverThread =
        thread(start = true, isDaemon = true, name = "wftnp-integration-server") {
            serve()
        }

    val port: Int
        get() = serverSocket.localPort

    val acceptedConnections: Int
        get() = acceptedConnectionCount.get()

    val writes = CopyOnWriteArrayList<Pair<UUID, ByteArray>>()

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            serverSocket.close()
            acceptedSocket?.close()
            serverThread.join(1_000)
        }
    }

    private fun serve() {
        try {
            serverSocket
                .accept()
                .also {
                    acceptedSocket = it
                    acceptedConnectionCount.incrementAndGet()
                }.use { socket ->
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    while (!closed.get()) {
                        val request = WftnpFrameCodec.read(input) ?: return
                        if (request.messageType == WftnpMessageType.WRITE_CHARACTERISTIC) {
                            writes +=
                                uuidFromWftnpBytes(request.data) to
                                request.data.copyOfRange(16, request.data.size)
                        }
                        val responseData =
                            when (request.messageType) {
                                WftnpMessageType.DISCOVER_SERVICES -> {
                                    FtmsUuid.FITNESS_MACHINE_SERVICE.toWftnpBytes() +
                                        HeartRateUuid.HEART_RATE_SERVICE.toWftnpBytes()
                                }

                                WftnpMessageType.DISCOVER_CHARACTERISTICS -> {
                                    when (uuidFromWftnpBytes(request.data)) {
                                        FtmsUuid.FITNESS_MACHINE_SERVICE -> {
                                            FtmsUuid.FITNESS_MACHINE_SERVICE.toWftnpBytes() +
                                                FtmsUuid.INDOOR_BIKE_DATA.toWftnpBytes() +
                                                byteArrayOf(0x04) +
                                                FtmsUuid.FITNESS_MACHINE_CONTROL_POINT.toWftnpBytes() +
                                                byteArrayOf(0x06)
                                        }

                                        HeartRateUuid.HEART_RATE_SERVICE -> {
                                            HeartRateUuid.HEART_RATE_SERVICE.toWftnpBytes() +
                                                HeartRateUuid.HEART_RATE_MEASUREMENT.toWftnpBytes() +
                                                byteArrayOf(0x04)
                                        }

                                        else -> {
                                            error("Unexpected WFTNP service ${uuidFromWftnpBytes(request.data)}")
                                        }
                                    }
                                }

                                WftnpMessageType.ENABLE_NOTIFICATIONS -> {
                                    request.data.copyOfRange(0, 16)
                                }

                                WftnpMessageType.WRITE_CHARACTERISTIC -> {
                                    request.data.copyOfRange(0, 16)
                                }

                                else -> {
                                    error("Unexpected WFTNP request ${request.messageType}")
                                }
                            }
                        output.write(
                            WftnpFrameCodec.encode(
                                WftnpFrame(
                                    version = 1,
                                    messageType = request.messageType,
                                    sequence = request.sequence,
                                    responseCode = 0,
                                    data = responseData,
                                ),
                            ),
                        )
                        output.flush()

                        when (request.messageType) {
                            WftnpMessageType.ENABLE_NOTIFICATIONS -> {
                                if (uuidFromWftnpBytes(request.data) == FtmsUuid.INDOOR_BIKE_DATA) {
                                    writeNotification(
                                        output,
                                        FtmsUuid.INDOOR_BIKE_DATA.toWftnpBytes() +
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
                                }
                                if (uuidFromWftnpBytes(request.data) == HeartRateUuid.HEART_RATE_MEASUREMENT) {
                                    writeNotification(
                                        output,
                                        HeartRateUuid.HEART_RATE_MEASUREMENT.toWftnpBytes() +
                                            byteArrayOf(0x00, 0x78),
                                    )
                                }
                            }

                            WftnpMessageType.WRITE_CHARACTERISTIC -> {
                                if (uuidFromWftnpBytes(request.data) == FtmsUuid.FITNESS_MACHINE_CONTROL_POINT) {
                                    writeNotification(
                                        output,
                                        FtmsUuid.FITNESS_MACHINE_CONTROL_POINT.toWftnpBytes() +
                                            byteArrayOf(
                                                0x80.toByte(),
                                                request.data[16],
                                                0x01,
                                            ),
                                    )
                                }
                            }

                            else -> {
                                Unit
                            }
                        }
                    }
                }
        } catch (_: Exception) {
            if (!closed.get()) {
                // The integration assertion observes the failed lifecycle if the server exits early.
            }
        } finally {
            acceptedSocket = null
        }
    }

    private fun writeNotification(
        output: OutputStream,
        data: ByteArray,
    ) {
        output.write(
            WftnpFrameCodec.encode(
                WftnpFrame(
                    version = 1,
                    messageType = WftnpMessageType.CHARACTERISTIC_NOTIFICATION,
                    sequence = 0,
                    responseCode = 0,
                    data = data,
                ),
            ),
        )
        output.flush()
    }
}
