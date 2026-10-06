package com.livevip.app.engine

/** Controlled exponential backoff: 1s, 2s, 4s, 8s, 15s then give up with ERROR. */
class ReconnectController {
    private val delaysMs = longArrayOf(1000, 2000, 4000, 8000, 15000)

    var attempt: Int = 0
        private set
    var totalReconnects: Int = 0
        private set

    fun nextDelayMs(): Long? {
        if (attempt >= delaysMs.size) return null
        return delaysMs[attempt++]
    }

    fun onReconnected() {
        if (attempt > 0) totalReconnects++
        attempt = 0
    }

    fun reset() {
        attempt = 0
    }

    fun exhausted(): Boolean = attempt >= delaysMs.size
}
