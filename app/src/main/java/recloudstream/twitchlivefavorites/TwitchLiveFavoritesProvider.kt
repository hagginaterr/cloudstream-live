package recloudstream.twitchlivefavorites
import com.fasterxml.jackson.annotation.JsonProperty

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LiveSearchResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLDecoder
import java.net.URLEncoder

class TwitchApiLiveFavoritesProvider : MainAPI() {
    override var mainUrl = "https://twitch.tv"
    override var name = "Twitch"
    private val legacyProviderNames = setOf("twitch live favorites api")
    private val starterFavoriteChannels = listOf("zfg247", "liam", "monstercat", "nasa")
    private val startupFavoriteChannels = listOf("zfg247", "liam")
    private val startupFavoriteSeedKey = "twitch_startup_favorites_seeded_v2"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "uni"
    override val hasMainPage = true
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 0L

    private val liveFavoritesNowName = "Live Now"
    private val isHorizontal = true

    private val actionMarker = "__twitch_live_favorites_api_v35_action__"
    private val legacyApiActionMarker = "__twitch_live_favorites_api_v34_action__"
    private val legacyApiActionMarkerV33 = "__twitch_live_favorites_api_v33_action__"
    private val olderApiActionMarker = "__twitch_live_favorites_api_v32_action__"
    private val oldestApiActionMarker = "__twitch_live_favorites_api_v31_action__"
    private val legacyActionMarker = "__twitch_live_favorites_action__"
    private val actionBase = "$mainUrl/$actionMarker"
    private val statusPrefix = "$actionBase/status/"


    private val helixBase = "https://api.twitch.tv/helix"
    private val oauthTokenUrl = "https://id.twitch.tv/oauth2/token"
    private val twitchVodMarker = "cloudstream_twitch_vod=1"
    private val twitchWebClientId = "kimne78kx3ncx6brgo4mv6wki5h1ko"
    private val twitchWebUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
    private val twitchGqlUrl = "https://gql.twitch.tv/gql"
    private val liveCacheTtlMs = 5 * 60 * 1000L
    private val failedApiRetryDelayMs = 60 * 1000L

    private var cachedAccessToken: String? = null
    private var cachedAccessTokenExpiresAtMs: Long = 0L
    private var cachedLiveKey: String = ""
    private var cachedLiveExpiresAtMs: Long = 0L
    private var cachedLiveFavorites: List<FavoriteChannel> = emptyList()
    private var cachedLiveUpdatedAtMs: Long = 0L
private var cachedRecentTopClipsKey: String = ""
private var cachedRecentTopClipsExpiresAtMs: Long = 0L
private var cachedRecentTopClips: List<SearchResponse> = emptyList()
    private var rateLimitedUntilMs: Long = 0L
    private var lastTwitchApiError: String? = null

    override val mainPage = mainPageOf(
        "$actionBase/live" to liveFavoritesNowName,
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // FollowingLiveNowPatch: when signed in, the Live Now row comes directly
        // from Twitch's streams/followed endpoint instead of the local favorites list.
        // NewFromFollowedChannelsHomePatch: also add fast-capped media rows from followed channels.
        ensureStartupFavoritesSaved()
        val signedInUserId = TwitchAccountAuth.signedInUserId()
        val useSignedInFollows = !signedInUserId.isNullOrBlank()
        val savedFavorites = getSavedFavoriteChannels()
        val usingStarterFavorites = savedFavorites.isEmpty()
        val favorites = if (usingStarterFavorites) starterFavoriteChannels else savedFavorites
        refreshCloudStreamFavoritePosters(favorites)

        if (useSignedInFollows) {
            val liveFollowed = fetchLiveFollowedStreams(signedInUserId.orEmpty())
            maybeScheduleAutoRefresh("followed:${signedInUserId.orEmpty()}")

            val liveItems = when {
                !hasTwitchCredentials() -> listOf(setupRequiredCard())
                liveFollowed == null -> listOf(apiErrorCard(lastTwitchApiError.orEmpty()))
                liveFollowed.isNotEmpty() -> liveFollowed.map { it.toChannelCard(showOfflineLabel = false, directPlay = true) }
                else -> listOf(statusCard("No followed Twitch channels are live right now", "no-followed-live"))
            }

            val rows = mutableListOf<HomePageList>(
                HomePageList(liveFavoritesRowTitle(), liveItems, isHorizontalImages = isHorizontal),
            )
            rows.addAll(fetchFollowedHomeMediaRows(signedInUserId.orEmpty()))
            return newHomePageResponse(rows, hasNext = false)
        }

        val items = when {
            !hasTwitchCredentials() -> listOf(setupRequiredCard())
            favorites.isEmpty() -> listOf(statusCard("Search Twitch to add favorites", "no-favorites"))
            else -> {
                val liveFavorites = fetchLiveFavoriteChannels(favorites)
                maybeScheduleAutoRefresh(favoritesCacheKey(favorites))

                when {
                    liveFavorites == null -> listOf(apiErrorCard(lastTwitchApiError.orEmpty()))
                    liveFavorites.isNotEmpty() -> liveFavorites.map { it.toChannelCard(showOfflineLabel = false, directPlay = true) }
                    usingStarterFavorites -> {
                            val starterUsers = fetchUsers(favorites)
                            favorites.map { channel ->
                                val normalized = normalizeChannel(channel)
                                val user = starterUsers[normalized]
                                val favorite = if (user != null) {
                                    favoriteFromApi(normalized, user, stream = null)
                                } else {
                                    fallbackChannel(normalized)
                                }
                                favorite.toChannelCard(showOfflineLabel = true, directPlay = true)
                            }
                        }
                    else -> listOf(statusCard("No saved favorites are live right now", "no-favorites"))
                }
            }
        }

        return singleHomeResponse(liveFavoritesRowTitle(), items, hasNext = false)
    }

    private fun singleHomeResponse(
        name: String,
        items: List<SearchResponse>,
        hasNext: Boolean,
    ): HomePageResponse {
        return newHomePageResponse(
            listOf(HomePageList(name, items, isHorizontalImages = isHorizontal)),
            hasNext = hasNext,
        )
    }

    private data class ChannelSummary(
        val channel: String,
        @JsonProperty("display_name")
        val displayName: String,
        val image: String? = null,
        val language: String? = null,
        val isLive: Boolean = false,
        val title: String? = null,
    )

    private data class FavoriteChannel(
        val channel: String,
        @JsonProperty("display_name")
        val displayName: String,
        val image: String?,
        val poster: String?,
        val isLive: Boolean,
        val language: String?,
        val rank: Int?,
        val description: String?,
        @JsonProperty("game_name")
        val gameName: String? = null,
        val viewerCount: Int? = null,
        @JsonProperty("user_id")
        val userId: String? = null,
    )

    private data class TwitchTokenResponse(
        val access_token: String = "",
        val expires_in: Long = 0L,
        val token_type: String = "",
    )

    private data class TwitchUsersResponse(
        val data: List<TwitchUser> = emptyList(),
    )

    private data class TwitchUser(
        val id: String = "",
        val login: String = "",
        val display_name: String = "",
        val description: String = "",
        val profile_image_url: String = "",
        val offline_image_url: String = "",
    )

    private data class TwitchStreamsResponse(
        val data: List<TwitchStream> = emptyList(),
        val pagination: TwitchPagination = TwitchPagination(),
    )

    private data class TwitchPagination(
        val cursor: String? = null,
    )

    private data class TwitchStream(
        val id: String = "",
        val user_id: String = "",
        val user_login: String = "",
        val user_name: String = "",
        val game_id: String = "",
        val game_name: String = "",
        val type: String = "",
        val title: String = "",
        val viewer_count: Int = 0,
        val started_at: String = "",
        val language: String = "",
        val thumbnail_url: String = "",
    )

    private data class TwitchFollowedChannelsResponse(
        val data: List<TwitchFollowedChannel> = emptyList(),
        val pagination: TwitchPagination = TwitchPagination(),
    )

    private data class TwitchFollowedChannel(
        val broadcaster_id: String = "",
        val broadcaster_login: String = "",
        val broadcaster_name: String = "",
        val followed_at: String = "",
    )
        private data class TwitchVideosResponse(
        val data: List<TwitchVideo> = emptyList(),
    )

    private data class TwitchVideo(
        val id: String = "",
        val user_id: String = "",
        val user_login: String = "",
        val user_name: String = "",
        val title: String = "",
        val description: String = "",
        val created_at: String = "",
        val published_at: String = "",
        val url: String = "",
        val thumbnail_url: String = "",
        val viewable: String = "",
        val view_count: Int = 0,
        val language: String = "",
        val type: String = "",
        val duration: String = "",
    )
    private data class TwitchClipsResponse(
        val data: List<TwitchClip> = emptyList(),
    )

    private data class TwitchClip(
        val id: String = "",
        val url: String = "",
        val embed_url: String = "",
        val broadcaster_id: String = "",
        val broadcaster_name: String = "",
        val creator_id: String = "",
        val creator_name: String = "",
        val video_id: String = "",
        val game_id: String = "",
        val language: String = "",
        val title: String = "",
        val view_count: Int = 0,
        val created_at: String = "",
        val thumbnail_url: String = "",
        val duration: Double = 0.0,
    )

    private data class TwitchGamesResponse(
        val data: List<TwitchGame> = emptyList(),
    )

    private data class TwitchGame(
        val id: String = "",
        val name: String = "",
        val box_art_url: String = "",
    )

    private data class TwitchFollowedClipHomeItem(
        val clip: TwitchClip,
        val channelDisplayName: String,
        val channelLogin: String,
        val channelAvatar: String?,
    )private data class TwitchSearchResponse(
        val data: List<TwitchSearchChannel> = emptyList(),
    )

    private data class TwitchSearchChannel(
        val broadcaster_language: String = "",
        val broadcaster_login: String = "",
        val display_name: String = "",
        val game_id: String = "",
        val game_name: String = "",
        val id: String = "",
        val is_live: Boolean = false,
        val thumbnail_url: String = "",
        val title: String = "",
        val started_at: String = "",
    )

        private data class TwitchPlaybackAccessTokenResponse(
        val data: TwitchPlaybackAccessTokenData? = null,
    )

    private data class TwitchPlaybackAccessTokenData(
        val videoPlaybackAccessToken: TwitchPlaybackAccessToken? = null,
    )

    private data class TwitchPlaybackAccessToken(
        val value: String = "",
        val signature: String = "",
    )

    private data class TwitchGqlVideoMetadataResponse(
        val data: TwitchGqlVideoMetadataData? = null,
    )

    private data class TwitchGqlVideoMetadataData(
        val video: TwitchGqlVideoMetadata? = null,
    )

    private data class TwitchGqlVideoMetadata(
        val game: TwitchGqlGame? = null,
    )

    private data class TwitchGqlGame(
        @JsonProperty("display_name")
        val displayName: String? = null,
        val name: String? = null,
    )
    private data class TwitchGqlClipPlaybackResponse(
        val data: TwitchGqlClipPlaybackData? = null,
    )

    private data class TwitchGqlClipPlaybackData(
        val clip: TwitchGqlClipPlaybackClip? = null,
    )

    private data class TwitchGqlClipPlaybackClip(
        val title: String? = null,
            val playbackAccessToken: TwitchPlaybackAccessToken? = null,
val videoQualities: List<TwitchGqlClipQuality> = emptyList(),
    )
private data class TwitchGqlClipQuality(
        val frameRate: Double? = null,
        val quality: String? = null,
        val sourceURL: String? = null,
    )

private data class ApiResponse(
        val success: Boolean = false,
        val urls: Map<String, String>? = null,
    )

    /**
     * Best-effort read of existing CloudStream favorites created by the normal Twitch provider
     * or by this API provider. This uses CloudStream internals, not the public provider API,
     * but it is read-only.
     */
    private fun getCloudStreamTwitchFavoriteChannels(): List<String> {
        return runCatching {
            DataStoreHelper.getAllFavorites()
                .asSequence()
                .filter { favorite ->
                    val url = favorite.url.lowercase()
                    val api = favorite.apiName.lowercase()
                    (api == "twitch" || api == name.lowercase() || api in legacyProviderNames || url.contains("twitch") || url.contains("twitchtracker")) &&
                        !url.contains(actionMarker) && !url.contains(legacyApiActionMarker) && !url.contains(legacyApiActionMarkerV33) && !url.contains(olderApiActionMarker) && !url.contains(oldestApiActionMarker) && !url.contains(legacyActionMarker)
                }
                .mapNotNull { favorite ->
                    val fromUrl = normalizeChannel(favorite.url)
                    val fromName = normalizeChannel(favorite.name)
                    when {
                        fromUrl.isNotBlank() -> fromUrl
                        fromName.isNotBlank() -> fromName
                        else -> null
                    }
                }
                .filter { it.isNotBlank() && it != "twitch" && it != "twitchtracker" }
                .distinct()
                .toList()
        }.getOrDefault(emptyList())
    }

    /**
     * v3.5 reads CloudStream's built-in favorites only. That keeps the custom provider
     * simple: search/open a streamer, use CloudStream's normal favorite button, and Live Now
     * imports those favorites automatically. Offline channels are still hidden by getMainPage().
     */
        private fun startupFavoriteSeedKeyForAccount(): String {
        return "${DataStoreHelper.currentAccount}/$startupFavoriteSeedKey"
    }

    private fun twitchFavoriteId(channel: String): Int {
        return normalizeChannel(channel).hashCode()
    }

    private fun seedCloudStreamFavorite(channel: String) {
        val normalized = normalizeChannel(channel)
        if (normalized.isBlank()) return

        val id = twitchFavoriteId(normalized)
        val current = DataStoreHelper.getFavoritesData(id)
        val now = nowMs()
        val displayName = when (normalized) {
            "zfg247" -> "zfg247"
            "liam" -> "Liam"
            else -> normalized
        }

        DataStoreHelper.setFavoritesData(
            id,
            DataStoreHelper.FavoritesData(
                favoritesTime = current?.favoritesTime ?: now,
                id = id,
                latestUpdatedTime = now,
                name = displayName,
                url = twitchUrl(normalized),
                apiName = name,
                type = TvType.Live,
                posterUrl = current?.posterUrl,
                year = null,
                plot = current?.plot ?: "Twitch streamer",
                tags = current?.tags ?: listOf("Twitch"),
            ),
        )
    }

    private suspend fun refreshCloudStreamFavoritePosters(channels: List<String>) {
        if (!hasTwitchCredentials() || channels.isEmpty()) return

        val missingPosterChannels = channels
            .map { normalizeChannel(it) }
            .filter { it.isNotBlank() }
            .distinct()
            .filter { channel ->
                val current = DataStoreHelper.getFavoritesData(twitchFavoriteId(channel))
                current != null && current.posterUrl.isNullOrBlank()
            }

        if (missingPosterChannels.isEmpty()) return

        val users = fetchUsers(missingPosterChannels)
        var changed = false

        missingPosterChannels.forEach { channel ->
            val user = users[channel] ?: return@forEach
            val avatar = user.profile_image_url.ifBlank { null } ?: return@forEach
            val current = DataStoreHelper.getFavoritesData(twitchFavoriteId(channel)) ?: return@forEach

            if (current.posterUrl == avatar) return@forEach

            DataStoreHelper.setFavoritesData(
                twitchFavoriteId(channel),
                DataStoreHelper.FavoritesData(
                    favoritesTime = current.favoritesTime,
                    id = current.id,
                    latestUpdatedTime = nowMs(),
                    name = current.name,
                    url = current.url,
                    apiName = current.apiName,
                    type = current.type,
                    posterUrl = avatar,
                    year = current.year,
                    plot = current.plot ?: user.description.ifBlank { "Twitch streamer" },
                    tags = current.tags ?: listOf("Twitch"),
                ),
            )
            changed = true
        }

        if (changed) {
            runCatching { MainActivity.bookmarksUpdatedEvent(true) }
            runCatching { MainActivity.reloadLibraryEvent(true) }
        }
    }
    private fun ensureStartupFavoritesSaved() {
        val currentChannels = getCloudStreamTwitchFavoriteChannels()
            .map { normalizeChannel(it) }
            .toSet()

        val missing = startupFavoriteChannels
            .map { normalizeChannel(it) }
            .filter { it.isNotBlank() && it !in currentChannels }
            .distinct()

        val seedKey = startupFavoriteSeedKeyForAccount()

        if (missing.isEmpty()) {
            setKey(seedKey, true)
            return
        }

        // One-time seed per CloudStream account. This fixes existing installs,
        // but does not keep re-adding zfg247 if the user removes him later.
        if (getKey(seedKey, false) == true) return

        missing.forEach { seedCloudStreamFavorite(it) }
        setKey(seedKey, true)

        runCatching { MainActivity.bookmarksUpdatedEvent(true) }
        runCatching { MainActivity.reloadLibraryEvent(true) }
    }
private fun getSavedFavoriteChannels(): List<String> {
        return getCloudStreamTwitchFavoriteChannels()
            .map { normalizeChannel(it) }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun isFavorite(channel: String): Boolean {
        val normalized = normalizeChannel(channel)
        return normalized.isNotBlank() && getSavedFavoriteChannels().contains(normalized)
    }

    private fun hasTwitchCredentials(): Boolean {
        return TwitchCredentials.CLIENT_ID.isNotBlank() && (
            TwitchCredentials.CLIENT_SECRET.isNotBlank() || TwitchAccountAuth.isSignedIn()
        )
    }

    private fun nowMs(): Long = System.currentTimeMillis()

    private fun isBackoffActive(): Boolean = nowMs() < rateLimitedUntilMs

    private fun secondsUntilBackoffEnds(): Long {
        val remaining = rateLimitedUntilMs - nowMs()
        return if (remaining <= 0L) 0L else (remaining + 999L) / 1000L
    }

    private fun shouldTreatAsRateLimit(error: Throwable): Boolean {
        val message = (error.message ?: "").lowercase()
        val type = error.javaClass.simpleName.lowercase()
        return "429" in message || "too many requests" in message || "rate limit" in message || "ratelimit" in message || "429" in type
    }

    private fun noteRateLimit(error: Throwable? = null) {
        // CloudStream's request wrapper may throw before exposing response headers.
        // Use a conservative one-minute backoff if we detect 429/Too Many Requests.
        rateLimitedUntilMs = maxOf(rateLimitedUntilMs, nowMs() + 60_000L)
        val suffix = secondsUntilBackoffEnds().takeIf { it > 0 }?.let { " Try again in about ${it}s." }.orEmpty()
        lastTwitchApiError = "Twitch API rate limit reached.$suffix"
    }

    private fun favoritesCacheKey(favorites: List<String>): String {
        return favorites
            .map { normalizeChannel(it) }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .joinToString("|")
    }

    /**
     * Keep refresh conservative: UI refreshes are allowed to re-render the row,
     * but they do not invalidate the Twitch API cache. A Twitch API call only
     * happens when the normal cache TTL has expired, the favorites set changed,
     * or there is no last-known-good result yet.
     */

    /**
     * Mark that the Live Now row rendered.
     *
     * UI refresh cadence now belongs to HomeFragment's STARTED lifecycle. This provider should only
     * fetch/cache data; it must not start raw timer threads or force a parent Home reload.
     */
    private fun maybeScheduleAutoRefresh(cacheKey: String) {
        if (cacheKey.isBlank()) return
    }

    private fun updatedAtText(): String? {
        if (cachedLiveUpdatedAtMs <= 0L) return null
        val formatted = runCatching {
            java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                .format(java.util.Date(cachedLiveUpdatedAtMs))
        }.getOrDefault(null) ?: return null
        return "last checked $formatted"
    }

    private fun liveFavoritesRowTitle(): String {
        return updatedAtText()?.let { "$liveFavoritesNowName - $it" } ?: liveFavoritesNowName
    }

    private suspend fun getAppAccessToken(): String? {
        TwitchAccountAuth.getValidAccessToken()?.let { userToken ->
            lastTwitchApiError = null
            return userToken
        }
        if (!hasTwitchCredentials()) {
            lastTwitchApiError = "Twitch API credentials are missing."
            return null
        }
        if (isBackoffActive()) {
            lastTwitchApiError = "Twitch API backoff is active. Try again in about ${secondsUntilBackoffEnds()}s."
            return null
        }

        val now = nowMs()
        cachedAccessToken?.let { token ->
            if (now < cachedAccessTokenExpiresAtMs) return token
        }

        return runCatching {
            val response = app.post(
                oauthTokenUrl,
                headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                data = mapOf(
                    "client_id" to TwitchCredentials.CLIENT_ID,
                    "client_secret" to TwitchCredentials.CLIENT_SECRET,
                    "grant_type" to "client_credentials",
                ),
            ).parsed<TwitchTokenResponse>()

            val token = response.access_token.trim()
            if (token.isBlank()) return@runCatching null
            cachedAccessToken = token
            cachedAccessTokenExpiresAtMs = now + maxOf(60L, response.expires_in - 60L) * 1000L
            lastTwitchApiError = null
            token
        }.getOrElse { error ->
            cachedAccessToken = null
            cachedAccessTokenExpiresAtMs = 0L
            if (shouldTreatAsRateLimit(error)) {
                noteRateLimit(error)
            } else {
                lastTwitchApiError = "Could not get Twitch API token: ${error.message ?: error.javaClass.simpleName}"
            }
            null
        }
    }

    private suspend inline fun <reified T : Any> twitchGet(url: String): T? {
        if (isBackoffActive()) {
            lastTwitchApiError = "Twitch API backoff is active. Try again in about ${secondsUntilBackoffEnds()}s."
            return null
        }

        val token = getAppAccessToken() ?: return null
        val firstTry = runCatching { twitchGetWithToken<T>(url, token) }
        firstTry.getOrNull()?.let { result ->
            lastTwitchApiError = null
            return result
        }

        firstTry.exceptionOrNull()?.let { error ->
            if (shouldTreatAsRateLimit(error)) {
                noteRateLimit(error)
                return null
            }
        }

        // If the cached token was stale or revoked, clear it and try once more.
        cachedAccessToken = null
        cachedAccessTokenExpiresAtMs = 0L
        val retryToken = getAppAccessToken() ?: return null
        return runCatching { twitchGetWithToken<T>(url, retryToken) }
            .onSuccess { lastTwitchApiError = null }
            .getOrElse { error ->
                cachedAccessToken = null
                cachedAccessTokenExpiresAtMs = 0L
                if (shouldTreatAsRateLimit(error)) {
                    noteRateLimit(error)
                } else {
                    lastTwitchApiError = "Twitch API request failed: ${error.message ?: error.javaClass.simpleName}"
                }
                null
            }
    }

    private suspend inline fun <reified T : Any> twitchGetWithToken(url: String, token: String): T {
        return app.get(
            url,
            headers = mapOf(
                "Authorization" to "Bearer $token",
                "Client-Id" to TwitchCredentials.CLIENT_ID,
            ),
        ).parsed<T>()
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun decode(value: String): String = runCatching {
        URLDecoder.decode(value, "UTF-8")
    }.getOrDefault(value)
    // TwitchPlayerStreamerOverlayPatch: pass streamer metadata through player URLs.
    private fun appendTwitchPlayerStreamerMeta(
    url: String,
    login: String?,
    displayName: String?,
    avatarUrl: String?,
): String {
    var out = url
    val cleanLogin = normalizeChannel(login.orEmpty())
    val cleanName = displayName?.trim()?.ifBlank { null }
    val cleanAvatar = avatarUrl?.trim()?.ifBlank { null }

    if (cleanLogin.isNotBlank()) {
        out = appendTwitchProfileQueryParam(out, "cs_streamer_login", cleanLogin)
    }
    if (cleanName != null) {
        out = appendTwitchProfileQueryParam(out, "cs_streamer_name", cleanName)
    }
    if (cleanAvatar != null) {
        out = appendTwitchProfileQueryParam(out, "cs_streamer_avatar", cleanAvatar)
    }
    out = appendTwitchProfileQueryParam(out, "cs_api_name", name)

    return out
}

    // BEGIN TwitchPlayerMetadataOnlyPatch
    private fun cleanTwitchPlayerMetadataLogin(value: String?): String? {
        val normalized = normalizeChannel(value.orEmpty())
        val blocked = setOf(
            "twitch",
            "twitchtv",
            "twitchcom",
            "wwwtwitchtv",
            "wwwtwitchcom",
            "clips",
            "clip",
            "videos",
            "video",
            "source",
            "auto",
            "vod",
            "m3u8",
        )

        return normalized
            .takeUnless { it.isBlank() || it in blocked || it.all { char -> char.isDigit() } }
    }

    private suspend fun twitchPlayerMetadataCarrierUrl(data: String): String? {
        val explicitLogin = cleanTwitchPlayerMetadataLogin(twitchProfileMediaParam(data, "cs_streamer_login"))
        val explicitName = twitchProfileMediaParam(data, "cs_streamer_name")?.ifBlank { null }
        val explicitAvatar = twitchProfileMediaParam(data, "cs_streamer_avatar")?.ifBlank { null }

        if (explicitLogin != null) {
            val avatar = explicitAvatar ?: fetchTwitchProfileAvatar(explicitLogin)
            return appendTwitchPlayerStreamerMeta(
                twitchUrl(explicitLogin),
                explicitLogin,
                explicitName ?: explicitLogin,
                avatar,
            )
        }

        if (isTwitchVideoUrl(data)) {
            val video = fetchVideo(extractVideoId(data))
            val videoLogin = cleanTwitchPlayerMetadataLogin(video?.user_login)
            if (videoLogin != null) {
                val avatar = explicitAvatar ?: fetchTwitchProfileAvatar(videoLogin)
                return appendTwitchPlayerStreamerMeta(
                    twitchUrl(videoLogin),
                    videoLogin,
                    explicitName ?: video?.user_name?.ifBlank { null } ?: videoLogin,
                    avatar,
                )
            }
        }

        val channel = cleanTwitchPlayerMetadataLogin(data) ?: return null
        val info = fetchChannel(channel)
        val avatar = explicitAvatar ?: fetchTwitchProfileAvatar(channel) ?: info?.image
        return appendTwitchPlayerStreamerMeta(
            twitchUrl(channel),
            channel,
            explicitName ?: info?.displayName ?: channel,
            avatar,
        )
    }

    private suspend fun annotateTwitchPlayerLinks(
        data: String,
        links: List<ExtractorLink>,
    ): List<ExtractorLink> {
        val carrier = twitchPlayerMetadataCarrierUrl(data) ?: return links

        return links.onEach { link ->
            val existing = link.extractorData?.ifBlank { null }
            if (existing?.contains("cs_streamer_login=", ignoreCase = true) == true) {
                return@onEach
            }

            link.extractorData = listOfNotNull(existing, carrier)
                .joinToString("\n")
                .ifBlank { null }
        }
    }
    // END TwitchPlayerMetadataOnlyPatch
private fun buildHelixUrl(
        endpoint: String,
        repeatedKey: String? = null,
        repeatedValues: List<String> = emptyList(),
        extra: Map<String, String> = emptyMap(),
    ): String {
        val parts = mutableListOf<String>()
        extra.forEach { (key, value) -> parts.add("${encode(key)}=${encode(value)}") }
        repeatedKey?.let { key ->
            repeatedValues.forEach { value -> parts.add("${encode(key)}=${encode(value)}") }
        }
        return "$helixBase/$endpoint" + if (parts.isEmpty()) "" else "?${parts.joinToString("&")}"
    }

    private suspend fun fetchUsers(channels: List<String>): Map<String, TwitchUser> {
        val normalized = channels
            .map { normalizeChannel(it) }
            .filter { it.isNotBlank() }
            .distinct()
        if (normalized.isEmpty()) return emptyMap()

        val users = mutableMapOf<String, TwitchUser>()
        normalized.chunked(100).forEach { chunk ->
            val response = twitchGet<TwitchUsersResponse>(
                buildHelixUrl("users", repeatedKey = "login", repeatedValues = chunk),
            ) ?: return@forEach
            response.data.forEach { user ->
                val login = normalizeChannel(user.login)
                if (login.isNotBlank()) users[login] = user
            }
        }
        return users
    }
    private suspend fun fetchGameNames(gameIds: List<String>): Map<String, String> {
        val cleanIds = gameIds
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (cleanIds.isEmpty()) return emptyMap()

        val names = mutableMapOf<String, String>()
        cleanIds.chunked(100).forEach { chunk ->
            val response = twitchGet<TwitchGamesResponse>(
                buildHelixUrl("games", repeatedKey = "id", repeatedValues = chunk),
            ) ?: return@forEach

            response.data.forEach { game ->
                if (game.id.isNotBlank() && game.name.isNotBlank()) {
                    names[game.id] = game.name
                }
            }
        }
        return names
    }
    private suspend fun fetchTwitchProfileAvatar(channel: String?): String? {
        val normalized = normalizeChannel(channel.orEmpty())
        if (normalized.isBlank()) return null
        return fetchUsers(listOf(normalized))[normalized]
            ?.profile_image_url
            ?.ifBlank { null }
    }
    private val followedHomeChannelRequestLimit = 25
    private val followedHomeMaxItemsPerRow = 25
private val recentTopClipsMaxItems = 25
private val recentTopClipsMinViews = 100
private val recentTopClipsLookbackDays = 30
private val recentTopClipsPerChannel = 25

    private suspend fun fetchFollowedChannelsForHome(userId: String): List<TwitchFollowedChannel> {
        val cleanUserId = userId.trim()
        if (cleanUserId.isBlank()) return emptyList()

        val token = TwitchAccountAuth.getValidAccessToken()?.trim().orEmpty()
        if (token.isBlank()) return emptyList()

        return runCatching {
            twitchGetWithToken<TwitchFollowedChannelsResponse>(
                buildHelixUrl(
                    "channels/followed",
                    extra = mapOf(
                        "user_id" to cleanUserId,
                        "first" to followedHomeChannelRequestLimit.toString(),
                    ),
                ),
                token,
            )
        }.getOrNull()
            ?.data
            .orEmpty()
            .filter { it.broadcaster_id.isNotBlank() || it.broadcaster_login.isNotBlank() }
            .distinctBy { it.broadcaster_id.ifBlank { normalizeChannel(it.broadcaster_login) } }
            .take(followedHomeChannelRequestLimit)
    }

    private suspend fun fetchFollowedHomeMediaRows(userId: String): List<HomePageList> {
    val followed = fetchFollowedChannelsForHome(userId)
    val recentTopClips = fetchFollowedClipsHome(followed, recentTopClipsPerChannel)
    val clipItems: List<SearchResponse> = if (recentTopClips.isNotEmpty()) {
        recentTopClips
    } else {
        listOf(statusCard("No recent 100+ view clips from followed Twitch channels", "no-recent-top-clips"))
    }
    return listOf(HomePageList("Recent Top Clips", clipItems, isHorizontalImages = isHorizontal))
}


    private suspend fun fetchFollowedVideosHome(
        followed: List<TwitchFollowedChannel>,
        type: String,
        marker: String,
        perChannel: Int,
    ): List<LiveSearchResponse> {
        val items = mutableListOf<Pair<String, LiveSearchResponse>>()

        followed.take(followedHomeChannelRequestLimit).forEach { followedChannel ->
            val broadcasterId = followedChannel.broadcaster_id.ifBlank { return@forEach }
            val displayName = followedChannel.broadcaster_name
                .ifBlank { followedChannel.broadcaster_login }
                .ifBlank { "Twitch" }
            val channelAvatar = fetchTwitchProfileAvatar(followedChannel.broadcaster_login)
val response = twitchGet<TwitchVideosResponse>(
                buildHelixUrl(
                    "videos",
                    extra = mapOf(
                        "user_id" to broadcasterId,
                        "type" to type,
                        "first" to perChannel.toString(),
                    ),
                ),
            ) ?: return@forEach

            response.data.forEach { video ->
                val sortKey = video.published_at.ifBlank { video.created_at }
                val card = video.toFollowedVideoHomeCard(marker, displayName, followedChannel.broadcaster_login, channelAvatar) ?: return@forEach
                items.add(sortKey to card)
            }
        }

        return items
            .sortedByDescending { it.first }
            .map { it.second }
            .distinctBy { it.url }
            .take(followedHomeMaxItemsPerRow)
    }

    private fun TwitchVideo.toFollowedVideoHomeCard(marker: String, channelDisplayName: String, channelLogin: String, channelAvatar: String?): LiveSearchResponse? {
        val videoId = id.filter { it.isDigit() }
        if (videoId.isBlank()) return null

        val displayTitle = cleanTwitchText(title) ?: channelDisplayName.ifBlank { "Twitch video" }
        val watchUrl = url.ifBlank { twitchVideoUrl(videoId) }
        val createdOrPublished = published_at.ifBlank { created_at }
        val age = formatTwitchVideoAge(createdOrPublished) ?: formatTwitchVideoDate(createdOrPublished)
        val views = formatViewCount(view_count)
        val durationText = formatTwitchDuration(duration) ?: duration.ifBlank { null }

        val mediaCardUrl = if (marker == "past_broadcast") {
            appendTwitchProfileMediaMarker(
                twitchVodCardUrl(videoId, displayTitle, age, null, views, durationText),
                marker,
            )
        } else {
            twitchProfileMediaCardUrl(watchUrl, marker, displayTitle, age, null, views, durationText)
        }

        val metaCardUrl = appendTwitchStreamerMeta(mediaCardUrl, channelDisplayName, channelLogin, channelAvatar)
        val cardUrl = appendDirectPlayMarker(metaCardUrl)
        return newLiveSearchResponse(displayTitle, cardUrl, TvType.Live, fix = false) {
            posterUrl = cacheBustImage(twitchProfileVideoThumbnail(thumbnail_url), nowMs())
            lang = listOfNotNull(channelDisplayName.ifBlank { null }, age, views)
                .joinToString(" - ")
                .ifBlank { null }
        }
    }

    private fun twitchIsoUtc(timestampMs: Long): String {
    val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
    format.timeZone = java.util.TimeZone.getTimeZone("UTC")
    return format.format(java.util.Date(timestampMs))
}
private suspend fun fetchFollowedClipsHome(
    followed: List<TwitchFollowedChannel>,
    perChannel: Int,
): List<SearchResponse> {
    val cleanFollowed = followed
        .filter { it.broadcaster_id.isNotBlank() || it.broadcaster_login.isNotBlank() }
        .distinctBy { it.broadcaster_id.ifBlank { normalizeChannel(it.broadcaster_login) } }
        .take(followedHomeChannelRequestLimit)

    if (cleanFollowed.isEmpty()) return emptyList()

    val cacheKey = cleanFollowed
        .joinToString("|") { it.broadcaster_id.ifBlank { normalizeChannel(it.broadcaster_login) } } +
        ":days=$recentTopClipsLookbackDays:min=$recentTopClipsMinViews:max=$recentTopClipsMaxItems"
    val now = nowMs()
    if (cacheKey == cachedRecentTopClipsKey && now < cachedRecentTopClipsExpiresAtMs) {
        return cachedRecentTopClips
    }

    val startedAt = twitchIsoUtc(now - recentTopClipsLookbackDays * 24L * 60L * 60L * 1000L)
    val endedAt = twitchIsoUtc(now)
    val perChannelLimit = perChannel.coerceIn(1, 100)
    val candidates = mutableListOf<TwitchFollowedClipHomeItem>()

    cleanFollowed.forEach { followedChannel ->
        val broadcasterId = followedChannel.broadcaster_id.ifBlank { return@forEach }
        val channelLogin = normalizeChannel(followedChannel.broadcaster_login)
        val displayName = followedChannel.broadcaster_name
            .ifBlank { followedChannel.broadcaster_login }
            .ifBlank { "Twitch" }
        val channelAvatar = fetchTwitchProfileAvatar(channelLogin.ifBlank { displayName })
        val response = twitchGet<TwitchClipsResponse>(
            buildHelixUrl(
                "clips",
                extra = mapOf(
                    "broadcaster_id" to broadcasterId,
                    "started_at" to startedAt,
                    "ended_at" to endedAt,
                    "first" to perChannelLimit.toString(),
                ),
            ),
        ) ?: return@forEach

        response.data
            .asSequence()
            .filter { it.view_count >= recentTopClipsMinViews }
            .map { clip ->
                TwitchFollowedClipHomeItem(
                    clip = clip,
                    channelDisplayName = displayName,
                    channelLogin = channelLogin,
                    channelAvatar = channelAvatar,
                )
            }
            .forEach { candidates.add(it) }
    }

    val gameNames = fetchGameNames(candidates.map { it.clip.game_id })
    val result = candidates
        .sortedWith(
            compareByDescending<TwitchFollowedClipHomeItem> { it.clip.view_count }
                .thenByDescending { it.clip.created_at }
        )
        .mapNotNull { item ->
            item.clip.toFollowedClipHomeCard(
                item.channelDisplayName,
                item.channelLogin,
                item.channelAvatar,
                gameNames[item.clip.game_id],
            ) as? SearchResponse
        }
        .distinctBy { it.url }
        .take(recentTopClipsMaxItems)

    cachedRecentTopClipsKey = cacheKey
    cachedRecentTopClipsExpiresAtMs = now + liveCacheTtlMs
    cachedRecentTopClips = result
    return result
}

    private fun TwitchClip.toFollowedClipHomeCard(
        channelDisplayName: String,
        channelLogin: String,
        channelAvatar: String?,
        gameName: String?,
    ): LiveSearchResponse? {
        val watchUrl = url.ifBlank { return null }
        val displayTitle = cleanTwitchText(title) ?: channelDisplayName.ifBlank { "Twitch clip" }
        val age = formatTwitchVideoAge(created_at) ?: formatTwitchVideoDate(created_at)
        val views = formatViewCount(view_count)
        val durationText = if (duration > 0f) "${duration.toInt()}s" else null
        val clipVideoUrl = twitchClipDirectVideoUrl(thumbnail_url)
        val mediaCardUrl = appendTwitchProfileQueryParam(
            twitchProfileMediaCardUrl(watchUrl, "clip", displayTitle, age, gameName, views, durationText),
            "cs_clip_video",
            clipVideoUrl,
        )
        val streamerMetaUrl = appendTwitchStreamerMeta(mediaCardUrl, channelDisplayName, channelLogin, channelAvatar)
        val metadataUrl = appendTwitchHomeCardMeta(
            streamerMetaUrl,
            title = displayTitle,
            category = gameName,
            viewers = views,
        )
        val cardUrl = appendDirectPlayMarker(metadataUrl)

        return newLiveSearchResponse(displayTitle, cardUrl, TvType.Live, fix = false) {
            posterUrl = cacheBustImage(thumbnail_url.ifBlank { null }, nowMs())
            lang = listOfNotNull(channelDisplayName.ifBlank { null }, gameName, age, views)
                .joinToString(" - ")
                .ifBlank { null }
        }
    }

    private suspend fun fetchLiveFollowedStreams(userId: String): List<FavoriteChannel>? {
        val cleanUserId = userId.trim()
        if (cleanUserId.isBlank()) return null

        if (isBackoffActive()) {
            lastTwitchApiError = "Twitch API backoff is active. Try again in about ${secondsUntilBackoffEnds()}s."
            return null
        }

        val token = TwitchAccountAuth.getValidAccessToken()?.trim().orEmpty()
        if (token.isBlank()) {
            lastTwitchApiError = "Sign in to Twitch again to load followed live channels."
            return null
        }

        val now = nowMs()
        val cacheKey = "followed:$cleanUserId"
        val forceImmediateRefresh = TwitchLiveNowImmediateRefresh.consumeForUser(cleanUserId)

        if (forceImmediateRefresh) {
            cachedLiveExpiresAtMs = 0L
        }

        if (!forceImmediateRefresh && cacheKey == cachedLiveKey && now < cachedLiveExpiresAtMs) {
            return cachedLiveFavorites
        }

        val streams = mutableMapOf<String, TwitchStream>()
        var cursor: String? = null
        var guard = 0

        do {
            val extra = mutableMapOf(
                "user_id" to cleanUserId,
                "first" to "100",
            )
            if (!cursor.isNullOrBlank()) {
                extra["after"] = cursor.orEmpty()
            }

            val response = runCatching {
                twitchGetWithToken<TwitchStreamsResponse>(
                    buildHelixUrl("streams/followed", extra = extra),
                    token,
                )
            }.getOrElse { error ->
                if (shouldTreatAsRateLimit(error)) {
                    noteRateLimit(error)
                } else {
                    lastTwitchApiError = "Could not load followed live channels: ${error.message ?: error.javaClass.simpleName}"
                }

                if (cacheKey == cachedLiveKey && cachedLiveUpdatedAtMs > 0L) {
                    cachedLiveExpiresAtMs = now + failedApiRetryDelayMs
                    return cachedLiveFavorites
                }
                return null
            }

            response.data.forEach { stream ->
                val login = normalizeChannel(stream.user_login)
                if (login.isNotBlank()) streams[login] = stream
            }

            cursor = response.pagination.cursor?.takeIf { it.isNotBlank() }
            guard++
        } while (!cursor.isNullOrBlank() && guard < 20)

        val users = fetchUsers(streams.keys.toList())
        val liveChannels = streams.values
            .map { stream ->
                val login = normalizeChannel(stream.user_login)
                favoriteFromApi(login, users[login], stream)
            }
            .sortedWith(compareByDescending<FavoriteChannel> { it.viewerCount ?: 0 }.thenBy { it.displayName.lowercase() })

        cachedLiveKey = cacheKey
        cachedLiveExpiresAtMs = now + liveCacheTtlMs
        cachedLiveUpdatedAtMs = now
        cachedLiveFavorites = liveChannels
        lastTwitchApiError = null
        return liveChannels
    }
    private suspend fun fetchStreams(channels: List<String>): Map<String, TwitchStream>? {
        val normalized = channels
            .map { normalizeChannel(it) }
            .filter { it.isNotBlank() }
            .distinct()
        if (normalized.isEmpty()) return emptyMap()

        val streams = mutableMapOf<String, TwitchStream>()
        normalized.chunked(100).forEach { chunk ->
            val response = twitchGet<TwitchStreamsResponse>(
                buildHelixUrl(
                    "streams",
                    repeatedKey = "user_login",
                    repeatedValues = chunk,
                    extra = mapOf("first" to "100", "type" to "live"),
                ),
            ) ?: return null
            response.data.forEach { stream ->
                val login = normalizeChannel(stream.user_login)
                if (login.isNotBlank()) streams[login] = stream
            }
        }
        return streams
    }

    private suspend fun fetchLiveFavoriteChannels(favorites: List<String>): List<FavoriteChannel>? {
        val normalized = favorites
            .map { normalizeChannel(it) }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
        if (normalized.isEmpty()) return emptyList()

        val now = nowMs()
        val cacheKey = normalized.joinToString("|")
        if (cacheKey == cachedLiveKey && now < cachedLiveExpiresAtMs) {
            return cachedLiveFavorites
        }

        val streams = fetchStreams(normalized)
        if (streams == null) {
            // Last-known-good fallback: if Twitch is temporarily unavailable or rate-limited,
            // keep showing the most recent result for the same favorites set instead of an error.
            // Also wait briefly before retrying so repeated UI reloads cannot hammer Helix.
            if (cacheKey == cachedLiveKey && cachedLiveUpdatedAtMs > 0L) {
                cachedLiveExpiresAtMs = now + failedApiRetryDelayMs
                return cachedLiveFavorites
            }
            return null
        }
        if (streams.isEmpty()) {
            cachedLiveKey = cacheKey
            cachedLiveExpiresAtMs = now + liveCacheTtlMs
            cachedLiveUpdatedAtMs = now
            cachedLiveFavorites = emptyList()
            return emptyList()
        }

        val users = fetchUsers(streams.keys.toList())
        val liveChannels = streams.values
            .map { stream ->
                val login = normalizeChannel(stream.user_login)
                favoriteFromApi(login, users[login], stream)
            }
            .sortedWith(compareByDescending<FavoriteChannel> { it.viewerCount ?: 0 }.thenBy { it.displayName.lowercase() })

        cachedLiveKey = cacheKey
        cachedLiveExpiresAtMs = now + liveCacheTtlMs
        cachedLiveUpdatedAtMs = now
        cachedLiveFavorites = liveChannels
        return liveChannels
    }

    private suspend fun fetchChannel(channel: String): FavoriteChannel? {
        val normalized = normalizeChannel(channel)
        if (normalized.isBlank()) return null

        // If this was opened from the Live Now row, reuse the already-fetched
        // stream metadata instead of spending extra Helix calls just to build
        // the detail page. Search results for non-favorites can still fetch.
        cachedLiveFavorites.firstOrNull { it.channel == normalized }?.let { cached ->
            return cached
        }

        if (!hasTwitchCredentials()) return fallbackChannel(normalized)

        val users = fetchUsers(listOf(normalized))
        val user = users[normalized] ?: return null
        val stream = fetchStreams(listOf(normalized))?.get(normalized)
        return favoriteFromApi(normalized, user, stream)
    }

    private fun favoriteFromApi(channel: String, user: TwitchUser?, stream: TwitchStream?): FavoriteChannel {
        val normalized = normalizeChannel(channel.ifBlank { user?.login.orEmpty() }.ifBlank { stream?.user_login.orEmpty() })
        val displayName = listOf(
            user?.display_name,
            stream?.user_name,
            normalized,
        ).firstOrNull { !it.isNullOrBlank() }.orEmpty()
        val preview = resizeTwitchImage(stream?.thumbnail_url, 640, 360)
        val profile = user?.profile_image_url?.ifBlank { null }
        val offline = user?.offline_image_url?.ifBlank { null }
        return FavoriteChannel(
            channel = normalized,
            displayName = displayName.ifBlank { normalized },
            image = profile,
            poster = preview ?: offline ?: profile,
            isLive = stream != null,
            language = stream?.language?.ifBlank { null },
            rank = null,
            description = stream?.title?.ifBlank { null } ?: user?.description?.ifBlank { null },
            gameName = stream?.game_name?.ifBlank { null },
            viewerCount = stream?.viewer_count,

        userId = user?.id?.ifBlank { null } ?: stream?.user_id?.ifBlank { null },)
    }

    private fun fallbackChannel(channel: String): FavoriteChannel {
        val normalized = normalizeChannel(channel)
        return FavoriteChannel(
            channel = normalized,
            displayName = normalized,
            image = null,
            poster = null,
            isLive = false,
            language = null,
            rank = null,
            description = null,
        )
    }

    private fun resizeTwitchImage(url: String?, width: Int, height: Int): String? {
        return url
            ?.ifBlank { null }
            ?.replace("%{width}", width.toString())
            ?.replace("%{height}", height.toString())
            ?.replace("{width}", width.toString())
            ?.replace("{height}", height.toString())
    }

    private fun cacheBustImage(url: String?, version: Long = cachedLiveUpdatedAtMs): String? {
        val clean = url?.ifBlank { null } ?: return null
        if (version <= 0L) return clean

        val separator = if (clean.contains("?")) "&" else "?"
        return "$clean${separator}t=$version"
    }
private fun normalizeChannel(value: String): String {
        val trimmed = value
            .trim()
            .removePrefix("@")
            .substringBefore("?")
            .substringBefore("#")
            .trim('/')
            .substringAfterLast("/")
            .lowercase()

        return trimmed.filter { it.isLetterOrDigit() || it == '_' }
    }

    private fun twitchUrl(channel: String): String = "https://twitch.tv/${normalizeChannel(channel)}"
    private fun appendDirectPlayMarker(url: String): String {
        val separator = if (url.contains("?")) "&" else "?"
        return "$url${separator}cloudstream_direct_play=1"
    }
    private fun directPlayUrl(channel: String): String {
        return appendDirectPlayMarker(twitchUrl(channel))
    }

        private fun twitchVideoUrl(id: String): String {
        val cleanId = id.filter { it.isDigit() }
        return "https://www.twitch.tv/videos/$cleanId"
    }

    private fun extractVideoId(value: String): String {
        val clean = value
            .substringBefore("?")
            .substringBefore("#")
            .trim()
            .trimEnd('/')

        if (clean.all { it.isDigit() }) return clean

        return clean
            .substringAfterLast("/videos/", "")
            .substringAfterLast("/")
            .filter { it.isDigit() }
    }

    private fun isTwitchVideoUrl(url: String): Boolean {
        val clean = url.substringBefore("?").substringBefore("#").trim().trimEnd('/')
        return clean.contains("/videos/", ignoreCase = true) ||
            clean.all { it.isDigit() }
    }

    private suspend fun fetchVideo(videoId: String): TwitchVideo? {
        val cleanId = videoId.filter { it.isDigit() }
        if (cleanId.isBlank()) return null

        val response = twitchGet<TwitchVideosResponse>(
            buildHelixUrl("videos", repeatedKey = "id", repeatedValues = listOf(cleanId)),
        ) ?: return null

        return response.data.firstOrNull()
    }

        private suspend inline fun <reified T : Any> twitchGqlPost(body: Map<String, Any?>): T? {
        val requestBody = body
            .toJson()
            .toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())

        return runCatching {
            app.post(
                twitchGqlUrl,
                requestBody = requestBody,
                headers = mapOf(
                    "Client-Id" to twitchWebClientId,
                    "Content-Type" to "application/json",
                    "User-Agent" to twitchWebUserAgent,
                    "Referer" to "https://www.twitch.tv/",
                ),
            ).parsed<T>()
        }.getOrNull()
    }

    private fun twitchVodCardUrl(
        videoId: String,
        title: String?,
        age: String?,
        category: String?,
        views: String?,
        duration: String?,
    ): String {
        val params = mutableListOf(twitchVodMarker)

        fun add(name: String, value: String?) {
            val clean = value?.ifBlank { null } ?: return
            params.add("${name}=${encode(clean)}")
        }

        add("cs_title", title)
        add("cs_age", age)
        add("cs_category", category)
        add("cs_views", views)
        add("cs_duration", duration)

        return "${twitchVideoUrl(videoId)}?${params.joinToString("&")}"
    }

    private suspend fun fetchTwitchVideoCategory(video: TwitchVideo): String? {
        val videoId = video.id.filter { it.isDigit() }
        if (videoId.isBlank()) return null

        val query = """
            query TwitchVideoMetadata(${'$'}id: ID!) {
                video(id: ${'$'}id) {
                    game {
                        displayName
                        name
                    }
                }
            }
        """.trimIndent()

        val response = twitchGqlPost<TwitchGqlVideoMetadataResponse>(
            mapOf(
                "operationName" to "TwitchVideoMetadata",
                "query" to query,
                "variables" to mapOf("id" to videoId),
            ),
        ) ?: return null

        val game = response.data?.video?.game
        return cleanTwitchText(game?.displayName ?: game?.name)
    }

    private fun formatTwitchVideoAge(value: String?): String? {
        val clean = value?.ifBlank { null } ?: return null

        return runCatching {
            val parser = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            parser.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val date = parser.parse(clean) ?: return@runCatching null
            val diffMs = (nowMs() - date.time).coerceAtLeast(0L)
            val minutes = diffMs / 60_000L
            val hours = minutes / 60L
            val days = hours / 24L
            val months = days / 30L
            val years = days / 365L

            when {
                minutes < 1L -> "just now"
                minutes == 1L -> "1 min ago"
                minutes < 60L -> "$minutes mins ago"
                hours == 1L -> "1 hour ago"
                hours < 24L -> "$hours hours ago"
                days == 1L -> "1 day ago"
                days < 30L -> "$days days ago"
                months == 1L -> "1 month ago"
                months < 12L -> "$months months ago"
                years == 1L -> "1 year ago"
                else -> "$years years ago"
            }
        }.getOrNull()
    }

    private fun formatTwitchDuration(value: String?): String? {
        val clean = value?.ifBlank { null } ?: return null

        val match = Regex("""(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?""").matchEntire(clean)
            ?: return clean

        val hours = match.groupValues.getOrNull(1)?.toIntOrNull() ?: 0
        val minutes = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
        val seconds = match.groupValues.getOrNull(3)?.toIntOrNull() ?: 0

        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    private suspend fun TwitchVideo.toPastBroadcastCardWithMetadata(): LiveSearchResponse? {
        val videoId = id.filter { it.isDigit() }
        if (videoId.isBlank()) return null

        val displayTitle = cleanTwitchText(title) ?: "Past broadcast"
        val age = formatTwitchVideoAge(published_at) ?: formatTwitchVideoDate(published_at)
        val category = fetchTwitchVideoCategory(this)
        val views = formatViewCount(view_count)
        val durationText = formatTwitchDuration(duration) ?: duration.ifBlank { null }
        val cardUrl = twitchVodCardUrl(videoId, displayTitle, age, category, views, durationText)

        return newLiveSearchResponse(displayTitle, cardUrl, TvType.Live, fix = false) {
            posterUrl = cacheBustImage(resizeTwitchImage(thumbnail_url, 640, 360), nowMs())
            lang = listOfNotNull(age, category).joinToString(" - ").ifBlank { null }
        }
    }

    private suspend fun fetchTwitchVodPlaybackToken(videoId: String): TwitchPlaybackAccessToken? {
        val cleanId = videoId.filter { it.isDigit() }
        if (cleanId.isBlank()) return null

        val query = """
            query PlaybackAccessToken_Template(${'$'}vodID: ID!, ${'$'}isVod: Boolean!, ${'$'}login: String!, ${'$'}isLive: Boolean!, ${'$'}playerType: String!) {
                streamPlaybackAccessToken(channelName: ${'$'}login, params: {platform: "web", playerBackend: "mediaplayer", playerType: ${'$'}playerType}) @include(if: ${'$'}isLive) {
                    value
                    signature
                }
                videoPlaybackAccessToken(id: ${'$'}vodID, params: {platform: "web", playerBackend: "mediaplayer", playerType: ${'$'}playerType}) @include(if: ${'$'}isVod) {
                    value
                    signature
                }
            }
        """.trimIndent()

        val response = twitchGqlPost<TwitchPlaybackAccessTokenResponse>(
            mapOf(
                "operationName" to "PlaybackAccessToken_Template",
                "query" to query,
                "variables" to mapOf(
                    "isLive" to false,
                    "login" to "",
                    "isVod" to true,
                    "vodID" to cleanId,
                    "playerType" to "site",
                ),
            ),
        ) ?: return null

        return response.data?.videoPlaybackAccessToken
            ?.takeIf { it.value.isNotBlank() && it.signature.isNotBlank() }
    }

    private fun twitchVodMasterPlaylistUrl(videoId: String, token: TwitchPlaybackAccessToken): String {
        val cleanId = videoId.filter { it.isDigit() }
        val params = listOf(
            "allow_source=true",
            "allow_audio_only=true",
            "player=twitchweb",
            "nauthsig=${encode(token.signature)}",
            "nauth=${encode(token.value)}",
        )

        return "https://usher.ttvnw.net/vod/$cleanId.m3u8?${params.joinToString("&")}"
    }

    private fun resolveTwitchPlaylistUrl(masterUrl: String, value: String): String {
        val clean = value.trim()
        if (clean.startsWith("http", ignoreCase = true)) return clean

        val base = masterUrl
            .substringBefore("?")
            .substringBeforeLast("/", missingDelimiterValue = masterUrl)

        return "$base/$clean"
    }

    private suspend fun parseTwitchVodMasterPlaylist(masterUrl: String, body: String): List<ExtractorLink> {
        val links = mutableListOf<ExtractorLink>()
        var streamInfo: String? = null

        body.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()

            when {
                line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) -> {
                    streamInfo = line
                }

                line.isNotBlank() && !line.startsWith("#") && streamInfo != null -> {
                    val info = streamInfo.orEmpty()
                    val height = info
                        .substringAfter("RESOLUTION=", "")
                        .substringBefore(",")
                        .substringAfter("x", "")
                        .toIntOrNull()

                    val videoGroup = info
                        .substringAfter("VIDEO=\"", "")
                        .substringBefore("\"")
                        .ifBlank { null }

                    val label = when {
                        videoGroup.equals("chunked", ignoreCase = true) -> "Source"
                        videoGroup.equals("audio_only", ignoreCase = true) -> "Audio Only"
                        height != null -> "${height}p"
                        else -> "Auto"
                    }

                    val quality = height?.let { getQualityFromName("${it}p") } ?: getQualityFromName(label)
                    val streamUrl = resolveTwitchPlaylistUrl(masterUrl, line)

                    links.add(
                        newExtractorLink(
                            name,
                            "$name VOD $label",
                            streamUrl,
                        ) {
                            this.type = ExtractorLinkType.M3U8
                            this.quality = quality
                            this.referer = "https://www.twitch.tv/"
                            this.headers = mapOf("User-Agent" to twitchWebUserAgent)
                        },
                    )

                    streamInfo = null
                }
            }
        }

        return links.distinctBy { it.url }
    }

    private suspend fun fetchTwitchVodLinks(videoId: String): List<ExtractorLink> {
        val token = fetchTwitchVodPlaybackToken(videoId) ?: return emptyList()
        val masterUrl = twitchVodMasterPlaylistUrl(videoId, token)

        val body = runCatching {
            app.get(
                masterUrl,
                headers = mapOf(
                    "Client-Id" to twitchWebClientId,
                    "User-Agent" to twitchWebUserAgent,
                    "Referer" to "https://www.twitch.tv/",
                ),
            ).text
        }.getOrNull() ?: return emptyList()

        return parseTwitchVodMasterPlaylist(masterUrl, body).ifEmpty {
            listOf(
                newExtractorLink(
                    name,
                    "$name VOD Auto",
                    masterUrl,
                ) {
                    this.type = ExtractorLinkType.M3U8
                    this.quality = getQualityFromName("auto")
                    this.referer = "https://www.twitch.tv/"
                    this.headers = mapOf("User-Agent" to twitchWebUserAgent)
                },
            )
        }
    }
private suspend fun fetchRecentPastBroadcasts(
        channel: String,
        userId: String?,
    ): List<LiveSearchResponse> {
        if (!hasTwitchCredentials()) return emptyList()

        val normalized = normalizeChannel(channel)
        val resolvedUserId = userId?.ifBlank { null }
            ?: fetchUsers(listOf(normalized))[normalized]?.id?.ifBlank { null }
            ?: return emptyList()

        val response = twitchGet<TwitchVideosResponse>(
            buildHelixUrl(
                "videos",
                extra = mapOf(
                    "user_id" to resolvedUserId,
                    "first" to "50",
                    "type" to "archive",
                ),
            ),
        ) ?: return emptyList()

        return response.data
            .mapNotNull { it.toPastBroadcastCardWithMetadata() }
            .distinctBy { it.url }
    }

    private fun TwitchVideo.toPastBroadcastCard(): LiveSearchResponse? {
        val videoId = id.filter { it.isDigit() }
        if (videoId.isBlank()) return null

        val displayTitle = cleanTwitchText(title) ?: "Past broadcast"
        val meta = listOfNotNull(
            "Past Broadcast",
            duration.ifBlank { null },
            formatTwitchVideoDate(published_at),
            formatViewCount(view_count),
        ).joinToString(" - ").ifBlank { null }

        return newLiveSearchResponse(displayTitle, twitchVideoUrl(videoId), TvType.Live, fix = false) {
            posterUrl = cacheBustImage(resizeTwitchImage(thumbnail_url, 640, 360), nowMs())
            lang = meta
        }
    }

    private fun formatTwitchVideoDate(value: String?): String? {
        val clean = value?.ifBlank { null } ?: return null
        val fallback = clean.substringBefore("T").ifBlank { clean }

        return runCatching {
            val parser = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            parser.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val date = parser.parse(clean) ?: return@runCatching fallback
            java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(date)
        }.getOrDefault(fallback)
    }

    private fun formatViewCount(count: Int?): String? {
        val value = count ?: return null
        if (value <= 0) return null

        val label = when {
            value >= 1_000_000 -> formatOneDecimal(value / 1_000_000.0) + "M"
            value >= 1_000 -> formatOneDecimal(value / 1_000.0) + "K"
            else -> value.toString()
        }

        return "$label views"
    }
private fun formatViewerCount(count: Int?): String? {
        val value = count ?: return null
        val label = when {
            value >= 1_000_000 -> formatOneDecimal(value / 1_000_000.0) + "M"
            value >= 1_000 -> formatOneDecimal(value / 1_000.0) + "K"
            else -> value.toString()
        }
        return "$label viewers"
    }

    private fun formatOneDecimal(value: Double): String {
        val rounded = kotlin.math.round(value * 10.0) / 10.0
        val asLong = rounded.toLong()
        return if (rounded == asLong.toDouble()) asLong.toString() else rounded.toString()
    }

    private fun FavoriteChannel.subtitle(): String? {
        return listOfNotNull(
            gameName?.ifBlank { null },
            formatViewerCount(viewerCount),
        ).joinToString(" - ").ifBlank { null }
    }

            private fun twitchMojibake(vararg codes: Int): String {
        return codes.map { it.toChar() }.joinToString("")
    }

    private fun cleanTwitchText(value: String?): String? {
        val bullet = 0x2022.toChar().toString()
        val badBullet = twitchMojibake(0x00E2, 0x20AC, 0x00A2)
        val badEnDash = twitchMojibake(0x00E2, 0x20AC, 0x201C)
        val badEmDash = twitchMojibake(0x00E2, 0x20AC, 0x201D)
        val badLeftQuote = twitchMojibake(0x00E2, 0x20AC, 0x0153)
        val badRightQuote = twitchMojibake(0x00E2, 0x20AC, 0x009D)
        val badApostrophe = twitchMojibake(0x00E2, 0x20AC, 0x2122)
        val badReplacement = 0xFFFD.toChar().toString()

        return value
            ?.ifBlank { null }
            ?.replace(badBullet, "-")
            ?.replace(badEnDash, "-")
            ?.replace(badEmDash, "-")
            ?.replace(badLeftQuote, "\"")
            ?.replace(badRightQuote, "\"")
            ?.replace(badApostrophe, "'")
            ?.replace(bullet, "-")
            ?.replace(badReplacement, "")
            ?.replace(0x00A0.toChar().toString(), " ")
            ?.trim()
    }
    private fun FavoriteChannel.profileStatusText(): String {
        return if (isLive) {
            listOfNotNull(
                "Live now",
                cleanTwitchText(gameName),
                formatViewerCount(viewerCount),
            ).joinToString(" - ")
        } else {
            "Offline"
        }
    }
        private fun FavoriteChannel.profilePlot(): String? {
        return listOfNotNull(
            "Status: ${profileStatusText()}",
            cleanTwitchText(description),
        ).joinToString("\n\n").ifBlank { null }
    }
        private fun FavoriteChannel.toChannelCard(
        showOfflineLabel: Boolean,
        directPlay: Boolean = false,
    ): LiveSearchResponse {
        val streamTitle = cleanTwitchText(description)
        val displayTitle = when {
            isLive && streamTitle != null -> streamTitle
            showOfflineLabel && !isLive -> "$displayName (offline)"
            else -> displayName
        }
        val baseResultUrl = if (directPlay && isLive) directPlayUrl(channel) else channel
        val streamerMetaUrl = appendTwitchStreamerMeta(
            baseResultUrl,
            displayName,
            channel,
            image,
            userId = userId,
        )
        val resultUrl = appendTwitchHomeCardMeta(
            streamerMetaUrl,
            title = displayTitle,
            category = gameName,
            viewers = formatViewerCount(viewerCount),
        )

        return newLiveSearchResponse(displayTitle, resultUrl, TvType.Live, fix = false) {
            posterUrl = cacheBustImage(poster ?: image)
            lang = subtitle() ?: language
        }
    }



    // BEGIN TWITCH_PROFILE_MEDIA_PROVIDER_ROWS
    private fun appendTwitchProfileMediaMarker(url: String, marker: String): String {
        val separator = if (url.contains("?")) "&" else "?"
        return "$url${separator}cloudstream_twitch_media=$marker"
    }

        private fun appendTwitchProfileQueryParam(url: String, name: String, value: String?): String {
        val cleanValue = value?.ifBlank { null } ?: return url
        val separator = if (url.contains("?")) "&" else "?"
        return "$url$separator${encode(name)}=${encode(cleanValue)}"
    }
    // BEGIN TwitchStreamerMetadataUrlPatch
    private fun appendTwitchStreamerMeta(
    url: String,
    displayName: String?,
    channel: String?,
    avatarUrl: String?,
    userId: String? = null,
): String {
    var out = url
    val cleanName = displayName?.ifBlank { null }
    val cleanChannel = channel?.let { normalizeChannel(it) }?.ifBlank { null }
    val cleanAvatar = avatarUrl?.ifBlank { null }
    val cleanUserId = userId?.ifBlank { null }
    if (cleanUserId != null) out = appendTwitchProfileQueryParam(out, "cs_streamer_user_id", cleanUserId)
    if (cleanName != null) out = appendTwitchProfileQueryParam(out, "cs_streamer_name", cleanName)
    if (cleanChannel != null) out = appendTwitchProfileQueryParam(out, "cs_streamer_login", cleanChannel)
    if (cleanAvatar != null) out = appendTwitchProfileQueryParam(out, "cs_streamer_avatar", cleanAvatar)
    out = appendTwitchProfileQueryParam(out, "cs_api_name", name)
    return out
}
    
    private fun appendTwitchHomeCardMeta(
        url: String,
        title: String?,
        category: String?,
        viewers: String?,
    ): String {
        var out = url
        val cleanTitle = cleanTwitchText(title)
        val cleanCategory = cleanTwitchText(category)
        val cleanViewers = cleanTwitchText(viewers)
        if (cleanTitle != null) out = appendTwitchProfileQueryParam(out, "cs_stream_title", cleanTitle)
        if (cleanCategory != null) out = appendTwitchProfileQueryParam(out, "cs_category", cleanCategory)
        if (cleanViewers != null) out = appendTwitchProfileQueryParam(out, "cs_viewers", cleanViewers)
        return out
    }
    // END TwitchStreamerMetadataUrlPatch
    private fun twitchProfileMediaParam(url: String, key: String): String? {
        val query = url.substringAfter("?", "")
        if (query.isBlank() || query == url) return null
        return query
            .split("&")
            .firstOrNull { it.substringBefore("=") == key }
            ?.substringAfter("=", "")
            ?.let { decode(it) }
            ?.ifBlank { null }
    }
    private fun twitchClipDirectVideoUrl(thumbnailUrl: String?): String? {
    val clean = thumbnailUrl?.ifBlank { null }?.substringBefore("?")?.substringBefore("#") ?: return null
    var base: String? = null
    for (marker in listOf("-preview-", "-social-preview", "-preview")) {
        val candidate = clean.substringBefore(marker, missingDelimiterValue = "").ifBlank { null }
        if (candidate != null) {
            base = candidate
            break
        }
    }
    val finalBase = base ?: clean.substringBeforeLast('.', missingDelimiterValue = "")
    if (finalBase.isBlank() || !finalBase.startsWith("http", ignoreCase = true)) return null
    return if (finalBase.endsWith(".mp4", ignoreCase = true)) finalBase else "$finalBase.mp4"
}

private fun isTwitchClipDirectVideoUrl(url: String): Boolean {
        val clean = url.substringBefore("?").substringBefore("#").lowercase()
        return clean.startsWith("http") && clean.endsWith(".mp4") &&
            (clean.contains("clips") || clean.contains("jtvnw") || clean.contains("twitch"))
    }

private fun twitchProfileMediaCardUrl(
    url: String,
    marker: String,
    title: String?,
    age: String?,
    category: String?,
    views: String?,
    duration: String?,
): String {
    val params = mutableListOf("cloudstream_twitch_media=$marker")
    fun add(name: String, value: String?) {
        val clean = value?.ifBlank { null } ?: return
        params.add("${name}=${encode(clean)}")
    }
    add("cs_title", title)
    add("cs_age", age)
    add("cs_category", category)
    add("cs_views", views)
    add("cs_duration", duration)
    val separator = if (url.contains("?")) "&" else "?"
    return "$url$separator${params.joinToString("&")}"
}
private fun twitchProfileVideoThumbnail(url: String): String? {
        return url
            .replace("%{width}", "640")
            .replace("%{height}", "360")
            .replace("{width}", "640")
            .replace("{height}", "360")
            .ifBlank { null }
    }

    private fun twitchProfileDateLabel(createdAt: String, suffix: String?): String? {
        val date = createdAt.substringBefore("T").takeIf { it.isNotBlank() }
        return listOfNotNull(date, suffix?.takeIf { it.isNotBlank() }).joinToString(" - ").ifBlank { null }
    }

    private suspend fun fetchTwitchVideoRecommendations(user: TwitchUser, type: String, marker: String): List<LiveSearchResponse> {
    if (user.id.isBlank()) return emptyList()
    val json = twitchGet<TwitchVideosResponse>(
        buildHelixUrl(
            "videos",
            extra = mapOf("user_id" to user.id, "type" to type, "first" to "12"),
        ),
    ) ?: return emptyList()

    return json.data.mapNotNull { video ->
        val videoId = video.id.filter { it.isDigit() }
        if (videoId.isBlank()) return@mapNotNull null
        val displayTitle = cleanTwitchText(video.title) ?: user.display_name.ifBlank { user.login }.ifBlank { "Twitch video" }
        val watchUrl = video.url.ifBlank { "https://www.twitch.tv/videos/$videoId" }
        val createdOrPublished = video.published_at.ifBlank { video.created_at }
        val age = formatTwitchVideoAge(createdOrPublished) ?: formatTwitchVideoDate(createdOrPublished)
        val category = fetchTwitchVideoCategory(video)
        val views = formatViewCount(video.view_count)
        val durationText = formatTwitchDuration(video.duration) ?: video.duration.ifBlank { null }
        val mediaCardUrl = if (marker == "past_broadcast") {
            appendTwitchProfileMediaMarker(
                twitchVodCardUrl(videoId, displayTitle, age, category, views, durationText),
                marker,
            )
        } else {
            twitchProfileMediaCardUrl(watchUrl, marker, displayTitle, age, category, views, durationText)
        }
        val metaCardUrl = appendTwitchStreamerMeta(mediaCardUrl, user.display_name.ifBlank { user.login }, user.login, user.profile_image_url)
        val cardUrl = appendDirectPlayMarker(metaCardUrl)
        newLiveSearchResponse(displayTitle, cardUrl, TvType.Live, fix = false) {
            posterUrl = cacheBustImage(twitchProfileVideoThumbnail(video.thumbnail_url), nowMs())
            lang = listOfNotNull(age, category).joinToString(" - ").ifBlank { null }
        }
    }.distinctBy { it.url }
}
private suspend fun fetchTwitchClipRecommendations(user: TwitchUser): List<LiveSearchResponse> {
    if (user.id.isBlank()) return emptyList()
    val json = twitchGet<TwitchClipsResponse>(
        buildHelixUrl(
            "clips",
            extra = mapOf("broadcaster_id" to user.id, "first" to "12"),
        ),
    ) ?: return emptyList()

    return json.data.mapNotNull { clip ->
        val watchUrl = clip.url.ifBlank { return@mapNotNull null }
        val displayTitle = cleanTwitchText(clip.title) ?: user.display_name.ifBlank { user.login }.ifBlank { "Twitch clip" }
        val age = formatTwitchVideoAge(clip.created_at) ?: formatTwitchVideoDate(clip.created_at)
        val views = formatViewCount(clip.view_count)
        val durationText = if (clip.duration > 0f) "${clip.duration.toInt()}s" else null
        val clipVideoUrl = twitchClipDirectVideoUrl(clip.thumbnail_url)
        val mediaCardUrl = appendTwitchProfileQueryParam(twitchProfileMediaCardUrl(watchUrl, "clip", displayTitle, age, null, views, durationText), "cs_clip_video", clipVideoUrl)
        val metaCardUrl = appendTwitchStreamerMeta(mediaCardUrl, user.display_name.ifBlank { user.login }, user.login, user.profile_image_url)
        val cardUrl = appendDirectPlayMarker(metaCardUrl)
        newLiveSearchResponse(displayTitle, cardUrl, TvType.Live, fix = false) {
            posterUrl = cacheBustImage(clip.thumbnail_url.ifBlank { null }, nowMs())
            lang = age
        }
    }.distinctBy { it.url }
}
private suspend fun fetchTwitchProfileRecommendations(channel: String): List<SearchResponse> {
        if (!hasTwitchCredentials()) return emptyList()
        val normalized = normalizeChannel(channel)
        if (normalized.isBlank()) return emptyList()
        val user = fetchUsers(listOf(normalized))[normalized] ?: return emptyList()
        val pastBroadcasts = fetchTwitchVideoRecommendations(user, "archive", "past_broadcast")
        val clips = fetchTwitchClipRecommendations(user)
        val highlights = fetchTwitchVideoRecommendations(user, "highlight", "highlight")
        return pastBroadcasts + clips + highlights
    }

    private fun twitchProfileMediaMarker(url: String): String? {
        return Regex("[?&]cloudstream_twitch_media=([^&#]+)").find(url)?.groupValues?.getOrNull(1)
    }

    private fun stripTwitchProfileMediaMarker(url: String): String {
        return url
            .replace(Regex("([?&])cloudstream_twitch_media=[^&#]*&?"), "$1")
            .replace("?&", "?")
            .trimEnd('?', '&')
    }

    private suspend fun twitchProfileMediaLoadResponse(url: String): LoadResponse {
        val marker = twitchProfileMediaMarker(url).orEmpty()
        val clipVideoUrl = twitchProfileMediaParam(url, "cs_clip_video")?.takeIf { isTwitchClipDirectVideoUrl(it) }
        val cleanUrl = stripTwitchProfileMediaMarker(url)

        val fallbackTitle = when (marker) {
            "clip" -> "Twitch Clip"
            "highlight" -> "Twitch Highlight"
            else -> "Twitch Past Broadcast"
        }
        val title = twitchProfileMediaParam(url, "cs_title") ?: fallbackTitle
        val mediaTag = when (marker) {
            "clip" -> "Clip"
            "highlight" -> "Highlight"
            else -> "Past Broadcast"
        }

        val age = twitchProfileMediaParam(url, "cs_age")
        val views = twitchProfileMediaParam(url, "cs_views")
        val duration = twitchProfileMediaParam(url, "cs_duration")
        val streamerLogin = twitchProfileMediaParam(url, "cs_streamer_login")
        val streamerName = twitchProfileMediaParam(url, "cs_streamer_name") ?: streamerLogin
        val streamerAvatar = twitchProfileMediaParam(url, "cs_streamer_avatar")
            ?: fetchTwitchProfileAvatar(streamerLogin)

        val playerUrl = appendTwitchPlayerStreamerMeta(
            cleanUrl,
            streamerLogin,
            streamerName,
            streamerAvatar,
        )

        return newLiveStreamLoadResponse(title, playerUrl, playerUrl) {
            plot = "Open this Twitch $mediaTag."
            this@newLiveStreamLoadResponse.tags = listOfNotNull("Twitch", mediaTag, age, views, duration)
        }
    }

// END TWITCH_PROFILE_MEDIA_PROVIDER_ROWS
    private fun ChannelSummary.toChannelCard(): LiveSearchResponse {
        val resultUrl = appendTwitchStreamerMeta(channel, displayName, channel, image)
        return newLiveSearchResponse(displayName, resultUrl, TvType.Live, fix = false) {
            posterUrl = image?.ifBlank { null }
            lang = language
        }
    }

    private fun statusCard(title: String, code: String): LiveSearchResponse {
        return newLiveSearchResponse(title, "$statusPrefix$code", TvType.Live, fix = false) {
            lang = "Status"
        }
    }

    private fun setupRequiredCard(): LiveSearchResponse {
        return statusCard("Twitch API setup needed - add GitHub Actions secrets", "setup")
    }

    private fun apiErrorCard(message: String): LiveSearchResponse {
        val safe = if (message.isBlank()) "Twitch API request failed" else message
        return statusCard("Twitch API error - open for details", "api-error/${encode(safe)}")
    }

    override suspend fun load(url: String): LoadResponse {
        val action = parseActionUrl(url)
        return when (action?.first) {
            "add", "remove" -> statusResponse("built-in-favorites")
            "status" -> statusResponse(action.second)
            else -> when {
                twitchProfileMediaMarker(url) != null && !isTwitchVideoUrl(url) -> twitchProfileMediaLoadResponse(url)
                isTwitchVideoUrl(url) -> videoLoadResponse(url)
                else -> channelLoadResponse(url)
            }
        }
    }

    private fun parseActionUrl(url: String): Pair<String, String>? {
        val cleanUrl = url
            .substringBefore('?')
            .substringBefore('#')
            .trim()
            .trimEnd('/')

        fun pathFor(marker: String): String? {
            val markerBase = "$mainUrl/$marker"
            return when {
                cleanUrl.startsWith(markerBase) -> cleanUrl.removePrefix(markerBase).trimStart('/')
                cleanUrl.contains("/$marker/") -> cleanUrl.substringAfter("/$marker/")
                cleanUrl.startsWith("$marker/") -> cleanUrl.removePrefix("$marker/")
                cleanUrl == marker -> "status/setup"
                else -> null
            }
        }

        val actionPath = pathFor(actionMarker) ?: pathFor(legacyApiActionMarker) ?: pathFor(legacyApiActionMarkerV33) ?: pathFor(olderApiActionMarker) ?: pathFor(oldestApiActionMarker) ?: pathFor(legacyActionMarker) ?: return null
        val rawAction = actionPath.substringBefore('/').ifBlank { "status" }
        val value = actionPath.substringAfter('/', "")

        // Important: old builds used noop/help URLs. Never let those fall through
        // to channelLoadResponse, because that is what caused Retry Connection pages.
        val action = when (rawAction) {
            "add", "remove", "status" -> rawAction
            "noop", "help", "live" -> "status"
            else -> "status"
        }
        return action to value
    }

    private suspend fun statusResponse(code: String): LoadResponse {
        val title: String
        val message: String
        val tags: List<String>
        when {
            code.startsWith("api-error/") -> {
                title = "Twitch API error"
                message = decode(code.substringAfter("api-error/", "Twitch API request failed"))
                tags = listOf("Status", "API")
            }
            code == "setup" -> {
                title = "Twitch API setup needed"
                message = "This v3.5 build did not receive Twitch API credentials. Add TWITCH_CLIENT_ID and TWITCH_CLIENT_SECRET to GitHub Actions secrets and rebuild. The workflow should fail if either secret is missing."
                tags = listOf("Setup", "Twitch API")
            }
            code == "no-favorites" -> {
                title = "No Twitch favorites yet"
                message = "Search for a streamer, open their page, and use CloudStream's normal favorite button. Existing normal Twitch-plugin favorites are imported automatically."
                tags = listOf("Status")
            }
            code == "channel-not-found" -> {
                title = "Channel not found"
                message = "Twitch did not return a matching channel for that name. Try searching again with the exact Twitch login."
                tags = listOf("Status")
            }
            code == "built-in-favorites" -> {
                title = "Use CloudStream Favorites"
                message = "v3.5 removed the custom Add/Remove cards. Search/open a streamer and use CloudStream's normal favorite button instead. Live Now imports those built-in favorites automatically."
                tags = listOf("Status", "Favorites")
            }
            else -> {
                title = "No saved favorites are live right now"
                message = "API v3.5 is active. Your saved/imported Twitch favorites are checked with Twitch Helix, not TwitchTracker. Offline streamers stay hidden until they go live. Live Now uses a conservative 5-minute cache and auto-refreshes the UI without forcing extra API calls."
                tags = listOf("Status")
            }
        }

        return newLiveStreamLoadResponse(title, "$statusPrefix$code", twitchUrl("twitch")) {
            plot = message
            this@newLiveStreamLoadResponse.tags = tags
        }
    }

        private suspend fun videoLoadResponse(url: String): LoadResponse {
        val videoId = extractVideoId(url)
        if (videoId.isBlank()) return statusResponse("channel-not-found")

        val video = fetchVideo(videoId)
        val streamerLogin = normalizeChannel(video?.user_login.orEmpty())
        val streamerInfo = if (streamerLogin.isNotBlank()) fetchChannel(streamerLogin) else null
        val streamerDisplayName = video?.user_name?.takeIf { it.isNotBlank() }
            ?: streamerInfo?.displayName
            ?: streamerLogin
        val streamerAvatar = fetchTwitchProfileAvatar(streamerLogin)

        val baseVideoUrl = appendTwitchStreamerMeta(
            twitchVideoUrl(videoId),
            streamerDisplayName,
            streamerLogin,
            streamerAvatar,
        )
        val playerVideoUrl = appendTwitchPlayerStreamerMeta(
            baseVideoUrl,
            streamerLogin,
            streamerDisplayName,
            streamerAvatar,
        )

        val title = cleanTwitchText(video?.title) ?: "Past broadcast"
        val poster = cacheBustImage(resizeTwitchImage(video?.thumbnail_url, 640, 360), nowMs())
        val profileRecommendations = fetchTwitchProfileRecommendations(streamerLogin)

        val tagList = listOfNotNull(
            "Past Broadcast",
            video?.duration?.ifBlank { null },
            formatTwitchVideoDate(video?.published_at),
            formatViewCount(video?.view_count),
            video?.language?.ifBlank { null },
        )

        return newLiveStreamLoadResponse(title, playerVideoUrl, playerVideoUrl) {
            plot = listOfNotNull(
                "Past broadcast",
                cleanTwitchText(video?.description),
            ).joinToString("\n\n").ifBlank { null }
            posterUrl = poster?.ifBlank { null }
            backgroundPosterUrl = poster?.ifBlank { null }
            this@newLiveStreamLoadResponse.tags = tagList
            recommendations = profileRecommendations
        }
    }
private suspend fun channelLoadResponse(url: String): LoadResponse {
        val channel = normalizeChannel(url)
        val info = fetchChannel(channel) ?: return statusResponse("channel-not-found")
        val statusTag = if (info.isLive) "LIVE NOW" else "OFFLINE"
        val profileRecommendations = fetchTwitchProfileRecommendations(info.channel)
        val tagList = listOfNotNull(
            statusTag,
            info.gameName,
            formatViewerCount(info.viewerCount),
            info.language,
        )
        val streamerAvatar = fetchTwitchProfileAvatar(info.channel)
        val streamUrl = appendTwitchStreamerMeta(
            twitchUrl(info.channel),
            info.displayName,
            info.channel,
            cacheBustImage(streamerAvatar, nowMs()),
        )

        return newLiveStreamLoadResponse(info.displayName, streamUrl, streamUrl) {
            plot = info.profilePlot()
            posterUrl = cacheBustImage(info.image, nowMs())
            backgroundPosterUrl = cacheBustImage(info.poster, nowMs())
            this@newLiveStreamLoadResponse.tags = tagList
            recommendations = profileRecommendations
        }
    }

    private suspend fun searchChannels(query: String): List<ChannelSummary> {
        if (!hasTwitchCredentials()) return emptyList()
        val response = twitchGet<TwitchSearchResponse>(
            buildHelixUrl(
                "search/channels",
                extra = mapOf("query" to query, "first" to "50", "live_only" to "false"),
            ),
        ) ?: return emptyList()

        return response.data
            .mapNotNull { item ->
                val channel = normalizeChannel(item.broadcaster_login)
                if (channel.isBlank()) return@mapNotNull null
                ChannelSummary(
                    channel = channel,
                    displayName = item.display_name.ifBlank { channel },
                    image = item.thumbnail_url.ifBlank { null },
                    language = item.broadcaster_language.ifBlank { null },
                    isLive = item.is_live,
                    title = item.title.ifBlank { null },
                )
            }
            .distinctBy { it.channel }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        if (!hasTwitchCredentials()) {
            return listOf(setupRequiredCard())
        }

        val results = searchChannels(query)
        val fallbackStatus = if (results.isEmpty() && lastTwitchApiError != null) {
            listOf(apiErrorCard(lastTwitchApiError.orEmpty()))
        } else {
            emptyList()
        }

        return (results.map { it.toChannelCard() } + fallbackStatus)
            .distinctBy { it.url }
    }


    private fun extractTwitchClipSlug(url: String): String {
        val clean = stripTwitchProfileMediaMarker(url)
            .substringBefore("?")
            .substringBefore("#")
            .trim()
            .trimEnd('/')
        return when {
            clean.contains("clips.twitch.tv/", ignoreCase = true) -> clean.substringAfterLast("/")
            clean.contains("/clip/", ignoreCase = true) -> clean.substringAfterLast("/clip/").substringAfterLast("/")
            else -> ""
        }.trim().trim('/')
    }

    private fun isTwitchClipPageUrl(url: String): Boolean {
        val clean = url.lowercase()
        return clean.contains("clips.twitch.tv/") || clean.contains("/clip/") || twitchProfileMediaMarker(url) == "clip"
    }

    private suspend fun twitchClipFallbackLink(url: String, label: String = "Twitch Clip"): ExtractorLink {
        return newExtractorLink(name, label, url) {
            this.type = ExtractorLinkType.VIDEO
            this.quality = getQualityFromName("720p")
            this.referer = "https://clips.twitch.tv/"
            this.headers = mapOf("User-Agent" to twitchWebUserAgent)
        }
    }
    private fun signedTwitchClipSourceUrl(sourceUrl: String, token: TwitchPlaybackAccessToken?): String? {
        val source = sourceUrl.ifBlank { null } ?: return null
        val nonNullToken = token ?: return null
        val signature = nonNullToken.signature.ifBlank { null } ?: return null
        val value = nonNullToken.value.ifBlank { null } ?: return null
        val separator = if (source.contains("?")) "&" else "?"
        return "$source${separator}sig=${encode(signature)}&token=${encode(value)}"
    }

    private suspend fun fetchTwitchClipLinks(url: String): List<ExtractorLink> {
        val slug = extractTwitchClipSlug(url)
        val direct = twitchProfileMediaParam(url, "cs_clip_video")?.takeIf { isTwitchClipDirectVideoUrl(it) }
        val directFallback = if (slug.isBlank()) {
            direct?.let { listOf(twitchClipFallbackLink(it, "$name Clip fallback")) } ?: emptyList()
        } else {
            emptyList()
        }
        if (slug.isBlank()) return directFallback

        val response: TwitchGqlClipPlaybackResponse? = twitchGqlPost(
            mapOf(
                "operationName" to "VideoAccessToken_Clip",
                "variables" to mapOf("slug" to slug),
                "extensions" to mapOf(
                    "persistedQuery" to mapOf(
                        "version" to 1,
                        "sha256Hash" to "36b89d2507fce29e5ca551df756d27c1cfe079e2609642b4390aa4c35796eb11",
                    ),
                ),
            ),
        )

        val clip = response?.data?.clip ?: return directFallback
        val token = clip.playbackAccessToken
        val links = clip.videoQualities.orEmpty().mapNotNull { item ->
            val source = item.sourceURL?.ifBlank { null } ?: return@mapNotNull null
            val signedSource = signedTwitchClipSourceUrl(source, token) ?: return@mapNotNull null
            val rawQuality = item.quality?.ifBlank { null }.orEmpty()
            val qualityLabel = when {
                rawQuality.isBlank() -> "source"
                rawQuality.endsWith("p", ignoreCase = true) -> rawQuality
                else -> "${rawQuality}p"
            }
            val quality = getQualityFromName(qualityLabel)
            newExtractorLink(name, "$name Clip $qualityLabel", signedSource) {
                this.type = ExtractorLinkType.VIDEO
                this.quality = quality
                this.referer = "https://clips.twitch.tv/"
                this.headers = mapOf(
                    "User-Agent" to twitchWebUserAgent,
                    "Referer" to "https://clips.twitch.tv/",
                )
            }
        }.distinctBy { it.url }

        return links.ifEmpty { directFallback }
    }

    // LiveNowUsesCurrentPastBroadcastPatch:
    // When a live channel is opened, prefer the channel's current archive VOD.
    // That gives the player a seekable DVR-style playlist so the user can rewind
    // and then seek back near the live edge. If Twitch has not exposed the current
    // archive yet, normal live HLS fallback is used below.
    private fun twitchIsoToMillis(value: String?): Long? {
        val clean = value?.ifBlank { null } ?: return null
        return runCatching {
            val parser = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            parser.timeZone = java.util.TimeZone.getTimeZone("UTC")
            parser.parse(clean)?.time
        }.getOrNull()
    }

    private suspend fun fetchCurrentLivePastBroadcast(channelOrUrl: String): TwitchVideo? {
        val normalized = normalizeChannel(channelOrUrl)
        if (normalized.isBlank() || !hasTwitchCredentials()) return null

        val stream = fetchStreams(listOf(normalized))?.get(normalized) ?: return null
        val userId = stream.user_id.ifBlank {
            cachedLiveFavorites.firstOrNull { it.channel == normalized }?.userId.orEmpty()
        }.ifBlank {
            fetchUsers(listOf(normalized))[normalized]?.id.orEmpty()
        }

        if (userId.isBlank()) return null

        val response = twitchGet<TwitchVideosResponse>(
            buildHelixUrl(
                "videos",
                extra = mapOf(
                    "user_id" to userId,
                    "first" to "5",
                    "type" to "archive",
                ),
            ),
        ) ?: return null

        val streamStartedAtMs = twitchIsoToMillis(stream.started_at)
        val now = nowMs()
        val generousStartWindowMs = 2L * 60L * 60L * 1000L
        val tightStartWindowMs = 35L * 60L * 1000L

        return response.data
            .asSequence()
            .filter { video ->
                video.id.any { it.isDigit() } &&
                        (video.type.isBlank() || video.type.equals("archive", ignoreCase = true))
            }
            .sortedByDescending { video ->
                twitchIsoToMillis(video.created_at)
                    ?: twitchIsoToMillis(video.published_at)
                    ?: 0L
            }
            .firstOrNull { video ->
                val videoStartedAtMs = twitchIsoToMillis(video.created_at)
                    ?: twitchIsoToMillis(video.published_at)
                    ?: return@firstOrNull true

                when {
                    streamStartedAtMs == null -> true
                    kotlin.math.abs(videoStartedAtMs - streamStartedAtMs) <= tightStartWindowMs -> true
                    videoStartedAtMs <= now && videoStartedAtMs >= streamStartedAtMs - generousStartWindowMs -> true
                    else -> false
                }
            }
    }

    private suspend fun fetchCurrentLivePastBroadcastLinks(channelOrUrl: String): List<ExtractorLink> {
        val currentVod = fetchCurrentLivePastBroadcast(channelOrUrl) ?: return emptyList()
        val videoId = currentVod.id.filter { it.isDigit() }
        if (videoId.isBlank()) return emptyList()
        return fetchTwitchVodLinks(videoId)
    }
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (!data.startsWith("http", ignoreCase = true)) return false

        suspend fun emit(links: List<ExtractorLink>): Boolean {
            val annotated = annotateTwitchPlayerLinks(data, links)
            annotated.forEach { callback.invoke(it) }
            return annotated.isNotEmpty()
        }

        if (isTwitchClipPageUrl(data)) {
            return emit(fetchTwitchClipLinks(data))
        }

        if (isTwitchClipDirectVideoUrl(data)) {
            return emit(
                listOf(
                    newExtractorLink(
                        name,
                        "$name Clip",
                        data,
                    ) {
                        this.type = ExtractorLinkType.VIDEO
                        this.quality = getQualityFromName("720p")
                        this.referer = "https://clips.twitch.tv/"
                    },
                ),
            )
        }

        if (isTwitchVideoUrl(data)) {
            return emit(fetchTwitchVodLinks(extractVideoId(data)))
        }

        val liveDvrLinks = fetchCurrentLivePastBroadcastLinks(data)
        if (liveDvrLinks.isNotEmpty()) {
            return emit(liveDvrLinks)
        }

        val response = runCatching {
            app.get("https://pwn.sh/tools/streamapi.py?url=$data").parsed<ApiResponse>()
        }.getOrNull() ?: return false

        val links = response.urls?.map { (qualityName, streamUrl) ->
            val quality = getQualityFromName(qualityName.substringBefore("p"))
            newExtractorLink(
                name,
                "$name ${qualityName.replace("${quality}p", "")}",
                streamUrl,
            ) {
                this.type = ExtractorLinkType.M3U8
                this.quality = quality
                this.referer = ""
            }
        }.orEmpty()

        return emit(links)
    }
}

