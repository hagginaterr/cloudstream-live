package com.lagradost.cloudstream3.ui.player

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
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
    ): View {
        val ctx = container.context
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

        val badgeViews = badges
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
            this.text = formatText(timestampLabel, displayName, fallbackName, color, text)
            setTextColor(Color.WHITE)
            textSize = if (isTv) 12.0f else 11.5f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setLineSpacing(0f, 1.04f)
            setShadowLayer(2f, 1f, 1f, Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        if (badgeViews.isNotEmpty()) row.addView(badgeStrip)
        row.addView(textView)

        if (badgeViews.isNotEmpty() && !channelLogin.isNullOrBlank()) {
            loadOfficialBadges(scope, channelLogin, row, badgeViews)
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
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = margin
            }
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
    ): CharSequence {
        val cleanName = clean(displayName).ifBlank { clean(fallbackName).ifBlank { "Twitch" } }
        val cleanText = clean(text)
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
            ForegroundColorSpan(color ?: Color.rgb(153, 214, 255)),
            nameStart,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        builder.setSpan(StyleSpan(Typeface.BOLD), nameStart, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.append(": ")
        builder.append(cleanText)
        return builder
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