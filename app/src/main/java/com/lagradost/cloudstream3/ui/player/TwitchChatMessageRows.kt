package com.lagradost.cloudstream3.ui.player

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import recloudstream.twitchlivefavorites.TwitchChatBadges
import recloudstream.twitchlivefavorites.TwitchChatEmote
import java.net.URL

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
        val ctx = container.context
        val visibleBadges = if (settings.badgesEnabled) badges else emptyList()
        val visibleEmotes = emotes.filter { settings.emotesEnabledFor(it.provider) }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            clipToPadding = false
            clipChildren = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(container, if (isTv) 4 else 3)
            }
        }

        val badgeStrip = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipToPadding = false
            clipChildren = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginEnd = dp(container, 5)
                topMargin = dp(container, if (isTv) 1 else 0)
            }
        }

        val badgeViews = visibleBadges
            .asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .take(if (isTv) 4 else 5)
            .map { rawBadge -> rawBadge to badgeImageView(container, rawBadge, isTv) }
            .toList()

        badgeViews.forEach { (rawBadge, image) ->
            val fallbackUrl = TwitchChatBadges.fallbackImageUrl(rawBadge)
            if (!fallbackUrl.isNullOrBlank()) {
                image.isVisible = true
                image.loadImage(fallbackUrl)
            }
            badgeStrip.addView(image)
        }

        val textView = TextView(ctx).apply {
            this.text = formatText(
                timestampLabel = timestampLabel,
                displayName = displayName,
                fallbackName = fallbackName,
                color = color,
                text = text,
                emotes = visibleEmotes,
                settings = settings,
            )
            setTextColor(Color.WHITE)
            textSize = settings.clampedFontSizeSp.toFloat() + if (isTv) 0.5f else 0f
            maxLines = if (settings.slowMode) 2 else 3
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setLineSpacing(0f, if (settings.clampedFontSizeSp >= 18) 1.0f else 1.04f)
            setShadowLayer(2f, 1f, 1f, Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        if (badgeViews.isNotEmpty()) row.addView(badgeStrip)
        row.addView(textView)

        if (settings.badgesEnabled && badgeViews.isNotEmpty() && !channelLogin.isNullOrBlank()) {
            loadOfficialBadges(scope, channelLogin, row, badgeViews)
        }
        if (visibleEmotes.isNotEmpty()) {
            loadInlineEmotes(
                scope = scope,
                row = row,
                textView = textView,
                timestampLabel = timestampLabel,
                displayName = displayName,
                fallbackName = fallbackName,
                color = color,
                text = text,
                emotes = visibleEmotes,
                isTv = isTv,
                settings = settings,
            )
        }
        return row
    }

    private fun badgeImageView(container: LinearLayout, rawBadge: String, isTv: Boolean): ImageView {
        val size = dp(container, if (isTv) 16 else 14)
        val margin = dp(container, 2)
        return ImageView(container.context).apply {
            isVisible = false
            contentDescription = TwitchChatBadges.badgeTitle(rawBadge)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = false
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = margin }
        }
    }

    private fun loadOfficialBadges(
        scope: CoroutineScope,
        channelLogin: String,
        row: View,
        badgeViews: List<Pair<String, ImageView>>,
    ) {
        scope.launch {
            val resolver = TwitchChatBadges.loadForChannel(channelLogin)
            withContext(Dispatchers.Main.immediate) {
                if (!row.isAttachedToWindow) return@withContext
                badgeViews.forEach { (rawBadge, image) ->
                    val url = resolver.imageUrlFor(rawBadge)
                    if (!url.isNullOrBlank()) {
                        image.isVisible = true
                        image.loadImage(url)
                    }
                }
            }
        }
    }

    private fun formatText(
        timestampLabel: String,
        displayName: String,
        fallbackName: String,
        color: Int?,
        text: String,
        emotes: List<TwitchChatEmote> = emptyList(),
        emoteDrawables: Map<String, Drawable> = emptyMap(),
        emoteSizePx: Int = 0,
        settings: TwitchChatSettingsState,
    ): CharSequence {
        val cleanName = clean(displayName).ifBlank { clean(fallbackName).ifBlank { "Twitch" } }
        val cleanText = cleanMessagePreservingOffsets(text)
        val builder = SpannableStringBuilder()

        if (timestampLabel.isNotBlank()) {
            val timeStart = builder.length
            builder.append(timestampLabel).append(' ')
            builder.setSpan(
                ForegroundColorSpan(0xFFB8B8B8.toInt()),
                timeStart,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
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
        applyEmoteSpans(builder, messageStart, cleanText, emotes, emoteDrawables, emoteSizePx)
        return builder
    }

    private fun usernameColor(color: Int?, settings: TwitchChatSettingsState): Int {
        return if (settings.coloredUsernames) {
            color ?: Color.rgb(153, 214, 255)
        } else {
            Color.rgb(218, 218, 218)
        }
    }

    private fun applyEmoteSpans(
        builder: SpannableStringBuilder,
        messageStart: Int,
        messageText: String,
        emotes: List<TwitchChatEmote>,
        emoteDrawables: Map<String, Drawable>,
        emoteSizePx: Int,
    ) {
        if (emoteDrawables.isEmpty() || emoteSizePx <= 0) return
        emotes.forEach { emote ->
            val spanStart = messageStart + emote.start
            val spanEnd = messageStart + emote.endExclusive
            if (emote.start < 0 || emote.endExclusive > messageText.length || spanStart >= spanEnd) return@forEach
            val source = emoteDrawables[emote.imageUrl] ?: return@forEach
            val drawable = source.constantState?.newDrawable()?.mutate() ?: source.mutate()
            drawable.setBounds(0, 0, emoteSizePx, emoteSizePx)
            builder.setSpan(ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM), spanStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun loadInlineEmotes(
        scope: CoroutineScope,
        row: View,
        textView: TextView,
        timestampLabel: String,
        displayName: String,
        fallbackName: String,
        color: Int?,
        text: String,
        emotes: List<TwitchChatEmote>,
        isTv: Boolean,
        settings: TwitchChatSettingsState,
    ) {
        val sizePx = dp(textView, (settings.clampedFontSizeSp * if (isTv) 2.25f else 2.05f).toInt().coerceIn(18, 44))
        val urls = emotes.map { it.imageUrl }.filter { it.isNotBlank() }.distinct().take(40)
        if (urls.isEmpty()) return

        scope.launch {
            val drawables = withContext(Dispatchers.IO) {
                urls.mapNotNull { url -> loadDrawable(url, sizePx)?.let { url to it } }.toMap()
            }
            if (drawables.isEmpty()) return@launch
            withContext(Dispatchers.Main.immediate) {
                if (!row.isAttachedToWindow) return@withContext
                textView.text = formatText(
                    timestampLabel = timestampLabel,
                    displayName = displayName,
                    fallbackName = fallbackName,
                    color = color,
                    text = text,
                    emotes = emotes,
                    emoteDrawables = drawables,
                    emoteSizePx = sizePx,
                    settings = settings,
                )
            }
        }
    }

    private fun loadDrawable(url: String, sizePx: Int): Drawable? = runCatching {
        val connection = URL(url).openConnection().apply {
            connectTimeout = 3_000
            readTimeout = 5_000
        }
        connection.getInputStream().use { stream ->
            Drawable.createFromStream(stream, null)?.apply { setBounds(0, 0, sizePx, sizePx) }
        }
    }.getOrNull()

    private fun cleanMessagePreservingOffsets(value: String): String {
        if (value.isEmpty()) return value
        return buildString(value.length) {
            value.forEach { ch -> append(if (ch == '\r' || ch == '\n' || ch == '\t' || ch.isISOControl()) ' ' else ch) }
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