package com.lagradost.cloudstream3.ui.home

import androidx.recyclerview.widget.LinearLayoutManager
import android.view.KeyEvent
import java.net.URLDecoder
import androidx.recyclerview.widget.RecyclerView
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.os.Build
import android.graphics.Shader
import android.graphics.RenderEffect
import androidx.preference.PreferenceManager
import androidx.viewbinding.ViewBinding
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.databinding.HomeRemoveGridBinding
import com.lagradost.cloudstream3.databinding.HomeRemoveGridExpandedBinding
import com.lagradost.cloudstream3.databinding.HomeResultGridBinding
import com.lagradost.cloudstream3.databinding.HomeResultGridExpandedBinding
import com.lagradost.cloudstream3.ui.BaseAdapter
import com.lagradost.cloudstream3.ui.BaseDiffCallback
import com.lagradost.cloudstream3.ui.ViewHolderState
import com.lagradost.cloudstream3.ui.newSharedPool
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_LOAD
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.ui.search.SearchResultBuilder
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.UIHelper.isBottomLayout
import com.lagradost.cloudstream3.utils.UIHelper.toPx
import android.widget.TextView
import com.lagradost.cloudstream3.databinding.HomeResultGridTvBinding
import java.util.Locale

object TwitchHomeFocusedBackground {
    private fun findPosterImage(view: View): ImageView? {
        if (view is ImageView && view.drawable != null) return view

        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findPosterImage(view.getChildAt(index))?.let { return it }
            }
        }

        return null
    }

    fun update(focusedView: View) {
        if (!isLayout(TV or EMULATOR)) return

        val source = findPosterImage(focusedView) ?: return
        val sourceDrawable = source.drawable ?: return
        val background = focusedView.rootView
            ?.findViewById<ImageView>(R.id.home_twitch_focus_background)
            ?: return

        background.visibility = View.VISIBLE
        background.alpha = 0.55f
        background.scaleType = ImageView.ScaleType.CENTER_CROP

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            background.setRenderEffect(
                RenderEffect.createBlurEffect(
                    18f,
                    18f,
                    Shader.TileMode.CLAMP,
                ),
            )
        }

        background.setImageDrawable(
            sourceDrawable.constantState
                ?.newDrawable()
                ?.mutate()
                ?: sourceDrawable,
        )
    }
}
object TwitchTvRowPager {
    @Suppress("UNUSED_PARAMETER")
    fun alignFocusedRow(view: View) = Unit
}
class HomeScrollViewHolderState(view: ViewBinding) : ViewHolderState<Boolean>(view) {
    // very shitty that we cant store the state when the view clears,
    // but this is because the focus clears before the view is removed
    // so we have to manually store it
    var wasFocused: Boolean = false
    override fun save(): Boolean = wasFocused
    override fun restore(state: Boolean) {
        if (state) {
            wasFocused = false
            // only refocus if tv
            if (isLayout(TV)) {
                itemView.requestFocus()
            }
        }
    }
}

class ResumeItemAdapter(
    nextFocusUp: Int? = null,
    nextFocusDown: Int? = null,
    clickCallback: (SearchClickCallback) -> Unit,
    private val removeCallback: (View) -> Unit,
) : HomeChildItemAdapter(
    id = "resumeAdapter".hashCode(),
    nextFocusUp = nextFocusUp,
    nextFocusDown = nextFocusDown,
    clickCallback = clickCallback
) {
    // As there is no popup on TV we instead use the footer to clear
    override val footers = if (isLayout(TV or EMULATOR)) 1 else 0

    override fun onCreateFooter(parent: ViewGroup): ViewHolderState<Boolean> {
        val expanded = parent.context.isBottomLayout()
        val inflater = LayoutInflater.from(parent.context)
        val binding = if (expanded) HomeRemoveGridExpandedBinding.inflate(
            inflater,
            parent,
            false
        ) else HomeRemoveGridBinding.inflate(inflater, parent, false)
        return HomeScrollViewHolderState(binding)
    }

    override fun onClearView(holder: ViewHolderState<Boolean>) {
        // Clear the image, idk if this saves ram or not, but I guess?
        clearImage(holder.view.root.findViewById(R.id.imageView))
    }

    override fun onBindFooter(holder: ViewHolderState<Boolean>) {
        this.applyBinding(holder, false)
        when (val binding = holder.view) {
            is HomeRemoveGridBinding -> {
                updateLayoutParms(binding.backgroundCard, setWidth, setHeight)
            }

            is HomeRemoveGridExpandedBinding -> {
                updateLayoutParms(binding.backgroundCard, setWidth, setHeight)
            }
        }
        holder.itemView.apply {
            if (isLayout(TV)) {
                isFocusableInTouchMode = true
                isFocusable = true
            }
            nextFocusUp?.let {
                nextFocusUpId = it
            }
            nextFocusDown?.let {
                nextFocusDownId = it
            }

            setOnClickListener { v ->
                removeCallback.invoke(v ?: return@setOnClickListener)
            }
        }
    }
}

/** Remember to set `updatePosterSize` to cache the poster size,
 * otherwise the width and height is unset */
open class HomeChildItemAdapter(
    id: Int,
    var nextFocusUp: Int? = null,
    var nextFocusDown: Int? = null,
    var clickCallback: (SearchClickCallback) -> Unit,
) :
    BaseAdapter<SearchResponse, Boolean>(
        id, diffCallback = BaseDiffCallback(
            itemSame = { a, b ->
                a.url == b.url && a.name == b.name
            },
            contentSame = { a, b ->
                a == b
            })
    ) {
    var hasNext: Boolean = false
    var isHorizontal: Boolean = false
        set(value) {
            field = value
            updateCachedPosterSize()
        }

    private fun updateCachedPosterSize() {
        setWidth = if (!isHorizontal) {
            minPosterSize
        } else {
            maxPosterSize
        }
        setHeight = if (!isHorizontal) {
            maxPosterSize
        } else {
            minPosterSize
        }
    }

    init {
        updateCachedPosterSize()
    }

    protected var setWidth = 0
    protected var setHeight = 0

    override fun onCreateContent(parent: ViewGroup): ViewHolderState<Boolean> {
        val inflater = LayoutInflater.from(parent.context)
        val binding = when {
            isLayout(TV) -> HomeResultGridTvBinding.inflate(inflater, parent, false)
            parent.context.isBottomLayout() -> HomeResultGridExpandedBinding.inflate(
                inflater,
                parent,
                false
            )
            else -> HomeResultGridBinding.inflate(inflater, parent, false)
        }
        return HomeScrollViewHolderState(binding)
    }

    companion object {
        // The vast majority of the lag comes from creating the view
        // This simply shares the views between all HomeChildItemAdapter
        val sharedPool =
            newSharedPool { setMaxRecycledViews(CONTENT, 20) }

        var minPosterSize: Int = 0
        var maxPosterSize: Int = 0

        fun updatePosterSize(context: Context, value: Int? = null) {
            val scale = value ?: PreferenceManager.getDefaultSharedPreferences(context)
                ?.getInt(context.getString(R.string.poster_size_key), 0) ?: 0
            // Scale by +10% per step
            val mul = 1.0f + scale * 0.1f
            minPosterSize = (114.toPx.toFloat() * mul).toInt()
            maxPosterSize = (180.toPx.toFloat() * mul).toInt()
        }

        fun updateLayoutParms(layout: FrameLayout, width: Int, height: Int) {
            val params = layout.layoutParams
            if (params.height == height && params.width == width) return

            params.width = width
            params.height = height

            layout.layoutParams = params
        }
    }

    // Twitch-style TV home cards
    private data class TwitchTvMetadata(
        val title: String,
        val streamer: String?,
        val category: String?,
        val viewers: String?,
    )

    private fun configureTwitchTvCardSize(root: View, backgroundCard: FrameLayout) {
        if (!isLayout(TV)) {
            updateLayoutParms(backgroundCard, setWidth, setHeight)
            return
        }

        val resize = resize@{
            val parentRecycler = root.parent as? RecyclerView
            val availableWidth = if (parentRecycler != null && parentRecycler.width > 0) {
                parentRecycler.width - parentRecycler.paddingLeft - parentRecycler.paddingRight
            } else {
                root.resources.displayMetrics.widthPixels - 128.toPx
            }

            if (availableWidth <= 0) return@resize

            val gap = 18.toPx
            val cardWidth = ((availableWidth - gap * 3) / 4).coerceAtLeast(220.toPx)
            val thumbnailHeight = (cardWidth * 9f / 16f).toInt()
            val rootParams = root.layoutParams ?: return@resize

            if (rootParams.width != cardWidth) {
                rootParams.width = cardWidth
                root.layoutParams = rootParams
            }

            val marginParams = root.layoutParams as? ViewGroup.MarginLayoutParams
            if (marginParams != null && marginParams.marginEnd != gap) {
                marginParams.marginEnd = gap
                root.layoutParams = marginParams
            }

            updateLayoutParms(backgroundCard, cardWidth, thumbnailHeight)
        }

        resize()
        root.post { resize() }
    }

    private fun bindTwitchTvMetadata(itemView: View, item: SearchResponse) {
        if (!isLayout(TV)) return

        val titleText: TextView? = itemView.findViewById(R.id.imageText)
        val streamerText: TextView? = itemView.findViewById(R.id.streamer_text)
        val categoryText: TextView? = itemView.findViewById(R.id.category_text)
        val viewerCountText: TextView? = itemView.findViewById(R.id.viewer_count_text)
        if (titleText == null && streamerText == null && categoryText == null && viewerCountText == null) return

        val metadata = item.toTwitchTvMetadata()
        titleText.setVisibleTvText(metadata.title)
        streamerText.setVisibleTvText(metadata.streamer)
        categoryText.setVisibleTvText(metadata.category)
        viewerCountText.setVisibleTvText(metadata.viewers)
    }

    private fun TextView?.setVisibleTvText(value: String?) {
        if (this == null) return
        val cleanValue = value?.cleanTvText().orEmpty()
        text = cleanValue
        visibility = if (cleanValue.isBlank()) View.GONE else View.VISIBLE
    }

        private fun SearchResponse.toTwitchTvMetadata(): TwitchTvMetadata {
        val nameParts = name
            .split('\n')
            .map { it.cleanTvText() }
            .filter { it.isNotBlank() }

        val langText = firstTextField("lang", "subtitle", "subTitle")
        val langParts = langText
            ?.split(" - ")
            ?.map { it.cleanTvText() }
            ?.filter { it.isNotBlank() }
            .orEmpty()

        val urlTitle = urlQueryParam("cs_stream_title", "cs_title", "cs_clip_title")
        val urlStreamer = urlQueryParam("cs_streamer_name", "cs_broadcaster_name")
        val urlCategory = urlQueryParam("cs_category", "cs_game_name", "cs_game")
        val urlViewers = urlQueryParam("cs_viewers", "cs_viewer_count", "cs_view_count")

        val title = urlTitle ?: firstTextField(
            "streamTitle",
            "stream_title",
            "title",
            "streamName",
            "stream_name",
            "status",
            "description",
        ) ?: when {
            nameParts.isNotEmpty() -> nameParts[0]
            else -> name.cleanTvText()
        }

        val streamer = urlStreamer ?: firstTextField(
            "streamerName",
            "streamer_name",
            "channelName",
            "channel_name",
            "channel",
            "userName",
            "user_name",
            "broadcasterName",
            "broadcaster_name",
            "displayName",
            "display_name",
            "login",
        ) ?: when {
            nameParts.size >= 2 -> nameParts[1]
            else -> streamerNameFromUrl(url)
        }

        val categoryFromLang = langParts.firstOrNull { part ->
            !part.contains("view", ignoreCase = true) &&
                !part.equals(streamer, ignoreCase = true) &&
                !part.equals(title, ignoreCase = true)
        }
        val category = urlCategory ?: firstTextField(
            "categoryName",
            "category_name",
            "gameName",
            "game_name",
            "category",
            "game",
            "directory",
            "section",
        ) ?: categoryFromLang ?: when {
            nameParts.size >= 3 -> nameParts[2]
            else -> null
        }

        val viewersFromLang = langParts.firstOrNull { it.contains("view", ignoreCase = true) }
        val viewers = urlViewers ?: firstField(
            "viewerCount",
            "viewer_count",
            "viewCount",
            "view_count",
            "viewers",
            "viewersText",
            "viewers_text",
            "viewerText",
            "viewer_text",
            "liveViewers",
            "live_viewers",
            "watching",
        ).asViewerText() ?: viewersFromLang

        return TwitchTvMetadata(
            title = title.cleanTvText(),
            streamer = streamer?.cleanTvText()?.takeUnless { it.equals(title, ignoreCase = true) },
            category = category?.cleanTvText(),
            viewers = viewers?.cleanTvText(),
        )
    }

    private fun SearchResponse.urlQueryParam(vararg names: String): String? {
        val query = url.substringAfter("?", "")
        if (query.isBlank() || query == url) return null

        val wanted = names.toSet()
        return query
            .split("&")
            .asSequence()
            .mapNotNull { part ->
                val key = part.substringBefore("=", "")
                if (key !in wanted) return@mapNotNull null
                part.substringAfter("=", "")
                    .decodeTwitchTvQueryParam()
                    .cleanTvText()
                    .takeIf { it.isNotBlank() }
            }
            .firstOrNull()
    }

    private fun String.decodeTwitchTvQueryParam(): String {
        return runCatching { URLDecoder.decode(this, "UTF-8") }.getOrDefault(this)
    }


    private fun SearchResponse.firstTextField(vararg names: String): String? {
        return firstField(*names).asCleanText()
    }

    private fun SearchResponse.firstField(vararg names: String): Any? {
        for (name in names) {
            var current: Class<*>? = javaClass
            while (current != null) {
                val fieldValue = runCatching {
                    current.getDeclaredField(name).apply { isAccessible = true }.get(this)
                }.getOrNull()
                if (fieldValue != null) return fieldValue
                current = current.superclass
            }

            val getter = name.toGetterName()
            val methodValue = runCatching {
                javaClass.methods.firstOrNull { method ->
                    method.parameterTypes.isEmpty() && (method.name == name || method.name == getter)
                }?.invoke(this)
            }.getOrNull()
            if (methodValue != null) return methodValue
        }
        return null
    }

    private fun String.toGetterName(): String {
        val camel = split('_')
            .filter { it.isNotBlank() }
            .joinToString("") { part ->
                part.substring(0, 1).uppercase(Locale.US) + part.substring(1)
            }
        return "get$camel"
    }

    private fun Any?.asCleanText(): String? {
        return when (this) {
            null -> null
            is String -> cleanTvText()
            is Number -> toString()
            else -> toString().cleanTvText()
        }?.takeIf { it.isNotBlank() }
    }

    private fun Any?.asViewerText(): String? {
        return when (this) {
            null -> null
            is Number -> toLong().formatViewerCount()
            is String -> {
                val cleaned = cleanTvText()
                when {
                    cleaned.isBlank() -> null
                    cleaned.contains("view", ignoreCase = true) -> cleaned
                    else -> cleaned.replace(",", "").toLongOrNull()?.formatViewerCount() ?: "$cleaned viewers"
                }
            }
            else -> toString().asViewerText()
        }
    }

    private fun Long.formatViewerCount(): String {
        val compact = when {
            this >= 1_000_000L -> String.format(Locale.US, "%.1fM", this / 1_000_000.0).replace(".0M", "M")
            this >= 1_000L -> String.format(Locale.US, "%.1fK", this / 1_000.0).replace(".0K", "K")
            else -> toString()
        }
        return "$compact ${if (this == 1L) "viewer" else "viewers"}"
    }

    private fun streamerNameFromUrl(value: String): String? {
        val lastSegment = value
            .substringBefore('?')
            .substringBefore('#')
            .trimEnd('/')
            .substringAfterLast('/')
            .cleanTvText()
            .replace("%20", " ")
        return lastSegment.takeIf { candidate ->
            candidate.isNotBlank() && candidate.length <= 48 && !candidate.contains(".")
        }
    }

    private fun String.cleanTvText(): String {
        return trim().replace(Regex("\\s+"), " ")
    }
    // End Twitch-style TV home cards
    // Explicit TV horizontal card focus navigation
    private fun configureTwitchTvHorizontalFocus(itemView: View) {
        if (!isLayout(TV)) return

        itemView.setOnKeyListener { view, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val rowRecycler = view.parent as? RecyclerView ?: return@setOnKeyListener false
            val currentPosition = rowRecycler.getChildAdapterPosition(view)
            if (currentPosition == RecyclerView.NO_POSITION) return@setOnKeyListener false

            val itemCount = rowRecycler.adapter?.itemCount ?: return@setOnKeyListener false
            val targetPosition = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (currentPosition <= 0) return@setOnKeyListener false
                    currentPosition - 1
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (currentPosition >= itemCount - 1) return@setOnKeyListener false
                    currentPosition + 1
                }
                else -> return@setOnKeyListener false
            }

            focusTwitchTvCard(rowRecycler, targetPosition, attempt = 0)
            true
        }
    }

    private fun focusTwitchTvCard(
        rowRecycler: RecyclerView,
        targetPosition: Int,
        attempt: Int,
    ) {
        rowRecycler.stopScroll()

        val visibleTarget = rowRecycler
            .findViewHolderForAdapterPosition(targetPosition)
            ?.itemView
        if (visibleTarget?.requestFocus() == true) return

        (rowRecycler.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(targetPosition, rowRecycler.paddingLeft)
            ?: rowRecycler.scrollToPosition(targetPosition)

        rowRecycler.post {
            val target = rowRecycler
                .findViewHolderForAdapterPosition(targetPosition)
                ?.itemView
            if (target?.requestFocus() == true) return@post

            if (attempt < 4) {
                rowRecycler.postDelayed({
                    focusTwitchTvCard(rowRecycler, targetPosition, attempt + 1)
                }, 45L)
            }
        }
    }
    // End explicit TV horizontal card focus navigation
    protected fun applyBinding(holder: ViewHolderState<Boolean>, isFirstItem: Boolean) {
        when (val binding = holder.view) {
            is HomeResultGridBinding -> {
                updateLayoutParms(binding.backgroundCard, setWidth, setHeight)
            }

            is HomeResultGridExpandedBinding -> {
                updateLayoutParms(binding.backgroundCard, setWidth, setHeight)

                if (isFirstItem) { // to fix tv
                    binding.backgroundCard.nextFocusLeftId = R.id.nav_rail_view
                }
            }

            is HomeResultGridTvBinding -> {
                configureTwitchTvCardSize(binding.root, binding.backgroundCard)

                if (isFirstItem) {
                    binding.root.nextFocusLeftId = R.id.nav_rail_view
                    binding.backgroundCard.nextFocusLeftId = R.id.nav_rail_view
                }
            }
        }
    }

    override fun onBindContent(
        holder: ViewHolderState<Boolean>,
        item: SearchResponse,
        position: Int
    ) {
        applyBinding(holder, position == 0)

        SearchResultBuilder.bind(
            clickCallback = { click ->
                // ok, so here we hijack the callback to fix the focus
                when (click.action) {
                    SEARCH_ACTION_LOAD -> (holder as? HomeScrollViewHolderState)?.wasFocused = true
                }
                clickCallback(click)
            },
            item,
            position,
            holder.itemView,
            nextFocusUp,
            nextFocusDown
        )

        // TwitchOfficialFocusedBackgroundPatch: update the TV home background from the focused thumbnail.
        if (isLayout(TV or EMULATOR)) {
            holder.itemView.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    TwitchHomeFocusedBackground.update(view)
                }
            }

            if (holder.itemView.hasFocus()) {
                TwitchHomeFocusedBackground.update(holder.itemView)
            }
        }
        bindTwitchTvMetadata(holder.itemView, item)
            configureTwitchTvHorizontalFocus(holder.itemView)

        holder.itemView.tag = position
        TvHomeFocusController.configureCard(holder.itemView)
    }
}
