package com.cyberbot.ai.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.cyberbot.ai.ui.MainActivity

/**
 * Launches CyberBot automatically after the device boots.
 *
 * The launch is delayed by [BOOT_DELAY_MS] to give the system time to finish
 * initializing (network, audio, etc.). Because a BroadcastReceiver must not
 * block in onReceive, the wait runs on a background thread kept alive via
 * goAsync().
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        Log.i(TAG, "Boot received ($action); launching CyberBot in ${BOOT_DELAY_MS}ms")
        val pendingResult = goAsync()
        val appContext = context.applicationContext

        Thread {
            try {
                Thread.sleep(BOOT_DELAY_MS)
                val launchIntent = Intent(appContext, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(launchIntent)
                Log.i(TAG, "CyberBot launched after boot")
            } catch (e: Exception) {
                Log.e(TAG, "Boot launch failed", e)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    companion object {
        private const val TAG = "BootReceiver"
        private const val BOOT_DELAY_MS = 5000L
    }
}
