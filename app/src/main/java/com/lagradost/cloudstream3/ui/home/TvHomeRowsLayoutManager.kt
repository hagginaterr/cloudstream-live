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
 * Important rule:
 * Do not move the parent RecyclerView synchronously from focus/layout callbacks.
 * Some Android TV RecyclerView builds crash with:
 * "Layout state should be one of 100 but it is 10".
 *
 * Row alignment is posted to the next frame so Home can safely restore focus
 * after returning from player/detail screens.
 */
class TvHomeRowsLayoutManager(
    context: Context,
) : LinearLayoutManager(context, RecyclerView.VERTICAL, false) {

    private var pendingAlignPosition: Int = RecyclerView.NO_POSITION

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

    private fun findFocusedRowPosition(parent: RecyclerView): Int {
        val focused = parent.findFocus() ?: return RecyclerView.NO_POSITION
        val row = parent.findContainingItemView(focused) ?: return RecyclerView.NO_POSITION
        return parent.getChildAdapterPosition(row)
    }

    private fun postAlignRowAtPosition(
        parent: RecyclerView,
        adapterPosition: Int,
    ): Boolean {
        if (adapterPosition == RecyclerView.NO_POSITION) return false
        pendingAlignPosition = adapterPosition

        parent.post {
            if (!parent.isAttachedToWindow) return@post
            if (parent.isComputingLayout) {
                postAlignRowAtPosition(parent, adapterPosition)
                return@post
            }

            val positionToAlign = if (pendingAlignPosition != RecyclerView.NO_POSITION) {
                pendingAlignPosition
            } else {
                adapterPosition
            }

            pendingAlignPosition = RecyclerView.NO_POSITION

            if (positionToAlign == RecyclerView.NO_POSITION) return@post
            if ((parent.adapter?.itemCount ?: 0) <= positionToAlign) return@post

            parent.stopScroll()

            /*
             * Offset 0 means "place the row at the start after RecyclerView
             * padding". The TV shelf is already created with top padding, so
             * passing parent.paddingTop here would push the row too low.
             */
            scrollToPositionWithOffset(positionToAlign, 0)
        }

        return true
    }

    fun alignRowAtPosition(
        parent: RecyclerView,
        adapterPosition: Int,
        immediate: Boolean = true,
    ): Boolean {
        // "immediate" is kept for compatibility with existing call sites.
        return postAlignRowAtPosition(parent, adapterPosition)
    }

    fun alignFocusedRowNow(parent: RecyclerView): Boolean {
        val position = findFocusedRowPosition(parent)
        return postAlignRowAtPosition(parent, position)
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
                    postAlignRowAtPosition(parent, position)
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
                postAlignRowAtPosition(parent, position)
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
