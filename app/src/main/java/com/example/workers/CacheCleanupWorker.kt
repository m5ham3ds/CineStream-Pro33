package com.example.workers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.utils.CacheManagementHelper

class CacheCleanupWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            Log.d("CacheCleanupWorker", "Starting safe cache cleanup (preserving downloaded items)...")
            val freedBytes = CacheManagementHelper.cleanCacheSafely(context)
            Log.d("CacheCleanupWorker", "Cache cleanup completed. Freed: ${CacheManagementHelper.formatBytes(freedBytes)}")
            Result.success()
        } catch (e: Exception) {
            Log.e("CacheCleanupWorker", "Error cleaning cache", e)
            Result.failure()
        }
    }
}
