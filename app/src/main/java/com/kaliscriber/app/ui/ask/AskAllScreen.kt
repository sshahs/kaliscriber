@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.kaliscriber.app.ui.ask

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kaliscriber.app.AppContainer
import com.kaliscriber.app.appViewModel
import com.kaliscriber.app.ui.common.MarkdownText
import com.kaliscriber.core.model.AskMessage
import com.kaliscriber.core.model.AskSource
import com.kaliscriber.core.model.ChatRole
import com.kaliscriber.core.transcript.TranscriptFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AskAllViewModel(private val container: AppContainer) : ViewModel() {
    val messages: StateFlow<List<AskMessage>> = container.libraryAsk.messages

    private val _thinking = MutableStateFlow(false)
    val thinking: StateFlow<Boolean> = _thinking.asStateFlow()

    fun ask(question: String) {
        if (question.isBlank() || _thinking.value) return
        _thinking.value = true
        // The app scope keeps the request going if the user leaves the screen.
        container.scope.launch {
            try {
                container.libraryAsk.ask(question)
            } finally {
                _thinking.value = false
            }
        }
    }

    fun clear() = viewModelScope.launch { container.libraryAsk.clear() }
}

/** Ask the AI about all recorded meetings at once; answers link to the meetings they come from. */
@Composable
fun AskAllScreen(
    initialQuestion: String?,
    onBack: () -> Unit,
    onOpenSource: (AskSource) -> Unit,
) {
    val vm = appViewModel { AskAllViewModel(it) }
    val messages by vm.messages.collectAsStateWithLifecycle()
    val thinking by vm.thinking.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var askedInitial by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(initialQuestion) {
        if (!initialQuestion.isNullOrBlank() && !askedInitial) {
            askedInitial = true
            vm.ask(initialQuestion)
        }
    }
    LaunchedEffect(messages.size, thinking) {
        val count = messages.size + if (thinking) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }
    fun send(text: String) {
        vm.ask(text)
        input = ""
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ask all meetings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (messages.isNotEmpty()) {
                        IconButton(onClick = { vm.clear() }, enabled = !thinking) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear conversation")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            if (messages.isEmpty() && !thinking) {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Ask about everything you've recorded", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "KaliScriber finds the meetings and moments that matter and answers from them. Tap a source to jump to it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            "What did we decide in the last two weeks?",
                            "What is still open, and who owns it?",
                            "What has been said about the budget?",
                            "Which topics keep coming up?",
                        ).forEach { s -> AssistChip(onClick = { send(s) }, label = { Text(s) }) }
                    }
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f),
                    state = listState,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(messages, key = { it.id }) { message -> MessageBubble(message, onOpenSource) }
                    if (thinking) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Searching your meetings…", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Ask across all meetings…") },
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                )
                IconButton(onClick = { send(input) }, enabled = input.isNotBlank() && !thinking) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: AskMessage, onOpenSource: (AskSource) -> Unit) {
    val mine = message.role == ChatRole.USER
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = when {
                message.isError -> MaterialTheme.colorScheme.errorContainer
                mine -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceContainerHigh
            },
            modifier = Modifier.widthIn(max = 360.dp),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (mine || message.isError) {
                    Text(message.content, style = MaterialTheme.typography.bodyMedium)
                } else {
                    SelectionContainer { MarkdownText(message.content) }
                }
                if (message.sources.isNotEmpty()) {
                    Text("Sources", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        message.sources.forEach { source ->
                            SuggestionChip(
                                onClick = { onOpenSource(source) },
                                icon = { Icon(Icons.Filled.PlayCircle, null, modifier = Modifier.size(16.dp)) },
                                label = {
                                    Text(
                                        listOfNotNull(
                                            source.label.takeIf { it.isNotBlank() },
                                            source.title.take(40),
                                            source.startMs?.let(TranscriptFormatter::timestamp),
                                        ).joinToString(" · "),
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
