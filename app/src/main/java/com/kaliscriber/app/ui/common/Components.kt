@file:OptIn(ExperimentalMaterial3Api::class)

package com.kaliscriber.app.ui.common

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kaliscriber.app.ui.theme.speakerColor
import com.kaliscriber.core.model.Speaker

@Composable
fun SpeakerBadge(speaker: Speaker?, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val color = speaker?.let { speakerColor(it.colorIndex) } ?: MaterialTheme.colorScheme.outline
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(
            text = speaker?.name ?: "Unknown",
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontStyle = if (speaker?.isPlaceholder == true) FontStyle.Italic else FontStyle.Normal,
        )
        if (speaker?.suggestedName != null) {
            Text(" → ${speaker.suggestedName}?", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        }
    }
}

@Composable
fun SectionCard(title: String, icon: ImageVector? = null, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier = modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

@Composable
fun BulletList(items: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { item ->
            Row {
                Text("•  ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Text(inlineMarkdown(item), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        action?.invoke()
    }
}

/** Read-only text field that opens a menu of [options]; avoids the still-evolving exposed dropdown APIs. */
@Composable
fun <T> DropdownSelector(
    label: String,
    selected: T,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = optionLabel(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
        )
        // Transparent overlay so a tap anywhere on the field opens the menu.
        Box(Modifier.matchParentSize().clickable { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(optionLabel(option)) }, onClick = {
                    expanded = false
                    onSelect(option)
                })
            }
        }
    }
}

/** An AI answer that is still being written, shown as it arrives. */
@Composable
fun StreamingAnswer(text: String, maxWidth: Dp) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.widthIn(max = maxWidth),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MarkdownText(text)
            LinearProgressIndicator(Modifier.width(48.dp))
        }
    }
}

/** Renders the small Markdown subset LLMs commonly produce: headings, bullets, checkboxes, bold and italics. */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        var inCode = false
        markdown.trim().lines().forEach { raw ->
            val line = raw.trimEnd()
            if (line.trimStart().startsWith("```")) {
                inCode = !inCode
                return@forEach
            }
            when {
                inCode -> Text(
                    line,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    color = color,
                )
                line.isBlank() -> Spacer(Modifier.size(2.dp))
                line.startsWith("#") -> Text(
                    inlineMarkdown(line.trimStart('#').trim()),
                    style = if (line.startsWith("###")) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                    color = color,
                )
                Regex("""^\s*[-*+]\s+\[( |x|X)]\s+.*""").matches(line) -> {
                    val done = line.contains("[x]", ignoreCase = true)
                    Row {
                        Text(if (done) "☑  " else "☐  ", color = color)
                        Text(inlineMarkdown(line.substringAfter("]").trim()), style = MaterialTheme.typography.bodyMedium, color = color)
                    }
                }
                Regex("""^\s*([-*+]|\d+[.)])\s+.*""").matches(line) -> {
                    val indent = line.takeWhile { it == ' ' }.length / 2
                    val marker = Regex("""^\s*([-*+]|\d+[.)])""").find(line)!!.groupValues[1]
                    Row(Modifier.padding(start = (indent * 12).dp)) {
                        Text(if (marker.first().isDigit()) "$marker " else "•  ", color = MaterialTheme.colorScheme.primary)
                        Text(
                            inlineMarkdown(line.trimStart().removePrefix(marker).trim()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = color,
                        )
                    }
                }
                else -> Text(inlineMarkdown(line), style = MaterialTheme.typography.bodyMedium, color = color)
            }
        }
    }
}

fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) && text.indexOf("**", i + 2) > i + 2 -> {
                val end = text.indexOf("**", i + 2)
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(text.substring(i + 2, end)) }
                i = end + 2
            }
            text.startsWith("`", i) && text.indexOf('`', i + 1) > i + 1 -> {
                val end = text.indexOf('`', i + 1)
                withStyle(SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)) { append(text.substring(i + 1, end)) }
                i = end + 1
            }
            (text[i] == '*' || text[i] == '_') && i + 1 < text.length && text[i + 1] != ' ' &&
                text.indexOf(text[i], i + 1) > i + 1 && (i == 0 || !text[i - 1].isLetterOrDigit()) -> {
                val end = text.indexOf(text[i], i + 1)
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                i = end + 1
            }
            else -> {
                append(text[i])
                i++
            }
        }
    }
}

fun shareText(context: Context, subject: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
