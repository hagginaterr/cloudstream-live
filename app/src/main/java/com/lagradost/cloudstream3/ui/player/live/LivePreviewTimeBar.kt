package com.lagradost.cloudstream3.ui.player.live

import android.content.Context
import android.util.AttributeSet
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.PlayerView
import androidx.media3.ui.R
import com.github.rubensousa.previewseekbar.media3.PreviewTimeBar
import java.lang.ref.WeakReference

@OptIn(UnstableApi::class)
class LivePreviewTimeBar(val ctx: Context, attrs: AttributeSet) : PreviewTimeBar(ctx, attrs) {
    private var _currentPlayerView: WeakReference<PlayerView>? = null
    val currentPlayer: Player? get() = _currentPlayerView?.get()?.player

    fun registerPlayerView(player: PlayerView?) {
        _currentPlayerView = WeakReference(player)

        val controller =
            _currentPlayerView?.get()?.findViewById<PlayerControlView>(R.id.exo_controller)

        controller?.setProgressUpdateListener { _, _ ->
            currentPlayer?.let { current ->
                getLiveDisplayPosition(current)?.let { displayPosition ->
                    setPosition(displayPosition)
                }
            }
        }
    }

    /**
     * Media3 reports Twitch live-DVR / live-with-past-broadcast playback using the raw
     * sliding-window/VOD position. After a tiny DPAD seek back, that raw position can be
     * visually around the middle of the time bar even though playback only moved a few
     * seconds. For live-DVR UI we render progress as distance behind the live edge instead.
     */
    private fun getLiveDisplayPosition(player: Player): Long? {
        val duration = player.duration
        if (!player.isCurrentMediaItemDynamic || duration == C.TIME_UNSET || duration <= 0L) {
            return null
        }

        if (isAtLiveEdge()) {
            return duration
        }

        val liveOffset = player.currentLiveOffset
        if (liveOffset == C.TIME_UNSET || liveOffset < 0L || liveOffset >= duration) {
            return null
        }

        val behindPreferredLiveEdge = (liveOffset - PREFERRED_LIVE_OFFSET).coerceAtLeast(0L)
        return (duration - behindPreferredLiveEdge).coerceIn(0L, duration)
    }

    fun isAtLiveEdge(): Boolean {
        return LiveHelper.getLiveManager(currentPlayer)?.isAtLiveEdge() == true
    }
}