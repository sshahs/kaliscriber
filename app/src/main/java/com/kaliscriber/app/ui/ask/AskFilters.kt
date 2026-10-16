package com.kaliscriber.app.ui.ask

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaliscriber.core.ask.AskScope

/** Chips that narrow which meetings a question is about: when, with whom, and what kind. */
@Composable
internal fun ScopeRow(scope: AskScope, people: List<String>, formats: List<String>, onChange: (AskScope) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScopeChip(
            label = if (scope.period == AskScope.Period.ANY) "Any time" else scope.period.label,
            selected = scope.period != AskScope.Period.ANY,
            what = "Time",
            options = AskScope.Period.entries.map { it.label to it },
            current = scope.period,
            onPick = { onChange(scope.copy(period = it ?: AskScope.Period.ANY)) },
        )
        if (people.isNotEmpty()) {
            ScopeChip(
                label = scope.person ?: "Anyone",
                selected = scope.person != null,
                what = "Person",
                options = listOf<Pair<String, String?>>("Anyone" to null) + people.map { it to it },
                current = scope.person,
                onPick = { onChange(scope.copy(person = it)) },
            )
        }
        if (formats.size > 1) {
            ScopeChip(
                label = scope.format ?: "Any format",
                selected = scope.format != null,
                what = "Format",
                options = listOf<Pair<String, String?>>("Any format" to null) + formats.map { it to it },
                current = scope.format,
                onPick = { onChange(scope.copy(format = it)) },
            )
        }
        if (scope.active) TextButton(onClick = { onChange(AskScope()) }) { Text("Clear") }
    }
}

@Composable
private fun <T> ScopeChip(label: String, selected: Boolean, what: String, options: List<Pair<String, T>>, current: T, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected,
            onClick = { open = true },
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose ${what.lowercase()}", modifier = Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (text, value) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    leadingIcon = if (value == current) ({ Icon(Icons.Filled.Check, contentDescription = "Chosen") }) else null,
                    onClick = { open = false; onPick(value) },
                )
            }
        }
    }
}
