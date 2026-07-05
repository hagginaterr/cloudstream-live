package com.lagradost.cloudstream3.ui.player.live

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import java.lang.ref.WeakReference

// How close to the live/default position still counts as live.
const val LIVE_MARGIN = 6_000L

// How far behind the real live edge ExoPlayer should try to play.
// This is also wired into DefaultMediaSourceFactory.setLiveTargetOffsetMs(...).
const val PREFERRED_LIVE_OFFSET = 5_000L

// Kept for source/binary compatibility with older patches. Do not use this to clamp the
// live target to the middle of the seek window; that was the cause of the stuck-midbar bug.
const val CHUNK_VARIANCE = 3_000L

// Snapshot of a dynamic live window when ExoPlayer reports a timeline update.
// Player.currentPosition is relative to the start of the current live window and
// Player.duration is the length/end of that window, so the fallback live target is
// near duration, not duration / 2.
class LivestreamChunk(
    val durationMs: Long,
    val receiveTimeMs: Long = System.currentTimeMillis()
) {
    val targetPosition: Long = if (durationMs <= 0L || durationMs == C.TIME_UNSET) {
        0L
    } else {
        (durationMs - PREFERRED_LIVE_OFFSET).coerceAtLeast(0L)
    }

    fun isPositionLive(position: Long): Boolean {
        return position + LIVE_MARGIN >= targetPosition
    }

    fun getTimeAheadOfLive(position: Long): Long {
        if (durationMs <= 0L || durationMs == C.TIME_UNSET) return 0L
        return (position - durationMs).coerceAtLeast(0L)
    }
}

class LiveManager(player: Player?) {
    private var _currentPlayer: WeakReference<Player>? = WeakReference(player)
    val currentPlayer: Player? get() = _currentPlayer?.get()

    private var lastLivestreamChunk: LivestreamChunk? = null

    fun submitLivestreamChunk(chunk: LivestreamChunk) {
        lastLivestreamChunk = chunk
    }

    private fun getWindow(player: Player): Timeline.Window? {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return null
        val index = player.currentMediaItemIndex
        if (index < 0 || index >= timeline.windowCount) return null
        return timeline.getWindow(index, Timeline.Window())
    }

    private fun getFallbackLiveTarget(player: Player): Long? {
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0L) return null

        val windowDefault = getWindow(player)
            ?.getDefaultPositionMs()
            ?.takeIf { it != C.TIME_UNSET && it in 0L..duration }

        return (windowDefault ?: (duration - PREFERRED_LIVE_OFFSET))
            .coerceIn(0L, duration)
    }

    /**
     * Returns how far a requested position is beyond the actual live edge/window end.
     * This must not use the preferred target offset, otherwise seeking back to live gets
     * corrected back away from live and the seekbar appears stuck in the middle.
     */
    fun getTimeAheadOfLive(position: Long): Long {
        val player = currentPlayer ?: return 0L
        if (!player.isCurrentMediaItemDynamic) return 0L

        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0L) return 0L

        val liveOffset = player.currentLiveOffset
        val liveEdge = if (liveOffset != C.TIME_UNSET && liveOffset >= 0L) {
            (player.currentPosition + liveOffset).coerceAtMost(duration)
        } else {
            duration
        }

        return (position - liveEdge).coerceAtLeast(0L)
    }

    /** Check if the stream is at ExoPlayer's live/default position, with margins. */
    fun isAtLiveEdge(): Boolean {
        val player = currentPlayer ?: return false
        if (!player.isCurrentMediaItemDynamic) return false

        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0L) return false

        val liveOffset = player.currentLiveOffset
        if (liveOffset != C.TIME_UNSET && liveOffset >= 0L) {
            return liveOffset <= PREFERRED_LIVE_OFFSET + LIVE_MARGIN
        }

        val target = getFallbackLiveTarget(player) ?: lastLivestreamChunk?.targetPosition ?: return false
        return player.currentPosition + LIVE_MARGIN >= target
    }
}