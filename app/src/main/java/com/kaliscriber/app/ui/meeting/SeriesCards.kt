package com.kaliscriber.app.ui.meeting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.kaliscriber.app.ui.common.BulletList
import com.kaliscriber.app.ui.common.SectionCard
import com.kaliscriber.app.ui.common.SpeakerAvatar
import com.kaliscriber.app.ui.theme.speakerColor
import com.kaliscriber.core.model.ActionItem
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.series.MeetingSeries
import com.kaliscriber.core.series.TalkStats
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/** What the previous meeting of the series decided and left open, with a way back to it. */
@Composable
internal fun SinceLastCard(since: MeetingSeries.SinceLast, onOpenMeeting: (String) -> Unit) {
    val previous = since.previous
    SectionCard("Since last time", Icons.Filled.History) {
        val day = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(previous.createdAt))
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = "Open the previous meeting", role = Role.Button) { onOpenMeeting(previous.id) }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(previous.title, style = MaterialTheme.typography.titleSmall)
                Text(day, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
        if (since.stillOpen.isNotEmpty()) {
            Label("Still open (${since.stillOpen.size})")
            since.stillOpen.forEach { ItemLine(it, done = false) }
        }
        if (since.done.isNotEmpty()) {
            Label("Done since (${since.done.size})")
            since.done.forEach { ItemLine(it, done = true) }
        }
        if (since.decisions.isNotEmpty()) {
            Label("Decided last time")
            BulletList(since.decisions)
        }
        if (since.stillOpen.isEmpty() && since.done.isEmpty() && since.decisions.isEmpty()) {
            Text("No action items or decisions were noted last time.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Label(text: String) {
    Spacer(Modifier.height(4.dp))
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun ItemLine(item: ActionItem, done: Boolean) {
    val owner = item.owner?.let { " — $it" }.orEmpty()
    Text(
        (if (done) "✓ " else "• ") + item.task + owner,
        style = MaterialTheme.typography.bodyMedium,
        textDecoration = if (done) TextDecoration.LineThrough else null,
    )
}

/** Who spoke how much, how often they took the floor and what they asked. */
@Composable
internal fun TalkTimeCard(meeting: Meeting, stats: TalkStats.Stats) {
    SectionCard("Talk time", Icons.Filled.RecordVoiceOver) {
        Text(
            listOf(
                "${stats.people.size} ${if (stats.people.size == 1) "person" else "people"}",
                plural(stats.turns, "turn"),
                plural(stats.questions, "question"),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        stats.people.forEach { person ->
            val speaker = meeting.speaker(person.speakerId)
            val color = speaker?.let { speakerColor(it.colorIndex) } ?: MaterialTheme.colorScheme.outline
            val percent = (person.share * 100).roundToInt()
            val detail = listOf(
                spoken(person.speakingMs),
                plural(person.turns, "turn"),
                plural(person.questions, "question"),
                "longest ${spoken(person.longestMs)}",
            ).joinToString(" · ")
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clearAndSetSemantics { contentDescription = "${person.name}: $percent percent of the talking, $detail" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SpeakerAvatar(speaker, Modifier.size(32.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row {
                        Text(person.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text("$percent%", style = MaterialTheme.typography.titleSmall)
                    }
                    LinearProgressIndicator(
                        progress = { person.share },
                        modifier = Modifier.fillMaxWidth(),
                        color = color,
                        trackColor = color.copy(alpha = 0.15f),
                        drawStopIndicator = {},
                    )
                    Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"

/** "45 s", "3 min", "1 h 5 min". */
internal fun spoken(ms: Long): String {
    val seconds = (ms / 1000).toInt()
    if (seconds < 60) return "$seconds s"
    val minutes = (seconds + 30) / 60
    return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
}
