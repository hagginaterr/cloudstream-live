package recloudstream.twitchlivefavorites

import android.graphics.Color
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Fetches Twitch VOD chat replay near the current player timestamp.
 *
 * Live chat stays on Twitch IRC through TwitchLiveChat. This object is only for
 * VOD/past-broadcast playback, where Twitch's own web player reads timestamped
 * replay comments from the VideoCommentsByOffsetOrCursor GraphQL operation.
 */
object TwitchVodChat {
    private const val GQL_URL = "https://gql.twitch.tv/gql"
    private const val TWITCH_WEB_CLIENT_ID = "kd1unb4b3q4t58fwlpcbzcbnm76a8fp"
    private const val TWITCH_FALLBACK_CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko"
    private const val TWITCH_WEB_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0 Safari/537.36"
    private const val COMMENTS_HASH =
        "b70a3591ff0f4e0313d126c6a1502d79a1c02baebb288227c582044aa76adf6a"
    private val jsonMediaType = "application/json".toMediaTypeOrNull()

    private val client: OkHttpClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OkHttpClient.Builder()
            .callTimeout(15, TimeUnit.SECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun extractVodIdFromLink(link: ExtractorLink): String? {
        val candidates = mutableListOf(
            link.url,
            link.name,
            link.source,
            link.referer,
            link.extractorData.orEmpty(),
        )
        link.headers.orEmpty().forEach { entry ->
            candidates.add(entry.key)
            candidates.add(entry.value)
        }
        candidates.forEach { candidate ->
            extractVodIdFromText(candidate)?.let { return it }
        }
        return null
    }

    fun extractVodIdFromText(value: String?): String? {
        val raw = value?.takeIf { it.isNotBlank() } ?: return null
        val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        val patterns = listOf(
            Regex("(?i)(?:^|[?&\\n])cs_twitch_vod_id=(\\d+)"),
            Regex("(?i)twitch\\.tv/videos/(\\d+)(?:[/?#&]|$)"),
            Regex("(?i)/videos/(\\d+)(?:[/?#&]|$)"),
            Regex("(?i)/vod/(\\d+)(?:\\.m3u8|[/?#&]|$)"),
            Regex("(?i)vod/(\\d+)(?:\\.m3u8|[/?#&]|$)"),
        )
        patterns.forEach { regex ->
            regex.find(decoded)?.groupValues?.getOrNull(1)
                ?.filter { it.isDigit() }
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        return null
    }

    suspend fun fetchAt(
        vodId: String,
        positionMs: Long,
        maxMessages: Int,
        channelLogin: String? = null,
    ): List<TwitchLiveChat.LiveMessage> {
        val cleanId = vodId.filter { it.isDigit() }
        if (cleanId.isBlank()) return emptyList()
        val targetPositionMs = positionMs.coerceAtLeast(0L)
        val cappedMax = maxMessages.coerceIn(1, 60)

        return withContext(Dispatchers.IO) {
            try { TwitchChatEmotes.loadForChannel(channelLogin, null) } catch (_: Throwable) {}

            val queryPositionsMs = listOf(
                (targetPositionMs - 12_000L).coerceAtLeast(0L),
                (targetPositionMs - 45_000L).coerceAtLeast(0L),
                (targetPositionMs - 180_000L).coerceAtLeast(0L),
            ).distinct()
            val collected = LinkedHashMap<String, TwitchLiveChat.LiveMessage>()

            for (queryPositionMs in queryPositionsMs) {
                val page = fetchPage(
                    videoId = cleanId,
                    offsetSeconds = (queryPositionMs / 1000.0).roundToLong(),
                    playerPositionMs = targetPositionMs,
                    channelLogin = channelLogin,
                )
                page.forEach { message -> collected[message.id] = message }
                if (collected.size >= cappedMax) break
            }

            collected.values
                .asSequence()
                .filter { message ->
                    val upperBound = if (targetPositionMs <= 5_000L) 8_000L else targetPositionMs + 2_000L
                    message.timestampMs <= upperBound
                }
                .sortedBy { it.timestampMs }
                .toList()
                .takeLast(cappedMax)
        }
    }

    private fun fetchPage(
        videoId: String,
        offsetSeconds: Long,
        playerPositionMs: Long,
        channelLogin: String?,
    ): List<TwitchLiveChat.LiveMessage> {
        val clientIds = listOf(TWITCH_WEB_CLIENT_ID, TWITCH_FALLBACK_CLIENT_ID).distinct()
        for (clientId in clientIds) {
            val result = runCatching {
                val request = Request.Builder()
                    .url(GQL_URL)
                    .header("Client-ID", clientId)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("Origin", "https://www.twitch.tv")
                    .header("User-Agent", TWITCH_WEB_USER_AGENT)
                    .header("Referer", "https://www.twitch.tv/videos/$videoId")
                    .post(buildRequestJson(videoId, offsetSeconds).toRequestBody(jsonMediaType))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.string().orEmpty()
                    if (body.isBlank()) return@use null
                    parseResponse(body, videoId, playerPositionMs, channelLogin)
                }
            }.getOrNull()
            if (result != null) return result
        }
        return emptyList()
    }

    private fun buildRequestJson(videoId: String, offsetSeconds: Long): String {
        val variables = JSONObject()
            .put("videoID", videoId)
            .put("contentOffsetSeconds", offsetSeconds.coerceAtLeast(0L))
        val persistedQuery = JSONObject()
            .put("version", 1)
            .put("sha256Hash", COMMENTS_HASH)
        val extensions = JSONObject().put("persistedQuery", persistedQuery)
        val query = JSONObject()
            .put("operationName", "VideoCommentsByOffsetOrCursor")
            .put("variables", variables)
            .put("extensions", extensions)
        return JSONArray().put(query).toString()
    }

    private fun parseResponse(
        body: String,
        videoId: String,
        playerPositionMs: Long,
        channelLogin: String?,
    ): List<TwitchLiveChat.LiveMessage>? {
        val root = runCatching { JSONArray(body).optJSONObject(0) }.getOrNull()
            ?: runCatching { JSONObject(body) }.getOrNull()
            ?: return null
        val edges = root.optJSONObject("data")
            ?.optJSONObject("video")
            ?.optJSONObject("comments")
            ?.optJSONArray("edges")
            ?: return null

        val lowerBound = (playerPositionMs - 10L * 60L * 1000L).coerceAtLeast(0L)
        val upperBound = if (playerPositionMs <= 5_000L) 8_000L else playerPositionMs + 2_000L
        return (0 until edges.length())
            .mapNotNull { index -> edges.optJSONObject(index)?.optJSONObject("node") }
            .mapNotNull { node -> parseNode(videoId, node, channelLogin) }
            .filter { message -> message.timestampMs in lowerBound..upperBound }
            .sortedBy { it.timestampMs }
    }

    private fun parseNode(videoId: String, node: JSONObject, channelLogin: String?): TwitchLiveChat.LiveMessage? {
        val offsetSeconds = node.optDouble("contentOffsetSeconds", -1.0)
        if (offsetSeconds < 0.0) return null
        val offsetMs = (offsetSeconds * 1000.0).roundToLong().coerceAtLeast(0L)
        val message = node.optJSONObject("message") ?: return null
        val commenter = node.optJSONObject("commenter")
        val replayText = parseReplayMessageText(message)
        val text = replayText.text
        if (text.isBlank()) return null

        val login = commenter?.optString("login")?.ifBlank { null }
        val displayName = commenter?.optString("displayName")
            ?.ifBlank { null }
            ?: login
            ?: "Twitch"

        return TwitchLiveChat.LiveMessage(
            id = node.optString("id").ifBlank { "$videoId-$offsetMs-${abs(text.hashCode())}" },
            displayName = cleanVisibleText(displayName).ifBlank { "Twitch" },
            color = parseTwitchColor(message.optString("userColor", ""))
                ?: fallbackUserColor(login ?: displayName),
            timestampMs = offsetMs,
            timestampLabel = formatOffset(offsetMs),
            text = text,
            badges = parseBadges(message.optJSONArray("userBadges")),
            emotes = TwitchChatEmotes.resolveFromCacheWithNativeFragments(
                text = text,
                nativeEmotes = replayText.nativeEmotes,
                channelLogin = channelLogin,
                channelId = null,
            ),
        )
    }

    private data class ReplayMessageText(
        val text: String,
        val nativeEmotes: List<TwitchChatEmote>,
    )

    /**
     * Twitch VOD comments contain ordered message fragments. Native Twitch
     * emotes are identified by fragment.emote.emoteID (legacy GQL shape) or
     * fragment.emote.id (newer fragment shape). Build spans while the original
     * UTF-16 offsets are still intact, then trim and rebase them together.
     */
    private fun parseReplayMessageText(message: JSONObject): ReplayMessageText {
        val fragments = message.optJSONArray("fragments")
        if (fragments == null || fragments.length() == 0) {
            return ReplayMessageText(
                text = cleanReplayText(message.optString("body", "")),
                nativeEmotes = emptyList(),
            )
        }

        val builder = StringBuilder()
        val nativeEmotes = mutableListOf<TwitchChatEmote>()
        for (index in 0 until fragments.length()) {
            val fragment = fragments.optJSONObject(index) ?: continue
            val fragmentText = cleanReplayFragmentPreservingOffsets(
                fragment.optString("text", ""),
            )
            if (fragmentText.isEmpty()) continue

            val fragmentStart = builder.length
            builder.append(fragmentText)
            val fragmentEnd = builder.length
            val emoteId = replayFragmentEmoteId(fragment) ?: continue

            nativeEmotes += TwitchChatEmote(
                code = fragmentText,
                imageUrl = twitchEmoteImageUrl(emoteId),
                start = fragmentStart,
                endExclusive = fragmentEnd,
                provider = "twitch",
            )
        }

        val fragmentText = builder.toString()
        if (fragmentText.isBlank()) {
            return ReplayMessageText(
                text = cleanReplayText(message.optString("body", "")),
                nativeEmotes = emptyList(),
            )
        }
        return trimReplayMessageText(fragmentText, nativeEmotes)
    }

    private fun replayFragmentEmoteId(fragment: JSONObject): String? {
        val emote = fragment.optJSONObject("emote") ?: return null
        return sequenceOf(
            emote.optString("emoteID", ""),
            emote.optString("id", ""),
            emote.optString("emoteId", ""),
        )
            .map { it.trim() }
            .firstOrNull { value ->
                value.isNotBlank() && value.all { character ->
                    character.isLetterOrDigit() || character == '_' || character == '-'
                }
            }
    }

    private fun twitchEmoteImageUrl(emoteId: String): String {
        // Animated is attempted first; TwitchChatMessageRows falls back to the
        // default rendition when a native emote has no animated asset.
        return "https://static-cdn.jtvnw.net/emoticons/v2/$emoteId/animated/dark/2.0"
    }

    private fun trimReplayMessageText(
        text: String,
        nativeEmotes: List<TwitchChatEmote>,
    ): ReplayMessageText {
        var trimStart = 0
        while (trimStart < text.length && text[trimStart].isWhitespace()) trimStart++

        var trimEnd = text.length
        while (trimEnd > trimStart && text[trimEnd - 1].isWhitespace()) trimEnd--

        if (trimStart >= trimEnd) return ReplayMessageText("", emptyList())
        val trimmedText = text.substring(trimStart, trimEnd)
        val adjustedEmotes = nativeEmotes.mapNotNull { emote ->
            if (
                emote.start < trimStart ||
                emote.endExclusive > trimEnd ||
                emote.start >= emote.endExclusive
            ) {
                null
            } else {
                val adjustedStart = emote.start - trimStart
                val adjustedEnd = emote.endExclusive - trimStart
                emote.copy(
                    code = trimmedText.substring(adjustedStart, adjustedEnd),
                    start = adjustedStart,
                    endExclusive = adjustedEnd,
                )
            }
        }
        return ReplayMessageText(trimmedText, adjustedEmotes)
    }

    private fun cleanReplayFragmentPreservingOffsets(value: String): String {
        if (value.isEmpty()) return value
        return buildString(value.length) {
            value.forEach { character ->
                append(
                    if (
                        character == '\r' ||
                        character == '\n' ||
                        character == '\t' ||
                        character.isISOControl()
                    ) {
                        ' '
                    } else {
                        character
                    },
                )
            }
        }
    }

    private fun cleanReplayText(value: String): String {
        return cleanReplayFragmentPreservingOffsets(value).trim()
    }
    private fun parseBadges(badges: JSONArray?): List<String> {
        if (badges == null) return emptyList()
        return (0 until badges.length())
            .mapNotNull { index -> badges.optJSONObject(index) }
            .mapNotNull { badge ->
                badge.optString("setID")
                    .ifBlank { badge.optString("id") }
                    .ifBlank { null }
                    ?.uppercase(Locale.US)
            }
            .distinct()
            .take(3)
    }

    private fun parseTwitchColor(value: String?): Int? {
        val clean = value?.trim()?.takeIf { it.startsWith('#') && it.length == 7 } ?: return null
        return runCatching { Color.parseColor(clean) }.getOrNull()?.let(::ensureReadableColor)
    }

    private fun fallbackUserColor(login: String): Int {
        val palette = intArrayOf(
            Color.rgb(255, 123, 114),
            Color.rgb(121, 192, 255),
            Color.rgb(126, 231, 135),
            Color.rgb(210, 168, 255),
            Color.rgb(255, 203, 107),
            Color.rgb(255, 171, 145),
            Color.rgb(164, 214, 255),
        )
        val index = abs(login.lowercase(Locale.US).hashCode()) % palette.size
        return palette[index]
    }

    private fun ensureReadableColor(color: Int): Int {
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        val brightness = (red * 0.299f) + (green * 0.587f) + (blue * 0.114f)
        return if (brightness < 95f) {
            Color.rgb(
                (red + 180).coerceAtMost(255),
                (green + 180).coerceAtMost(255),
                (blue + 180).coerceAtMost(255),
            )
        } else {
            color
        }
    }

    private fun cleanVisibleText(value: String): String {
        return value
            .filter { !it.isISOControl() }
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun formatOffset(offsetMs: Long): String {
        val totalSeconds = (offsetMs / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    @Suppress("unused")
    private fun formatWallClock(timestampMs: Long): String {
        return runCatching {
            SimpleDateFormat("h:mm", Locale.getDefault()).format(Date(timestampMs))
        }.getOrDefault("")
    }
}