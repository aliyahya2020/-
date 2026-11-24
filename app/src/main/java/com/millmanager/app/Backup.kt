package com.millmanager.app

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import java.io.File
import java.util.concurrent.TimeUnit

class BackupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        return try {
            val dbFile = applicationContext.getDatabasePath("mill_manager.db")
            if (!dbFile.exists()) return Result.success()
            val dir = File(applicationContext.getExternalFilesDir(null), "MillManager/Backups")
            if (!dir.exists()) dir.mkdirs()
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm", java.util.Locale.US).format(java.util.Date())
            val target = File(dir, "mill_backup_$stamp.db")
            dbFile.copyTo(target, overwrite = true)
            File(dir, "latest_backup.txt").writeText(target.name)
            dir.listFiles()?.filter { it.name.startsWith("mill_backup_") && it.extension == "db" }
                ?.sortedByDescending { it.lastModified() }?.drop(30)?.forEach { it.delete() }
            Result.success()
        } catch (_: Exception) { Result.retry() }
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BackupWorker>(24, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("mill_daily_backup", ExistingPeriodicWorkPolicy.KEEP, request)
        }
        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueue(androidx.work.OneTimeWorkRequestBuilder<BackupWorker>().build())
        }
    }
}
