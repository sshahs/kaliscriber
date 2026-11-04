package com.kaliscriber.app.ui.meeting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.AssignmentInd
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.PersonPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.kaliscriber.app.ui.common.SectionCard
import com.kaliscriber.core.model.ForMe

private val TIMESTAMP = Regex("""\[(\d{1,2}(?::\d{2}){1,2})]""")

/** The user's own part in the meeting: asked of them, promised by them, waiting on them. */
@Composable
internal fun ForMeCard(me: ForMe, onOpenTranscriptAt: (Long) -> Unit) {
    SectionCard("For you", Icons.Filled.PersonPin) {
        Group("Asked of you", Icons.Filled.AssignmentInd, me.asked, onOpenTranscriptAt)
        Group("You promised", Icons.Filled.TaskAlt, me.promised, onOpenTranscriptAt)
        Group("Still to answer", Icons.AutoMirrored.Filled.HelpOutline, me.unanswered, onOpenTranscriptAt)
    }
}

@Composable
private fun Group(title: String, icon: ImageVector, items: List<String>, onOpenTranscriptAt: (Long) -> Unit) {
    if (items.isEmpty()) return
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(title, style = MaterialTheme.typography.titleSmall)
        }
        items.forEach { item ->
            val at = TIMESTAMP.find(item)?.groupValues?.get(1)?.let(::millis)
            Text(
                "• $item",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (at != null) Modifier.clickable(onClickLabel = "Play from here") { onOpenTranscriptAt(at) } else Modifier)
                    .padding(start = 24.dp, top = 4.dp, bottom = 4.dp),
            )
        }
    }
}

private fun millis(text: String): Long? {
    val parts = text.split(':').map { it.toLongOrNull() ?: return null }
    return when (parts.size) {
        2 -> (parts[0] * 60 + parts[1]) * 1000
        3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
        else -> null
    }
}
