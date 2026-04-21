package com.phantom.swap

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

class SwapAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SwapAlarm"
        private const val INTERVAL_MS = 3 * 60 * 1000L // 3 minutes
        private const val REQUEST_CODE = 9999

        fun schedule(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, SwapAlarmReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val triggerAt = SystemClock.elapsedRealtime() + INTERVAL_MS
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi
            )
            Log.i(TAG, "Alarm scheduled in ${INTERVAL_MS / 1000}s")
        }

        fun cancel(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, SwapAlarmReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pi)
            Log.i(TAG, "Alarm cancelled")
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        Log.i(TAG, "Alarm fired")
        val swapManager = SwapManager(context)

        if (swapManager.needsRecovery()) {
            Log.i(TAG, "Swap needs recovery, starting service")
            try {
                val serviceIntent = Intent(context, SwapForegroundService::class.java).apply {
                    action = SwapForegroundService.ACTION_RESTORE
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start service from alarm", e)
            }
        } else {
            Log.d(TAG, "Swap OK or not configured")
        }

        // Reschedule (setExactAndAllowWhileIdle is one-shot)
        schedule(context)
    }
}
