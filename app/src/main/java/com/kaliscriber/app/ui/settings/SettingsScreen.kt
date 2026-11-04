@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.kaliscriber.app.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.ListItem
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaliscriber.app.appViewModel
import com.kaliscriber.app.data.ExportSecret
import com.kaliscriber.app.export.ExportTarget
import com.kaliscriber.app.speech.ModelCatalog
import com.kaliscriber.app.speech.ModelState
import com.kaliscriber.app.ui.common.DropdownSelector
import com.kaliscriber.app.ui.common.SectionCard
import com.kaliscriber.core.ai.ApiKind
import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.ai.Microphone
import com.kaliscriber.core.ai.ProviderId
import com.kaliscriber.core.ai.TtsEngine
import com.kaliscriber.core.ai.stt.SttClients
import com.kaliscriber.core.ai.usage.ModelPrice
import com.kaliscriber.core.ai.usage.TokenUsage
import com.kaliscriber.core.ai.usage.UsageLedger
import kotlin.math.roundToInt

/** The pages Settings is split into; [keywords] let the search box find what is on each. */
enum class SettingsPage(val title: String, val summary: String, val keywords: List<String>) {
    AI(
        "AI services", "Models, API keys, the quick model, hiding details, usage and cost",
        listOf("redact", "hide", "privacy", "mask", "personal details", "phone numbers", "provider", "API key", "model", "OpenAI", "Claude", "Anthropic", "Gemini", "Ollama", "Command Code", "DeepSeek", "Groq", "ElevenLabs", "quick model", "usage", "cost", "price", "tokens", "voice", "read aloud", "transcription", "thinking effort", "zero data retention"),
    ),
    SPEECH(
        "Speech & voices", "On-device transcription, speaker detection and voice memory",
        listOf("Parakeet", "Whisper", "SenseVoice", "speech model", "download", "speaker detection", "voice memory", "saved voices", "voiceprint"),
    ),
    RECORDING(
        "Recording & notes", "Microphone, audio clean-up, glossary and what happens after a meeting",
        listOf("your name", "for you", "promised", "microphone", "Bluetooth", "headset", "noise", "audio clean-up", "glossary", "names", "terms", "summary", "language", "headphone button", "bookmark", "speaker names", "running notes", "calendar", "auto-record", "record automatically", "scheduled meetings", "Google Calendar"),
    ),
    FORMATS(
        "Summary formats", "Your own formats with the sections you want",
        listOf("format", "template", "sections", "custom summary", "meeting type", "1:1", "stand-up", "sales call", "interview", "lecture"),
    ),
    SEARCH("Smart search", "Find passages by meaning when asking across meetings", listOf("semantic", "meaning", "Ask all meetings", "search model")),
    REMINDERS("Reminders", "Daily action items and the weekly recap", listOf("notification", "daily", "tasks", "due", "weekly recap", "Friday")),
    EXPORT(
        "Export destinations", "Email, Google Docs, Notion, AFFiNE, Baikal/CalDAV and a Markdown folder",
        listOf("email", "Google Docs", "Drive", "Notion", "AFFiNE", "Baikal", "CalDAV", "calendar", "tasks", "Markdown", "Obsidian", "folder", "sync", "vault"),
    ),
    APPEARANCE("Appearance", "Light or dark, and wallpaper colours", listOf("theme", "dark mode", "light mode", "night", "colours", "colors", "Material You", "wallpaper")),
    DATA("Data & storage", "Space used, keeping recordings, backup and restore", listOf("storage", "space", "delete recordings", "keep recordings", "backup", "restore", "password", "automatic backup", "daily backup", "Google Drive", "Nextcloud")),
    PROBLEMS("Problem report", "Send or save a log of what the app did, to find out what went wrong", listOf("bug", "crash", "log", "diagnostics", "help", "support", "not working", "error")),
    ;

    /** What on this page matches [query], or null if nothing does. */
    fun match(query: String): String? {
        val q = query.trim()
        if (q.isEmpty()) return summary
        if (title.contains(q, true) || summary.contains(q, true)) return summary
        return keywords.firstOrNull { it.contains(q, true) || q.contains(it, true) }?.let { "Includes: ${it.replaceFirstChar(Char::uppercase)}" }
    }
}

private fun SettingsPage.icon(): ImageVector = when (this) {
    SettingsPage.AI -> Icons.Filled.AutoAwesome
    SettingsPage.SPEECH -> Icons.Filled.RecordVoiceOver
    SettingsPage.RECORDING -> Icons.Filled.Mic
    SettingsPage.FORMATS -> Icons.Filled.ViewAgenda
    SettingsPage.SEARCH -> Icons.Filled.Search
    SettingsPage.REMINDERS -> Icons.Filled.Notifications
    SettingsPage.EXPORT -> Icons.Filled.IosShare
    SettingsPage.APPEARANCE -> Icons.Filled.Palette
    SettingsPage.DATA -> Icons.Filled.Storage
    SettingsPage.PROBLEMS -> Icons.Filled.BugReport
}

/** Settings: a short list of pages with a search box, or one page when [initialPage] is given. */
@Composable
fun SettingsScreen(onBack: () -> Unit, initialPage: SettingsPage? = null) {
    val vm = appViewModel { SettingsViewModel(it) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val keys by vm.configuredKeys.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val modelStates by vm.modelStates.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var page by rememberSaveable { mutableStateOf(initialPage) }
    var query by rememberSaveable { mutableStateOf("") }
    // Opened on a page directly (from an error or setup), Back leaves Settings.
    val pageIsRoot = initialPage != null
    BackHandler(enabled = page != null && !pageIsRoot) { page = null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(page?.title ?: "Settings") },
                navigationIcon = {
                    IconButton(onClick = { if (page != null && !pageIsRoot) page = null else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (page) {
                null -> {
                    item {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Search settings") },
                            leadingIcon = { Icon(Icons.Filled.Search, null) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    val found = SettingsPage.entries.mapNotNull { p -> p.match(query)?.let { p to it } }
                    if (found.isEmpty()) {
                        item { Text("Nothing in Settings matches \"${query.trim()}\".", style = MaterialTheme.typography.bodyMedium) }
                    }
                    items(found, key = { it.first.name }) { (p, line) ->
                        Card(Modifier.fillMaxWidth().clickable { page = p }) {
                            ListItem(
                                headlineContent = { Text(p.title) },
                                supportingContent = { Text(line) },
                                leadingContent = { Icon(p.icon(), null, tint = MaterialTheme.colorScheme.primary) },
                                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                            )
                        }
                    }
                    item {
                        Text(
                            "Privacy: recordings, transcripts and notes stay on this device. Audio and text are sent only to the AI services you choose, using your keys.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
                SettingsPage.AI -> {
                    item { ActiveServices(settings, keys, vm) }
                    item { RedactionSection(settings, vm) }
                    item { UsageSection(settings, vm) }
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
                    items(ProviderId.entries.filter { it != ProviderId.LOCAL }, key = { it.name }) { id ->
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
                }
                SettingsPage.SPEECH -> {
                    item { LocalSpeechSection(settings, modelStates, vm) }
                    item { VoiceMemorySection(settings, modelStates, vm) }
                }
                SettingsPage.RECORDING -> {
                    item { AutoRecordSection(settings, vm) }
                    item { ProcessingSettings(settings, vm) }
                    item { YourNameSection(settings, vm) }
                    item { GlossarySection(settings, vm) }
                }
                SettingsPage.FORMATS -> item { FormatsSection(settings, vm) }
                SettingsPage.SEARCH -> item { SmartSearchSection(modelStates, vm) }
                SettingsPage.REMINDERS -> item { ReminderSection(settings, vm) }
                SettingsPage.EXPORT -> {
                    item { ExportSection(settings, vm) }
                    item { MarkdownFolderSection(settings, vm) }
                }
                SettingsPage.APPEARANCE -> item { AppearanceSection(settings, vm) }
                SettingsPage.DATA -> {
                    item { StorageSection(settings, vm) }
                    item { BackupSection(vm) }
                    item { AutoBackupSection(settings, vm) }
                }
                SettingsPage.PROBLEMS -> item { ProblemReportSection(vm) }
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
        FastModelSetting(settings, keys, vm)
        DropdownSelector(
            label = "Transcription (speech-to-text)",
            selected = settings.sttProvider,
            options = ProviderId.sttProviders,
            optionLabel = { it.displayName + if (it.keyRequired && it !in keys) " (no key)" else "" },
            onSelect = { id -> vm.update { it.copy(sttProvider = id) } },
            modifier = Modifier.fillMaxWidth(),
        )
        val sttConfig = settings.configFor(settings.sttProvider, null)
        if (settings.sttProvider == ProviderId.LOCAL) {
            val model = ModelCatalog.asrModels.firstOrNull { it.id == settings.localAsrModel }
            Text(
                "Audio never leaves your phone. Model: ${model?.name ?: "choose one below"}" +
                    if (settings.localDiarization) " · speakers detected on-device when a meeting ends" else "",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            val diarizes = SttClients.supportsDiarization(sttConfig.id, sttConfig.sttModel)
            Text(
                "Model: ${sttConfig.sttModel.ifBlank { "provider default" }} · " +
                    if (diarizes) "detects speakers" else "no speaker detection (AI will separate speakers from the text)",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        DropdownSelector(
            label = "Voice for reading aloud & audio briefings",
            selected = settings.ttsEngine,
            options = TtsEngine.entries.toList(),
            optionLabel = { it.displayName + if (it == TtsEngine.ELEVENLABS && ProviderId.ELEVENLABS !in keys) " (no key)" else "" },
            onSelect = { engine -> vm.update { it.copy(ttsEngine = engine) } },
            modifier = Modifier.fillMaxWidth(),
        )
        if (settings.ttsEngine == TtsEngine.DEVICE) {
            val engines = remember { vm.ttsEngines() }
            val names = engines.toMap()
            DropdownSelector(
                label = "Phone voice engine",
                selected = settings.deviceTtsEngine,
                options = listOf("") + engines.map { it.first },
                optionLabel = { pkg ->
                    when {
                        pkg.isEmpty() -> "System default"
                        else -> names[pkg] ?: "$pkg (not installed)"
                    }
                },
                onSelect = { pkg -> vm.update { it.copy(deviceTtsEngine = pkg) } },
                modifier = Modifier.fillMaxWidth(),
            )
            if (engines.isEmpty()) {
                Text(
                    "No text-to-speech engine found. Install one, such as NekoSpeak or RHVoice, or use ElevenLabs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        val voiceTest by vm.voiceTest.collectAsStateWithLifecycle()
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = vm::testVoice) { Text("Test voice") }
            voiceTest?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)) }
        }
    }
}

/** A second, quicker model for background tasks (see [AppSettings.FAST_TASKS]). */
@Composable
private fun FastModelSetting(settings: AppSettings, keys: Set<ProviderId>, vm: SettingsViewModel) {
    DropdownSelector<ProviderId?>(
        label = "Quick model for background tasks",
        selected = settings.fastChatProvider,
        options = listOf<ProviderId?>(null) + ProviderId.chatProviders,
        optionLabel = { id ->
            if (id == null) "Same as above" else id.displayName + if (id.keyRequired && id !in keys) " (no key)" else ""
        },
        onSelect = { id -> vm.update { it.copy(fastChatProvider = id) } },
        modifier = Modifier.fillMaxWidth(),
    )
    val provider = settings.fastChatProvider ?: return
    var model by remember(provider) { mutableStateOf(settings.fastChatModel) }
    OutlinedTextField(
        value = model,
        onValueChange = { value ->
            model = value
            vm.update { it.copy(fastChatModel = value) }
        },
        label = { Text("Model") },
        placeholder = { Text(settings.configFor(provider, null).chatModel) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        "Used for running notes while recording, speaker name suggestions, glossary fixes and the search words for Ask all " +
            "meetings. A small, fast model saves time and money here; summaries and answers keep the model above.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (provider.keyRequired && provider !in keys) {
        Text(
            "Add a ${provider.displayName} API key below. Until then the model above is used.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun LocalSpeechSection(settings: AppSettings, states: Map<String, ModelState>, vm: SettingsViewModel) {
    SectionCard("On-device speech") {
        Text(
            "Private, offline transcription with sherpa-onnx. Download a model once over Wi-Fi; recording then works without internet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ModelCatalog.asrModels.forEach { model ->
            val state = states[model.id] ?: ModelState.NotDownloaded
            val ready = state is ModelState.Ready
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = settings.localAsrModel == model.id,
                    onClick = { vm.selectLocalModel(model) },
                    enabled = ready,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        model.name + if (model == ModelCatalog.recommended) "  ·  recommended" else "",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text("${model.languages} · ${model.downloadMb} MB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text(model.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ModelStateLine(state)
                }
                ModelAction(state, onDownload = { vm.downloadModel(model) }, onCancel = { vm.cancelModel(model) }, onDelete = { vm.deleteModel(model) })
            }
        }
        HorizontalDivider()
        val diarizationStates = ModelCatalog.diarization.map { states[it.id] ?: ModelState.NotDownloaded }
        val diarizationReady = diarizationStates.all { it is ModelState.Ready }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Speaker detection pack", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Tells voices apart on-device when a meeting ends (${ModelCatalog.diarization.sumOf { it.downloadMb }} MB). " +
                        "Unrecognised speakers still get a random name you can change.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                diarizationStates.firstOrNull { it !is ModelState.Ready && it !is ModelState.NotDownloaded }?.let { ModelStateLine(it) }
            }
            when {
                diarizationReady -> IconButton(onClick = { ModelCatalog.diarization.forEach(vm::deleteModel) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete speaker models")
                }
                diarizationStates.any { it is ModelState.Downloading } -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else -> IconButton(onClick = vm::downloadDiarization) { Icon(Icons.Filled.Download, contentDescription = "Download speaker models") }
            }
        }
        ToggleRow(
            "Detect speakers on-device",
            if (diarizationReady) "Runs after each meeting; long meetings take a few minutes." else "Download the speaker detection pack first.",
            settings.localDiarization,
        ) { v -> vm.update { it.copy(localDiarization = v) } }
    }
}

@Composable
private fun VoiceMemorySection(settings: AppSettings, states: Map<String, ModelState>, vm: SettingsViewModel) {
    val voices by vm.voices.collectAsStateWithLifecycle()
    val ready = states[ModelCatalog.embedding.id] is ModelState.Ready
    SectionCard("Voice memory") {
        Text(
            "Name a speaker once and KaliScriber recognises their voice in later meetings. Voiceprints are computed and stored only on this phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ToggleRow(
            "Remember voices",
            if (ready) "${voices.size} saved voice${if (voices.size == 1) "" else "s"}" else "Needs the speaker detection pack above.",
            settings.rememberVoices,
        ) { v -> vm.update { it.copy(rememberVoices = v) } }
        voices.sortedBy { it.name.lowercase() }.forEach { voice ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.RecordVoiceOver, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(voice.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (voice.model != ModelCatalog.embedding.id) {
                            "Saved with the older speaker model. Name them in a meeting to save their voice again."
                        } else {
                            "Learned from ${voice.samples} meeting${if (voice.samples == 1) "" else "s"}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                IconButton(onClick = { vm.deleteVoice(voice.id) }) { Icon(Icons.Filled.Delete, contentDescription = "Forget ${voice.name}") }
            }
        }
    }
}

/** Token counts like "12.3k" or "1.2M". */
private fun tokens(n: Long): String = when {
    n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format(java.util.Locale.US, "%.1fk", n / 1_000.0)
    else -> n.toString()
}

private fun dollars(amount: Double): String =
    if (amount < 0.01) "under $0.01" else String.format(java.util.Locale.US, "$%.2f", amount)

@Composable
private fun UsageSection(settings: AppSettings, vm: SettingsViewModel) {
    val models by vm.usageThisMonth.collectAsStateWithLifecycle()
    var pricing by remember { mutableStateOf<UsageLedger.ModelTotal?>(null) }
    SectionCard("AI usage & cost") {
        if (models.isEmpty()) {
            Text("No AI requests this month yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val total = models.map { it.usage }.reduce(TokenUsage::plus)
            Text(
                "This month: ${models.sumOf { it.requests }} requests, ${tokens(total.input)} tokens in " +
                    "(${tokens(total.cachedInput)} from cache), ${tokens(total.output)} out" +
                    (vm.monthCost(settings.modelPrices)?.let { " · about ${dollars(it)}" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
            )
            models.forEach { m ->
                val price = settings.modelPrices[m.key]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.model.ifBlank { m.provider }, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${m.requests} requests · ${tokens(m.usage.input)} in · ${tokens(m.usage.output)} out" +
                                (price?.let { " · ${dollars(it.cost(m.usage))}" } ?: "") +
                                if (m.usage.estimated) " · partly estimated" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { pricing = m }) { Text(if (price == null) "Set price" else "Price") }
                }
            }
            TextButton(onClick = vm::clearUsage) { Text("Clear usage history") }
        }
        Text(
            "Token counts come from your provider; when it doesn't report them they are estimated. Enter each model's price " +
                "from your provider's pricing page to see costs.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    pricing?.let { m ->
        val current = settings.modelPrices[m.key]
        var input by remember(m.key) { mutableStateOf(current?.input?.toString().orEmpty()) }
        var cached by remember(m.key) { mutableStateOf(current?.cachedInput?.toString().orEmpty()) }
        var output by remember(m.key) { mutableStateOf(current?.output?.toString().orEmpty()) }
        AlertDialog(
            onDismissRequest = { pricing = null },
            title = { Text("Price of ${m.model}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("US dollars per million tokens.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(input, { input = it }, label = { Text("Input") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(cached, { cached = it }, label = { Text("Cached input (optional)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(output, { output = it }, label = { Text("Output") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val i = input.replace(',', '.').toDoubleOrNull()
                    val o = output.replace(',', '.').toDoubleOrNull()
                    vm.setPrice(m.key, if (i == null && o == null) null else ModelPrice(i ?: 0.0, o ?: 0.0, cached.replace(',', '.').toDoubleOrNull()))
                    pricing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { pricing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SmartSearchSection(states: Map<String, ModelState>, vm: SettingsViewModel) {
    val model = ModelCatalog.searchModel
    val state = states[model.id] ?: ModelState.NotDownloaded
    SectionCard("Smart search") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Ask all meetings also finds passages by meaning, so \"what did it cost?\" finds talk about prices and budgets. " +
                        "Runs on this phone (${model.downloadMb} MB, English).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ModelStateLine(state)
                vm.searchModelError()?.let {
                    Text("Couldn't load the model: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            ModelAction(state, onDownload = { vm.downloadModel(model) }, onCancel = { vm.cancelModel(model) }, onDelete = { vm.deleteModel(model) })
        }
    }
}

@Composable
private fun GlossarySection(settings: AppSettings, vm: SettingsViewModel) {
    var text by remember { mutableStateOf(settings.glossary) }
    val people = remember { vm.knownPeople() }
    SectionCard("Glossary") {
        Text(
            "Names, products and jargon that speech recognition gets wrong, one per line (for example \"Priya Sharma — product lead\"). " +
                "The AI spells them correctly in summaries and answers, and fixes them in the transcript after each meeting. " +
                "With an on-device Parakeet model, the recognizer also listens out for them as you record.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = text,
            onValueChange = { value ->
                text = value
                vm.update { it.copy(glossary = value) }
            },
            placeholder = { Text("Priya Sharma\nKaliScriber\nOKR") },
            minLines = 3,
            maxLines = 10,
            modifier = Modifier.fillMaxWidth(),
        )
        val missing = people.filter { name -> text.lines().none { it.trim().startsWith(name, ignoreCase = true) } }
        if (missing.isNotEmpty()) {
            TextButton(onClick = {
                text = (text.trimEnd().lines().filter { it.isNotBlank() } + missing).joinToString("\n")
                vm.update { it.copy(glossary = text) }
            }) { Text("Add ${missing.size} people from your meetings") }
        }
    }
}

@Composable
private fun ReminderSection(settings: AppSettings, vm: SettingsViewModel) {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.update { it.copy(taskReminders = granted) }
    }
    val recapPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.update { it.copy(weeklyRecap = granted) }
    }
    SectionCard("Reminders") {
        Text(
            "A daily notification listing action items that are overdue or due that day. Find all of them under Tasks on the home screen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ToggleRow("Daily reminder", "Only shown when something is due", settings.taskReminders) { on ->
            val needsPermission = on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            if (needsPermission) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.update { it.copy(taskReminders = on) }
        }
        if (settings.taskReminders) {
            DropdownSelector(
                label = "Remind me at",
                selected = settings.reminderHour,
                options = (5..21).toList(),
                optionLabel = { hour -> String.format(java.util.Locale.ROOT, "%02d:00", hour) },
                onSelect = { hour -> vm.update { it.copy(reminderHour = hour) } },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        ToggleRow(
            "Weekly recap on Fridays",
            "At 4 pm the AI recaps the week's meetings: decisions, open items and what to follow up. Also under the calendar icon on the home screen.",
            settings.weeklyRecap,
        ) { on ->
            val needsPermission = on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            if (needsPermission) recapPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.update { it.copy(weeklyRecap = on) }
        }
    }
}

@Composable
private fun ExportSection(settings: AppSettings, vm: SettingsViewModel) {
    val secrets by vm.exportSecrets.collectAsStateWithLifecycle()
    val status by vm.exportStatus.collectAsStateWithLifecycle()
    val e = settings.export
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    SectionCard("Export destinations") {
        ToggleRow("Include the full transcript", "Otherwise exports contain the summary, decisions, action items and questions.", e.includeTranscript) { v ->
            vm.updateExport { it.copy(includeTranscript = v) }
        }
        ExportCard("Email", "Default recipients", open == "email", { open = if (open == "email") null else "email" }) {
            SettingField("Recipients (comma separated)", e.emailTo, KeyboardType.Email) { v -> vm.updateExport { it.copy(emailTo = v) } }
        }
        ExportCard(
            "Notion",
            if (ExportSecret.NOTION_TOKEN in secrets && e.notionParent.isNotBlank()) "Ready" else "Not set up",
            open == "notion", { open = if (open == "notion") null else "notion" },
        ) {
            Text(
                "Create an internal integration at notion.so/profile/integrations, copy its secret, then open the parent page in Notion and add the integration under ••• → Connections.",
                style = MaterialTheme.typography.bodySmall,
            )
            SecretField("Integration secret", ExportSecret.NOTION_TOKEN in secrets) { vm.saveExportSecret(ExportSecret.NOTION_TOKEN, it) }
            SettingField("Parent page link or id", e.notionParent, KeyboardType.Uri) { v -> vm.updateExport { it.copy(notionParent = v) } }
            TestRow(status[ExportTarget.NOTION]) { vm.testExport(ExportTarget.NOTION) }
        }
        ExportCard(
            "AFFiNE",
            if (ExportSecret.AFFINE_TOKEN in secrets && e.affineWorkspaceId.isNotBlank()) "Ready" else "Not set up",
            open == "affine", { open = if (open == "affine") null else "affine" },
        ) {
            Text(
                "In AFFiNE open workspace settings → Integrations → MCP Server, create a read-write token, and copy the workspace id from the address bar (…/workspace/<id>/…). " +
                    "If your server doesn't allow creating docs yet, KaliScriber copies the notes and opens AFFiNE so you can paste them.",
                style = MaterialTheme.typography.bodySmall,
            )
            SettingField("Server", e.affineServer, KeyboardType.Uri) { v -> vm.updateExport { it.copy(affineServer = v) } }
            SettingField("Workspace id", e.affineWorkspaceId) { v -> vm.updateExport { it.copy(affineWorkspaceId = v) } }
            SecretField("MCP token", ExportSecret.AFFINE_TOKEN in secrets) { vm.saveExportSecret(ExportSecret.AFFINE_TOKEN, it) }
            TestRow(status[ExportTarget.AFFINE]) { vm.testExport(ExportTarget.AFFINE) }
        }
        ExportCard(
            "Calendar & tasks (Baikal / CalDAV)",
            if (e.caldavUrl.isNotBlank() && ExportSecret.CALDAV_PASSWORD in secrets) "Ready" else "Not set up",
            open == "caldav", { open = if (open == "caldav") null else "caldav" },
        ) {
            Text(
                "Adds the meeting as an event and each action item as a task. For Baikal use https://<server>/dav.php/calendars/<user>/<calendar>/. Re-exporting updates the same items.",
                style = MaterialTheme.typography.bodySmall,
            )
            SettingField("Calendar URL", e.caldavUrl, KeyboardType.Uri) { v -> vm.updateExport { it.copy(caldavUrl = v) } }
            SettingField("Tasks calendar URL (optional)", e.caldavTasksUrl, KeyboardType.Uri) { v -> vm.updateExport { it.copy(caldavTasksUrl = v) } }
            SettingField("Username", e.caldavUser) { v -> vm.updateExport { it.copy(caldavUser = v) } }
            SecretField("Password", ExportSecret.CALDAV_PASSWORD in secrets) { vm.saveExportSecret(ExportSecret.CALDAV_PASSWORD, it) }
            ToggleRow("Add the meeting as an event", "", e.caldavEvent) { v -> vm.updateExport { it.copy(caldavEvent = v) } }
            ToggleRow("Add action items as tasks", "", e.caldavTasks) { v -> vm.updateExport { it.copy(caldavTasks = v) } }
            if (e.caldavTasks) TaskSyncRow(e.caldavTaskSync, vm)
            ToggleRow(
                "Use this calendar for meetings",
                "Recordings take the title and invitees of the event happening now, and Home shows what to prepare for the next one.",
                e.calendarLookup,
            ) { v -> vm.updateExport { it.copy(calendarLookup = v) } }
            TestRow(status[ExportTarget.CALDAV]) { vm.testExport(ExportTarget.CALDAV) }
        }
        ExportCard(
            "Google Docs",
            if (e.googleDirectUpload) "Direct upload" else "Opens a Word file in Google Docs",
            open == "google", { open = if (open == "google") null else "google" },
        ) {
            Text(
                "By default the notes open in the Google Docs app as a Word file; tap Save as Google Docs there. " +
                    "For one-tap upload, create an Android OAuth client in a Google Cloud project with the Drive API enabled, using package com.kaliscriber.app and this app's SHA-1 below.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Signing SHA-1: ${vm.signingSha1 ?: "unknown"}", style = MaterialTheme.typography.labelSmall)
            ToggleRow("Upload directly with my Google account", "", e.googleDirectUpload) { v -> vm.updateExport { it.copy(googleDirectUpload = v) } }
        }
    }
}

@Composable
private fun ExportCard(title: String, subtitle: String, expanded: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
        }
        HorizontalDivider()
    }
}

/** A text setting that saves as you type. */
@Composable
private fun SettingField(label: String, value: String, keyboardType: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    var text by remember { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(it.trim())
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A password-style field; the saved value is never shown, only replaced. */
@Composable
private fun SecretField(label: String, saved: Boolean, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(label) },
            placeholder = { Text(if (saved) "Saved · type to replace" else "Not set") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = {
            onSave(text)
            text = ""
        }, enabled = text.isNotBlank()) { Text("Save") }
    }
}

@Composable
private fun TestRow(status: ProviderStatus?, onTest: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onTest) { Text("Test") }
        Spacer(Modifier.width(12.dp))
        when (status) {
            ProviderStatus.Working -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            is ProviderStatus.Ok -> Text(status.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            is ProviderStatus.Failed -> Text(status.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            else -> Unit
        }
    }
}

@Composable
private fun ModelStateLine(state: ModelState) {
    when (state) {
        is ModelState.Downloading -> Column {
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            Text("Downloading ${(state.progress * 100).toInt()}% · keep the app open", style = MaterialTheme.typography.labelSmall)
        }
        is ModelState.Ready -> Text("Downloaded · ${state.bytes / 1_000_000} MB on device", style = MaterialTheme.typography.labelSmall)
        is ModelState.Failed -> Text(state.message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        ModelState.NotDownloaded -> Unit
    }
}

@Composable
private fun ModelAction(state: ModelState, onDownload: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit) {
    when (state) {
        is ModelState.Downloading -> IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = "Cancel download") }
        is ModelState.Ready -> IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete model") }
        else -> IconButton(onClick = onDownload) { Icon(Icons.Filled.Download, contentDescription = "Download model") }
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
            var voiceMenu by remember { mutableStateOf(false) }
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
                if (id.supportsChat && (id.kind == ApiKind.OPENAI_COMPATIBLE || id.kind == ApiKind.COMMAND_CODE)) {
                    DropdownSelector(
                        label = "Thinking effort (reasoning models)",
                        selected = draft.reasoningEffort,
                        options = listOf("", "low", "medium", "high", "max"),
                        optionLabel = { effort ->
                            when (effort) {
                                "" -> "Model default"
                                "low" -> "Low · fastest, good for summaries"
                                else -> effort.replaceFirstChar { it.uppercase() }
                            }
                        },
                        onSelect = { draft = draft.copy(reasoningEffort = it) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (id.supportsZdr) {
                    ToggleRow(
                        "Zero data retention (ZDR)",
                        "Sends x-cmd-zdr so Command Code only routes to upstreams that keep nothing. Models without a ZDR route fail instead of falling back.",
                        draft.zeroDataRetention,
                    ) { draft = draft.copy(zeroDataRetention = it) }
                }
                if (id.supportsTts) {
                    Column {
                        OutlinedTextField(
                            value = draft.voiceId,
                            onValueChange = { draft = draft.copy(voiceId = it) },
                            label = { Text("Voice ID (reading aloud & briefings)") },
                            placeholder = { Text(ProviderId.DEFAULT_ELEVENLABS_VOICE) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val voices = (status as? ProviderStatus.Voices)?.voices.orEmpty()
                        DropdownMenu(expanded = voiceMenu && voices.isNotEmpty(), onDismissRequest = { voiceMenu = false }) {
                            voices.forEach { voice ->
                                DropdownMenuItem(text = { Text(voice.name) }, onClick = {
                                    draft = draft.copy(voiceId = voice.id)
                                    voiceMenu = false
                                })
                            }
                        }
                    }
                    OutlinedTextField(
                        value = draft.ttsModel,
                        onValueChange = { draft = draft.copy(ttsModel = it) },
                        label = { Text("Text-to-speech model") },
                        placeholder = { Text(ProviderId.DEFAULT_ELEVENLABS_TTS_MODEL) },
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
                    if (id.supportsTts) {
                        OutlinedButton(onClick = {
                            voiceMenu = true
                            vm.listVoices(id, draft)
                        }) { Text("List voices") }
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
                    is ProviderStatus.Voices -> Text(
                        "${status.voices.size} voices found. Tap \"List voices\" to pick one.",
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
        ToggleRow(
            "Clean up audio while recording",
            "Android's noise suppression and automatic volume, which help with a noisy room and quiet voices. " +
                "Turn off if transcripts get worse on your phone.",
            settings.audioCleanup,
        ) { v -> vm.update { it.copy(audioCleanup = v) } }
        ToggleRow(
            "Bookmark with the headphone button",
            "While recording, pressing play/pause on wired or Bluetooth headphones marks the moment. The phone buzzes to confirm.",
            settings.headsetBookmarks,
        ) { v -> vm.update { it.copy(headsetBookmarks = v) } }
        DropdownSelector(
            label = "Microphone",
            options = Microphone.entries,
            selected = settings.microphone,
            optionLabel = { it.label },
            onSelect = { mic -> vm.update { it.copy(microphone = mic) } },
        )
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
            "Write running notes during the meeting",
            "Every ten minutes the AI notes what was said, so the summary of a long meeting is ready sooner",
            settings.liveNotes,
        ) { v -> vm.update { it.copy(liveNotes = v) } }
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
internal fun ToggleRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    // One switch for screen readers: the row toggles, reading out the title and description.
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}
