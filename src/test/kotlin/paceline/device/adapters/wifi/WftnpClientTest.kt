package paceline.device.adapters.wifi

import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WftnpClientTest {
    @Test
    fun `blocked notification listener does not block protocol response processing`() {
        // given a blocked notification listener and a request whose response arrives after a notification:
        val clientInput = PipedInputStream()
        val serverOutput = PipedOutputStream(clientInput)
        val serverInput = PipedInputStream()
        val clientOutput = PipedOutputStream(serverInput)
        val service = UUID.fromString("00001826-0000-1000-8000-00805f9b34fb")
        val dataCharacteristic = UUID.fromString("00002ad2-0000-1000-8000-00805f9b34fb")
        val listenerStarted = CountDownLatch(1)
        val releaseListener = CountDownLatch(1)
        val requestCompleted = CountDownLatch(1)
        val discoveredServices = AtomicReference<List<UUID>?>(null)
        val requestFailure = AtomicReference<Throwable?>(null)
        val serverFailure = AtomicReference<Throwable?>(null)
        val server =
            thread(start = true, isDaemon = true, name = "wftnp-blocked-listener-test-server") {
                try {
                    val request = WftnpFrameCodec.read(serverInput) ?: return@thread
                    serverOutput.write(
                        WftnpFrameCodec.encode(
                            WftnpFrame(
                                version = 1,
                                messageType = WftnpMessageType.CHARACTERISTIC_NOTIFICATION,
                                sequence = 0,
                                responseCode = 0,
                                data = dataCharacteristic.toWftnpBytes() + byteArrayOf(0x44, 0x00),
                            ),
                        ),
                    )
                    serverOutput.flush()
                    check(listenerStarted.await(1, java.util.concurrent.TimeUnit.SECONDS)) {
                        "Notification listener did not start"
                    }
                    serverOutput.write(
                        WftnpFrameCodec.encode(
                            WftnpFrame(
                                version = 1,
                                messageType = request.messageType,
                                sequence = request.sequence,
                                responseCode = 0,
                                data = service.toWftnpBytes(),
                            ),
                        ),
                    )
                    serverOutput.flush()
                } catch (exception: Throwable) {
                    serverFailure.set(exception)
                }
            }
        val client = WftnpClient(clientInput, clientOutput, Duration.ofSeconds(2))
        val registration =
            client.addNotificationListener {
                listenerStarted.countDown()
                releaseListener.await(5, java.util.concurrent.TimeUnit.SECONDS)
            }
        client.start()
        val requester =
            thread(start = true, isDaemon = true, name = "wftnp-blocked-listener-test-request") {
                try {
                    discoveredServices.set(client.discoverServices())
                } catch (exception: Throwable) {
                    requestFailure.set(exception)
                } finally {
                    requestCompleted.countDown()
                }
            }

        // when a listener remains blocked, the reader receives the request response:
        try {
            assertTrue(listenerStarted.await(1, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(requestCompleted.await(1, java.util.concurrent.TimeUnit.SECONDS))

            // then the response is processed independently and the listener is still blocked:
            assertEquals(null, requestFailure.get())
            assertEquals(listOf(service), discoveredServices.get())
            assertEquals(1L, releaseListener.count)
            assertEquals(null, serverFailure.get())
        } finally {
            releaseListener.countDown()
            registration.close()
            client.close()
            serverOutput.close()
            serverInput.close()
            server.join(1_000)
            requester.join(1_000)
        }
    }

    @Test
    fun `discovers the gatt profile and routes unsolicited notifications`() {
        // given a synthetic WFTNP server with an FTMS service and indoor-bike-data characteristic:
        val clientInput = PipedInputStream()
        val serverOutput = PipedOutputStream(clientInput)
        val serverInput = PipedInputStream()
        val clientOutput = PipedOutputStream(serverInput)
        val service = UUID.fromString("00001826-0000-1000-8000-00805f9b34fb")
        val dataCharacteristic = UUID.fromString("00002ad2-0000-1000-8000-00805f9b34fb")
        val notificationValue = byteArrayOf(0x44, 0x00, 0xC4.toByte(), 0x09, 0xB4.toByte(), 0x00, 0xC8.toByte(), 0x00)
        val serverFailure = AtomicReference<Throwable?>(null)
        val notificationReceived = CountDownLatch(1)
        val receivedNotification = AtomicReference<WftnpNotification?>(null)
        val server =
            thread(start = true, isDaemon = true, name = "wftnp-test-server") {
                try {
                    repeat(3) {
                        val request = WftnpFrameCodec.read(serverInput) ?: return@thread
                        val responseData =
                            when (request.messageType) {
                                WftnpMessageType.DISCOVER_SERVICES -> {
                                    service.toWftnpBytes()
                                }

                                WftnpMessageType.DISCOVER_CHARACTERISTICS -> {
                                    service.toWftnpBytes() + dataCharacteristic.toWftnpBytes() + byteArrayOf(0x04)
                                }

                                WftnpMessageType.ENABLE_NOTIFICATIONS -> {
                                    dataCharacteristic.toWftnpBytes()
                                }

                                else -> {
                                    error("Unexpected request ${request.messageType}")
                                }
                            }
                        serverOutput.write(
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
                        serverOutput.flush()
                        if (request.messageType == WftnpMessageType.ENABLE_NOTIFICATIONS) {
                            serverOutput.write(
                                WftnpFrameCodec.encode(
                                    WftnpFrame(
                                        version = 1,
                                        messageType = WftnpMessageType.CHARACTERISTIC_NOTIFICATION,
                                        sequence = 0,
                                        responseCode = 0,
                                        data = dataCharacteristic.toWftnpBytes() + notificationValue,
                                    ),
                                ),
                            )
                            serverOutput.flush()
                        }
                    }
                } catch (exception: Throwable) {
                    serverFailure.set(exception)
                }
            }
        val client = WftnpClient(clientInput, clientOutput, Duration.ofSeconds(1))
        val registration =
            client.addNotificationListener { notification ->
                receivedNotification.set(notification)
                notificationReceived.countDown()
            }

        // when the client starts, discovers FTMS, and enables its data notification:
        client.start()
        try {
            assertEquals(listOf(service), client.discoverServices())
            assertEquals(
                listOf(WftnpCharacteristic(dataCharacteristic, WftnpCharacteristicProperty.NOTIFY.mask)),
                client.discoverCharacteristics(service),
            )
            client.enableNotifications(dataCharacteristic)

            // then the profile is decoded and the unsolicited data is delivered:
            assertTrue(notificationReceived.await(1, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(null, serverFailure.get())
            val notification = receivedNotification.get()!!
            assertEquals(dataCharacteristic, notification.characteristic)
            assertContentEquals(notificationValue, notification.value)
        } finally {
            registration.close()
            client.close()
            serverOutput.close()
            serverInput.close()
            server.join(1_000)
        }
    }
}
