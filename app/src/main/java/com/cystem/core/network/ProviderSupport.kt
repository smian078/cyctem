package com.cystem.core.network

import kotlinx.coroutines.delay
import java.io.IOException

class ProviderException(
    val provider: String,
    val messageForUser: String,
    val retryable: Boolean = false,
    val httpStatus: Int? = null,
    val retryAfterMs: Long? = null,
) : IOException(messageForUser)

class RetryPolicy(
    private val maxAttempts: Int = 3,
    private val initialBackoffMs: Long = 500L,
    private val maxBackoffMs: Long = 4_000L,
) {
    init {
        require(maxAttempts >= 1)
    }

    suspend fun <T> execute(block: suspend () -> T): T {
        var attempt = 1
        var lastFailure: Throwable? = null

        while (attempt <= maxAttempts) {
            try {
                return block()
            } catch (error: ProviderException) {
                lastFailure = error
                if (!error.retryable || attempt == maxAttempts) throw error

                val backoff = error.retryAfterMs
                    ?: (initialBackoffMs shl (attempt - 1)).coerceAtMost(maxBackoffMs)
                delay(backoff)
            } catch (error: IOException) {
                lastFailure = error
                if (attempt == maxAttempts) throw error
                delay((initialBackoffMs shl (attempt - 1)).coerceAtMost(maxBackoffMs))
            }

            attempt++
        }

        throw lastFailure ?: IllegalStateException("Retry policy ended without a result")
    }
}
