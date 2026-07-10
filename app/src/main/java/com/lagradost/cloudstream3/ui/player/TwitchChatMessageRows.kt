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
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
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
import kotlin.math.max

internal object TwitchChatMessageRows {
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
                    badge.setBounds(0, 0, badgeSizePx, badgeSizePx)
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
                            loadDrawable(url, badgeSizePx)?.let { rawBadge to it }
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
                    .mapNotNull { url -> loadDrawable(url, emoteSizePx)?.let { url to it } }
                    .toMap()

                badgeDrawables to emoteDrawables
            }

            val badgeDrawables = media.first
            val emoteDrawables = media.second
            if (badgeDrawables.isEmpty() && emoteDrawables.isEmpty()) return@launch

            withContext(Dispatchers.Main.immediate) {
                if (!textView.isAttachedToWindow) return@withContext
                textView.text = formatText(
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
                )
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
            drawable.setBounds(0, 0, emoteSizePx, emoteSizePx)
            prepareAnimatedDrawable(drawable)

            val span = if (spanHost != null && drawable is Animatable) {
                AnimatedInlineImageSpan(spanHost, drawable)
            } else {
                ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM)
            }
            builder.setSpan(span, spanStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun loadDrawable(url: String, sizePx: Int): Drawable? {
        candidateImageUrls(url).forEach { candidate ->
            val drawable = runCatching {
                val connection = URL(candidate).openConnection().apply {
                    connectTimeout = 3_000
                    readTimeout = 5_000
                }
                val bytes = connection.getInputStream().use { stream -> stream.readBytes() }
                decodeDrawable(bytes, sizePx)
            }.getOrNull()
            if (drawable != null) return drawable
        }
        return null
    }

    private fun decodeDrawable(bytes: ByteArray, sizePx: Int): Drawable? {
        val decoded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetSize(sizePx, sizePx)
                }
            }.getOrNull()
        } else {
            null
        }

        val drawable = decoded ?: Drawable.createFromStream(ByteArrayInputStream(bytes), null)
        drawable?.setBounds(0, 0, sizePx, sizePx)
        drawable?.let { prepareAnimatedDrawable(it) }
        return drawable
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
            if (animatedDrawable is Animatable && !animatedDrawable.isRunning) {
                runCatching { animatedDrawable.start() }
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
