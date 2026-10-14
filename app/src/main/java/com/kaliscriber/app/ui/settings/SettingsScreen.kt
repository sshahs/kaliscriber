@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.kaliscriber.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaliscriber.app.appViewModel
import com.kaliscriber.app.ui.common.DropdownSelector
import com.kaliscriber.app.ui.common.SectionCard
import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.ai.ProviderId
import com.kaliscriber.core.ai.stt.SttClients
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val vm = appViewModel { SettingsViewModel(it) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val keys by vm.configuredKeys.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ActiveServices(settings, keys, vm) }
            item {
                Column {
                    Text("Providers & API keys", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                    Text(
                        "Bring your own keys. They are encrypted with the Android Keystore and only sent to the provider they belong to.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(ProviderId.entries.toList(), key = { it.name }) { id ->
                ProviderCard(
                    id = id,
                    settings = settings,
                    hasKey = id in keys,
                    status = status[id],
                    expanded = expanded == id.name,
                    onToggle = { expanded = if (expanded == id.name) null else id.name },
                    vm = vm,
                )
            }
            item { ProcessingSettings(settings, vm) }
            item {
                Text(
                    "Privacy: recordings, transcripts and notes stay on this device. Audio and text are sent only to the AI services you select above, using your keys.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ActiveServices(settings: AppSettings, keys: Set<ProviderId>, vm: SettingsViewModel) {
    SectionCard("Active services") {
        DropdownSelector(
            label = "AI model for summaries, insights & chat",
            selected = settings.chatProvider,
            options = ProviderId.chatProviders,
            optionLabel = { it.displayName + if (it.keyRequired && it !in keys) " (no key)" else "" },
            onSelect = { id -> vm.update { it.copy(chatProvider = id) } },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Model: ${settings.configFor(settings.chatProvider, null).chatModel.ifBlank { "not set" }}",
            style = MaterialTheme.typography.bodySmall,
        )
        DropdownSelector(
            label = "Transcription (speech-to-text)",
            selected = settings.sttProvider,
            options = ProviderId.sttProviders,
            optionLabel = { it.displayName + if (it.keyRequired && it !in keys) " (no key)" else "" },
            onSelect = { id -> vm.update { it.copy(sttProvider = id) } },
            modifier = Modifier.fillMaxWidth(),
        )
        val sttConfig = settings.configFor(settings.sttProvider, null)
        val diarizes = SttClients.supportsDiarization(sttConfig.id, sttConfig.sttModel)
        Text(
            "Model: ${sttConfig.sttModel.ifBlank { "provider default" }} · " +
                if (diarizes) "detects speakers" else "no speaker detection (AI will separate speakers from the text)",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ProviderCard(
    id: ProviderId,
    settings: AppSettings,
    hasKey: Boolean,
    status: ProviderStatus?,
    expanded: Boolean,
    onToggle: () -> Unit,
    vm: SettingsViewModel,
) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(id.displayName, style = MaterialTheme.typography.titleSmall)
                val uses = listOfNotNull(
                    "chat".takeIf { id.supportsChat },
                    "transcription".takeIf { id.supportsStt },
                ).joinToString(" & ")
                val active = listOfNotNull(
                    "active for chat".takeIf { settings.chatProvider == id },
                    "active for transcription".takeIf { settings.sttProvider == id },
                )
                Text(
                    (listOf(uses) + active).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (hasKey) {
                Icon(Icons.Filled.CheckCircle, contentDescription = "Key saved", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
        }
        AnimatedVisibility(expanded) {
            var draft by remember(id) { mutableStateOf(vm.draftFor(id)) }
            var showKey by remember { mutableStateOf(false) }
            var modelMenu by remember { mutableStateOf(false) }
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft.apiKey,
                    onValueChange = { draft = draft.copy(apiKey = it) },
                    label = { Text(if (id.keyRequired) "API key" else "API key (optional)") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = "Show key")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.baseUrl,
                    onValueChange = { draft = draft.copy(baseUrl = it) },
                    label = { Text("Base URL") },
                    placeholder = { Text(id.defaultBaseUrl) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (id.supportsChat) {
                    Column {
                        OutlinedTextField(
                            value = draft.chatModel,
                            onValueChange = { draft = draft.copy(chatModel = it) },
                            label = { Text("Chat model") },
                            placeholder = { Text(id.defaultChatModel.ifBlank { "model name" }) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val models = (status as? ProviderStatus.Models)?.models.orEmpty()
                        DropdownMenu(expanded = modelMenu && models.isNotEmpty(), onDismissRequest = { modelMenu = false }) {
                            models.take(200).forEach { model ->
                                DropdownMenuItem(text = { Text(model) }, onClick = {
                                    draft = draft.copy(chatModel = model)
                                    modelMenu = false
                                })
                            }
                        }
                    }
                }
                if (id.supportsStt) {
                    OutlinedTextField(
                        value = draft.sttModel,
                        onValueChange = { draft = draft.copy(sttModel = it) },
                        label = { Text("Transcription model") },
                        placeholder = { Text(id.defaultSttModel.ifBlank { "provider default" }) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (id == ProviderId.OLLAMA_CLOUD) {
                    Text(
                        "Uses Ollama's cloud models at ollama.com with your API key. You can also point the base URL at any remote Ollama server.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (id == ProviderId.OPENAI) {
                    Text(
                        "gpt-4o-transcribe-diarize labels speakers; whisper-1 and gpt-4o-transcribe do not.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilledTonalButton(onClick = { vm.save(id, draft) }) { Text("Save") }
                    OutlinedButton(onClick = { vm.test(id, draft) }) { Text("Test") }
                    if (id.supportsChat) {
                        OutlinedButton(onClick = {
                            modelMenu = true
                            vm.fetchModels(id, draft)
                        }) { Text("List models") }
                    }
                    if (id.keyUrl.isNotBlank()) {
                        TextButton(onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(id.keyUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }) { Text("Get a key") }
                    }
                }
                when (status) {
                    ProviderStatus.Working -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    is ProviderStatus.Ok -> Text(status.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    is ProviderStatus.Failed -> Text(status.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    is ProviderStatus.Models -> Text(
                        "${status.models.size} models found. Tap \"List models\" to pick one.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    null -> Unit
                }
            }
        }
    }
}

@Composable
private fun ProcessingSettings(settings: AppSettings, vm: SettingsViewModel) {
    SectionCard("Meeting processing") {
        var chunkSeconds by remember(settings.chunkSeconds) { mutableIntStateOf(settings.chunkSeconds) }
        Text("Live transcript update interval: ${chunkSeconds}s", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Shorter parts update the live transcript sooner; longer parts give the transcription service more context.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = chunkSeconds.toFloat(),
            onValueChange = { v -> chunkSeconds = ((v / 5).roundToInt() * 5).coerceIn(10, 120) },
            onValueChangeFinished = { vm.update { it.copy(chunkSeconds = chunkSeconds) } },
            valueRange = 10f..120f,
        )
        var transcriptionLanguage by remember { mutableStateOf(settings.transcriptionLanguage) }
        OutlinedTextField(
            value = transcriptionLanguage,
            onValueChange = { value ->
                transcriptionLanguage = value
                vm.update { it.copy(transcriptionLanguage = value.trim()) }
            },
            label = { Text("Spoken language code (blank = auto-detect)") },
            placeholder = { Text("e.g. en, de, hi") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        var outputLanguage by remember { mutableStateOf(settings.outputLanguage) }
        OutlinedTextField(
            value = outputLanguage,
            onValueChange = { value ->
                outputLanguage = value
                vm.update { it.copy(outputLanguage = value.trim()) }
            },
            label = { Text("Language for AI notes (blank = same as meeting)") },
            placeholder = { Text("e.g. English") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        ToggleRow(
            "Detect speakers over the whole meeting when it ends",
            "Re-transcribes the full recording with a speaker-aware model for consistent speakers.",
            settings.finalDiarizationPass,
        ) { v -> vm.update { it.copy(finalDiarizationPass = v) } }
        ToggleRow(
            "Separate speakers with AI",
            "When the transcription service can't tell voices apart, the AI model splits the transcript by speaker.",
            settings.llmSpeakerSeparation,
        ) { v -> vm.update { it.copy(llmSpeakerSeparation = v) } }
        ToggleRow(
            "Suggest real names for unknown speakers",
            "Uses introductions and names mentioned in the meeting. You confirm each suggestion.",
            settings.suggestSpeakerNames,
        ) { v -> vm.update { it.copy(suggestSpeakerNames = v) } }
        ToggleRow(
            "Generate insights automatically",
            "Summary, action items, highlights, gaps and questions as soon as a meeting ends.",
            settings.autoAnalyze,
        ) { v -> vm.update { it.copy(autoAnalyze = v) } }
    }
}

@Composable
private fun ToggleRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
