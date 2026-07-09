package recloudstream.twitchlivefavorites

import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

object TwitchHistoricalChat {
    private const val ROBOTTY_BASE_URL = "https://recent-messages.robotty.de"
    private const val USER_AGENT = "CloudStream-Live-Android-TV/1.0 (+https://github.com/hagginaterr/cloudstream-live)"
    private val validLoginRegex = Regex("^[a-zA-Z0-9_]{1,25}$")

    private val robottyClient: OkHttpClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OkHttpClient.Builder()
            .callTimeout(3, TimeUnit.SECONDS)
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    data class Message(
        val id: String,
        val login: String,
        val displayName: String,
        val color: Int,
        val timestampMs: Long,
        val timestampLabel: String,
        val text: String,
        val badges: List<String>,
    )

    suspend fun fetchRecent(channelLogin: String, maxMessages: Int): List<Message> {
        val channel = normalizeLogin(channelLogin) ?: return emptyList()
        val cappedMax = maxMessages.coerceIn(1, 12)
        val requestLimit = (cappedMax * 3).coerceIn(cappedMax, 36)
        val encodedChannel = URLEncoder.encode(channel, "UTF-8")
        val url = "$ROBOTTY_BASE_URL/api/v2/recent-messages/$encodedChannel" +
            "?limit=$requestLimit&hideModerationMessages=true&hideModeratedMessages=true"

        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .get()
                    .build()

                robottyClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use emptyList<Message>()
                    val body = response.body?.string().orEmpty()
                    parseRobottyResponse(body, cappedMax)
                }
            }.getOrElse { emptyList() }
        }
    }

    private fun parseRobottyResponse(body: String, cappedMax: Int): List<Message> {
        val messages = runCatching { JSONObject(body).optJSONArray("messages") }
            .getOrNull()
            ?: return emptyList()

        return (0 until messages.length())
            .asSequence()
            .map { messages.optString(it, "") }
            .filter { it.isNotBlank() }
            .mapNotNull(::parseRawIrcMessage)
            .filter { it.text.isNotBlank() }
            .sortedBy { it.timestampMs }
            .toList()
            .takeLast(cappedMax)
    }

    private fun normalizeLogin(value: String): String? {
        val login = value
            .trim()
            .trimStart('@')
            .substringBefore('?')
            .substringBefore('/')
            .lowercase(Locale.US)
        return login.takeIf { validLoginRegex.matches(it) }
    }

    private fun parseRawIrcMessage(raw: String): Message? {
        var remaining = raw.trim()
        if (remaining.isBlank()) return null

        val tags = mutableMapOf<String, String>()
        if (remaining.startsWith("@")) {
            val tagEnd = remaining.indexOf(' ')
            if (tagEnd <= 1) return null
            remaining.substring(1, tagEnd)
                .split(';')
                .forEach { entry ->
                    val key = entry.substringBefore('=').trim()
                    if (key.isBlank()) return@forEach
                    val rawValue = entry.substringAfter('=', "")
                    tags[key] = unescapeIrcTag(rawValue)
                }
            remaining = remaining.substring(tagEnd + 1).trimStart()
        }

        var prefix = ""
        if (remaining.startsWith(":")) {
            val prefixEnd = remaining.indexOf(' ')
            if (prefixEnd <= 1) return null
            prefix = remaining.substring(1, prefixEnd)
            remaining = remaining.substring(prefixEnd + 1).trimStart()
        }

        val command = remaining.substringBefore(' ').uppercase(Locale.US)
        if (command != "PRIVMSG") return null

        val trailingIndex = remaining.indexOf(" :")
        if (trailingIndex < 0) return null

        val messageText = cleanVisibleText(remaining.substring(trailingIndex + 2)) .trim()
        if (messageText.isBlank()) return null

        val login = cleanVisibleText(
            prefix.substringBefore('!')
                .ifBlank { tags["login"].orEmpty() }
                .ifBlank { tags["display-name"].orEmpty() },
        ).lowercase(Locale.US).takeIf { it.isNotBlank() } ?: return null

        val displayName = cleanVisibleText(tags["display-name"].orEmpty())
            .ifBlank { login }

        val timestampMs = tags["tmi-sent-ts"]?.toLongOrNull()
            ?: tags["rm-received-ts"]?.toLongOrNull()
            ?: System.currentTimeMillis()

        return Message(
            id = tags["id"].orEmpty().ifBlank { "$login-$timestampMs-${abs(raw.hashCode())}" },
            login = login,
            displayName = displayName,
            color = parseUserColor(tags["color"], login),
            timestampMs = timestampMs,
            timestampLabel = formatTimestamp(timestampMs),
            text = messageText,
            badges = parseBadges(tags["badges"]),
        )
    }

    private fun unescapeIrcTag(value: String): String {
        return value
            .replace("\\s", " ")
            .replace("\\:", ";")
            .replace("\\r", "\r")
            .replace("\\n", "\n")
            .replace("\\\\", "\\")
    }

    private fun cleanVisibleText(value: String): String {
        return value
            .filter { !it.isISOControl() }
            .replace(Regex("\\s+"), " ")
            .trim()
    }    private fun parseBadges(rawBadges: String?): List<String> {
        return rawBadges
            .orEmpty()
            .split(',')
            .asSequence()
            .map { it.trim().lowercase(Locale.US) }
            .filter { it.isNotBlank() }
            .mapNotNull { badge ->
                val setId = badge.substringBefore('/').trim()
                val version = badge.substringAfter('/', "1").trim().ifBlank { "1" }
                setId.takeIf { it.isNotBlank() }?.let { "$it/$version" }
            }
            .distinct()
            .take(5)
            .toList()
    }
    private fun parseUserColor(rawColor: String?, login: String): Int {
        val parsed = rawColor
            ?.trim()
            ?.takeIf { it.startsWith("#") && (it.length == 7 || it.length == 9) }
            ?.let { value -> runCatching { Color.parseColor(value) }.getOrNull() }
        return ensureReadableColor(parsed ?: fallbackUserColor(login))
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
        val index = abs(login.hashCode()) % palette.size
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

    private fun formatTimestamp(timestampMs: Long): String {
        return runCatching {
            SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestampMs))
        }.getOrDefault("")
    }
}