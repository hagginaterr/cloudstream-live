package com.lagradost.cloudstream3.ui.player

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Dialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.text.Spanned
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.core.animation.addListener
import androidx.core.app.NotificationCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.text.toSpanned
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Format.NO_VALUE
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerNotificationManager
import androidx.media3.ui.PlayerNotificationManager.EXTRA_INSTANCE_ID
import androidx.media3.ui.PlayerNotificationManager.MediaDescriptionAdapter
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.getAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.getImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.getMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.getTMDbId
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.databinding.DialogOnlineSubtitlesBinding
import com.lagradost.cloudstream3.databinding.FragmentPlayerBinding
import com.lagradost.cloudstream3.databinding.PlayerSelectSourceAndSubsBinding
import com.lagradost.cloudstream3.databinding.PlayerSelectTracksBinding
import com.lagradost.cloudstream3.isAnimeOp
import com.lagradost.cloudstream3.isEpisodeBased
import com.lagradost.cloudstream3.isLiveStream
import com.lagradost.cloudstream3.isMovieType
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.observe
import com.lagradost.cloudstream3.mvvm.observeNullable
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.subtitles.AbstractSubtitleEntities
import com.lagradost.cloudstream3.subtitles.AbstractSubtitleEntities.SubtitleSearch
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.subtitleProviders
import com.lagradost.cloudstream3.ui.download.DownloadButtonSetup
import com.lagradost.cloudstream3.ui.player.CS3IPlayer.Companion.preferredAudioTrackLanguage
import com.lagradost.cloudstream3.ui.player.CustomDecoder.Companion.updateForcedEncoding
import com.lagradost.cloudstream3.ui.player.PlayerSubtitleHelper.Companion.toSubtitleMimeType
import com.lagradost.cloudstream3.ui.player.source_priority.LinkSource
import com.lagradost.cloudstream3.ui.player.source_priority.QualityDataHelper
import com.lagradost.cloudstream3.ui.player.source_priority.QualityDataHelper.getLinkPriority
import com.lagradost.cloudstream3.ui.player.source_priority.QualityProfileDialog
import com.lagradost.cloudstream3.ui.result.ACTION_CLICK_DEFAULT
import com.lagradost.cloudstream3.ui.result.EpisodeAdapter
import com.lagradost.cloudstream3.ui.result.FOCUS_SELF
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.ResultFragment
import com.lagradost.cloudstream3.ui.result.ResultFragment.bindLogo
import com.lagradost.cloudstream3.ui.result.ResultViewModel2
import com.lagradost.cloudstream3.ui.result.SyncViewModel
import com.lagradost.cloudstream3.ui.result.setLinearListLayout
import com.lagradost.cloudstream3.ui.setRecycledViewPool
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.PHONE
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.ui.subtitles.SUBTITLE_AUTO_SELECT_KEY
import com.lagradost.cloudstream3.ui.subtitles.SubtitlesFragment
import com.lagradost.cloudstream3.ui.subtitles.SubtitlesFragment.Companion.getAutoSelectLanguageTagIETF
import com.lagradost.cloudstream3.utils.AppContextUtils.getShortSeasonText
import com.lagradost.cloudstream3.utils.AppContextUtils.html
import com.lagradost.cloudstream3.utils.AppContextUtils.sortSubs
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.runOnMainThread
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.DataStoreHelper.getViewPos
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showDialog
import com.lagradost.cloudstream3.utils.SubtitleHelper.fromTagToEnglishLanguageName
import com.lagradost.cloudstream3.utils.SubtitleHelper.fromTagToLanguageName
import com.lagradost.cloudstream3.utils.SubtitleHelper.languages
import com.lagradost.cloudstream3.utils.UIHelper.clipboardHelper
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import com.lagradost.cloudstream3.utils.UIHelper.dismissSafe
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding
import com.lagradost.cloudstream3.utils.UIHelper.hideSystemUI
import com.lagradost.cloudstream3.utils.UIHelper.popCurrentPage
import com.lagradost.cloudstream3.utils.UIHelper.toPx
import com.lagradost.cloudstream3.utils.downloader.DownloadUtils.getImageBitmapFromUrl
import com.lagradost.cloudstream3.utils.setText
import com.lagradost.cloudstream3.utils.txt
import com.lagradost.cloudstream3.utils.videoskip.VideoSkipStamp
import com.lagradost.safefile.SafeFile
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.Serializable
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import android.view.KeyEvent
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import com.google.android.material.button.MaterialButton
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.widget.ImageButton
import android.view.ViewOutlineProvider

@OptIn(UnstableApi::class)
class GeneratorPlayer : FullScreenPlayer() {
    companion object {
        const val NOTIFICATION_ID = 2326
        const val CHANNEL_ID = 7340
        const val STOP_ACTION = "stopcs3"

        private val generators = ConcurrentHashMap<String, VideoGenerator<*>>()
        fun newInstance(
            generator: VideoGenerator<*>,
            index: Int,
            syncData: HashMap<String, String>? = null
        ): Bundle {
            Log.i(TAG, "newInstance = $syncData")
            val uuid = UUID.randomUUID().toString()
            generators[uuid] = generator
            return Bundle().apply {
                putString("uuid", uuid)
                putInt("index", index)
                if (syncData != null) putSerializable("syncData", syncData)
            }
        }

        val subsProviders = subtitleProviders
        val subsProvidersIsActive
            get() = subsProviders.isNotEmpty()
    }


    private var limitTitle = 0
    private var showTitle = false
    private var showName = false
    private var showResolution = false
    private var showMediaInfo = false

    private lateinit var viewModel: PlayerGeneratorViewModel //by activityViewModels()
    private lateinit var sync: SyncViewModel

    private var currentSelectedLink: Pair<ExtractorLink?, ExtractorUri?>? = null
    private var currentSelectedSubtitles: SubtitleData? = null
    private val currentMeta: Any? get() = viewModel.state.generatorState?.meta
    private val nextMeta: Any? get() = viewModel.state.generatorState?.nextMeta

    private var isPlayerActive: AtomicBoolean = AtomicBoolean(false)
    private var isNextEpisode: Boolean = false // this is used to reset the watch time

    private var preferredAutoSelectSubtitles: String? = null // null means do nothing, "" means none
    private val allMeta: List<ResultEpisode>?
        get() = viewModel.state.generatorState?.allMeta?.filterIsInstance<ResultEpisode>()
            ?.map { episode ->
                // Refresh all the episodes watch duration
                getViewPos(episode.id)?.let { data ->
                    episode.copy(position = data.position, duration = data.duration)
                } ?: episode
            }

    private fun setSubtitles(subtitle: SubtitleData?, userInitiated: Boolean): Boolean {
        // If subtitle is changed and user initiated -> Save the language
        if (subtitle != currentSelectedSubtitles && userInitiated) {
            val subtitleLanguageTagIETF = if (subtitle == null) {
                "" // -> No Subtitles
            } else {
                subtitle.getIETF_tag()
            }

            if (subtitleLanguageTagIETF != null) {
                Log.i(TAG, "Set SUBTITLE_AUTO_SELECT_KEY to '$subtitleLanguageTagIETF'")
                setKey(SUBTITLE_AUTO_SELECT_KEY, subtitleLanguageTagIETF)
                preferredAutoSelectSubtitles = subtitleLanguageTagIETF
            }
        }

        currentSelectedSubtitles = subtitle
        //Log.i(TAG, "setSubtitles = $subtitle")
        return player.setPreferredSubtitles(subtitle)
    }

    override fun embeddedSubtitlesFetched(subtitles: List<SubtitleData>) {
        viewModel.addSubtitles(subtitles.toSet())
    }

    override fun onTracksInfoChanged() {
        val tracks = player.getVideoTracks()
        playerBinding?.playerTracksBtt?.isVisible =
            tracks.allVideoTracks.size > 1 || tracks.allAudioTracks.size > 1
        // Only set the preferred language if it is available.
        // Otherwise, it may give some users audio track init failed!
        if (tracks.allAudioTracks.any { it.language == preferredAudioTrackLanguage }) {
            player.setPreferredAudioTrack(preferredAudioTrackLanguage)
        }
        updatePlayerInfo()
    }

    override fun playerStatusChanged() {
        super.playerStatusChanged()
        if (player.getIsPlaying()) {
            viewModel.forceClearCache = false
        }
    }

    private fun noSubtitles(): Boolean {
        return setSubtitles(null, true)
    }

    private fun getPos(): Long {
        val durPos = getViewPos(viewModel.state.generatorState?.id) ?: return 0L
        if (durPos.duration == 0L) return 0L
        if (durPos.position * 100L / durPos.duration > 95L) {
            return 0L
        }
        return durPos.position
    }

    private var currentVerifyLink: Job? = null

    private fun loadExtractorJob(extractorLink: ExtractorLink?) {
        currentVerifyLink?.cancel()

        extractorLink?.let { link ->
            currentVerifyLink = ioSafe {
                if (link.extractorData != null) {
                    getApiFromNameNull(link.source)?.extractorVerifierJob(link.extractorData)
                }
            }
        }
    }

    // https://github.com/androidx/media/blob/main/libraries/ui/src/main/java/androidx/media3/ui/PlayerNotificationManager.java#L1517
    private fun createBroadcastIntent(
        action: String,
        context: Context,
        instanceId: Int
    ): PendingIntent {
        val intent: Intent = Intent(action).setPackage(context.packageName)
        intent.putExtra(EXTRA_INSTANCE_ID, instanceId)
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else PendingIntent.FLAG_UPDATE_CURRENT

        return PendingIntent.getBroadcast(context, instanceId, intent, pendingFlags)
    }

    private var cachedPlayerNotificationManager: PlayerNotificationManager? = null

    private fun getMediaNotification(context: Context): PlayerNotificationManager {
        val cache = cachedPlayerNotificationManager
        if (cache != null) return cache
        return PlayerNotificationManager.Builder(
            context,
            NOTIFICATION_ID,
            CHANNEL_ID.toString()
        )
            .setChannelNameResourceId(R.string.player_notification_channel_name)
            .setChannelDescriptionResourceId(R.string.player_notification_channel_description)
            .setMediaDescriptionAdapter(object : MediaDescriptionAdapter {
                override fun getCurrentContentTitle(player: Player): CharSequence {
                    return when (val meta = currentMeta) {
                        is ResultEpisode -> {
                            meta.headerName
                        }

                        is ExtractorUri -> {
                            meta.headerName ?: meta.name
                        }

                        else -> null
                    } ?: "Unknown"
                }

                override fun createCurrentContentIntent(player: Player): PendingIntent? {
                    // Open the app without creating a new task to resume playback seamlessly
                    return PendingIntentCompat.getActivity(
                        context,
                        0,
                        Intent(context, MainActivity::class.java),
                        0,
                        false
                    )
                }

                override fun getCurrentContentText(player: Player): CharSequence? {
                    return when (val meta = currentMeta) {
                        is ResultEpisode -> {
                            meta.name
                        }

                        is ExtractorUri -> {
                            if (meta.headerName == null) {
                                null
                            } else {
                                meta.name
                            }
                        }

                        else -> null
                    }
                }

                override fun getCurrentLargeIcon(
                    player: Player,
                    callback: PlayerNotificationManager.BitmapCallback
                ): Bitmap? {
                    ioSafe {
                        val url = when (val meta = currentMeta) {
                            is ResultEpisode -> {
                                meta.poster
                            }

                            else -> null
                        }
                        // if we have a poster url try with it first
                        if (url != null) {
                            val urlBitmap = context.getImageBitmapFromUrl(url)
                            if (urlBitmap != null) {
                                callback.onBitmap(urlBitmap)
                                return@ioSafe
                            }
                        }

                        // retry several times with a preview in case the preview generator is slow
                        repeat(10) {
                            val preview = this@GeneratorPlayer.player.getPreview(0.5f)
                            if (preview != null) {
                                callback.onBitmap(preview)
                                return@repeat
                            }
                            delay(1000L)
                        }
                    }

                    // return null as we want to use the callback
                    return null
                }
            }).setCustomActionReceiver(object : PlayerNotificationManager.CustomActionReceiver {
                // we have to use a custom action for stop if we want to exit the player instead of just stopping playback
                override fun createCustomActions(
                    context: Context,
                    instanceId: Int
                ): MutableMap<String, NotificationCompat.Action> {
                    return mutableMapOf(
                        STOP_ACTION to NotificationCompat.Action(
                            R.drawable.baseline_stop_24,
                            @SuppressLint("PrivateResource")
                            context.getString(androidx.media3.ui.R.string.exo_controls_stop_description),
                            createBroadcastIntent(STOP_ACTION, context, instanceId)
                        )
                    )
                }

                override fun getCustomActions(player: Player): MutableList<String> {
                    return mutableListOf(STOP_ACTION)
                }

                override fun onCustomAction(player: Player, action: String, intent: Intent) {
                    when (action) {
                        STOP_ACTION -> {
                            exitPlayer()
                        }
                    }
                }
            })
            .setPlayActionIconResourceId(R.drawable.ic_baseline_play_arrow_24)
            .setPauseActionIconResourceId(R.drawable.netflix_pause)
            .setSmallIconResourceId(R.drawable.baseline_headphones_24)
            .setStopActionIconResourceId(R.drawable.baseline_stop_24)
            .setRewindActionIconResourceId(R.drawable.go_back_30)
            .setFastForwardActionIconResourceId(R.drawable.go_forward_30)
            .setNextActionIconResourceId(R.drawable.ic_baseline_skip_next_24)
            .setPreviousActionIconResourceId(R.drawable.baseline_skip_previous_24)
            .build().apply {
                setColorized(true) // Color
                setUseChronometer(true) // Seekbar

                // Don't show the prev episode button
                setUsePreviousAction(false)
                setUsePreviousActionInCompactView(false)

                // Don't show the next episode button
                setUseNextAction(false)
                setUseNextActionInCompactView(false)

                // Show the skip 30s in both modes
                setUseFastForwardAction(true)
                setUseFastForwardActionInCompactView(true)

                // Only show rewind in expanded
                setUseRewindAction(true)
                setUseFastForwardActionInCompactView(false)

                // Use custom stop action
                setUseStopAction(false)
            }
            .also { cachedPlayerNotificationManager = it }
    }

    override fun playerUpdated(player: Any?) {
        super.playerUpdated(player)

        // Cancel the notification when released
        if (player == null) {
            cachedPlayerNotificationManager?.setPlayer(null)
            cachedPlayerNotificationManager = null
            return
        }

        // setup the notification when starting the player
        if (player is ExoPlayer) {
            val ctx = context ?: return
            getMediaNotification(ctx).apply {
                setPlayer(player)
                mMediaSession?.platformToken?.let {
                    setMediaSessionToken(it)
                }
            }
        }
    }

    override fun onDownload(event: DownloadEvent) {
        super.onDownload(event)
        showDownloadProgress(event)
    }

    private fun showDownloadProgress(event: DownloadEvent) {
        activity?.runOnUiThread {
            playerBinding?.downloadedProgress?.apply {
                val indeterminate = event.totalBytes <= 0 || event.downloadedBytes <= 0
                isIndeterminate = indeterminate
                if (!indeterminate) {
                    max = (event.totalBytes / 1000).toInt()
                    progress = (event.downloadedBytes / 1000).toInt()
                }
            }
            playerBinding?.downloadedProgressText.setText(
                txt(
                    R.string.download_size_format,
                    android.text.format.Formatter.formatShortFileSize(
                        context,
                        event.downloadedBytes
                    ),
                    android.text.format.Formatter.formatShortFileSize(context, event.totalBytes)
                )
            )
            val downloadSpeed =
                android.text.format.Formatter.formatShortFileSize(context, event.downloadSpeed)
            playerBinding?.downloadedProgressSpeedText?.text =
                    // todo string fmt
                event.connections?.let { connections ->
                    "%s/s - %d Connections".format(downloadSpeed, connections)
                } ?: downloadSpeed

            // don't display when done
            playerBinding?.downloadedProgressSpeedText?.isGone =
                event.downloadedBytes != 0L && event.downloadedBytes - 1024 >= event.totalBytes
        }
    }

    private fun loadLink(link: VideoLink?, sameEpisode: Boolean) {
        if (link == null) return
        isPlayerActive.set(true)
        // manage UI
        binding?.playerLoadingOverlay?.isVisible = false
        val isTorrent =
            link.first?.type == ExtractorLinkType.MAGNET || link.first?.type == ExtractorLinkType.TORRENT

        playerBinding?.downloadHeader?.isVisible = false
        playerBinding?.downloadHeaderToggle?.isVisible = isTorrent
        if (!isLayout(PHONE)) {
            playerBinding?.downloadBothHeader?.isVisible = isTorrent
        }

        showDownloadProgress(DownloadEvent(0, 0, 0, null))

        uiReset()
        currentSelectedLink = link
        updateTwitchStreamerOverlay()
        updateTwitchPlayerControls()
        updateTwitchStreamerOverlay()
        //  setEpisodes(viewModel.getAllMeta() ?: emptyList())
        setPlayerDimen(null)
        setTitle()
        if (!sameEpisode)
            hasRequestedStamps = false

        loadExtractorJob(link.first)
        // load player
        context?.let { ctx ->
            val (url, uri) = link
            val subtitles = viewModel.state.subtitles
            player.loadPlayer(
                ctx,
                sameEpisode,
                url,
                uri,
                startPosition = if (sameEpisode) null else {
                    if (isNextEpisode) 0L else getPos()
                },
                subtitles,
                (if (sameEpisode) currentSelectedSubtitles else null) ?: getAutoSelectSubtitle(
                    subtitles, settings = true, downloads = true
                ),
                preview = true
            )
        }

        if (!sameEpisode) {
            player.addTimeStamps(emptyList()) // clear stamps
            // Resets subtitle delay, as we watch some other content
            player.setSubtitleOffset(0)
        }
    }

    data class TempMetaData(
        var episode: Int? = null,
        var season: Int? = null,
        var name: String? = null,
        var imdbId: String? = null,
    )

    private fun getMetaData(): TempMetaData {
        val meta = TempMetaData()

        when (val newMeta = currentMeta) {
            is ResultEpisode -> {
                if (!newMeta.tvType.isMovieType()) {
                    meta.episode = newMeta.episode
                    meta.season = newMeta.season
                }
                meta.name = newMeta.headerName
            }

            is ExtractorUri -> {
                if (newMeta.tvType?.isMovieType() == false) {
                    meta.episode = newMeta.episode
                    meta.season = newMeta.season
                }
                meta.name = newMeta.headerName
            }
        }
        return meta
    }

    fun getName(entry: AbstractSubtitleEntities.SubtitleEntity, withLanguage: Boolean): String {
        if (entry.lang.isBlank() || !withLanguage) {
            return entry.name
        }
        val language = fromTagToLanguageName(entry.lang.trim()) ?: entry.lang
        return "$language ${entry.name}"
    }

    override fun openOnlineSubPicker(
        context: Context, loadResponse: LoadResponse?, dismissCallback: (() -> Unit)
    ) {
        val providers = subsProviders.toList()
        val isSingleProvider = subsProviders.size == 1

        val dialog = Dialog(context, R.style.DialogFullscreenPlayer)
        val binding =
            DialogOnlineSubtitlesBinding.inflate(LayoutInflater.from(context), null, false)
        dialog.setContentView(binding.root)
        fixSystemBarsPadding(binding.root)

        var currentSubtitles: List<AbstractSubtitleEntities.SubtitleEntity> = emptyList()
        var currentSubtitle: AbstractSubtitleEntities.SubtitleEntity? = null

        val layout = R.layout.sort_bottom_single_choice_double_text
        val arrayAdapter =
            object : ArrayAdapter<AbstractSubtitleEntities.SubtitleEntity>(dialog.context, layout) {
                fun setHearingImpairedIcon(
                    imageViewEnd: ImageView?, position: Int
                ) {
                    if (imageViewEnd == null) return
                    val isHearingImpaired =
                        currentSubtitles.getOrNull(position)?.isHearingImpaired ?: false

                    val drawableEnd = if (isHearingImpaired) {
                        ContextCompat.getDrawable(
                            context, R.drawable.ic_baseline_hearing_24
                        )?.apply {
                            setTint(
                                ContextCompat.getColor(
                                    context, R.color.textColor
                                )
                            )
                        }
                    } else null

                    imageViewEnd.setImageDrawable(drawableEnd)
                }

                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = convertView ?: LayoutInflater.from(context).inflate(layout, null)

                    val item = getItem(position)

                    val mainTextView = view.findViewById<TextView>(R.id.main_text)
                    val secondaryTextView = view.findViewById<TextView>(R.id.secondary_text)
                    val drawableEnd = view.findViewById<ImageView>(R.id.drawable_end)

                    mainTextView?.text = item?.let { getName(it, false) }

                    val language =
                        item?.let { fromTagToLanguageName(it.lang) ?: it.lang } ?: ""
                    val providerSuffix =
                        if (isSingleProvider || item == null) "" else " · ${item.source}"
                    @SuppressLint("SetTextI18n")
                    secondaryTextView?.text = language + providerSuffix

                    setHearingImpairedIcon(drawableEnd, position)
                    return view
                }
            }

        dialog.show()
        binding.cancelBtt.setOnClickListener {
            dialog.dismissSafe()
        }

        binding.subtitleAdapter.choiceMode = AbsListView.CHOICE_MODE_SINGLE
        binding.subtitleAdapter.adapter = arrayAdapter

        binding.subtitleAdapter.setOnItemClickListener { _, _, position, _ ->
            currentSubtitle = currentSubtitles.getOrNull(position) ?: return@setOnItemClickListener
        }

        var currentLanguageTagIETF: String = getAutoSelectLanguageTagIETF()


        fun setSubtitlesList(list: List<AbstractSubtitleEntities.SubtitleEntity>) {
            currentSubtitles = list
            arrayAdapter.clear()
            arrayAdapter.addAll(currentSubtitles)
        }

        val currentTempMeta = getMetaData()

        // bruh idk why it is not correct
        val color =
            ColorStateList.valueOf(context.colorFromAttribute(androidx.appcompat.R.attr.colorAccent))
        binding.searchLoadingBar.progressTintList = color
        binding.searchLoadingBar.indeterminateTintList = color

        observeNullable(viewModel.currentSubtitleYear) {
            // When year is changed search again
            binding.subtitlesSearch.setQuery(binding.subtitlesSearch.query, true)
            binding.yearBtt.text = it?.toString() ?: txt(R.string.none).asString(context)
        }

        binding.yearBtt.setOnClickListener {
            val none = txt(R.string.none).asString(context)
            val currentYear = Calendar.getInstance().get(Calendar.YEAR)
            val earliestYear = 1900

            val years = (currentYear downTo earliestYear).toList()
            val options = listOf(none) + years.map {
                it.toString()
            }

            val selectedIndex = viewModel.currentSubtitleYear.value
                ?.let {
                    // + 1 since none also takes a space
                    years.indexOf(it) + 1
                }
                ?.takeIf { it >= 0 } ?: 0

            activity?.showDialog(
                options,
                selectedIndex,
                txt(R.string.year).asString(context),
                true, {
                }, { index ->
                    viewModel.setSubtitleYear(years.getOrNull(index - 1))
                }
            )
        }

        binding.subtitlesSearch.setOnQueryTextListener(object :
            androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                binding.searchLoadingBar.show()
                ioSafe {
                    val search =
                        SubtitleSearch(
                            query = query ?: return@ioSafe,
                            imdbId = loadResponse?.getImdbId(),
                            tmdbId = loadResponse?.getTMDbId()?.toInt(),
                            malId = loadResponse?.getMalId()?.toInt(),
                            aniListId = loadResponse?.getAniListId()?.toInt(),
                            epNumber = currentTempMeta.episode,
                            seasonNumber = currentTempMeta.season,
                            lang = currentLanguageTagIETF.ifBlank { null },
                            year = viewModel.currentSubtitleYear.value
                        )

                    // TODO Make ui a lot better, like search with tabs
                    val results = providers.amap {
                        when (val response = Resource.fromResult(it.search(search))) {
                            is Resource.Success -> {
                                response.value
                            }

                            is Resource.Loading -> {
                                emptyList()
                            }

                            is Resource.Failure -> {
                                showToast(response.errorString)
                                emptyList()
                            }
                        }
                    }
                    val max = results.maxOfOrNull { it.size } ?: return@ioSafe

                    // very ugly
                    val items = ArrayList<AbstractSubtitleEntities.SubtitleEntity>()
                    val arrays = results.size
                    for (index in 0 until max) {
                        for (i in 0 until arrays) {
                            items.add(results[i].getOrNull(index) ?: continue)
                        }
                    }

                    // ugly ik
                    activity?.runOnUiThread {
                        setSubtitlesList(items)
                        binding.searchLoadingBar.hide()
                    }
                }

                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                return true
            }
        })

        binding.searchFilter.setOnClickListener { view ->
            val languagesTagName =
                languages
                    .map { Pair(it.IETF_tag, it.nameNextToFlagEmoji()) }
                    .sortedBy {
                        it.second.substringAfter("\u00a0").lowercase()
                    } // name ignoring flag emoji
            val (langTagsIETF, langNames) = languagesTagName.unzip()

            activity?.showDialog(
                langNames,
                langTagsIETF.indexOf(currentLanguageTagIETF),
                view?.context?.getString(R.string.subs_subtitle_languages)
                    ?: return@setOnClickListener,
                true,
                { }) { index ->
                currentLanguageTagIETF = langTagsIETF[index]
                binding.subtitlesSearch.setQuery(binding.subtitlesSearch.query, true)
            }
        }

        binding.applyBtt.setOnClickListener {
            currentSubtitle?.let { currentSubtitle ->
                providers.firstOrNull { it.idPrefix == currentSubtitle.idPrefix }?.let { api ->
                    ioSafe {
                        when (val apiResource =
                            Resource.fromResult(api.resource(currentSubtitle))) {
                            is Resource.Success -> {
                                val subtitles = apiResource.value.getSubtitles().map { resource ->
                                    SubtitleData(
                                        originalName = resource.name ?: getName(
                                            currentSubtitle,
                                            true
                                        ),
                                        nameSuffix = "",
                                        url = resource.url,
                                        origin = resource.origin,
                                        mimeType = resource.url.toSubtitleMimeType(),
                                        headers = currentSubtitle.headers,
                                        languageCode = currentSubtitle.lang
                                    )
                                }
                                if (subtitles.isEmpty()) {
                                    showToast(R.string.no_subtitles)
                                    return@ioSafe
                                }
                                runOnMainThread {
                                    addAndSelectSubtitles(*subtitles.toTypedArray())
                                }
                            }

                            is Resource.Failure -> {
                                showToast(apiResource.errorString)
                            }

                            is Resource.Loading -> {
                                // not possible
                            }
                        }
                    }
                }
            }
            dialog.dismissSafe()
        }

        dialog.setOnDismissListener {
            dismissCallback.invoke()
        }

        dialog.show()
        binding.subtitlesSearch.setQuery(currentTempMeta.name, true)
        //TODO: Set year text from currently loaded movie on Player
        //dialog.subtitles_search_year?.setText(currentTempMeta.year)
    }

    private fun openSubPicker() {
        try {
            subsPathPicker.launch(
                arrayOf(
                    "text/plain",
                    "text/str",
                    "application/octet-stream",
                    MimeTypes.TEXT_UNKNOWN,
                    MimeTypes.TEXT_VTT,
                    MimeTypes.TEXT_SSA,
                    MimeTypes.APPLICATION_TTML,
                    MimeTypes.APPLICATION_MP4VTT,
                    MimeTypes.APPLICATION_SUBRIP,
                )
            )
        } catch (e: Exception) {
            logError(e)
        }
    }

    @MainThread
    private fun addAndSelectSubtitles(
        vararg subtitleData: SubtitleData
    ) {
        if (subtitleData.isEmpty()) return
        val ctx = context ?: return
        val selectedSubtitle = subtitleData.first()
        viewModel.addSubtitles(subtitleData.toSet())

        // this is used instead of observe(viewModel._currentSubs), because observe is too slow
        player.setActiveSubtitles(viewModel.state.subtitles)

        // Save current time as to not reset player to 00:00
        player.saveData()
        player.reloadPlayer(ctx)

        setSubtitles(selectedSubtitle, false)

        selectSourceDialog?.dismissSafe()
        selectSourceDialog = null

        showToast(
            String.format(ctx.getString(R.string.player_loaded_subtitles), selectedSubtitle.name),
            Toast.LENGTH_LONG
        )
    }

    // Open file picker
    private val subsPathPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            safe {
                // It lies, it can be null if file manager quits.
                if (uri == null) return@safe
                val ctx = context ?: CloudStreamApp.context ?: return@safe
                // RW perms for the path
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )

                val file = SafeFile.fromUri(ctx, uri)
                val fileName = file?.name()
                println("Loaded subtitle file. Selected URI path: $uri - Name: $fileName")
                // DO NOT REMOVE THE FILE EXTENSION FROM NAME, IT'S NEEDED FOR MIME TYPES
                val name = fileName ?: uri.toString()

                val subtitleData = SubtitleData(
                    name,
                    "",
                    uri.toString(),
                    SubtitleOrigin.DOWNLOADED_FILE,
                    name.toSubtitleMimeType(),
                    emptyMap(),
                    null
                )

                addAndSelectSubtitles(subtitleData)
            }
        }

    /** Will toast both when an error is found and when a subtitle is selected,
     * so only use from a user click and not a background process */
    private fun addFirstSub(query: SubtitleSearch) =
        viewModel.viewModelScope.launch {
            // async should not have a race condition if they are on the same group
            var hasSelectASubtitle = false

            // first come first served with these subtitles
            // we might want to change it to prefer different sources when used multiple times,
            // however caching might make this random after the first click too
            subsProviders.toList().amap { provider ->
                val success = when (val result = Resource.fromResult(
                    provider.search(
                        query = query
                    )
                )) {
                    is Resource.Failure -> {
                        // scope might cancel, so we do an extra check
                        if (this.isActive) {
                            showToast("${provider.idPrefix}${result.errorString}")
                        }
                        return@amap
                    }

                    is Resource.Loading -> {
                        // unreachable
                        return@amap
                    }

                    is Resource.Success -> {
                        result.value
                    }
                }

                // try to add every subtitle until we have added a new subtitle file
                for (subtitleEntry in success) {
                    if (hasSelectASubtitle || !this.isActive) {
                        break
                    }

                    val subtitleResources = provider.resource(subtitleEntry).getOrNull() ?: continue

                    val subtitles = subtitleResources.getSubtitles().map { resource ->
                        SubtitleData(
                            originalName = resource.name ?: getName(subtitleEntry, true),
                            nameSuffix = "",
                            url = resource.url,
                            origin = resource.origin,
                            mimeType = resource.url.toSubtitleMimeType(),
                            headers = subtitleEntry.headers,
                            languageCode = subtitleEntry.lang,
                        )
                    }

                    // checks for both a race condition and if any of the subs generated is new
                    if (this.isActive && !viewModel.state.subtitles.containsAll(subtitles) && !hasSelectASubtitle) {
                        hasSelectASubtitle = true
                        runOnMainThread {
                            addAndSelectSubtitles(*subtitles.toTypedArray())
                        }
                        break
                    }
                }
            }
            // maybe better error here?
            if (!hasSelectASubtitle && this.isActive) {
                showToast(R.string.no_subtitles)
            }
        }


        private fun isTwitchExtractorLink(link: ExtractorLink?): Boolean {
        if (link == null) return false

        return listOf(
            link.source,
            link.name,
            link.url,
            link.referer,
        ).any { value ->
            value?.contains("twitch", ignoreCase = true) == true ||
                value?.contains("ttvnw.net", ignoreCase = true) == true
        }
    }

    private fun isTwitchPlayback(): Boolean {
        return isTwitchExtractorLink(currentSelectedLink?.first)
    }

    override fun allowResizeShortcut(): Boolean {
        return !isTwitchPlayback()
    }
    // BEGIN TwitchPlayerChatFoundationPatch
    private enum class TwitchPlayerChatCorner(val label: String) {
        TOP_START("top left"),
        TOP_END("top right"),
        BOTTOM_START("bottom left"),
        BOTTOM_END("bottom right");

        fun next(): TwitchPlayerChatCorner {
            val values = values()
            return values[(ordinal + 1) % values.size]
        }

        val isStart: Boolean
            get() = this == TOP_START || this == BOTTOM_START

        val isTop: Boolean
            get() = this == TOP_START || this == TOP_END
    }

    private data class TwitchPlayerChatTarget(
        val login: String,
        val displayName: String,
        val avatarUrl: String?,
    )

    private var twitchPlayerChatVisible = false
    private var twitchPlayerChatCorner = TwitchPlayerChatCorner.TOP_END

    private fun cleanTwitchPlayerChatLabel(value: String?): String {
        return value
            ?.replace(Regex("\\s+"), " ")
            ?.filter { !it.isISOControl() }
            ?.trim()
            .orEmpty()
    }

    private fun currentTwitchPlayerChatTarget(): TwitchPlayerChatTarget? {
        val overlay = currentTwitchStreamerOverlay() ?: return null
        val login = cleanTwitchPlayerChatLabel(
            twitchChannelFromUrl(overlay.profileUrl)
                ?: cleanTwitchProfileChannel(firstTwitchOverlayParam("cs_streamer_login"))
        ).lowercase().takeIf { it.isNotBlank() } ?: return null

        val displayName = cleanTwitchPlayerChatLabel(overlay.displayName)
            .ifBlank { login }

        return TwitchPlayerChatTarget(
            login = login,
            displayName = displayName,
            avatarUrl = overlay.avatarUrl,
        )
    }

    private fun View.twitchPlayerChatDp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun configureTwitchPlayerChatButton(button: android.widget.ImageButton) {
        button.setBackgroundResource(R.drawable.twitch_player_profile_button_bg)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            button.foreground = button.context.getDrawable(R.drawable.twitch_player_profile_button_focus_overlay)
        }

        val focusedScale = 1.14f
        val normalScale = 1f
        val glowZ = 18f * button.resources.displayMetrics.density

        fun applyFocusState(hasFocus: Boolean, animate: Boolean) {
            button.isSelected = hasFocus

            if (animate) {
                button.animate()
                    .scaleX(if (hasFocus) focusedScale else normalScale)
                    .scaleY(if (hasFocus) focusedScale else normalScale)
                    .setDuration(120L)
                    .start()
            } else {
                button.scaleX = if (hasFocus) focusedScale else normalScale
                button.scaleY = if (hasFocus) focusedScale else normalScale
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                val z = if (hasFocus) glowZ else 0f
                button.elevation = z
                button.translationZ = z
            }
        }

        applyFocusState(button.hasFocus(), animate = false)
        button.setOnFocusChangeListener { _, hasFocus ->
            applyFocusState(hasFocus, animate = true)
        }
    }

    private fun setTwitchPlayerChatOverlaySize(overlay: View) {
        val parentView = overlay.parent as? View
        val parentWidth = parentView?.width ?: 0
        val parentHeight = parentView?.height ?: 0

        val maxWidth = overlay.twitchPlayerChatDp(if (isLayout(TV)) 260 else 220)
        val maxHeight = overlay.twitchPlayerChatDp(if (isLayout(TV)) 150 else 130)
        val minWidth = overlay.twitchPlayerChatDp(if (isLayout(TV)) 180 else 160)
        val minHeight = overlay.twitchPlayerChatDp(if (isLayout(TV)) 105 else 95)

        val scaledWidth = if (parentWidth > 0) {
            minOf(maxWidth, (parentWidth * 0.30f).toInt().coerceAtLeast(minWidth))
        } else {
            maxWidth
        }

        val scaledHeight = if (parentHeight > 0) {
            minOf(maxHeight, (parentHeight * 0.22f).toInt().coerceAtLeast(minHeight))
        } else {
            maxHeight
        }

        val params = overlay.layoutParams ?: return
        params.width = scaledWidth
        params.height = scaledHeight
        overlay.layoutParams = params
    }

    private fun positionTwitchPlayerChatOverlayNow(overlay: View) {
        val parentView = overlay.parent as? View ?: return
        val parentWidth = parentView.width
        val parentHeight = parentView.height

        if (parentWidth <= 0 || parentHeight <= 0) return

        val overlayWidth = overlay.width.takeIf { it > 0 }
            ?: overlay.layoutParams?.width?.takeIf { it > 0 }
            ?: overlay.twitchPlayerChatDp(if (isLayout(TV)) 260 else 220)

        val overlayHeight = overlay.height.takeIf { it > 0 }
            ?: overlay.layoutParams?.height?.takeIf { it > 0 }
            ?: overlay.twitchPlayerChatDp(if (isLayout(TV)) 150 else 130)

        val targetLeft = if (twitchPlayerChatCorner.isStart) {
            0
        } else {
            (parentWidth - overlayWidth).coerceAtLeast(0)
        }

        val targetTop = if (twitchPlayerChatCorner.isTop) {
            0
        } else {
            (parentHeight - overlayHeight).coerceAtLeast(0)
        }

        overlay.translationX = targetLeft.toFloat() - overlay.left.toFloat()
        overlay.translationY = targetTop.toFloat() - overlay.top.toFloat()
        overlay.bringToFront()
    }

    private fun applyTwitchPlayerChatCorner(overlay: View) {
        setTwitchPlayerChatOverlaySize(overlay)

        val params = overlay.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        val parent = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        val unset = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET

        if (params != null) {
            params.startToStart = unset
            params.startToEnd = unset
            params.endToStart = unset
            params.endToEnd = unset
            params.topToTop = unset
            params.topToBottom = unset
            params.bottomToTop = unset
            params.bottomToBottom = unset

            params.leftMargin = 0
            params.rightMargin = 0
            params.topMargin = 0
            params.bottomMargin = 0
            params.marginStart = 0
            params.marginEnd = 0

            if (twitchPlayerChatCorner.isStart) {
                params.startToStart = parent
            } else {
                params.endToEnd = parent
            }

            if (twitchPlayerChatCorner.isTop) {
                params.topToTop = parent
            } else {
                params.bottomToBottom = parent
            }

            params.horizontalBias = if (twitchPlayerChatCorner.isStart) 0f else 1f
            params.verticalBias = if (twitchPlayerChatCorner.isTop) 0f else 1f
            overlay.layoutParams = params
        }

        overlay.translationX = 0f
        overlay.translationY = 0f
        overlay.post {
            setTwitchPlayerChatOverlaySize(overlay)
            positionTwitchPlayerChatOverlayNow(overlay)
        }
        overlay.postDelayed({
            setTwitchPlayerChatOverlaySize(overlay)
            positionTwitchPlayerChatOverlayNow(overlay)
        }, 80L)
        overlay.postDelayed({
            setTwitchPlayerChatOverlaySize(overlay)
            positionTwitchPlayerChatOverlayNow(overlay)
        }, 250L)
    }

    private fun formatTwitchPlayerChatTarget(target: TwitchPlayerChatTarget): String {
        val display = cleanTwitchPlayerChatLabel(target.displayName)
        return if (display.isBlank() || display.equals(target.login, ignoreCase = true)) {
            "@${target.login}"
        } else {
            "@${target.login} - $display"
        }
    }

    private fun updateTwitchPlayerChatText(target: TwitchPlayerChatTarget) {
        val root = playerBinding?.root ?: return

        root.findViewById<android.widget.TextView>(R.id.twitch_player_chat_title)?.text =
            "Twitch chat"
        root.findViewById<android.widget.TextView>(R.id.twitch_player_chat_status)?.text =
            formatTwitchPlayerChatTarget(target)
        root.findViewById<android.widget.TextView>(R.id.twitch_player_chat_hint)?.text =
            "Messages and emotes are next.\n${twitchPlayerChatCorner.label}\nLong-press to move."
    }

    private fun focusTwitchPlayerChatButton() {
        val button = playerBinding?.root
            ?.findViewById<View>(R.id.twitch_player_chat_button)
            ?: return

        button.post {
            button.requestFocus()
        }
    }

    private fun closeTwitchPlayerChatAndFocusButton() {
        twitchPlayerChatVisible = false
        updateTwitchPlayerChatOverlay()
        focusTwitchPlayerChatButton()
    }

    private fun cycleTwitchPlayerChatCorner(focusOverlay: Boolean = false) {
        twitchPlayerChatCorner = twitchPlayerChatCorner.next()
        playerBinding?.root
            ?.findViewById<View>(R.id.twitch_player_chat_overlay)
            ?.let { overlay ->
                applyTwitchPlayerChatCorner(overlay)
                if (focusOverlay) {
                    overlay.requestFocus()
                } else {
                    focusTwitchPlayerChatButton()
                }
            }

        android.widget.Toast.makeText(
            context,
            "Chat moved to ${twitchPlayerChatCorner.label}",
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }

    private fun consumeTwitchPlayerChatOverlayInput(overlay: View) {
        overlay.isClickable = true
        overlay.isFocusable = true
        overlay.isFocusableInTouchMode = true

        overlay.setOnClickListener {
            closeTwitchPlayerChatAndFocusButton()
        }

        overlay.setOnLongClickListener {
            cycleTwitchPlayerChatCorner(focusOverlay = true)
            updateTwitchPlayerChatOverlay()
            true
        }

        overlay.setOnKeyListener { _, keyCode, event ->
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                android.view.KeyEvent.KEYCODE_ENTER,
                android.view.KeyEvent.KEYCODE_SPACE -> {
                    if (event.action == android.view.KeyEvent.ACTION_UP) {
                        closeTwitchPlayerChatAndFocusButton()
                    }
                    true
                }
                android.view.KeyEvent.KEYCODE_DPAD_UP,
                android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                        focusTwitchPlayerChatButton()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun updateTwitchPlayerChatOverlay() {
        val root = playerBinding?.root ?: return
        val button = root.findViewById<View>(R.id.twitch_player_chat_button) as? android.widget.ImageButton
            ?: return
        val overlayView = root.findViewById<View>(R.id.twitch_player_chat_overlay)
            ?: return

        configureTwitchPlayerChatButton(button)
        consumeTwitchPlayerChatOverlayInput(overlayView)

        val target = currentTwitchPlayerChatTarget()
        val hasChatTarget = target != null
        val wasOverlayVisible = overlayView.isVisible

        button.isVisible = hasChatTarget
        button.isEnabled = hasChatTarget
        button.isClickable = hasChatTarget
        button.isFocusable = hasChatTarget
        button.setImageResource(R.drawable.ic_twitch_player_chat)
        button.contentDescription = if (twitchPlayerChatVisible) {
            "Hide Twitch chat"
        } else {
            "Show Twitch chat"
        }

        overlayView.isVisible = hasChatTarget && twitchPlayerChatVisible

        if (!hasChatTarget) {
            twitchPlayerChatVisible = false
            overlayView.isVisible = false
            button.setOnClickListener(null)
            button.setOnLongClickListener(null)
            overlayView.setOnClickListener(null)
            overlayView.setOnLongClickListener(null)
            overlayView.setOnKeyListener(null)
            return
        }

        target?.let {
            applyTwitchPlayerChatCorner(overlayView)
            updateTwitchPlayerChatText(it)
        }

        button.setOnClickListener {
            twitchPlayerChatVisible = !twitchPlayerChatVisible
            updateTwitchPlayerChatOverlay()
            focusTwitchPlayerChatButton()
        }

        button.setOnLongClickListener {
            cycleTwitchPlayerChatCorner(focusOverlay = false)
            updateTwitchPlayerChatOverlay()
            focusTwitchPlayerChatButton()
            true
        }

        if (overlayView.isVisible && !wasOverlayVisible) {
            overlayView.post {
                applyTwitchPlayerChatCorner(overlayView)
                focusTwitchPlayerChatButton()
            }
        } else if (overlayView.isVisible) {
            overlayView.post {
                positionTwitchPlayerChatOverlayNow(overlayView)
            }
        } else if (button.hasFocus()) {
            focusTwitchPlayerChatButton()
        }
    }
    // END TwitchPlayerChatFoundationPatch
private fun updateTwitchPlayerControls() {
        playerBinding?.playerResizeBtt?.isVisible = playerResizeEnabled && !isTwitchPlayback()
        updateTwitchStreamerOverlay()
        updateTwitchPlayerChatOverlay()
    }

    // BEGIN TwitchPlayerStreamerOverlayPatch
    private data class TwitchPlayerStreamerOverlay(
        val displayName: String,
        val avatarUrl: String?,
        val profileUrl: String,
        val apiName: String?,
    )

    private fun cleanTwitchOverlayText(value: String?): String? {
        return value?.trim()?.ifBlank { null }
    }

    private fun twitchOverlayQueryParam(source: String?, key: String): String? {
        val raw = source?.trim()?.ifBlank { null } ?: return null
        return runCatching {
            Uri.parse(raw).getQueryParameter(key)?.trim()?.ifBlank { null }
        }.getOrNull()
    }

    private fun currentTwitchOverlayCandidateUrls(): List<String> {
        val meta = currentMeta
        val response = viewModel.state.generatorState?.response

        return listOfNotNull(
            when (meta) {
                is ResultEpisode -> meta.data
                is ExtractorUri -> meta.uri.toString()
                else -> null
            },
            response?.url,
            currentSelectedLink?.first?.extractorData,
            currentSelectedLink?.first?.url,
            currentSelectedLink?.first?.referer,
            currentSelectedLink?.first?.source,
            currentSelectedLink?.first?.name,
            currentSelectedLink?.second?.uri?.toString(),
            currentSelectedLink?.second?.name,
        ).flatMap { it.split('\n') }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun firstTwitchOverlayParam(key: String): String? {
        currentTwitchOverlayCandidateUrls().forEach { source ->
            twitchOverlayQueryParam(source, key)?.let { return it }
        }
        return null
    }

    private fun cleanTwitchProfileChannel(value: String?): String? {
        val raw = value?.trim()?.ifBlank { null } ?: return null
        val channel = raw
            .removePrefix("@")
            .filter { it.isLetterOrDigit() || it == '_' }
            .lowercase()
            .ifBlank { null }
            ?: return null

        val blocked = setOf(
            "twitch",
            "twitchtv",
            "twitchcom",
            "wwwtwitchtv",
            "wwwtwitchcom",
            "clips",
            "clip",
            "videos",
            "video",
            "source",
            "auto",
            "vod",
            "m3u8",
        )

        return channel.takeUnless { it in blocked || it.all { char -> char.isDigit() } }
    }

    private fun twitchChannelFromUrl(value: String?): String? {
        val raw = value?.trim()?.ifBlank { null } ?: return null
        val clean = raw.substringBefore("?").substringBefore("#").trim().trimEnd('/')
        val lower = clean.lowercase()
        if (!lower.contains("twitch.tv/")) return null
        if (lower.contains("clips.twitch.tv/")) return null

        val afterHost = clean.substringAfter("twitch.tv/", "")
        val firstPathPart = afterHost.substringBefore("/").trim().removePrefix("@")
        if (firstPathPart.equals("videos", ignoreCase = true)) return null
        if (firstPathPart.equals("clip", ignoreCase = true)) return null

        return cleanTwitchProfileChannel(firstPathPart)
    }

    private fun isLikelyTwitchMediaThumbnail(value: String?): Boolean {
        val lower = value?.lowercase() ?: return false
        return lower.contains("previews-ttv") ||
            lower.contains("cf_vods") ||
            lower.contains("clips-media-assets") ||
            lower.contains("-preview-") ||
            lower.contains("-social-preview") ||
            lower.contains("/vod/") ||
            lower.contains("thumb")
    }

    private fun strictTwitchProfileAvatar(value: String?): String? {
        val clean = value?.trim()?.ifBlank { null } ?: return null
        val lower = clean.lowercase()
        if (!lower.startsWith("http")) return null
        if (lower.contains("%{width}") || lower.contains("{width}")) return null
        if (isLikelyTwitchMediaThumbnail(lower)) return null
        return clean
    }

    private fun hasTwitchOverlaySignal(): Boolean {
        if (firstTwitchOverlayParam("cs_streamer_login") != null) return true
        if (currentTwitchOverlayCandidateUrls().any { twitchChannelFromUrl(it) != null }) return true
        return isTwitchPlayback()
    }

    private fun currentTwitchStreamerOverlay(): TwitchPlayerStreamerOverlay? {
        if (!hasTwitchOverlaySignal()) return null

        val meta = currentMeta
        val response = viewModel.state.generatorState?.response
        val explicitName = firstTwitchOverlayParam("cs_streamer_name")
        val explicitChannel = cleanTwitchProfileChannel(firstTwitchOverlayParam("cs_streamer_login"))
        val explicitAvatar = strictTwitchProfileAvatar(firstTwitchOverlayParam("cs_streamer_avatar"))
        val explicitApiName = firstTwitchOverlayParam("cs_api_name")

        val urls = currentTwitchOverlayCandidateUrls()
        val channelFromUrl = urls.mapNotNull { twitchChannelFromUrl(it) }.firstOrNull()
        val responseUrlChannel = twitchChannelFromUrl(response?.url)

        val profileChannel = explicitChannel
            ?: responseUrlChannel
            ?: channelFromUrl
            ?: return null

        val metaName = when (meta) {
            is ResultEpisode -> meta.headerName.ifBlank { meta.name.orEmpty() }
            is ExtractorUri -> meta.headerName?.takeIf { it.isNotBlank() }
                ?: meta.displayName?.takeIf { it.isNotBlank() }
                ?: meta.name.takeIf { it.isNotBlank() }
            else -> null
        }?.trim()?.ifBlank { null }

        val displayName = cleanTwitchOverlayText(explicitName)
            ?: cleanTwitchOverlayText(response?.name)
            ?: cleanTwitchOverlayText(metaName)?.takeIf {
                !it.contains("://") && !it.contains(".m3u8", ignoreCase = true)
            }
            ?: profileChannel

        val apiName = explicitApiName
            ?: when (meta) {
                is ResultEpisode -> meta.apiName
                else -> null
            }
            ?: response?.apiName

        return TwitchPlayerStreamerOverlay(
            displayName = displayName,
            avatarUrl = explicitAvatar,
            profileUrl = "https://www.twitch.tv/$profileChannel",
            apiName = apiName,
        )
    }

    private fun twitchResultNavigationDestinationId(): Int {
        val act = activity ?: return 0
        val candidates = listOf(
            "navigation_results_tv",
            "navigation_results",
            "navigation_results_phone",
            "navigation_result",
            "result",
            "results",
            "resultFragment",
            "result_fragment",
        )
        return candidates
            .asSequence()
            .map { act.resources.getIdentifier(it, "id", act.packageName) }
            .firstOrNull { it != 0 }
            ?: 0
    }

    private fun openTwitchStreamerProfile(overlay: TwitchPlayerStreamerOverlay) {
        val act = activity
        val apiName = overlay.apiName?.takeIf { it.isNotBlank() }
        val resultNavId = twitchResultNavigationDestinationId()

        if (act == null || apiName == null || resultNavId == 0) {
            Toast.makeText(context, "Could not open Twitch profile in app", Toast.LENGTH_SHORT).show()
            return
        }

        runCatching {
            act.navigate(
                resultNavId,
                ResultFragment.newInstance(
                    overlay.profileUrl,
                    apiName,
                    overlay.displayName,
                ),
            )
        }.onFailure { error ->
            logError(error)
            Toast.makeText(context, "Could not open Twitch profile in app", Toast.LENGTH_SHORT).show()
        }
    }

    private fun View.twitchProfileButtonDefaultSize(): Int {
        return (48f * resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun configureTwitchStreamerProfileButton(button: ImageButton) {
        button.imageTintList = null
        button.scaleType = ImageView.ScaleType.CENTER_CROP
        button.adjustViewBounds = false
        button.setPadding(0, 0, 0, 0)
        button.setBackgroundResource(R.drawable.twitch_player_profile_button_bg)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            button.foreground =
                button.context.getDrawable(R.drawable.twitch_player_profile_button_focus_overlay)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            button.outlineProvider = ViewOutlineProvider.BACKGROUND
            button.clipToOutline = true
        }

        val focusedScale = 1.14f
        val normalScale = 1f
        val glowZ = 18f * button.resources.displayMetrics.density

        fun applyFocusState(hasFocus: Boolean, animate: Boolean) {
            button.isSelected = hasFocus

            if (animate) {
                button.animate()
                    .scaleX(if (hasFocus) focusedScale else normalScale)
                    .scaleY(if (hasFocus) focusedScale else normalScale)
                    .setDuration(120L)
                    .start()
            } else {
                button.scaleX = if (hasFocus) focusedScale else normalScale
                button.scaleY = if (hasFocus) focusedScale else normalScale
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val z = if (hasFocus) glowZ else 0f
                button.elevation = z
                button.translationZ = z
            }
        }

        applyFocusState(button.hasFocus(), animate = false)
        button.setOnFocusChangeListener { _, hasFocus ->
            applyFocusState(hasFocus, animate = true)
        }
    }

    private fun Bitmap.toTwitchSafeProfileBitmap(): Bitmap? {
        return runCatching {
            if (isRecycled || width <= 0 || height <= 0) {
                null
            } else if (config?.name == "HARDWARE" || config == null) {
                copy(Bitmap.Config.ARGB_8888, false)
            } else {
                this
            }
        }.onFailure { error ->
            logError(error)
        }.getOrNull()
    }

    private fun updateTwitchStreamerOverlay() {
        val root = playerBinding?.root ?: return
        val button = root.findViewById<View>(R.id.twitch_player_streamer_chip) as? ImageButton ?: return

        configureTwitchStreamerProfileButton(button)

        val overlay = currentTwitchStreamerOverlay()
        button.isVisible = overlay != null
        button.isEnabled = overlay != null
        button.isClickable = overlay != null
        button.isFocusable = overlay != null
        button.contentDescription = overlay?.let { "Open ${it.displayName} on Twitch" }

        if (overlay == null) {
            button.tag = null
            button.setImageResource(R.drawable.twitch_player_streamer_avatar_placeholder)
            button.setOnClickListener(null)
            button.setOnKeyListener(null)
            return
        }

        button.setOnClickListener {
            openTwitchStreamerProfile(overlay)
        }

        button.setOnKeyListener { _, keyCode, event ->
            if (
                event.action == KeyEvent.ACTION_UP &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == KeyEvent.KEYCODE_ENTER ||
                    keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
            ) {
                openTwitchStreamerProfile(overlay)
                true
            } else {
                false
            }
        }

        val avatarUrl = overlay.avatarUrl
        button.tag = avatarUrl
        button.setImageResource(R.drawable.twitch_player_streamer_avatar_placeholder)

        if (!avatarUrl.isNullOrBlank()) {
            ioSafe {
                val safeBitmap = context?.getImageBitmapFromUrl(avatarUrl)
                    ?.toTwitchSafeProfileBitmap()

                runOnMainThread {
                    val currentButton =
                        root.findViewById<View>(R.id.twitch_player_streamer_chip) as? ImageButton
                    if (currentButton?.tag == avatarUrl) {
                        configureTwitchStreamerProfileButton(currentButton)
                        runCatching {
                            if (safeBitmap != null && !safeBitmap.isRecycled) {
                                currentButton.setImageBitmap(safeBitmap)
                            } else {
                                currentButton.setImageResource(R.drawable.twitch_player_streamer_avatar_placeholder)
                            }
                            currentButton.imageTintList = null
                        }.onFailure { error ->
                            logError(error)
                            currentButton.setImageResource(R.drawable.twitch_player_streamer_avatar_placeholder)
                        }
                    }
                }
            }
        }
    }
// END TwitchPlayerStreamerOverlayPatch

    private fun twitchQualityLabel(link: ExtractorLink?): String {
    if (link == null) return "Unknown"

    fun cleanQualityLabel(value: String?): String? {
        val label = value
            ?.replace("%20", " ")
            ?.replace("_", " ")
            ?.replace("-", " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        return when {
            label.equals("chunked", ignoreCase = true) ||
                    label.equals("source", ignoreCase = true) -> "Source"

            label.equals("audio only", ignoreCase = true) ||
                    label.equals("audio_only", ignoreCase = true) -> "Audio Only"

            else -> label
        }
    }

    fun parseQualityToken(value: String?): String? {
        val text = value
            ?.replace("%20", " ")
            ?.replace("%2F", "/", ignoreCase = true)
            ?.replace("%3D", "=", ignoreCase = true)
            ?.replace("%26", "&", ignoreCase = true)
            ?.replace("_", " ")
            ?: return null

        if (text.contains("audio only", ignoreCase = true) ||
                text.contains("audio_only", ignoreCase = true)
        ) {
            return "Audio Only"
        }

        if (Regex("""(?i)(?:quality|q|stream|variant)=source""").containsMatchIn(text) ||
                Regex("""(?i)(^|[/_.-])source($|[/_.-])""").containsMatchIn(text) ||
                Regex("""(?i)(^|[?&/_=.-])chunked($|[&/_=.-])""").containsMatchIn(text)
        ) {
            return "Source"
        }

        val qualityMatch = Regex(
            """(?i)(^|[^0-9a-z])((?:2160|1440|1080|936|900|720|540|480|360|240|160)p(?:60|30)?)([^0-9a-z]|$)"""
        ).find(text)

        return qualityMatch?.groupValues?.getOrNull(2)?.lowercase()
    }

    val qualityFromInt = cleanQualityLabel(Qualities.getStringByInt(link.quality))
        ?.takeUnless {
            it.equals("unknown", ignoreCase = true) ||
                    it.equals("auto", ignoreCase = true) ||
                    it == "0"
        }

    if (qualityFromInt != null && !qualityFromInt.equals(link.name, ignoreCase = true)) {
        return qualityFromInt
    }

    for (candidate in listOf(link.url, link.name)) {
        parseQualityToken(candidate)?.let { return it }
    }

    return "Auto"
}
private fun showTwitchQualityDialog(): Boolean {
        if (!isTwitchPlayback()) return false

        val links = viewModel.state
            .sortLinks(currentQualityProfile)
            .filter { isTwitchExtractorLink(it.first) }

        if (links.isEmpty()) return false

        val labels = links.map { twitchQualityLabel(it.first) }
        val selectedIndex = links.indexOf(currentSelectedLink).coerceAtLeast(0)

        // TwitchInstantQualityPickerPatch:
        // Source/quality selections should apply immediately for streams.
        // Passing showApply = false hides Apply/Cancel and makes the dialog
        // select + dismiss on click. We remember the current play state and
        // resume after swapping the HLS variant so changing quality does not
        // leave the stream paused.
        activity?.showDialog(
            labels,
            selectedIndex,
            "Twitch quality",
            false,
            {},
        ) { index ->
            links.getOrNull(index)?.let { selected ->
                val resumeAfterQualityChange = player.getIsPlaying()
                loadLink(selected, true)
                if (resumeAfterQualityChange) {
                    player.handleEvent(CSPlayerEvent.Play)
                }
            }
        }

        return true
    }
override fun showMirrorsDialogue() {
        if (showTwitchQualityDialog()) return
        try {
            currentSelectedSubtitles = player.getCurrentPreferredSubtitle()
            //println("CURRENT SELECTED :$currentSelectedSubtitles of $currentSubs")
            context?.let { ctx ->
                val isPlaying = player.getIsPlaying()
                player.handleEvent(CSPlayerEvent.Pause, PlayerEventSource.UI)
                val currentSubtitles = sortSubs(viewModel.state.subtitles)

                val sourceDialog = Dialog(ctx, R.style.DialogFullscreenPlayer)
                val binding =
                    PlayerSelectSourceAndSubsBinding.inflate(LayoutInflater.from(ctx), null, false)
                sourceDialog.setContentView(binding.root)

                fixSystemBarsPadding(binding.root)
                selectSourceDialog = sourceDialog

                sourceDialog.show()
                val providerList = binding.sortProviders
                val subtitleList = binding.sortSubtitles
                val subtitleOptionList = binding.sortSubtitlesOptions

                val loadFromFileFooter: TextView =
                    layoutInflater.inflate(R.layout.sort_bottom_footer_add_choice, null) as TextView

                loadFromFileFooter.text = ctx.getString(R.string.player_load_subtitles)
                loadFromFileFooter.setOnClickListener {
                    openSubPicker()
                }
                subtitleList.addFooterView(loadFromFileFooter)

                var shouldDismiss = true

                binding.subtitleSettingsBtt.setOnClickListener {
                    safe {
                        val subtitlesFragment = SubtitlesFragment()
                        subtitlesFragment.systemBarsAddPadding = true
                        subtitlesFragment.show(this.parentFragmentManager, "SubtitleSettings")
                    }
                }

                fun dismiss() {
                    if (isPlaying) {
                        player.handleEvent(CSPlayerEvent.Play)
                    }
                    activity?.hideSystemUI()
                }

                if (subsProvidersIsActive) {
                    val currentLoadResponse = viewModel.state.generatorState?.response

                    val loadFromOpenSubsFooter: TextView = layoutInflater.inflate(
                        R.layout.sort_bottom_footer_add_choice, null
                    ) as TextView

                    loadFromOpenSubsFooter.text =
                        ctx.getString(R.string.player_load_subtitles_online)

                    loadFromOpenSubsFooter.setOnClickListener {
                        shouldDismiss = false
                        sourceDialog.dismissSafe(activity)
                        selectSourceDialog = null
                        openOnlineSubPicker(it.context, currentLoadResponse) {
                            dismiss()
                        }
                    }
                    subtitleList.addFooterView(loadFromOpenSubsFooter)

                    // subs from 1 button here
                    val metadata = getMetaData()
                    val queryName = metadata.name ?: currentLoadResponse?.name
                    if (queryName != null) {
                        val currentLanguageTagIETF: String = getAutoSelectLanguageTagIETF()
                        val loadFromFirstSubsFooter: TextView = layoutInflater.inflate(
                            R.layout.sort_bottom_footer_add_choice, null
                        ) as TextView

                        loadFromFirstSubsFooter.text =
                            ctx.getString(R.string.player_load_one_subtitle_online)

                        loadFromFirstSubsFooter.setOnClickListener {
                            sourceDialog.dismissSafe(activity)
                            selectSourceDialog = null
                            showToast(R.string.loading)
                            addFirstSub(
                                SubtitleSearch(
                                    query = queryName,
                                    imdbId = currentLoadResponse?.getImdbId(),
                                    tmdbId = currentLoadResponse?.getTMDbId()?.toInt(),
                                    malId = currentLoadResponse?.getMalId()?.toInt(),
                                    aniListId = currentLoadResponse?.getAniListId()?.toInt(),
                                    epNumber = metadata.episode,
                                    seasonNumber = metadata.season,
                                    lang = currentLanguageTagIETF.ifBlank { null },
                                    year = viewModel.currentSubtitleYear.value
                                )
                            )
                        }
                        subtitleList.addFooterView(loadFromFirstSubsFooter)
                    }
                }

                var sourceIndex = 0
                var startSource = 0
                var sortedUrls = emptyList<Pair<ExtractorLink?, ExtractorUri?>>()

                fun refreshLinks(qualityProfile: Int) {
                    sortedUrls = viewModel.state.sortLinks(qualityProfile)
                    if (sortedUrls.isEmpty()) {
                        sourceDialog.findViewById<LinearLayout>(R.id.sort_sources_holder)?.isGone =
                            true
                    } else {
                        startSource = sortedUrls.indexOf(currentSelectedLink)
                        sourceIndex = startSource

                        val sourcesArrayAdapter =
                            ArrayAdapter<String>(ctx, R.layout.sort_bottom_single_choice)

                        sourcesArrayAdapter.addAll(sortedUrls.map { (link, uri) ->
                            val name = link?.name ?: uri?.name ?: "NULL"
                            "$name ${Qualities.getStringByInt(link?.quality)}"
                        })

                        providerList.choiceMode = AbsListView.CHOICE_MODE_SINGLE
                        providerList.adapter = sourcesArrayAdapter
                        providerList.setSelection(sourceIndex)
                        providerList.setItemChecked(sourceIndex, true)

                        providerList.setOnItemClickListener { _, _, which, _ ->
                            sourceIndex = which
                            providerList.setItemChecked(which, true)
                        }

                        providerList.setOnItemLongClickListener { _, _, position, _ ->
                            sortedUrls.getOrNull(position)?.first?.url?.let {
                                clipboardHelper(
                                    txt(R.string.video_source),
                                    it
                                )
                            }
                            true
                        }
                    }
                }

                refreshLinks(currentQualityProfile)

                sourceDialog.setOnDismissListener {
                    if (shouldDismiss) dismiss()
                    selectSourceDialog = null
                }


                val subsArrayAdapter =
                    ArrayAdapter<Spanned>(ctx, R.layout.sort_bottom_single_choice)
                subsArrayAdapter.add(ctx.getString(R.string.no_subtitles).html())

                val subtitlesGrouped =
                    currentSubtitles.groupBy { it.originalName }.map { (key, value) ->
                        key to value.sortedBy { it.nameSuffix.toIntOrNull() ?: 0 }
                    }.toMap()
                val subtitlesGroupedList = subtitlesGrouped.entries.toList()

                val subtitles = subtitlesGrouped.map { it.key.html() }

                val subtitleGroupIndexStart =
                    subtitlesGrouped.keys.indexOf(currentSelectedSubtitles?.originalName) + 1
                var subtitleGroupIndex = subtitleGroupIndexStart

                val subtitleOptionIndexStart =
                    subtitlesGrouped[currentSelectedSubtitles?.originalName]?.indexOfFirst { it.nameSuffix == currentSelectedSubtitles?.nameSuffix }
                        ?: 0
                var subtitleOptionIndex = subtitleOptionIndexStart

                subsArrayAdapter.addAll(subtitles)

                subtitleList.adapter = subsArrayAdapter
                subtitleList.choiceMode = AbsListView.CHOICE_MODE_SINGLE

                subtitleList.setSelection(subtitleGroupIndex)
                subtitleList.setItemChecked(subtitleGroupIndex, true)

                val subsOptionsArrayAdapter =
                    ArrayAdapter<Spanned>(ctx, R.layout.sort_bottom_single_choice)

                subtitleOptionList.adapter = subsOptionsArrayAdapter
                subtitleOptionList.choiceMode = AbsListView.CHOICE_MODE_SINGLE

                fun updateSubtitleOptionList() {
                    subsOptionsArrayAdapter.clear()

                    val subtitleOptions =
                        subtitlesGroupedList
                            .getOrNull(subtitleGroupIndex - 1)?.value?.map { subtitle ->
                                val nameSuffix = subtitle.nameSuffix.html()
                                nameSuffix.ifBlank {
                                    when (subtitle.origin) {
                                        SubtitleOrigin.URL -> txt(R.string.subtitles_from_online)
                                        SubtitleOrigin.DOWNLOADED_FILE -> txt(R.string.downloaded)
                                        SubtitleOrigin.EMBEDDED_IN_VIDEO -> txt(R.string.subtitles_from_embedded)
                                    }.asString(ctx).toSpanned()
                                }
                            }
                            ?: emptyList()

                    // Show nothing if there is nothing to select
                    val shouldHide = subtitleOptions.size < 2
                    subtitleOptionList.isGone = shouldHide // Make it easier to click
                    if (shouldHide) return

                    subsOptionsArrayAdapter.addAll(subtitleOptions)

                    subtitleOptionList.setSelection(subtitleOptionIndex)
                    subtitleOptionList.setItemChecked(subtitleOptionIndex, true)
                }

                updateSubtitleOptionList()

                subtitleList.setOnItemClickListener { _, _, which, _ ->
                    if (which > subtitlesGrouped.size) {
                        // Since android TV is funky the setOnItemClickListener will be triggered
                        // instead of setOnClickListener when selecting. To override this we programmatically
                        // click the view when selecting an item outside the list.

                        // Cheeky way of getting the view at that position to click it
                        // to avoid keeping track of the various footers.
                        // getChildAt() gives null :(
                        val child = subtitleList.adapter.getView(which, null, subtitleList)
                        child?.performClick()
                    } else {
                        if (subtitleGroupIndex != which) {
                            subtitleGroupIndex = which
                            subtitleOptionIndex =
                                if (subtitleGroupIndex == subtitleGroupIndexStart) {
                                    subtitleOptionIndexStart
                                } else {
                                    0
                                }
                        }
                        subtitleList.setItemChecked(which, true)
                        updateSubtitleOptionList()
                    }
                }

                subtitleOptionList.setOnItemClickListener { _, _, which, _ ->
                    if (which >= (subtitlesGroupedList.getOrNull(subtitleGroupIndex - 1)?.value?.size
                            ?: -1)
                    ) {
                        val child = subtitleOptionList.adapter.getView(which, null, subtitleList)
                        child?.performClick()
                    } else {
                        subtitleOptionIndex = which
                        subtitleOptionList.setItemChecked(which, true)
                    }
                }

                binding.cancelBtt.setOnClickListener {
                    sourceDialog.dismissSafe(activity)
                    this.selectSourceDialog = null
                }

                fun setProfileName(profile: Int) {
                    binding.sourceSettingsBtt.setText(
                        QualityDataHelper.getProfileName(
                            profile
                        )
                    )
                }
                setProfileName(currentQualityProfile)

                binding.profilesClickSettings.setOnClickListener {
                    val activity = activity ?: return@setOnClickListener
                    val dialog = QualityProfileDialog(
                        activity,
                        R.style.DialogFullscreenPlayer,
                        viewModel.state.links.mapNotNull {
                            it.first?.let { extractorLink ->
                                LinkSource(
                                    extractorLink
                                )
                            }
                        },
                        currentQualityProfile
                    ) { profile ->
                        currentQualityProfile = profile.id
                        setProfileName(profile.id)
                    }

                    dialog.setOnDismissListener {
                        viewModel.state.clearSortedLinksCache()
                        refreshLinks(currentQualityProfile)
                    }

                    dialog.show()
                }

                binding.subtitlesEncodingFormat.apply {
                    val settingsManager = PreferenceManager.getDefaultSharedPreferences(ctx)

                    val prefNames = ctx.resources.getStringArray(R.array.subtitles_encoding_list)
                    val prefValues = ctx.resources.getStringArray(R.array.subtitles_encoding_values)

                    val value = settingsManager.getString(
                        ctx.getString(R.string.subtitles_encoding_key), null
                    )
                    val index = prefValues.indexOf(value)
                    text = prefNames[if (index == -1) 0 else index]
                }

                binding.subtitlesEncodingFormat.setOnClickListener {
                    val settingsManager = PreferenceManager.getDefaultSharedPreferences(ctx)

                    val prefNames = ctx.resources.getStringArray(R.array.subtitles_encoding_list)
                    val prefValues = ctx.resources.getStringArray(R.array.subtitles_encoding_values)

                    val currentPrefMedia = settingsManager.getString(
                        ctx.getString(R.string.subtitles_encoding_key), null
                    )

                    shouldDismiss = false
                    sourceDialog.dismissSafe(activity)
                    selectSourceDialog = null

                    val index = prefValues.indexOf(currentPrefMedia)
                    activity?.showDialog(
                        prefNames.toList(),
                        if (index == -1) 0 else index,
                        ctx.getString(R.string.subtitles_encoding),
                        true,
                        {}) {
                        settingsManager.edit {
                            putString(
                                ctx.getString(R.string.subtitles_encoding_key), prefValues[it]
                            )
                        }
                        updateForcedEncoding(ctx)
                        dismiss()
                        player.seekTime(-1) // to update subtitles, a dirty trick
                    }
                }

                binding.applyBtt.setOnClickListener {
                    var init = sourceIndex != startSource
                    if (subtitleGroupIndex != subtitleGroupIndexStart || subtitleOptionIndex != subtitleOptionIndexStart) {
                        init = init or if (subtitleGroupIndex <= 0) {
                            noSubtitles()
                        } else {
                            subtitlesGroupedList.getOrNull(subtitleGroupIndex - 1)?.value?.getOrNull(
                                subtitleOptionIndex
                            )?.let {
                                setSubtitles(it, true)
                            } ?: false
                        }
                    }
                    if (init) {
                        sortedUrls.getOrNull(sourceIndex)?.let {
                            loadLink(it, true)
                        }
                    }
                    sourceDialog.dismissSafe(activity)
                    selectSourceDialog = null
                }
            }
        } catch (e: Exception) {
            logError(e)
        }
    }

    override fun showTracksDialogue() {
        try {
            //println("CURRENT SELECTED :$currentSelectedSubtitles of $currentSubs")
            context?.let { ctx ->
                val tracks = player.getVideoTracks()

                val isPlaying = player.getIsPlaying()
                player.handleEvent(CSPlayerEvent.Pause)

                val currentVideoTracks = tracks.allVideoTracks.sortedBy {
                    it.height?.times(-1)
                }
                val currentAudioTracks = tracks.allAudioTracks
                val binding: PlayerSelectTracksBinding =
                    PlayerSelectTracksBinding.inflate(LayoutInflater.from(ctx), null, false)
                val trackDialog = Dialog(ctx, R.style.DialogFullscreenPlayer)
                this.selectTrackDialog = trackDialog
                trackDialog.setContentView(binding.root)
                trackDialog.show()

                fixSystemBarsPadding(binding.root)

                // selectTracksDialog = tracksDialog

                val videosList = binding.videoTracksList
                val audioList = binding.autoTracksList

                binding.videoTracksHolder.isVisible = currentVideoTracks.size > 1
                binding.audioTracksHolder.isVisible = currentAudioTracks.size > 1

                fun dismiss() {
                    if (isPlaying) {
                        player.handleEvent(CSPlayerEvent.Play)
                    }
                    activity?.hideSystemUI()
                }

                val videosArrayAdapter =
                    ArrayAdapter<String>(ctx, R.layout.sort_bottom_single_choice)

                videosArrayAdapter.addAll(currentVideoTracks.mapIndexed { index, format ->
                    format.label
                        ?: (if (format.height == NO_VALUE || format.width == NO_VALUE) index else "${format.width}x${format.height}").toString()
                })

                videosList.choiceMode = AbsListView.CHOICE_MODE_SINGLE
                videosList.adapter = videosArrayAdapter

                // Sometimes the data is not the same because some data gets resolved at different stages i think
                var videoIndex = currentVideoTracks.indexOf(tracks.currentVideoTrack).takeIf {
                    it != -1
                } ?: currentVideoTracks.indexOfFirst {
                    tracks.currentVideoTrack?.id == it.id
                }

                videosList.setSelection(videoIndex)
                videosList.setItemChecked(videoIndex, true)

                videosList.setOnItemClickListener { _, _, which, _ ->
                    videoIndex = which
                    videosList.setItemChecked(which, true)
                }

                trackDialog.setOnDismissListener {
                    dismiss()
                    // selectTracksDialog = null
                }

                var audioIndexStart = currentAudioTracks.indexOfFirst { track ->
                    track.id == tracks.currentAudioTrack?.id &&
                            track.formatIndex == tracks.currentAudioTrack?.formatIndex
                }.coerceAtLeast(0)

                val audioArrayAdapter =
                    ArrayAdapter<String>(ctx, R.layout.sort_bottom_single_choice)

                audioArrayAdapter.addAll(
                    currentAudioTracks.mapIndexed { _, track ->

                        val language = (
                                track.language?.trim()?.let { raw ->
                                    fromTagToLanguageName(raw)
                                        ?: fromTagToLanguageName(
                                            raw.replace('_', '-').substringBefore('-').lowercase()
                                        )
                                        ?: raw
                                }
                                    ?: track.label
                                    ?: "Audio"
                                ).replaceFirstChar { it.uppercaseChar() }

                        val codec = audioCodecName(track.sampleMimeType)

                        val channelCount = track.channelCount

                        val channels = when {
                            // May be below 1 or null when unknown
                            channelCount == null || channelCount <= 0 -> ""
                            channelCount == 1 -> "Mono"
                            channelCount == 2 -> "Stereo"
                            channelCount == 6 -> "5.1"
                            channelCount == 8 -> "7.1"
                            else -> "${channelCount}ch"
                        }

                        listOfNotNull(
                            language.takeIf { it.isNotBlank() }
                                ?.replaceFirstChar { it.uppercaseChar() },
                            channels.takeIf { it.isNotBlank() },
                            codec.takeIf { it.isNotBlank() }?.uppercase()
                        ).joinToString(" • ")


                    }
                )

                audioList.adapter = audioArrayAdapter
                audioList.choiceMode = AbsListView.CHOICE_MODE_SINGLE

                audioList.setSelection(audioIndexStart)
                audioList.setItemChecked(audioIndexStart, true)

                audioList.setOnItemClickListener { _, _, which, _ ->
                    audioIndexStart = which
                    audioList.setItemChecked(which, true)
                }

                binding.cancelBtt.setOnClickListener {
                    trackDialog.dismissSafe(activity)
                    this.selectTrackDialog = null
                }

                binding.applyBtt.setOnClickListener {
                    val currentTrack = currentAudioTracks.getOrNull(audioIndexStart)
                    player.setPreferredAudioTrack(
                        currentTrack?.language,
                        currentTrack?.id,
                        currentTrack?.formatIndex,
                    )

                    val currentVideo = currentVideoTracks.getOrNull(videoIndex)
                    val width = currentVideo?.width ?: NO_VALUE
                    val height = currentVideo?.height ?: NO_VALUE
                    if (width != NO_VALUE && height != NO_VALUE) {
                        player.setMaxVideoSize(width, height, currentVideo?.id)
                    }
                    trackDialog.dismissSafe(activity)
                    this.selectTrackDialog = null
                }
            }
        } catch (e: Exception) {
            logError(e)
        }
    }

    override fun playerError(exception: Throwable) {
        val currentUrl =
            currentSelectedLink?.let { it.first?.url ?: it.second?.uri?.toString() } ?: "unknown"
        val headers = currentSelectedLink?.first?.headers?.toString() ?: "none"
        val referer = currentSelectedLink?.first?.referer ?: "none"
        Log.e(
            TAG,
            "playerError: $currentSelectedLink, " +
                    "type=${exception::class.qualifiedName}, " +
                    "message=${exception.message}, url=$currentUrl, headers=$headers, " +
                    "referer=$referer, position=${player.getPosition() ?: "unknown"}, " +
                    "duration=${player.getDuration() ?: "unknown"}, " +
                    "isPlaying=${player.getIsPlaying()}", exception
        )

        if (!hasNextMirror()) {
            viewModel.forceClearCache = true
        }
        super.playerError(exception)
    }

    private fun noLinksFound() {
        viewModel.forceClearCache = true

        showToast(R.string.no_links_found_toast, Toast.LENGTH_SHORT)
        activity?.popCurrentPage()
    }

    private fun startPlayer() {
        // We don't want double load when you skip loading
        if (isPlayerActive.get()) {
            return
        }

        val links = viewModel.state.sortLinks(currentQualityProfile)
        if (links.isEmpty()) {
            noLinksFound()
            return
        }
        // Atomic operation to prevent double loading
        if (!isPlayerActive.compareAndSet(false, true)) {
            return
        }
        loadLink(links.first(), false)
        showPlayerMetadata()
    }

    private fun showPlayerMetadata() {
        val overlay = playerBinding?.playerMetadataScrim ?: return

        val titleView = overlay.findViewById<TextView>(R.id.player_movie_title)
        val logoView = overlay.findViewById<ImageView>(R.id.player_movie_logo)
        val metaView = overlay.findViewById<TextView>(R.id.player_movie_meta)
        val descView = overlay.findViewById<TextView>(R.id.player_movie_overview)

        val load = viewModel.state.generatorState?.response ?: return
        val episode = currentMeta as? ResultEpisode
        titleView.text = load.name

        bindLogo(
            url = load.logoUrl,
            headers = load.posterHeaders,
            titleView = titleView,
            logoView = logoView
        )

        val meta = arrayOf(
            load.tags?.takeIf { it.isNotEmpty() }?.take(6)?.joinToString(", "),
            load.year?.toString(),
            if (!load.type.isMovieType())
                context?.getShortSeasonText(
                    episode = episode?.episode,
                    season = episode?.season
                )
            else null,
            load.score?.let { "⭐ $it" }
        ).filterNotNull()
            .joinToString(" • ")

        metaView.text = meta
        metaView.isVisible = meta.isNotBlank()


        val description = load.plot

        if (!description.isNullOrBlank()) {
            descView.isVisible = true
            descView.text = description.html()
        } else {
            descView.isVisible = false

        }
    }

    override fun nextEpisode() {
        if (viewModel.hasNextEpisode() == true) {
            isNextEpisode = true
            releasePlayer()
            viewModel.loadLinksNext()
        }
    }

    override fun prevEpisode() {
        if (viewModel.hasPrevEpisode() == true) {
            isNextEpisode = true
            releasePlayer()
            viewModel.loadLinksPrev()
        }
    }

    override fun hasNextMirror(): Boolean {
        val links = viewModel.state.sortLinks(currentQualityProfile)
        return links.isNotEmpty() && links.indexOf(currentSelectedLink) + 1 < links.size
    }

    override fun nextMirror() {
        val links = viewModel.state.sortLinks(currentQualityProfile)
        if (links.isEmpty()) {
            noLinksFound()
            return
        }

        val newIndex = links.indexOf(currentSelectedLink) + 1
        if (newIndex >= links.size) {
            noLinksFound()
            return
        }

        loadLink(links[newIndex], true)
    }

    override fun onDestroy() {
        ResultFragment.updateUI()
        currentVerifyLink?.cancel()
        super.onDestroy()
    }

    var maxEpisodeSet: Int? = null
    var hasRequestedStamps: Boolean = false
    override fun playerPositionChanged(position: Long, duration: Long) {
        // Don't save livestream data
        if ((currentMeta as? ResultEpisode)?.tvType?.isLiveStream() == true) return

        // Don't save NSFW data
        if ((currentMeta as? ResultEpisode)?.tvType == TvType.NSFW) return

        if (duration <= 0L) return // idk how you achieved this, but div by zero crash
        if (!hasRequestedStamps) {
            hasRequestedStamps = true
            val fetchStamps = context?.let { ctx ->
                val settingsManager = PreferenceManager.getDefaultSharedPreferences(ctx)
                settingsManager.getBoolean(
                    ctx.getString(R.string.enable_skip_op_from_database),
                    true
                )
            } ?: true
            if (fetchStamps)
                viewModel.loadStamps(duration)
        }

        val percentage = position * 100L / duration

        DataStoreHelper.setViewPosAndResume(
            viewModel.state.generatorState?.id,
            position,
            duration,
            currentMeta,
            nextMeta
        )

        var isOpVisible = false
        when (val meta = currentMeta) {
            is ResultEpisode -> {
                if (percentage >= UPDATE_SYNC_PROGRESS_PERCENTAGE && (maxEpisodeSet
                        ?: -1) < meta.episode
                ) {
                    context?.let { ctx ->
                        val settingsManager = PreferenceManager.getDefaultSharedPreferences(ctx)
                        if (settingsManager.getBoolean(
                                ctx.getString(R.string.episode_sync_enabled_key), true
                            )
                        ) maxEpisodeSet = meta.episode
                        sync.modifyMaxEpisode(meta.totalEpisodeIndex ?: meta.episode)
                    }
                }

                if (meta.tvType.isAnimeOp()) isOpVisible = percentage < SKIP_OP_VIDEO_PERCENTAGE
            }
        }

        playerBinding?.playerSkipOp?.isVisible = isOpVisible

        when {
            isLayout(PHONE) ->
                playerBinding?.playerSkipEpisode?.isVisible =
                    !isOpVisible && viewModel.hasNextEpisode() == true

            else -> {
                val hasNextEpisode = viewModel.hasNextEpisode() == true
                playerBinding?.playerGoForward?.isVisible = hasNextEpisode
                playerBinding?.playerGoForwardRoot?.isVisible = hasNextEpisode
            }

        }

        if (percentage >= PRELOAD_NEXT_EPISODE_PERCENTAGE) {
            viewModel.preLoadNextLinks()
        }
    }

    private fun getAutoSelectSubtitle(
        subtitles: Set<SubtitleData>, settings: Boolean, downloads: Boolean
    ): SubtitleData? {
        val langCode = preferredAutoSelectSubtitles ?: return null
        if (downloads) {
            sortSubs(subtitles).firstOrNull {
                it.origin == SubtitleOrigin.DOWNLOADED_FILE && it.matchesLanguageCode(
                    langCode
                )
            }?.let { return it }
        }

        if (!settings) return null

        return sortSubs(subtitles).firstOrNull { it.matchesLanguageCode(langCode) }
    }

    private fun autoSelectFromSettings(): Boolean {
        // auto select subtitle based on settings
        val langCode = preferredAutoSelectSubtitles
        val current = player.getCurrentPreferredSubtitle()
        Log.i(TAG, "autoSelectFromSettings = $current")
        context?.let { ctx ->
            // Only use the player preferred subtitle if it matches the available language
            if (current != null && (langCode == null || current.matchesLanguageCode(langCode))) {
                if (setSubtitles(current, false)) {
                    player.saveData()
                    player.reloadPlayer(ctx)
                    player.handleEvent(CSPlayerEvent.Play)
                    return true
                }
            } else if (!langCode.isNullOrEmpty()) {
                getAutoSelectSubtitle(
                    viewModel.state.subtitles, settings = true, downloads = false
                )?.let { sub ->
                    if (setSubtitles(sub, false)) {
                        player.saveData()
                        player.reloadPlayer(ctx)
                        player.handleEvent(CSPlayerEvent.Play)
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun autoSelectFromDownloads() {
        if (player.getCurrentPreferredSubtitle() != null) {
            return
        }
        val sub =
            getAutoSelectSubtitle(viewModel.state.subtitles, settings = false, downloads = true)
                ?: return
        val ctx = context ?: return
        if (!setSubtitles(sub, false)) {
            return
        }
        player.saveData()
        player.reloadPlayer(ctx)
        player.handleEvent(CSPlayerEvent.Play)
    }

    private fun autoSelectSubtitles() {
        //Log.i(TAG, "autoSelectSubtitles")
        safe {
            if (!autoSelectFromSettings()) {
                autoSelectFromDownloads()
            }
        }
    }

    private fun getHeaderName(): String? {
        return when (val meta = currentMeta) {
            is ResultEpisode -> meta.headerName
            is ExtractorUri -> meta.headerName
            else -> null
        }
    }

    private fun getPlayerVideoTitle(): String {
        var headerName: String? = null
        var subName: String? = null
        var episode: Int? = null
        var season: Int? = null
        var tvType: TvType? = null

        when (val meta = currentMeta) {
            is ResultEpisode -> {
                headerName = meta.headerName
                subName = meta.name
                episode = meta.episode
                season = meta.season
                tvType = meta.tvType
            }

            is ExtractorUri -> {
                headerName = meta.headerName
                subName = meta.name
                episode = meta.episode
                season = meta.season
                tvType = meta.tvType
            }
        }
        context?.let { ctx ->
            //Generate video title
            val playerVideoTitle = if (headerName != null) {
                (headerName + if (tvType.isEpisodeBased() && episode != null) if (season == null) " - ${
                    ctx.getString(
                        R.string.episode
                    )
                } $episode"
                else " \"${ctx.getString(R.string.season_short)}${season}:${
                    ctx.getString(
                        R.string.episode_short
                    )
                }${episode}\""
                else "") + if (subName.isNullOrBlank() || subName == headerName) "" else " - $subName"
            } else {
                ""
            }
            return playerVideoTitle
        }
        return ""
    }

    fun setTitle() {
        var playerVideoTitle = getPlayerVideoTitle()

        //Hide title, if set in setting
        if (limitTitle < 0) {
            playerBinding?.playerVideoTitle?.visibility = View.GONE
        } else {
            //Truncate video title if it exceeds limit
            val differenceInLength = playerVideoTitle.length - limitTitle
            val margin = 3 //If the difference is smaller than or equal to this value, ignore it
            if (limitTitle > 0 && differenceInLength > margin) {
                playerVideoTitle = playerVideoTitle.substring(0, limitTitle - 1) + "..."
            }
        }
        val isFiller: Boolean? = (currentMeta as? ResultEpisode)?.isFiller

        playerBinding?.playerEpisodeFillerHolder?.isVisible = isFiller ?: false
        playerBinding?.playerVideoTitle?.text = playerVideoTitle
        playerBinding?.offlinePin?.isVisible = viewModel.generator is DownloadFileGenerator
    }

    fun setPlayerDimen(widthHeight: Pair<Int, Int>?) {
        val resolution = widthHeight?.let { "${it.first}x${it.second}" }
        val name = currentSelectedLink?.first?.name ?: currentSelectedLink?.second?.name
        val title = getHeaderName()

        val result = listOfNotNull(
            title?.takeIf { showTitle && it.isNotBlank() },
            name?.takeIf { showName && it.isNotBlank() },
            resolution?.takeIf { showResolution && it.isNotBlank() },
        ).joinToString(" - ")

        playerBinding?.playerVideoTitleRez?.apply {
            text = result
            isVisible = result.isNotBlank()
        }
    }


    private fun videoCodecName(mime: String?): String? {
        val m = mime?.lowercase() ?: return null
        return when {
            m.contains("avc") || m.contains("h264") -> "AVC"
            m.contains("hevc") || m.contains("h265") -> "HEVC"
            m.contains("av1") -> "AV1"
            m.contains("vp9") -> "VP9"
            m.contains("vp8") -> "VP8"
            "/" in m -> m.substringAfter("/").uppercase()
            else -> m.uppercase()
        }
    }

    private fun audioCodecName(mime: String?): String {
        val m = mime?.lowercase()?.trim().orEmpty()
        if (m.isBlank()) return ""
        return when {
            m.contains("eac3-joc") -> "Dolby Atmos"
            m.contains("truehd") -> "TrueHD"
            m.contains("eac3") -> "E-AC3"
            m.contains("ac-3") || m.contains("ac3") -> "AC3"
            m.contains("aac") || m.contains("mp4a") -> "AAC"
            m.contains("opus") -> "Opus"
            m.contains("vorbis") -> "Vorbis"
            m.contains("mp3") -> "MP3"
            m.contains("flac") -> "FLAC"
            m.contains("dts") -> "DTS"
            m.contains("pcm") -> "PCM"
            m.contains("alac") -> "ALAC"
            m.contains("amr") -> "AMR"
            m.contains("/") -> m.substringAfter("/").uppercase().takeIf { it.isNotBlank() } ?: ""
            else -> ""
        }
    }

    private fun updatePlayerInfo() {
        val tracks = player.getVideoTracks()

        val videoTrack = tracks.currentVideoTrack
        val audioTrack = tracks.currentAudioTrack

        val ctx = context ?: return
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        showMediaInfo = prefs.getBoolean(ctx.getString(R.string.show_media_info_key), false)

        val videoCodec = videoCodecName(videoTrack?.sampleMimeType)
        val audioCodec = audioCodecName(audioTrack?.sampleMimeType)
        val languageName = fromTagToLanguageName(audioTrack?.language)
        val label = audioTrack?.label

        val channelCount = audioTrack?.channelCount

        val channels = when {
            // May be below 1 or null when unknown
            channelCount == null || channelCount <= 0 -> ""
            channelCount == 1 -> "Mono"
            channelCount == 2 -> "Stereo"
            channelCount == 6 -> "5.1"
            channelCount == 8 -> "7.1"
            else -> "${channelCount}ch"
        }

        val language = languageName?.takeIf { it.isNotBlank() }?.let { lang ->
            label?.takeIf { it.isNotBlank() && !it.equals(lang, true) }
                ?.let { lang }
                ?: lang
        } ?: label?.takeIf { it.isNotBlank() }

        val stats = arrayOf(
            videoCodec,
            language,
            channels,
            audioCodec
        ).filter { !it.isNullOrBlank() }.joinToString(" • ")

        playerBinding?.playerVideoInfo?.apply {
            text = stats
            isVisible = showMediaInfo && stats.isNotBlank()
        }
    }

    override fun playerDimensionsLoaded(width: Int, height: Int) {
        super.playerDimensionsLoaded(width, height)
        setPlayerDimen(width to height)
    }

    private fun unwrapBundle(savedInstanceState: Bundle?) {
        Log.i(TAG, "unwrapBundle = $savedInstanceState")
        savedInstanceState?.let { bundle ->
            sync.addSyncs(bundle.getSafeSerializable<HashMap<String, String>>("syncData"))
        }
    }

    /**
     * This is used instead of layout-television to follow the
     * settings and some TV devices are not classified as TV
     * for some reason.
     */
    override fun pickLayout(): Int =
        if (isLayout(TV or EMULATOR)) R.layout.fragment_player_tv else R.layout.fragment_player

    var skipAnimator: ValueAnimator? = null
    var skipIndex = 0

    private fun displayTimeStamp(show: Boolean) {
        if (timestampShowState == show) return
        skipIndex++
        timestampShowState = show
        playerBinding?.skipChapterButton?.apply {
            val showWidth = 170.toPx
            val noShowWidth = 10.toPx
            //if((show && width == showWidth) || (!show && width == noShowWidth)) {
            //    return
            //}
            val to = if (show) showWidth else noShowWidth
            val from = if (!show) showWidth else noShowWidth

            skipAnimator?.cancel()
            isVisible = true

            /** Focus instantly to make the focus color appear instantly */
            if (show && !isShowing) {
                // Automatically request focus if the menu is not opened
                playerBinding?.skipChapterButton?.requestFocus()
            }

            // just in case
            val lay = layoutParams
            lay.width = from
            layoutParams = lay
            skipAnimator = ValueAnimator.ofInt(
                from, to
            ).apply {
                addListener(onEnd = {
                    if (!show) {
                        playerBinding?.skipChapterButton?.isVisible = false
                        if (!isShowing) {
                            // Automatically return focus to play pause
                            playerBinding?.playerPausePlay?.requestFocus()
                        }
                    }
                })
                addUpdateListener { valueAnimator ->
                    val value = valueAnimator.animatedValue as Int
                    val layoutParams: ViewGroup.LayoutParams = layoutParams
                    layoutParams.width = value
                    setLayoutParams(layoutParams)
                }
                duration = 500
                start()
            }
        }
    }

    override fun onTimestampSkipped(timestamp: VideoSkipStamp) {
        displayTimeStamp(false)
    }

    override fun onTimestamp(timestamp: VideoSkipStamp?) {
        if (timestamp != null) {
            playerBinding?.skipChapterButton?.setText(timestamp.uiText)
            displayTimeStamp(true)
            val currentIndex = skipIndex
            playerBinding?.skipChapterButton?.handler?.postDelayed({
                if (skipIndex == currentIndex)
                    displayTimeStamp(false)
            }, 6000)
        } else {
            displayTimeStamp(false)
        }
    }

    override fun isThereEpisodes(): Boolean {
        // Checks if there is a second episode of type ResultEpisode
        // => There exists more than 1 episode, and they are all ResultEpisode
        return viewModel.state.generatorState?.allMeta?.getOrNull(1) as? ResultEpisode != null
    }

    override fun showEpisodesOverlay() {
        try {
            playerBinding?.apply {
                playerEpisodeList.setRecycledViewPool(EpisodeAdapter.sharedPool)
                playerEpisodeList.adapter = EpisodeAdapter(
                    false,
                    { episodeClick ->
                        if (episodeClick.action == ACTION_CLICK_DEFAULT) {
                            isNextEpisode = false
                            releasePlayer()
                            playerEpisodeOverlay.isGone = true
                            episodeClick.position?.let { viewModel.loadThisEpisode(it) }
                        }
                    },
                    { downloadClickEvent ->
                        DownloadButtonSetup.handleDownloadClick(downloadClickEvent)
                    }
                )
                playerEpisodeList.setLinearListLayout(
                    isHorizontal = false,
                    nextUp = FOCUS_SELF,
                    nextDown = FOCUS_SELF,
                    nextRight = FOCUS_SELF,
                )
                val episodes = allMeta ?: emptyList()
                (playerEpisodeList.adapter as? EpisodeAdapter)?.submitList(episodes)

                // Scroll to current episode
                viewModel.state.generatorState?.index?.let { index ->
                    playerEpisodeList.scrollToPosition(index)
                    // Ensure focus on tv
                    if (isLayout(TV)) {
                        playerEpisodeList.post {
                            val viewHolder =
                                playerEpisodeList.findViewHolderForAdapterPosition(index)
                            viewHolder?.itemView?.requestFocus()
                            viewHolder?.itemView?.let { itemView ->
                                itemView.isFocusableInTouchMode = true
                                itemView.requestFocus()
                            }
                        }
                    }
                }

                // update overlay season title
                var lastTopIndex = -1
                playerEpisodeList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                        val layoutManager =
                            recyclerView.layoutManager as? LinearLayoutManager ?: return
                        val topIndex = layoutManager.findFirstCompletelyVisibleItemPosition()
                        if (topIndex != RecyclerView.NO_POSITION && topIndex != lastTopIndex) {
                            @Suppress("AssignedValueIsNeverRead")
                            lastTopIndex = topIndex
                            val topItem = episodes.getOrNull(topIndex)
                            topItem?.let {
                                playerEpisodeOverlayTitle.setText(
                                    ResultViewModel2.seasonToTxt(
                                        topItem.seasonData,
                                        topItem.seasonIndex
                                    )
                                )
                            }
                        }
                    }
                })
            }
        } catch (e: Exception) {
            logError(e)
        }
    }

    @MainThread
    fun releasePlayer() {
        player.release()
        currentSelectedSubtitles = null
        currentSelectedLink = null
        isPlayerActive.set(false)
        binding?.overlayLoadingSkipButton?.isVisible = false
        binding?.playerLoadingOverlay?.isVisible = true
        uiReset()
        updateTwitchStreamerOverlay()
    }

    fun exitPlayer() {
        playerHostView?.exitFullscreen()
        player.release()
        activity?.popCurrentPage()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("index", viewModel.episodeIndex)
        super.onSaveInstanceState(outState)
    }

    override fun onBindingCreated(binding: FragmentPlayerBinding, savedInstanceState: Bundle?) {
        viewModel = ViewModelProvider(this)[PlayerGeneratorViewModel::class.java]
        sync = ViewModelProvider(this)[SyncViewModel::class.java]

        val uuid = savedInstanceState?.getString("uuid") ?: arguments?.getString("uuid")
        val index = savedInstanceState?.getInt("index") ?: arguments?.getInt("index")
        val generator = generators[uuid]

        unwrapBundle(savedInstanceState)
        unwrapBundle(arguments)

        super.onBindingCreated(binding, savedInstanceState)

        // Avoid showing no links found
        if (generator == null || index == null) {
            exitPlayer()
            return
        }
        viewModel.attachGenerator(generator, index)

        context?.let { ctx ->
            val settingsManager = PreferenceManager.getDefaultSharedPreferences(ctx)
            showName = settingsManager.getBoolean(ctx.getString(R.string.show_name_key), true)
            showResolution =
                settingsManager.getBoolean(ctx.getString(R.string.show_resolution_key), true)
            showMediaInfo =
                settingsManager.getBoolean(ctx.getString(R.string.show_media_info_key), false)
            limitTitle = settingsManager.getInt(ctx.getString(R.string.prefer_title_limit_key), 0)
            updateForcedEncoding(ctx)
            viewModel.filterSubByLang =
                settingsManager.getBoolean(getString(R.string.filter_sub_lang_key), false)
            if (viewModel.filterSubByLang) {
                val langFromPrefMedia = settingsManager.getStringSet(
                    this.getString(R.string.provider_lang_key), mutableSetOf("en")
                )
                viewModel.langFilterList = langFromPrefMedia?.mapNotNull {
                    fromTagToEnglishLanguageName(it)?.lowercase() ?: return@mapNotNull null
                } ?: listOf()
            }
        }

        unwrapBundle(savedInstanceState)
        unwrapBundle(arguments)

        sync.updateUserData()

        preferredAutoSelectSubtitles = getAutoSelectLanguageTagIETF()

        val selectedLink = currentSelectedLink
        if (selectedLink == null) {
            viewModel.loadLinks()
        } else {
            // Recreated view, so we need to recreate the
            loadLink(selectedLink, true)
        }

        binding.overlayLoadingSkipButton.setOnClickListener {
            // Mark as "success" early
            viewModel.modifyState {
                copy(loading = Resource.Success(Unit))
            }
        }

        binding.playerLoadingGoBack.setOnClickListener {
            exitPlayer()
        }

        playerBinding?.downloadHeader?.setOnClickListener {
            it?.isVisible = false
        }

        playerBinding?.downloadHeaderToggle?.setOnClickListener {
            playerBinding?.downloadHeader?.let {
                it.isVisible = !it.isVisible
            }
        }

        observe(viewModel.currentStamps) { (stamps, instance) ->
            if (instance != viewModel.state.instance) return@observe // Outdated observe
            player.addTimeStamps(stamps)
        }

        observe(viewModel.currentSubtitles) { (subtitles, instance) ->
            if (instance != viewModel.state.instance) return@observe // Outdated observe
            player.setActiveSubtitles(subtitles)

            // If the file is downloaded then do not select auto select the subtitles
            // Downloaded subtitles cannot be selected immediately after loading since
            // player.getCurrentPreferredSubtitle() cannot fetch data from non-loaded subtitles
            // Resulting in unselecting the downloaded subtitle
            if (subtitles.lastOrNull()?.origin != SubtitleOrigin.DOWNLOADED_FILE) {
                autoSelectSubtitles()
            }
        }
        observe(viewModel.loadingLinks) { (loading, instance) ->
            if (instance != viewModel.state.instance) return@observe // Outdated observe

            when (loading) {
                is Resource.Loading -> {
                    releasePlayer()
                }

                is Resource.Success -> {
                    // provider returned false
                    //if (it.value != true) {
                    //    showToast(activity, R.string.unexpected_error, Toast.LENGTH_SHORT)
                    //}
                    startPlayer()
                }

                is Resource.Failure -> {
                    showToast(loading.errorString, Toast.LENGTH_LONG)
                    startPlayer()
                }
            }
        }

        observe(viewModel.currentLinks) { (links, instance) ->
            if (instance != viewModel.state.instance) return@observe // Outdated observe

            val turnVisible = links.isNotEmpty() && viewModel.generator?.canSkipLoading == true
            val wasGone = binding.overlayLoadingSkipButton.isGone

            binding.overlayLoadingSkipButton.apply {
                isVisible = turnVisible
                if (links.isEmpty()) {
                    setText(R.string.skip_loading)
                } else {
                    @SuppressLint("SetTextI18n")
                    text = "${context.getString(R.string.skip_loading)} (${links.size})"
                }
            }

            safe {
                if (!isPlayerActive.get() && viewModel.state.links.any { link ->
                        getLinkPriority(currentQualityProfile, link.first) >=
                                QualityDataHelper.AUTO_SKIP_PRIORITY
                    }
                ) {
                    startPlayer()
                }
            }

            if (turnVisible && wasGone) {
                binding.overlayLoadingSkipButton.requestFocus()
            }
        }
    }
}

@Suppress("DEPRECATION")
inline fun <reified T : Serializable> Bundle.getSafeSerializable(key: String): T? =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) getSerializable(key) as? T else getSerializable(
        key,
        T::class.java
    )
