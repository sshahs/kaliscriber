package com.kaliscriber.app.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kaliscriber.app.LaunchRequest
import com.kaliscriber.app.appContainer
import com.kaliscriber.app.ui.ask.AskAllScreen
import com.kaliscriber.app.ui.home.HomeScreen
import com.kaliscriber.app.ui.meeting.MeetingScreen
import com.kaliscriber.app.ui.notes.NoteScreen
import com.kaliscriber.app.ui.people.PersonScreen
import com.kaliscriber.app.ui.recap.RecapScreen
import com.kaliscriber.app.ui.schedule.ScheduleEditScreen
import com.kaliscriber.app.ui.recording.RecordingScreen
import com.kaliscriber.app.ui.settings.SettingsPage
import com.kaliscriber.app.ui.setup.SetupScreen
import com.kaliscriber.app.ui.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val RECORDING = "recording"
    const val SETTINGS = "settings?page={page}"
    fun settings(page: SettingsPage? = null) = "settings" + (page?.let { "?page=${it.name}" } ?: "")
    const val MEETING = "meeting/{id}?tab={tab}&seg={seg}"
    const val NOTE = "note/{id}"
    const val ASK_ALL = "ask-all?q={q}"
    const val PERSON = "person/{name}"
    const val RECAP = "recap"
    const val SCHEDULE_EDIT = "schedule/edit?id={id}"
    fun scheduleEdit(id: String?) = "schedule/edit" + (id?.let { "?id=${Uri.encode(it)}" } ?: "")
    const val SETUP = "setup"
    fun person(name: String) = "person/${Uri.encode(name)}"
    fun meeting(id: String, tab: Int = 0, segmentId: String? = null) =
        "meeting/$id?tab=$tab" + (segmentId?.let { "&seg=${Uri.encode(it)}" } ?: "")
    fun note(id: String) = "note/$id"
    fun askAll(question: String? = null) = "ask-all" + (question?.let { "?q=${Uri.encode(it)}" } ?: "")
}

/** [openTasks] starts on the task list (from the reminder notification), [openSchedule] on the Schedule tab. */
@Composable
fun AppNavigation(openTasks: Boolean = false, openSchedule: Boolean = false) {
    val nav = rememberNavController()
    val launches = appContainer().launches
    val request by launches.pending.collectAsStateWithLifecycle()
    LaunchedEffect(request) {
        when (val r = request) {
            is LaunchRequest.OpenMeeting -> {
                launches.consume(r)
                nav.navigate(Routes.meeting(r.id)) { popUpTo(Routes.HOME) }
            }
            // Home starts the recording (it asks for the microphone permission if needed).
            LaunchRequest.Record -> if (nav.currentDestination?.route != Routes.HOME) nav.popBackStack(Routes.HOME, inclusive = false)
            LaunchRequest.OpenRecap -> {
                launches.consume(LaunchRequest.OpenRecap)
                nav.navigate(Routes.RECAP) { popUpTo(Routes.HOME) }
            }
            null -> Unit
        }
    }
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenMeeting = { nav.navigate(Routes.meeting(it)) },
                onOpenNote = { nav.navigate(Routes.note(it)) },
                onOpenRecording = { nav.navigate(Routes.RECORDING) { launchSingleTop = true } },
                onOpenSettings = { nav.navigate(Routes.settings()) },
                onOpenMeetingAt = { id, segmentId -> nav.navigate(Routes.meeting(id, tab = if (segmentId != null) 1 else 0, segmentId = segmentId)) },
                onAskAll = { nav.navigate(Routes.askAll(it)) },
                onOpenPerson = { nav.navigate(Routes.person(it)) },
                onOpenRecap = { nav.navigate(Routes.RECAP) },
                onEditSchedule = { nav.navigate(Routes.scheduleEdit(it)) },
                onOpenSettingsPage = { nav.navigate(Routes.settings(it)) },
                onOpenSetup = { nav.navigate(Routes.SETUP) { launchSingleTop = true } },
                startTab = when {
                    openTasks -> 2
                    openSchedule -> 4
                    else -> 0
                },
            )
        }
        composable(Routes.SETUP) {
            SetupScreen(onDone = { nav.popBackStack(Routes.HOME, inclusive = false) })
        }
        composable(
            Routes.SCHEDULE_EDIT,
            arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            ScheduleEditScreen(id = entry.arguments?.getString("id"), onBack = { nav.popBackStack() })
        }
        composable(Routes.RECAP) {
            RecapScreen(onBack = { nav.popBackStack() }, onOpenMeeting = { nav.navigate(Routes.meeting(it)) })
        }
        composable(Routes.PERSON, arguments = listOf(navArgument("name") { type = NavType.StringType })) { entry ->
            PersonScreen(
                name = entry.arguments?.getString("name").orEmpty(),
                onBack = { nav.popBackStack() },
                onOpenMeeting = { nav.navigate(Routes.meeting(it)) },
                onAsk = { nav.navigate(Routes.askAll(it)) },
            )
        }
        composable(Routes.ASK_ALL, arguments = listOf(navArgument("q") { type = NavType.StringType; nullable = true; defaultValue = null })) { entry ->
            AskAllScreen(
                initialQuestion = entry.arguments?.getString("q"),
                onBack = { nav.popBackStack() },
                onOpenSettings = { nav.navigate(Routes.settings(it)) },
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
        composable(
            Routes.SETTINGS,
            arguments = listOf(navArgument("page") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            val page = entry.arguments?.getString("page")?.let { name -> SettingsPage.entries.firstOrNull { it.name == name } }
            SettingsScreen(onBack = { nav.popBackStack() }, initialPage = page)
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
                onOpenSettings = { nav.navigate(Routes.settings()) },
                onOpenSettingsPage = { nav.navigate(Routes.settings(it)) },
                onOpenMeeting = { nav.navigate(Routes.meeting(it)) },
            )
        }
        composable(Routes.NOTE, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            NoteScreen(noteId = id, onBack = { nav.popBackStack() })
        }
    }
}
