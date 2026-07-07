package com.lagradost.cloudstream3.utils

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Point
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import android.view.WindowManager
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import java.util.Locale

/**
 * Android TV performance profile manager.
 *
 * This follows the Android TV memory guidance:
 * - Use ActivityManager.isLowRamDevice as the primary low-memory signal.
 * - Treat UI resolution and video resolution as different concepts; many 4K TVs
 *   render app UI at 1080p while still supporting 2160p video playback.
 * - Keep grid images and fullscreen background artwork under control.
 * - Reuse RecyclerView views without keeping overly large pools on low-memory TVs.
 * - Keep playback buffer targets profile-backed for the later player phase.
 */
object TvPerformanceProfileManager {
    private const val TAG = "TvPerformanceProfile"

    private const val CURRENT_AUTO_DETECTION_VERSION = 3

    private const val USER_SELECTED_PROFILE_KEY = "tv_performance_profile_user"
    private const val AUTO_DETECTED_PROFILE_KEY = "tv_performance_profile_auto"
    private const val AUTO_DETECTION_DONE_KEY = "tv_performance_profile_auto_done"
    private const val AUTO_DETECTION_VERSION_KEY = "tv_performance_profile_auto_version"
    private const val DEVICE_INFO_SUMMARY_KEY = "tv_performance_profile_device_info"

    private const val MIME_HEVC = "video/hevc"
    private const val MIME_AV1 = "video/av01"

    enum class UserSelectedProfile(val storedValue: String) {
        AUTO("auto"),
        PERFORMANCE("performance"),
        BALANCED("balanced"),
        QUALITY("quality");

        companion object {
            fun fromStoredValue(value: String?): UserSelectedProfile {
                return values().firstOrNull { it.storedValue == value } ?: AUTO
            }
        }
    }

    enum class PerformanceProfile(val storedValue: String) {
        PERFORMANCE("performance"),
        BALANCED("balanced"),
        QUALITY("quality");

        companion object {
            fun fromStoredValue(value: String?): PerformanceProfile {
                return values().firstOrNull { it.storedValue == value } ?: BALANCED
            }
        }
    }

    data class DevicePerformanceInfo(
        val isLowRam: Boolean,
        val totalRamMb: Long,
        val memoryClassMb: Int,
        val cpuCores: Int,
        val displayWidth: Int,
        val displayHeight: Int,
        val refreshRate: Float,
        val hasHardwareHevc: Boolean,
        val hasHardwareAv1: Boolean,
    ) {
        fun toDebugSummary(): String {
            return "profileSignals(" +
                    "lowRam=$isLowRam, " +
                    "totalRamMb=$totalRamMb, " +
                    "memoryClassMb=$memoryClassMb, " +
                    "cpuCores=$cpuCores, " +
                    "display=${displayWidth}x$displayHeight@${String.format(Locale.US, "%.2f", refreshRate)}, " +
                    "hwHevc=$hasHardwareHevc, " +
                    "hwAv1=$hasHardwareAv1" +
                    ")"
        }
    }

    data class TvPerformanceSettings(
        val enableRichHomeBackground: Boolean,
        val homeBackgroundBlurRadius: Float,
        val homeBackgroundAlpha: Float,
        val homePosterScaleMultiplier: Float,
        val homeRecyclerPoolSize: Int,
        val homeRowItemViewCacheSize: Int,
        val homeRowInitialPrefetchItemCount: Int,
        val preferHardwareBitmaps: Boolean,
        val playbackBufferTargetMb: Int,
    )

    fun ensureInitialized(context: Context) {
        val prefs = prefs(context)

        if (!prefs.contains(USER_SELECTED_PROFILE_KEY)) {
            prefs.edit {
                putString(USER_SELECTED_PROFILE_KEY, UserSelectedProfile.AUTO.storedValue)
            }
        }

        val storedVersion = prefs.getInt(AUTO_DETECTION_VERSION_KEY, 0)
        if (!prefs.getBoolean(AUTO_DETECTION_DONE_KEY, false) || storedVersion < CURRENT_AUTO_DETECTION_VERSION) {
            refreshAutoDetectedProfile(context)
        } else {
            Log.i(
                TAG,
                "Using stored effective profile=${getEffectiveProfile(context)} " +
                        "auto=${getAutoDetectedProfile(context)} " +
                        "user=${getUserSelectedProfile(context)} " +
                        "settings=${getSettings(context)}"
            )
        }
    }

    fun refreshAutoDetectedProfile(context: Context): PerformanceProfile {
        val info = detectDevicePerformanceInfo(context)
        val autoProfile = chooseProfile(info)

        prefs(context).edit {
            putString(AUTO_DETECTED_PROFILE_KEY, autoProfile.storedValue)
            putBoolean(AUTO_DETECTION_DONE_KEY, true)
            putInt(AUTO_DETECTION_VERSION_KEY, CURRENT_AUTO_DETECTION_VERSION)
            putString(DEVICE_INFO_SUMMARY_KEY, info.toDebugSummary())
        }

        Log.i(TAG, "Auto-detected profile=$autoProfile from ${info.toDebugSummary()} settings=${getSettings(autoProfile)}")

        return autoProfile
    }

    fun getUserSelectedProfile(context: Context): UserSelectedProfile {
        return UserSelectedProfile.fromStoredValue(
            prefs(context).getString(USER_SELECTED_PROFILE_KEY, UserSelectedProfile.AUTO.storedValue)
        )
    }

    fun setUserSelectedProfile(
        context: Context,
        profile: UserSelectedProfile,
    ) {
        prefs(context).edit {
            putString(USER_SELECTED_PROFILE_KEY, profile.storedValue)
        }

        Log.i(TAG, "User-selected profile=$profile effective=${getEffectiveProfile(context)} settings=${getSettings(context)}")
    }

    fun getAutoDetectedProfile(context: Context): PerformanceProfile {
        return PerformanceProfile.fromStoredValue(
            prefs(context).getString(AUTO_DETECTED_PROFILE_KEY, PerformanceProfile.BALANCED.storedValue)
        )
    }

    fun getEffectiveProfile(context: Context): PerformanceProfile {
        return when (getUserSelectedProfile(context)) {
            UserSelectedProfile.AUTO -> getAutoDetectedProfile(context)
            UserSelectedProfile.PERFORMANCE -> PerformanceProfile.PERFORMANCE
            UserSelectedProfile.BALANCED -> PerformanceProfile.BALANCED
            UserSelectedProfile.QUALITY -> PerformanceProfile.QUALITY
        }
    }

    fun getSettings(context: Context): TvPerformanceSettings {
        return getSettings(getEffectiveProfile(context))
    }

    fun getSettings(profile: PerformanceProfile): TvPerformanceSettings {
        return when (profile) {
            PerformanceProfile.PERFORMANCE -> TvPerformanceSettings(
                enableRichHomeBackground = false,
                homeBackgroundBlurRadius = 0f,
                homeBackgroundAlpha = 0f,
                homePosterScaleMultiplier = 0.90f,
                homeRecyclerPoolSize = 8,
                homeRowItemViewCacheSize = 0,
                homeRowInitialPrefetchItemCount = 2,
                preferHardwareBitmaps = true,
                playbackBufferTargetMb = 60,
            )

            PerformanceProfile.BALANCED -> TvPerformanceSettings(
                enableRichHomeBackground = false,
                homeBackgroundBlurRadius = 0f,
                homeBackgroundAlpha = 0f,
                homePosterScaleMultiplier = 1.00f,
                homeRecyclerPoolSize = 12,
                homeRowItemViewCacheSize = 1,
                homeRowInitialPrefetchItemCount = 3,
                preferHardwareBitmaps = true,
                playbackBufferTargetMb = 80,
            )

            PerformanceProfile.QUALITY -> TvPerformanceSettings(
                enableRichHomeBackground = true,
                homeBackgroundBlurRadius = 10f,
                homeBackgroundAlpha = 0.42f,
                homePosterScaleMultiplier = 1.00f,
                homeRecyclerPoolSize = 20,
                homeRowItemViewCacheSize = 2,
                homeRowInitialPrefetchItemCount = 4,
                preferHardwareBitmaps = true,
                playbackBufferTargetMb = 100,
            )
        }
    }

    fun getDeviceInfoSummary(context: Context): String {
        return prefs(context).getString(DEVICE_INFO_SUMMARY_KEY, null)
            ?: detectDevicePerformanceInfo(context).toDebugSummary()
    }

    fun chooseProfile(info: DevicePerformanceInfo): PerformanceProfile {
        // Android TV guidance says to use isLowRamDevice as the primary
        // constrained-device signal. Do not demote a capable TV just because the
        // app UI reports 1080p or the CPU reports four cores.
        if (info.isLowRam) {
            return PerformanceProfile.PERFORMANCE
        }

        // 1 GB and 1.5 GB TV devices are the main low-memory target range.
        if (info.totalRamMb in 1L..1600L) {
            return PerformanceProfile.PERFORMANCE
        }

        val hasHardwareModernVideoDecoder = info.hasHardwareHevc || info.hasHardwareAv1

        // Only demote four-core devices when they also have constrained memory
        // and no modern hardware video decoder. A 4-core TV with about 2.7 GB
        // RAM plus HEVC/AV1 hardware decode should be BALANCED.
        if (
            info.cpuCores <= 4 &&
            info.totalRamMb in 1L..2399L &&
            !hasHardwareModernVideoDecoder
        ) {
            return PerformanceProfile.PERFORMANCE
        }

        // Very small CPU counts are still treated as constrained unless the
        // device has substantial memory headroom.
        if (info.cpuCores <= 2 && info.totalRamMb < 3000L) {
            return PerformanceProfile.PERFORMANCE
        }

        // Quality is intentionally conservative. Display.Mode often reports the
        // app UI mode, not video capability, so require memory, CPU, and codec
        // headroom rather than relying on a reported 4K mode alone.
        val hasLargeMemoryHeadroom = info.totalRamMb >= 3600L && info.memoryClassMb >= 256
        val hasEnoughCpu = info.cpuCores >= 6
        val likely4kVideoCapable = hasHardwareModernVideoDecoder && info.totalRamMb >= 3000L
        if (hasLargeMemoryHeadroom && hasEnoughCpu && likely4kVideoCapable) {
            return PerformanceProfile.QUALITY
        }

        return PerformanceProfile.BALANCED
    }

    private fun detectDevicePerformanceInfo(context: Context): DevicePerformanceInfo {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()

        if (activityManager != null) {
            activityManager.getMemoryInfo(memoryInfo)
        }

        val totalRamMb = if (memoryInfo.totalMem > 0L) {
            memoryInfo.totalMem / 1024L / 1024L
        } else {
            0L
        }

        val displayInfo = detectDisplayInfo(context)

        return DevicePerformanceInfo(
            isLowRam = activityManager?.isLowRamDevice == true,
            totalRamMb = totalRamMb,
            memoryClassMb = activityManager?.memoryClass ?: 0,
            cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
            displayWidth = displayInfo.width,
            displayHeight = displayInfo.height,
            refreshRate = displayInfo.refreshRate,
            hasHardwareHevc = hasHardwareDecoder(MIME_HEVC),
            hasHardwareAv1 = hasHardwareDecoder(MIME_AV1),
        )
    }

    private data class DisplayInfo(
        val width: Int,
        val height: Int,
        val refreshRate: Float,
    )

    @Suppress("DEPRECATION")
    private fun detectDisplayInfo(context: Context): DisplayInfo {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display
        } else {
            windowManager?.defaultDisplay
        }

        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            display?.mode
        } else {
            null
        }

        if (mode != null) {
            return DisplayInfo(
                width = mode.physicalWidth,
                height = mode.physicalHeight,
                refreshRate = mode.refreshRate,
            )
        }

        val size = Point()
        display?.getRealSize(size)

        return DisplayInfo(
            width = size.x,
            height = size.y,
            refreshRate = display?.refreshRate ?: 0f,
        )
    }

    private fun hasHardwareDecoder(mimeType: String): Boolean {
        return try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS)
                .codecInfos
                .any { codec ->
                    !codec.isEncoder &&
                            codec.supportedTypes.any { it.equals(mimeType, ignoreCase = true) } &&
                            isHardwareCodec(codec)
                }
        } catch (t: Throwable) {
            Log.w(TAG, "Codec check failed for $mimeType", t)
            false
        }
    }

    private fun isHardwareCodec(codecInfo: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return codecInfo.isHardwareAccelerated && !codecInfo.isSoftwareOnly
        }

        val name = codecInfo.name.lowercase(Locale.US)

        return !name.startsWith("omx.google.") &&
                !name.startsWith("c2.android.") &&
                !name.contains(".sw.") &&
                !name.contains("software")
    }

    private fun prefs(context: Context): SharedPreferences {
        return PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
    }
}