package com.lagradost.cloudstream3.ui.home

import android.content.Context
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * TV home rows are paged by focus, not by fling snap helpers.
 *
 * The master RecyclerView stays full-screen so D-pad Up/Down can find the next
 * row, but the visible content shelf is the padded lower portion of the view.
 * Whenever a row or one of its cards gets focus, this manager pins that row's
 * top to parent.paddingTop, which fully replaces the previous row.
 */
class TvHomeRowsLayoutManager(
    context: Context,
) : LinearLayoutManager(context, RecyclerView.VERTICAL, false) {

    private fun directRow(parent: RecyclerView, child: View): View {
        return if (child.parent == parent) {
            child
        } else {
            parent.findContainingItemView(child) ?: child
        }
    }

    private fun rowTopOffset(parent: RecyclerView, row: View): Int {
        return row.top - parent.paddingTop
    }

    private fun alignRowToShelf(
        parent: RecyclerView,
        row: View,
        immediate: Boolean,
    ): Boolean {
        if (parent.height <= 0 || !row.isAttachedToWindow) return false

        val dy = rowTopOffset(parent, row)
        if (abs(dy) <= 2) return false

        if (immediate) {
            parent.scrollBy(0, dy)
        } else {
            parent.post {
                if (!parent.isAttachedToWindow) return@post
                val currentRow = if (row.parent == parent) {
                    row
                } else {
                    parent.findContainingItemView(row)
                } ?: return@post
                val currentDy = rowTopOffset(parent, currentRow)
                if (abs(currentDy) > 2) {
                    parent.scrollBy(0, currentDy)
                }
            }
        }
        return true
    }

    fun alignRowAtPosition(
        parent: RecyclerView,
        adapterPosition: Int,
        immediate: Boolean = true,
    ): Boolean {
        val row = parent.findViewHolderForAdapterPosition(adapterPosition)?.itemView ?: return false
        return alignRowToShelf(parent, row, immediate)
    }

    fun alignFocusedRowNow(parent: RecyclerView): Boolean {
        val focused = parent.findFocus() ?: return false
        val row = parent.findContainingItemView(focused) ?: return false
        return alignRowToShelf(parent, row, immediate = true)
    }

    override fun requestChildRectangleOnScreen(
        parent: RecyclerView,
        child: View,
        rect: Rect,
        immediate: Boolean,
        focusedChildVisible: Boolean,
    ): Boolean {
        val row = directRow(parent, child)
        return alignRowToShelf(parent, row, immediate) || super.requestChildRectangleOnScreen(
            parent,
            child,
            rect,
            immediate,
            focusedChildVisible,
        )
    }

    override fun onRequestChildFocus(
        parent: RecyclerView,
        child: View,
        focused: View?,
    ): Boolean {
        val row = directRow(parent, child)
        return alignRowToShelf(parent, row, immediate = false) || super.onRequestChildFocus(
            parent,
            child,
            focused,
        )
    }

    override fun onRequestChildFocus(
        parent: RecyclerView,
        state: RecyclerView.State,
        child: View,
        focused: View?,
    ): Boolean {
        if (!state.isPreLayout) {
            val row = directRow(parent, child)
            if (alignRowToShelf(parent, row, immediate = false)) return true
        }
        return super.onRequestChildFocus(parent, state, child, focused)
    }
}