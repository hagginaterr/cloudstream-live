package recloudstream.twitchlivefavorites

import android.os.SystemClock

/**
 * One-shot signal used when Twitch auth/follow state changes.
 *
 * The normal Live Now row keeps a conservative cache to protect the Twitch API.
 * A successful sign-in/sync is different: the next followed Live Now render
 * should bypass that stale cache exactly once.
 */
object TwitchLiveNowImmediateRefresh {
    // TwitchLiveNowResumeRefreshPatch: keep refresh age across Home lifecycle stops.
    const val REFRESH_INTERVAL_MS: Long = 5L * 60L * 1000L
    private const val MIN_ELAPSED_TIMESTAMP_MS: Long = 1L

    private val liveNowCheckLock = Any()

    @Volatile
    private var lastLiveNowCheckElapsedMs: Long = 0L

    @Volatile
    private var requestedUserId: String = ""

    @Volatile
    private var requestVersion: Long = 0L

    @Volatile
    private var consumedVersion: Long = 0L

    fun markLiveNowChecked(nowElapsedMs: Long = SystemClock.elapsedRealtime()) {
        synchronized(liveNowCheckLock) {
            lastLiveNowCheckElapsedMs = nowElapsedMs.coerceAtLeast(MIN_ELAPSED_TIMESTAMP_MS)
        }
    }

    fun tryMarkLiveNowCheckedIfStale(
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
    ): Boolean {
        synchronized(liveNowCheckLock) {
            val lastChecked = lastLiveNowCheckElapsedMs
            val ageMs = if (lastChecked <= 0L || nowElapsedMs < lastChecked) {
                REFRESH_INTERVAL_MS
            } else {
                nowElapsedMs - lastChecked
            }
            if (ageMs < REFRESH_INTERVAL_MS) return false

            lastLiveNowCheckElapsedMs = nowElapsedMs.coerceAtLeast(MIN_ELAPSED_TIMESTAMP_MS)
            return true
        }
    }

    fun millisUntilLiveNowCheckDue(
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
    ): Long {
        val lastChecked = lastLiveNowCheckElapsedMs
        if (lastChecked <= 0L || nowElapsedMs < lastChecked) return 0L
        return (REFRESH_INTERVAL_MS - (nowElapsedMs - lastChecked)).coerceAtLeast(0L)
    }

    fun requestForUser(userId: String?) {
        requestedUserId = userId
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "*"

        requestVersion = System.currentTimeMillis().coerceAtLeast(1L)
    }

    fun consumeForUser(userId: String): Boolean {
        val version = requestVersion
        if (version <= 0L || version <= consumedVersion) return false

        val requested = requestedUserId
        val cleanUserId = userId.trim()

        if (requested.isNotBlank() && requested != "*" && requested != cleanUserId) {
            return false
        }

        consumedVersion = version
        return true
    }
}