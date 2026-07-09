package com.lagradost.cloudstream3.ui.player

import android.content.pm.PackageManager
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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.lagradost.cloudstream3.R
import kotlinx.coroutines.CoroutineScope
import recloudstream.twitchlivefavorites.TwitchLiveChat

internal class TwitchLiveChatUiController(
    private val scope: CoroutineScope,
    private val root: View,
    private val player: IPlayer,
) {
    private companion object {
        const val OVERLAY_TAG = "cloudstream_twitch_live_chat_overlay"
    }

    private var controlsVisible = true
    private var activeChannel: String? = null
    private var liveChat: TwitchLiveChat? = null
    private var statusText: String = "Live chat"
    private var latestMessages: List<TwitchLiveChat.LiveMessage> = emptyList()

    fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        updateButtonVisibility()
    }

    fun sync() {
        updateButtonVisibility()
        val channel = currentChannelLogin()
        if (channel == null && liveChat != null) {
            releaseConnection()
            hideOverlay()
        }
    }

    fun release() {
        releaseConnection()
        removeOverlay()
    }

    private fun updateButtonVisibility() {
        val button = chatButton() ?: return
        val available = currentChannelLogin() != null
        button.isVisible = controlsVisible && available
        button.isEnabled = available
        button.isFocusable = available
        button.setOnClickListener {
            val channel = currentChannelLogin() ?: return@setOnClickListener
            if (overlayView()?.isVisible == true) {
                hideOverlay()
                releaseConnection()
            } else {
                showOverlay(channel)
            }
        }
    }

    private fun currentChannelLogin(): String? {
        if (!player.isTwitchLiveStream()) return null
        return TwitchLiveChat.normalizeChannelLogin(player.getTwitchChatChannelLogin())
    }

    private fun showOverlay(channel: String) {
        val overlay = ensureOverlay() ?: return
        overlay.isVisible = true
        overlay.bringToFront()
        statusText = "Connecting live chat..."
        latestMessages = emptyList()
        render()
        ensureConnection(channel)
    }

    private fun hideOverlay() {
        overlayView()?.isVisible = false
    }

    private fun ensureConnection(channel: String) {
        if (activeChannel == channel && liveChat != null) return
        releaseConnection()
        activeChannel = channel
        val max = maxVisibleMessages()
        liveChat = TwitchLiveChat(
            scope = scope,
            maxMessages = max,
            listener = object : TwitchLiveChat.Listener {
                override fun onState(message: String) {
                    statusText = message
                    if (latestMessages.isEmpty()) render()
                }

                override fun onMessages(messages: List<TwitchLiveChat.LiveMessage>) {
                    latestMessages = messages.takeLast(max)
                    render()
                }
            },
        ).also { chat ->
            chat.start(channel)
        }
    }

    private fun releaseConnection() {
        liveChat?.stop()
        liveChat = null
        activeChannel = null
        latestMessages = emptyList()
    }

    private fun chatButton(): View? = root.findViewById(R.id.twitch_player_chat_button)

    private fun overlayView(): LinearLayout? {
        val tagged = (root as? ViewGroup)?.findViewWithTag<View>(OVERLAY_TAG) as? LinearLayout
        if (tagged != null) return tagged
        return root.findViewById(R.id.twitch_player_chat_overlay) as? LinearLayout
    }

    private fun ensureOverlay(): LinearLayout? {
        overlayView()?.let { overlay ->
            overlay.orientation = LinearLayout.VERTICAL
            overlay.gravity = Gravity.START
            overlay.setBackgroundColor(Color.argb(210, 0, 0, 0))
            overlay.setPadding(dp(14), dp(10), dp(14), dp(10))
            overlay.isFocusable = false
            overlay.isClickable = false
            return overlay
        }
        val parent = root as? ViewGroup ?: return null
        val overlay = LinearLayout(root.context).apply {
            tag = OVERLAY_TAG
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setBackgroundColor(Color.argb(210, 0, 0, 0))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            isFocusable = false
            isClickable = false
            visibility = View.GONE
        }
        val width = if (isTvDevice()) dp(520) else dp(360)
        val bottomMarginPx = if (isTvDevice()) dp(104) else dp(86)
        val params: ViewGroup.LayoutParams = when (parent) {
            is FrameLayout -> FrameLayout.LayoutParams(
                width,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.BOTTOM,
            ).apply {
                marginEnd = dp(28)
                bottomMargin = bottomMarginPx
            }
            is androidx.constraintlayout.widget.ConstraintLayout ->
                androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
                    width,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                    bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                    marginEnd = dp(28)
                    bottomMargin = bottomMarginPx
                }
            else -> ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        parent.addView(overlay, params)
        return overlay
    }

    private fun removeOverlay() {
        val parent = root as? ViewGroup ?: return
        overlayView()?.let { parent.removeView(it) }
    }

    private fun render() {
        val overlay = ensureOverlay() ?: return
        overlay.removeAllViews()
        val messages = latestMessages.takeLast(maxVisibleMessages())
        if (messages.isEmpty()) {
            overlay.addView(statusTextView(statusText))
            return
        }
        messages.forEach { message ->
            overlay.addView(messageTextView(message))
        }
    }

    private fun statusTextView(text: String): TextView {
        return TextView(root.context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = if (isTvDevice()) 14f else 13f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }
    }

    private fun messageTextView(message: TwitchLiveChat.LiveMessage): TextView {
        return TextView(root.context).apply {
            text = formatMessage(message)
            setTextColor(Color.WHITE)
            textSize = if (isTvDevice()) 12.5f else 12f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setLineSpacing(0f, 1.05f)
            setShadowLayer(2f, 1f, 1f, Color.BLACK)
        }
    }

    private fun formatMessage(message: TwitchLiveChat.LiveMessage): CharSequence {
        val builder = SpannableStringBuilder()
        if (message.timestampLabel.isNotBlank()) {
            builder.append(message.timestampLabel).append(' ')
        }
        if (message.badges.isNotEmpty()) {
            builder.append(message.badges.take(2).joinToString(" ") { "[$it]" }).append(' ')
        }
        val nameStart = builder.length
        builder.append(message.displayName.ifBlank { "Twitch" })
        val nameEnd = builder.length
        builder.setSpan(
            ForegroundColorSpan(message.color ?: Color.rgb(153, 214, 255)),
            nameStart,
            nameEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        builder.setSpan(StyleSpan(Typeface.BOLD), nameStart, nameEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.append(": ")
        builder.append(message.text)
        return builder
    }

    private fun maxVisibleMessages(): Int = if (isTvDevice()) 8 else 12

    private fun isTvDevice(): Boolean {
        return root.context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    private fun dp(value: Int): Int {
        return (value * root.context.resources.displayMetrics.density + 0.5f).toInt()
    }
}