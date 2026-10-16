package com.kaliscriber.app.autorecord

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kaliscriber.app.KaliScriberApp
import kotlinx.coroutines.launch

/** Alarms at meeting starts and ends, and "Don't record" from the notification. Not reachable by other apps. */
class AutoRecordReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = handle(context, intent)

    companion object {
        const val ACTION_TICK = "com.kaliscriber.app.action.AUTO_RECORD_TICK"
        const val ACTION_SKIP = "com.kaliscriber.app.action.AUTO_RECORD_SKIP"
        const val EXTRA_KEY = "key"
        const val EXTRA_END = "end"
    }
}

/** Alarms don't survive a reboot or an app update, and a changed clock or time zone moves every meeting. */
class AutoRecordBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in SYSTEM_ACTIONS) return
        handle(context, Intent(AutoRecordReceiver.ACTION_TICK).putExtra(REFRESH, true))
    }

    private companion object {
        val SYSTEM_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
}

private const val REFRESH = "refresh"

/** Runs the check (or the skip) in the app's scope; the receiver stays alive until it's done. */
private fun BroadcastReceiver.handle(context: Context, intent: Intent) {
    val container = (context.applicationContext as KaliScriberApp).container
    val pending = goAsync()
    container.scope.launch {
        try {
            container.meetings.load()
            when (intent.action) {
                AutoRecordReceiver.ACTION_SKIP -> {
                    val key = intent.getStringExtra(AutoRecordReceiver.EXTRA_KEY) ?: return@launch
                    container.autoRecorder.skip(key, intent.getLongExtra(AutoRecordReceiver.EXTRA_END, System.currentTimeMillis()))
                }
                else -> container.autoRecorder.tick(refresh = intent.getBooleanExtra(REFRESH, false))
            }
        } catch (e: Exception) {
            Log.w("AutoRecord", "Auto-record check failed", e)
        } finally {
            pending.finish()
        }
    }
}
