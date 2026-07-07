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
 * Phase 1 of Android TV performance tuning:
 * detect and persist a safe default profile without changing rendering/playback yet.
 *
 * Stored values:
 * - user-selected profile: AUTO by default, later settings can override it.
 * - auto-detected profile: PERFORMANCE, BALANCED, or QUALITY.
 * - compact device info summary for debugging.
 */
object TvPerformanceProfileManager {
    private const val TAG = "TvPerformanceProfile"

    private const val USER_SELECTED_PROFILE_KEY = "tv_performance_profile_user"
    private const val AUTO_DETECTED_PROFILE_KEY = "tv_performance_profile_auto"
    private const val AUTO_DETECTION_DONE_KEY = "tv_performance_profile_auto_done"
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
                return entries.firstOrNull { it.storedValue == value } ?: AUTO
            }
        }
    }

    enum class PerformanceProfile(val storedValue: String) {
        PERFORMANCE("performance"),
        BALANCED("balanced"),
        QUALITY("quality");

        companion object {
            fun fromStoredValue(value: String?): PerformanceProfile {
                return entries.firstOrNull { it.storedValue == value } ?: BALANCED
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

    fun ensureInitialized(context: Context) {
        val prefs = prefs(context)

        if (!prefs.contains(USER_SELECTED_PROFILE_KEY)) {
            prefs.edit {
                putString(USER_SELECTED_PROFILE_KEY, UserSelectedProfile.AUTO.storedValue)
            }
        }

        if (!prefs.getBoolean(AUTO_DETECTION_DONE_KEY, false)) {
            refreshAutoDetectedProfile(context)
        } else {
            Log.i(
                TAG,
                "Using stored effective profile=${getEffectiveProfile(context)} " +
                        "auto=${getAutoDetectedProfile(context)} user=${getUserSelectedProfile(context)}"
            )
        }
    }

    fun refreshAutoDetectedProfile(context: Context): PerformanceProfile {
        val info = detectDevicePerformanceInfo(context)
        val autoProfile = chooseProfile(info)

        prefs(context).edit {
            putString(AUTO_DETECTED_PROFILE_KEY, autoProfile.storedValue)
            putBoolean(AUTO_DETECTION_DONE_KEY, true)
            putString(DEVICE_INFO_SUMMARY_KEY, info.toDebugSummary())
        }

        Log.i(TAG, "Auto-detected profile=$autoProfile from ${info.toDebugSummary()}")

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

        Log.i(TAG, "User-selected profile=$profile effective=${getEffectiveProfile(context)}")
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

    fun getDeviceInfoSummary(context: Context): String {
        return prefs(context).getString(DEVICE_INFO_SUMMARY_KEY, null)
            ?: detectDevicePerformanceInfo(context).toDebugSummary()
    }

    fun chooseProfile(info: DevicePerformanceInfo): PerformanceProfile {
        if (info.isLowRam || info.totalRamMb in 1..1600 || info.cpuCores <= 4) {
            return PerformanceProfile.PERFORMANCE
        }

        val is4kDisplay = info.displayWidth >= 3840 || info.displayHeight >= 2160
        if (is4kDisplay && info.totalRamMb >= 3000 && info.cpuCores >= 6) {
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