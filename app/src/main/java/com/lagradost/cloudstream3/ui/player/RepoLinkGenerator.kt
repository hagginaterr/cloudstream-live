package com.lagradost.cloudstream3.ui.player

import android.util.Log
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.APIHolder.unixTime
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.AppContextUtils.html
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

data class Cache(
    val linkCache: MutableSet<ExtractorLink>,
    val subtitleCache: MutableSet<SubtitleData>,
    /** When it was last updated */
    var lastCachedTimestamp: Long = unixTime,
    /** If it has fully loaded */
    var saturated: Boolean,
)

class RepoLinkGenerator(
    episodes: List<ResultEpisode>,
    val page: LoadResponse? = null,
) : VideoGenerator<ResultEpisode>(episodes) {
    companion object {
        const val TAG = "RepoLink"
        val cache: HashMap<Pair<String, Int>, Cache> =
            hashMapOf()
    }

    override val hasCache = true
    override val canSkipLoading = true
    override fun getId(index: Int): Int? = videos.getOrNull(index)?.id

    // this is a simple array that is used to instantly load links if they are already loaded
    //var linkCache = Array<Set<ExtractorLink>>(size = episodes.size, init = { setOf() })
    //var subsCache = Array<Set<SubtitleData>>(size = episodes.size, init = { setOf() })

    // TwitchClipFallback by ChatGPT: app-level fallback for Twitch clip pages.
    private fun extractTwitchClipSlugForFallback(dataUrl: String): String? {
        val raw = dataUrl.trim()
        val decoded = try {
            URLDecoder.decode(raw, "UTF-8")
        } catch (_: Throwable) {
            raw
        }
        val normalized = decoded.replace("\\/", "/")
        val sources = listOf(raw, decoded, normalized)
        val patterns = listOf(
            Regex("""(?i)clips\.twitch\.tv/([A-Za-z0-9_-]+)"""),
            Regex("""(?i)twitch\.tv/[^/?#]+/clip/([A-Za-z0-9_-]+)"""),
            Regex("""["'](?:slug|clipSlug)["']\s*:\s*["']([A-Za-z0-9_-]+)["']"""),
            Regex("""(?i)[?&#](?:slug|clipSlug|clip)=([A-Za-z0-9_-]+)"""),
            Regex("""(?i)(?:slug|clipSlug|clip):([A-Za-z0-9_-]+)""")
        )

        for (source in sources) {
            for (pattern in patterns) {
                val match = pattern.find(source) ?: continue
                return match.groupValues.getOrNull(1)
                    ?.substringBefore("?")
                    ?.trimEnd('/')
                    ?.takeIf { it.isNotBlank() }
            }
        }

        return null
    }

    private suspend fun tryLoadTwitchClipFallback(
        dataUrl: String,
        sourceTypes: Set<ExtractorLinkType>,
        currentCache: Cache,
        currentLinksUrls: MutableSet<String>,
        callback: (Pair<ExtractorLink?, ExtractorUri?>) -> Unit,
    ): Boolean {
        val slug = extractTwitchClipSlugForFallback(dataUrl) ?: return false

        return try {
            val query = """
                query VideoAccessToken_Clip(${'$'}slug: ID!) {
                    clip(slug: ${'$'}slug) {
                        playbackAccessToken(params: {platform: "web", playerBackend: "mediaplayer", playerType: "site"}) {
                            signature
                            value
                        }
                    }
                }
            """.trimIndent()
            val body = mapOf(
                "operationName" to "VideoAccessToken_Clip",
                "variables" to mapOf("slug" to slug),
                "query" to query,
            ).toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
            fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

            val response = app.post(
                "https://gql.twitch.tv/gql",
                headers = mapOf(
                    "Accept" to "application/json",
                    "Client-ID" to "kimne78kx3ncx6brgo4mv6wki5h1ko"
                ),
                requestBody = body,
                cacheTime = 0
            )
            val accessToken = JSONObject(response.text)
                .optJSONObject("data")
                ?.optJSONObject("clip")
                ?.optJSONObject("playbackAccessToken")
            val signature = accessToken?.optString("signature").orEmpty()
            val tokenValue = accessToken?.optString("value").orEmpty()

            if (signature.isBlank() || tokenValue.isBlank()) {
                Log.w(TAG, "Twitch clip fallback did not receive a playback token for $slug")
                return false
            }

            val hlsUrl = "https://usher.ttvnw.net/api/v2/clips/${urlEncode(slug)}.m3u8" +
                    "?sig=${urlEncode(signature)}" +
                    "&token=${urlEncode(tokenValue)}" +
                    "&allow_source=true" +
                    "&allow_audio_only=true" +
                    "&player=twitchweb"

            if (!currentLinksUrls.add(hlsUrl)) {
                return false
            }

            val link = ExtractorLink(
                source = "Twitch Clip",
                name = "Twitch Clip",
                url = hlsUrl,
                referer = "https://clips.twitch.tv/",
                quality = 0,
                type = ExtractorLinkType.M3U8,
            )

            synchronized(currentCache) {
                if (currentCache.linkCache.add(link)) {
                    if (sourceTypes.contains(link.type)) {
                        callback(link to null)
                    }
                    currentCache.lastCachedTimestamp = unixTime
                }
            }

            true
        } catch (t: Throwable) {
            Log.w(TAG, "Twitch clip fallback failed for $slug", t)
            false
        }
    }
    @Throws
    override suspend fun generateLinks(
        clearCache: Boolean,
        sourceTypes: Set<ExtractorLinkType>,
        callback: (Pair<ExtractorLink?, ExtractorUri?>) -> Unit,
        subtitleCallback: (SubtitleData) -> Unit,
        offset: Int,
        isCasting: Boolean,
    ): Boolean {
        val current = videos.getOrNull(offset) ?: return false

        val currentCache = synchronized(cache) {
            cache[current.apiName to current.id] ?: Cache(
                mutableSetOf(),
                mutableSetOf(),
                unixTime,
                false
            ).also {
                cache[current.apiName to current.id] = it
            }
        }

        // These act as a general filter to prevent duplication of links or names
        // Avoid any possible ConcurrentModificationException
        val currentLinksUrls = ConcurrentHashMap.newKeySet<String>()
        val currentSubsUrls = ConcurrentHashMap.newKeySet<String>()
        // Use atomics as otherwise we get race conditions when incrementing, while rare it did actually happen!
        val lastCountedSuffix = ConcurrentHashMap<String, AtomicInteger>()

        synchronized(currentCache) {
            val outdatedCache =
                unixTime - currentCache.lastCachedTimestamp > 60 * 20 // 20 minutes

            if (outdatedCache || clearCache) {
                currentCache.linkCache.clear()
                currentCache.subtitleCache.clear()
                currentCache.saturated = false
            } else if (currentCache.linkCache.isNotEmpty()) {
                Log.d(
                    TAG,
                    "Resumed previous loading from ${unixTime - currentCache.lastCachedTimestamp}s ago"
                )
            }

            // call all callbacks
            currentCache.linkCache.forEach { link ->
                currentLinksUrls.add(link.url)
                if (sourceTypes.contains(link.type)) {
                    callback(link to null)
                }
            }

            currentCache.subtitleCache.forEach { sub ->
                currentSubsUrls.add(sub.url)
                lastCountedSuffix.getOrPut(sub.originalName) { AtomicInteger(0) }.incrementAndGet()
                subtitleCallback(sub)
            }

            // this stops all execution if links are cached
            // no extra get requests
            if (currentCache.saturated) {
                return true
            }
        }

        val result = APIRepository(
            getApiFromNameNull(current.apiName) ?: throw Exception("This provider does not exist")
        ).loadLinks(
            current.data,
            isCasting = isCasting,
            subtitleCallback = { file ->
                Log.d(TAG, "Loaded SubtitleFile: $file")
                val correctFile = PlayerSubtitleHelper.getSubtitleData(file)
                if (correctFile.url.isBlank() || !currentSubsUrls.add(correctFile.url)) {
                    return@loadLinks
                }

                // this part makes sure that all names are unique for UX
                val nameDecoded = correctFile.originalName.html().toString()
                    .trim() // `%3Ch1%3Esub%20name…` → `<h1>sub name…` → `sub name…`
                val suffixCount =
                    lastCountedSuffix.getOrPut(nameDecoded) { AtomicInteger(0) }.incrementAndGet()

                val updatedFile =
                    correctFile.copy(originalName = nameDecoded, nameSuffix = "$suffixCount")

                synchronized(currentCache) {
                    if (currentCache.subtitleCache.add(updatedFile)) {
                        subtitleCallback(updatedFile)
                        currentCache.lastCachedTimestamp = unixTime
                    }
                }
            },
            callback = { link ->
                Log.d(TAG, "Loaded ExtractorLink: $link")
                if (link.url.isBlank() || !currentLinksUrls.add(link.url)) {
                    return@loadLinks
                }

                synchronized(currentCache) {
                    if (currentCache.linkCache.add(link)) {
                        if (sourceTypes.contains(link.type)) {
                            callback(Pair(link, null))
                        }

                        currentCache.linkCache.add(link)
                        currentCache.lastCachedTimestamp = unixTime
                    }
                }
            }
        )
        if (currentCache.linkCache.isEmpty()) {
            tryLoadTwitchClipFallback(
                dataUrl = current.data,
                sourceTypes = sourceTypes,
                currentCache = currentCache,
                currentLinksUrls = currentLinksUrls,
                callback = callback
            )
        }

        synchronized(currentCache) {
            currentCache.saturated = currentCache.linkCache.isNotEmpty()
            currentCache.lastCachedTimestamp = unixTime
        }

        return result
    }
}