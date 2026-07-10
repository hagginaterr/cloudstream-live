package com.lagradost.cloudstream3.ui.player

import android.content.Context
import androidx.preference.PreferenceManager
import java.util.Locale

internal data class TwitchChatSettingsState(
    val widthDp: Int,
    val heightDp: Int,
    val transparencyPercent: Int,
    val twitchEmotesEnabled: Boolean,
    val bttvEmotesEnabled: Boolean,
    val ffzEmotesEnabled: Boolean,
    val sevenTvEmotesEnabled: Boolean,
    val coloredUsernames: Boolean,
    val fontSizeSp: Int,
    val badgesEnabled: Boolean,
    val slowMode: Boolean,
) {
    val clampedWidthDp: Int get() = widthDp.coerceIn(220, 760)
    val clampedHeightDp: Int get() = heightDp.coerceIn(120, 620)
    val clampedTransparencyPercent: Int get() = transparencyPercent.coerceIn(0, 95)
    val clampedFontSizeSp: Int get() = fontSizeSp.coerceIn(8, 24)
    val backgroundAlpha: Int
        get() = (((100 - clampedTransparencyPercent) * 255) / 100).coerceIn(12, 255)

    fun emotesEnabledFor(provider: String): Boolean {
        return when (provider.lowercase(Locale.US)) {
            "twitch", "native", "native_twitch" -> twitchEmotesEnabled
            "bttv", "betterttv" -> bttvEmotesEnabled
            "ffz", "frankerfacez" -> ffzEmotesEnabled
            "7tv", "seventv" -> sevenTvEmotesEnabled
            else -> true
        }
    }

    fun maxVisibleMessages(isTv: Boolean): Int {
        val usableHeight = (clampedHeightDp - if (isTv) 36 else 24).coerceAtLeast(48)
        val approximateRowHeight = (clampedFontSizeSp * if (isTv) 2.4f else 2.1f)
            .toInt()
            .coerceAtLeast(if (isTv) 26 else 20)
        val count = (usableHeight / approximateRowHeight).coerceIn(1, if (isTv) 16 else 20)
        return if (slowMode) count.coerceAtMost(if (isTv) 5 else 4) else count
    }
}

internal object TwitchChatSettings {
    private const val KEY_WIDTH = "twitch_chat_box_width_dp_v1"
    private const val KEY_HEIGHT = "twitch_chat_box_height_dp_v1"
    private const val KEY_TRANSPARENCY = "twitch_chat_transparency_percent_v1"
    private const val KEY_EMOTE_TWITCH = "twitch_chat_emote_twitch_enabled_v1"
    private const val KEY_EMOTE_BTTV = "twitch_chat_emote_bttv_enabled_v1"
    private const val KEY_EMOTE_FFZ = "twitch_chat_emote_ffz_enabled_v1"
    private const val KEY_EMOTE_SEVENTV = "twitch_chat_emote_7tv_enabled_v1"
    private const val KEY_COLORED_NAMES = "twitch_chat_colored_names_v1"
    private const val KEY_FONT_SIZE = "twitch_chat_font_size_sp_v1"
    private const val KEY_BADGES = "twitch_chat_badges_enabled_v1"
    private const val KEY_SLOW_MODE = "twitch_chat_slow_mode_v1"

    fun defaultState(context: Context): TwitchChatSettingsState {
        return TwitchChatSettingsState(
            widthDp = 317,
            heightDp = 148,
            transparencyPercent = 73,
            twitchEmotesEnabled = true,
            bttvEmotesEnabled = true,
            ffzEmotesEnabled = true,
            sevenTvEmotesEnabled = true,
            coloredUsernames = true,
            fontSizeSp = 14,
            badgesEnabled = true,
            slowMode = false,
        )
    }

    fun load(context: Context): TwitchChatSettingsState {
        val defaults = defaultState(context)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return TwitchChatSettingsState(
            widthDp = prefs.getInt(KEY_WIDTH, defaults.widthDp),
            heightDp = prefs.getInt(KEY_HEIGHT, defaults.heightDp),
            transparencyPercent = prefs.getInt(KEY_TRANSPARENCY, defaults.transparencyPercent),
            twitchEmotesEnabled = prefs.getBoolean(KEY_EMOTE_TWITCH, defaults.twitchEmotesEnabled),
            bttvEmotesEnabled = prefs.getBoolean(KEY_EMOTE_BTTV, defaults.bttvEmotesEnabled),
            ffzEmotesEnabled = prefs.getBoolean(KEY_EMOTE_FFZ, defaults.ffzEmotesEnabled),
            sevenTvEmotesEnabled = prefs.getBoolean(KEY_EMOTE_SEVENTV, defaults.sevenTvEmotesEnabled),
            coloredUsernames = prefs.getBoolean(KEY_COLORED_NAMES, defaults.coloredUsernames),
            fontSizeSp = prefs.getInt(KEY_FONT_SIZE, defaults.fontSizeSp),
            badgesEnabled = prefs.getBoolean(KEY_BADGES, defaults.badgesEnabled),
            slowMode = prefs.getBoolean(KEY_SLOW_MODE, defaults.slowMode),
        )
    }

    fun save(context: Context, state: TwitchChatSettingsState) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putInt(KEY_WIDTH, state.clampedWidthDp)
            .putInt(KEY_HEIGHT, state.clampedHeightDp)
            .putInt(KEY_TRANSPARENCY, state.clampedTransparencyPercent)
            .putBoolean(KEY_EMOTE_TWITCH, state.twitchEmotesEnabled)
            .putBoolean(KEY_EMOTE_BTTV, state.bttvEmotesEnabled)
            .putBoolean(KEY_EMOTE_FFZ, state.ffzEmotesEnabled)
            .putBoolean(KEY_EMOTE_SEVENTV, state.sevenTvEmotesEnabled)
            .putBoolean(KEY_COLORED_NAMES, state.coloredUsernames)
            .putInt(KEY_FONT_SIZE, state.clampedFontSizeSp)
            .putBoolean(KEY_BADGES, state.badgesEnabled)
            .putBoolean(KEY_SLOW_MODE, state.slowMode)
            .apply()
    }

    fun reset(context: Context): TwitchChatSettingsState {
        val state = defaultState(context)
        save(context, state)
        return state
    }
}