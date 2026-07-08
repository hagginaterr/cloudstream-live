package recloudstream.twitchlivefavorites

/**
 * One-shot signal used when Twitch auth/follow state changes.
 *
 * The normal Live Now row keeps a conservative cache to protect the Twitch API.
 * A successful sign-in/sync is different: the next followed Live Now render
 * should bypass that stale cache exactly once.
 */
object TwitchLiveNowImmediateRefresh {
    const val REFRESH_INTERVAL_MS: Long = 5L * 60L * 1000L

    @Volatile
    private var requestedUserId: String = ""

    @Volatile
    private var requestVersion: Long = 0L

    @Volatile
    private var consumedVersion: Long = 0L

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