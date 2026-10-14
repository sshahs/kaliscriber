package com.kaliscriber.app.shortcuts

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.kaliscriber.app.KaliScriberApp
import com.kaliscriber.app.MainActivity
import com.kaliscriber.app.recording.RecordingState

/**
 * Quick Settings tile: one tap opens KaliScriber and starts recording a meeting, or shows the
 * meeting being recorded. The tile is lit while a meeting is recording.
 */
class RecordTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val state = (application as KaliScriberApp).container.recorder.state.value
        val recording = state is RecordingState.Recording
        tile.state = if (recording) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (recording) "Recording" else "Record meeting"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when (state) {
                is RecordingState.Recording -> if (state.paused) "Paused" else "Tap to view"
                is RecordingState.Finishing -> "Processing"
                RecordingState.Idle -> "KaliScriber"
            }
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = MainActivity.recordIntent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        /** Asks the system to refresh the tile, e.g. when recording starts or stops. */
        fun refresh(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, RecordTileService::class.java)) }
        }
    }
}
