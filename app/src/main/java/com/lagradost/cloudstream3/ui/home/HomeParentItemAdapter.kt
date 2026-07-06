package com.lagradost.cloudstream3.ui.home

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

class LoadClickCallback(
    val action: Int = 0,
    val view: View,
    val position: Int,
    val response: LoadResponse,
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
        contentSame = { a, b -> a.list.list == b.list.list },
    ),
) {
    companion object {
        val sharedPool = newSharedPool {
            setMaxRecycledViews(CONTENT, 4)
        }
    }

    data class ParentItemHolder(val binding: ViewBinding) : ViewHolderState<Bundle>(binding) {
        override fun save(): Bundle = Bundle().apply {
            val recyclerView = (binding as? HomepageParentBinding)?.homeChildRecyclerview
            putParcelable(
                "value",
                recyclerView?.layoutManager?.onSaveInstanceState(),
            )
            (recyclerView?.adapter as? BaseAdapter<*, *>)?.save(recyclerView)
        }

        override fun restore(state: Bundle) {
            (binding as? HomepageParentBinding)?.homeChildRecyclerview?.layoutManager
                ?.onRestoreInstanceState(state.getSafeParcelable("value"))
        }
    }

    override fun submitList(
        list: Collection<HomeViewModel.ExpandableHomepageList>?,
        commitCallback: Runnable?,
    ) {
        super.submitList(list?.sortedBy { it.list.list.isEmpty() }, commitCallback)
    }

    // Match each TV parent row to the visible bottom-half RecyclerView viewport.
    // This makes vertical row navigation behave like one row per screen.
// Row height follows the visible lower shelf viewport from the master RecyclerView.
// This keeps one row on-screen without hiding the next row from D-pad focus search.
private fun configureModernTvParentRow(binding: HomepageParentBinding) {
    if (!isLayout(TV)) return

    binding.root.post {
        val parentRecycler = binding.root.parent as? RecyclerView ?: return@post
        val rowHeight = parentRecycler.height -
                parentRecycler.paddingTop -
                parentRecycler.paddingBottom
        if (rowHeight <= 0) return@post

        val params = binding.root.layoutParams
        if (params.height != rowHeight) {
            params.height = rowHeight
            binding.root.layoutParams = params
        }
        binding.root.minimumHeight = rowHeight
    }
}

override fun onUpdateContent(
        holder: ViewHolderState<Bundle>,
        item: HomeViewModel.ExpandableHomepageList,
        position: Int,
    ) {
        val binding = holder.view
        if (binding !is HomepageParentBinding) return
        configureModernTvParentRow(binding)

        configureNormalTvRow(binding)
        (binding.homeChildRecyclerview.adapter as? HomeChildItemAdapter)?.submitList(item.list.list)
    }

    private fun configureNormalTvRow(binding: HomepageParentBinding) {
        if (!isLayout(TV or EMULATOR)) return

        binding.root.translationY = 0f
        binding.root.minimumHeight = 0
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

    override fun onBindContent(
        holder: ViewHolderState<Bundle>,
        item: HomeViewModel.ExpandableHomepageList,
        position: Int,
    ) {
        val startFocus = R.id.navigation_home
        val endFocus = FOCUS_SELF
        val binding = holder.view
        if (binding !is HomepageParentBinding) return
        configureModernTvParentRow(binding)

        configureNormalTvRow(binding)

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

            homeChildRecyclerview.clearOnScrollListeners()
            homeChildRecyclerview.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                var expandCount = 0
                val name = item.list.name

                override fun onScrollStateChanged(
                    recyclerView: RecyclerView,
                    newState: Int,
                ) {
                    super.onScrollStateChanged(recyclerView, newState)

                    val adapter = recyclerView.adapter
                    if (adapter !is HomeChildItemAdapter) return

                    val count = adapter.itemCount
                    val hasNext = adapter.hasNext

                    if (!recyclerView.isRecyclerScrollable() && hasNext && expandCount != count) {
                        expandCount = count
                        expandCallback?.invoke(name)
                    }
                }
            })

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
            HomepageParentBinding.inflate(inflater)
        }

        return ParentItemHolder(binding)
    }
}

@Suppress("DEPRECATION")
inline fun <reified T : Parcelable> Bundle.getSafeParcelable(key: String): T? =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        getParcelable(key)
    } else {
        getParcelable(key, T::class.java)
    }