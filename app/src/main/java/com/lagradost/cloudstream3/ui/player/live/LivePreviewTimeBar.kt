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
class LivePreviewTimeBar(ctx: Context, attrs: AttributeSet) : PreviewTimeBar(ctx, attrs) {
    private var _currentPlayerView: WeakReference<PlayerView>? = null
    val currentPlayer: Player? get() = _currentPlayerView?.get()?.player

    fun registerPlayerView(player: PlayerView?) {
        _currentPlayerView = player?.let { WeakReference(it) }

        val controller =
            _currentPlayerView?.get()?.findViewById<PlayerControlView>(R.id.exo_controller)

        controller?.setProgressUpdateListener { position, bufferedPosition ->
            setPosition(position)
            setBufferedPosition(bufferedPosition)
        }
    }

    override fun setPosition(position: Long) {
        super.setPosition(normalizeLiveDvrPosition(position))
    }

    override fun setBufferedPosition(bufferedPosition: Long) {
        super.setBufferedPosition(normalizeLiveDvrPosition(bufferedPosition))
    }

    private fun normalizeLiveDvrPosition(position: Long): Long {
        if (position == C.TIME_UNSET || position < 0L) return position

        val player = currentPlayer ?: return position
        return LiveHelper.getLiveManager(player)
            ?.getSeekbarDisplayPosition(position)
            ?: position
    }

    fun isAtLiveEdge(): Boolean {
        return LiveHelper.getLiveManager(currentPlayer)?.isAtLiveEdge() == true
    }
}