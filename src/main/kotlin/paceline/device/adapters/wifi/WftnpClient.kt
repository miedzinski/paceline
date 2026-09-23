package paceline.device.adapters.wifi

import paceline.device.adapters.gatt.GattCharacteristic
import paceline.device.adapters.gatt.GattCharacteristicProperty
import paceline.device.adapters.gatt.GattClient
import paceline.device.adapters.gatt.GattNotification
import paceline.device.adapters.gatt.GattService
import java.io.InputStream
import java.io.OutputStream
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class WftnpNotification(
    val characteristic: UUID,
    val value: ByteArray,
)

enum class WftnpCharacteristicProperty(
    val mask: Int,
) {
    READ(0x01),
    WRITE(0x02),
    NOTIFY(0x04),
}

data class WftnpCharacteristic(
    val uuid: UUID,
    val properties: Int,
) {
    fun supports(property: WftnpCharacteristicProperty): Boolean = properties and property.mask != 0
}

data class WftnpService(
    val uuid: UUID,
    val characteristics: List<WftnpCharacteristic>,
)

class WftnpClient(
    private val input: InputStream,
    private val output: OutputStream,
    private val requestTimeout: Duration = Duration.ofSeconds(3),
) : AutoCloseable {
    private data class RequestKey(
        val messageType: WftnpMessageType,
        val sequence: Int,
    )

    private data class NotificationListenerRegistration(
        val id: Int,
        val listener: (WftnpNotification) -> Unit,
        val executor: ExecutorService,
    )

    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val sequence = AtomicInteger(0)
    private val requestLock = Any()
    private val pending = ConcurrentHashMap<RequestKey, CompletableFuture<WftnpFrame>>()
    private val notificationListenerSequence = AtomicInteger(0)
    private val notificationListeners = CopyOnWriteArrayList<NotificationListenerRegistration>()

    @Volatile
    private var readerThread: Thread? = null

    init {
        require(!requestTimeout.isNegative && !requestTimeout.isZero) {
            "WFTNP request timeout must be positive"
        }
    }

    fun start() {
        check(!closed.get()) { "WFTNP client is closed" }
        check(started.compareAndSet(false, true)) { "WFTNP client has already started" }
        readerThread =
            Thread({ readLoop() }, "wftnp-reader").apply {
                isDaemon = true
                start()
            }
    }

    fun isOpen(): Boolean = started.get() && !closed.get() && readerThread?.isAlive == true

    fun addNotificationListener(listener: (WftnpNotification) -> Unit): AutoCloseable {
        check(!closed.get()) { "WFTNP client is closed" }
        val listenerId = notificationListenerSequence.incrementAndGet()
        val registration =
            NotificationListenerRegistration(
                id = listenerId,
                listener = listener,
                executor =
                    Executors.newSingleThreadExecutor { task ->
                        Thread(task, "wftnp-notification-$listenerId").apply { isDaemon = true }
                    },
            )
        notificationListeners += registration
        return AutoCloseable {
            notificationListeners -= registration
            registration.executor.shutdownNow()
        }
    }

    fun discoverServices(): List<UUID> {
        val data = request(WftnpMessageType.DISCOVER_SERVICES).data
        if (data.size % 16 != 0) {
            throw WftnpProtocolException("Malformed Discover Services response length ${data.size}")
        }
        return (data.indices step 16).map { offset -> uuidFromWftnpBytes(data, offset) }
    }

    fun discoverCharacteristics(service: UUID): List<WftnpCharacteristic> {
        val data = request(WftnpMessageType.DISCOVER_CHARACTERISTICS, service.toWftnpBytes()).data
        if (data.size < 16 || (data.size - 16) % 17 != 0) {
            throw WftnpProtocolException("Malformed Discover Characteristics response length ${data.size}")
        }

        val returnedService = uuidFromWftnpBytes(data)
        if (returnedService != service) {
            throw WftnpProtocolException(
                "Discover Characteristics returned $returnedService instead of $service",
            )
        }

        return ((16 until data.size) step 17).map { offset ->
            WftnpCharacteristic(
                uuid = uuidFromWftnpBytes(data, offset),
                properties = data[offset + 16].toInt() and 0xff,
            )
        }
    }

    fun discoverProfile(): List<WftnpService> =
        discoverServices().map { service ->
            WftnpService(service, discoverCharacteristics(service))
        }

    fun readCharacteristic(characteristic: UUID): ByteArray {
        val data = request(WftnpMessageType.READ_CHARACTERISTIC, characteristic.toWftnpBytes()).data
        return characteristicValue(data, characteristic, "Read Characteristic")
    }

    fun writeCharacteristic(
        characteristic: UUID,
        value: ByteArray,
    ) {
        val data = request(WftnpMessageType.WRITE_CHARACTERISTIC, characteristic.toWftnpBytes() + value).data
        characteristicValue(data, characteristic, "Write Characteristic")
    }

    fun enableNotifications(
        characteristic: UUID,
        enable: Boolean = true,
    ) {
        val data =
            request(
                WftnpMessageType.ENABLE_NOTIFICATIONS,
                characteristic.toWftnpBytes() + byteArrayOf(if (enable) 1 else 0),
            ).data
        characteristicValue(data, characteristic, "Enable Notifications")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }

        started.set(false)
        notificationListeners.forEach { registration -> registration.executor.shutdownNow() }
        notificationListeners.clear()
        closeQuietly(input)
        closeQuietly(output)
        failPending(WftnpProtocolException("WFTNP client closed"))

        readerThread
            ?.takeUnless { it === Thread.currentThread() }
            ?.join(1_000)
        readerThread = null
    }

    private fun request(
        messageType: WftnpMessageType,
        data: ByteArray = byteArrayOf(),
    ): WftnpFrame =
        synchronized(requestLock) {
            check(isOpen()) { "WFTNP client is not open" }
            val requestKey = RequestKey(messageType, nextSequence())
            val response = CompletableFuture<WftnpFrame>()
            pending[requestKey] = response

            try {
                output.write(
                    WftnpFrameCodec.encode(
                        WftnpFrame(
                            version = 1,
                            messageType = messageType,
                            sequence = requestKey.sequence,
                            responseCode = 0,
                            data = data,
                        ),
                    ),
                )
                output.flush()

                val frame =
                    try {
                        response.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS)
                    } catch (exception: TimeoutException) {
                        throw WftnpTimeoutException(
                            "Timed out waiting for WFTNP ${messageType.name} response",
                            exception,
                        )
                    } catch (exception: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw WftnpProtocolException("Interrupted waiting for WFTNP response", exception)
                    } catch (exception: ExecutionException) {
                        val cause = exception.cause ?: exception
                        if (cause is WftnpProtocolException) {
                            throw cause
                        }
                        throw WftnpProtocolException("WFTNP request failed", cause)
                    }

                if (frame.responseCode != 0) {
                    throw WftnpResponseException(messageType, requestKey.sequence, frame.responseCode)
                }
                frame
            } catch (exception: WftnpProtocolException) {
                throw exception
            } catch (exception: Exception) {
                throw WftnpProtocolException("Unable to send WFTNP ${messageType.name} request", exception)
            } finally {
                pending.remove(requestKey)
            }
        }

    private fun readLoop() {
        try {
            while (!closed.get()) {
                val frame = WftnpFrameCodec.read(input) ?: break
                if (frame.version != WftnpFrameCodec.VERSION) {
                    throw WftnpProtocolException("Unsupported WFTNP version ${frame.version}")
                }

                if (frame.messageType == WftnpMessageType.CHARACTERISTIC_NOTIFICATION) {
                    if (frame.data.size < 16) {
                        throw WftnpProtocolException(
                            "Malformed Characteristic Notification response length ${frame.data.size}",
                        )
                    }
                    val notification =
                        WftnpNotification(
                            characteristic = uuidFromWftnpBytes(frame.data),
                            value = frame.data.copyOfRange(16, frame.data.size),
                        )
                    notificationListeners.forEach { registration ->
                        try {
                            registration.executor.execute {
                                try {
                                    registration.listener(notification)
                                } catch (_: Exception) {
                                    // A consumer must not stop the protocol reader for every other listener.
                                }
                            }
                        } catch (_: RejectedExecutionException) {
                            // The listener was closed before this notification could be submitted.
                        }
                    }
                } else {
                    pending[RequestKey(frame.messageType, frame.sequence)]?.complete(frame)
                }
            }
        } catch (exception: Exception) {
            if (!closed.get()) {
                failPending(WftnpProtocolException("WFTNP reader stopped", exception))
            }
        } finally {
            started.set(false)
            failPending(WftnpProtocolException("WFTNP connection closed"))
        }
    }

    private fun nextSequence(): Int =
        sequence.updateAndGet { current ->
            if (current == 0xff) 1 else current + 1
        }

    private fun characteristicValue(
        data: ByteArray,
        characteristic: UUID,
        operation: String,
    ): ByteArray {
        if (data.size < 16) {
            throw WftnpProtocolException("Malformed $operation response length ${data.size}")
        }
        val returnedCharacteristic = uuidFromWftnpBytes(data)
        if (returnedCharacteristic != characteristic) {
            throw WftnpProtocolException(
                "$operation returned $returnedCharacteristic instead of $characteristic",
            )
        }
        return data.copyOfRange(16, data.size)
    }

    private fun failPending(exception: WftnpProtocolException) {
        pending.values.forEach { future -> future.completeExceptionally(exception) }
        pending.clear()
    }

    private fun closeQuietly(closeable: AutoCloseable) {
        try {
            closeable.close()
        } catch (_: Exception) {
            // Preserve the first protocol failure as the useful diagnostic.
        }
    }
}

class WftnpGattClient(
    private val client: WftnpClient,
    private val closeTransport: () -> Unit = {},
) : GattClient {
    override fun discoverServices(): List<GattService> =
        client.discoverProfile().map { service ->
            GattService(
                uuid = service.uuid,
                characteristics =
                    service.characteristics.map { characteristic ->
                        GattCharacteristic(
                            uuid = characteristic.uuid,
                            properties =
                                buildSet {
                                    if (characteristic.supports(WftnpCharacteristicProperty.NOTIFY)) {
                                        add(GattCharacteristicProperty.NOTIFY)
                                    }
                                    if (characteristic.supports(WftnpCharacteristicProperty.WRITE)) {
                                        add(GattCharacteristicProperty.WRITE)
                                    }
                                },
                        )
                    },
            )
        }

    override fun addNotificationListener(listener: (GattNotification) -> Unit): AutoCloseable =
        client.addNotificationListener { notification ->
            listener(GattNotification(notification.characteristic, notification.value))
        }

    override fun enableNotifications(characteristic: UUID) {
        client.enableNotifications(characteristic)
    }

    override fun writeCharacteristic(
        characteristic: UUID,
        value: ByteArray,
    ) {
        client.writeCharacteristic(characteristic, value)
    }

    override fun isOpen(): Boolean = client.isOpen()

    override fun close() {
        try {
            client.close()
        } finally {
            closeTransport()
        }
    }
}
