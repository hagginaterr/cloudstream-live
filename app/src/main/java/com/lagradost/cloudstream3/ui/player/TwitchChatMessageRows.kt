package com.lagradost.cloudstream3.ui.player

import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.DynamicDrawableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.StyleSpan
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import recloudstream.twitchlivefavorites.TwitchChatBadges
import recloudstream.twitchlivefavorites.TwitchChatEmote
import java.io.ByteArrayInputStream
import java.net.URL
import java.nio.ByteBuffer
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt

internal object TwitchChatMessageRows {
    // TwitchChatMediaCacheV27: share decoded media and coalesce layout settling.
    private const val DRAWABLE_FAILURE_TTL_MS = 30_000L
    private const val MEDIA_SETTLE_DELAY_MS = 48L
    private val drawableStateCache = object : LruCache<String, Drawable.ConstantState>(192) {}
    private val drawableBytesCache = object : LruCache<String, ByteArray>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }
    private val drawableLoadLocks = ConcurrentHashMap<String, Any>()
    private val drawableFailureUntil = ConcurrentHashMap<String, Long>()
    private val pendingMediaSettles = WeakHashMap<ScrollView, Runnable>()
    fun create(
        container: LinearLayout,
        scope: CoroutineScope,
        channelLogin: String?,
        timestampLabel: String,
        displayName: String,
        fallbackName: String,
        color: Int?,
        text: String,
        badges: List<String>,
        isTv: Boolean,
        emotes: List<TwitchChatEmote> = emptyList(),
        settings: TwitchChatSettingsState = TwitchChatSettings.load(container.context),
    ): View {
        val visibleBadges = if (settings.badgesEnabled) {
            badges
                .asSequence()
                .filter { it.isNotBlank() }
                .distinct()
                .take(if (isTv) 4 else 5)
                .toList()
        } else {
            emptyList()
        }
        val visibleEmotes = emotes.filter { settings.emotesEnabledFor(it.provider) }
        val badgeSizePx = dp(container, if (isTv) 16 else 14)
        val emoteSizePx = dp(
            container,
            (settings.clampedFontSizeSp * if (isTv) 2.25f else 2.05f).toInt().coerceIn(18, 44),
        )

        val textView = TextView(container.context).apply {
            this.text = formatText(
                timestampLabel = timestampLabel,
                badges = visibleBadges,
                displayName = displayName,
                fallbackName = fallbackName,
                color = color,
                text = text,
                emotes = visibleEmotes,
                badgeSizePx = badgeSizePx,
                settings = settings,
            )
            setTextColor(Color.WHITE)
            textSize = settings.clampedFontSizeSp.toFloat() + if (isTv) 0.5f else 0f
            setMaxLines(if (settings.slowMode) 2 else 3)
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setSingleLine(false)
            setHorizontallyScrolling(false)
            setLineSpacing(0f, if (settings.clampedFontSizeSp >= 18) 1.0f else 1.04f)
            setShadowLayer(2f, 1f, 1f, Color.BLACK)
            setPadding(0, dp(container, 1), 0, dp(container, 2))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(container, if (isTv) 4 else 3)
            }
        }

        if (visibleBadges.isNotEmpty() || visibleEmotes.isNotEmpty()) {
            loadInlineMedia(
                scope = scope,
                textView = textView,
                channelLogin = channelLogin,
                timestampLabel = timestampLabel,
                badges = visibleBadges,
                displayName = displayName,
                fallbackName = fallbackName,
                color = color,
                text = text,
                emotes = visibleEmotes,
                badgeSizePx = badgeSizePx,
                emoteSizePx = emoteSizePx,
                settings = settings,
            )
        }

        return textView
    }

    private fun formatText(
        timestampLabel: String,
        badges: List<String>,
        displayName: String,
        fallbackName: String,
        color: Int?,
        text: String,
        emotes: List<TwitchChatEmote> = emptyList(),
        badgeDrawables: Map<String, Drawable> = emptyMap(),
        badgeSizePx: Int = 0,
        emoteDrawables: Map<String, Drawable> = emptyMap(),
        emoteSizePx: Int = 0,
        settings: TwitchChatSettingsState,
        spanHost: TextView? = null,
    ): CharSequence {
        val cleanName = clean(displayName).ifBlank { clean(fallbackName).ifBlank { "Twitch" } }
        val cleanText = cleanMessagePreservingOffsets(text)
        val builder = SpannableStringBuilder()

        // Required order: timestamp first, then badges, then chatter name, then message.
        if (timestampLabel.isNotBlank()) {
            val timeStart = builder.length
            builder.append(timestampLabel)
            builder.setSpan(
                ForegroundColorSpan(0xFFB8B8B8.toInt()),
                timeStart,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            builder.append(' ')
        }

        if (settings.badgesEnabled && badgeSizePx > 0) {
            badges.forEach { rawBadge ->
                val drawable = badgeDrawables[rawBadge]
                if (drawable != null) {
                    val badgeStart = builder.length
                    val badge = drawable.constantState?.newDrawable()?.mutate() ?: drawable.mutate()
                    setBoundsPreservingAspectRatio(
                        drawable = badge,
                        targetHeightPx = badgeSizePx,
                        maxWidthPx = badgeSizePx * 2,
                    )
                    builder.append('\uFFFC')
                    builder.setSpan(
                        ImageSpan(badge, ImageSpan.ALIGN_BOTTOM),
                        badgeStart,
                        badgeStart + 1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    builder.append(' ')
                }
            }
        }

        val nameStart = builder.length
        builder.append(cleanName)
        builder.setSpan(
            ForegroundColorSpan(usernameColor(color, settings)),
            nameStart,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        builder.setSpan(StyleSpan(Typeface.BOLD), nameStart, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.append(": ")

        val messageStart = builder.length
        builder.append(cleanText)
        applyEmoteSpans(
            builder = builder,
            messageStart = messageStart,
            messageText = cleanText,
            emotes = emotes,
            emoteDrawables = emoteDrawables,
            emoteSizePx = emoteSizePx,
            spanHost = spanHost,
        )
        return builder
    }

    private fun usernameColor(color: Int?, settings: TwitchChatSettingsState): Int {
        return if (settings.coloredUsernames) {
            color ?: Color.rgb(153, 214, 255)
        } else {
            Color.rgb(218, 218, 218)
        }
    }

    private fun loadInlineMedia(
        scope: CoroutineScope,
        textView: TextView,
        channelLogin: String?,
        timestampLabel: String,
        badges: List<String>,
        displayName: String,
        fallbackName: String,
        color: Int?,
        text: String,
        emotes: List<TwitchChatEmote>,
        badgeSizePx: Int,
        emoteSizePx: Int,
        settings: TwitchChatSettingsState,
    ) {
        scope.launch {
            val media = withContext(Dispatchers.IO) {
                val resolver = if (settings.badgesEnabled && badges.isNotEmpty() && !channelLogin.isNullOrBlank()) {
                    runCatching { TwitchChatBadges.loadForChannel(channelLogin) }.getOrNull()
                } else {
                    null
                }

                val badgeDrawables = if (settings.badgesEnabled && badges.isNotEmpty()) {
                    badges.mapNotNull { rawBadge ->
                        val officialUrl = resolver?.imageUrlFor(rawBadge)
                        val url = if (!officialUrl.isNullOrBlank()) {
                            officialUrl
                        } else {
                            TwitchChatBadges.fallbackImageUrl(rawBadge)
                        }
                        if (url.isNullOrBlank()) {
                            null
                        } else {
                            loadDrawable(
                                url = url,
                                targetHeightPx = badgeSizePx,
                                maxWidthPx = badgeSizePx * 2,
                            )?.let { rawBadge to it }
                        }
                    }.toMap()
                } else {
                    emptyMap()
                }

                val emoteDrawables = emotes
                    .map { it.imageUrl }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .take(40)
                    .mapNotNull { url ->
                        loadDrawable(
                            url = url,
                            targetHeightPx = emoteSizePx,
                            maxWidthPx = emoteSizePx * 6,
                        )?.let { url to it }
                    }
                    .toMap()

                badgeDrawables to emoteDrawables
            }

            val badgeDrawables = media.first
            val emoteDrawables = media.second
            if (badgeDrawables.isEmpty() && emoteDrawables.isEmpty()) return@launch

            withContext(Dispatchers.Main.immediate) {
                if (!textView.isAttachedToWindow) return@withContext
                textView.setTextKeepState(formatText(
                    timestampLabel = timestampLabel,
                    badges = badges,
                    displayName = displayName,
                    fallbackName = fallbackName,
                    color = color,
                    text = text,
                    emotes = emotes,
                    badgeDrawables = badgeDrawables,
                    badgeSizePx = badgeSizePx,
                    emoteDrawables = emoteDrawables,
                    emoteSizePx = emoteSizePx,
                    settings = settings,
                    spanHost = textView,
                ))
                scheduleOwningChatSettle(textView)
            }
        }
    }

    private fun applyEmoteSpans(
        builder: SpannableStringBuilder,
        messageStart: Int,
        messageText: String,
        emotes: List<TwitchChatEmote>,
        emoteDrawables: Map<String, Drawable>,
        emoteSizePx: Int,
        spanHost: TextView?,
    ) {
        if (emoteDrawables.isEmpty() || emoteSizePx <= 0) return

        emotes.forEach { emote ->
            val spanStart = messageStart + emote.start
            val spanEnd = messageStart + emote.endExclusive
            if (emote.start < 0 || emote.endExclusive > messageText.length || spanStart >= spanEnd) return@forEach

            val source = emoteDrawables[emote.imageUrl] ?: return@forEach
            val drawable = source.constantState?.newDrawable()?.mutate() ?: source.mutate()
            setBoundsPreservingAspectRatio(
                drawable = drawable,
                targetHeightPx = emoteSizePx,
                maxWidthPx = emoteSizePx * 6,
            )
            prepareAnimatedDrawable(drawable)

            val span = if (spanHost != null && drawable is Animatable) {
                AnimatedInlineImageSpan(spanHost, drawable)
            } else {
                ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM)
            }
            builder.setSpan(span, spanStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun loadDrawable(
        url: String,
        targetHeightPx: Int,
        maxWidthPx: Int,
    ): Drawable? {
        candidateImageUrls(url).forEach { candidate ->
            loadDrawableCandidate(candidate, targetHeightPx, maxWidthPx)?.let { return it }
        }
        return null
    }

    private fun loadDrawableCandidate(
        candidate: String,
        targetHeightPx: Int,
        maxWidthPx: Int,
    ): Drawable? {
        val renderKey = "$candidate|$targetHeightPx|$maxWidthPx"
        cachedDrawable(renderKey, targetHeightPx, maxWidthPx)?.let { return it }

        val lock = drawableLoadLocks.getOrPut(candidate) { Any() }
        return try {
            synchronized(lock) {
                cachedDrawable(renderKey, targetHeightPx, maxWidthPx)?.let { return@synchronized it }

                var bytes = synchronized(drawableBytesCache) { drawableBytesCache.get(candidate) }
                if (bytes == null) {
                    val now = SystemClock.uptimeMillis()
                    val failedUntil = drawableFailureUntil[candidate] ?: 0L
                    if (failedUntil > now) return@synchronized null
                    val downloaded = runCatching {
                        val connection = URL(candidate).openConnection().apply {
                            connectTimeout = 3_000
                            readTimeout = 5_000
                            useCaches = true
                        }
                        connection.getInputStream().use { stream -> stream.readBytes() }
                    }.getOrNull()
                    if (downloaded == null) {
                        drawableFailureUntil[candidate] = now + DRAWABLE_FAILURE_TTL_MS
                        return@synchronized null
                    }
                    bytes = downloaded
                    drawableFailureUntil.remove(candidate)
                    synchronized(drawableBytesCache) { drawableBytesCache.put(candidate, downloaded) }
                }

                val loadedBytes = bytes ?: return@synchronized null
                val decoded = decodeDrawable(
                    bytes = loadedBytes,
                    targetHeightPx = targetHeightPx,
                    maxWidthPx = maxWidthPx,
                ) ?: return@synchronized null
                decoded.constantState?.let { state ->
                    synchronized(drawableStateCache) { drawableStateCache.put(renderKey, state) }
                    return@synchronized state.newDrawable().mutate().also { drawable ->
                        setBoundsPreservingAspectRatio(drawable, targetHeightPx, maxWidthPx)
                        prepareAnimatedDrawable(drawable)
                    }
                }
                decoded
            }
        } finally {
            drawableLoadLocks.remove(candidate, lock)
        }
    }

    private fun cachedDrawable(
        renderKey: String,
        targetHeightPx: Int,
        maxWidthPx: Int,
    ): Drawable? {
        val state = synchronized(drawableStateCache) { drawableStateCache.get(renderKey) } ?: return null
        return state.newDrawable().mutate().also { drawable ->
            setBoundsPreservingAspectRatio(drawable, targetHeightPx, maxWidthPx)
            prepareAnimatedDrawable(drawable)
        }
    }

    private fun decodeDrawable(
        bytes: ByteArray,
        targetHeightPx: Int,
        maxWidthPx: Int,
    ): Drawable? {
        val decoded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val targetWidthPx = scaledWidthForHeight(
                        sourceWidth = info.size.width,
                        sourceHeight = info.size.height,
                        targetHeightPx = targetHeightPx,
                        maxWidthPx = maxWidthPx,
                    )
                    decoder.setTargetSize(targetWidthPx, targetHeightPx)
                }
            }.getOrNull()
        } else {
            null
        }

        val drawable = decoded ?: Drawable.createFromStream(ByteArrayInputStream(bytes), null)
        drawable?.let {
            setBoundsPreservingAspectRatio(
                drawable = it,
                targetHeightPx = targetHeightPx,
                maxWidthPx = maxWidthPx,
            )
            prepareAnimatedDrawable(it)
        }
        return drawable
    }

    private fun setBoundsPreservingAspectRatio(
        drawable: Drawable,
        targetHeightPx: Int,
        maxWidthPx: Int,
    ) {
        val sourceWidth = drawable.intrinsicWidth
            .takeIf { it > 0 }
            ?: drawable.bounds.width().takeIf { it > 0 }
            ?: targetHeightPx
        val sourceHeight = drawable.intrinsicHeight
            .takeIf { it > 0 }
            ?: drawable.bounds.height().takeIf { it > 0 }
            ?: targetHeightPx
        val targetWidthPx = scaledWidthForHeight(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            targetHeightPx = targetHeightPx,
            maxWidthPx = maxWidthPx,
        )
        drawable.setBounds(0, 0, targetWidthPx, targetHeightPx)
    }

    private fun scaledWidthForHeight(
        sourceWidth: Int,
        sourceHeight: Int,
        targetHeightPx: Int,
        maxWidthPx: Int,
    ): Int {
        if (sourceWidth <= 0 || sourceHeight <= 0) return targetHeightPx
        return (sourceWidth.toFloat() / sourceHeight.toFloat() * targetHeightPx.toFloat())
            .roundToInt()
            .coerceIn(1, maxWidthPx.coerceAtLeast(1))
    }

    private fun prepareAnimatedDrawable(drawable: Drawable) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && drawable is AnimatedImageDrawable) {
            drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
        }
        if (drawable is Animatable && !drawable.isRunning) {
            runCatching { drawable.start() }
        }
    }

    private fun candidateImageUrls(url: String): List<String> {
        val clean = url.trim()
        if (clean.isBlank()) return emptyList()
        val candidates = linkedSetOf(clean)

        if (clean.contains("/emoticons/v2/") && clean.contains("/animated/")) {
            candidates.add(clean.replace("/animated/", "/default/"))
        }

        if (clean.contains("cdn.betterttv.net/emote/")) {
            when {
                clean.endsWith(".gif", ignoreCase = true) -> {
                    candidates.add(clean.removeSuffix(".gif") + ".webp")
                    candidates.add(clean.removeSuffix(".gif") + ".png")
                }
                clean.endsWith(".webp", ignoreCase = true) -> {
                    candidates.add(clean.removeSuffix(".webp") + ".gif")
                    candidates.add(clean.removeSuffix(".webp") + ".png")
                }
                clean.endsWith(".png", ignoreCase = true) -> {
                    candidates.add(clean.removeSuffix(".png") + ".gif")
                    candidates.add(clean.removeSuffix(".png") + ".webp")
                }
                else -> {
                    candidates.add("$clean.gif")
                    candidates.add("$clean.webp")
                    candidates.add("$clean.png")
                }
            }
        }

        return candidates.toList()
    }

    private class AnimatedInlineImageSpan(
        private val host: TextView,
        private val animatedDrawable: Drawable,
    ) : DynamicDrawableSpan(DynamicDrawableSpan.ALIGN_BOTTOM), Drawable.Callback {
        init {
            animatedDrawable.callback = this
            val animatable = animatedDrawable as? Animatable
            if (animatable != null && !animatable.isRunning) {
                runCatching { animatable.start() }
            }
        }

        override fun getDrawable(): Drawable = animatedDrawable

        override fun invalidateDrawable(who: Drawable) {
            if (host.isAttachedToWindow) {
                host.postInvalidateOnAnimation()
            }
        }

        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
            if (host.isAttachedToWindow) {
                host.postDelayed(what, max(0L, `when` - SystemClock.uptimeMillis()))
            }
        }

        override fun unscheduleDrawable(who: Drawable, what: Runnable) {
            host.removeCallbacks(what)
        }
    }

    // TwitchChatWholeRowViewportPatch:
    // Wrapping and asynchronously loaded emotes make estimated row heights unreliable.
    // Fit measured rows and remove complete oldest rows instead of clipping one at the top.
    private val pendingViewportFits =
        java.util.WeakHashMap<ScrollView, ViewTreeObserver.OnPreDrawListener>()

    internal fun fitAndScrollToBottom(scrollView: ScrollView) {
        val observer = scrollView.viewTreeObserver
        if (!observer.isAlive) return

        pendingViewportFits.remove(scrollView)?.let { pending ->
            observer.removeOnPreDrawListener(pending)
        }

        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val currentObserver = scrollView.viewTreeObserver
                if (!currentObserver.isAlive) {
                    pendingViewportFits.remove(scrollView)
                    return true
                }

                val container = scrollView.getChildAt(0) as? LinearLayout
                if (container == null) {
                    currentObserver.removeOnPreDrawListener(this)
                    pendingViewportFits.remove(scrollView)
                    return true
                }

                val viewportHeight = (
                    scrollView.height -
                        scrollView.paddingTop -
                        scrollView.paddingBottom
                    ).coerceAtLeast(0)
                if (viewportHeight <= 0) {
                    currentObserver.removeOnPreDrawListener(this)
                    pendingViewportFits.remove(scrollView)
                    return true
                }

                var contentHeight = measuredMessageContentHeight(container)
                var removedOldestRow = false
                while (container.childCount > 1 && contentHeight > viewportHeight) {
                    val oldestRow = container.getChildAt(0)
                    contentHeight -= measuredOuterHeight(oldestRow)
                    container.removeViewAt(0)
                    removedOldestRow = true
                }

                if (removedOldestRow) {
                    container.requestLayout()
                    scrollView.requestLayout()
                    return false
                }

                currentObserver.removeOnPreDrawListener(this)
                pendingViewportFits.remove(scrollView)
                val scrollRange =
                    (container.measuredHeight - viewportHeight).coerceAtLeast(0)
                scrollView.scrollTo(0, scrollRange)
                return true
            }
        }

        pendingViewportFits[scrollView] = listener
        observer.addOnPreDrawListener(listener)
        scrollView.requestLayout()
    }

    private fun measuredMessageContentHeight(container: LinearLayout): Int {
        var height = container.paddingTop + container.paddingBottom
        for (index in 0 until container.childCount) {
            height += measuredOuterHeight(container.getChildAt(index))
        }
        return height
    }

    private fun measuredOuterHeight(view: View): Int {
        val margins = view.layoutParams as? ViewGroup.MarginLayoutParams
        return view.measuredHeight +
            (margins?.topMargin ?: 0) +
            (margins?.bottomMargin ?: 0)
    }

    private fun scheduleOwningChatSettle(view: View) {
        var parent = view.parent
        while (parent != null && parent !is ScrollView) {
            parent = parent.parent
        }
        val scrollView = parent as? ScrollView ?: return
        val pending = synchronized(pendingMediaSettles) { pendingMediaSettles.remove(scrollView) }
        if (pending != null) scrollView.removeCallbacks(pending)

        lateinit var settle: Runnable
        settle = Runnable {
            synchronized(pendingMediaSettles) {
                if (pendingMediaSettles[scrollView] === settle) {
                    pendingMediaSettles.remove(scrollView)
                }
            }
            if (scrollView.isAttachedToWindow) {
                fitAndScrollToBottom(scrollView)
            }
        }
        synchronized(pendingMediaSettles) { pendingMediaSettles[scrollView] = settle }
        scrollView.postDelayed(settle, MEDIA_SETTLE_DELAY_MS)
    }

    private fun cleanMessagePreservingOffsets(value: String): String {
        if (value.isEmpty()) return value
        return buildString(value.length) {
            value.forEach { ch ->
                append(if (ch == '\r' || ch == '\n' || ch == '\t' || ch.isISOControl()) ' ' else ch)
            }
        }.trimEnd()
    }

    private fun clean(value: String): String {
        return value
            .filter { !it.isISOControl() }
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun dp(view: View, value: Int): Int {
        return (value * view.context.resources.displayMetrics.density + 0.5f).toInt()
    }
}
