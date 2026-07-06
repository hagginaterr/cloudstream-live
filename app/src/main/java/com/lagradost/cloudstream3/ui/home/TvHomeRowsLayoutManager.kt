package com.lagradost.cloudstream3.ui.home

import android.content.Context
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * Keeps the focused TV home row aligned to the lower shelf viewport.
 *
 * The master RecyclerView stays full-screen so D-pad focus search can still find
 * the next row, while clipToPadding and a large top padding keep rows from being
 * drawn above the lower content shelf.
 */
class TvHomeRowsLayoutManager(context: Context) : LinearLayoutManager(context, RecyclerView.VERTICAL, false) {
    private fun directChild(parent: RecyclerView, child: View): View {
        return if (child.parent == parent) child else parent.findContainingItemView(child) ?: child
    }

    private fun alignChildToShelf(
        parent: RecyclerView,
        child: View,
        immediate: Boolean,
    ): Boolean {
        if (parent.height <= 0) return false

        val targetTop = parent.paddingTop
        val dy = child.top - targetTop
        if (abs(dy) <= 2) return false

        if (immediate) {
            parent.scrollBy(0, dy)
        } else {
            parent.post {
                if (!parent.isAttachedToWindow) return@post
                val currentChild = if (child.parent == parent) {
                    child
                } else {
                    parent.findContainingItemView(child)
                } ?: return@post

                val currentDy = currentChild.top - parent.paddingTop
                if (abs(currentDy) > 2) {
                    parent.smoothScrollBy(0, currentDy)
                }
            }
        }
        return true
    }

    override fun requestChildRectangleOnScreen(
        parent: RecyclerView,
        child: View,
        rect: Rect,
        immediate: Boolean,
        focusedChildVisible: Boolean,
    ): Boolean {
        val row = directChild(parent, child)
        return alignChildToShelf(parent, row, immediate) || super.requestChildRectangleOnScreen(
            parent,
            child,
            rect,
            immediate,
            focusedChildVisible,
        )
    }

    override fun onRequestChildFocus(
        parent: RecyclerView,
        state: RecyclerView.State,
        child: View,
        focused: View,
    ): Boolean {
        if (!state.isPreLayout && alignChildToShelf(parent, child, immediate = false)) {
            return true
        }
        return super.onRequestChildFocus(parent, state, child, focused)
    }
}