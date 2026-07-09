package recloudstream.twitchlivefavorites

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

data class TwitchChatEmote(
    val code: String,
    val imageUrl: String,
    val start: Int,
    val endExclusive: Int,
    val provider: String,
)

object TwitchChatEmotes {
    private const val USER_AGENT = "CloudStream Live Twitch Emotes"

    private const val BTTV_GLOBAL = "https://api.betterttv.net/3/cached/emotes/global"
    private const val BTTV_CHANNEL = "https://api.betterttv.net/3/cached/users/twitch/"
    private const val FFZ_GLOBAL = "https://api.frankerfacez.com/v1/set/global"
    private const val FFZ_ROOM_BY_ID = "https://api.frankerfacez.com/v1/room/id/"
    private const val FFZ_ROOM_BY_LOGIN = "https://api.frankerfacez.com/v1/room/"
    private const val SEVENTV_GLOBAL = "https://7tv.io/v3/emote-sets/global"
    private const val SEVENTV_CHANNEL = "https://7tv.io/v3/users/twitch/"
    private const val HELIX_USERS = "https://api.twitch.tv/helix/users?login="

    private const val GLOBAL_TTL_MS = 6L * 60L * 60L * 1000L
    private const val CHANNEL_TTL_MS = 30L * 60L * 1000L
    private const val FAILURE_TTL_MS = 60L * 1000L
    private const val CHANNEL_ID_TTL_MS = 6L * 60L * 60L * 1000L

    private val client: OkHttpClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OkHttpClient.Builder()
            .callTimeout(8, TimeUnit.SECONDS)
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val lock = Any()
    private var globalCache: CacheEntry<Map<String, EmoteDefinition>>? = null
    private val channelCache = mutableMapOf<String, CacheEntry<Map<String, EmoteDefinition>>>()
    private val channelIdCache = mutableMapOf<String, CacheEntry<String>>()

    private data class CacheEntry<T>(val value: T, val expiresAtMs: Long)

    internal data class EmoteDefinition(
        val code: String,
        val imageUrl: String,
        val provider: String,
    )

    class Resolver internal constructor(
        private val definitions: Map<String, EmoteDefinition>,
    ) {
        fun resolve(text: String, nativeEmotesTag: String?): List<TwitchChatEmote> {
            return resolveMessage(text, nativeEmotesTag, definitions)
        }
    }

    suspend fun loadForChannel(channelLogin: String?, channelId: String?): Resolver = withContext(Dispatchers.IO) {
        Resolver(mergeEmotes(getGlobalEmotes(), getChannelEmotes(channelLogin, channelId)))
    }

    fun resolveFromCache(
        text: String,
        nativeEmotesTag: String?,
        channelLogin: String?,
        channelId: String?,
    ): List<TwitchChatEmote> {
        val definitions = mergeEmotes(getCachedGlobalEmotes(), getCachedChannelEmotes(channelLogin, channelId))
        return resolveMessage(text, nativeEmotesTag, definitions)
    }

    private fun resolveMessage(
        text: String,
        nativeEmotesTag: String?,
        definitions: Map<String, EmoteDefinition>,
    ): List<TwitchChatEmote> {
        if (text.isBlank() && nativeEmotesTag.isNullOrBlank()) return emptyList()

        val native = parseNativeTwitchEmotes(text, nativeEmotesTag)
        val thirdParty = parseThirdPartyEmotes(text, definitions, native)
        return (native + thirdParty).sortedWith(compareBy<TwitchChatEmote> { it.start }.thenByDescending { it.endExclusive })
    }

    private fun parseNativeTwitchEmotes(text: String, raw: String?): List<TwitchChatEmote> {
        if (text.isEmpty() || raw.isNullOrBlank()) return emptyList()

        val result = mutableListOf<TwitchChatEmote>()
        for (emoteSpec in raw.split('/')) {
            val emoteId = emoteSpec.substringBefore(':', missingDelimiterValue = "").trim()
            val ranges = emoteSpec.substringAfter(':', missingDelimiterValue = "")
            if (emoteId.isBlank() || ranges.isBlank()) continue

            for (range in ranges.split(',')) {
                val start = range.substringBefore('-', missingDelimiterValue = "").toIntOrNull() ?: continue
                val endInclusive = range.substringAfter('-', missingDelimiterValue = "").toIntOrNull() ?: continue
                if (start < 0 || endInclusive < start || endInclusive >= text.length) continue

                result += TwitchChatEmote(
                    code = text.substring(start, endInclusive + 1),
                    imageUrl = "https://static-cdn.jtvnw.net/emoticons/v2/$emoteId/default/dark/2.0",
                    start = start,
                    endExclusive = endInclusive + 1,
                    provider = "twitch",
                )
            }
        }
        return result
    }

    private fun parseThirdPartyEmotes(
        text: String,
        definitions: Map<String, EmoteDefinition>,
        occupied: List<TwitchChatEmote>,
    ): List<TwitchChatEmote> {
        if (text.isEmpty() || definitions.isEmpty()) return emptyList()

        val result = mutableListOf<TwitchChatEmote>()
        var tokenStart = -1

        fun flush(end: Int) {
            if (tokenStart < 0 || end <= tokenStart) return
            val token = text.substring(tokenStart, end)
            val definition = definitions[token]
            if (definition != null && !overlaps(tokenStart, end, occupied + result)) {
                result += TwitchChatEmote(
                    code = definition.code,
                    imageUrl = definition.imageUrl,
                    start = tokenStart,
                    endExclusive = end,
                    provider = definition.provider,
                )
            }
            tokenStart = -1
        }

        for (index in 0..text.length) {
            val atEnd = index == text.length
            if (atEnd || text[index].isWhitespace()) {
                flush(index)
            } else if (tokenStart < 0) {
                tokenStart = index
            }
        }

        return result
    }

    private fun overlaps(start: Int, endExclusive: Int, emotes: List<TwitchChatEmote>): Boolean {
        return emotes.any { start < it.endExclusive && endExclusive > it.start }
    }

    private fun getCachedGlobalEmotes(): Map<String, EmoteDefinition> = synchronized(lock) {
        globalCache?.takeIf { it.expiresAtMs > now() }?.value.orEmpty()
    }

    private fun getCachedChannelEmotes(channelLogin: String?, channelId: String?): Map<String, EmoteDefinition> {
        val keys = channelCacheKeys(channelLogin, channelId)
        if (keys.isEmpty()) return emptyMap()

        synchronized(lock) {
            keys.forEach { key ->
                channelCache[key]?.takeIf { it.expiresAtMs > now() }?.value?.let { return it }
            }
        }
        return emptyMap()
    }

    private fun getGlobalEmotes(): Map<String, EmoteDefinition> {
        synchronized(lock) {
            globalCache?.takeIf { it.expiresAtMs > now() }?.value?.let { return it }
        }

        val fresh = mergeEmotes(
            fetchBttvGlobalSafe(),
            fetchFfzGlobalSafe(),
            fetchSevenTvGlobalSafe(),
        )

        synchronized(lock) {
            globalCache = CacheEntry(fresh, now() + if (fresh.isEmpty()) FAILURE_TTL_MS else GLOBAL_TTL_MS)
        }
        return fresh
    }

    private suspend fun getChannelEmotes(channelLogin: String?, channelId: String?): Map<String, EmoteDefinition> {
        val login = TwitchLiveChat.normalizeChannelLogin(channelLogin)
        val id = channelId?.filter { it.isDigit() }?.takeIf { it.isNotBlank() }
        val initialKeys = channelCacheKeys(login, id)
        synchronized(lock) {
            initialKeys.forEach { key ->
                channelCache[key]?.takeIf { it.expiresAtMs > now() }?.value?.let { return it }
            }
        }

        val resolvedId = id ?: login?.let { getChannelId(it) }
        if (login == null && resolvedId == null) return emptyMap()

        val fresh = mergeEmotes(
            resolvedId?.let { fetchBttvChannelSafe(it) }.orEmpty(),
            fetchFfzChannelSafe(login, resolvedId),
            resolvedId?.let { fetchSevenTvChannelSafe(it) }.orEmpty(),
        )

        val keys = channelCacheKeys(login, resolvedId ?: id)
        if (keys.isNotEmpty()) {
            synchronized(lock) {
                val entry = CacheEntry(fresh, now() + if (fresh.isEmpty()) FAILURE_TTL_MS else CHANNEL_TTL_MS)
                keys.forEach { key -> channelCache[key] = entry }
            }
        }
        return fresh
    }

    private suspend fun getChannelId(channelLogin: String): String? {
        val login = TwitchLiveChat.normalizeChannelLogin(channelLogin) ?: return null
        synchronized(lock) {
            channelIdCache[login]?.takeIf { it.expiresAtMs > now() }?.value?.let { return it }
        }

        val token = TwitchAccountAuth.getValidAccessToken()
            ?.trim()
            ?.removePrefix("oauth:")
            .orEmpty()
            .takeIf { it.isNotBlank() }
            ?: return null
        val clientId = TwitchCredentials.CLIENT_ID.trim().takeIf { it.isNotBlank() } ?: return null

        val request = Request.Builder()
            .url(HELIX_USERS + encode(login))
            .header("Client-Id", clientId)
            .header("Authorization", "Bearer $token")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()

        val id = runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                JSONObject(response.body?.string().orEmpty())
                    .optJSONArray("data")
                    ?.optJSONObject(0)
                    ?.optString("id")
                    ?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()

        if (!id.isNullOrBlank()) {
            synchronized(lock) {
                channelIdCache[login] = CacheEntry(id, now() + CHANNEL_ID_TTL_MS)
            }
        }
        return id
    }

    private fun fetchBttvGlobalSafe(): Map<String, EmoteDefinition> =
        runCatching { fetchBttvGlobal() }.getOrElse { emptyMap() }

    private fun fetchBttvChannelSafe(channelId: String): Map<String, EmoteDefinition> =
        runCatching { fetchBttvChannel(channelId) }.getOrElse { emptyMap() }

    private fun fetchFfzGlobalSafe(): Map<String, EmoteDefinition> =
        runCatching { fetchFfzGlobal() }.getOrElse { emptyMap() }

    private fun fetchFfzChannelSafe(channelLogin: String?, channelId: String?): Map<String, EmoteDefinition> {
        val urls = listOfNotNull(
            channelId?.let { FFZ_ROOM_BY_ID + encode(it) },
            channelLogin?.let { FFZ_ROOM_BY_LOGIN + encode(it.lowercase(Locale.US)) },
        )
        urls.forEach { url ->
            val parsed = runCatching { parseFfzSets(getJsonObject(url) ?: return@runCatching emptyMap()) }
                .getOrElse { emptyMap() }
            if (parsed.isNotEmpty()) return parsed
        }
        return emptyMap()
    }

    private fun fetchSevenTvGlobalSafe(): Map<String, EmoteDefinition> =
        runCatching { fetchSevenTvGlobal() }.getOrElse { emptyMap() }

    private fun fetchSevenTvChannelSafe(channelId: String): Map<String, EmoteDefinition> =
        runCatching { fetchSevenTvChannel(channelId) }.getOrElse { emptyMap() }

    private fun fetchBttvGlobal(): Map<String, EmoteDefinition> = parseBttvArray(getJsonArray(BTTV_GLOBAL))

    private fun fetchBttvChannel(channelId: String): Map<String, EmoteDefinition> {
        val root = getJsonObject(BTTV_CHANNEL + encode(channelId)) ?: return emptyMap()
        return mergeEmotes(
            parseBttvArray(root.optJSONArray("channelEmotes")),
            parseBttvArray(root.optJSONArray("sharedEmotes")),
        )
    }

    private fun parseBttvArray(array: JSONArray?): Map<String, EmoteDefinition> {
        if (array == null) return emptyMap()
        val result = linkedMapOf<String, EmoteDefinition>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val code = item.optString("code").takeIf { it.isNotBlank() } ?: continue
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
            result[code] = EmoteDefinition(
                code = code,
                imageUrl = "https://cdn.betterttv.net/emote/$id/2x",
                provider = "bttv",
            )
        }
        return result
    }

    private fun fetchFfzGlobal(): Map<String, EmoteDefinition> = parseFfzSets(getJsonObject(FFZ_GLOBAL), defaultOnly = true)

    private fun parseFfzSets(root: JSONObject?, defaultOnly: Boolean = false): Map<String, EmoteDefinition> {
        if (root == null) return emptyMap()
        val sets = root.optJSONObject("sets") ?: return emptyMap()
        val allowedSetIds = if (defaultOnly) ffzDefaultSetIds(root) else null
        val result = linkedMapOf<String, EmoteDefinition>()
        val keys = sets.keys()
        while (keys.hasNext()) {
            val setId = keys.next()
            if (allowedSetIds != null && !allowedSetIds.contains(setId)) continue
            val set = sets.optJSONObject(setId) ?: continue
            val emotes = set.optJSONArray("emoticons") ?: continue
            for (index in 0 until emotes.length()) {
                val item = emotes.optJSONObject(index) ?: continue
                val code = item.optString("name").takeIf { it.isNotBlank() } ?: continue
                val url = chooseFfzUrl(item.optJSONObject("urls")) ?: continue
                result[code] = EmoteDefinition(code = code, imageUrl = url, provider = "ffz")
            }
        }
        return result
    }

    private fun ffzDefaultSetIds(root: JSONObject): Set<String> {
        val array = root.optJSONArray("default_sets") ?: return emptySet()
        return (0 until array.length())
            .mapNotNull { index -> array.optString(index).takeIf { it.isNotBlank() } }
            .toSet()
    }

    private fun chooseFfzUrl(urls: JSONObject?): String? {
        if (urls == null) return null
        listOf("4", "2", "1").forEach { key ->
            urls.optString(key).takeIf { it.isNotBlank() }?.let { return normalizeImageUrl(it) }
        }
        return null
    }

    private fun fetchSevenTvGlobal(): Map<String, EmoteDefinition> = parseSevenTvSet(getJsonObject(SEVENTV_GLOBAL))

    private fun fetchSevenTvChannel(channelId: String): Map<String, EmoteDefinition> {
        val root = getJsonObject(SEVENTV_CHANNEL + encode(channelId)) ?: return emptyMap()
        return parseSevenTvSet(root.optJSONObject("emote_set"))
    }

    private fun parseSevenTvSet(root: JSONObject?): Map<String, EmoteDefinition> {
        if (root == null) return emptyMap()
        val emotes = root.optJSONArray("emotes") ?: return emptyMap()
        val result = linkedMapOf<String, EmoteDefinition>()
        for (index in 0 until emotes.length()) {
            val item = emotes.optJSONObject(index) ?: continue
            val code = item.optString("name").takeIf { it.isNotBlank() } ?: continue
            val url = chooseSevenTvUrl(item) ?: continue
            result[code] = EmoteDefinition(code = code, imageUrl = url, provider = "7tv")
        }
        return result
    }

    private fun chooseSevenTvUrl(emote: JSONObject): String? {
        val data = emote.optJSONObject("data")
        val host = data?.optJSONObject("host") ?: emote.optJSONObject("host") ?: return null
        val hostUrl = host.optString("url").takeIf { it.isNotBlank() } ?: return null
        val files = host.optJSONArray("files") ?: return null
        val fileNames = mutableListOf<String>()
        for (index in 0 until files.length()) {
            files.optJSONObject(index)?.optString("name")?.takeIf { it.isNotBlank() }?.let(fileNames::add)
        }
        val selected = listOf("2x.webp", "3x.webp", "1x.webp", "4x.webp")
            .firstOrNull { fileNames.contains(it) }
            ?: fileNames.firstOrNull()
            ?: return null
        return normalizeImageUrl(hostUrl.trimEnd('/') + "/" + selected)
    }

    private fun getJsonObject(url: String): JSONObject? = requestBody(url)?.let { body ->
        runCatching { JSONObject(body) }.getOrNull()
    }

    private fun getJsonArray(url: String): JSONArray? = requestBody(url)?.let { body ->
        runCatching { JSONArray(body) }.getOrNull()
    }

    private fun requestBody(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.string()
            }
        }.getOrNull()
    }

    private fun channelCacheKeys(channelLogin: String?, channelId: String?): List<String> {
        val login = TwitchLiveChat.normalizeChannelLogin(channelLogin)
        val id = channelId?.filter { it.isDigit() }?.takeIf { it.isNotBlank() }
        return listOfNotNull(
            id?.let { "id:$it" },
            login?.let { "login:${it.lowercase(Locale.US)}" },
        ).distinct()
    }

    private fun mergeEmotes(vararg maps: Map<String, EmoteDefinition>): Map<String, EmoteDefinition> {
        val result = linkedMapOf<String, EmoteDefinition>()
        maps.forEach { map -> map.forEach { (code, emote) -> result[code] = emote } }
        return result
    }

    private fun normalizeImageUrl(url: String): String = when {
        url.startsWith("https://") || url.startsWith("http://") -> url
        url.startsWith("//") -> "https:$url"
        else -> "https://$url"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun now(): Long = System.currentTimeMillis()
}