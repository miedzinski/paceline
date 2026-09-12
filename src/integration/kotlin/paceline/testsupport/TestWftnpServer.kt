package paceline.testsupport

import paceline.device.adapters.profiles.ftms.FtmsUuid
import paceline.device.adapters.wifi.WftnpFrame
import paceline.device.adapters.wifi.WftnpFrameCodec
import paceline.device.adapters.wifi.WftnpMessageType
import paceline.device.adapters.wifi.toWftnpBytes
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
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
                        val responseData =
                            when (request.messageType) {
                                WftnpMessageType.DISCOVER_SERVICES -> {
                                    FtmsUuid.FITNESS_MACHINE_SERVICE.toWftnpBytes()
                                }

                                WftnpMessageType.DISCOVER_CHARACTERISTICS -> {
                                    FtmsUuid.FITNESS_MACHINE_SERVICE.toWftnpBytes() +
                                        FtmsUuid.INDOOR_BIKE_DATA.toWftnpBytes() +
                                        byteArrayOf(0x04)
                                }

                                WftnpMessageType.ENABLE_NOTIFICATIONS -> {
                                    FtmsUuid.INDOOR_BIKE_DATA.toWftnpBytes()
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

                        if (request.messageType == WftnpMessageType.ENABLE_NOTIFICATIONS) {
                            output.write(
                                WftnpFrameCodec.encode(
                                    WftnpFrame(
                                        version = 1,
                                        messageType = WftnpMessageType.CHARACTERISTIC_NOTIFICATION,
                                        sequence = 0,
                                        responseCode = 0,
                                        data =
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
                                    ),
                                ),
                            )
                            output.flush()
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
}
