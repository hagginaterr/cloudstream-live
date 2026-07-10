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
        const val CHAT_OPEN_PREF_KEY = "twitch_chat_overlay_open_v1"
        const val VOD_POLL_INTERVAL_MS = 2_500L
        const val TARGET_REFRESH_INTERVAL_MS = 1_000L
        const val VOD_REFETCH_DISTANCE_MS = 3_000L
        const val LIVE_DVR_CHAT_THRESHOLD_MS = 20_000L
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
    private var targetRefreshJob: Job? = null
    private var statusText: String = "Live chat"
    private var latestMessages: List<TwitchLiveChat.LiveMessage> = emptyList()
    private var liveReplaySeedMessages: List<TwitchLiveChat.LiveMessage> = emptyList()
    private var liveIrcMessages: List<TwitchLiveChat.LiveMessage> = emptyList()
    private var liveStartupHistoryJob: Job? = null
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
            }
            hideOverlay()
            return
        }

        if (!isChatOpenPreference()) {
            if (activeTarget != null) {
                releaseConnection()
            }
            hideOverlay()
            overlayView()?.let { overlay ->
                styleOverlay(overlay)
                applyOverlayPosition(overlay)
            }
            return
        }

        val overlay = overlayView()
        if (activeTarget != target || overlay?.isVisible != true) {
            showOverlay(target)
        } else {
            overlay.let {
                styleOverlay(it)
                applyOverlayPosition(it)
                it.bringToFront()
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
                saveChatOpenPreference(false)
                hideOverlay()
                releaseConnection()
            } else {
                saveChatOpenPreference(true)
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
        val vodId = player.getTwitchVodId()?.filter { it.isDigit() }?.ifBlank { null }
        val isLivePlayback = player.isTwitchLiveStream() || player.isTwitchLiveDvrStream()

        if (vodId != null) {
            return if (isLivePlayback && channel != null && isDefinitelyAtLiveEdge()) {
                ChatTarget(ChatMode.LIVE, channel, vodId)
            } else {
                ChatTarget(ChatMode.VOD, channel, vodId)
            }
        }

        if (isLivePlayback && channel != null) {
            return ChatTarget(ChatMode.LIVE, channel, null)
        }
        return null
    }

    private fun currentVodContentPositionMs(): Long {
        return player.getTwitchVodContentPositionMs()
            ?.coerceAtLeast(0L)
            ?: player.getPosition()?.coerceAtLeast(0L)
            ?: 0L
    }

    private fun currentVodContentPositionMs(): Long {
        return player.getTwitchVodContentPositionMs()
            ?.coerceAtLeast(0L)
            ?: player.getPosition()?.coerceAtLeast(0L)
            ?: 0L
    }

    private fun isDefinitelyAtLiveEdge(): Boolean {
        val liveDelayMs = player.getLiveDelayMs()?.coerceAtLeast(0L)
        if (liveDelayMs != null) {
            return liveDelayMs < LIVE_DVR_CHAT_THRESHOLD_MS
        }

        val durationMs = player.getDuration()?.takeIf { it > 0L && it < Long.MAX_VALUE / 4 } ?: return false
        val positionMs = player.getPosition()?.takeIf { it >= 0L } ?: return false
        val distanceFromEndMs = durationMs - positionMs
        return distanceFromEndMs in 0L until LIVE_DVR_CHAT_THRESHOLD_MS
    }
    private fun isChatOpenPreference(): Boolean {
        return PreferenceManager.getDefaultSharedPreferences(root.context)
            .getBoolean(CHAT_OPEN_PREF_KEY, false)
    }

    private fun saveChatOpenPreference(open: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(root.context)
            .edit()
            .putBoolean(CHAT_OPEN_PREF_KEY, open)
            .apply()
    }
    private fun showOverlay(target: ChatTarget) {
        saveChatOpenPreference(true)
        settings = TwitchChatSettings.load(root.context)
        val overlay = ensureOverlay() ?: return
        overlay.isVisible = true
        overlay.bringToFront()
        latestMessages = emptyList()
        liveReplaySeedMessages = emptyList()
        liveIrcMessages = emptyList()
        liveStartupHistoryJob?.cancel()
        liveStartupHistoryJob = null
        statusText = when (target.mode) {
            ChatMode.LIVE -> if (target.vodId != null) "Loading recent chat..." else "Connecting live chat..."
            ChatMode.VOD -> "Loading VOD chat..."
        }
        render()
        startTargetRefreshLoop()
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

        stopActiveChatSources()
        activeTarget = target
        statusText = if (target.vodId != null) "Loading recent chat..." else "Connecting live chat..."
        render()
        maybeSeedLiveStartupHistory(target)
        liveChat = TwitchLiveChat(
            scope = scope,
            maxMessages = chatBufferMaxMessages(),
            listener = object : TwitchLiveChat.Listener {
                override fun onState(message: String) {
                    statusText = message
                    if (latestMessages.isEmpty()) renderRespectingSlowMode(force = true)
                }

                override fun onMessages(messages: List<TwitchLiveChat.LiveMessage>) {
                    liveIrcMessages = messages.takeLast(chatBufferMaxMessages())
                    latestMessages = combinedLiveMessages()
                    statusText = "Live chat"
                    renderRespectingSlowMode(force = false)
                }
            },
        ).also { chat -> chat.start(channel) }
    }
    private fun ensureVodHistory(target: ChatTarget) {
        val vodId = target.vodId ?: return
        if (activeTarget == target && vodChatJob != null) return

        stopActiveChatSources()
        activeTarget = target
        lastVodFetchPositionMs = Long.MIN_VALUE
        statusText = "Loading VOD chat..."
        render()
        vodChatJob = scope.launch {
            while (activeTarget == target) {
                val positionMs = currentVodContentPositionMs()
                val shouldFetch = lastVodFetchPositionMs == Long.MIN_VALUE ||
                    abs(positionMs - lastVodFetchPositionMs) >= VOD_REFETCH_DISTANCE_MS
                if (shouldFetch) {
                    lastVodFetchPositionMs = positionMs
                    val max = maxBufferedMessages()
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
    private fun startTargetRefreshLoop() {
        if (targetRefreshJob?.isActive == true) return
        targetRefreshJob = scope.launch {
            while (isChatOpenPreference()) {
                delay(TARGET_REFRESH_INTERVAL_MS)
                if (overlayView()?.isVisible != true) continue

                val freshTarget = currentTarget()
                if (freshTarget == null) {
                    releaseConnection()
                    hideOverlay()
                    return@launch
                }
                if (activeTarget != freshTarget) {
                    showOverlay(freshTarget)
                }
            }
        }
    }
    private fun chatBufferMaxMessages(): Int {
        val visible = maxVisibleMessages().coerceAtLeast(1)
        val base = if (isTvDevice()) 220 else 180
        return (visible * 6).coerceIn(visible, base)
    }

    private fun liveStartupBackfillSize(): Int {
        return maxVisibleMessages().coerceIn(1, 60)
    }

    private fun combinedLiveMessages(): List<TwitchLiveChat.LiveMessage> {
        val max = chatBufferMaxMessages()
        if (liveReplaySeedMessages.isEmpty()) return liveIrcMessages.takeLast(max)
        if (liveIrcMessages.isEmpty()) return liveReplaySeedMessages.takeLast(liveStartupBackfillSize())

        val liveIds = liveIrcMessages.mapTo(mutableSetOf()) { it.id }
        val seed = liveReplaySeedMessages.filter { it.id !in liveIds }
        return (seed + liveIrcMessages).takeLast(max)
    }

    private fun maybeSeedLiveStartupHistory(target: ChatTarget) {
        val vodId = target.vodId ?: return
        liveStartupHistoryJob?.cancel()
        liveStartupHistoryJob = scope.launch {
            val seedTarget = target
            val positionMs = currentVodContentPositionMs()
            val requested = liveStartupBackfillSize()
            val messages = TwitchVodChat.fetchAt(vodId, positionMs, requested, target.channel)
            if (activeTarget != seedTarget || messages.isEmpty()) return@launch
            liveReplaySeedMessages = messages.takeLast(requested)
            latestMessages = combinedLiveMessages()
            if (latestMessages.isNotEmpty()) {
                statusText = "Live chat"
                renderRespectingSlowMode(force = true)
            }
        }
    }

    private fun stopActiveChatSources() {
        liveChat?.stop()
        liveChat = null
        vodChatJob?.cancel()
        vodChatJob = null
        slowRenderJob?.cancel()
        slowRenderJob = null
        liveStartupHistoryJob?.cancel()
        liveStartupHistoryJob = null
        latestMessages = emptyList()
        liveReplaySeedMessages = emptyList()
        liveIrcMessages = emptyList()
        lastVodFetchPositionMs = Long.MIN_VALUE
        lastRenderedAtMs = 0L
    }

    private fun releaseConnection() {
        val refreshJob = targetRefreshJob
        targetRefreshJob = null
        refreshJob?.cancel()
        stopActiveChatSources()
        activeTarget = null
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
        overlay.clipToPadding = true
        overlay.clipChildren = true
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
    }    private fun maxVisibleMessages(): Int = visibleMessageLimit(overlayView())

    private fun maxBufferedMessages(): Int = if (isTvDevice()) 220 else 180

    private fun visibleMessageLimit(overlay: LinearLayout?): Int {
        val isTv = isTvDevice()
        val layoutHeightPx = overlay?.layoutParams?.height ?: 0
        val measuredHeightPx = overlay?.height ?: 0
        val heightPx = when {
            layoutHeightPx > 0 -> layoutHeightPx
            measuredHeightPx > 0 -> measuredHeightPx
            else -> overlayHeight()
        }.coerceAtLeast(dp(96))
        val verticalPaddingPx = (overlay?.paddingTop ?: dp(8)) + (overlay?.paddingBottom ?: dp(8))
        val usableHeightPx = (heightPx - verticalPaddingPx).coerceAtLeast(dp(48))
        val scaledDensity = root.context.resources.displayMetrics.scaledDensity
        val fontPx = (settings.clampedFontSizeSp * scaledDensity).coerceAtLeast(dp(if (isTv) 12 else 10).toFloat())
        val lineHeightPx = fontPx * if (settings.clampedFontSizeSp >= 18) 1.08f else 1.12f
        val rowGapPx = dp(if (isTv) 4 else 3)
        val averageLinesPerRow = if (settings.badgesEnabled) 1.46f else 1.36f
        val rowHeightPx = (lineHeightPx * averageLinesPerRow + rowGapPx)
            .toInt()
            .coerceAtLeast(dp(if (isTv) 22 else 18))
        val visibleCount = (usableHeightPx / rowHeightPx).coerceAtLeast(1)
        return visibleCount.coerceIn(1, if (isTv) 30 else 36)
    }

    private fun isTvDevice(): Boolean {
        return root.context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    private fun overlayWidth(): Int = dp(settings.clampedWidthDp)

    private fun overlayHeight(): Int = dp(settings.clampedHeightDp)

    private fun overlayBottomMargin(): Int = 0

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
        val width = overlayWidth()
        val height = overlayHeight()

        when (val params = overlay.layoutParams) {
            is FrameLayout.LayoutParams -> {
                params.width = width
                params.height = height
                params.gravity = (if (alignStart) Gravity.START else Gravity.END) or
                    (if (alignTop) Gravity.TOP else Gravity.BOTTOM)
                params.marginStart = 0
                params.marginEnd = 0
                params.topMargin = 0
                params.bottomMargin = 0
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
                params.marginStart = 0
                params.marginEnd = 0
                params.topMargin = 0
                params.bottomMargin = 0

                if (alignStart) {
                    params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                } else {
                    params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                }

                if (alignTop) {
                    params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                } else {
                    params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
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
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        content.addView(TextView(ctx).apply {
            text = "Twitch chat settings"
            setTextColor(Color.WHITE)
            textSize = 14f
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        })

        var suppressLiveUpdates = false
        lateinit var widthSeek: SeekBar
        lateinit var heightSeek: SeekBar
        lateinit var transparencySeek: SeekBar
        lateinit var fontSeek: SeekBar
        lateinit var twitchCheck: CheckBox
        lateinit var bttvCheck: CheckBox
        lateinit var ffzCheck: CheckBox
        lateinit var sevenTvCheck: CheckBox
        lateinit var coloredNamesCheck: CheckBox
        lateinit var badgesCheck: CheckBox
        lateinit var slowModeCheck: CheckBox
        lateinit var positionGroup: android.widget.RadioGroup
        val positionIds = intArrayOf(
            View.generateViewId(),
            View.generateViewId(),
            View.generateViewId(),
            View.generateViewId(),
        )

        fun buildSettings(): TwitchChatSettingsState {
            return TwitchChatSettingsState(
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
        }

        fun saveVisualSettings() {
            if (suppressLiveUpdates) return
            val newSettings = buildSettings()
            TwitchChatSettings.save(ctx, newSettings)
            applySettings(newSettings)
        }

        fun updateCorner(index: Int) {
            if (suppressLiveUpdates) return
            saveCornerIndex(index.coerceIn(0, 3))
            overlayView()?.let { overlay ->
                styleOverlay(overlay)
                applyOverlayPosition(overlay)
                if (overlay.isVisible) render()
                overlay.bringToFront()
            }
        }

        widthSeek = addSeekRow(content, "Width", 220, 760, settings.clampedWidthDp, { "$it dp" }) {
            saveVisualSettings()
        }
        heightSeek = addSeekRow(content, "Height", 120, 620, settings.clampedHeightDp, { "$it dp" }) {
            saveVisualSettings()
        }
        transparencySeek = addSeekRow(content, "Transparency", 0, 95, settings.clampedTransparencyPercent, { "$it%" }) {
            saveVisualSettings()
        }
        fontSeek = addSeekRow(content, "Font", 8, 24, settings.clampedFontSizeSp, { "$it sp" }) {
            saveVisualSettings()
        }

        content.addView(TextView(ctx).apply {
            text = "Position"
            setTextColor(Color.WHITE)
            textSize = 12f
            includeFontPadding = false
        })

        positionGroup = android.widget.RadioGroup(ctx).apply {
            orientation = android.widget.RadioGroup.VERTICAL
            val labels = listOf("Bottom right", "Top right", "Top left", "Bottom left")
            labels.forEachIndexed { index, label ->
                addView(android.widget.RadioButton(ctx).apply {
                    id = positionIds[index]
                    text = label
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    minHeight = 0
                    setPadding(0, 0, 0, 0)
                })
            }
            check(positionIds[cornerIndex()])
            setOnCheckedChangeListener { _, checkedId ->
                val index = positionIds.indexOf(checkedId)
                if (index >= 0) updateCorner(index)
            }
        }
        content.addView(positionGroup, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(4) })

        twitchCheck = addCheckBox(content, "Native Twitch emotes", settings.twitchEmotesEnabled).apply {
            setOnCheckedChangeListener { _, _ -> saveVisualSettings() }
        }
        bttvCheck = addCheckBox(content, "BTTV emotes", settings.bttvEmotesEnabled).apply {
            setOnCheckedChangeListener { _, _ -> saveVisualSettings() }
        }
        ffzCheck = addCheckBox(content, "FFZ emotes", settings.ffzEmotesEnabled).apply {
            setOnCheckedChangeListener { _, _ -> saveVisualSettings() }
        }
        sevenTvCheck = addCheckBox(content, "7TV emotes", settings.sevenTvEmotesEnabled).apply {
            setOnCheckedChangeListener { _, _ -> saveVisualSettings() }
        }
        coloredNamesCheck = addCheckBox(content, "Colored usernames", settings.coloredUsernames).apply {
            setOnCheckedChangeListener { _, _ -> saveVisualSettings() }
        }
        badgesCheck = addCheckBox(content, "Badges", settings.badgesEnabled).apply {
            setOnCheckedChangeListener { _, _ -> saveVisualSettings() }
        }
        slowModeCheck = addCheckBox(content, "Slow mode", settings.slowMode).apply {
            setOnCheckedChangeListener { _, _ -> saveVisualSettings() }
        }

        content.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(6), 0, 0)
            addView(Button(ctx).apply {
                text = "Defaults"
                textSize = 12f
                minHeight = 0
                setOnClickListener {
                    suppressLiveUpdates = true
                    val defaults = TwitchChatSettings.reset(ctx)
                    settings = defaults
                    widthSeek.progress = defaults.clampedWidthDp - 220
                    heightSeek.progress = defaults.clampedHeightDp - 120
                    transparencySeek.progress = defaults.clampedTransparencyPercent
                    fontSeek.progress = defaults.clampedFontSizeSp - 8
                    twitchCheck.isChecked = defaults.twitchEmotesEnabled
                    bttvCheck.isChecked = defaults.bttvEmotesEnabled
                    ffzCheck.isChecked = defaults.ffzEmotesEnabled
                    sevenTvCheck.isChecked = defaults.sevenTvEmotesEnabled
                    coloredNamesCheck.isChecked = defaults.coloredUsernames
                    badgesCheck.isChecked = defaults.badgesEnabled
                    slowModeCheck.isChecked = defaults.slowMode
                    saveCornerIndex(0)
                    positionGroup.check(positionIds[0])
                    suppressLiveUpdates = false
                    applySettings(defaults)
                }
            })
            addView(Button(ctx).apply {
                text = "Close"
                textSize = 12f
                minHeight = 0
                setOnClickListener { dialog.dismiss() }
            })
        })

        val scroll = ScrollView(ctx).apply {
            setBackgroundColor(Color.argb(235, 16, 16, 16))
            addView(content)
        }

        dialog.setContentView(scroll)
        dialog.show()
        dialog.window?.let { window ->
            window.setLayout(if (isTvDevice()) dp(410) else dp(310), ViewGroup.LayoutParams.WRAP_CONTENT)
            val attrs = window.attributes
            attrs.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            attrs.x = dp(4)
            attrs.y = 0
            window.attributes = attrs
        }
    }


    // BEGIN TwitchChatCornerSettingsPatch
    private fun cornerLabel(index: Int): String {
        return when (index.coerceIn(0, 3)) {
            0 -> "Bottom right"
            1 -> "Top right"
            2 -> "Top left"
            else -> "Bottom left"
        }
    }

    private fun addCornerRow(parent: LinearLayout): android.widget.RadioGroup {
        val ctx = parent.context
        parent.addView(TextView(ctx).apply {
            text = "Position"
            setTextColor(Color.WHITE)
            textSize = 13f
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) }
        })

        return android.widget.RadioGroup(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val current = cornerIndex()
            for (index in 0..3) {
                addView(android.widget.RadioButton(ctx).apply {
                    id = View.generateViewId()
                    tag = index
                    text = cornerLabel(index)
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    isChecked = index == current
                })
            }
            parent.addView(
                this,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(8) },
            )
        }
    }

    private fun selectedCornerIndex(group: android.widget.RadioGroup): Int {
        return group.findViewById<android.widget.RadioButton>(group.checkedRadioButtonId)
            ?.tag
            ?.let { it as? Int }
            ?.coerceIn(0, 3)
            ?: 0
    }
    // END TwitchChatCornerSettingsPatch
    private fun addSeekRow(
        parent: LinearLayout,
        title: String,
        min: Int,
        max: Int,
        initial: Int,
        formatter: (Int) -> String,
        onValueChanged: ((Int) -> Unit)? = null,
    ): SeekBar {
        val ctx = parent.context
        val label = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
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
                val value = progress + min
                updateLabel(value)
                if (fromUser) onValueChanged?.invoke(value)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        parent.addView(label)
        parent.addView(seek, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(4) })
        return seek
    }

    private fun addCheckBox(parent: LinearLayout, label: String, checked: Boolean): CheckBox {
        return CheckBox(parent.context).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 12f
            isChecked = checked
            minHeight = 0
            setPadding(0, 0, 0, 0)
            parent.addView(this, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
    }

        private fun applySettings(newSettings: TwitchChatSettingsState) {
        settings = newSettings
        if (activeTarget?.mode == ChatMode.LIVE) {
            liveReplaySeedMessages = liveReplaySeedMessages.takeLast(liveStartupBackfillSize())
            liveIrcMessages = liveIrcMessages.takeLast(chatBufferMaxMessages())
            latestMessages = combinedLiveMessages()
        } else {
            latestMessages = latestMessages.takeLast(chatBufferMaxMessages())
        }
        overlayView()?.let { overlay ->
            styleOverlay(overlay)
            applyOverlayPosition(overlay)
            if (overlay.isVisible) {
                render()
                overlay.post {
                    if (overlay.isVisible) render()
                }
            }
            overlay.bringToFront()
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