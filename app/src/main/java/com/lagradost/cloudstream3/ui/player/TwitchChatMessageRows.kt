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
                emoteSizePx = dp(textView, (settings.clampedFontSizeSp * if (isTv) 2.25f else 2.05f).toInt().coerceIn(18, 44)),
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

    private fun loadDrawable(url: String, sizePx: Int): Drawable? = runCatching {
        val connection = URL(url).openConnection().apply {
            connectTimeout = 3_000
            readTimeout = 5_000
        }
        connection.getInputStream().use { stream ->
            Drawable.createFromStream(stream, null)?.apply {
                setBounds(0, 0, sizePx, sizePx)
            }
        }
    }.getOrNull()

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
