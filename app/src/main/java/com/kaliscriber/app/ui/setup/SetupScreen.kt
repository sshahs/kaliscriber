@file:OptIn(ExperimentalMaterial3Api::class)

package com.kaliscriber.app.ui.setup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaliscriber.app.LaunchRequest
import com.kaliscriber.app.appContainer
import com.kaliscriber.app.appViewModel
import com.kaliscriber.app.speech.ModelCatalog
import com.kaliscriber.app.speech.ModelState
import com.kaliscriber.app.ui.common.DropdownSelector
import com.kaliscriber.app.ui.settings.ProviderStatus
import com.kaliscriber.app.ui.settings.SettingsViewModel
import com.kaliscriber.core.ai.ProviderId
import kotlinx.coroutines.launch

private const val STEPS = 4

/**
 * First-run setup in four steps: how meetings are transcribed, which AI writes the notes,
 * permissions, and an optional test recording. Every step can be skipped and changed later in Settings.
 */
@Composable
fun SetupScreen(onDone: () -> Unit) {
    val vm = appViewModel { SettingsViewModel(it) }
    val container = appContainer()
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableIntStateOf(0) }
    // Saved before leaving, so the home screen doesn't open setup again.
    fun finish(record: Boolean) {
        scope.launch {
            container.settingsStore.update { it.copy(setupDone = true) }
            if (record) container.launches.post(LaunchRequest.Record)
            onDone()
        }
    }
    // Back goes to the previous step; from the first it closes setup (which then won't reopen by itself).
    BackHandler { if (step > 0) step-- else finish(record = false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Set up KaliScriber") },
                actions = { IconButton(onClick = { finish(record = false) }) { Icon(Icons.Filled.Close, contentDescription = "Close setup") } },
            )
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                LinearProgressIndicator(progress = { (step + 1f) / STEPS }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.size(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (step > 0) TextButton(onClick = { step-- }) { Text("Back") }
                    Spacer(Modifier.weight(1f))
                    Text("Step ${step + 1} of $STEPS", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.weight(1f))
                    if (step < STEPS - 1) {
                        Button(onClick = { step++ }) { Text("Next") }
                    } else {
                        Button(onClick = { finish(record = false) }) { Text("Done") }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (step) {
                0 -> TranscriptionStep(vm)
                1 -> AiStep(vm)
                2 -> PermissionsStep()
                else -> DoneStep(onRecord = { finish(record = true) })
            }
        }
    }
}

@Composable
private fun StepTitle(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.headlineSmall)
    Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Choice(selected: Boolean, title: String, body: String, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Step 1: on the phone (and which model), or a cloud service. */
@Composable
private fun TranscriptionStep(vm: SettingsViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val states by vm.modelStates.collectAsStateWithLifecycle()
    StepTitle(
        "How should meetings be transcribed?",
        "On the phone, audio never leaves it. A cloud service needs its own API key and is useful on older phones.",
    )
    val local = settings.sttProvider == ProviderId.LOCAL
    Choice(local, "On this phone (private)", "Download a speech model once; it works offline.") {
        vm.update { it.copy(sttProvider = ProviderId.LOCAL) }
    }
    if (local) {
        val chosen = ModelCatalog.asrModels.firstOrNull { it.id == settings.localAsrModel } ?: ModelCatalog.recommended
        ModelCatalog.asrModels.forEach { model ->
            val state = states[model.id]
            Choice(
                selected = model.id == chosen.id,
                title = model.name + if (model == ModelCatalog.recommended) " (recommended)" else "",
                body = "${model.languages} · ${model.downloadMb} MB · ${model.description}",
            ) { vm.update { it.copy(localAsrModel = model.id) } }
            if (model.id == chosen.id) {
                when (state) {
                    is ModelState.Ready -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Downloaded and ready", style = MaterialTheme.typography.bodyMedium)
                    }
                    is ModelState.Downloading -> Column {
                        Text("Downloading… ${(state.progress * 100).toInt()}% · you can carry on meanwhile", style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                    }
                    is ModelState.Failed -> Column {
                        Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        FilledTonalButton(onClick = { vm.downloadModel(model) }) { Text("Try again") }
                    }
                    else -> FilledTonalButton(onClick = { vm.downloadModel(model) }) {
                        Text("Download ${model.downloadMb} MB (Wi-Fi recommended)")
                    }
                }
            }
        }
    }
    val cloud = ProviderId.sttProviders.filter { it != ProviderId.LOCAL }
    Choice(!local, "With a cloud service", "ElevenLabs, Deepgram, AssemblyAI, OpenAI and others; detects speakers too.") {
        if (local) vm.update { it.copy(sttProvider = cloud.first()) }
    }
    if (!local) {
        DropdownSelector(
            label = "Transcription service",
            selected = settings.sttProvider,
            options = cloud,
            optionLabel = { it.displayName },
            onSelect = { id -> vm.update { it.copy(sttProvider = id) } },
            modifier = Modifier.fillMaxWidth(),
        )
        KeyField(vm, settings.sttProvider)
    }
}

/** Step 2: the AI that writes summaries and answers, with its key and a test. */
@Composable
private fun AiStep(vm: SettingsViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    StepTitle(
        "Which AI writes your notes?",
        "Your key stays encrypted on this phone and is only sent to that provider. You can switch at any time.",
    )
    DropdownSelector(
        label = "AI for summaries, notes and answers",
        selected = settings.chatProvider,
        options = ProviderId.chatProviders,
        optionLabel = { it.displayName },
        onSelect = { id -> vm.update { it.copy(chatProvider = id) } },
        modifier = Modifier.fillMaxWidth(),
    )
    KeyField(vm, settings.chatProvider)
}

/** The provider's API key, where to get one, and a test that also saves it. */
@Composable
private fun KeyField(vm: SettingsViewModel, id: ProviderId) {
    val keys by vm.configuredKeys.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    if (!id.keyRequired) {
        Text("${id.displayName} doesn't need a key.", style = MaterialTheme.typography.bodySmall)
        return
    }
    var key by remember(id) { mutableStateOf("") }
    OutlinedTextField(
        value = key,
        onValueChange = { key = it },
        label = { Text("${id.displayName} API key") },
        placeholder = { Text(if (id in keys) "Saved; paste a new one to replace it" else "Paste your key") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(
            onClick = {
                val draft = vm.draftFor(id).let { if (key.isNotBlank()) it.copy(apiKey = key.trim()) else it }
                vm.save(id, draft)
                vm.test(id, draft)
            },
            enabled = key.isNotBlank() || id in keys,
        ) { Text("Save and test") }
        if (id.keyUrl.isNotBlank()) {
            OutlinedButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(id.keyUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }) { Text("Get a key") }
        }
    }
    when (val s = status[id]) {
        ProviderStatus.Working -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Testing…", style = MaterialTheme.typography.bodySmall)
        }
        is ProviderStatus.Ok -> Text("✓ ${s.message}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        is ProviderStatus.Failed -> Text(s.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        else -> if (id in keys) Text("✓ Key saved", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
}

/** Step 3: the microphone, and notifications for recording controls and ready notes. */
@Composable
private fun PermissionsStep() {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    fun granted(permission: String) = refresh >= 0 &&
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    StepTitle("Allow access", "KaliScriber only listens while you record, and shows a notification whenever it does.")
    PermissionRow(
        "Microphone",
        "Needed to record meetings.",
        granted(Manifest.permission.RECORD_AUDIO),
    ) { ask.launch(Manifest.permission.RECORD_AUDIO) }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        PermissionRow(
            "Notifications",
            "Recording controls, notes-ready alerts and reminders.",
            granted(Manifest.permission.POST_NOTIFICATIONS),
        ) { ask.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }
}

@Composable
private fun PermissionRow(title: String, body: String, granted: Boolean, onAllow: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodySmall)
            }
            if (granted) {
                Icon(Icons.Filled.CheckCircle, contentDescription = "Allowed", tint = MaterialTheme.colorScheme.primary)
            } else {
                FilledTonalButton(onClick = onAllow) { Text("Allow") }
            }
        }
    }
}

/** Step 4: a short test recording, or straight to the app. */
@Composable
private fun DoneStep(onRecord: () -> Unit) {
    StepTitle(
        "You're set",
        "Try a 30-second test: say a few sentences, add a bookmark, then hold Stop. You'll see the transcript, a summary and any action items.",
    )
    Button(onClick = onRecord, modifier = Modifier.fillMaxWidth()) { Text("Record a test") }
    Text(
        "Everything here can be changed later in Settings.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
