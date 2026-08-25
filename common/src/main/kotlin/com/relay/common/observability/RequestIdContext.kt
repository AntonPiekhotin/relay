package com.relay.common.observability

import org.slf4j.MDC

/**
 * Helpers for carrying the correlation context across a thread boundary.
 *
 * SLF4J's [MDC] is backed by a plain `ThreadLocal` and is **not** inheritable, so every hop onto
 * another thread starts with an empty MDC.
 */
object RequestIdContext {

    /**
     * Runs [body] with the given correlation context in the MDC, restoring exactly what was there
     * before — including absence. Nesting is therefore safe.
     */
    inline fun <T> with(
        requestId: String?,
        userId: String? = null,
        sessionId: String? = null,
        body: () -> T,
    ): T {
        val previous = MDC.getCopyOfContextMap()
        try {
            put(RequestId.MDC_REQUEST_ID, requestId)
            put(RequestId.MDC_USER_ID, userId)
            put(RequestId.MDC_SESSION_ID, sessionId)
            return body()
        } finally {
            if (previous == null) MDC.clear() else MDC.setContextMap(previous)
        }
    }

    /**
     * Snapshots the MDC now and re-applies it inside the returned [Runnable]. Use at the point a
     * task is *handed off*, not where it runs — the snapshot has to happen on the thread that
     * still has the context.
     */
    fun wrap(runnable: Runnable): Runnable {
        val captured = MDC.getCopyOfContextMap()
        return Runnable {
            val previous = MDC.getCopyOfContextMap()
            try {
                if (captured == null) MDC.clear() else MDC.setContextMap(captured)
                runnable.run()
            } finally {
                if (previous == null) MDC.clear() else MDC.setContextMap(previous)
            }
        }
    }

    /**
     * Snapshots the whole MDC now, for re-application on a thread that will not have it.
     *
     * Take the snapshot where the context still exists — at the point work is handed off — and call
     * [Snapshot.restoring] where it runs:
     *
     * ```
     * val mdc = RequestIdContext.capture()
     * kafkaTemplate.send(topic, key, payload)
     *     .whenComplete { _, ex -> mdc.restoring { logger.error("...", ex) } }
     * ```
     *
     * Preferred over `with(RequestId.current())` at such a seam because it carries every key, not
     * just the request id — so a gateway callback keeps the user and session too.
     */
    fun capture(): Snapshot = Snapshot(MDC.getCopyOfContextMap())

    /** An MDC snapshot. See [capture]. */
    class Snapshot internal constructor(private val captured: Map<String, String>?) {

        inline fun <T> restoring(body: () -> T): T {
            val previous = MDC.getCopyOfContextMap()
            try {
                apply()
                return body()
            } finally {
                if (previous == null) MDC.clear() else MDC.setContextMap(previous)
            }
        }

        /** Published only so the inline [restoring] above can reach it. */
        @PublishedApi
        internal fun apply() {
            if (captured == null) MDC.clear() else MDC.setContextMap(captured)
        }
    }

    /** Puts a key, or removes it when the value is null — never stores a literal "null". */
    fun put(key: String, value: String?) {
        if (value == null) MDC.remove(key) else MDC.put(key, value)
    }

    /** Clears only the keys this package owns, leaving anything else on the thread alone. */
    fun clear() {
        MDC.remove(RequestId.MDC_REQUEST_ID)
        MDC.remove(RequestId.MDC_USER_ID)
        MDC.remove(RequestId.MDC_SESSION_ID)
        MDC.remove(RequestId.MDC_KAFKA_TOPIC)
    }
}
