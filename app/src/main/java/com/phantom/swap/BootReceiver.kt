package com.phantom.swap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "Received: $action")

        val isLockedBoot = action == Intent.ACTION_LOCKED_BOOT_COMPLETED

        if (isLockedBoot) {
            try {
                SwapAlarmReceiver.schedule(context)
                Log.i(TAG, "Alarm scheduled from LOCKED_BOOT")
            } catch (e: Exception) {
                Log.w(TAG, "Alarm schedule failed from LOCKED_BOOT", e)
            }
            return
        }

        // BOOT_COMPLETED / USER_PRESENT / QUICKBOOT 等
        try {
            SwapAlarmReceiver.schedule(context)
        } catch (e: Exception) {
            Log.w(TAG, "Alarm schedule failed", e)
        }
        try {
            val serviceIntent = Intent(context, SwapForegroundService::class.java).apply {
                this.action = SwapForegroundService.ACTION_RESTORE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.i(TAG, "Service RESTORE started")
        } catch (e: Exception) {
            Log.w(TAG, "Service start failed from BootReceiver", e)
        }
    }
}
