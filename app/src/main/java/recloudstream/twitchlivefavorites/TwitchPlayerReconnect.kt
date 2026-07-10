package recloudstream.twitchlivefavorites

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.net.URLDecoder
import java.util.Locale
import kotlin.math.abs

/**
 * Small reconnect helper for Twitch live playback.
 *
 * ExoPlayer can hit stale/expired HLS playlists while the streamer is still live.
 * Player code calls this only for links explicitly marked by the Twitch provider as
 * live-channel links, never for clips or VODs.
 */
object TwitchPlayerReconnect {
    private val loginRegex = Regex("^[a-zA-Z0-9_]{1,25}$")

    fun isAutoReconnectable(link: ExtractorLink?): Boolean {
        if (link == null || link.type != ExtractorLinkType.M3U8) return false
        val data = link.extractorData.orEmpty()
        // TwitchLiveDvrReconnectPatch:
        // Live-DVR links remain live HLS even though they also carry a VOD ID for chat.
        val markedLive = data.contains("cs_twitch_reconnect=1", ignoreCase = true) ||
            data.contains("cs_twitch_live_dvr=1", ignoreCase = true)
        return markedLive && channelLogin(link) != null
    }

    fun channelLogin(link: ExtractorLink?): String? {
        val data = link?.extractorData.orEmpty()
        val fromMeta = paramFromCarrier(data, "cs_streamer_login")
        val clean = normalizeLogin(fromMeta.orEmpty())
        if (clean != null) return clean
        return normalizeLogin(link?.referer.orEmpty())
    }

    suspend fun fetchFreshLink(previousLink: ExtractorLink): ExtractorLink? {
        val channel = channelLogin(previousLink) ?: return null
        val provider = TwitchApiLiveFavoritesProvider()
        if (!provider.isChannelLiveForPlayerReconnect(channel)) return null

        val freshLinks = mutableListOf<ExtractorLink>()
        val handled = runCatching {
            provider.loadLinks(
                data = "https://twitch.tv/$channel",
                isCasting = false,
                subtitleCallback = { _: SubtitleFile -> },
                callback = { link -> freshLinks.add(link) },
            )
        }.getOrDefault(false)

        if (!handled && freshLinks.isEmpty()) return null
        return pickClosestQuality(
            freshLinks.filter { it.type == ExtractorLinkType.M3U8 },
            previousLink.quality,
        )
    }

    private fun pickClosestQuality(links: List<ExtractorLink>, preferredQuality: Int): ExtractorLink? {
        if (links.isEmpty()) return null
        if (preferredQuality <= 0) return links.maxByOrNull { it.quality }
        return links.minByOrNull { link -> abs(link.quality - preferredQuality) }
            ?: links.maxByOrNull { it.quality }
    }

    private fun paramFromCarrier(data: String, key: String): String? {
        return data.lineSequence()
            .flatMap { line ->
                line.substringAfter("?", "")
                    .substringBefore("#")
                    .split("&")
                    .asSequence()
            }
            .firstOrNull { entry -> entry.substringBefore("=") == key }
            ?.substringAfter("=", "")
            ?.let(::decode)
            ?.ifBlank { null }
    }

    private fun decode(value: String): String {
        return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }

    private fun normalizeLogin(value: String): String? {
        val clean = value
            .trim()
            .trimStart('@')
            .substringBefore('?')
            .substringBefore('#')
            .trim('/')
            .substringAfterLast('/')
            .lowercase(Locale.US)
            .filter { it.isLetterOrDigit() || it == '_' }
        return clean.takeIf { loginRegex.matches(it) }
    }
}