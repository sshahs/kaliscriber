package com.kaliscriber.app.autorecord

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kaliscriber.app.KaliScriberApp

/** Reads the calendar every half hour, so meetings added or moved since the last alarm are picked up. */
class AutoRecordWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as KaliScriberApp).container
        container.meetings.load()
        container.autoRecorder.tick(refresh = true)
        return Result.success()
    }
}
