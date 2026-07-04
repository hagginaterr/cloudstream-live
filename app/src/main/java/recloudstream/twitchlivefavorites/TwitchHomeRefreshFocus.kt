package recloudstream.twitchlivefavorites

object TwitchHomeRefreshFocus {
    @Volatile
    private var focusFirstLiveNowOnNextBind: Boolean = false

    fun requestFocusFirstLiveNow() {
        focusFirstLiveNowOnNextBind = true
    }

    fun consumeForRow(rowName: String): Boolean {
        if (!focusFirstLiveNowOnNextBind) return false
        if (!rowName.startsWith("Live Now", ignoreCase = true)) return false

        focusFirstLiveNowOnNextBind = false
        return true
    }
}