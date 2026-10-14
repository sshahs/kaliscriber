@file:OptIn(ExperimentalMaterial3Api::class)

package com.kaliscriber.app.ui.notes

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaliscriber.app.appViewModel
import com.kaliscriber.app.ui.common.MarkdownText
import com.kaliscriber.app.ui.common.shareText
import com.kaliscriber.core.ai.NoteAction

@Composable
fun NoteScreen(noteId: String, onBack: () -> Unit) {
    val vm = appViewModel(key = "note-$noteId") { NoteViewModel(it, noteId) }
    val title by vm.title.collectAsStateWithLifecycle()
    val content by vm.content.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()
    val dictating by vm.dictating.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var aiMenu by remember { mutableStateOf(false) }
    var customPrompt by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.toggleDictation()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (dictating) "Listening…" else "Note") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = {
                        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        if (granted || dictating) vm.toggleDictation() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }) {
                        Icon(
                            if (dictating) Icons.Filled.Stop else Icons.Filled.Mic,
                            contentDescription = if (dictating) "Stop dictation" else "Dictate",
                            tint = if (dictating) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(onClick = { aiMenu = true }, enabled = busy == null) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = "AI")
                    }
                    DropdownMenu(expanded = aiMenu, onDismissRequest = { aiMenu = false }) {
                        NoteAction.entries.forEach { action ->
                            DropdownMenuItem(text = { Text(action.label) }, onClick = {
                                aiMenu = false
                                vm.runAction(action)
                            })
                        }
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Custom instruction…") }, onClick = {
                            aiMenu = false
                            customPrompt = ""
                        })
                    }
                    IconButton(onClick = { shareText(context, title.ifBlank { "Note" }, "# ${title}\n\n$content") }) {
                        Icon(Icons.Filled.Share, contentDescription = "Share")
                    }
                    IconButton(onClick = { vm.delete(onBack) }) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            busy?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            }
            val transparent = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            )
            TextField(
                value = title,
                onValueChange = vm::setTitle,
                placeholder = { Text("Title") },
                textStyle = MaterialTheme.typography.headlineSmall,
                singleLine = true,
                colors = transparent,
                modifier = Modifier.fillMaxWidth(),
            )
            TextField(
                value = content,
                onValueChange = vm::setContent,
                placeholder = { Text("Type, or tap the mic to dictate. Use ✨ to tidy up, summarise or pull out action items.") },
                textStyle = MaterialTheme.typography.bodyLarge,
                colors = transparent,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }

    result?.let { r ->
        AlertDialog(
            onDismissRequest = vm::dismissResult,
            title = { Text(r.label) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) { MarkdownText(r.text) }
            },
            confirmButton = {
                TextButton(onClick = { vm.applyResult(replace = true) }) { Text("Replace note") }
            },
            dismissButton = {
                Column {
                    TextButton(onClick = { vm.applyResult(replace = false) }) { Text("Append") }
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(r.text))
                        vm.dismissResult()
                    }) { Text("Copy") }
                }
            },
        )
    }

    customPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = { customPrompt = null },
            title = { Text("Ask AI about this note") },
            text = {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { customPrompt = it },
                    placeholder = { Text("e.g. Turn this into an email to the team") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.runCustom(prompt)
                    customPrompt = null
                }, enabled = prompt.isNotBlank()) { Text("Run") }
            },
            dismissButton = { TextButton(onClick = { customPrompt = null }) { Text("Cancel") } },
        )
    }
}
