package com.lagradost.cloudstream3.ui.player.live

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.PlayerView
import androidx.media3.ui.R
import com.github.rubensousa.previewseekbar.media3.PreviewTimeBar
import java.lang.ref.WeakReference
import java.util.Formatter
import java.util.Locale

@OptIn(UnstableApi::class)
class LivePreviewTimeBar(ctx: Context, attrs: AttributeSet) : PreviewTimeBar(ctx, attrs) {
    private var _currentPlayerView: WeakReference<PlayerView>? = null
    val currentPlayer: Player? get() = _currentPlayerView?.get()?.player

    private val formatBuilder = StringBuilder()
    private val formatter = Formatter(formatBuilder, Locale.getDefault())

    fun registerPlayerView(player: PlayerView?) {
        _currentPlayerView = player?.let { WeakReference(it) }

        val controller =
            _currentPlayerView?.get()?.findViewById<PlayerControlView>(R.id.exo_controller)

        controller?.setProgressUpdateListener { position, bufferedPosition ->
            applyLiveDvrDisplay(position, bufferedPosition)
        }
    }

    override fun setDuration(duration: Long) {
        super.setDuration(normalizeLiveDvrDuration(duration))
    }

    override fun setPosition(position: Long) {
        super.setPosition(normalizeLiveDvrPosition(position))
    }

    override fun setBufferedPosition(bufferedPosition: Long) {
        super.setBufferedPosition(normalizeLiveDvrBufferedPosition(bufferedPosition))
    }

    private fun liveManager(): LiveManager? {
        return LiveHelper.getLiveManager(currentPlayer)
    }

    private fun normalizeLiveDvrDuration(duration: Long): Long {
        return liveManager()?.getSeekbarDisplayDuration() ?: duration
    }

    private fun normalizeLiveDvrPosition(position: Long): Long {
        if (position == C.TIME_UNSET || position < 0L) return position
        return liveManager()?.getSeekbarDisplayPosition(position) ?: position
    }

    private fun normalizeLiveDvrBufferedPosition(bufferedPosition: Long): Long {
        if (bufferedPosition == C.TIME_UNSET || bufferedPosition < 0L) return bufferedPosition
        return liveManager()?.getSeekbarDisplayBufferedPosition(bufferedPosition) ?: bufferedPosition
    }

    private fun applyLiveDvrDisplay(position: Long, bufferedPosition: Long) {
        val manager = liveManager() ?: return
        val displayDuration = manager.getSeekbarDisplayDuration() ?: return
        val displayPosition = manager.getSeekbarDisplayPosition(position) ?: return
        val displayBuffered = manager.getSeekbarDisplayBufferedPosition(bufferedPosition)
            ?: displayDuration

        super.setDuration(displayDuration)
        super.setPosition(displayPosition)
        super.setBufferedPosition(displayBuffered)

        val controller =
            _currentPlayerView?.get()?.findViewById<PlayerControlView>(R.id.exo_controller)
                ?: return

        controller.findViewById<TextView>(R.id.exo_position)?.text =
            formatTime(displayPosition)
        controller.findViewById<TextView>(R.id.exo_duration)?.text =
            formatTime(displayDuration)
    }

    private fun formatTime(timeMs: Long): String {
        formatBuilder.setLength(0)
        return Util.getStringForTime(formatBuilder, formatter, timeMs)
    }

    fun isAtLiveEdge(): Boolean {
        return liveManager()?.isAtLiveEdge() == true
    }
}