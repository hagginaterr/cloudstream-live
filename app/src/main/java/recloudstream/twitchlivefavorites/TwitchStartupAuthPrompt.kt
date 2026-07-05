package recloudstream.twitchlivefavorites

import android.graphics.Bitmap
import android.os.CountDownTimer
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.DeviceAuthBinding
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import com.lagradost.cloudstream3.utils.UIHelper.dismissSafe
import qrcode.QRCode

object TwitchStartupAuthPrompt {
    @Volatile private var showing = false
    @Volatile private var shownThisRun = false
    @Volatile private var syncStartedThisRun = false

    fun maybeShow(activity: FragmentActivity?) {
        if (activity == null) return
        if (TwitchAccountAuth.isSignedIn()) {
            maybeSyncFollowsOnStartup(activity)
            return
        }
        if (shownThisRun || showing) return
        shownThisRun = true
        activity.window?.decorView?.postDelayed({ show(activity, force = false) }, 900L)
    }

    // TwitchFavoritesStartupSyncPatch: sync followed Twitch channels once per app run.
    private fun maybeSyncFollowsOnStartup(activity: FragmentActivity) {
        if (syncStartedThisRun || !TwitchAccountAuth.isSyncOnStartupEnabled()) return
        syncStartedThisRun = true

        ioSafe {
            runCatching {
                TwitchAccountAuth.syncFollowedFavorites()
            }.onFailure { error ->
                logError(error)
            }
        }
    }
    fun show(activity: FragmentActivity?, force: Boolean = true) {
        if (activity == null) return
        if (showing) return
        if (!force && TwitchAccountAuth.isSignedIn()) return
        showing = true

        val binding = DeviceAuthBinding.inflate(activity.layoutInflater, null, false)
        val dialog = AlertDialog.Builder(activity, R.style.AlertDialogCustom)
            .setView(binding.root)
            .setNegativeButton(R.string.cancel) { _, _ -> }
            .create()

        dialog.setOnDismissListener { showing = false }
        dialog.show()
        if (isLayout(TV or EMULATOR)) {
            binding.devicePinCode.requestFocus()
        }

        binding.deviceAuthMessage.text = "Getting Twitch sign-in code..."
        binding.devicePinCode.text = "Twitch"
        binding.deviceAuthValidationCounter.text = "Use your phone to scan the QR code."

        ioSafe {
            try {
                val deviceCode = TwitchAccountAuth.requestDeviceCode()
                val qrCodeImage = QRCode.ofRoundedSquares()
                    .withColor(activity.colorFromAttribute(R.attr.textColor))
                    .withBackgroundColor(activity.colorFromAttribute(R.attr.primaryBlackBackground))
                    .build(deviceCode.verificationUri)
                    .render()
                    .nativeImage() as Bitmap

                activity.runOnUiThread {
                    binding.devicePinCode.text = deviceCode.userCode
                    binding.deviceAuthMessage.text = "Scan the QR code with your phone to sign in to Twitch. This imports the channels you follow into app favorites."
                    binding.deviceAuthQrcode.loadImage(qrCodeImage)
                }

                activity.runOnUiThread {
                    val expirationMillis = deviceCode.expiresIn.toLong() * 1000L
                    object : CountDownTimer(expirationMillis, 1000L) {
                        private var polling = false

                        override fun onTick(millisUntilFinished: Long) {
                            val secondsLeft = (millisUntilFinished / 1000L).toInt()
                            binding.deviceAuthValidationCounter.text = "Expires in ${secondsLeft / 60}:${(secondsLeft % 60).toString().padStart(2, '0')}"
                            if (polling || secondsLeft % deviceCode.interval != 0) return
                            polling = true
                            ioSafe {
                                try {
                                    val result = TwitchAccountAuth.pollTokenAndImport(deviceCode)
                                    if (result != null) {
                                        activity.runOnUiThread {
                                            val who = result.displayName ?: "Twitch"
                                            showToast("Signed in as $who. Imported ${result.importedCount} followed channels.")
                                            dialog.dismissSafe(activity)
                                            cancel()
                                        }
                                    }
                                } catch (t: Throwable) {
                                    logError(t)
                                    activity.runOnUiThread {
                                        showToast("Twitch sign-in failed: ${t.message ?: t.javaClass.simpleName}")
                                        dialog.dismissSafe(activity)
                                        cancel()
                                    }
                                } finally {
                                    polling = false
                                }
                            }
                        }

                        override fun onFinish() {
                            showToast("Twitch sign-in expired. Open Settings > Accounts to try again.")
                            dialog.dismissSafe(activity)
                        }
                    }.start()
                }
            } catch (t: Throwable) {
                logError(t)
                activity.runOnUiThread {
                    showToast("Could not start Twitch sign-in: ${t.message ?: t.javaClass.simpleName}")
                    dialog.dismissSafe(activity)
                }
            }
        }
    }
}