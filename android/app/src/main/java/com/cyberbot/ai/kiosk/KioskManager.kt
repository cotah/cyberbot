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
 * starts lock-task.
 *
 * Every operation is defensively guarded. A failure in any step — obtaining the
 * DevicePolicyManager, window flags, immersive bars, or lock-task (whether or
 * not the app is a device owner) — is logged with [Log.e] but never allowed to
 * propagate, so the app can never crash from kiosk setup.
 */
class KioskManager(context: Context) {

    private val appContext = context.applicationContext

    private val devicePolicyManager: DevicePolicyManager? = try {
        appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
    } catch (e: Exception) {
        Log.e(TAG, "Failed to obtain DevicePolicyManager", e)
        null
    }

    private val adminComponent: ComponentName? = try {
        ComponentName(appContext, AdminReceiver::class.java)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to build admin ComponentName", e)
        null
    }

    fun enableKioskMode(activity: Activity) {
        try {
            // Keep the screen always on.
            try {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set FLAG_KEEP_SCREEN_ON", e)
            }

            // Hide status and navigation bars (immersive).
            try {
                WindowCompat.setDecorFitsSystemWindows(activity.window, false)
                val controller =
                    WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } catch (e: Exception) {
                Log.e(TAG, "Failed to hide system bars", e)
            }

            // Start lock task (kiosk pinning). Safe whether or not we are a
            // device owner; any failure is logged and never crashes the app.
            try {
                val dpm = devicePolicyManager
                val admin = adminComponent
                if (dpm != null && admin != null &&
                    dpm.isDeviceOwnerApp(appContext.packageName)
                ) {
                    dpm.setLockTaskPackages(admin, arrayOf(appContext.packageName))
                    Log.i(TAG, "Lock task packages whitelisted (device owner)")
                }
                activity.startLockTask()
                Log.i(TAG, "Kiosk mode enabled (lock task started)")
            } catch (e: Exception) {
                Log.e(TAG, "startLockTask failed; continuing without kiosk pinning", e)
            }
        } catch (e: Exception) {
            // Final safety net: nothing in this method may ever propagate.
            Log.e(TAG, "enableKioskMode failed unexpectedly", e)
        }
    }

    fun disableKioskMode(activity: Activity) {
        try {
            try {
                activity.stopLockTask()
            } catch (e: Exception) {
                Log.e(TAG, "stopLockTask failed", e)
            }
            try {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear FLAG_KEEP_SCREEN_ON", e)
            }
            Log.i(TAG, "Kiosk mode disabled")
        } catch (e: Exception) {
            Log.e(TAG, "disableKioskMode failed unexpectedly", e)
        }
    }

    companion object {
        private const val TAG = "KioskManager"
    }
}
