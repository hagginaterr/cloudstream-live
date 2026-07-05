package com.lagradost.cloudstream3.ui.home

import android.widget.LinearLayout
import android.view.Gravity
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.HomepageParentBinding
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.BaseAdapter
import com.lagradost.cloudstream3.ui.BaseDiffCallback
import com.lagradost.cloudstream3.ui.ViewHolderState
import com.lagradost.cloudstream3.ui.newSharedPool
import com.lagradost.cloudstream3.ui.result.FOCUS_SELF
import com.lagradost.cloudstream3.ui.result.setLinearListLayout
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.ui.setRecycledViewPool
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.PHONE
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.isRecyclerScrollable
import recloudstream.twitchlivefavorites.TwitchHomeRefreshFocus
import android.graphics.Rect

class LoadClickCallback(
    val action: Int = 0,
    val view: View,
    val position: Int,
    val response: LoadResponse
)

open class ParentItemAdapter(
    id: Int,
    private val clickCallback: (SearchClickCallback) -> Unit,
    private val moreInfoClickCallback: (HomeViewModel.ExpandableHomepageList) -> Unit,
    private val expandCallback: ((String) -> Unit)? = null,
) : BaseAdapter<HomeViewModel.ExpandableHomepageList, Bundle>(
    id,
    diffCallback = BaseDiffCallback(
        itemSame = { a, b -> a.list.name == b.list.name },
        contentSame = { a, b ->
            a.list.list == b.list.list
        })
) {
    companion object {
        val sharedPool =
            newSharedPool { setMaxRecycledViews(CONTENT, 4) }
    }

    data class ParentItemHolder(val binding: ViewBinding) : ViewHolderState<Bundle>(binding) {
        override fun save(): Bundle = Bundle().apply {
            val recyclerView = (binding as? HomepageParentBinding)?.homeChildRecyclerview
            putParcelable(
                "value",
                recyclerView?.layoutManager?.onSaveInstanceState()
            )
            (recyclerView?.adapter as? BaseAdapter<*, *>)?.save(recyclerView)
        }

        override fun restore(state: Bundle) {
            (binding as? HomepageParentBinding)?.homeChildRecyclerview?.layoutManager?.onRestoreInstanceState(
                state.getSafeParcelable<Parcelable>("value")
            )
        }
    }

    override fun submitList(
        list: Collection<HomeViewModel.ExpandableHomepageList>?,
        commitCallback: Runnable?
    ) {
        super.submitList(list?.sortedBy { it.list.list.isEmpty() }, commitCallback)
    }

    override fun onUpdateContent(
        holder: ViewHolderState<Bundle>,
        item: HomeViewModel.ExpandableHomepageList,
        position: Int
    ) {
        val binding = holder.view
        if (binding !is HomepageParentBinding) return
        applyTwitchTvRowPageSizing(holder)
        updateTwitchTvScrollMoreIndicator(binding, position)
        (binding.homeChildRecyclerview.adapter as? HomeChildItemAdapter)?.submitList(item.list.list)
    }

    // TwitchTrueRowPagingPatch: TV home rows must be real full-screen pages.
    // PagerSnapHelper alone is not enough if each parent row still measures as wrap_content.
        // TwitchTrueRowPagingPatch: TV home rows must be real full-screen pages.
    // Keep sizing stable during bind/scroll; bottom alignment is handled by homepage_parent_tv.xml.
        // TwitchStableTvHomeRowsPatch: each TV home row is one viewport-height page.
    // Do not shift the row during focus or scroll; the XML spacer pins content to the bottom.
    // BEGIN TwitchBottomLockedHomeRowsPatch
private class TwitchBottomLockedHomeRowDecoration : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        if (!isLayout(TV or EMULATOR)) return

        val parentHeight = parent.height
            .takeIf { it > 0 }
            ?: parent.measuredHeight.takeIf { it > 0 }
            ?: return

        val rowHeight = view.measuredHeight
            .takeIf { it > 0 }
            ?: view.height.takeIf { it > 0 }
            ?: return

        outRect.left = 0
        outRect.right = 0
        outRect.bottom = 0
        outRect.top = (parentHeight - rowHeight).coerceAtLeast(0)
    }
}

private fun installTwitchBottomLockedHomeRows(parentRecycler: RecyclerView) {
    if (!isLayout(TV or EMULATOR)) return

    val installed = parentRecycler.getTag(R.id.home_master_recycler) == "twitch_bottom_locked_rows"
    parentRecycler.itemAnimator = null
    parentRecycler.clipToPadding = false
    parentRecycler.overScrollMode = View.OVER_SCROLL_NEVER
    parentRecycler.isNestedScrollingEnabled = false

    if (!installed) {
        parentRecycler.addItemDecoration(TwitchBottomLockedHomeRowDecoration())
        parentRecycler.setTag(R.id.home_master_recycler, "twitch_bottom_locked_rows")
        parentRecycler.post {
            parentRecycler.invalidateItemDecorations()
        }
    }
}
// END TwitchBottomLockedHomeRowsPatch
private fun applyTwitchTvRowPageSizing(holder: ViewHolderState<Bundle>) {
    if (!isLayout(TV or EMULATOR)) return

    val binding = holder.view as? HomepageParentBinding ?: return
    val parentRecycler = binding.root.parent as? RecyclerView
    if (parentRecycler != null) {
        installTwitchBottomLockedHomeRows(parentRecycler)
    }

    binding.root.translationY = 0f
    binding.root.layoutParams?.let { params ->
        var changed = false
        if (params.width != ViewGroup.LayoutParams.MATCH_PARENT) {
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            changed = true
        }
        if (params.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            changed = true
        }
        if (changed) {
            binding.root.layoutParams = params
        }
    }

    binding.homeChildRecyclerview.apply {
        itemAnimator = null
        isNestedScrollingEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        clipToPadding = false
        setHasFixedSize(true)
    }
}
private fun updateTwitchTvScrollMoreIndicator(binding: HomepageParentBinding, position: Int) {
    if (!isLayout(TV or EMULATOR)) return
    binding.root.findViewById<View>(R.id.tv_home_more_indicator)?.visibility =
        if (position < itemCount - 1) View.VISIBLE else View.GONE
}

    override fun onBindContent(
        holder: ViewHolderState<Bundle>,
        item: HomeViewModel.ExpandableHomepageList,
        position: Int
    ) {
        val startFocus = R.id.nav_rail_view
        val endFocus = FOCUS_SELF
        val binding = holder.view
        if (binding !is HomepageParentBinding) return
        applyTwitchTvRowPageSizing(holder)
        updateTwitchTvScrollMoreIndicator(binding, position)
        val info = item.list
        binding.apply {
            val currentAdapter = homeChildRecyclerview.adapter as? HomeChildItemAdapter
            if (currentAdapter == null) {
                homeChildRecyclerview.setRecycledViewPool(HomeChildItemAdapter.sharedPool)
                homeChildRecyclerview.adapter = HomeChildItemAdapter(
                    id = id + position + 100,
                    clickCallback = clickCallback,
                    nextFocusUp = homeChildRecyclerview.nextFocusUpId,
                    nextFocusDown = homeChildRecyclerview.nextFocusDownId,
                ).apply {
                    isHorizontal = info.isHorizontalImages
                    hasNext = item.hasNext
                    submitList(item.list.list)
                }
            } else {
                currentAdapter.apply {
                    isHorizontal = info.isHorizontalImages
                    hasNext = item.hasNext
                    this.clickCallback = this@ParentItemAdapter.clickCallback
                    nextFocusUp = homeChildRecyclerview.nextFocusUpId
                    nextFocusDown = homeChildRecyclerview.nextFocusDownId
                    submitIncomparableList(item.list.list)
                }
            }

            homeChildRecyclerview.setLinearListLayout(
                isHorizontal = true,
                nextLeft = startFocus,
                nextRight = endFocus,
            )
            homeChildMoreInfo.text = info.name
if (TwitchHomeRefreshFocus.consumeForRow(info.name)) {
                homeChildRecyclerview.post {
                    homeChildRecyclerview.scrollToPosition(0)
                    homeChildRecyclerview.post {
                        homeChildRecyclerview.findViewHolderForAdapterPosition(0)
                            ?.itemView
                            ?.requestFocus()
                    }
                }
            }
                            // TwitchTvHomeScrollStutterPatch: onBind can run repeatedly, so avoid stacking listeners.
                homeChildRecyclerview.clearOnScrollListeners()
// TwitchStableTvHomeRowsPatch: avoid stacked row listeners during recycled binds.
                homeChildRecyclerview.clearOnScrollListeners()
homeChildRecyclerview.addOnScrollListener(object :
                RecyclerView.OnScrollListener() {
                var expandCount = 0
                val name = item.list.name

                override fun onScrollStateChanged(
                    recyclerView: RecyclerView,
                    newState: Int
                ) {
                    super.onScrollStateChanged(recyclerView, newState)

                    val adapter = recyclerView.adapter
                    if (adapter !is HomeChildItemAdapter) return

                    val count = adapter.itemCount
                    val hasNext = adapter.hasNext
                    /*println(
                        "scolling ${recyclerView.isRecyclerScrollable()} ${
                            recyclerView.canScrollHorizontally(
                                1
                            )
                        }"
                    )*/
                    //!recyclerView.canScrollHorizontally(1)
                    if (!recyclerView.isRecyclerScrollable() && hasNext && expandCount != count) {
                        expandCount = count
                        expandCallback?.invoke(name)
                    }
                }
            })

            //(recyclerView.adapter as HomeChildItemAdapter).notifyDataSetChanged()
            if (isLayout(PHONE)) {
                homeChildMoreInfo.setOnClickListener {
                    moreInfoClickCallback.invoke(item)
                }
            }
        }
    }

    override fun onCreateContent(parent: ViewGroup): ParentItemHolder {
        val layoutResId = when {
            isLayout(TV) -> R.layout.homepage_parent_tv
            isLayout(EMULATOR) -> R.layout.homepage_parent_emulator
            else -> R.layout.homepage_parent
        }

        val inflater = LayoutInflater.from(parent.context)
        val binding = try {
            HomepageParentBinding.bind(inflater.inflate(layoutResId, parent, false))
        } catch (t: Throwable) {
            logError(t)
            // just in case someone forgot we don't want to crash
            HomepageParentBinding.inflate(inflater)
        }

        return ParentItemHolder(binding)
    }
}

@Suppress("DEPRECATION")
inline fun <reified T> Bundle.getSafeParcelable(key: String): T? =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) getParcelable(key)
    else getParcelable(key, T::class.java)