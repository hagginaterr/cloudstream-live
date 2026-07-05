package com.lagradost.cloudstream3.ui.player.live

import androidx.media3.common.C
import androidx.media3.common.Player
import java.lang.ref.WeakReference

// How much margin from the live point is still considered "live"
const val LIVE_MARGIN = 6_000L

// How many ms should we be behind the real live point?
// Too low, and we cannot pre-buffer.
// Too high, and we are no longer live.
const val PREFERRED_LIVE_OFFSET = 5_000L

// An extra offset from the optimal calculated timestamp.
// This accounts for chunk updates not always being the same size.
const val CHUNK_VARIANCE = 3_000L

// A livestream chunk from the player. The time we get it and the duration can
// be used to calculate the expected live timestamp for streams where Media3
// cannot expose a trustworthy currentLiveOffset.
class LivestreamChunk(
    val durationMs: Long,
    val receiveTimeMs: Long = System.currentTimeMillis(),
) {
    // We want to be PREFERRED_LIVE_OFFSET ms after the latest update, but we
    // cannot be ahead of the middle point. If we are ahead of the middle point
    // we will reach the end before the new chunk is expected to be released.
    val targetPosition = maxOf(
        0L,
        minOf(
            durationMs - PREFERRED_LIVE_OFFSET,
            durationMs / 2 - CHUNK_VARIANCE,
        ),
    )

    fun isPositionLive(position: Long): Boolean {
        val currentTime = System.currentTimeMillis()
        val livePosition = targetPosition + (currentTime - receiveTimeMs)
        return position + LIVE_MARGIN > livePosition - PREFERRED_LIVE_OFFSET
    }

    fun getTimeAheadOfLive(position: Long): Long {
        val currentTime = System.currentTimeMillis()
        val livePosition = targetPosition + (currentTime - receiveTimeMs)
        return position - livePosition
    }
}

// There are two types of livestreams we need to manage:
// 1. A livestream with no history, a continually sliding window.
// 2. A livestream with history / live-DVR, where currentLiveOffset is the
//    reliable signal for "how far behind live" the player currently is.
class LiveManager(player: Player?) {
    private var _currentPlayer: WeakReference<Player>? = player?.let { WeakReference(it) }
    val currentPlayer: Player? get() = _currentPlayer?.get()

    private var lastLivestreamChunk: LivestreamChunk? = null

    fun submitLivestreamChunk(chunk: LivestreamChunk) {
        lastLivestreamChunk = chunk
    }

    /**
     * Returns how much a position is ahead of the calculated live window.
     * Returns 0 if not ahead of live window.
     */
    fun getTimeAheadOfLive(position: Long): Long {
        val player = currentPlayer ?: return 0
        if (!player.isCurrentMediaItemDynamic || player.duration == C.TIME_UNSET) return 0

        // If currentLiveOffset is wrong we fall back to manual calculations.
        val ahead = if (
            player.currentLiveOffset != C.TIME_UNSET &&
            player.currentLiveOffset >= 0L &&
            player.currentLiveOffset < player.duration
        ) {
            val relativeOffset = player.currentLiveOffset - player.currentPosition + position
            PREFERRED_LIVE_OFFSET - relativeOffset
        } else {
            lastLivestreamChunk?.getTimeAheadOfLive(position) ?: 0
        }

        return maxOf(0, ahead)
    }

    private fun hasReliableLiveDvrOffset(player: Player): Boolean {
        val duration = player.duration
        val liveOffset = player.currentLiveOffset

        return player.isCurrentMediaItemDynamic &&
            duration != C.TIME_UNSET &&
            duration > 0L &&
            liveOffset != C.TIME_UNSET &&
            liveOffset >= 0L &&
            liveOffset < duration
    }

    fun getSeekbarDisplayDuration(): Long? {
        val player = currentPlayer ?: return null
        val duration = player.duration

        if (!player.isCurrentMediaItemDynamic) return null
        if (duration != C.TIME_UNSET && duration > 0L) return duration

        return lastLivestreamChunk
            ?.durationMs
            ?.takeIf { it > 0L }
    }

    /**
     * Converts raw player positions into a display-only seekbar position for
     * live-DVR streams.
     *
     * This intentionally only runs when currentLiveOffset is reliable. If
     * currentLiveOffset is unavailable, we fall back to raw Media3 behavior
     * instead of guessing, because guessing is what caused negative timestamps
     * and halfway-bar placement.
     */
    fun getSeekbarDisplayPosition(position: Long): Long? {
        val player = currentPlayer ?: return null
        if (!hasReliableLiveDvrOffset(player)) return null

        val duration = getSeekbarDisplayDuration() ?: return null
        val rawOffsetFromCurrent = player.currentPosition - position
        val offsetAtPosition = (player.currentLiveOffset + rawOffsetFromCurrent).coerceAtLeast(0L)
        val behindPreferredLiveEdge = (offsetAtPosition - PREFERRED_LIVE_OFFSET).coerceAtLeast(0L)

        return (duration - behindPreferredLiveEdge).coerceIn(0L, duration)
    }

    fun getSeekbarDisplayBufferedPosition(bufferedPosition: Long): Long? {
        val player = currentPlayer ?: return null
        if (!hasReliableLiveDvrOffset(player)) return null

        val duration = getSeekbarDisplayDuration() ?: return null
        val normalized = getSeekbarDisplayPosition(bufferedPosition) ?: duration

        // For live-DVR playback the area from the current position to the live
        // edge is normally available or about to be available. Keep the
        // buffered marker at least at the normalized position and never beyond
        // the display duration.
        return maxOf(normalized, duration).coerceIn(0L, duration)
    }

    /**
     * Check if the stream is currently at the expected live edge, with margins.
     */
    fun isAtLiveEdge(): Boolean {
        val player = currentPlayer ?: return false
        if (!player.isCurrentMediaItemDynamic || player.duration == C.TIME_UNSET) return false

        // If currentLiveOffset is wrong we fall back to manual calculations.
        return if (
            player.currentLiveOffset != C.TIME_UNSET &&
            player.currentLiveOffset >= 0L &&
            player.currentLiveOffset < player.duration
        ) {
            player.currentLiveOffset < LIVE_MARGIN + PREFERRED_LIVE_OFFSET
        } else {
            lastLivestreamChunk?.isPositionLive(player.currentPosition) == true
        }
    }
}