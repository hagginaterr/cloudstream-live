package com.lagradost.cloudstream3.ui.player.live

import android.content.Context
import android.util.AttributeSet
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.github.rubensousa.previewseekbar.media3.PreviewTimeBar
import java.lang.ref.WeakReference

@OptIn(UnstableApi::class)
class LivePreviewTimeBar(val ctx: Context, attrs: AttributeSet) : PreviewTimeBar(ctx, attrs) {

    private var _currentPlayerView: WeakReference<PlayerView>? = null
    val currentPlayer: Player? get() = _currentPlayerView?.get()?.player

    fun registerPlayerView(player: PlayerView?) {
        _currentPlayerView = WeakReference(player)
        // Leave progress ownership to Media3 PlayerControlView/TimeBar. Manually forcing
        // setPosition(duration) while live caused stale seekbar state after returning to live.
    }

    fun isAtLiveEdge(): Boolean {
        return LiveHelper.getLiveManager(currentPlayer)?.isAtLiveEdge() == true
    }
}