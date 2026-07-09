package com.lagradost.cloudstream3.ui.player

import android.content.pm.PackageManager
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.preference.PreferenceManager
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
        const val CORNER_KEY = "twitch_live_chat_overlay_corner"
    }

    private enum class ChatCorner(val storeValue: String, val label: String) {
        BottomRight("bottom_right", "Bottom right"),
        TopRight("top_right", "Top right"),
        TopLeft("top_left", "Top left"),
        BottomLeft("bottom_left", "Bottom left"),
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
        overlayView()?.let { applyOverlayCorner(it) }
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
        button.setOnLongClickListener {
            val channel = currentChannelLogin() ?: return@setOnLongClickListener false
            cycleOverlayCorner()
            showOverlay(channel)
            true
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
        statusText = if (latestMessages.isEmpty()) "Connecting live chat..." else "Live chat"
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
                    statusText = "Live chat"
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
            configureOverlayBase(overlay)
            applyOverlayCorner(overlay)
            return overlay
        }

        val parent = root as? ViewGroup ?: return null
        val overlay = LinearLayout(root.context).apply {
            tag = OVERLAY_TAG
            visibility = View.GONE
        }
        configureOverlayBase(overlay)

        val width = if (isTvDevice()) dp(520) else dp(360)
        val params: ViewGroup.LayoutParams = when (parent) {
            is FrameLayout -> FrameLayout.LayoutParams(
                width,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            is androidx.constraintlayout.widget.ConstraintLayout ->
                androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
                    width,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            else -> ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        parent.addView(overlay, params)
        applyOverlayCorner(overlay)
        return overlay
    }

    private fun configureOverlayBase(overlay: LinearLayout) {
        overlay.orientation = LinearLayout.VERTICAL
        overlay.gravity = Gravity.START
        overlay.setBackgroundColor(Color.argb(220, 0, 0, 0))
        overlay.setPadding(dp(14), dp(10), dp(14), dp(10))
        overlay.isFocusable = false
        overlay.isClickable = false
        overlay.clipToPadding = false
        overlay.clipChildren = false
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

        val channel = activeChannel ?: currentChannelLogin()
        messages.forEach { message ->
            overlay.addView(
                TwitchChatMessageRows.create(
                    container = overlay,
                    scope = scope,
                    channelLogin = channel,
                    timestampLabel = message.timestampLabel,
                    displayName = message.displayName,
                    fallbackName = channel.orEmpty(),
                    color = message.color,
                    text = message.text,
                    badges = message.badges,
                    isTv = isTvDevice(),
                ),
            )
        }
    }

    private fun statusTextView(text: String): TextView {
        return TextView(root.context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = if (isTvDevice()) 14f else 13f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
        }
    }

    private fun selectedCorner(): ChatCorner {
        val stored = PreferenceManager.getDefaultSharedPreferences(root.context)
            .getString(CORNER_KEY, ChatCorner.BottomRight.storeValue)
        return ChatCorner.values().firstOrNull { it.storeValue == stored } ?: ChatCorner.BottomRight
    }

    private fun cycleOverlayCorner() {
        val values = ChatCorner.values()
        val current = selectedCorner()
        val next = values[(values.indexOf(current) + 1) % values.size]
        PreferenceManager.getDefaultSharedPreferences(root.context)
            .edit()
            .putString(CORNER_KEY, next.storeValue)
            .apply()
        overlayView()?.let { overlay ->
            applyOverlayCorner(overlay)
            overlay.bringToFront()
        }
        statusText = "Chat moved to ${next.label}"
        if (latestMessages.isEmpty()) render()
        Toast.makeText(root.context, statusText, Toast.LENGTH_SHORT).show()
    }

    private fun applyOverlayCorner(overlay: LinearLayout) {
        val corner = selectedCorner()
        val width = if (isTvDevice()) dp(520) else dp(360)
        val sideMargin = dp(if (isTvDevice()) 28 else 18)
        val verticalMargin = dp(if (isTvDevice()) 104 else 86)
        val topMargin = dp(if (isTvDevice()) 44 else 28)

        when (val params = overlay.layoutParams) {
            is FrameLayout.LayoutParams -> {
                params.width = width
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                params.gravity = when (corner) {
                    ChatCorner.BottomRight -> Gravity.END or Gravity.BOTTOM
                    ChatCorner.TopRight -> Gravity.END or Gravity.TOP
                    ChatCorner.TopLeft -> Gravity.START or Gravity.TOP
                    ChatCorner.BottomLeft -> Gravity.START or Gravity.BOTTOM
                }
                params.marginStart = sideMargin
                params.marginEnd = sideMargin
                params.topMargin = if (corner == ChatCorner.TopLeft || corner == ChatCorner.TopRight) topMargin else 0
                params.bottomMargin = if (corner == ChatCorner.BottomLeft || corner == ChatCorner.BottomRight) verticalMargin else 0
                overlay.layoutParams = params
            }
            is androidx.constraintlayout.widget.ConstraintLayout.LayoutParams -> {
                params.width = width
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                params.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                params.startToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                params.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                params.endToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                params.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                params.topToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                params.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                params.bottomToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                if (corner == ChatCorner.TopLeft || corner == ChatCorner.BottomLeft) {
                    params.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                    params.marginStart = sideMargin
                } else {
                    params.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                    params.marginEnd = sideMargin
                }
                if (corner == ChatCorner.TopLeft || corner == ChatCorner.TopRight) {
                    params.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                    params.topMargin = topMargin
                    params.bottomMargin = 0
                } else {
                    params.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                    params.bottomMargin = verticalMargin
                    params.topMargin = 0
                }
                overlay.layoutParams = params
            }
            else -> {
                params?.width = width
                overlay.layoutParams = params
            }
        }
    }

    private fun maxVisibleMessages(): Int = if (isTvDevice()) 8 else 12

    private fun isTvDevice(): Boolean {
        return root.context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    private fun dp(value: Int): Int {
        return (value * root.context.resources.displayMetrics.density + 0.5f).toInt()
    }
}