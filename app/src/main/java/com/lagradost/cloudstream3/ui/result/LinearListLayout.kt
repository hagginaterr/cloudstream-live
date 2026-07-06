package com.lagradost.cloudstream3.ui.result

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.FocusDirection
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout

const val FOCUS_SELF = View.NO_ID - 1
const val FOCUS_INHERIT = FOCUS_SELF - 1

fun RecyclerView?.setLinearListLayout(
    isHorizontal: Boolean = true,
    nextLeft: Int = FOCUS_INHERIT,
    nextRight: Int = FOCUS_INHERIT,
    nextUp: Int = FOCUS_INHERIT,
    nextDown: Int = FOCUS_INHERIT
) {
    if (this == null) return
    val ctx = this.context ?: return
    this.layoutManager = (this.layoutManager as? LinearListLayout ?: LinearListLayout(ctx)).apply {
        if (isHorizontal) setHorizontal() else setVertical()
        nextFocusLeft =
            if (nextLeft == FOCUS_INHERIT) this@setLinearListLayout.nextFocusLeftId else nextLeft
        nextFocusRight =
            if (nextRight == FOCUS_INHERIT) this@setLinearListLayout.nextFocusRightId else nextRight
        nextFocusUp =
            if (nextUp == FOCUS_INHERIT) this@setLinearListLayout.nextFocusUpId else nextUp
        nextFocusDown =
            if (nextDown == FOCUS_INHERIT) this@setLinearListLayout.nextFocusDownId else nextDown
    }
}

open class LinearListLayout(context: Context?) :
    LinearLayoutManager(context) {

    var nextFocusLeft: Int = View.NO_ID
    var nextFocusRight: Int = View.NO_ID
    var nextFocusUp: Int = View.NO_ID
    var nextFocusDown: Int = View.NO_ID

    private var attachedRecyclerView: RecyclerView? = null

    override fun onAttachedToWindow(view: RecyclerView) {
        super.onAttachedToWindow(view)
        attachedRecyclerView = view
        if (isLayout(TV) && orientation == HORIZONTAL) {
            setInitialPrefetchItemCount(maxOf(8, getInitialPrefetchItemCount()))
        }
    }

    override fun onDetachedFromWindow(view: RecyclerView, recycler: RecyclerView.Recycler) {
        if (attachedRecyclerView === view) {
            attachedRecyclerView = null
        }
        super.onDetachedFromWindow(view, recycler)
    }

    fun setHorizontal() {

        orientation = HORIZONTAL

        if (isLayout(TV)) {

            setInitialPrefetchItemCount(maxOf(8, getInitialPrefetchItemCount()))

        }

    }

    fun setVertical() {
        orientation = VERTICAL
    }

    private fun getCorrectParent(focused: View?): View? {
        if (focused == null) return null
        var current: View? = focused
        val last: ArrayList<View> = arrayListOf(focused)
        while (current != null && current !is RecyclerView) {
            current = (current.parent as? View?)?.also { last.add(it) }
        }
        return last.getOrNull(last.count() - 2)
    }

    private fun getPosition(view: View?): Int? {
        return (view?.layoutParams as? RecyclerView.LayoutParams?)?.absoluteAdapterPosition
    }

    private fun getViewFromPos(pos: Int): View? {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if ((child?.layoutParams as? RecyclerView.LayoutParams?)?.absoluteAdapterPosition == pos) {
                return child
            }
        }
        return null
        //return recyclerView.children.firstOrNull { child -> (child.layoutParams as? RecyclerView.LayoutParams?)?.absoluteAdapterPosition == pos) }
    }

    /*
    private fun scrollTo(position: Int) {
        val linearSmoothScroller = LinearSmoothScroller(recyclerView.context)
        linearSmoothScroller.targetPosition = position
        startSmoothScroll(linearSmoothScroller)
    }*/

    /** from the current focus go to a direction */
    private fun getNextDirection(focused: View?, direction: FocusDirection): View? {
        val id = when (direction) {
            FocusDirection.Start -> if (isLayoutRTL) nextFocusRight else nextFocusLeft
            FocusDirection.End -> if (isLayoutRTL) nextFocusLeft else nextFocusRight
            FocusDirection.Up -> nextFocusUp
            FocusDirection.Down -> nextFocusDown
        }

        return when (id) {
            View.NO_ID -> null
            FOCUS_SELF -> focused
            else -> CommonActivity.continueGetNextFocus(
                activity ?: focused,
                focused ?: return null,
                direction,
                id
            )
        }
    }

    fun redirectRecycleToFirstItem(focused: View): View? {
        return when (focused) {
            is RecyclerView -> {
                (focused.layoutManager as? LinearListLayout)?.let { focusedLayoutManager ->
                    val firstPosition = focusedLayoutManager.findFirstVisibleItemPosition()
                    val firstView = focusedLayoutManager.findViewByPosition(firstPosition)
                    firstView
                } ?: focused
            }

            else -> focused
        }
    }

    private fun isHorizontalFocusDirection(direction: Int): Boolean {
        return direction == View.FOCUS_RIGHT || direction == View.FOCUS_LEFT
    }

    private fun getHorizontalFocusStep(direction: Int): Int {
        var step = if (direction == View.FOCUS_RIGHT) 1 else -1
        if (isLayoutRTL) {
            step = -step
        }
        return step
    }

    private fun focusHorizontalPositionLater(position: Int, focused: View, direction: Int) {
        val row = attachedRecyclerView ?: (focused.parent as? RecyclerView)
        try {
            row?.stopScroll()
            scrollToPosition(position)
        } catch (e: Exception) {
            logError(e)
        }

        fun requestTargetFocus() {
            val target = getViewFromPos(position) ?: findViewByPosition(position)
            target?.requestFocus(direction)
        }

        if (row != null) {
            row.post { requestTargetFocus() }
            row.postDelayed({ requestTargetFocus() }, 50L)
        } else {
            focused.post { requestTargetFocus() }
        }
    }
    override fun onInterceptFocusSearch(focused: View, direction: Int): View? {
        val dir = if (orientation == HORIZONTAL) {
            if (direction == View.FOCUS_DOWN) getNextDirection(
                focused,
                FocusDirection.Down
            )?.let { newFocus ->
                return redirectRecycleToFirstItem(newFocus)
            }
            if (direction == View.FOCUS_UP) getNextDirection(
                focused,
                FocusDirection.Up
            )?.let { newFocus ->
                return redirectRecycleToFirstItem(newFocus)
            }

            if (direction == View.FOCUS_DOWN || direction == View.FOCUS_UP) {
                // This scrolls the recyclerview before doing focus search, which
                // allows the focus search to work better.

                // Without this the recyclerview focus location on the screen
                // would change when scrolling between recyclerviews.
                (focused.parent as? RecyclerView)?.focusSearch(direction)
                return null
            }
            var ret = if (direction == View.FOCUS_RIGHT) 1 else -1
            // only flip on horizontal layout
            if (isLayoutRTL) {
                ret = -ret
            }
            ret
        } else {
            if (direction == View.FOCUS_RIGHT) getNextDirection(
                focused,
                FocusDirection.End
            )?.let { newFocus ->
                return newFocus
            }
            if (direction == View.FOCUS_LEFT) getNextDirection(
                focused,
                FocusDirection.Start
            )?.let { newFocus ->
                return newFocus
            }

            if (direction == View.FOCUS_RIGHT || direction == View.FOCUS_LEFT) {
                (focused.parent as? RecyclerView)?.focusSearch(direction)
                return null
            }

            //if (direction == View.FOCUS_RIGHT || direction == View.FOCUS_LEFT) return null
            if (direction == View.FOCUS_DOWN) 1 else -1
        }

        try {
            val position = getPosition(getCorrectParent(focused)) ?: return null
            val lookFor = dir + position

            // if out of bounds then refocus as specified
            return if (lookFor >= itemCount) {
                // TvHorizontalRowTrap by ChatGPT: LEFT/RIGHT at the row edge must
                // stay in this row. Android TV focus search can otherwise escape
                // vertically to a row above/below when the user fast-scrolls.
                if (isLayout(TV) && orientation == HORIZONTAL) {
                    // TvHomeSidebarEdgePatch: honor an explicit row-edge target on TV.
                    getNextDirection(focused, FocusDirection.End) ?: focused
                } else {
                    getNextDirection(
                        focused,
                        if (orientation == HORIZONTAL) FocusDirection.End else FocusDirection.Down
                    )
                }
            } else if (lookFor < 0) {
                if (isLayout(TV) && orientation == HORIZONTAL) {
                    // TvHomeSidebarEdgePatch: first item DPAD-left can leave to the sidebar.
                    getNextDirection(focused, FocusDirection.Start) ?: focused
                } else {
                    getNextDirection(
                        focused,
                        if (orientation == HORIZONTAL) FocusDirection.Start else FocusDirection.Up
                    )
                }
            } else {
            getViewFromPos(lookFor) ?: run {
                if (isLayout(TV) && orientation == HORIZONTAL && isHorizontalFocusDirection(direction)) {
                    focusHorizontalPositionLater(lookFor, focused, direction)
                    focused
                } else {
                    scrollToPosition(lookFor)
                    null
                }
            }
        }
    } catch (e: Exception) {
            logError(e)
            return null
        }
    }

    override fun onFocusSearchFailed(
        focused: View,
        direction: Int,
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State
    ): View? {
        if (isLayout(TV) && orientation == HORIZONTAL && isHorizontalFocusDirection(direction)) {
            try {
                val position = getPosition(getCorrectParent(focused)) ?: getPosition(focused) ?: return focused
                val lookFor = position + getHorizontalFocusStep(direction)
                if (lookFor in 0 until itemCount) {
                    focusHorizontalPositionLater(lookFor, focused, direction)
                    return focused
                }

                // TvHomeSidebarEdgePatch: if focus search falls off a row edge, honor
                // nextFocusLeft/nextFocusRight before falling back to row trapping.
                return getNextDirection(
                    focused,
                    if (direction == View.FOCUS_LEFT) FocusDirection.Start else FocusDirection.End
                ) ?: focused
            } catch (e: Exception) {
                logError(e)
                return focused
            }
        }
        return super.onFocusSearchFailed(focused, direction, recycler, state)
    }
    override fun requestChildRectangleOnScreen(
        parent: RecyclerView,
        child: View,
        rect: android.graphics.Rect,
        immediate: Boolean,
        focusedChildVisible: Boolean
    ): Boolean {
        if (isLayout(TV) && orientation == HORIZONTAL) {
            val parentStart = parent.paddingLeft
            val parentEnd = parent.width - parent.paddingRight
            val childStart = getDecoratedLeft(child)
            val childEnd = getDecoratedRight(child)
            val dx = when {
                childStart < parentStart -> childStart - parentStart
                childEnd > parentEnd -> childEnd - parentEnd
                else -> 0
            }

            if (dx != 0) {
                parent.stopScroll()
                parent.scrollBy(dx, 0)
                return true
            }
            return false
        } else {
            return super.requestChildRectangleOnScreen(
                parent,
                child,
                rect,
                immediate,
                focusedChildVisible
            )
        }
    }

    /*override fun onRequestChildFocus(
        parent: RecyclerView,
        state: RecyclerView.State,
        child: View,
        focused: View?
    ): Boolean {
        return super.onRequestChildFocus(parent, state, child, focused)
        getPosition(getCorrectParent(focused ?: return true))?.let {
            val startView = findFirstVisibleChildClosestToStart(true,true)
            val endView = findFirstVisibleChildClosestToEnd(true,true)
            val start = getPosition(startView)
            val end = getPosition(endView)
            fill(parent,LayoutState())

            val helper = mOrientationHelper ?: return false
            val laidOutArea: Int = abs(
                helper.getDecoratedEnd(startView)
                        - helper.getDecoratedStart(endView)
            )
            val itemRange: Int = abs(
                (start
                        - end)
            ) + 1

            val avgSizePerRow = laidOutArea.toFloat() / itemRange

            return Math.round(
                itemsBefore * avgSizePerRow + ((orientation.getStartAfterPadding()
                        - orientation.getDecoratedStart(startChild)))
            )
            recyclerView.scrollToPosition(it)
        }
        return true*/

    //return super.onRequestChildFocus(parent, state, child, focused)
    /* if (focused == null || focused == child) {
         return super.onRequestChildFocus(parent, state, child, focused)
     }

     try {
         val pos = getPosition(getCorrectParent(focused) ?: return true)
         scrollToPosition(pos)
     } catch (e: Exception) {
         logError(e)
     }
     return true
}*/
}