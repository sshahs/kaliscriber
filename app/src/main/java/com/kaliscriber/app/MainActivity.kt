package com.kaliscriber.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.kaliscriber.app.ui.theme.isDark
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kaliscriber.app.processing.ReadyNotifier
import com.kaliscriber.app.tasks.RecapReminder
import com.kaliscriber.app.tasks.TaskReminders
import com.kaliscriber.app.ui.AppNavigation
import com.kaliscriber.app.ui.theme.KaliScriberTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val container: AppContainer get() = (application as KaliScriberApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val openTasks = intent?.getBooleanExtra(TaskReminders.EXTRA_OPEN_TASKS, false) == true
        val openSchedule = intent?.getBooleanExtra(EXTRA_OPEN_SCHEDULE, false) == true
        // A recreated activity has already handled the intent it was started with.
        if (savedInstanceState == null) handle(intent)
        setContent {
            val settings by container.settingsStore.value.collectAsState()
            val dark = isDark(settings.themeMode)
            // System bar icons follow the app's theme, which may differ from the phone's.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
                onDispose {}
            }
            KaliScriberTheme(settings.themeMode, settings.dynamicColor) {
                AppNavigation(openTasks = openTasks, openSchedule = openSchedule)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    /** Files shared to or opened with the app become meetings; the tile and widget start recording. */
    private fun handle(intent: Intent?) {
        intent ?: return
        if (intent.action == ACTION_RECORD || intent.getBooleanExtra(EXTRA_RECORD, false)) {
            container.launches.post(LaunchRequest.Record)
            return
        }
        intent.getStringExtra(ReadyNotifier.EXTRA_OPEN_MEETING)?.let { id ->
            container.launches.post(LaunchRequest.OpenMeeting(id))
            return
        }
        if (intent.getBooleanExtra(RecapReminder.EXTRA_OPEN_RECAP, false)) {
            container.launches.post(LaunchRequest.OpenRecap)
            return
        }
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.stream())
            Intent.ACTION_SEND_MULTIPLE -> intent.streams()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        Toast.makeText(this, if (uris.size == 1) "Importing into KaliScriber…" else "Importing ${uris.size} files…", Toast.LENGTH_SHORT).show()
        // The app's scope keeps the copy going if the screen rotates; the grant to read lasts as long as this task.
        container.scope.launch {
            var last: String? = null
            for (uri in uris) {
                try {
                    last = container.importer.import(uri)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Couldn't import: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            last?.let { container.launches.post(LaunchRequest.OpenMeeting(it)) }
        }
    }

    private fun Intent.stream(): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri
        }

    private fun Intent.streams(): List<Uri> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            @Suppress("DEPRECATION")
            getParcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM).orEmpty().filterIsInstance<Uri>()
        }

    companion object {
        /** Opens the app and starts recording (Quick Settings tile, widget). */
        const val ACTION_RECORD = "com.kaliscriber.app.action.RECORD"
        const val EXTRA_RECORD = "com.kaliscriber.app.extra.RECORD"
        /** Opens the app on the Schedule tab (from the next-recording widget). */
        const val EXTRA_OPEN_SCHEDULE = "com.kaliscriber.app.extra.OPEN_SCHEDULE"

        fun recordIntent(context: android.content.Context): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_RECORD)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        /** The scrims Android uses behind three-button navigation, as in enableEdgeToEdge's defaults. */
        private val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        private val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as KaliScriberApp).container

/** ViewModel built from the [AppContainer]; [key] distinguishes instances (e.g. per meeting id). */
@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val container = appContainer()
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}
