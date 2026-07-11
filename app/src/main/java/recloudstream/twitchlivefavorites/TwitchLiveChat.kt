package recloudstream.twitchlivefavorites

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Read-only Twitch IRC WebSocket client.
 *
 * Phase 3 scope:
 * - authenticated chat:read only
 * - joins exactly one channel: the currently playing streamer
 * - no sending user chat messages
 */
class TwitchLiveChat(
    private val scope: CoroutineScope,
    private val maxMessages: Int,
    private val listener: Listener,
) {
    interface Listener {
        fun onState(message: String)
        fun onMessages(messages: List<LiveMessage>)
    }

    data class LiveMessage(
        val id: String,
        val displayName: String,
        val color: Int?,
        val timestampMs: Long,
        val timestampLabel: String,
        val text: String,
        val badges: List<String>,
        val emotes: List<TwitchChatEmote> = emptyList(),
    )

    companion object {
        private const val IRC_WS_URL = "wss://irc-ws.chat.twitch.tv:443"
        private const val USER_AGENT = "CloudStream Live Twitch Chat"
        private const val EMOTE_REFRESH_INTERVAL_MS = 15L * 60L * 1000L
        private val loginPattern = Regex("^[a-z0-9_]{3,25}$", RegexOption.IGNORE_CASE)
        private val blockedLoginWords = setOf(
            "twitch", "twitchtv", "twitchcom", "wwwtwitchtv", "wwwtwitchcom",
            "clips", "clip", "videos", "video", "source", "auto", "vod", "m3u8",
            "index", "playlist", "chunked", "live", "stream", "player",
        )

        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(8, TimeUnit.SECONDS)
                .pingInterval(25, TimeUnit.SECONDS)
                .build()
        }

        fun normalizeChannelLogin(value: String?): String? {
            val normalized = value
                ?.trim()
                ?.removePrefix("#")
                ?.lowercase(Locale.US)
                ?.takeIf { it.isNotBlank() }
                ?: return null
            if (!loginPattern.matches(normalized)) return null
            if (normalized in blockedLoginWords) return null
            if (normalized.all { it.isDigit() }) return null
            return normalized
        }

        fun extractChannelLoginFromLink(link: ExtractorLink): String? {
            val candidates = mutableListOf<String>()
            candidates.add(link.extractorData.orEmpty())
            candidates.add(link.referer.orEmpty())
            candidates.add(link.url)
            candidates.add(link.name)
            candidates.add(link.source)
            link.headers.orEmpty().forEach { entry ->
                candidates.add(entry.key)
                candidates.add(entry.value)
            }
            candidates.forEach { candidate ->
                extractChannelLoginFromText(candidate)?.let { return it }
            }
            return null
        }

        private fun extractChannelLoginFromText(value: String?): String? {
            val raw = value?.takeIf { it.isNotBlank() } ?: return null
            val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)

            listOf(
                Regex("(?i)(?:^|[?&\\n])cs_streamer_login=([^&#\\s]+)"),
                Regex("(?i)(?:^|[?&\\n])streamer_login=([^&#\\s]+)"),
                Regex("(?i)(?:^|[?&\\n])channel=([^&#\\s]+)"),
                Regex("(?i)twitch\\.tv/([a-z0-9_]{3,25})(?:[/?#&]|$)"),
                Regex("(?i)/hls/([a-z0-9_]{3,25})(?:\\.m3u8|[/?#&]|$)"),
            ).forEach { regex ->
                regex.find(decoded)?.groupValues?.getOrNull(1)?.let { match ->
                    normalizeChannelLogin(match)?.let { return it }
                }
            }

            return null
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val messages = mutableListOf<LiveMessage>()
    private var connectJob: Job? = null
    private var emoteRefreshJob: Job? = null
    @Volatile private var activeChannel: String? = null
    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var activeChannelId: String? = null

    fun start(channelLogin: String) {
        val channel = normalizeChannelLogin(channelLogin) ?: run {
            postState("Live chat unavailable for this channel")
            return
        }
        if (activeChannel == channel && webSocket != null) return

        stop()
        activeChannel = channel
        activeChannelId = null
        synchronized(messages) { messages.clear() }
        postState("Connecting live chat...")

        connectJob = scope.launch(Dispatchers.IO) {
            val token = TwitchAccountAuth.getValidAccessToken()
                ?.trim()
                ?.removePrefix("oauth:")
                .orEmpty()
            val userLogin = TwitchAccountAuth.userLogin()
                ?.trim()
                .orEmpty()

            if (token.isBlank() || userLogin.isBlank()) {
                postState("Sign in to Twitch to load live chat")
                return@launch
            }

            val nick = normalizeChannelLogin(userLogin)
            if (nick.isNullOrBlank()) {
                postState("Sign out and sign back in to load live chat")
                return@launch
            }
            startEmoteRefreshLoop(channel)


            if (activeChannel != channel) return@launch

            val request = Request.Builder()
                .url(IRC_WS_URL)
                .header("User-Agent", USER_AGENT)
                .build()

            val socket = client.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        if (activeChannel != channel) {
                            webSocket.close(1000, "channel changed")
                            return
                        }
                        webSocket.send("PASS oauth:$token")
                        webSocket.send("NICK $nick")
                        webSocket.send("CAP REQ :twitch.tv/tags twitch.tv/commands")
                        webSocket.send("JOIN #$channel")
                        postState("Live chat")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        text.split("\r\n", "\n").forEach { raw ->
                            handleRawLine(webSocket, raw.trim(), channel)
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (activeChannel == channel) {
                            postState("Live chat disconnected")
                        }
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (activeChannel == channel) {
                            postState("Live chat closed")
                        }
                    }
                },
            )
            webSocket = socket
        }
    }

    fun stop() {
        connectJob?.cancel()
        connectJob = null
        emoteRefreshJob?.cancel()
        emoteRefreshJob = null
        val socket = webSocket
        webSocket = null
        activeChannel = null
        activeChannelId = null
        runCatching { socket?.close(1000, "closed") }
        synchronized(messages) { messages.clear() }
    }

    // TwitchChatEmoteRefreshLoopV32: refresh provider definitions without reconnecting IRC.
    private fun startEmoteRefreshLoop(channel: String) {
        emoteRefreshJob?.cancel()
        emoteRefreshJob = scope.launch(Dispatchers.IO) {
            while (activeChannel == channel) {
                runCatching {
                    TwitchChatEmotes.refreshForChannel(channel, activeChannelId)
                }
                delay(EMOTE_REFRESH_INTERVAL_MS)
            }
        }
    }
    private fun handleRawLine(socket: WebSocket, raw: String, expectedChannel: String) {
        if (raw.isBlank()) return
        if (raw.startsWith("PING", ignoreCase = true)) {
            socket.send("PONG :tmi.twitch.tv")
            return
        }
        if (raw.contains("NOTICE", ignoreCase = true)) {
            val lower = raw.lowercase(Locale.US)
            when {
                "login authentication failed" in lower || "improperly formatted auth" in lower ->
                    postState("Sign out and sign back in once to grant chat:read")
                "msg_channel_suspended" in lower ->
                    postState("Live chat is unavailable for this channel")
            }
            return
        }
        val message = parsePrivmsg(raw, expectedChannel) ?: return
        appendMessage(message)
    }

    private fun appendMessage(message: LiveMessage) {
        val copy = synchronized(messages) {
            if (messages.any { it.id == message.id }) {
                return
            }
            messages.add(message)
            while (messages.size > maxMessages.coerceAtLeast(1)) {
                messages.removeAt(0)
            }
            messages.toList()
        }
        mainHandler.post { listener.onMessages(copy) }
    }

    private fun parsePrivmsg(raw: String, expectedChannel: String): LiveMessage? {
        if (!raw.contains(" PRIVMSG ", ignoreCase = true)) return null
        if (!raw.contains("#$expectedChannel", ignoreCase = true)) return null

        val tags = parseTags(raw)
        val body = raw.substringAfter(" PRIVMSG ", "")
            .substringAfter(" :", "")
            .takeIf { it.isNotBlank() }
            ?: return null
        val roomId = tags["room-id"]?.filter { it.isDigit() }?.takeIf { it.isNotBlank() }
        if (roomId != null && activeChannelId != roomId) {
            activeChannelId = roomId
            scope.launch(Dispatchers.IO) {
                try { TwitchChatEmotes.refreshForChannel(expectedChannel, roomId) } catch (_: Throwable) {}
            }
        }

        val displayName = unescapeIrcTag(tags["display-name"].orEmpty())
            .ifBlank { raw.substringAfter(':', "").substringBefore('!').trim() }
            .ifBlank { expectedChannel }

        val timestampMs = tags["tmi-sent-ts"]?.toLongOrNull() ?: System.currentTimeMillis()
        val badges = parseTwitchBadgeTags(tags["badges"])
        val text = unescapeIrcTag(body).trim()

        return LiveMessage(
            id = tags["id"]?.takeIf { it.isNotBlank() }
                ?: "${timestampMs}:${displayName}:${body.hashCode()}",
            displayName = displayName,
            color = parseTwitchColor(tags["color"]),
            timestampMs = timestampMs,
            timestampLabel = formatTimestamp(timestampMs),
            text = text,
            badges = badges,
            emotes = TwitchChatEmotes.resolveFromCache(
                text = text,
                nativeEmotesTag = tags["emotes"],
                channelLogin = expectedChannel,
                channelId = roomId ?: activeChannelId,
            ),
        )
    }

    private fun parseTags(raw: String): Map<String, String> {
        if (!raw.startsWith('@')) return emptyMap()
        val tagBlock = raw.substringAfter('@').substringBefore(' ')
        if (tagBlock.isBlank()) return emptyMap()
        return tagBlock.split(';')
            .mapNotNull { pair ->
                val key = pair.substringBefore('=', "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val value = pair.substringAfter('=', "")
                key to value
            }
            .toMap()
    }

    private fun unescapeIrcTag(value: String): String {
        return value
            .replace("\\s", " ")
            .replace("\\:", ";")
            .replace("\\r", "\r")
            .replace("\\n", "\n")
            .replace("\\\\", "\\")
    }    private fun parseTwitchBadgeTags(rawBadges: String?): List<String> {
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


    private fun parseTwitchColor(value: String?): Int? {
        val clean = value?.trim()?.takeIf { it.startsWith('#') && it.length == 7 } ?: return null
        return runCatching { Color.parseColor(clean) }.getOrNull()
    }

    private fun formatTimestamp(timestampMs: Long): String {
        return runCatching {
            SimpleDateFormat("h:mm", Locale.getDefault()).format(Date(timestampMs))
        }.getOrDefault("")
    }

    private fun postState(message: String) {
        mainHandler.post { listener.onState(message) }
    }
}