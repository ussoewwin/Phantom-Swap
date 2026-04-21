package com.phantom.swap

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.RemoteViews

class SwapWidget : AppWidgetProvider() {

    companion object {
        private const val TAG = "SwapWidget"
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        Log.i(TAG, "onUpdate: ${appWidgetIds.size} widgets")

        // Widget update = app process is alive. Trigger recovery if needed.
        triggerRecoveryIfNeeded(context)

        val swapManager = SwapManager(context)
        for (widgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_swap)
            if (swapManager.isSwapHealthy()) {
                val sizeMb = swapManager.getSavedSwapSizeMb()
                val label = if (sizeMb >= 1024) {
                    String.format("%.1fGB", sizeMb / 1024.0)
                } else {
                    "${sizeMb}MB"
                }
                views.setTextViewText(R.id.tv_widget_status, "SWAP ON")
                views.setTextViewText(R.id.tv_widget_size, label)
                views.setInt(R.id.tv_widget_status, "setTextColor", 0xFF4CAF50.toInt())
            } else if (swapManager.needsRecovery()) {
                views.setTextViewText(R.id.tv_widget_status, "Restoring…")
                views.setTextViewText(R.id.tv_widget_size, "")
                views.setInt(R.id.tv_widget_status, "setTextColor", 0xFFFF9800.toInt())
            } else {
                views.setTextViewText(R.id.tv_widget_status, "SWAP OFF")
                views.setTextViewText(R.id.tv_widget_size, "")
                views.setInt(R.id.tv_widget_status, "setTextColor", 0xFF9E9E9E.toInt())
            }
            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        Log.i(TAG, "First widget placed")
        triggerRecoveryIfNeeded(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        Log.d(TAG, "onReceive: ${intent.action}")
        // Every broadcast to the widget = process is alive = chance to recover
        triggerRecoveryIfNeeded(context)
    }

    private fun triggerRecoveryIfNeeded(context: Context) {
        val swapManager = SwapManager(context)
        if (!swapManager.needsRecovery()) return

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
            Log.w(TAG, "Service start from widget failed", e)
        }

        try {
            SwapAlarmReceiver.schedule(context)
        } catch (e: Exception) {
            Log.w(TAG, "Alarm schedule from widget failed", e)
        }
    }
}
