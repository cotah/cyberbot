package com.cyberbot.ai.kiosk

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Manages kiosk (single-app) mode: hides system bars, keeps the screen on, and
 * starts lock-task. Full lock-task pinning without a confirmation dialog
 * requires the app to be a device owner (provisioned via ADB/MDM); otherwise
 * [Activity.startLockTask] still works but shows the standard pinning prompt.
 */
class KioskManager(context: Context) {

    private val appContext = context.applicationContext
    private val devicePolicyManager =
        appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val adminComponent = ComponentName(appContext, AdminReceiver::class.java)

    fun enableKioskMode(activity: Activity) {
        // Keep the screen always on.
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Hide status and navigation bars (immersive).
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        val controller =
            WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // Start lock task (kiosk pinning).
        try {
            if (devicePolicyManager.isDeviceOwnerApp(appContext.packageName)) {
                devicePolicyManager.setLockTaskPackages(
                    adminComponent,
                    arrayOf(appContext.packageName),
                )
            }
            activity.startLockTask()
            Log.i(TAG, "Kiosk mode enabled (lock task started)")
        } catch (e: Exception) {
            Log.w(TAG, "startLockTask failed (device owner likely required): ${e.message}")
        }
    }

    fun disableKioskMode(activity: Activity) {
        try {
            activity.stopLockTask()
        } catch (e: Exception) {
            Log.w(TAG, "stopLockTask failed: ${e.message}")
        }
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.i(TAG, "Kiosk mode disabled")
    }

    companion object {
        private const val TAG = "KioskManager"
    }
}
