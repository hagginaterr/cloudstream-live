package recloudstream.twitchlivefavorites

import android.content.Context
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.mvvm.logError

object TwitchAccountRestoreStore {
    private const val PREFS_NAME = "twitch_account_restore"

    private const val BACKUP_VERSION_KEY = "backup_version"
    private const val ACCESS_TOKEN_KEY = "twitch_user_access_token"
    private const val REFRESH_TOKEN_KEY = "twitch_user_refresh_token"
    private const val EXPIRES_AT_KEY = "twitch_user_token_expires_at"
    private const val USER_ID_KEY = "twitch_user_id"
    private const val USER_LOGIN_KEY = "twitch_user_login"
    private const val USER_DISPLAY_KEY = "twitch_user_display_name"

    data class RestoredAccount(
        val accessToken: String,
        val refreshToken: String?,
        val expiresAt: Long,
        val userId: String?,
        val userLogin: String?,
        val userDisplayName: String?,
    )

    private fun prefs() = CloudStreamApp.context
        ?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(
        accessToken: String?,
        refreshToken: String?,
        expiresAt: Long,
        userId: String?,
        userLogin: String?,
        userDisplayName: String?,
    ): Boolean {
        val cleanAccessToken = accessToken?.trim()?.takeIf { it.isNotBlank() } ?: return false
        val preferences = prefs() ?: return false

        return runCatching {
            val editor = preferences.edit()
                .putInt(BACKUP_VERSION_KEY, 1)
                .putString(ACCESS_TOKEN_KEY, cleanAccessToken)
                .putLong(EXPIRES_AT_KEY, expiresAt)

            refreshToken?.trim()?.takeIf { it.isNotBlank() }?.let {
                editor.putString(REFRESH_TOKEN_KEY, it)
            } ?: editor.remove(REFRESH_TOKEN_KEY)

            userId?.trim()?.takeIf { it.isNotBlank() }?.let {
                editor.putString(USER_ID_KEY, it)
            } ?: editor.remove(USER_ID_KEY)

            userLogin?.trim()?.takeIf { it.isNotBlank() }?.let {
                editor.putString(USER_LOGIN_KEY, it)
            } ?: editor.remove(USER_LOGIN_KEY)

            userDisplayName?.trim()?.takeIf { it.isNotBlank() }?.let {
                editor.putString(USER_DISPLAY_KEY, it)
            } ?: editor.remove(USER_DISPLAY_KEY)

            editor.apply()
            true
        }.onFailure { error ->
            logError(error)
        }.getOrDefault(false)
    }

    fun restore(): RestoredAccount? {
        val preferences = prefs() ?: return null

        return runCatching {
            val accessToken = preferences.getString(ACCESS_TOKEN_KEY, null)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return null

            RestoredAccount(
                accessToken = accessToken,
                refreshToken = preferences.getString(REFRESH_TOKEN_KEY, null)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() },
                expiresAt = preferences.getLong(EXPIRES_AT_KEY, 0L),
                userId = preferences.getString(USER_ID_KEY, null)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() },
                userLogin = preferences.getString(USER_LOGIN_KEY, null)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() },
                userDisplayName = preferences.getString(USER_DISPLAY_KEY, null)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() },
            )
        }.onFailure { error ->
            logError(error)
        }.getOrNull()
    }

    fun clear() {
        runCatching {
            prefs()?.edit()?.clear()?.apply()
        }.onFailure { error ->
            logError(error)
        }
    }
}