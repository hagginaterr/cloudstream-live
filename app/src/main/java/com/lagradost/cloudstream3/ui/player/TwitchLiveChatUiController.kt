package com.lagradost.cloudstream3.ui.player

import android.app.Dialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
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
        const val SLOW_RENDER_INTERVAL_MS = 2_000L
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
    private var slowRenderJob: Job? = null
    private var statusText: String = "Live chat"
    private var latestMessages: List<TwitchLiveChat.LiveMessage> = emptyList()
    private var lastVodFetchPositionMs: Long = Long.MIN_VALUE
    private var lastRenderedAtMs: Long = 0L
    private var settings: TwitchChatSettingsState = TwitchChatSettings.load(root.context)

    fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        updateButtonVisibility()
    }

    fun sync() {
        settings = TwitchChatSettings.load(root.context)
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
        } else {
            overlayView()?.let { overlay ->
                styleOverlay(overlay)
                applyOverlayPosition(overlay)
            }
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
                showSettingsMenu()
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
        settings = TwitchChatSettings.load(root.context)
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
                    if (latestMessages.isEmpty()) renderRespectingSlowMode(force = true)
                }

                override fun onMessages(messages: List<TwitchLiveChat.LiveMessage>) {
                    latestMessages = messages.takeLast(maxVisibleMessages())
                    statusText = "Live chat"
                    renderRespectingSlowMode(force = false)
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
        vodChatJob = scope.launch {
            while (activeTarget == target) {
                val positionMs = player.getPosition()?.coerceAtLeast(0L) ?: 0L
                val shouldFetch = lastVodFetchPositionMs == Long.MIN_VALUE ||
                    abs(positionMs - lastVodFetchPositionMs) >= VOD_REFETCH_DISTANCE_MS
                if (shouldFetch) {
                    lastVodFetchPositionMs = positionMs
                    val max = maxVisibleMessages()
                    val messages = TwitchVodChat.fetchAt(vodId, positionMs, max, target.channel)
                    if (activeTarget == target) {
                        latestMessages = messages.takeLast(max)
                        statusText = if (latestMessages.isEmpty()) {
                            "No VOD chat near ${formatVodPosition(positionMs)}"
                        } else {
                            "VOD chat"
                        }
                        renderRespectingSlowMode(force = true)
                    }
                }
                delay(if (settings.slowMode) VOD_POLL_INTERVAL_MS * 2 else VOD_POLL_INTERVAL_MS)
            }
        }
    }

    private fun releaseConnection() {
        liveChat?.stop()
        liveChat = null
        vodChatJob?.cancel()
        vodChatJob = null
        slowRenderJob?.cancel()
        slowRenderJob = null
        activeTarget = null
        latestMessages = emptyList()
        lastVodFetchPositionMs = Long.MIN_VALUE
        lastRenderedAtMs = 0L
    }

    private fun chatButton(): View? = root.findViewById(R.id.twitch_player_chat_button)

    private fun overlayView(): LinearLayout? {
        val tagged = (root as? ViewGroup)?.findViewWithTag<View>(OVERLAY_TAG)
        if (tagged != null) return tagged as? LinearLayout
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
        overlay.setBackgroundColor(Color.argb(settings.backgroundAlpha, 0, 0, 0))
        overlay.setPadding(dp(12), dp(8), dp(12), dp(8))
        overlay.clipToPadding = false
        overlay.clipChildren = false
        overlay.isFocusable = false
        overlay.isClickable = false
    }

    private fun initialOverlayLayoutParams(parent: ViewGroup): ViewGroup.LayoutParams {
        val width = overlayWidth()
        val height = overlayHeight()
        return when (parent) {
            is FrameLayout -> FrameLayout.LayoutParams(
                width,
                height,
                Gravity.END or Gravity.BOTTOM,
            )
            is ConstraintLayout -> ConstraintLayout.LayoutParams(width, height)
            else -> ViewGroup.LayoutParams(width, height)
        }
    }

    private fun removeOverlay() {
        val parent = root as? ViewGroup ?: return
        overlayView()?.let { parent.removeView(it) }
    }

    private fun renderRespectingSlowMode(force: Boolean) {
        if (!settings.slowMode || force) {
            lastRenderedAtMs = System.currentTimeMillis()
            render()
            return
        }
        val now = System.currentTimeMillis()
        val remaining = SLOW_RENDER_INTERVAL_MS - (now - lastRenderedAtMs)
        if (remaining <= 0L) {
            lastRenderedAtMs = now
            render()
            return
        }
        if (slowRenderJob?.isActive == true) return
        slowRenderJob = scope.launch {
            delay(remaining)
            lastRenderedAtMs = System.currentTimeMillis()
            render()
        }
    }

    private fun render() {
        val overlay = ensureOverlay() ?: return
        overlay.removeAllViews()
        overlay.addView(headerView())

        val messages = latestMessages.takeLast(maxVisibleMessages())
        if (messages.isEmpty()) {
            overlay.addView(statusTextView(statusText))
            return
        }

        val channelLogin = activeTarget?.channel
        val isTv = isTvDevice()
        messages.forEach { message ->
            overlay.addView(
                TwitchChatMessageRows.create(
                    container = overlay,
                    scope = scope,
                    channelLogin = channelLogin,
                    timestampLabel = message.timestampLabel,
                    displayName = message.displayName,
                    fallbackName = "Twitch",
                    color = message.color,
                    text = message.text,
                    badges = message.badges,
                    isTv = isTv,
                    emotes = message.emotes,
                    settings = settings,
                )
            )
        }
    }

    private fun headerView(): View {
        val ctx = root.context
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(6) }

            addView(TextView(ctx).apply {
                text = if (settings.slowMode) "$statusText - Slow" else statusText
                setTextColor(0xFFEDEDED.toInt())
                textSize = (settings.clampedFontSizeSp - 1).coerceAtLeast(9).toFloat()
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })

            addView(headerButton("Move") { cycleChatCorner() })
            addView(headerButton("Settings") { showSettingsMenu() })
        }
    }

    private fun headerButton(label: String, onClick: () -> Unit): Button {
        return Button(root.context).apply {
            text = label
            textSize = 10f
            minHeight = 0
            minWidth = 0
            setPadding(dp(8), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(30),
            ).apply { marginStart = dp(6) }
            setOnClickListener { onClick() }
        }
    }

    private fun statusTextView(text: String): TextView {
        return TextView(root.context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = settings.clampedFontSizeSp.toFloat()
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }
    }

    private fun maxVisibleMessages(): Int = settings.maxVisibleMessages(isTvDevice())

    private fun isTvDevice(): Boolean {
        return root.context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    private fun overlayWidth(): Int = dp(settings.clampedWidthDp)

    private fun overlayHeight(): Int = dp(settings.clampedHeightDp)

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
        val height = overlayHeight()

        when (val params = overlay.layoutParams) {
            is FrameLayout.LayoutParams -> {
                params.width = width
                params.height = height
                params.gravity = (if (alignStart) Gravity.START else Gravity.END) or
                    (if (alignTop) Gravity.TOP else Gravity.BOTTOM)
                params.marginStart = if (alignStart) sideMargin else 0
                params.marginEnd = if (alignStart) 0 else sideMargin
                params.topMargin = if (alignTop) topMargin else 0
                params.bottomMargin = if (alignTop) 0 else bottomMargin
                overlay.layoutParams = params
            }
            is ConstraintLayout.LayoutParams -> {
                params.width = width
                params.height = height
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
                overlay.layoutParams = ViewGroup.LayoutParams(width, height)
            }
        }
    }

    private fun showSettingsMenu() {
        settings = TwitchChatSettings.load(root.context)
        val ctx = root.context
        val dialog = Dialog(ctx)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }

        content.addView(TextView(ctx).apply {
            text = "Twitch chat settings"
            setTextColor(Color.WHITE)
            textSize = 18f
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
        })

        val widthSeek = addSeekRow(content, "Box width", 220, 760, settings.clampedWidthDp) { "$it dp" }
        val heightSeek = addSeekRow(content, "Box height", 120, 620, settings.clampedHeightDp) { "$it dp" }
        val transparencySeek = addSeekRow(content, "Transparency", 0, 95, settings.clampedTransparencyPercent) { "$it%" }
        val fontSeek = addSeekRow(content, "Font size", 8, 24, settings.clampedFontSizeSp) { "$it sp" }

        val twitchCheck = addCheckBox(content, "Native Twitch emotes", settings.twitchEmotesEnabled)
        val bttvCheck = addCheckBox(content, "BTTV emotes", settings.bttvEmotesEnabled)
        val ffzCheck = addCheckBox(content, "FFZ emotes", settings.ffzEmotesEnabled)
        val sevenTvCheck = addCheckBox(content, "7TV emotes", settings.sevenTvEmotesEnabled)
        val coloredNamesCheck = addCheckBox(content, "Colored usernames", settings.coloredUsernames)
        val badgesCheck = addCheckBox(content, "Badges", settings.badgesEnabled)
        val slowModeCheck = addCheckBox(content, "Slow mode", settings.slowMode)

        content.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(12), 0, 0)

            addView(Button(ctx).apply {
                text = "Defaults"
                setOnClickListener {
                    applySettings(TwitchChatSettings.reset(ctx))
                    dialog.dismiss()
                }
            })
            addView(Button(ctx).apply {
                text = "Cancel"
                setOnClickListener { dialog.dismiss() }
            })
            addView(Button(ctx).apply {
                text = "Save"
                setOnClickListener {
                    val newSettings = TwitchChatSettingsState(
                        widthDp = widthSeek.progress + 220,
                        heightDp = heightSeek.progress + 120,
                        transparencyPercent = transparencySeek.progress,
                        twitchEmotesEnabled = twitchCheck.isChecked,
                        bttvEmotesEnabled = bttvCheck.isChecked,
                        ffzEmotesEnabled = ffzCheck.isChecked,
                        sevenTvEmotesEnabled = sevenTvCheck.isChecked,
                        coloredUsernames = coloredNamesCheck.isChecked,
                        fontSizeSp = fontSeek.progress + 8,
                        badgesEnabled = badgesCheck.isChecked,
                        slowMode = slowModeCheck.isChecked,
                    )
                    TwitchChatSettings.save(ctx, newSettings)
                    applySettings(newSettings)
                    dialog.dismiss()
                }
            })
        })

        val scroll = ScrollView(ctx).apply {
            setBackgroundColor(Color.argb(235, 16, 16, 16))
            addView(content)
        }
        dialog.setContentView(scroll)
        dialog.show()
        dialog.window?.setLayout(if (isTvDevice()) dp(520) else dp(380), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun addSeekRow(
        parent: LinearLayout,
        title: String,
        min: Int,
        max: Int,
        initial: Int,
        formatter: (Int) -> String,
    ): SeekBar {
        val ctx = parent.context
        val label = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            includeFontPadding = false
        }
        val seek = SeekBar(ctx).apply {
            this.max = max - min
            progress = initial.coerceIn(min, max) - min
        }
        fun updateLabel(value: Int) {
            label.text = "$title: ${formatter(value)}"
        }
        updateLabel(seek.progress + min)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateLabel(progress + min)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        parent.addView(label)
        parent.addView(seek, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(8) })
        return seek
    }

    private fun addCheckBox(parent: LinearLayout, label: String, checked: Boolean): CheckBox {
        return CheckBox(parent.context).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 14f
            isChecked = checked
            parent.addView(this)
        }
    }

    private fun applySettings(newSettings: TwitchChatSettingsState) {
        settings = newSettings
        val wasVisible = overlayView()?.isVisible == true
        val target = activeTarget ?: currentTarget()
        if (wasVisible && target != null) {
            releaseConnection()
            showOverlay(target)
        } else {
            overlayView()?.let { overlay ->
                styleOverlay(overlay)
                applyOverlayPosition(overlay)
                render()
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