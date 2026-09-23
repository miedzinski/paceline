package paceline.runtime

import jakarta.servlet.AsyncEvent
import jakarta.servlet.AsyncListener
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.concurrent.atomic.AtomicBoolean

@Component
class RuntimeActivityFilter(
    private val activity: RuntimeActivity,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val keepsRuntimeActive = !request.acceptsEventStream()
        activity.requestStarted(keepsRuntimeActive)
        val completed = AtomicBoolean()
        val complete: () -> Unit = {
            if (completed.compareAndSet(false, true)) {
                activity.requestCompleted(keepsRuntimeActive)
            }
        }

        try {
            filterChain.doFilter(request, response)
        } finally {
            if (request.isAsyncStarted) {
                try {
                    request.asyncContext.addListener(
                        object : AsyncListener {
                            override fun onComplete(event: AsyncEvent) = complete()

                            override fun onTimeout(event: AsyncEvent) = complete()

                            override fun onError(event: AsyncEvent) = complete()

                            override fun onStartAsync(event: AsyncEvent) {
                                event.asyncContext.addListener(this)
                            }
                        },
                    )
                } catch (_: IllegalStateException) {
                    complete()
                }
            } else {
                complete()
            }
        }
    }

    private fun HttpServletRequest.acceptsEventStream(): Boolean =
        getHeader("Accept")
            ?.split(',')
            ?.any { accepted -> accepted.substringBefore(';').trim() == MediaType.TEXT_EVENT_STREAM_VALUE }
            ?: false
}
