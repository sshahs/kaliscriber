@file:OptIn(ExperimentalLayoutApi::class)

package com.kaliscriber.app.ui.settings

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kaliscriber.app.ui.common.SectionCard
import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.ai.Redaction
import com.kaliscriber.core.ai.RedactionSettings
import kotlinx.coroutines.launch

/** The user's name, so the notes can say what they were asked and promised. */
@Composable
internal fun YourNameSection(settings: AppSettings, vm: SettingsViewModel) {
    var text by remember { mutableStateOf(settings.yourName) }
    SectionCard("Your name") {
        Text(
            "With your name, each meeting's notes get a \"For you\" part: what you were asked, what you promised and questions still waiting on you. " +
                "Add other names people call you after a comma.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = text,
            onValueChange = { value ->
                text = value
                vm.update { it.copy(yourName = value.trim()) }
            },
            label = { Text("Your name") },
            placeholder = { Text("Sam, Samantha Lee") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Masking private details before text goes to the AI. */
@Composable
internal fun RedactionSection(settings: AppSettings, vm: SettingsViewModel) {
    val r = settings.redaction
    fun set(transform: (RedactionSettings) -> RedactionSettings) = vm.update { it.copy(redaction = transform(it.redaction)) }
    var names by remember { mutableStateOf(r.names) }
    SectionCard("Hide details from the AI") {
        ToggleRow(
            "Hide private details",
            "Before any text is sent to the AI, the details below are swapped for placeholders like [NAME_1], and put back in what comes back, " +
                "so your notes still show them. Recordings sent to a cloud transcription service, and text read aloud by ElevenLabs, are not changed.",
            r.enabled,
        ) { on -> set { it.copy(enabled = on) } }
        if (!r.enabled) return@SectionCard
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CheckRow("Email addresses", r.emails) { v -> set { it.copy(emails = v) } }
            CheckRow("Phone numbers", r.phones) { v -> set { it.copy(phones = v) } }
            CheckRow("Card numbers", r.cards) { v -> set { it.copy(cards = v) } }
        }
        OutlinedTextField(
            value = names,
            onValueChange = { value ->
                names = value
                set { it.copy(names = value) }
            },
            label = { Text("Names and words to hide") },
            placeholder = { Text("Priya Sharma, Priya\nAcme Corp\nProject Falcon") },
            supportingText = { Text("One per line. Ways of saying the same name go on one line, separated by commas.") },
            minLines = 3,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth(),
        )
        var sample by remember { mutableStateOf("") }
        OutlinedTextField(
            value = sample,
            onValueChange = { sample = it },
            label = { Text("Try it") },
            placeholder = { Text("Call Priya on 07700 900123") },
            modifier = Modifier.fillMaxWidth(),
        )
        if (sample.isNotBlank()) {
            Text("The AI sees: " + Redaction(r).redact(sample), style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            "Speaker names in your notes come back as you named them. The AI does a little less well with placeholders: it can't tell who a person is from their name.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 4.dp, end = 12.dp))
    }
}

/** Saving or sending a log of what the app did, to find out what went wrong. */
@Composable
internal fun ProblemReportSection(vm: SettingsViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var size by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { size = vm.problemLogBytes() }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch { message = vm.saveProblemReport(uri) }
    }
    SectionCard("Problem report") {
        Text(
            "The app keeps a log on this phone of what it did: recordings starting and stopping, automatic recording, processing steps, sync, backups and errors. " +
                "A report adds the phone model, Android version, permissions and which settings are on. " +
                "It has no API keys, passwords, email addresses or meeting content, though it can name meetings. Nothing is sent unless you send it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = {
                scope.launch {
                    message = try {
                        context.startActivity(vm.problemReportIntent())
                        null
                    } catch (e: ActivityNotFoundException) {
                        "No app on this phone can send it. Save it instead."
                    } catch (e: Exception) {
                        "Couldn't make the report: ${e.message}"
                    }
                }
            }) { Text("Send report") }
            OutlinedButton(onClick = { save.launch(vm.problemReportName()) }) { Text("Save report") }
        }
        Text(
            "Log: ${if (size < 1024) "$size bytes" else "${size / 1024} KB"} (the newest few days, older lines are dropped).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = {
            vm.clearProblemLog()
            size = 0
            message = "Log cleared."
        }) { Text("Clear the log") }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}
