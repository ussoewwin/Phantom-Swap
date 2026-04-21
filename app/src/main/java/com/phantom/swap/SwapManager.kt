package com.phantom.swap

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class SwapManager(private val context: Context) {

    companion object {
        private const val TAG = "SwapManager"
        private const val SWAP_FILE_NAME = "swapfile.dat"
        private const val PREFS_NAME = "swap_prefs"
        private const val KEY_SWAP_ACTIVE = "swap_active"
        private const val KEY_SWAP_SIZE_MB = "swap_size_mb"

        private const val CHUNK_SIZE_64BIT = 512L * 1024 * 1024  // 512MB per chunk on 64-bit
        private const val CHUNK_SIZE_32BIT = 128L * 1024 * 1024  // 128MB per chunk on 32-bit

        @Volatile
        private var mappedBuffers: List<MappedByteBuffer>? = null
        @Volatile
        private var fileChannel: FileChannel? = null
        @Volatile
        private var randomAccessFile: RandomAccessFile? = null
        @Volatile
        private var currentSizeMb: Int = 0
    }

    data class MemoryInfo(
        val totalRam: Long,
        val availableRam: Long,
        val usedRam: Long
    )

    data class SwapStatus(
        val isActive: Boolean,
        val sizeMb: Int,
        val filePath: String?
    )

    interface ProgressCallback {
        fun onProgress(percent: Int, message: String)
        fun onComplete(success: Boolean, message: String)
    }

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun is32Bit(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return !android.os.Process.is64Bit()
        }
        val abis = Build.SUPPORTED_ABIS
        return abis.isNotEmpty() && abis.all { it.contains("arm") && !it.contains("64") || it == "x86" }
    }

    private fun getMaxChunkSize(): Long {
        return if (is32Bit()) CHUNK_SIZE_32BIT else CHUNK_SIZE_64BIT
    }

    fun getMemoryInfo(): MemoryInfo {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return MemoryInfo(
            totalRam = memInfo.totalMem,
            availableRam = memInfo.availMem,
            usedRam = memInfo.totalMem - memInfo.availMem
        )
    }

    fun getSwapStatus(): SwapStatus {
        val swapFile = getSwapFile()
        val isActive = mappedBuffers != null && swapFile?.exists() == true
        val sizeMb = if (isActive) currentSizeMb else 0
        return SwapStatus(
            isActive = isActive,
            sizeMb = sizeMb,
            filePath = if (swapFile?.exists() == true) swapFile.absolutePath else null
        )
    }

    fun createSwap(sizeMb: Int, callback: ProgressCallback? = null): Result<String> {
        return try {
            if (mappedBuffers != null) {
                return Result.failure(Exception("Swap is already active"))
            }

            val swapFile = getSwapFile() ?: return Result.failure(Exception("Storage not mounted"))
            val sizeBytes = sizeMb.toLong() * 1024 * 1024

            Log.i(TAG, "createSwap: ${sizeMb}MB, 32bit=${is32Bit()}, chunk=${getMaxChunkSize() / 1024 / 1024}MB")
            callback?.onProgress(0, "Creating swap file (${sizeMb}MB)...")
            
            if (swapFile.exists()) {
                swapFile.delete()
            }
            
            val bufferSize = 1024 * 1024
            val zeroBuffer = ByteArray(bufferSize)
            val totalBlocks = sizeMb
            
            java.io.FileOutputStream(swapFile).use { fos ->
                for (i in 0 until totalBlocks) {
                    fos.write(zeroBuffer)
                    if (i % 10 == 0 || i == totalBlocks - 1) {
                        val percent = (i.toFloat() / totalBlocks * 90).toInt()
                        callback?.onProgress(percent, "Writing data: ${i} MB / ${sizeMb} MB")
                    }
                }
            }

            callback?.onProgress(90, "Mapping to memory...")
            mapSwapFile(swapFile, sizeBytes)

            saveSwapState(true, sizeMb)

            callback?.onProgress(100, "Swap active: ${sizeMb}MB")
            callback?.onComplete(true, "Swap created: ${sizeMb}MB")
            Result.success("Swap created: ${sizeMb}MB")
        } catch (e: Exception) {
            Log.e(TAG, "createSwap failed", e)
            cleanup()
            callback?.onComplete(false, "Error: ${e.message}")
            Result.failure(e)
        }
    }

    fun restoreSwap(sizeMb: Int, callback: ProgressCallback? = null): Result<String> {
        return try {
            if (mappedBuffers != null) {
                return Result.failure(Exception("Swap is already active"))
            }

            val swapFile = getSwapFile() ?: return Result.failure(Exception("Storage not mounted"))
            if (!swapFile.exists()) {
                return Result.failure(Exception("Swap file not found"))
            }

            val sizeBytes = sizeMb.toLong() * 1024 * 1024
            val actualSize = swapFile.length()
            if (actualSize < sizeBytes) {
                return Result.failure(Exception("Size mismatch: Expect $sizeMb MB, Found ${actualSize / 1024 / 1024} MB"))
            }

            Log.i(TAG, "restoreSwap: ${sizeMb}MB, 32bit=${is32Bit()}, chunk=${getMaxChunkSize() / 1024 / 1024}MB")
            callback?.onProgress(90, "Restoring memory mapping...")
            mapSwapFile(swapFile, sizeBytes)

            callback?.onProgress(100, "Swap active: ${sizeMb}MB")
            callback?.onComplete(true, "Swap restored: ${sizeMb}MB")
            Result.success("Swap restored: ${sizeMb}MB")
        } catch (e: Exception) {
            Log.e(TAG, "restoreSwap failed", e)
            cleanup()
            callback?.onComplete(false, "Restore Error: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Maps the swap file into memory using architecture-appropriate chunk sizes.
     * 32-bit devices use 128MB chunks to avoid virtual address space exhaustion.
     */
    private fun mapSwapFile(swapFile: File, sizeBytes: Long) {
        val raf = RandomAccessFile(swapFile, "rw")
        val channel = raf.channel
        val maxChunk = getMaxChunkSize()
        var mappedPosition = 0L
        val buffersList = mutableListOf<MappedByteBuffer>()

        try {
            while (mappedPosition < sizeBytes) {
                val mapSize = Math.min(sizeBytes - mappedPosition, maxChunk)
                Log.d(TAG, "mmap chunk: offset=$mappedPosition, size=${mapSize / 1024 / 1024}MB")
                val bufferChunk = channel.map(FileChannel.MapMode.READ_WRITE, mappedPosition, mapSize)
                buffersList.add(bufferChunk)
                mappedPosition += mapSize
            }
        } catch (e: Exception) {
            Log.e(TAG, "mmap failed at offset=$mappedPosition", e)
            buffersList.clear()
            channel.close()
            raf.close()
            throw e
        }

        randomAccessFile = raf
        fileChannel = channel
        mappedBuffers = buffersList
        currentSizeMb = (sizeBytes / 1024 / 1024).toInt()
    }

    fun deleteSwap(): Result<String> {
        return try {
            cleanup()
            val swapFile = getSwapFile()
            if (swapFile?.exists() == true) {
                swapFile.delete()
            }
            saveSwapState(false, 0)
            Result.success("Swap deleted successfully")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun isSwapActive(): Boolean {
        val swapFile = getSwapFile()
        return mappedBuffers != null && swapFile?.exists() == true
    }

    /**
     * Probes mapped buffers to verify they are still valid.
     * Returns false if any buffer throws or the mapping has been lost.
     */
    fun isSwapHealthy(): Boolean {
        val buffers = mappedBuffers ?: return false
        val swapFile = getSwapFile() ?: return false
        if (!swapFile.exists()) return false
        return try {
            for (buf in buffers) {
                buf.get(0)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Health check failed", e)
            false
        }
    }

    /**
     * Returns true if swap should be active (file + prefs exist) but the mapping is dead.
     * Used by the service health monitor to trigger auto-recovery.
     */
    fun needsRecovery(): Boolean {
        if (isSwapActive() && isSwapHealthy()) return false
        val savedSize = getSavedSwapSizeMb()
        if (savedSize <= 0) return false
        val swapFile = getSwapFile() ?: return false
        return swapFile.exists() && swapFile.length() >= savedSize.toLong() * 1024 * 1024
    }

    fun getSavedSwapSizeMb(): Int {
        val prefsSize = if (prefs.getBoolean(KEY_SWAP_ACTIVE, false)) {
            prefs.getInt(KEY_SWAP_SIZE_MB, 0)
        } else {
            0
        }

        if (prefsSize == 0) {
            val swapFile = getSwapFile()
            if (swapFile?.exists() == true) {
                val fileMb = (swapFile.length() / (1024 * 1024)).toInt()
                if (fileMb > 0) {
                    return fileMb
                }
            }
        }
        return prefsSize
    }

    fun getDisplayPath(): String {
        val dir = context.filesDir
        return dir.absolutePath
    }

    fun getDiagnosticReport(): String {
        val sb = StringBuilder()
        val dir = context.filesDir
        sb.append("Dir: ${if (dir.exists()) "Ready" else "Missing"}\n")
        
        val swapFile = getSwapFile()
        if (swapFile != null) {
            sb.append("File: ${if (swapFile.exists()) "Found (${swapFile.length() / 1024 / 1024}MB)" else "Missing"}\n")
        } else {
            sb.append("File: PathNULL\n")
        }

        val isActive = prefs.getBoolean(KEY_SWAP_ACTIVE, false)
        val savedSize = prefs.getInt(KEY_SWAP_SIZE_MB, 0)
        sb.append("Prefs: ${if (isActive) "Active($savedSize)" else "Inactive"}\n")
        sb.append("Arch: ${if (is32Bit()) "32bit" else "64bit"}\n")
        sb.append("Mapped: ${mappedBuffers?.size ?: 0} chunks\n")
        sb.append("Healthy: ${isSwapHealthy()}\n")

        return sb.toString()
    }

    /**
     * Reads one byte per 1MB across all mapped buffers.
     * This marks the pages as "recently accessed" in the kernel's LRU,
     * preventing eviction from physical RAM.
     */
    fun touchAllPages() {
        val buffers = mappedBuffers ?: return
        val stride = 1024 * 1024 // 1MB intervals
        var touchCount = 0
        for (buf in buffers) {
            val limit = buf.capacity()
            var pos = 0
            while (pos < limit) {
                buf.get(pos)
                pos += stride
                touchCount++
            }
        }
        Log.d(TAG, "Touched $touchCount pages across ${buffers.size} chunks")
    }

    fun detectOrphanedSwap(): Int {
        val swapFile = getSwapFile()
        if (swapFile?.exists() == true) {
            val sizeMb = (swapFile.length() / (1024 * 1024)).toInt()
            if (sizeMb >= 128) return sizeMb
        }
        return 0
    }

    /**
     * Force-clears mappings without closing resources.
     * Used before recovery when the mapping may already be invalid.
     */
    fun forceInvalidate() {
        mappedBuffers = null
        try { fileChannel?.close() } catch (_: Exception) {}
        fileChannel = null
        try { randomAccessFile?.close() } catch (_: Exception) {}
        randomAccessFile = null
        currentSizeMb = 0
    }

    private fun cleanup() {
        try {
            mappedBuffers = null
            fileChannel?.close()
            fileChannel = null
            randomAccessFile?.close()
            randomAccessFile = null
            currentSizeMb = 0
        } catch (_: Exception) {}
    }

    private fun saveSwapState(active: Boolean, sizeMb: Int) {
        prefs.edit()
            .putBoolean(KEY_SWAP_ACTIVE, active)
            .putInt(KEY_SWAP_SIZE_MB, sizeMb)
            .commit()
    }

    private fun getSwapFile(): File? {
        val dir = context.filesDir
        return File(dir, SWAP_FILE_NAME)
    }
}
