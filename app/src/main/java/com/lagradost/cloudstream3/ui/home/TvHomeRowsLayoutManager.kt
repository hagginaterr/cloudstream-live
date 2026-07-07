package com.lagradost.cloudstream3.ui.home

import android.content.Context
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * Stable TV shelf layout manager for the home rows.
 *
 * Important detail: LinearLayoutManager.scrollToPositionWithOffset() measures the
 * offset from the start after RecyclerView padding. Because the home shelf is
 * created with top padding, the correct offset is 0, not parent.paddingTop.
 * Passing parent.paddingTop double-offsets the target row and hides it below the shelf.
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

    private fun isAligned(parent: RecyclerView, row: View): Boolean {
        return abs(row.top - parent.paddingTop) <= 4
    }

    private fun alignAttachedRow(parent: RecyclerView, row: View): Boolean {
        if (parent.height <= 0) return false
        val dy = row.top - parent.paddingTop
        if (abs(dy) <= 4) return false
        parent.stopScroll()
        parent.scrollBy(0, dy)
        return true
    }

    private fun scrollRowToShelf(parent: RecyclerView, adapterPosition: Int) {
        if (adapterPosition == RecyclerView.NO_POSITION) return
        parent.stopScroll()
        scrollToPositionWithOffset(adapterPosition, 0)
    }

    fun alignRowAtPosition(
        parent: RecyclerView,
        adapterPosition: Int,
        immediate: Boolean = true,
    ): Boolean {
        if (adapterPosition == RecyclerView.NO_POSITION) return false

        val attached = parent.findViewHolderForAdapterPosition(adapterPosition)?.itemView
        if (attached != null) {
            alignAttachedRow(parent, attached)
            return true
        }

        if (immediate && !parent.isComputingLayout) {
            scrollRowToShelf(parent, adapterPosition)
        } else {
            parent.post {
                if (parent.isAttachedToWindow) {
                    scrollRowToShelf(parent, adapterPosition)
                }
            }
        }
        return true
    }

    fun alignFocusedRowNow(parent: RecyclerView): Boolean {
        val focused = parent.findFocus() ?: return false
        val row = parent.findContainingItemView(focused) ?: return false
        val position = parent.getChildAdapterPosition(row)
        return alignRowAtPosition(parent, position, immediate = true)
    }

    override fun onRequestChildFocus(
        parent: RecyclerView,
        state: RecyclerView.State,
        child: View,
        focused: View?,
    ): Boolean {
        if (!state.isPreLayout) {
            val row = directRow(parent, child)
            val position = parent.getChildAdapterPosition(row)
            if (position != RecyclerView.NO_POSITION) {
                if (!isAligned(parent, row)) {
                    alignAttachedRow(parent, row)
                }
                return true
            }
        }
        return super.onRequestChildFocus(parent, state, child, focused)
    }

    override fun requestChildRectangleOnScreen(
        parent: RecyclerView,
        child: View,
        rect: Rect,
        immediate: Boolean,
        focusedChildVisible: Boolean,
    ): Boolean {
        val row = directRow(parent, child)
        val position = parent.getChildAdapterPosition(row)
        if (position != RecyclerView.NO_POSITION) {
            if (!isAligned(parent, row)) {
                alignAttachedRow(parent, row)
            }
            return true
        }
        return super.requestChildRectangleOnScreen(
            parent,
            child,
            rect,
            immediate,
            focusedChildVisible,
        )
    }

    override fun supportsPredictiveItemAnimations(): Boolean = false
}