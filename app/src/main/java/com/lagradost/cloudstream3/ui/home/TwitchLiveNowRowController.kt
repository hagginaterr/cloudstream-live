package com.lagradost.cloudstream3.ui.home

import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.SearchResponse
import java.net.URLDecoder
import java.util.Locale

/**
 * Keeps the Twitch Live Now row stable while its stream data changes.
 *
 * The home provider can still fetch and cache data, but visual refreshes should target the
 * already-mounted Live Now child adapter instead of replacing the parent home rows.
 */
object TwitchLiveNowRowController {
    private const val LIVE_NOW_ROW_KEY = "live now"

    private val userIdQueryNames = setOf(
        "cs_streamer_user_id",
        "cs_user_id",
        "user_id",
        "broadcaster_id",
        "channel_id",
    )

    private val loginQueryNames = setOf(
        "cs_streamer_login",
        "cs_broadcaster_login",
        "login",
        "user_login",
        "channel",
        "channel_name",
        "broadcaster_login",
    )

    private val ignoredTwitchPathSegments = setOf(
        "directory",
        "downloads",
        "login",
        "logout",
        "settings",
        "signup",
        "videos",
    )

    val streamDiffCallback = object : DiffUtil.ItemCallback<SearchResponse>() {
        override fun areItemsTheSame(oldItem: SearchResponse, newItem: SearchResponse): Boolean {
            return stableStreamKey(oldItem) == stableStreamKey(newItem)
        }

        override fun areContentsTheSame(oldItem: SearchResponse, newItem: SearchResponse): Boolean {
            return oldItem == newItem
        }

        override fun getChangePayload(oldItem: SearchResponse, newItem: SearchResponse): Any? {
            return Any()
        }
    }

    fun rowKey(rowName: String): String {
        val cleanName = rowName.trim()
        val lower = cleanName.lowercase(Locale.ROOT)
        val lastCheckedIndex = lower.indexOf(" - last checked")
        val withoutTimestamp = if (lastCheckedIndex >= 0) {
            cleanName.substring(0, lastCheckedIndex)
        } else {
            cleanName
        }

        return withoutTimestamp.trim().lowercase(Locale.ROOT)
    }

    fun isLiveNowRowName(rowName: String): Boolean {
        return rowKey(rowName) == LIVE_NOW_ROW_KEY
    }

    fun stableStreamKey(item: SearchResponse): String {
        val url = item.url
        val isTwitchCard = isTwitchUrl(url) || hasCloudStreamTwitchMeta(url)

        if (isTwitchCard) {
            queryParam(url, userIdQueryNames)?.let { userId ->
                return "uid:${userId.lowercase(Locale.ROOT)}"
            }

            firstTextField(
                item,
                "userId",
                "user_id",
                "broadcasterId",
                "broadcaster_id",
                "channelId",
                "channel_id",
            )?.let { userId ->
                return "uid:${userId.lowercase(Locale.ROOT)}"
            }

            queryParam(url, loginQueryNames)?.let { login ->
                return "login:${login.lowercase(Locale.ROOT)}"
            }

            firstTextField(
                item,
                "login",
                "userLogin",
                "user_login",
                "channel",
                "broadcasterLogin",
                "broadcaster_login",
            )?.let { login ->
                return "login:${login.lowercase(Locale.ROOT)}"
            }

            twitchLoginFromUrl(url)?.let { login ->
                return "login:${login.lowercase(Locale.ROOT)}"
            }
        }

        stableUrl(url)?.let { stableUrl ->
            return "url:${stableUrl.lowercase(Locale.ROOT)}"
        }

        return "name:${item.name.trim().lowercase(Locale.ROOT)}"
    }

    fun orderedForFocusedRow(
        oldItems: List<SearchResponse>,
        newItems: List<SearchResponse>,
    ): List<SearchResponse> {
        if (oldItems.isEmpty() || newItems.isEmpty()) return newItems

        val newByKey = LinkedHashMap<String, SearchResponse>()
        newItems.forEach { item ->
            newByKey.putIfAbsent(stableStreamKey(item), item)
        }

        val keptInOldOrder = oldItems.mapNotNull { oldItem ->
            newByKey.remove(stableStreamKey(oldItem))
        }

        return keptInOldOrder + newByKey.values
    }

    fun focusedAdapterPosition(rowRecycler: RecyclerView?): Int {
        val recycler = rowRecycler ?: return RecyclerView.NO_POSITION
        val focusedView = recycler.findFocus() ?: recycler.focusedChild ?: return RecyclerView.NO_POSITION
        return recycler.findContainingViewHolder(focusedView)?.bindingAdapterPosition
            ?: recycler.getChildAdapterPosition(focusedView)
    }

    fun restoreFocusAfterDiff(
        rowRecycler: RecyclerView?,
        adapter: HomeChildItemAdapter,
        previousFocusedKey: String?,
        previousFocusedPosition: Int,
    ) {
        val recycler = rowRecycler ?: return
        if (previousFocusedKey == null && previousFocusedPosition == RecyclerView.NO_POSITION) return

        val itemCount = adapter.immutableCurrentList.size
        if (itemCount <= 0) return

        val keyPosition = previousFocusedKey?.let { key ->
            adapter.immutableCurrentList.indexOfFirst { stableStreamKey(it) == key }
        } ?: RecyclerView.NO_POSITION

        val targetPosition = when {
            keyPosition != RecyclerView.NO_POSITION -> keyPosition
            previousFocusedPosition != RecyclerView.NO_POSITION -> previousFocusedPosition.coerceIn(0, itemCount - 1)
            else -> return
        }

        recycler.post {
            if (!recycler.isAttachedToWindow) return@post
            requestFocusAt(recycler, targetPosition)
        }
    }

    private fun requestFocusAt(rowRecycler: RecyclerView, position: Int, attempt: Int = 0) {
        val child = rowRecycler.findViewHolderForAdapterPosition(position)?.itemView
        if (child?.requestFocus() == true) return

        (rowRecycler.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(position, rowRecycler.paddingLeft)
            ?: run {
                if (position != 0) rowRecycler.scrollToPosition(position)
            }

        rowRecycler.post {
            val target = rowRecycler.findViewHolderForAdapterPosition(position)?.itemView
            if (target?.requestFocus() == true) return@post

            if (attempt < 4) {
                rowRecycler.postDelayed({ requestFocusAt(rowRecycler, position, attempt + 1) }, 45L)
            }
        }
    }

    private fun queryParam(url: String, names: Set<String>): String? {
        val query = url.substringAfter("?", "")
        if (query.isBlank() || query == url) return null

        return query.substringBefore("#")
            .split("&")
            .asSequence()
            .mapNotNull { part ->
                val key = part.substringBefore("=", "").lowercase(Locale.ROOT)
                if (key !in names) return@mapNotNull null

                decode(part.substringAfter("=", ""))
                    .trim()
                    .takeIf { it.isNotBlank() }
            }
            .firstOrNull()
    }

    private fun twitchLoginFromUrl(url: String): String? {
        val clean = url.substringBefore("?")
            .substringBefore("#")
            .trim()
            .trimEnd('/')
        val lower = clean.lowercase(Locale.ROOT)
        val marker = "twitch.tv/"
        val markerIndex = lower.indexOf(marker)
        if (markerIndex < 0) return null

        val firstSegment = clean.substring(markerIndex + marker.length)
            .substringBefore("/")
            .trim()
        val normalized = firstSegment.lowercase(Locale.ROOT)
        if (normalized in ignoredTwitchPathSegments) return null
        if (!firstSegment.matches(Regex("[A-Za-z0-9_]{2,25}"))) return null
        return firstSegment
    }

    private fun stableUrl(url: String): String? {
        return url.substringBefore("#")
            .trim()
            .trimEnd('/')
            .takeIf { it.isNotBlank() }
    }

    private fun isTwitchUrl(url: String): Boolean {
        return url.lowercase(Locale.ROOT).contains("twitch.tv")
    }

    private fun hasCloudStreamTwitchMeta(url: String): Boolean {
        return url.lowercase(Locale.ROOT).contains("cs_streamer_")
    }

    private fun firstTextField(item: SearchResponse, vararg names: String): String? {
        for (name in names) {
            var current: Class<*>? = item.javaClass
            while (current != null) {
                val fieldValue = runCatching {
                    current.getDeclaredField(name).apply { isAccessible = true }.get(item)
                }.getOrNull()
                fieldValue?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
                current = current.superclass
            }

            val getterName = name.toGetterName()
            val methodValue = runCatching {
                item.javaClass.methods.firstOrNull { method ->
                    method.parameterTypes.isEmpty() && (method.name == name || method.name == getterName)
                }?.invoke(item)
            }.getOrNull()
            methodValue?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
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

    private fun decode(value: String): String {
        return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }
}
