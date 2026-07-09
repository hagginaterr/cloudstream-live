package recloudstream.twitchlivefavorites










import com.lagradost.cloudstream3.utils.getImageFromDrawable
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import android.widget.ImageView
import android.view.animation.AccelerateDecelerateInterpolator
import android.os.Build
import android.graphics.drawable.GradientDrawable
import android.animation.ValueAnimator
import android.animation.ObjectAnimator
import android.animation.AnimatorSet
import android.widget.Toast
import android.graphics.Color
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.navigation.NavOptions
import android.view.ViewGroup
import android.view.View
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.LiveSearchResponse
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.ui.player.ExtractorLinkGenerator
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.utils.AppContextUtils.loadSearchResult
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink

object TwitchDirectPlayHelper {
    private const val DIRECT_PLAY_MARKER = "cloudstream_direct_play=1"
    private const val STREAM_API = "https://pwn.sh/tools/streamapi.py?url="
    private const val HOME_RESTORE_DELAY_MS = 3_000L
    private const val DIRECT_PLAY_LOADING_OVERLAY_TAG = "cloudstream_twitch_direct_play_loading_overlay"
    private var directPlayLoadingPulse: AnimatorSet? = null

    private fun noAnimationNavOptions(): NavOptions {
        return NavOptions.Builder()
            .setEnterAnim(0)
            .setExitAnim(0)
            .setPopEnterAnim(0)
            .setPopExitAnim(0)
            .build()
    }

        private fun showDirectPlayLoadingOverlay(avatarUrl: String?) {
        val act = activity ?: return
        act.runOnUiThread {
            val decor = act.window?.decorView as? ViewGroup ?: return@runOnUiThread
            val existing = decor.findViewWithTag<View>(DIRECT_PLAY_LOADING_OVERLAY_TAG)
            if (existing != null) {
                existing.bringToFront()
                existing.requestFocus()
                return@runOnUiThread
            }

            fun dp(value: Int): Int = (value * act.resources.displayMetrics.density).toInt()

            val overlay = FrameLayout(act).apply {
                tag = DIRECT_PLAY_LOADING_OVERLAY_TAG
                setBackgroundColor(Color.BLACK)
                isClickable = true
                isFocusable = true
                isFocusableInTouchMode = true
                alpha = 0f
            }

            val avatarSize = dp(132)
            val avatarPadding = dp(6)
            val avatarFrame = FrameLayout(act).apply {
                contentDescription = "Loading stream"
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.rgb(16, 16, 16))
                    setStroke(dp(3), Color.argb(190, 255, 255, 255))
                }
                elevation = dp(10).toFloat()
                setPadding(avatarPadding, avatarPadding, avatarPadding, avatarPadding)
            }

            val avatar = ImageView(act).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.rgb(28, 28, 28))
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    clipToOutline = true
                }

                avatarUrl?.takeIf { it.isNotBlank() }?.let { url ->
                    loadImage(url) {
                        error {
                            getImageFromDrawable(act, R.drawable.default_cover)
                        }
                    }
                } ?: loadImage(R.drawable.default_cover)
            }

            avatarFrame.addView(
                avatar,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER,
                ),
            )

            overlay.addView(
                avatarFrame,
                FrameLayout.LayoutParams(
                    avatarSize,
                    avatarSize,
                    Gravity.CENTER,
                ),
            )

            decor.addView(
                overlay,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )

            overlay.bringToFront()
            overlay.requestFocus()
            overlay.animate().alpha(1f).setDuration(120L).start()

            directPlayLoadingPulse?.cancel()
            val scaleX = ObjectAnimator.ofFloat(avatarFrame, View.SCALE_X, 0.94f, 1.08f)
            val scaleY = ObjectAnimator.ofFloat(avatarFrame, View.SCALE_Y, 0.94f, 1.08f)
            val alphaPulse = ObjectAnimator.ofFloat(avatarFrame, View.ALPHA, 0.72f, 1.0f)
            listOf(scaleX, scaleY, alphaPulse).forEach { animator ->
                animator.repeatCount = ValueAnimator.INFINITE
                animator.repeatMode = ValueAnimator.REVERSE
            }
            directPlayLoadingPulse = AnimatorSet().apply {
                playTogether(scaleX, scaleY, alphaPulse)
                duration = 900L
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        }
    }

    private fun hideDirectPlayLoadingOverlay(animated: Boolean = true) {
        directPlayLoadingPulse?.cancel()
        directPlayLoadingPulse = null
        val act = activity ?: return
        act.runOnUiThread {
            val decor = act.window?.decorView as? ViewGroup ?: return@runOnUiThread
            val overlay = decor.findViewWithTag<View>(DIRECT_PLAY_LOADING_OVERLAY_TAG) ?: return@runOnUiThread
            overlay.animate().cancel()
            if (animated) {
                overlay.animate()
                    .alpha(0f)
                    .setDuration(160L)
                    .withEndAction { (overlay.parent as? ViewGroup)?.removeView(overlay) }
                    .start()
            } else {
                (overlay.parent as? ViewGroup)?.removeView(overlay)
            }
        }
    }
private fun setHomeLaunchSuppressed(suppressed: Boolean) {
        val act = activity ?: return
        act.runOnUiThread {
            val root = act.findViewById<ViewGroup>(R.id.home_root) ?: return@runOnUiThread
            val master = root.findViewById<View>(R.id.home_master_recycler)
            val toolbar = root.findViewById<View>(R.id.home_api_holder)
            val targetAlpha = 1f

            root.descendantFocusability = if (suppressed) {
                ViewGroup.FOCUS_BLOCK_DESCENDANTS
            } else {
                ViewGroup.FOCUS_AFTER_DESCENDANTS
            }

            master?.animate()?.cancel()
            master?.alpha = targetAlpha
            master?.isFocusable = !suppressed
            master?.isFocusableInTouchMode = !suppressed
            (master as? ViewGroup)?.descendantFocusability = if (suppressed) {
                ViewGroup.FOCUS_BLOCK_DESCENDANTS
            } else {
                ViewGroup.FOCUS_AFTER_DESCENDANTS
            }

            toolbar?.animate()?.cancel()
            toolbar?.alpha = targetAlpha

            if (suppressed) {
                master?.clearFocus()
                root.clearFocus()
            }
        }
    }

    private fun suppressHomeDuringDirectPlayLaunch() {
        setHomeLaunchSuppressed(true)
    }

    private fun restoreHomeAfterDirectPlayLaunch(delayMs: Long = 0L) {
        val act = activity ?: return
        if (delayMs <= 0L) {
            setHomeLaunchSuppressed(false)
        } else {
            act.window?.decorView?.postDelayed({
                setHomeLaunchSuppressed(false)
            }, delayMs)
        }
    }

    private data class ApiResponse(
        val success: Boolean = false,
        val urls: Map<String, String>? = null,
    )

    fun tryOpen(card: SearchResponse): Boolean {
        if (!isDirectPlayCard(card)) return false

        val twitchUrl = cleanDirectPlayUrl(card.url)
            .ifBlank { return false }
        val title = card.name.ifBlank { "Twitch" }

        TwitchHomeRefreshFocus.suppressForMediaLaunch()
        showDirectPlayLoadingOverlay(directPlayLoadingAvatar(card))
        suppressHomeDuringDirectPlayLaunch()

        ioSafe {
            val links = fetchLinks(twitchUrl, title)

            activity?.runOnUiThread {
                if (links.isEmpty()) {
                    TwitchHomeRefreshFocus.clearFocusReapplySuppression()
                    hideDirectPlayLoadingOverlay()
                    restoreHomeAfterDirectPlayLaunch()
                    showToast("Could not open Twitch media", Toast.LENGTH_SHORT)
                    return@runOnUiThread
                }

                TwitchHomeRefreshFocus.suppressForMediaLaunch(6_000L)
                restoreHomeAfterDirectPlayLaunch(HOME_RESTORE_DELAY_MS)
                activity?.window?.decorView?.postDelayed({ hideDirectPlayLoadingOverlay() }, 450L)
                activity?.navigate(
                    R.id.global_to_navigation_player,
                    GeneratorPlayer.newInstance(
                        ExtractorLinkGenerator(links, emptyList()),
                        0,
                    ),
                    navOptions = noAnimationNavOptions(),
                )
            }
        }

        return true
    }

    fun tryOpenProfile(card: SearchResponse): Boolean {
        if (!isDirectPlayCard(card)) return false

        val profileUrl = cleanProfileUrl(card.url)
            .ifBlank { return false }

        val profileCard = when (card) {
            is LiveSearchResponse -> card.copy(url = profileUrl)
            else -> return false
        }

        loadSearchResult(profileCard)
        return true
    }

    private fun isDirectPlayCard(card: SearchResponse): Boolean {
        val api = card.apiName.lowercase()
        return (api == "twitch" || api == "twitch live favorites api") &&
            card.url.contains(DIRECT_PLAY_MARKER, ignoreCase = true)
    }

    private fun decodeDirectPlayQueryValue(value: String): String? {
    return runCatching {
        java.net.URLDecoder.decode(value, "UTF-8")
    }.getOrNull()?.trim()?.ifBlank { null }
}

private fun directPlayQueryParam(url: String, key: String): String? {
    val query = url.substringAfter("?", "").substringBefore("#")
    if (query.isBlank()) return null
    return query
        .split("&")
        .asSequence()
        .mapNotNull { part ->
            val rawKey = part.substringBefore("=", "")
            val rawValue = part.substringAfter("=", "")
            val decodedKey = decodeDirectPlayQueryValue(rawKey) ?: rawKey
            if (decodedKey == key) decodeDirectPlayQueryValue(rawValue) else null
        }
        .firstOrNull { it.isNotBlank() }
}

private fun directPlayLoadingAvatar(card: SearchResponse): String? {
    val avatar = directPlayQueryParam(card.url, "cs_streamer_avatar") ?: return null
    val lower = avatar.lowercase()
    val looksLikeStreamThumbnail = lower.contains("previews-ttv") ||
        lower.contains("-preview-") ||
        lower.contains("%{width}") ||
        lower.contains("{width}")
    return avatar.takeUnless { looksLikeStreamThumbnail }
}
private fun removeDirectPlayMarker(url: String): String {
        return url
            .replace(Regex("([?&])cloudstream_direct_play=1&?", RegexOption.IGNORE_CASE), "$1")
            .replace("?&", "?")
            .trimEnd('?', '&')
    }

    private fun cleanDirectPlayUrl(url: String): String {
        return removeDirectPlayMarker(url).substringBefore("#")
    }

    private fun cleanProfileUrl(url: String): String {
        return cleanDirectPlayUrl(url).substringBefore("?").trimEnd('/')
    }

    private suspend fun fetchLinks(twitchUrl: String, title: String): List<ExtractorLink> {
        val providerLinks = mutableListOf<ExtractorLink>()
        val providerHandled = runCatching {
            TwitchApiLiveFavoritesProvider().loadLinks(
                twitchUrl,
                isCasting = false,
                subtitleCallback = { _ -> },
                callback = { link -> providerLinks.add(link) },
            )
        }.getOrDefault(false)

        if (providerHandled && providerLinks.isNotEmpty()) {
            return providerLinks.distinctBy { it.url }
        }

        val response = runCatching {
            app.get("$STREAM_API$twitchUrl").parsed<ApiResponse>()
        }.getOrNull() ?: return providerLinks.distinctBy { it.url }

        val liveLinks = response.urls
            .orEmpty()
            .mapNotNull { (qualityName, streamUrl) ->
                if (streamUrl.isBlank()) return@mapNotNull null

                val quality = getQualityFromName(qualityName.substringBefore("p"))
                newExtractorLink(
                    "Twitch",
                    "$title ${qualityName.replace("${quality}p", "")}".trim(),
                    streamUrl,
                ) {
                    this.type = ExtractorLinkType.M3U8
                    this.quality = quality
                    this.referer = ""
                }
            }

        return (providerLinks + liveLinks).distinctBy { it.url }
    }
}