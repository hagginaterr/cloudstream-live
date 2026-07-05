package recloudstream.twitchlivefavorites

import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.utils.DataStoreHelper
import java.net.URLEncoder

object TwitchAccountAuth {
    // TwitchDeviceFlowPendingFixV2: keep QR login open while Twitch is waiting for phone approval.
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

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val expiresIn: Int,
        val interval: Int,
    )

    data class ImportResult(
        val importedCount: Int,
        val displayName: String?,
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

    fun isSignedIn(): Boolean = !getKey<String>(ACCESS_TOKEN_KEY).isNullOrBlank()

    fun displayName(): String? {
        return getKey<String>(USER_DISPLAY_KEY)
            ?: getKey<String>(USER_LOGIN_KEY)
    }

    fun userId(): String? {
        return getKey<String>(USER_ID_KEY)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    fun lastImportSummary(): String? {
        val count = getKey<Int>(LAST_IMPORT_COUNT_KEY) ?: return null
        return "$count followed channels imported"
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
        ).forEach { removeKey(it) }
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
        val imported = importFollowedChannels(token.access_token, user.id)
        setKey(LAST_IMPORT_COUNT_KEY, imported)
        setKey(LAST_IMPORT_AT_KEY, System.currentTimeMillis())
        runCatching { MainActivity.bookmarksUpdatedEvent(true) }
        runCatching { MainActivity.reloadLibraryEvent(true) }
        runCatching { MainActivity.reloadHomeEvent(true) }
        return ImportResult(imported, user.display_name.ifBlank { user.login })
    }

    suspend fun getValidAccessToken(): String? {
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
            token.access_token.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun String.isPendingDeviceAuth(): Boolean {
        return contains("authorization_pending") ||
                contains("slow_down") ||
                contains("pending")
    }
    private fun saveToken(token: TwitchTokenResponse) {
        if (token.access_token.isBlank()) throw IllegalStateException("Twitch returned an empty access token.")
        setKey(ACCESS_TOKEN_KEY, token.access_token)
        if (token.refresh_token.isNotBlank()) setKey(REFRESH_TOKEN_KEY, token.refresh_token)
        val expiresMs = maxOf(60L, token.expires_in) * 1000L
        setKey(EXPIRES_AT_KEY, System.currentTimeMillis() + expiresMs)
    }

    private fun saveUser(user: TwitchUser) {
        setKey(USER_ID_KEY, user.id)
        setKey(USER_LOGIN_KEY, user.login)
        setKey(USER_DISPLAY_KEY, user.display_name.ifBlank { user.login })
    }

    private suspend fun fetchAuthenticatedUser(accessToken: String): TwitchUser {
        val response = app.get(
            "$HELIX_BASE/users",
            headers = authHeaders(accessToken),
        ).parsed<TwitchUsersResponse>()
        return response.data.firstOrNull()
            ?: throw IllegalStateException("Twitch did not return the signed-in user.")
    }

    private suspend fun importFollowedChannels(accessToken: String, userId: String): Int {
        val followed = mutableListOf<TwitchFollowedChannel>()
        var cursor: String? = null
        var guard = 0

        do {
            val url = buildString {
                append("$HELIX_BASE/channels/followed?user_id=")
                append(encode(userId))
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

        followed.distinctBy { normalizeChannel(it.broadcaster_login) }
            .forEach { seedCloudStreamFavorite(it) }
        return followed.map { normalizeChannel(it.broadcaster_login) }.filter { it.isNotBlank() }.distinct().size
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

    private fun authHeaders(accessToken: String): Map<String, String> {
        return mapOf(
            "Authorization" to "Bearer $accessToken",
            "Client-Id" to TwitchCredentials.CLIENT_ID,
        )
    }

    private fun normalizeChannel(value: String?): String {
        return value.orEmpty()
            .trim()
            .trimEnd('/')
            .substringAfterLast("/")
            .substringBefore("?")
            .lowercase()
            .filter { it.isLetterOrDigit() || it == '_' }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}

