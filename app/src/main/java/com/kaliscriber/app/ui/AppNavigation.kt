package com.kaliscriber.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kaliscriber.app.ui.home.HomeScreen
import com.kaliscriber.app.ui.meeting.MeetingScreen
import com.kaliscriber.app.ui.notes.NoteScreen
import com.kaliscriber.app.ui.recording.RecordingScreen
import com.kaliscriber.app.ui.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val RECORDING = "recording"
    const val SETTINGS = "settings"
    const val MEETING = "meeting/{id}?tab={tab}"
    const val NOTE = "note/{id}"
    fun meeting(id: String, tab: Int = 0) = "meeting/$id?tab=$tab"
    fun note(id: String) = "note/$id"
}

@Composable
fun AppNavigation() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenMeeting = { nav.navigate(Routes.meeting(it)) },
                onOpenNote = { nav.navigate(Routes.note(it)) },
                onOpenRecording = { nav.navigate(Routes.RECORDING) { launchSingleTop = true } },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
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
            ),
        ) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            MeetingScreen(
                meetingId = id,
                initialTab = entry.arguments?.getInt("tab") ?: 0,
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
