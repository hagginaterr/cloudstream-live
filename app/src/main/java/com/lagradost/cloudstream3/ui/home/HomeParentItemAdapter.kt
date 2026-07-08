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
import com.lagradost.cloudstream3.SearchResponse
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
import java.lang.ref.WeakReference

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
        itemSame = { a, b ->
            if (TwitchLiveNowRowController.isLiveNowRowName(a.list.name) &&
                TwitchLiveNowRowController.isLiveNowRowName(b.list.name)
            ) {
                TwitchLiveNowRowController.rowKey(a.list.name) == TwitchLiveNowRowController.rowKey(b.list.name)
            } else {
                a.list.name == b.list.name
            }
        },
        contentSame = { a, b ->
            a.list.name == b.list.name && a.list.list == b.list.list
        },
    ),
) {
    companion object {
        val sharedPool = newSharedPool {
            setMaxRecycledViews(CONTENT, 4)
        }
    }

    private data class LiveNowRowState(
        val rowName: String,
        val items: List<SearchResponse>,
    )

    private val liveNowRows = linkedMapOf<String, LiveNowRowState>()
    private val liveNowChildAdapters = linkedMapOf<String, WeakReference<HomeChildItemAdapter>>()
    private val liveNowChildRecyclerViews = linkedMapOf<String, WeakReference<RecyclerView>>()
    private val liveNowRowBindings = linkedMapOf<String, WeakReference<HomepageParentBinding>>()

    private fun liveNowDisplayItem(
        item: HomeViewModel.ExpandableHomepageList,
    ): HomeViewModel.ExpandableHomepageList {
        if (!TwitchLiveNowRowController.isLiveNowRowName(item.list.name)) return item
        val state = liveNowRows[TwitchLiveNowRowController.rowKey(item.list.name)] ?: return item
        return item.copy(
            list = item.list.copy(
                name = state.rowName,
                list = state.items.toMutableList(),
            ),
        )
    }

    private fun rememberLiveNowRow(
        rowName: String,
        binding: HomepageParentBinding,
        adapter: HomeChildItemAdapter?,
    ) {
        if (!TwitchLiveNowRowController.isLiveNowRowName(rowName) || adapter == null) return

        val key = TwitchLiveNowRowController.rowKey(rowName)
        liveNowChildAdapters[key] = WeakReference(adapter)
        liveNowChildRecyclerViews[key] = WeakReference(binding.homeChildRecyclerview)
        liveNowRowBindings[key] = WeakReference(binding)
    }

    fun updateLiveNowRows(rows: Collection<HomeViewModel.ExpandableHomepageList>): Boolean {
        var updated = false
        rows.forEach { row ->
            if (TwitchLiveNowRowController.isLiveNowRowName(row.list.name)) {
                updated = updateLiveNowItems(row.list.name, row.list.list) || updated
            }
        }
        return updated
    }

    fun updateLiveNowItems(rowName: String, newItems: List<SearchResponse>): Boolean {
        if (!TwitchLiveNowRowController.isLiveNowRowName(rowName)) return false

        val key = TwitchLiveNowRowController.rowKey(rowName)
        val rowRecycler = liveNowChildRecyclerViews[key]?.get()
        val adapter = liveNowChildAdapters[key]?.get()
            ?: (rowRecycler?.adapter as? HomeChildItemAdapter)
        val rowHasFocus = rowRecycler?.hasFocus() == true
        val previousFocusedPosition = if (rowHasFocus) {
            TwitchLiveNowRowController.focusedAdapterPosition(rowRecycler)
        } else {
            RecyclerView.NO_POSITION
        }
        val previousFocusedKey = adapter
            ?.immutableCurrentList
            ?.getOrNull(previousFocusedPosition)
            ?.let { TwitchLiveNowRowController.stableStreamKey(it) }

        val displayItems = if (rowHasFocus && adapter != null) {
            TwitchLiveNowRowController.orderedForFocusedRow(adapter.immutableCurrentList, newItems)
        } else {
            newItems
        }

        liveNowRows[key] = LiveNowRowState(rowName, displayItems)
        liveNowRowBindings[key]?.get()?.homeChildMoreInfo?.text = rowName

        if (adapter == null) return true

        adapter.submitList(displayItems) {
            if (rowHasFocus) {
                TwitchLiveNowRowController.restoreFocusAfterDiff(
                    rowRecycler,
                    adapter,
                    previousFocusedKey,
                    previousFocusedPosition,
                )
            }
        }
        return true
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

override fun onUpdateContent(
        holder: ViewHolderState<Bundle>,
        item: HomeViewModel.ExpandableHomepageList,
        position: Int,
    ) {
        val binding = holder.view
        if (binding !is HomepageParentBinding) return
        val displayItem = liveNowDisplayItem(item)
        val info = displayItem.list
        configureTwitchTvRow(binding)
        binding.homeChildMoreInfo.text = info.name
        (binding.homeChildRecyclerview.adapter as? HomeChildItemAdapter)?.apply {
            isHorizontal = info.isHorizontalImages
            hasNext = displayItem.hasNext
            submitList(info.list)
            rememberLiveNowRow(info.name, binding, this)
        }
    }

    private fun configureTwitchTvRow(binding: HomepageParentBinding) {
        TvHomeFocusController.configureRow(binding.root, binding.homeChildRecyclerview)
    }        // TwitchTvVerticalFocusPatch: Up/Down should move only between content rows.
    // It should never leak to the side rail. Only Left from the first card may do that.
    override fun onBindContent(
        holder: ViewHolderState<Bundle>,
        item: HomeViewModel.ExpandableHomepageList,
        position: Int,
    ) {
        val startFocus = R.id.navigation_home
        val endFocus = FOCUS_SELF
        val binding = holder.view
        if (binding !is HomepageParentBinding) return
        configureTwitchTvRow(binding)

        val displayItem = liveNowDisplayItem(item)
        val info = displayItem.list
        binding.apply {
            val currentAdapter = homeChildRecyclerview.adapter as? HomeChildItemAdapter
            if (currentAdapter == null) {
                homeChildRecyclerview.setRecycledViewPool(HomeChildItemAdapter.sharedPoolFor(homeChildRecyclerview.context))
                homeChildRecyclerview.adapter = HomeChildItemAdapter(
                    id = id + position + 100,
                    clickCallback = clickCallback,
                    nextFocusUp = homeChildRecyclerview.nextFocusUpId,
                    nextFocusDown = homeChildRecyclerview.nextFocusDownId,
                ).apply {
                    isHorizontal = info.isHorizontalImages
                    hasNext = displayItem.hasNext
                    submitList(info.list)
                }
            } else {
                currentAdapter.apply {
                    isHorizontal = info.isHorizontalImages
                    hasNext = displayItem.hasNext
                    this.clickCallback = this@ParentItemAdapter.clickCallback
                    nextFocusUp = homeChildRecyclerview.nextFocusUpId
                    nextFocusDown = homeChildRecyclerview.nextFocusDownId
                    if (TwitchLiveNowRowController.isLiveNowRowName(info.name)) {
                        submitList(info.list)
                    } else {
                        submitIncomparableList(info.list)
                    }
                }
            }

            rememberLiveNowRow(
                info.name,
                binding,
                homeChildRecyclerview.adapter as? HomeChildItemAdapter,
            )

            homeChildRecyclerview.setLinearListLayout(
                isHorizontal = true,
                nextLeft = startFocus,
                nextRight = endFocus,
            )
            // TwitchTvFocusReapplyAfterLinearLayout
            configureTwitchTvRow(binding)

            homeChildMoreInfo.text = info.name


            homeChildRecyclerview.clearOnScrollListeners()
            homeChildRecyclerview.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                var expandCount = 0
                val name = info.name

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
                    moreInfoClickCallback.invoke(displayItem)
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