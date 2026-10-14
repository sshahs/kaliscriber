package com.kaliscriber.app.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kaliscriber.app.ui.ask.AskAllScreen
import com.kaliscriber.app.ui.home.HomeScreen
import com.kaliscriber.app.ui.meeting.MeetingScreen
import com.kaliscriber.app.ui.notes.NoteScreen
import com.kaliscriber.app.ui.recording.RecordingScreen
import com.kaliscriber.app.ui.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val RECORDING = "recording"
    const val SETTINGS = "settings"
    const val MEETING = "meeting/{id}?tab={tab}&seg={seg}"
    const val NOTE = "note/{id}"
    const val ASK_ALL = "ask-all?q={q}"
    fun meeting(id: String, tab: Int = 0, segmentId: String? = null) =
        "meeting/$id?tab=$tab" + (segmentId?.let { "&seg=${Uri.encode(it)}" } ?: "")
    fun note(id: String) = "note/$id"
    fun askAll(question: String? = null) = "ask-all" + (question?.let { "?q=${Uri.encode(it)}" } ?: "")
}

/** [openTasks] starts on the task list (from the reminder notification). */
@Composable
fun AppNavigation(openTasks: Boolean = false) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenMeeting = { nav.navigate(Routes.meeting(it)) },
                onOpenNote = { nav.navigate(Routes.note(it)) },
                onOpenRecording = { nav.navigate(Routes.RECORDING) { launchSingleTop = true } },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenMeetingAt = { id, segmentId -> nav.navigate(Routes.meeting(id, tab = if (segmentId != null) 1 else 0, segmentId = segmentId)) },
                onAskAll = { nav.navigate(Routes.askAll(it)) },
                startTab = if (openTasks) 2 else 0,
            )
        }
        composable(Routes.ASK_ALL, arguments = listOf(navArgument("q") { type = NavType.StringType; nullable = true; defaultValue = null })) { entry ->
            AskAllScreen(
                initialQuestion = entry.arguments?.getString("q"),
                onBack = { nav.popBackStack() },
                onOpenSource = { source ->
                    nav.navigate(Routes.meeting(source.meetingId, tab = if (source.segmentId != null) 1 else 0, segmentId = source.segmentId))
                },
            )
        }
        composable(Routes.RECORDING) {
            RecordingScreen(
                onBack = { nav.popBackStack() },
                onAskAi = { meetingId -> nav.navigate(Routes.meeting(meetingId, tab = 2)) },
                onFinished = { meetingId ->
                    nav.navigate(Routes.meeting(meetingId)) { popUpTo(Routes.HOME) }
                },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
        composable(
            Routes.MEETING,
            arguments = listOf(
                navArgument("id") { type = NavType.StringType },
                navArgument("tab") {
                    type = NavType.IntType
                    defaultValue = 0
                },
                navArgument("seg") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            MeetingScreen(
                meetingId = id,
                initialTab = entry.arguments?.getInt("tab") ?: 0,
                focusSegmentId = entry.arguments?.getString("seg"),
                onBack = { nav.popBackStack() },
                onOpenNote = { nav.navigate(Routes.note(it)) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.NOTE, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            NoteScreen(noteId = id, onBack = { nav.popBackStack() })
        }
    }
}
