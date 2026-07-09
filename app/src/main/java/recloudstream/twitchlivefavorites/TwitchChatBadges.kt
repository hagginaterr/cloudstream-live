package recloudstream.twitchlivefavorites

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Official Twitch chat badge resolver.
 *
 * Uses Helix Get Global Chat Badges and Get Channel Chat Badges so subscriber,
 * bits, VIP, moderator, broadcaster, Prime, partner, and channel-specific badge
 * art comes from Twitch's own badge image URLs.
 */
object TwitchChatBadges {
    private const val HELIX_BASE = "https://api.twitch.tv/helix"
    private const val USER_AGENT = "CloudStream Live Twitch Badges"
    private const val GLOBAL_TTL_MS = 6L * 60L * 60L * 1000L
    private const val CHANNEL_TTL_MS = 30L * 60L * 1000L
    private const val USER_TTL_MS = 6L * 60L * 60L * 1000L

    private val client: OkHttpClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OkHttpClient.Builder()
            .callTimeout(6, TimeUnit.SECONDS)
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    data class BadgeEntry(
        val setId: String,
        val version: String,
        val imageUrl: String,
        val title: String,
    )

    class Resolver internal constructor(entries: List<BadgeEntry>) {
        private val byExactKey: Map<String, BadgeEntry> = entries.associateBy { key(it.setId, it.version) }
        private val bySet: Map<String, List<BadgeEntry>> = entries.groupBy { it.setId.lowercase(Locale.US) }

        fun imageUrlFor(rawBadge: String): String? {
            val keyParts = parseBadge(rawBadge) ?: return fallbackImageUrl(rawBadge)
            byExactKey[key(keyParts.first, keyParts.second)]?.imageUrl?.let { return it }

            val requestedTier = keyParts.second.toLongOrNull()
            if (requestedTier != null) {
                val tierMatch = bySet[keyParts.first]
                    .orEmpty()
                    .mapNotNull { entry -> entry.version.toLongOrNull()?.let { tier -> tier to entry } }
                    .filter { (tier, _) -> tier <= requestedTier }
                    .maxByOrNull { (tier, _) -> tier }
                    ?.second
                if (tierMatch != null) return tierMatch.imageUrl
            }

            val defaultMatch = byExactKey[key(keyParts.first, "1")]
                ?: byExactKey[key(keyParts.first, "0")]
                ?: bySet[keyParts.first].orEmpty().firstOrNull()
            return defaultMatch?.imageUrl ?: fallbackImageUrl(rawBadge)
        }
    }

    private data class CacheEntry<T>(val expiresAtMs: Long, val value: T)

    private val lock = Any()
    private var globalBadges: CacheEntry<List<BadgeEntry>>? = null
    private val channelBadges = mutableMapOf<String, CacheEntry<List<BadgeEntry>>>()
    private val channelIds = mutableMapOf<String, CacheEntry<String>>()

    suspend fun loadForChannel(channelLogin: String?): Resolver = withContext(Dispatchers.IO) {
        val token = TwitchAccountAuth.getValidAccessToken()
            ?.trim()
            ?.removePrefix("oauth:")
            .orEmpty()
        val clientId = TwitchCredentials.CLIENT_ID.trim()
        if (token.isBlank() || clientId.isBlank()) {
            return@withContext Resolver(emptyList())
        }

        val global = getGlobalBadges(token, clientId)
        val channel = channelLogin
            ?.let { TwitchLiveChat.normalizeChannelLogin(it) }
            ?.let { login -> getChannelBadges(login, token, clientId) }
            .orEmpty()

        // Channel badge definitions override generic definitions when Twitch returns both.
        Resolver(global + channel)
    }

    fun parseBadge(rawBadge: String?): Pair<String, String>? {
        val normalized = rawBadge
            ?.trim()
            ?.lowercase(Locale.US)
            ?.takeIf { it.isNotBlank() }
            ?: return null

        return when (normalized) {
            "sub", "subscriber" -> "subscriber" to "0"
            "bits" -> "bits" to "1"
            "mod", "moderator" -> "moderator" to "1"
            "vip" -> "vip" to "1"
            "prime", "premium" -> "premium" to "1"
            "partner" -> "partner" to "1"
            "broadcaster", "live" -> "broadcaster" to "1"
            else -> {
                val setId = normalized.substringBefore('/').trim()
                val version = normalized.substringAfter('/', "1").trim().ifBlank { "1" }
                if (setId.isBlank()) null else setId to version
            }
        }
    }

    fun fallbackImageUrl(rawBadge: String): String? {
        val (setId, version) = parseBadge(rawBadge) ?: return null
        val exact = fallbackBadgeUrls[key(setId, version)]
        if (exact != null) return exact
        return fallbackBadgeUrls[key(setId, "1")]
            ?: fallbackBadgeUrls[key(setId, "0")]
            ?: fallbackBadgeUrls[setId]
    }

    fun badgeTitle(rawBadge: String): String {
        val (setId, version) = parseBadge(rawBadge) ?: return rawBadge
        return when (setId) {
            "broadcaster" -> "Broadcaster"
            "moderator" -> "Moderator"
            "vip" -> "VIP"
            "subscriber" -> if (version == "0") "Subscriber" else "Subscriber $version"
            "founder" -> "Founder"
            "premium" -> "Prime"
            "partner" -> "Partner"
            "bits", "bits-leader", "bits-charity" -> "Bits $version"
            "staff" -> "Twitch Staff"
            "turbo" -> "Turbo"
            else -> setId.replace('-', ' ').replaceFirstChar { it.titlecase(Locale.US) }
        }
    }

    private fun getGlobalBadges(token: String, clientId: String): List<BadgeEntry> {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            globalBadges?.takeIf { it.expiresAtMs > now }?.value?.let { return it }
        }

        val fresh = fetchBadgeEndpoint("$HELIX_BASE/chat/badges/global", token, clientId)
        synchronized(lock) {
            globalBadges = CacheEntry(now + GLOBAL_TTL_MS, fresh)
        }
        return fresh
    }

    private fun getChannelBadges(login: String, token: String, clientId: String): List<BadgeEntry> {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            channelBadges[login]?.takeIf { it.expiresAtMs > now }?.value?.let { return it }
        }

        val broadcasterId = getChannelId(login, token, clientId) ?: return emptyList()
        val encodedId = URLEncoder.encode(broadcasterId, "UTF-8")
        val fresh = fetchBadgeEndpoint("$HELIX_BASE/chat/badges?broadcaster_id=$encodedId", token, clientId)
        synchronized(lock) {
            channelBadges[login] = CacheEntry(now + CHANNEL_TTL_MS, fresh)
        }
        return fresh
    }

    private fun getChannelId(login: String, token: String, clientId: String): String? {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            channelIds[login]?.takeIf { it.expiresAtMs > now }?.value?.let { return it }
        }

        val encodedLogin = URLEncoder.encode(login, "UTF-8")
        val request = Request.Builder()
            .url("$HELIX_BASE/users?login=$encodedLogin")
            .header("Authorization", "Bearer $token")
            .header("Client-Id", clientId)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()

        val id = runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string().orEmpty()
                JSONObject(body)
                    .optJSONArray("data")
                    ?.optJSONObject(0)
                    ?.optString("id")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()

        if (!id.isNullOrBlank()) {
            synchronized(lock) {
                channelIds[login] = CacheEntry(now + USER_TTL_MS, id)
            }
        }
        return id
    }

    private fun fetchBadgeEndpoint(url: String, token: String, clientId: String): List<BadgeEntry> {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Client-Id", clientId)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                parseBadgeResponse(response.body?.string().orEmpty())
            }
        }.getOrElse { emptyList() }
    }

    private fun parseBadgeResponse(body: String): List<BadgeEntry> {
        val data = runCatching { JSONObject(body).optJSONArray("data") }.getOrNull() ?: return emptyList()
        val out = mutableListOf<BadgeEntry>()
        for (i in 0 until data.length()) {
            val set = data.optJSONObject(i) ?: continue
            val setId = set.optString("set_id").trim().lowercase(Locale.US)
            if (setId.isBlank()) continue
            val versions = set.optJSONArray("versions") ?: continue
            for (j in 0 until versions.length()) {
                val version = versions.optJSONObject(j) ?: continue
                val id = version.optString("id").trim().ifBlank { "1" }
                val imageUrl = version.optString("image_url_2x")
                    .ifBlank { version.optString("image_url_4x") }
                    .ifBlank { version.optString("image_url_1x") }
                    .trim()
                if (imageUrl.isBlank()) continue
                out += BadgeEntry(
                    setId = setId,
                    version = id,
                    imageUrl = imageUrl,
                    title = version.optString("title").ifBlank { badgeTitle("$setId/$id") },
                )
            }
        }
        return out
    }

    private fun key(setId: String, version: String): String {
        return setId.lowercase(Locale.US).trim() + "/" + version.lowercase(Locale.US).trim()
    }

    private val fallbackBadgeUrls = mapOf(
        key("staff", "1") to "https://static-cdn.jtvnw.net/badges/v1/d97c37bd-a6f5-4c38-8f57-4e4bef88af34/2",
        key("partner", "1") to "https://static-cdn.jtvnw.net/badges/v1/d12a2e27-16f6-41d0-ab77-b780518f00a3/2",
        key("premium", "1") to "https://static-cdn.jtvnw.net/badges/v1/bbbe0db0-a598-423e-86d0-f9fb98ca1933/2",
        key("broadcaster", "1") to "https://static-cdn.jtvnw.net/badges/v1/5527c58c-fb7d-422d-b71b-f309dcb85cc1/2",
        key("moderator", "1") to "https://static-cdn.jtvnw.net/badges/v1/3267646d-33f0-4b17-b3df-f923a41db1d0/2",
        key("vip", "1") to "https://static-cdn.jtvnw.net/badges/v1/b817aba4-fad8-49e2-b88a-7cc744dfa6ec/2",
        key("founder", "1") to "https://static-cdn.jtvnw.net/badges/v1/511b78a9-ab37-472f-9569-457753bbe7d3/2",
        key("artist-badge", "1") to "https://static-cdn.jtvnw.net/badges/v1/4300a897-03dc-4e83-8c0e-c332fee7057f/2",
        key("subscriber", "0") to "https://static-cdn.jtvnw.net/badges/v1/5d9f2208-5dd8-11e7-8513-2ff4adfae661/2",
        key("bits", "1") to "https://static-cdn.jtvnw.net/badges/v1/743a0f3b-84b3-450b-96a0-503d7f4a9764/2",
        key("turbo", "1") to "https://static-cdn.jtvnw.net/badges/v1/1df85c53-1135-4d3e-a9f4-7a4df3ee6b53/2",
    )
}