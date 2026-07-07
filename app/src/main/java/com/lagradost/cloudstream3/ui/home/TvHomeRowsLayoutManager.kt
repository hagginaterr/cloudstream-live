package com.lagradost.cloudstream3.ui.home

import android.content.Context
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * TV home rows are controlled by focus, not by snap helpers.
 *
 * The parent RecyclerView stays full-screen so focus search can find rows, while
 * padding defines the visible lower shelf. This manager always pins the focused
 * content row to parent.paddingTop and avoids RecyclerView's default rectangle
 * scrolling, which was causing half-row jumps and stutter when moving between
 * Live Now and Recent Top Clips.
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

    private fun alignRowToShelf(
        parent: RecyclerView,
        row: View,
        immediate: Boolean,
    ): Boolean {
        val adapterPosition = parent.getChildAdapterPosition(row)
        if (adapterPosition == RecyclerView.NO_POSITION) return false

        val currentOffset = row.top - parent.paddingTop
        if (abs(currentOffset) <= 2) return true

        return alignRowAtPosition(parent, adapterPosition, immediate)
    }

    fun alignRowAtPosition(
        parent: RecyclerView,
        adapterPosition: Int,
        immediate: Boolean = true,
    ): Boolean {
        if (adapterPosition == RecyclerView.NO_POSITION) return false

        val applyAlignment = {
            if (parent.isAttachedToWindow) {
                scrollToPositionWithOffset(adapterPosition, parent.paddingTop)
            }
        }

        if (immediate && !parent.isComputingLayout) {
            applyAlignment()
        } else {
            parent.post { applyAlignment() }
        }
        return true
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
        alignRowToShelf(parent, row, immediate)
        return true
    }

    override fun onRequestChildFocus(
        parent: RecyclerView,
        child: View,
        focused: View?,
    ): Boolean {
        val row = directRow(parent, child)
        alignRowToShelf(parent, row, immediate = false)
        return true
    }

    override fun onRequestChildFocus(
        parent: RecyclerView,
        state: RecyclerView.State,
        child: View,
        focused: View?,
    ): Boolean {
        if (!state.isPreLayout) {
            val row = directRow(parent, child)
            alignRowToShelf(parent, row, immediate = false)
        }
        return true
    }
}