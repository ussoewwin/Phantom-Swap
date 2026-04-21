package com.phantom.swap

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

class SwapApplication : Application() {

    companion object {
        private const val TAG = "SwapApp"
    }

    override fun onCreate() {
        super.onCreate()

        ensureBootReceiverEnabled()

        val swapManager = SwapManager(this)
        if (swapManager.needsRecovery()) {
            Log.i(TAG, "Process started - swap needs recovery, launching service")
            try {
                val intent = Intent(this, SwapForegroundService::class.java).apply {
                    action = SwapForegroundService.ACTION_RESTORE
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Service start from Application failed, scheduling alarm", e)
            }
            try {
                SwapAlarmReceiver.schedule(this)
            } catch (e: Exception) {
                Log.w(TAG, "Alarm schedule failed", e)
            }
        }
    }

    /**
     * Some devices disable manifest-declared receivers after APK install.
     * Explicitly enabling via PackageManager forces the system to register them.
     */
    private fun ensureBootReceiverEnabled() {
        try {
            val cn = ComponentName(this, BootReceiver::class.java)
            val current = packageManager.getComponentEnabledSetting(cn)
            if (current != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                packageManager.setComponentEnabledSetting(
                    cn,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                Log.i(TAG, "BootReceiver explicitly enabled (was $current)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enable BootReceiver", e)
        }
    }
}
