package com.phantom.swap

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Periodic watchdog (every 15 min) that ensures the swap service stays alive.
 * Handles OEMs that ignore START_STICKY and kill the service during Doze.
 */
class SwapWatchdogWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "SwapWatchdog"
    }

    override suspend fun doWork(): Result {
        val swapManager = SwapManager(applicationContext)

        if (!swapManager.needsRecovery()) {
            Log.d(TAG, "Swap healthy or not configured, nothing to do")
            return Result.success()
        }

        Log.i(TAG, "Swap needs recovery, starting service")
        try {
            val serviceIntent = Intent(applicationContext, SwapForegroundService::class.java).apply {
                action = SwapForegroundService.ACTION_RESTORE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(serviceIntent)
            } else {
                applicationContext.startService(serviceIntent)
            }
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service", e)
            return Result.retry()
        }
    }
}
