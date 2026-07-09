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
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import recloudstream.twitchlivefavorites.TwitchLiveChat
import recloudstream.twitchlivefavorites.TwitchVodChat
import kotlin.math.abs

internal class TwitchLiveChatUiController(
    private val scope: CoroutineScope,
    private val root: View,
    private val player: IPlayer,
) {
    private companion object {
        const val OVERLAY_TAG = "cloudstream_twitch_live_chat_overlay"
        const val CORNER_PREF_KEY = "twitch_chat_overlay_corner_v1"
        const val VOD_POLL_INTERVAL_MS = 2_500L
        const val VOD_REFETCH_DISTANCE_MS = 7_000L
    }

    private enum class ChatMode { LIVE, VOD }

    private data class ChatTarget(
        val mode: ChatMode,
        val channel: String?,
        val vodId: String?,
    )

    private var controlsVisible = true
    private var activeTarget: ChatTarget? = null
    private var liveChat: TwitchLiveChat? = null
    private var vodChatJob: Job? = null
    private var statusText: String = "Live chat"
    private var latestMessages: List<TwitchLiveChat.LiveMessage> = emptyList()
    private var lastVodFetchPositionMs: Long = Long.MIN_VALUE

    fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        updateButtonVisibility()
    }

    fun sync() {
        updateButtonVisibility()
        val target = currentTarget()
        if (target == null) {
            if (activeTarget != null) {
                releaseConnection()
                hideOverlay()
            }
            return
        }

        if (overlayView()?.isVisible == true && activeTarget != null && activeTarget != target) {
            showOverlay(target)
        }
    }

    fun release() {
        releaseConnection()
        removeOverlay()
    }

    private fun updateButtonVisibility() {
        val button = chatButton() ?: return
        val target = currentTarget()
        val available = target != null
        button.isVisible = controlsVisible && available
        button.isEnabled = available
        button.isFocusable = available
        button.setOnClickListener {
            val freshTarget = currentTarget() ?: return@setOnClickListener
            if (overlayView()?.isVisible == true) {
                hideOverlay()
                releaseConnection()
            } else {
                showOverlay(freshTarget)
            }
        }
        button.setOnLongClickListener {
            if (available) {
                cycleChatCorner()
                true
            } else {
                false
            }
        }
    }

    private fun currentTarget(): ChatTarget? {
        val channel = TwitchLiveChat.normalizeChannelLogin(player.getTwitchChatChannelLogin())
        if (player.isTwitchLiveStream() && channel != null) {
            return ChatTarget(ChatMode.LIVE, channel, null)
        }

        val vodId = player.getTwitchVodId()?.filter { it.isDigit() }?.ifBlank { null }
        if (!player.isTwitchLiveStream() && vodId != null) {
            return ChatTarget(ChatMode.VOD, channel, vodId)
        }

        return null
    }

    private fun showOverlay(target: ChatTarget) {
        val overlay = ensureOverlay() ?: return
        overlay.isVisible = true
        overlay.bringToFront()
        latestMessages = emptyList()
        statusText = when (target.mode) {
            ChatMode.LIVE -> "Connecting live chat..."
            ChatMode.VOD -> "Loading VOD chat..."
        }
        render()
        when (target.mode) {
            ChatMode.LIVE -> ensureLiveConnection(target)
            ChatMode.VOD -> ensureVodHistory(target)
        }
    }

    private fun hideOverlay() {
        overlayView()?.isVisible = false
    }

    private fun ensureLiveConnection(target: ChatTarget) {
        val channel = target.channel ?: return
        if (activeTarget == target && liveChat != null) return
        releaseConnection()
        activeTarget = target
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
        ).also { chat -> chat.start(channel) }
    }

    private fun ensureVodHistory(target: ChatTarget) {
        val vodId = target.vodId ?: return
        if (activeTarget == target && vodChatJob != null) return
        releaseConnection()
        activeTarget = target
        lastVodFetchPositionMs = Long.MIN_VALUE
        statusText = "Loading VOD chat..."
        render()
        val max = maxVisibleMessages()
        vodChatJob = scope.launch {
            while (activeTarget == target) {
                val positionMs = player.getPosition()?.coerceAtLeast(0L) ?: 0L
                val shouldFetch = lastVodFetchPositionMs == Long.MIN_VALUE ||
                    abs(positionMs - lastVodFetchPositionMs) >= VOD_REFETCH_DISTANCE_MS
                if (shouldFetch) {
                    lastVodFetchPositionMs = positionMs
                    val messages = TwitchVodChat.fetchAt(vodId, positionMs, max)
                    if (activeTarget == target) {
                        latestMessages = messages.takeLast(max)
                        statusText = if (latestMessages.isEmpty()) {
                            "No VOD chat near ${formatVodPosition(positionMs)}"
                        } else {
                            "VOD chat"
                        }
                        render()
                    }
                }
                delay(VOD_POLL_INTERVAL_MS)
            }
        }
    }

    private fun releaseConnection() {
        liveChat?.stop()
        liveChat = null
        vodChatJob?.cancel()
        vodChatJob = null
        activeTarget = null
        latestMessages = emptyList()
        lastVodFetchPositionMs = Long.MIN_VALUE
    }

    private fun chatButton(): View? = root.findViewById(R.id.twitch_player_chat_button)

    private fun overlayView(): LinearLayout? {
        val tagged = (root as? ViewGroup)?.findViewWithTag<LinearLayout>(OVERLAY_TAG)
        if (tagged != null) return tagged
        return root.findViewById(R.id.twitch_player_chat_overlay) as? LinearLayout
    }

    private fun ensureOverlay(): LinearLayout? {
        overlayView()?.let { overlay ->
            styleOverlay(overlay)
            applyOverlayPosition(overlay)
            return overlay
        }

        val parent = root as? ViewGroup ?: return null
        val overlay = LinearLayout(root.context).apply {
            tag = OVERLAY_TAG
            visibility = View.GONE
        }
        styleOverlay(overlay)
        parent.addView(overlay, initialOverlayLayoutParams(parent))
        applyOverlayPosition(overlay)
        return overlay
    }

    private fun styleOverlay(overlay: LinearLayout) {
        overlay.orientation = LinearLayout.VERTICAL
        overlay.gravity = Gravity.START
        overlay.setBackgroundColor(Color.argb(210, 0, 0, 0))
        overlay.setPadding(dp(14), dp(10), dp(14), dp(10))
        overlay.isFocusable = false
        overlay.isClickable = false
    }

    private fun initialOverlayLayoutParams(parent: ViewGroup): ViewGroup.LayoutParams {
        val width = overlayWidth()
        return when (parent) {
            is FrameLayout -> FrameLayout.LayoutParams(
                width,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.BOTTOM,
            )

            is ConstraintLayout -> ConstraintLayout.LayoutParams(
                width,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )

            else -> ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
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
        messages.forEach { message -> overlay.addView(messageTextView(message)) }
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

    private fun overlayWidth(): Int = if (isTvDevice()) dp(520) else dp(360)

    private fun overlayBottomMargin(): Int = if (isTvDevice()) dp(104) else dp(86)

    private fun cornerIndex(): Int {
        return PreferenceManager.getDefaultSharedPreferences(root.context)
            .getInt(CORNER_PREF_KEY, 0)
            .coerceIn(0, 3)
    }

    private fun saveCornerIndex(index: Int) {
        PreferenceManager.getDefaultSharedPreferences(root.context)
            .edit()
            .putInt(CORNER_PREF_KEY, index.coerceIn(0, 3))
            .apply()
    }

    private fun cycleChatCorner() {
        saveCornerIndex((cornerIndex() + 1) % 4)
        overlayView()?.let { overlay ->
            applyOverlayPosition(overlay)
            overlay.bringToFront()
        }
    }

    private fun applyOverlayPosition(overlay: LinearLayout) {
        val corner = cornerIndex()
        val alignTop = corner == 1 || corner == 2
        val alignStart = corner == 2 || corner == 3
        val sideMargin = dp(28)
        val topMargin = dp(48)
        val bottomMargin = overlayBottomMargin()
        val width = overlayWidth()

        when (val params = overlay.layoutParams) {
            is FrameLayout.LayoutParams -> {
                params.width = width
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                params.gravity =
                    (if (alignStart) Gravity.START else Gravity.END) or
                        (if (alignTop) Gravity.TOP else Gravity.BOTTOM)
                params.marginStart = if (alignStart) sideMargin else 0
                params.marginEnd = if (alignStart) 0 else sideMargin
                params.topMargin = if (alignTop) topMargin else 0
                params.bottomMargin = if (alignTop) 0 else bottomMargin
                overlay.layoutParams = params
            }

            is ConstraintLayout.LayoutParams -> {
                params.width = width
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                params.startToStart = ConstraintLayout.LayoutParams.UNSET
                params.startToEnd = ConstraintLayout.LayoutParams.UNSET
                params.endToStart = ConstraintLayout.LayoutParams.UNSET
                params.endToEnd = ConstraintLayout.LayoutParams.UNSET
                params.topToTop = ConstraintLayout.LayoutParams.UNSET
                params.topToBottom = ConstraintLayout.LayoutParams.UNSET
                params.bottomToTop = ConstraintLayout.LayoutParams.UNSET
                params.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
                if (alignStart) {
                    params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                    params.marginStart = sideMargin
                    params.marginEnd = 0
                } else {
                    params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                    params.marginEnd = sideMargin
                    params.marginStart = 0
                }
                if (alignTop) {
                    params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                    params.topMargin = topMargin
                    params.bottomMargin = 0
                } else {
                    params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                    params.bottomMargin = bottomMargin
                    params.topMargin = 0
                }
                overlay.layoutParams = params
            }

            else -> {
                overlay.layoutParams = ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }
    }

    private fun formatVodPosition(positionMs: Long): String {
        val totalSeconds = (positionMs.coerceAtLeast(0L) / 1000L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    private fun dp(value: Int): Int {
        return (value * root.context.resources.displayMetrics.density + 0.5f).toInt()
    }
}