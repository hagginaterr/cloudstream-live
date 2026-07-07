package com.lagradost.cloudstream3.ui.home

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import kotlin.math.roundToInt

/**
 * One small owner for TV home focus rules.
 *
 * Rules:
 * - DPAD left stays inside the row until adapter position 0; only position 0 may leave to the rail.
 * - DPAD up/down moves between content rows only and always focuses item 0 in the target row.
 * - The master row list is positioned by TvHomeRowsLayoutManager, not by snap helpers.
 */
object TvHomeFocusController {
    private const val MAX_FOCUS_RETRIES = 8

    fun configureMaster(
        masterRecycler: RecyclerView,
        root: View,
    ) {
        if (!isLayout(TV or EMULATOR)) return

        masterRecycler.onFlingListener = null
        if (masterRecycler.layoutManager !is TvHomeRowsLayoutManager) {
            masterRecycler.layoutManager = TvHomeRowsLayoutManager(masterRecycler.context)
        }
        masterRecycler.itemAnimator = null
        masterRecycler.clipToPadding = true
        masterRecycler.clipChildren = true
        masterRecycler.overScrollMode = View.OVER_SCROLL_NEVER
        masterRecycler.isNestedScrollingEnabled = false
        masterRecycler.setHasFixedSize(false)
        masterRecycler.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

        val params = masterRecycler.layoutParams
        if (params != null) {
            var changed = false
            if (params.width != ViewGroup.LayoutParams.MATCH_PARENT) {
                params.width = ViewGroup.LayoutParams.MATCH_PARENT
                changed = true
            }
            if (params.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                params.height = ViewGroup.LayoutParams.MATCH_PARENT
                changed = true
            }
            if (changed) {
                masterRecycler.layoutParams = params
            }
        }

        val applyShelf = {
            val rootHeight = root.height.takeIf { it > 0 } ?: masterRecycler.height
            if (rootHeight > 0) {
                val bottomInset = masterRecycler.dp(28)
                val minimumShelfHeight = masterRecycler.dp(260).coerceAtMost(rootHeight / 2)
                val desiredTopPadding = (rootHeight * 0.52f).roundToInt()
                val maxTopPadding = (rootHeight - minimumShelfHeight - bottomInset).coerceAtLeast(0)
                val topPadding = desiredTopPadding.coerceAtMost(maxTopPadding).coerceAtLeast(0)

                if (masterRecycler.paddingTop != topPadding ||
                    masterRecycler.paddingBottom != bottomInset
                ) {
                    masterRecycler.setPadding(
                        masterRecycler.paddingLeft,
                        topPadding,
                        masterRecycler.paddingRight,
                        bottomInset,
                    )
                }

                val shelfHeight = masterRecycler.height - topPadding - bottomInset
                if (shelfHeight > 0) {
                    for (index in 0 until masterRecycler.childCount) {
                        val child = masterRecycler.getChildAt(index)
                        val childParams = child.layoutParams
                        if (childParams.height != shelfHeight) {
                            childParams.height = shelfHeight
                            child.layoutParams = childParams
                        }
                        child.minimumHeight = shelfHeight
                    }
                }

                (masterRecycler.layoutManager as? TvHomeRowsLayoutManager)
                    ?.alignFocusedRowNow(masterRecycler)
            }
        }

        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyShelf()
        }
        masterRecycler.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    applyShelf()
                }

                override fun onChildViewDetachedFromWindow(view: View) = Unit
            },
        )
        masterRecycler.post { applyShelf() }
    }

    fun configureRow(
        rowRoot: View,
        rowRecycler: RecyclerView,
    ) {
        if (!isLayout(TV or EMULATOR)) return

        rowRoot.translationY = 0f
        (rowRoot as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        val rowParams = rowRoot.layoutParams
        if (rowParams != null && rowParams.width != ViewGroup.LayoutParams.MATCH_PARENT) {
            rowParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            rowRoot.layoutParams = rowParams
        }

        rowRecycler.itemAnimator = null
        rowRecycler.isNestedScrollingEnabled = false
        rowRecycler.overScrollMode = View.OVER_SCROLL_NEVER
        rowRecycler.clipToPadding = false
        rowRecycler.setHasFixedSize(true)
        rowRecycler.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

        val manager = rowRecycler.layoutManager as? LinearLayoutManager
        if (manager == null || manager.orientation != RecyclerView.HORIZONTAL) {
            rowRecycler.layoutManager = LinearLayoutManager(
                rowRecycler.context,
                RecyclerView.HORIZONTAL,
                false,
            ).apply {
                initialPrefetchItemCount = 4
            }
        } else {
            manager.initialPrefetchItemCount = 4
        }

        rowRecycler.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) {
                false
            } else {
                handleKey(rowRecycler.findFocus() ?: rowRecycler, keyCode)
            }
        }
    }

    fun configureCard(cardView: View) {
        if (!isLayout(TV or EMULATOR)) return

        cardView.isFocusable = true
        cardView.isFocusableInTouchMode = true

        val listener = View.OnKeyListener { view, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) {
                false
            } else {
                handleKey(view, keyCode)
            }
        }
        installKeyListenerRecursive(cardView, listener)
    }

    private fun installKeyListenerRecursive(
        view: View,
        listener: View.OnKeyListener,
    ) {
        view.setOnKeyListener(listener)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                installKeyListenerRecursive(view.getChildAt(index), listener)
            }
        }
    }

    private fun handleKey(
        focusedView: View,
        keyCode: Int,
    ): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> moveLeftWithinRow(focusedView)
            KeyEvent.KEYCODE_DPAD_UP -> moveToAdjacentRow(focusedView, -1)
            KeyEvent.KEYCODE_DPAD_DOWN -> moveToAdjacentRow(focusedView, 1)
            else -> false
        }
    }

    private fun moveLeftWithinRow(focusedView: View): Boolean {
        val rowRecycler = focusedView.findNearestRecyclerView() ?: return false
        val currentItem = rowRecycler.findContainingItemView(focusedView) ?: return false
        val currentPosition = rowRecycler.getChildAdapterPosition(currentItem)
        if (currentPosition == RecyclerView.NO_POSITION) return false

        val targetPosition = currentPosition - 1
        if (targetPosition < 0) {
            // Only the true first item may move to the side rail.
            return false
        }

        rowRecycler.stopScroll()
        val visibleTarget = rowRecycler.findViewHolderForAdapterPosition(targetPosition)?.itemView
        if (visibleTarget?.requestFocus() == true) {
            return true
        }

        (rowRecycler.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(targetPosition, rowRecycler.paddingLeft)
            ?: rowRecycler.scrollToPosition(targetPosition)
        rowRecycler.post {
            focusCardInRow(rowRecycler, targetPosition, 0)
        }
        return true
    }

    private fun moveToAdjacentRow(
        focusedView: View,
        direction: Int,
    ): Boolean {
        val rowRecycler = focusedView.findNearestRecyclerView() ?: return true
        val masterRecycler = rowRecycler.findMasterRecyclerView() ?: return true
        val currentRow = masterRecycler.findContainingItemView(rowRecycler) ?: return true
        val currentPosition = masterRecycler.getChildAdapterPosition(currentRow)
        if (currentPosition == RecyclerView.NO_POSITION) return true

        // There are intentionally only two content rows on this home screen.
        // Up from Live Now and Down from Recent Top Clips should be consumed.
        if (direction < 0 && currentRow.isLiveNowRow()) return true
        if (direction > 0 && currentRow.isRecentTopClipsRow()) return true

        val targetPosition = findAdjacentContentRowPosition(
            masterRecycler = masterRecycler,
            currentPosition = currentPosition,
            direction = direction,
        ) ?: return true

        masterRecycler.stopScroll()
        (masterRecycler.layoutManager as? TvHomeRowsLayoutManager)
            ?.alignRowAtPosition(masterRecycler, targetPosition, immediate = true)
            ?: (masterRecycler.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(targetPosition, masterRecycler.paddingTop)
            ?: masterRecycler.scrollToPosition(targetPosition)

        masterRecycler.post {
            focusFirstCardInRow(masterRecycler, targetPosition, 0)
        }
        return true
    }

    private fun findAdjacentContentRowPosition(
        masterRecycler: RecyclerView,
        currentPosition: Int,
        direction: Int,
    ): Int? {
        val itemCount = masterRecycler.adapter?.itemCount ?: return null
        var position = currentPosition + direction
        while (position in 0 until itemCount) {
            val attached = masterRecycler.findViewHolderForAdapterPosition(position)?.itemView
            if (attached == null || attached.findViewById<RecyclerView>(R.id.home_child_recyclerview) != null) {
                return position
            }
            position += direction
        }
        return null
    }

    private fun focusFirstCardInRow(
        masterRecycler: RecyclerView,
        rowPosition: Int,
        attempt: Int,
    ) {
        val rowRoot = masterRecycler
            .findViewHolderForAdapterPosition(rowPosition)
            ?.itemView
        val rowRecycler = rowRoot?.findViewById<RecyclerView>(R.id.home_child_recyclerview)

        if (rowRecycler != null) {
            rowRecycler.stopScroll()
            (rowRecycler.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(0, rowRecycler.paddingLeft)
                ?: rowRecycler.scrollToPosition(0)

            val firstCard = rowRecycler.findViewHolderForAdapterPosition(0)?.itemView
            if (firstCard?.requestFocus() == true) {
                (masterRecycler.layoutManager as? TvHomeRowsLayoutManager)
                    ?.alignRowAtPosition(masterRecycler, rowPosition, immediate = true)
                return
            }
        }

        if (attempt < MAX_FOCUS_RETRIES) {
            masterRecycler.postDelayed({
                focusFirstCardInRow(masterRecycler, rowPosition, attempt + 1)
            }, 40L)
        }
    }

    private fun focusCardInRow(
        rowRecycler: RecyclerView,
        position: Int,
        attempt: Int,
    ) {
        val card = rowRecycler.findViewHolderForAdapterPosition(position)?.itemView
        if (card?.requestFocus() == true) return

        if (attempt < MAX_FOCUS_RETRIES) {
            rowRecycler.postDelayed({
                focusCardInRow(rowRecycler, position, attempt + 1)
            }, 32L)
        }
    }

    private fun View.findNearestRecyclerView(): RecyclerView? {
        if (this is RecyclerView && id == R.id.home_child_recyclerview) return this

        var current: ViewParent? = parent
        while (current is View) {
            if (current is RecyclerView) return current
            current = current.parent
        }
        return null
    }

    private fun RecyclerView.findMasterRecyclerView(): RecyclerView? {
        var current: ViewParent? = parent
        while (current is View) {
            if (current is RecyclerView && current.id == R.id.home_master_recycler) {
                return current
            }
            current = current.parent
        }
        return null
    }

    private fun View.rowTitle(): String {
        return findViewById<TextView>(R.id.home_child_more_info)
            ?.text
            ?.toString()
            ?.trim()
            .orEmpty()
    }

    private fun View.isLiveNowRow(): Boolean {
        return rowTitle().equals("Live Now", ignoreCase = true)
    }

    private fun View.isRecentTopClipsRow(): Boolean {
        return rowTitle().equals("Recent Top Clips", ignoreCase = true)
    }

    private fun View.dp(value: Int): Int {
        return (value * resources.displayMetrics.density).roundToInt()
    }
}