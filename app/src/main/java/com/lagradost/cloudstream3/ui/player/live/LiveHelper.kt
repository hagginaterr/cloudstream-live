package com.lagradost.cloudstream3.ui.player.live

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import com.lagradost.cloudstream3.mvvm.debugWarning
import java.util.WeakHashMap

object LiveHelper {
    private val liveManagers = WeakHashMap<Player, Pair<LiveManager, Player.Listener>>()

    @OptIn(UnstableApi::class)
    fun registerPlayer(player: Player?) {
        if (player == null) {
            debugWarning { "LiveHelper registerPlayer called with null player!" }
            return
        }

        if (liveManagers.contains(player)) {
            return
        }

        val liveManager = LiveManager(player)
        val listener = object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                val window = Timeline.Window()
                if (!timeline.isEmpty && player.currentMediaItemIndex in 0 until timeline.windowCount) {
                    timeline.getWindow(player.currentMediaItemIndex, window)
                    if (window.isDynamic) {
                        liveManager.submitLivestreamChunk(LivestreamChunk(window.durationMs))
                    }
                }
                super.onTimelineChanged(timeline, reason)
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                super.onPositionDiscontinuity(oldPosition, newPosition, reason)

                // Only correct positions that are beyond the actual live window edge.
                // Do not correct positions that are merely ahead of the preferred live offset;
                // Media3's default live target is already handled by seekToDefaultPosition().
                val timeAheadOfLive = liveManager.getTimeAheadOfLive(newPosition.positionMs)
                if (timeAheadOfLive > 100) {
                    player.seekToDefaultPosition()
                }
            }
        }

        synchronized(liveManagers) {
            player.addListener(listener)
            liveManagers[player] = liveManager to listener
        }
    }

    fun unregisterPlayer(player: Player?) {
        if (player == null) {
            return
        }

        synchronized(liveManagers) {
            liveManagers[player]?.let { (_, listener) ->
                player.removeListener(listener)
            }
            liveManagers.remove(player)
        }
    }

    fun getLiveManager(player: Player?) = liveManagers[player]?.first
}