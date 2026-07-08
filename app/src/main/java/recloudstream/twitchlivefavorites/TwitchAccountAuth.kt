package recloudstream.twitchlivefavorites
import com.fasterxml.jackson.annotation.JsonProperty

import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.utils.DataStoreHelper
import java.net.URLEncoder
import com.lagradost.cloudstream3.mvvm.logError

object TwitchAccountAuth {
    private const val CLIENT_ID_MISSING = "Twitch client ID is missing from this build."
    private const val OAUTH_DEVICE_URL = "https://id.twitch.tv/oauth2/device"
    private const val OAUTH_TOKEN_URL = "https://id.twitch.tv/oauth2/token"
    private const val HELIX_BASE = "https://api.twitch.tv/helix"
    private const val SCOPE = "user:read:follows"

    private const val ACCESS_TOKEN_KEY = "twitch_user_access_token"
    private const val REFRESH_TOKEN_KEY = "twitch_user_refresh_token"
    private const val EXPIRES_AT_KEY = "twitch_user_token_expires_at"
    private const val USER_ID_KEY = "twitch_user_id"
    private const val USER_LOGIN_KEY = "twitch_user_login"
    private const val USER_DISPLAY_KEY = "twitch_user_display_name"
    private const val LAST_IMPORT_COUNT_KEY = "twitch_user_last_import_count"
    private const val LAST_IMPORT_AT_KEY = "twitch_user_last_import_at"
    private const val LAST_SYNC_REMOVED_COUNT_KEY = "twitch_user_last_sync_removed_count"
    private const val LAST_SYNC_FOLLOWED_COUNT_KEY = "twitch_user_last_sync_followed_count"
    private const val SYNC_ON_STARTUP_KEY = "twitch_user_sync_on_startup"
    private const val REMOVE_UNFOLLOWED_KEY = "twitch_user_remove_unfollowed"

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val expiresIn: Int,
        val interval: Int,
    )

    data class ImportResult(
        val importedCount: Int,
        @JsonProperty("display_name")
        val displayName: String?,
        val removedCount: Int = 0,
        val followedCount: Int = importedCount,
    )

    private data class TwitchDeviceCodeResponse(
        val device_code: String = "",
        val user_code: String = "",
        val verification_uri: String = "",
        val expires_in: Int = 0,
        val interval: Int = 5,
    )

    private data class TwitchTokenResponse(
        val access_token: String = "",
        val expires_in: Long = 0L,
        val refresh_token: String = "",
        val scope: List<String> = emptyList(),
        val token_type: String = "",
        val error: String = "",
        val message: String = "",
        val error_description: String = "",
        val status: Int = 0,
    )

    private data class TwitchUsersResponse(
        val data: List<TwitchUser> = emptyList(),
    )

    private data class TwitchUser(
        val id: String = "",
        val login: String = "",
        val display_name: String = "",
        val profile_image_url: String = "",
    )

    private data class TwitchFollowedChannelsResponse(
        val total: Int = 0,
        val data: List<TwitchFollowedChannel> = emptyList(),
        val pagination: TwitchPagination = TwitchPagination(),
    )

    private data class TwitchFollowedChannel(
        val broadcaster_id: String = "",
        val broadcaster_login: String = "",
        val broadcaster_name: String = "",
        val followed_at: String = "",
    )

    private data class TwitchPagination(
        val cursor: String? = null,
    )

        // BEGIN TwitchRestorableAccountBackupPatch
    private fun restoreAccountFromDeviceBackupIfNeeded(): Boolean {
        val restored = TwitchAccountRestoreStore.restore() ?: return false
        var changed = false

        fun restoreStringIfMissing(key: String, value: String?) {
            val current: String? = getKey(key)
            val clean = value?.trim()?.takeIf { it.isNotBlank() } ?: return
            if (current.isNullOrBlank()) {
                setKey(key, clean)
                changed = true
            }
        }

        fun restoreLongIfMissing(key: String, value: Long) {
            val current: Long = getKey(key) ?: 0L
            if (current <= 0L && value > 0L) {
                setKey(key, value)
                changed = true
            }
        }

        restoreStringIfMissing(ACCESS_TOKEN_KEY, restored.accessToken)
        restoreStringIfMissing(REFRESH_TOKEN_KEY, restored.refreshToken)
        restoreLongIfMissing(EXPIRES_AT_KEY, restored.expiresAt)
        restoreStringIfMissing(USER_ID_KEY, restored.userId)
        restoreStringIfMissing(USER_LOGIN_KEY, restored.userLogin)
        restoreStringIfMissing(USER_DISPLAY_KEY, restored.userDisplayName)

        return changed
    }private fun persistAccountToDeviceBackup() {
        TwitchAccountRestoreStore.save(
            accessToken = getKey<String>(ACCESS_TOKEN_KEY),
            refreshToken = getKey<String>(REFRESH_TOKEN_KEY),
            expiresAt = getKey<Long>(EXPIRES_AT_KEY) ?: 0L,
            userId = getKey<String>(USER_ID_KEY),
            userLogin = getKey<String>(USER_LOGIN_KEY),
            userDisplayName = getKey<String>(USER_DISPLAY_KEY),
        )
    }
    // END TwitchRestorableAccountBackupPatch

fun isSignedIn(): Boolean {
        restoreAccountFromDeviceBackupIfNeeded()
        return !getKey<String>(ACCESS_TOKEN_KEY).isNullOrBlank()
    }

    fun displayName(): String? {
        restoreAccountFromDeviceBackupIfNeeded()
        return getKey(USER_DISPLAY_KEY) ?: getKey(USER_LOGIN_KEY)
    }

    fun userId(): String? {
        restoreAccountFromDeviceBackupIfNeeded()
        return getKey<String>(USER_ID_KEY)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    suspend fun signedInUserId(): String? {
        restoreAccountFromDeviceBackupIfNeeded()

        userId()?.let { return it }

        val accessToken = getValidAccessToken()
            ?.trim()
            .orEmpty()

        if (accessToken.isBlank()) return null

        return runCatching {
            val user = fetchAuthenticatedUser(accessToken)
            saveUser(user)
            user.id.trim().takeIf { it.isNotBlank() }
        }.onFailure { error ->
            logError(error)
        }.getOrNull() ?: userId()
    }

    suspend fun forceRefreshAccessToken(): String? {
        restoreAccountFromDeviceBackupIfNeeded()

        val refreshToken = getKey<String>(REFRESH_TOKEN_KEY)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return getKey<String>(ACCESS_TOKEN_KEY)
                ?.trim()
                ?.takeIf { it.isNotBlank() }

        return refreshAccessToken(refreshToken)
            ?: getKey<String>(ACCESS_TOKEN_KEY)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
    }

fun lastImportSummary()(): String? {
        val count = getKey<Int>(LAST_IMPORT_COUNT_KEY) ?: return null
        val removed = getKey<Int>(LAST_SYNC_REMOVED_COUNT_KEY) ?: 0
        return if (removed > 0) {
            "$count followed channels synced, $removed removed"
        } else {
            "$count followed channels synced"
        }
    }

    fun isSyncOnStartupEnabled(): Boolean {
        return getKey<Boolean>(SYNC_ON_STARTUP_KEY) ?: true
    }

    fun setSyncOnStartupEnabled(enabled: Boolean) {
        setKey(SYNC_ON_STARTUP_KEY, enabled)
    }

    fun isRemoveUnfollowedEnabled(): Boolean {
        return getKey<Boolean>(REMOVE_UNFOLLOWED_KEY) ?: false
    }

    fun setRemoveUnfollowedEnabled(enabled: Boolean) {
        setKey(REMOVE_UNFOLLOWED_KEY, enabled)
    }

    fun clearAccount() {
        listOf(
            ACCESS_TOKEN_KEY,
            REFRESH_TOKEN_KEY,
            EXPIRES_AT_KEY,
            USER_ID_KEY,
            USER_LOGIN_KEY,
            USER_DISPLAY_KEY,
            LAST_IMPORT_COUNT_KEY,
            LAST_IMPORT_AT_KEY,
            LAST_SYNC_REMOVED_COUNT_KEY,
            LAST_SYNC_FOLLOWED_COUNT_KEY,
        ).forEach { removeKey(it) }

        TwitchAccountRestoreStore.clear()
    }

    suspend fun requestDeviceCode(): DeviceCode {
        val clientId = TwitchCredentials.CLIENT_ID.trim()
        if (clientId.isBlank()) throw IllegalStateException(CLIENT_ID_MISSING)

        val response = app.post(
            OAUTH_DEVICE_URL,
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            data = mapOf(
                "client_id" to clientId,
                "scopes" to SCOPE,
            ),
        ).parsed<TwitchDeviceCodeResponse>()

        val deviceCode = response.device_code.trim()
        val userCode = response.user_code.trim()
        val verificationUri = response.verification_uri.trim()
        if (deviceCode.isBlank() || userCode.isBlank() || verificationUri.isBlank()) {
            throw IllegalStateException("Twitch did not return a valid device sign-in code.")
        }

        return DeviceCode(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUri = verificationUri,
            expiresIn = response.expires_in.takeIf { it > 0 } ?: 1800,
            interval = response.interval.takeIf { it > 0 } ?: 5,
        )
    }

    suspend fun pollTokenAndImport(deviceCode: DeviceCode): ImportResult? {
        val clientId = TwitchCredentials.CLIENT_ID.trim()
        if (clientId.isBlank()) throw IllegalStateException(CLIENT_ID_MISSING)

        val token = try {
            app.post(
                OAUTH_TOKEN_URL,
                headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                data = mapOf(
                    "client_id" to clientId,
                    "scopes" to SCOPE,
                    "device_code" to deviceCode.deviceCode,
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                ),
            ).parsed<TwitchTokenResponse>()
        } catch (t: Throwable) {
            val message = (t.message ?: "").lowercase()
            if (message.isPendingDeviceAuth()) {
                return null
            }
            throw t
        }

        val pendingError = token.error
            .ifBlank { token.message }
            .ifBlank { token.error_description }
            .lowercase()

        if (token.access_token.isBlank() && pendingError.isPendingDeviceAuth()) {
            return null
        }

        if (token.access_token.isBlank()) {
            val detail = pendingError.ifBlank { "empty token" }
            throw IllegalStateException("Twitch sign in failed: $detail")
        }

        saveToken(token)
        val user = fetchAuthenticatedUser(token.access_token)
        saveUser(user)
        return syncFollowedFavoritesWith(
            accessToken = token.access_token,
            user = user,
            removeUnfollowed = isRemoveUnfollowedEnabled(),
        )
    }

    suspend fun getValidAccessToken(): String? {
        restoreAccountFromDeviceBackupIfNeeded()

        val savedAccessToken = getKey<String>(ACCESS_TOKEN_KEY)?.trim().orEmpty()
        if (savedAccessToken.isBlank()) return null

        val expiresAt = getKey<Long>(EXPIRES_AT_KEY) ?: 0L
        if (System.currentTimeMillis() < expiresAt - 60_000L) {
            return savedAccessToken
        }

        val refreshToken = getKey<String>(REFRESH_TOKEN_KEY)?.trim().orEmpty()
        if (refreshToken.isBlank()) return savedAccessToken

        return refreshAccessToken(refreshToken) ?: savedAccessToken
    }

    suspend fun syncFollowedFavorites(
        removeUnfollowed: Boolean = isRemoveUnfollowedEnabled(),
    ): ImportResult {
        val accessToken = getValidAccessToken()
            ?: throw IllegalStateException("Sign in to Twitch first.")

        val savedUserId = userId()
        val user = if (savedUserId.isNullOrBlank()) {
            fetchAuthenticatedUser(accessToken).also { saveUser(it) }
        } else {
            TwitchUser(
                id = savedUserId,
                login = getKey<String>(USER_LOGIN_KEY).orEmpty(),
                display_name = displayName().orEmpty(),
            )
        }

        return syncFollowedFavoritesWith(
            accessToken = accessToken,
            user = user,
            removeUnfollowed = removeUnfollowed,
        )
    }

    private suspend fun syncFollowedFavoritesWith(
        accessToken: String,
        user: TwitchUser,
        removeUnfollowed: Boolean,
    ): ImportResult {
        val followed = fetchFollowedChannels(accessToken, user.id)
        val uniqueFollowed = followed
            .distinctBy { normalizeChannel(it.broadcaster_login) }
            .filter { normalizeChannel(it.broadcaster_login).isNotBlank() }

        uniqueFollowed.forEach { seedCloudStreamFavorite(it) }

        val followedNames = uniqueFollowed
            .map { normalizeChannel(it.broadcaster_login) }
            .filter { it.isNotBlank() }
            .toSet()

        val removed = if (removeUnfollowed) {
            removeUnfollowedTwitchFavorites(followedNames)
        } else {
            0
        }

        setKey(LAST_IMPORT_COUNT_KEY, followedNames.size)
        setKey(LAST_SYNC_FOLLOWED_COUNT_KEY, followedNames.size)
        setKey(LAST_SYNC_REMOVED_COUNT_KEY, removed)
        setKey(LAST_IMPORT_AT_KEY, System.currentTimeMillis())

        runCatching { MainActivity.bookmarksUpdatedEvent(true) }
        runCatching { MainActivity.reloadLibraryEvent(true) }
        runCatching { MainActivity.reloadHomeEvent(true) }

        return ImportResult(
            importedCount = followedNames.size,
            displayName = user.display_name.ifBlank { user.login },
            removedCount = removed,
            followedCount = followedNames.size,
        )
    }

    private suspend fun refreshAccessToken(refreshToken: String): String? {
        val clientId = TwitchCredentials.CLIENT_ID.trim()
        if (clientId.isBlank()) return null

        return runCatching {
            val token = app.post(
                OAUTH_TOKEN_URL,
                headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                data = mapOf(
                    "client_id" to clientId,
                    "grant_type" to "refresh_token",
                    "refresh_token" to refreshToken,
                ),
            ).parsed<TwitchTokenResponse>()
            saveToken(token)
            token.access_token.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun saveToken(token: TwitchTokenResponse) {
        if (token.access_token.isBlank()) throw IllegalStateException("Twitch returned an empty access token.")

        setKey(ACCESS_TOKEN_KEY, token.access_token)

        if (token.refresh_token.isNotBlank()) {
            setKey(REFRESH_TOKEN_KEY, token.refresh_token)
        }

        val expiresAt = System.currentTimeMillis() + maxOf(60L, token.expires_in) * 1000L
        setKey(EXPIRES_AT_KEY, expiresAt)

        persistAccountToDeviceBackup()
    }

    private fun saveUser(user: TwitchUser) {
        setKey(USER_ID_KEY, user.id)
        setKey(USER_LOGIN_KEY, user.login)
        setKey(USER_DISPLAY_KEY, user.display_name.ifBlank { user.login })

        persistAccountToDeviceBackup()
    }

    private suspend fun fetchAuthenticatedUser(accessToken: String): TwitchUser {
        val response = app.get(
            "$HELIX_BASE/users",
            headers = authHeaders(accessToken),
        ).parsed<TwitchUsersResponse>()
        return response.data.firstOrNull()
            ?: throw IllegalStateException("Twitch did not return the signed-in user.")
    }

    private suspend fun fetchFollowedChannels(accessToken: String, userId: String): List<TwitchFollowedChannel> {
        val cleanUserId = userId.trim()
        if (cleanUserId.isBlank()) throw IllegalStateException("Twitch user id is missing.")

        val followed = mutableListOf<TwitchFollowedChannel>()
        var cursor: String? = null
        var guard = 0

        do {
            val url = buildString {
                append("$HELIX_BASE/channels/followed?user_id=")
                append(encode(cleanUserId))
                append("&first=100")
                if (!cursor.isNullOrBlank()) {
                    append("&after=")
                    append(encode(cursor.orEmpty()))
                }
            }
            val response = app.get(url, headers = authHeaders(accessToken))
                .parsed<TwitchFollowedChannelsResponse>()
            followed.addAll(response.data)
            cursor = response.pagination.cursor?.takeIf { it.isNotBlank() }
            guard++
        } while (!cursor.isNullOrBlank() && guard < 200)

        return followed
    }

    private fun removeUnfollowedTwitchFavorites(followedChannels: Set<String>): Int {
        var removed = 0

        DataStoreHelper.getAllFavorites().forEach { favorite ->
            val favoriteChannel = favorite.toTwitchChannelOrNull()
            if (favoriteChannel.isNotBlank() && favoriteChannel !in followedChannels) {
                DataStoreHelper.removeFavoritesData(favorite.id)
                removed++
            }
        }

        return removed
    }

    private fun DataStoreHelper.FavoritesData.toTwitchChannelOrNull(): String {
        val api = apiName.lowercase()
        val normalizedUrl = url.lowercase()
        val isTwitchFavorite = type == TvType.Live &&
                (api == "twitch" || api == "twitch live favorites api" || normalizedUrl.contains("twitch.tv/"))

        if (!isTwitchFavorite) return ""
        if (normalizedUrl.contains("/videos/") || normalizedUrl.contains("/clip/") || normalizedUrl.contains("/clips/")) return ""
        if (normalizedUrl.contains("cloudstream_twitch_vod") || normalizedUrl.contains("cloudstream_twitch_clip")) return ""

        val fromUrl = normalizeChannel(url)
        val fromName = normalizeChannel(name)

        return when {
            fromUrl.isNotBlank() && fromUrl != "twitch" -> fromUrl
            fromName.isNotBlank() && fromName != "twitch" -> fromName
            else -> ""
        }
    }

    private fun seedCloudStreamFavorite(channel: TwitchFollowedChannel) {
        val normalized = normalizeChannel(channel.broadcaster_login)
        if (normalized.isBlank()) return
        val id = normalized.hashCode()
        val current = DataStoreHelper.getFavoritesData(id)
        val now = System.currentTimeMillis()
        val displayName = channel.broadcaster_name.ifBlank { normalized }
        DataStoreHelper.setFavoritesData(
            id,
            DataStoreHelper.FavoritesData(
                favoritesTime = current?.favoritesTime ?: now,
                id = id,
                latestUpdatedTime = now,
                name = displayName,
                url = "https://twitch.tv/$normalized",
                apiName = "Twitch",
                type = TvType.Live,
                posterUrl = current?.posterUrl,
                year = null,
                plot = current?.plot ?: "Twitch streamer followed by your signed-in Twitch account",
                tags = current?.tags ?: listOf("Twitch"),
            ),
        )
    }

        // BEGIN TwitchPersistentFavoritesPatch
    @Volatile
    private var followedFavoriteRestoreRunning = false

    @Volatile
    private var lastFollowedFavoriteRestoreAccount: String? = null

    private fun twitchImportedFavoriteCount(): Int {
        return DataStoreHelper.getAllFavorites().count { item ->
            item.apiName.equals("Twitch", ignoreCase = true) ||
                item.url.contains("twitch.tv/", ignoreCase = true)
        }
    }

    private fun notifyTwitchFavoritesChanged() {
        runCatching { MainActivity.bookmarksUpdatedEvent(true) }
        runCatching { MainActivity.reloadLibraryEvent(true) }
        runCatching { MainActivity.reloadHomeEvent(true) }
    }

    suspend fun ensureFollowedFavoritesPresent(force: Boolean = false): Boolean {
        if (!isSignedIn()) return false

        val accountKey = DataStoreHelper.currentAccount
        val currentCount = twitchImportedFavoriteCount()

        if (!force && currentCount > 0) {
            lastFollowedFavoriteRestoreAccount = accountKey
            return false
        }

        if (!force && followedFavoriteRestoreRunning) return false
        if (!force && lastFollowedFavoriteRestoreAccount == accountKey && currentCount > 0) return false

        followedFavoriteRestoreRunning = true

        return try {
            val result = syncFollowedFavorites()
            val restoredCount = twitchImportedFavoriteCount()
            val changed = restoredCount > currentCount || result.followedCount > 0

            if (changed) {
                lastFollowedFavoriteRestoreAccount = accountKey
                notifyTwitchFavoritesChanged()
            }

            changed
        } catch (t: Throwable) {
            logError(t)
            false
        } finally {
            followedFavoriteRestoreRunning = false
        }
    }
    // END TwitchPersistentFavoritesPatch
private fun authHeaders(accessToken: String): Map<String, String> {
        return mapOf(
            "Authorization" to "Bearer $accessToken",
            "Client-Id" to TwitchCredentials.CLIENT_ID,
        )
    }

    private fun String.isPendingDeviceAuth(): Boolean {
        return contains("authorization_pending") ||
                contains("slow_down") ||
                contains("pending")
    }

    private fun normalizeChannel(value: String?): String {
        return value.orEmpty()
            .trim()
            .trimEnd('/')
            .substringAfter("twitch.tv/", missingDelimiterValue = value.orEmpty())
            .substringAfterLast("/")
            .substringBefore("?")
            .lowercase()
            .filter { it.isLetterOrDigit() || it == '_' }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}