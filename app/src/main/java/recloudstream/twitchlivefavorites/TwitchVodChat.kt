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
    private const val TWITCH_WEB_CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko"
    private const val TWITCH_WEB_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0 Safari/537.36"
    private const val COMMENTS_HASH =
        "b70a3591ff0f4e0313d126c6a1502d79a1c02baebb288227c582044aa76adf6a"
    private val jsonMediaType = "application/json".toMediaTypeOrNull()

    private val client: OkHttpClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OkHttpClient.Builder()
            .callTimeout(4, TimeUnit.SECONDS)
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
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
    ): List<TwitchLiveChat.LiveMessage> {
        val cleanId = vodId.filter { it.isDigit() }
        if (cleanId.isBlank()) return emptyList()
        val cappedMax = maxMessages.coerceIn(1, 12)
        val offsetSeconds = (positionMs.coerceAtLeast(0L) / 1000.0).roundToLong()
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(GQL_URL)
                    .header("Client-ID", TWITCH_WEB_CLIENT_ID)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", TWITCH_WEB_USER_AGENT)
                    .header("Referer", "https://www.twitch.tv/")
                    .post(buildRequestJson(cleanId, offsetSeconds).toRequestBody(jsonMediaType))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use emptyList()
                    val body = response.body?.string().orEmpty()
                    parseResponse(body, cleanId, positionMs, cappedMax)
                }
            }.getOrElse { emptyList() }
        }
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
        cappedMax: Int,
    ): List<TwitchLiveChat.LiveMessage> {
        val root = runCatching { JSONArray(body).optJSONObject(0) }.getOrNull()
            ?: runCatching { JSONObject(body) }.getOrNull()
            ?: return emptyList()
        val edges = root.optJSONObject("data")
            ?.optJSONObject("video")
            ?.optJSONObject("comments")
            ?.optJSONArray("edges")
            ?: return emptyList()

        val lowerBound = playerPositionMs - 45_000L
        val upperBound = playerPositionMs + 45_000L
        val parsed = (0 until edges.length())
            .mapNotNull { index -> edges.optJSONObject(index)?.optJSONObject("node") }
            .mapNotNull { node -> parseNode(videoId, node) }
            .filter { message ->
                val offsetMs = message.timestampMs
                offsetMs in lowerBound..upperBound || playerPositionMs <= 5_000L
            }
            .sortedBy { it.timestampMs }

        return parsed.take(cappedMax)
    }

    private fun parseNode(videoId: String, node: JSONObject): TwitchLiveChat.LiveMessage? {
        val offsetSeconds = node.optDouble("contentOffsetSeconds", -1.0)
        if (offsetSeconds < 0.0) return null
        val offsetMs = (offsetSeconds * 1000.0).roundToLong().coerceAtLeast(0L)
        val message = node.optJSONObject("message") ?: return null
        val commenter = node.optJSONObject("commenter")
        val fragments = message.optJSONArray("fragments")
        val text = buildString {
            if (fragments != null) {
                for (index in 0 until fragments.length()) {
                    append(fragments.optJSONObject(index)?.optString("text").orEmpty())
                }
            }
        }.ifBlank { message.optString("body", "") }
            .let(::cleanVisibleText) .trim()
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
        )
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