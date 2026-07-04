package recloudstream.twitchlivefavorites

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
import java.net.URLDecoder
import java.net.URLEncoder

class TwitchApiLiveFavoritesProvider : MainAPI() {
    override var mainUrl = "https://twitch.tv"
    override var name = "Twitch"
    private val legacyProviderNames = setOf("twitch live favorites api")
    private val starterFavoriteChannels = listOf("zfg247", "monstercat", "nasa")
    private val startupFavoriteChannels = listOf("zfg247")
    private val startupFavoriteSeedKey = "twitch_startup_favorites_seeded_v1"
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
    private val liveCacheTtlMs = 5 * 60 * 1000L
    private val autoRefreshIntervalMs = 5 * 60 * 1000L
    private val failedApiRetryDelayMs = 60 * 1000L

    private var cachedAccessToken: String? = null
    private var cachedAccessTokenExpiresAtMs: Long = 0L
    private var cachedLiveKey: String = ""
    private var cachedLiveExpiresAtMs: Long = 0L
    private var cachedLiveFavorites: List<FavoriteChannel> = emptyList()
    private var cachedLiveUpdatedAtMs: Long = 0L
    private var rateLimitedUntilMs: Long = 0L
    private var lastTwitchApiError: String? = null
    @Volatile private var lastHomeRenderedAtMs: Long = 0L
    @Volatile private var autoRefreshScheduledAtMs: Long = 0L
    @Volatile private var autoRefreshScheduledForKey: String = ""

    override val mainPage = mainPageOf(
        "$actionBase/live" to liveFavoritesNowName,
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureStartupFavoritesSaved()
        val savedFavorites = getSavedFavoriteChannels()
        val usingStarterFavorites = savedFavorites.isEmpty()
        val favorites = if (usingStarterFavorites) starterFavoriteChannels else savedFavorites

        val items = when {
            !hasTwitchCredentials() -> listOf(setupRequiredCard())
            favorites.isEmpty() -> listOf(statusCard("Search Twitch to add favorites", "no-favorites"))
            else -> {
                val liveFavorites = fetchLiveFavoriteChannels(favorites)
                maybeScheduleAutoRefresh(favoritesCacheKey(favorites))

                when {
                    liveFavorites == null -> listOf(apiErrorCard(lastTwitchApiError.orEmpty()))
                    liveFavorites.isNotEmpty() -> liveFavorites.map { it.toChannelCard(showOfflineLabel = false, directPlay = true) }
                    usingStarterFavorites -> favorites.map {
                        fallbackChannel(it).toChannelCard(showOfflineLabel = true, directPlay = true)
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
        val displayName: String,
        val image: String? = null,
        val language: String? = null,
        val isLive: Boolean = false,
        val title: String? = null,
    )

    private data class FavoriteChannel(
        val channel: String,
        val displayName: String,
        val image: String?,
        val poster: String?,
        val isLive: Boolean,
        val language: String?,
        val rank: Int?,
        val description: String?,
        val gameName: String? = null,
        val viewerCount: Int? = null,
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
private data class TwitchSearchResponse(
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
        return TwitchCredentials.CLIENT_ID.isNotBlank() && TwitchCredentials.CLIENT_SECRET.isNotBlank()
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
     * Best-effort request for CloudStream to reload the visible home provider.
     * These are CloudStream internals, so every call is deliberately guarded.
     */
    private fun requestUiRefresh() {
        TwitchHomeRefreshFocus.requestFocusFirstLiveNow()

        runCatching { MainActivity.reloadHomeEvent(true) }
    }

    /**
     * API-safe auto-refresh: schedule one delayed UI reload after the row renders.
     * The timer does not call Twitch directly and does not force-expire cache.
     * That caps Live Now checks to roughly one Helix stream request per cache TTL
     * while this provider keeps being re-rendered.
     */
    private fun maybeScheduleAutoRefresh(cacheKey: String) {
        if (cacheKey.isBlank()) return
        val now = nowMs()
        lastHomeRenderedAtMs = now

        val existingScheduleStillPending =
            autoRefreshScheduledForKey == cacheKey && now < autoRefreshScheduledAtMs + autoRefreshIntervalMs
        if (existingScheduleStillPending) return

        autoRefreshScheduledForKey = cacheKey
        autoRefreshScheduledAtMs = now

        Thread {
            runCatching { Thread.sleep(autoRefreshIntervalMs) }
            val elapsedSinceRender = nowMs() - lastHomeRenderedAtMs
            val providerWasRecentlyRendered = elapsedSinceRender <= autoRefreshIntervalMs + 60_000L
            if (autoRefreshScheduledForKey == cacheKey && providerWasRecentlyRendered && !isBackoffActive()) {
                requestUiRefresh()
            }
        }.apply {
            name = "TwitchLiveFavoritesAutoRefresh"
            isDaemon = true
            start()
        }
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
            image = profile ?: preview,
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
    private fun directPlayUrl(channel: String): String {
        return "${twitchUrl(channel)}?cloudstream_direct_play=1"
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
                    "first" to "12",
                    "type" to "archive",
                ),
            ),
        ) ?: return emptyList()

        return response.data
            .mapNotNull { it.toPastBroadcastCard() }
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
        val displayTitle = if (showOfflineLabel && !isLive) "$displayName (offline)" else displayName
        val resultUrl = if (directPlay && isLive) directPlayUrl(channel) else channel

        return newLiveSearchResponse(displayTitle, resultUrl, TvType.Live, fix = false) {
            posterUrl = cacheBustImage(poster ?: image)
            lang = subtitle() ?: language
        }
    }

    private fun ChannelSummary.toChannelCard(): LiveSearchResponse {
        return newLiveSearchResponse(displayName, channel, TvType.Live, fix = false) {
            posterUrl = image
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
            else -> if (isTwitchVideoUrl(url)) videoLoadResponse(url) else channelLoadResponse(url)
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
        val videoUrl = twitchVideoUrl(videoId)
        val title = cleanTwitchText(video?.title) ?: "Past broadcast"
        val poster = cacheBustImage(resizeTwitchImage(video?.thumbnail_url, 640, 360), nowMs())
        val tagList = listOfNotNull(
            "Past Broadcast",
            video?.duration?.ifBlank { null },
            formatTwitchVideoDate(video?.published_at),
            formatViewCount(video?.view_count),
            video?.language?.ifBlank { null },
        )

        return newLiveStreamLoadResponse(title, videoUrl, videoUrl) {
            plot = listOfNotNull(
                "Past broadcast",
                cleanTwitchText(video?.description),
            ).joinToString("\n\n").ifBlank { null }
            posterUrl = poster
            backgroundPosterUrl = poster
            this@newLiveStreamLoadResponse.tags = tagList
            this@newLiveStreamLoadResponse.recommendations = fetchRecentPastBroadcasts(info.channel, info.userId)
        }
    }
private suspend fun channelLoadResponse(url: String): LoadResponse {
        val channel = normalizeChannel(url)
        val info = fetchChannel(channel) ?: return statusResponse("channel-not-found")
        val statusTag = if (info.isLive) "LIVE NOW" else "OFFLINE"
        val tagList = listOfNotNull(
            statusTag,
            info.gameName,
            formatViewerCount(info.viewerCount),
            info.language,
        )
        val streamUrl = twitchUrl(info.channel)

        return newLiveStreamLoadResponse(info.displayName, streamUrl, streamUrl) {
            plot = info.profilePlot()
            posterUrl = cacheBustImage(info.image, nowMs())
            backgroundPosterUrl = cacheBustImage(info.poster, nowMs())
            this@newLiveStreamLoadResponse.tags = tagList
        }
    }

    private suspend fun searchChannels(query: String): List<ChannelSummary> {
        if (!hasTwitchCredentials()) return emptyList()
        val response = twitchGet<TwitchSearchResponse>(
            buildHelixUrl(
                "search/channels",
                extra = mapOf("query" to query, "first" to "20", "live_only" to "false"),
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (!data.startsWith("http", ignoreCase = true)) return false

        val response = runCatching {
            app.get("https://pwn.sh/tools/streamapi.py?url=$data").parsed<ApiResponse>()
        }.getOrNull() ?: return false

        var found = false
        response.urls?.forEach { (qualityName, streamUrl) ->
            val quality = getQualityFromName(qualityName.substringBefore("p"))
            callback.invoke(
                newExtractorLink(
                    name,
                    "$name ${qualityName.replace("${quality}p", "")}",
                    streamUrl,
                ) {
                    this.type = ExtractorLinkType.M3U8
                    this.quality = quality
                    this.referer = ""
                },
            )
            found = true
        }
        return found
    }
}
