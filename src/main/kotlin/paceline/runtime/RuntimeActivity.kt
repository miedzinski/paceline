package paceline.runtime

import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@Component
class RuntimeActivity {
    private val activeRequests = AtomicInteger()
    private val lastActivityNanos = AtomicLong(System.nanoTime())

    fun requestStarted(keepsRuntimeActive: Boolean = true) {
        if (keepsRuntimeActive) {
            activeRequests.incrementAndGet()
        }
        touch()
    }

    fun requestCompleted(keepsRuntimeActive: Boolean = true) {
        if (keepsRuntimeActive) {
            activeRequests.updateAndGet { current -> current.coerceAtLeast(1) - 1 }
        }
        touch()
    }

    fun isIdle(timeout: Duration): Boolean {
        if (activeRequests.get() != 0) {
            return false
        }
        return System.nanoTime() - lastActivityNanos.get() >= timeout.toNanos()
    }

    private fun touch() {
        lastActivityNanos.set(System.nanoTime())
    }
}
