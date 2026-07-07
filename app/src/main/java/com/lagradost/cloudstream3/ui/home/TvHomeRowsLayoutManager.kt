package com.lagradost.cloudstream3.ui.home

import android.content.Context
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * Stable focus-aligned layout manager for the Android TV home rows.
 *
 * The master RecyclerView stays full height so RecyclerView can still recycle rows normally.
 * The visual shelf is created with top/bottom padding. When focus moves to another row,
 * the row is placed at parent.paddingTop immediately. Horizontal card movement inside the
 * same row does not trigger smooth vertical scrolling, which prevents the half-row jump.
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
        return abs(row.top - parent.paddingTop) <= 6
    }

    fun alignRowAtPosition(
        parent: RecyclerView,
        adapterPosition: Int,
        immediate: Boolean = true,
    ): Boolean {
        if (adapterPosition == RecyclerView.NO_POSITION) return false
        val align = {
            if (parent.isAttachedToWindow) {
                parent.stopScroll()
                scrollToPositionWithOffset(adapterPosition, parent.paddingTop)
            }
        }

        if (immediate && !parent.isComputingLayout) {
            align()
        } else {
            parent.post { align() }
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
                    alignRowAtPosition(parent, position, immediate = false)
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
                alignRowAtPosition(parent, position, immediate = immediate)
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