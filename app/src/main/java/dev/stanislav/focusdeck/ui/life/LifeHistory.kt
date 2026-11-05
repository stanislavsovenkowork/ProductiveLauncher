package dev.stanislav.focusdeck.ui.life

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class HistoryBlock(val label: String?, val text: String)
private data class HistoryEvent(
    val position: Int,
    val title: String,
    val occurredAt: LocalDateTime?,
    val dateLabel: String?,
    val blocks: List<HistoryBlock>,
)

private val HISTORY_EVENT_HEADING = Regex("""^###\s+(\d{4}-\d{2}-\d{2})\s+(\d{2}:\d{2}(?::\d{2})?)\s+·\s+(.+)$""")
private val HISTORY_FIELD_HEADING = Regex("""^\*\*(.+)\*\*$""")
private val HISTORY_ANCHOR = Regex("""\^life-(?:event|review)-[A-Za-z0-9-]+""")
private val HISTORY_WIKILINK = Regex("""\[\[([^\]\n]+)\]\]""")
private val HISTORY_FILENAME_ID = Regex("""\s+--\s+[0-9a-fA-F-]{36}$""")
private val HISTORY_UUID = Regex("""[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}""")

/** Presentation only. Never serialize this simplified view back into the note. */
private fun readableHistoryText(source: String): String {
    var text = source.trim().replace(Regex("""^(?:>\s*)+"""), "")
    text = HISTORY_ANCHOR.replace(text, "")
    text = HISTORY_WIKILINK.replace(text) { match ->
        val destination = match.groupValues[1]
        if ('|' in destination) destination.substringAfter('|') else {
            destination.substringBefore('#').substringAfterLast('/').removeSuffix(".md")
                .replace(HISTORY_FILENAME_ID, "")
                .takeUnless { it.isBlank() || HISTORY_UUID.matches(it) } ?: "Связанная запись"
        }
    }
    text = text.replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
        .replace(Regex("""\*\*(.+?)\*\*"""), "$1")
        .replace(Regex("""__(.+?)__"""), "$1")
        .replace(Regex("""`([^`]+)`"""), "$1")
        .replace(Regex("""^#{1,6}\s+"""), "")
        .replace(Regex("""^-\s+\[[xX]]\s*"""), "✓ ")
        .replace(Regex("""^-\s+\[ ]\s*"""), "○ ")
        .replace(Regex("""^[-*]\s+"""), "• ")
    return text.trim()
}

private fun historyEvents(source: String): List<HistoryEvent> {
    val events = mutableListOf<HistoryEvent>()
    val blocks = mutableListOf<HistoryBlock>()
    val textLines = mutableListOf<String>()
    var title: String? = null
    var timestamp: LocalDateTime? = null
    var dateLabel: String? = null
    var label: String? = null
    fun finishBlock() {
        val text = textLines.joinToString("\n").trim()
        if (text.isNotBlank() || label != null) blocks += HistoryBlock(label, text)
        textLines.clear()
        label = null
    }
    fun finishEvent() {
        finishBlock()
        if (title != null || blocks.isNotEmpty()) events += HistoryEvent(
            events.size, title ?: "Запись из Obsidian", timestamp, dateLabel, blocks.toList(),
        )
        blocks.clear()
    }
    source.lineSequence().forEach { line ->
        val raw = line.trim()
        val eventHeading = HISTORY_EVENT_HEADING.matchEntire(raw)
        when {
            eventHeading != null -> {
                finishEvent()
                title = readableHistoryText(eventHeading.groupValues[3])
                timestamp = runCatching {
                    LocalDateTime.parse("${eventHeading.groupValues[1]}T${eventHeading.groupValues[2]}")
                }.getOrNull()
                dateLabel = timestamp?.format(DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm", Locale.forLanguageTag("ru")))
                    ?: "${eventHeading.groupValues[1]} ${eventHeading.groupValues[2]}"
            }
            HISTORY_ANCHOR.matches(raw) -> Unit
            raw.startsWith("Предыдущая запись:") && raw.contains("[[") -> Unit
            HISTORY_FIELD_HEADING.matches(raw) -> {
                finishBlock()
                label = readableHistoryText(HISTORY_FIELD_HEADING.matchEntire(raw)?.groupValues?.get(1).orEmpty())
            }
            raw.matches(Regex("""^(?:>\s*)?(?:`{3,}|~{3,}).*$""")) -> Unit
            else -> {
                // Unquoted metadata after a field is its own paragraph, not part of the observation.
                if (label != null && raw.isNotEmpty() && !raw.startsWith('>')) finishBlock()
                textLines += readableHistoryText(raw)
            }
        }
    }
    finishEvent()
    return events.sortedWith(compareByDescending<HistoryEvent> { it.occurredAt ?: LocalDateTime.MIN }.thenByDescending { it.position })
}

/** A timeline: dot, date, what happened. Details open per event. */
@Composable
internal fun HistoryList(title: String, source: String) {
    val events = remember(source) { historyEvents(source) }
    var open by rememberSaveable(title) { mutableStateOf(false) }
    LifeCard(padding = 0.dp) {
        Column(Modifier.animateContentSize()) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title.uppercase(Locale.forLanguageTag("ru")), style = LifeType.section, modifier = Modifier.weight(1f))
                Text(events.size.toString(), style = LifeType.number, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp))
            }
            if (open) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                if (events.isEmpty()) Text("Полный текст — в Obsidian", style = LifeType.muted)
                events.forEach { event ->
                    key(event.position, event.occurredAt, event.title) {
                        var expanded by rememberSaveable { mutableStateOf(false) }
                        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(event.title, style = MaterialTheme.typography.titleSmall)
                                Text(event.dateLabel ?: "Дата не указана", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (expanded) event.blocks.forEach { block ->
                                    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        block.label?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                                        if (block.text.isNotBlank()) Text(block.text, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
