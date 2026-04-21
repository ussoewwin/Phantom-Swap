package com.phantom.swap

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.widget.Toast
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SwapForegroundService : Service() {

    companion object {
        private const val TAG = "SwapService"
        const val ACTION_START = "ACTION_START"
        const val ACTION_RESTORE = "ACTION_RESTORE"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_SWAP_SIZE = "EXTRA_SWAP_SIZE"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "PhantomSwapFinalChannel"
        private const val HEALTH_CHECK_INTERVAL_MS = 30_000L
        private const val PAGE_TOUCH_INTERVAL_MS = 15_000L
    }

    private lateinit var swapManager: SwapManager
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private val healthHandler = Handler(Looper.getMainLooper())
    private var healthRunnable: Runnable? = null
    private var pageTouchJob: kotlinx.coroutines.Job? = null
    private var silentAudioTrack: AudioTrack? = null
    private var silentAudioJob: kotlinx.coroutines.Job? = null
    private var isRecovering = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.i(TAG, "Screen OFF - refreshing WakeLock")
                    refreshWakeLock()
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.i(TAG, "Screen ON - immediate health check")
                    triggerHealthCheck()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        swapManager = SwapManager(this)
        createNotificationChannel()
        registerScreenReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.i(TAG, "onStartCommand: action=$action, intent=${intent != null}")

        if (action == ACTION_STOP) {
            stopSilentAudio()
            stopHealthMonitor()
            stopPageTouchLoop()
            cancelWatchdog()
            SwapAlarmReceiver.cancel(this)
            swapManager.deleteSwap()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        val sizeMb = intent?.getIntExtra(EXTRA_SWAP_SIZE, 0) ?: 0

        acquireWakeLock()

        if (action == ACTION_RESTORE) {
            startForeground(NOTIFICATION_ID, createNotification("Restoring swap..."))
            restoreSwapAsync(sizeMb)
        } else if (action == ACTION_START && sizeMb > 0) {
            startForeground(NOTIFICATION_ID, createNotification("Initializing swap..."))
            createSwapAsync(sizeMb)
        } else {
            startForeground(NOTIFICATION_ID, createNotification("Waking up..."))
            restoreSwapAsync(0)
        }
        return START_STICKY
    }

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
    }

    private fun startHealthMonitor() {
        stopHealthMonitor()
        healthRunnable = object : Runnable {
            override fun run() {
                performHealthCheck()
                healthHandler.postDelayed(this, HEALTH_CHECK_INTERVAL_MS)
            }
        }
        healthHandler.postDelayed(healthRunnable!!, HEALTH_CHECK_INTERVAL_MS)
        Log.i(TAG, "Health monitor started (interval=${HEALTH_CHECK_INTERVAL_MS}ms)")
    }

    private fun stopHealthMonitor() {
        healthRunnable?.let { healthHandler.removeCallbacks(it) }
        healthRunnable = null
    }

    /**
     * Periodically touches mapped pages to keep them in physical RAM.
     * Without this, the kernel freely evicts file-backed mmap pages
     * from the page cache during screen-off / low-memory situations,
     * destroying the "swap" RAM expansion effect even though the
     * process and mappings remain alive.
     */
    private fun startPageTouchLoop() {
        stopPageTouchLoop()
        pageTouchJob = scope.launch {
            Log.i(TAG, "Page touch loop started")
            while (true) {
                delay(PAGE_TOUCH_INTERVAL_MS)
                try {
                    swapManager.touchAllPages()
                } catch (e: Exception) {
                    Log.w(TAG, "Page touch error", e)
                }
            }
        }
    }

    private fun stopPageTouchLoop() {
        pageTouchJob?.cancel()
        pageTouchJob = null
    }

    /**
     * Plays a silent audio stream to prevent OEM process killers
     * from terminating the service during display-off.
     * Audio-playing processes are treated as "active media" by Android
     * and nearly all OEMs, giving them the highest kill resistance.
     */
    private fun startSilentAudio() {
        stopSilentAudio()
        try {
            val sampleRate = 8000
            val bufSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            silentAudioTrack = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
                bufSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            silentAudioTrack?.play()

            val silence = ByteArray(bufSize)
            silentAudioJob = scope.launch {
                while (true) {
                    silentAudioTrack?.write(silence, 0, silence.size)
                    delay(500)
                }
            }
            Log.i(TAG, "Silent audio started")
        } catch (e: Exception) {
            Log.w(TAG, "Silent audio failed", e)
        }
    }

    private fun stopSilentAudio() {
        silentAudioJob?.cancel()
        silentAudioJob = null
        try {
            silentAudioTrack?.stop()
            silentAudioTrack?.release()
        } catch (_: Exception) {}
        silentAudioTrack = null
    }

    private fun scheduleWatchdog() {
        try {
            val workRequest = androidx.work.PeriodicWorkRequestBuilder<SwapWatchdogWorker>(
                15, java.util.concurrent.TimeUnit.MINUTES
            ).build()
            androidx.work.WorkManager.getInstance(this)
                .enqueueUniquePeriodicWork(
                    "swap_watchdog",
                    androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                    workRequest
                )
            Log.i(TAG, "Watchdog WorkManager scheduled")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to schedule watchdog", e)
        }
    }

    private fun cancelWatchdog() {
        try {
            androidx.work.WorkManager.getInstance(this)
                .cancelUniqueWork("swap_watchdog")
        } catch (_: Exception) {}
    }

    private fun triggerHealthCheck() {
        healthHandler.post { performHealthCheck() }
    }

    private fun performHealthCheck() {
        if (isRecovering) return

        if (swapManager.isSwapActive() && swapManager.isSwapHealthy()) {
            return
        }

        if (swapManager.needsRecovery()) {
            Log.w(TAG, "Health check: mapping lost, starting recovery")
            isRecovering = true
            swapManager.forceInvalidate()
            val sizeMb = swapManager.getSavedSwapSizeMb()
            if (sizeMb > 0) {
                updateNotification("Recovering swap (${sizeMb}MB)...")
                scope.launch {
                    val result = swapManager.restoreSwap(sizeMb)
                    isRecovering = false
                    if (result.isSuccess) {
                        Log.i(TAG, "Auto-recovery successful: ${sizeMb}MB")
                        updateNotification("Swap Active: ${sizeMb}MB")
                        startSilentAudio()
                        startPageTouchLoop()
                        SwapAlarmReceiver.schedule(this@SwapForegroundService)
                    } else {
                        Log.e(TAG, "Auto-recovery failed: ${result.exceptionOrNull()?.message}")
                        updateNotification("Recovery failed - retrying...")
                    }
                }
            } else {
                isRecovering = false
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhantomSwap::WakeLock").apply {
                setReferenceCounted(false)
            }
        }
        wakeLock?.acquire()
    }

    private fun refreshWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
            it.acquire()
            Log.d(TAG, "WakeLock refreshed")
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSilentAudio()
        stopHealthMonitor()
        stopPageTouchLoop()
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        releaseWakeLock()
        scope.cancel()
    }

    private fun createSwapAsync(sizeMb: Int) {
        scope.launch {
            swapManager.createSwap(sizeMb, object : SwapManager.ProgressCallback {
                override fun onProgress(percent: Int, message: String) {
                    updateNotification("Creating Swap: $percent% - $message")
                }
                override fun onComplete(success: Boolean, message: String) {
                    if (success) {
                        updateNotification("Swap Active: ${sizeMb}MB")
                        startSilentAudio()
                        startHealthMonitor()
                        startPageTouchLoop()
                        scheduleWatchdog()
                        SwapAlarmReceiver.schedule(this@SwapForegroundService)
                    } else {
                        updateNotification("Swap Error: $message")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            stopForeground(STOP_FOREGROUND_DETACH)
                        } else {
                            @Suppress("DEPRECATION")
                            stopForeground(false)
                        }
                        stopSelf()
                    }
                }
            })
        }
    }

    private fun restoreSwapAsync(initialSizeMb: Int) {
        scope.launch {
            var retries = 0
            val maxRetries = 60
            var success = false
            var currentTargetSize = initialSizeMb

            while (retries < maxRetries) {
                if (currentTargetSize <= 0) {
                    currentTargetSize = swapManager.getSavedSwapSizeMb()
                }

                if (currentTargetSize > 0) {
                    swapManager.forceInvalidate()

                    Log.i(TAG, "Restore attempt #$retries: ${currentTargetSize}MB")
                    updateNotification("Restoring: ${currentTargetSize}MB (attempt #${retries + 1})")
                    
                    val result = swapManager.restoreSwap(currentTargetSize, object : SwapManager.ProgressCallback {
                        override fun onProgress(percent: Int, message: String) {
                            updateNotification("Restoring: $percent% - $message")
                        }
                        override fun onComplete(success: Boolean, message: String) {}
                    })

                    if (result.isSuccess) {
                        success = true
                        updateNotification("Swap Active: ${currentTargetSize}MB")
                        val sizeLabel = if (currentTargetSize >= 1024)
                            String.format("%.1fGB", currentTargetSize / 1024.0)
                        else "${currentTargetSize}MB"
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(applicationContext, "Swap Active: $sizeLabel", Toast.LENGTH_SHORT).show()
                        }
                        startSilentAudio()
                        startHealthMonitor()
                        startPageTouchLoop()
                        scheduleWatchdog()
                        SwapAlarmReceiver.schedule(this@SwapForegroundService)
                        break
                    } else {
                        val errorMsg = result.exceptionOrNull()?.message ?: "Unknown"
                        Log.w(TAG, "Restore retry #$retries failed: $errorMsg")
                    }
                } else {
                    updateNotification("Waiting for configuration...")
                }

                retries++
                val waitTime = if (retries < 3) 1000L else 5000L
                delay(waitTime)
            }

            if (!success) {
                updateNotification("Restore Failed: Storage Timeout")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_DETACH)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(false)
                }
                stopSelf()
            }
        }
    }

    private fun createNotification(contentText: String): Notification {
        createNotificationChannel()
        return androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Phantom Swap")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(contentText))
            .setOngoing(true)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_SERVICE)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    private fun updateNotification(text: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Phantom Swap Monitor",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
