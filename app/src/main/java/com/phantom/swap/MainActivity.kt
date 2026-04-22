package com.phantom.swap

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var swapManager: SwapManager
    private val handler = Handler(Looper.getMainLooper())
    private var memoryUpdateRunnable: Runnable? = null

    private lateinit var tvTotalRam: TextView
    private lateinit var tvUsedRam: TextView
    private lateinit var tvAvailableRam: TextView
    private lateinit var tvSwapSize: TextView
    private lateinit var seekSwapSize: SeekBar
    private lateinit var tvSwapStatus: TextView
    private lateinit var tvSwapInfo: TextView
    private lateinit var btnCreate: Button
    private lateinit var btnDelete: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgress: TextView
    private lateinit var tvSwapPath: TextView
    private lateinit var btnReattach: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        swapManager = SwapManager(this)
        initViews()
        setupListeners()
        autoRestoreSwap()
        requestNotificationPermission()
        startMemoryMonitor()
    }

    private fun initViews() {
        tvTotalRam = findViewById(R.id.tvTotalRam)
        tvUsedRam = findViewById(R.id.tvUsedRam)
        tvAvailableRam = findViewById(R.id.tvAvailableRam)
        tvSwapSize = findViewById(R.id.tvSwapSize)
        seekSwapSize = findViewById(R.id.seekSwapSize)
        tvSwapStatus = findViewById(R.id.tvSwapStatus)
        tvSwapInfo = findViewById(R.id.tvSwapInfo)
        btnCreate = findViewById(R.id.btnCreate)
        btnDelete = findViewById(R.id.btnDelete)
        progressBar = findViewById(R.id.progressBar)
        tvProgress = findViewById(R.id.tvProgress)
        tvSwapPath = findViewById(R.id.tvSwapPath)
        btnReattach = findViewById(R.id.btnReattach)
    }

    private fun setupListeners() {
        seekSwapSize.max = 63
        seekSwapSize.progress = 3
        updateSwapSizeLabel(3)

        seekSwapSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateSwapSizeLabel(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnCreate.setOnClickListener { onCreateSwap() }
        btnDelete.setOnClickListener { onDeleteSwap() }
        btnReattach.setOnClickListener { onReattachSwap() }
    }

    private fun getSwapSizeMb(): Int {
        return (seekSwapSize.progress + 1) * 128
    }

    private fun updateSwapSizeLabel(progress: Int) {
        val sizeMb = (progress + 1) * 128
        tvSwapSize.text = if (sizeMb >= 1024) {
            String.format("%.1f GB", sizeMb / 1024.0)
        } else {
            "${sizeMb} MB"
        }
    }

    private fun onCreateSwap() {
        val sizeMb = getSwapSizeMb()
        val sizeDisplay = if (sizeMb >= 1024) {
            String.format("%.1f GB", sizeMb / 1024.0)
        } else {
            "${sizeMb} MB"
        }

        AlertDialog.Builder(this)
            .setTitle("Create Phantom Swap")
            .setMessage(
                "Create a swap file.\n\n" +
                "Size: $sizeDisplay\n" +
                "Storage Consumption: $sizeDisplay\n\n" +
                "For large sizes, creation may take several minutes.\n\n" +
                "Do you want to continue?"
            )
            .setPositiveButton("YES") { _, _ ->
                showProgress(true)
                startSwapService(sizeMb)
                Toast.makeText(this, "Creating swap: ${sizeDisplay}...", Toast.LENGTH_SHORT).show()
                startProgressMonitor()
            }
            .setNegativeButton("NO", null)
            .show()
    }

    private fun onDeleteSwap() {
        AlertDialog.Builder(this)
            .setTitle("Delete Swap")
            .setMessage("Are you sure you want to delete the swap file?")
            .setPositiveButton("YES") { _, _ ->
                stopSwapService()
                swapManager.forceInvalidate()
                showProgress(false)
                tvSwapStatus.text = "● INACTIVE"
                tvSwapStatus.setTextColor(android.graphics.Color.RED)
                tvSwapInfo.text = "Deleting..."
                btnCreate.isEnabled = false
                btnDelete.isEnabled = false
                Toast.makeText(this, "Swap deleted", Toast.LENGTH_SHORT).show()
                handler.postDelayed({ updateStatus() }, 2000)
            }
            .setNegativeButton("NO", null)
            .show()
    }

    private fun autoRestoreSwap() {
        if (!swapManager.isSwapActive()) {
            val orphanedSize = swapManager.detectOrphanedSwap()
            if (orphanedSize > 0) {
                showProgress(true, "Restoring existing swap...")
                restoreSwapService(orphanedSize)
                Toast.makeText(this, "Auto-restoring ${orphanedSize}MB swap...", Toast.LENGTH_SHORT).show()
                startProgressMonitor()
            }
        }
    }

    private fun onReattachSwap() {
        val orphanedSize = swapManager.detectOrphanedSwap()
        if (orphanedSize > 0) {
            showProgress(true, "Restoring existing swap...")
            restoreSwapService(orphanedSize)
            Toast.makeText(this, "Re-attaching existing ${orphanedSize}MB swap...", Toast.LENGTH_SHORT).show()
            startProgressMonitor()
        }
    }

    private fun showProgress(show: Boolean, message: String = "Initializing...") {
        progressBar.visibility = if (show) View.VISIBLE else View.GONE
        tvProgress.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            btnCreate.isEnabled = false
            seekSwapSize.isEnabled = false
            tvProgress.text = message
        }
    }

    private fun startProgressMonitor() {
        val progressRunnable = object : Runnable {
            override fun run() {
                val status = swapManager.getSwapStatus()
                if (status.isActive) {
                    showProgress(false)
                    updateStatus()
                } else {
                    handler.postDelayed(this, 1000)
                }
            }
        }
        handler.postDelayed(progressRunnable, 2000)
    }

    private fun startSwapService(sizeMb: Int) {
        val intent = Intent(this, SwapForegroundService::class.java).apply {
            action = SwapForegroundService.ACTION_START
            putExtra(SwapForegroundService.EXTRA_SWAP_SIZE, sizeMb)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun restoreSwapService(sizeMb: Int) {
        val intent = Intent(this, SwapForegroundService::class.java).apply {
            action = SwapForegroundService.ACTION_RESTORE
            putExtra(SwapForegroundService.EXTRA_SWAP_SIZE, sizeMb)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopSwapService() {
        val intent = Intent(this, SwapForegroundService::class.java).apply {
            action = SwapForegroundService.ACTION_STOP
        }
        startService(intent)
    }

    private fun startMemoryMonitor() {
        memoryUpdateRunnable = object : Runnable {
            override fun run() {
                updateMemoryInfo()
                updateStatus()
                handler.postDelayed(this, 3000)
            }
        }
        handler.post(memoryUpdateRunnable!!)
    }

    private fun updateMemoryInfo() {
        val memInfo = swapManager.getMemoryInfo()
        tvTotalRam.text = formatBytes(memInfo.totalRam)
        tvUsedRam.text = formatBytes(memInfo.usedRam)
        tvAvailableRam.text = formatBytes(memInfo.availableRam)
    }

    private fun updateStatus() {
        val status = swapManager.getSwapStatus()
        if (status.isActive) {
            tvSwapStatus.text = "● ACTIVE"
            tvSwapStatus.setTextColor(android.graphics.Color.GREEN)
            val sizeDisplay = if (status.sizeMb >= 1024) {
                String.format("%.1f GB", status.sizeMb / 1024.0)
            } else {
                "${status.sizeMb} MB"
            }
            tvSwapInfo.text = "Size: $sizeDisplay"
            
            // Sync SeekBar to the currently active size
            val activeProgress = (status.sizeMb / 128) - 1
            if (activeProgress in 0..seekSwapSize.max) {
                seekSwapSize.progress = activeProgress
                updateSwapSizeLabel(activeProgress)
            }

            btnCreate.isEnabled = false
            btnDelete.isEnabled = true
            seekSwapSize.isEnabled = false
            btnReattach.visibility = View.GONE // ACTIVE時は隠す
            showProgress(false)
        } else {
            btnCreate.isEnabled = true
            btnDelete.isEnabled = false
            seekSwapSize.isEnabled = true

            // Detect orphaned swap (file exists but not active)
            val orphanedSize = swapManager.detectOrphanedSwap()
            if (orphanedSize > 0) {
                btnReattach.visibility = View.VISIBLE
                val sizeDisplay = if (orphanedSize >= 1024) {
                    String.format("%.1f GB", orphanedSize / 1024.0)
                } else {
                    "${orphanedSize}MB"
                }
                btnReattach.text = "RE-ATTACH $sizeDisplay SWAP"
                tvSwapInfo.text = "Detected existing $sizeDisplay swap file."
            } else {
                btnReattach.visibility = View.GONE
                tvSwapInfo.text = "No swap file"
            }
        }

        tvSwapPath.text = "SWAP path: " + swapManager.getDisplayPath()
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) {
            String.format("%.1f GB", mb / 1024.0)
        } else {
            String.format("%.0f MB", mb)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    100
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        memoryUpdateRunnable?.let { handler.removeCallbacks(it) }
    }
}
