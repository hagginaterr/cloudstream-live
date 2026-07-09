package recloudstream.twitchlivefavorites

object TwitchHomeRefreshFocus {
    @Volatile
    private var focusFirstLiveNowOnNextBind: Boolean = false

    @Volatile
    private var suppressFocusReapplyUntilMs: Long = 0L

    private fun nowMs(): Long = System.currentTimeMillis()

    fun requestFocusFirstLiveNow() {
        if (isFocusReapplySuppressed()) {
            focusFirstLiveNowOnNextBind = false
            return
        }

        focusFirstLiveNowOnNextBind = true
    }

    fun suppressFocusReapplyBriefly(durationMs: Long = 2_500L) {
        suppressFocusReapplyUntilMs = maxOf(
            suppressFocusReapplyUntilMs,
            nowMs() + durationMs,
        )
        focusFirstLiveNowOnNextBind = false
    }

    fun suppressForMediaLaunch(durationMs: Long = 12_000L) {
        suppressFocusReapplyBriefly(durationMs)
    }

    fun clearFocusReapplySuppression() {
        suppressFocusReapplyUntilMs = 0L
        focusFirstLiveNowOnNextBind = false
    }

    fun isFocusReapplySuppressed(): Boolean {
        return nowMs() < suppressFocusReapplyUntilMs
    }

    fun consumeForRow(rowName: String): Boolean {
        if (!focusFirstLiveNowOnNextBind) return false

        if (isFocusReapplySuppressed()) {
            focusFirstLiveNowOnNextBind = false
            return false
        }

        if (!rowName.startsWith("Live Now", ignoreCase = true)) return false

        focusFirstLiveNowOnNextBind = false
        return true
    }
}