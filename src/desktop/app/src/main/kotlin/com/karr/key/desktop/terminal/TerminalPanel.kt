package com.karr.key.desktop.terminal

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.karr.key.core.KarrTrace
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

private val TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

/**
 * The desktop Terminal: a live view of the [KarrTrace] stream that on Android
 * only reaches `adb logcat`. Mouse-first — a scrollbar, wheel scrolling, tag
 * filter chips, a text filter, autoscroll, copy, and clear.
 */
@Composable
fun TerminalPanel(
    log: TraceLog,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
) {
    // Read revision so the panel recomposes whenever a line is appended/cleared.
    @Suppress("UNUSED_VARIABLE")
    val revision = log.revision
    val allLines = log.snapshot()

    var textFilter by remember { mutableStateOf("") }
    val activeChannels = remember { mutableStateOf(TraceChannel.entries.toSet()) }
    var autoScroll by remember { mutableStateOf(true) }

    val enabledTags = activeChannels.value.map { it.tag }.toSet()
    val visibleLines = allLines.filter { line ->
        line.tag in enabledTags &&
            (textFilter.isBlank() || line.message.contains(textFilter, ignoreCase = true) ||
                line.tag.contains(textFilter, ignoreCase = true))
    }

    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(visibleLines.size, autoScroll) {
        if (autoScroll && visibleLines.isNotEmpty()) {
            listState.scrollToItem(visibleLines.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        TerminalToolbar(
            channels = activeChannels.value,
            onToggleChannel = { channel ->
                val current = activeChannels.value
                activeChannels.value =
                    if (channel in current) current - channel else current + channel
            },
            onAllChannels = { activeChannels.value = TraceChannel.entries.toSet() },
            textFilter = textFilter,
            onTextFilter = { textFilter = it },
            autoScroll = autoScroll,
            onAutoScroll = { autoScroll = it },
            lineCount = visibleLines.size,
            totalCount = allLines.size,
            onCopy = {
                clipboard.setText(AnnotatedString(visibleLines.joinToString("\n") { it.render() }))
            },
            onClear = log::clear,
            onScrollToEnd = {
                scope.launch {
                    if (visibleLines.isNotEmpty()) listState.scrollToItem(visibleLines.lastIndex)
                }
            },
            onClose = onClose,
        )

        Box(modifier = Modifier.fillMaxSize()) {
            if (visibleLines.isEmpty()) {
                Text(
                    text = if (allLines.isEmpty()) {
                        "No activity yet. Scan or connect to a unit to see the live " +
                            "BLE, protocol, and authentication trace here."
                    } else {
                        "No lines match the current filters."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    contentPadding = PaddingValues(vertical = 6.dp),
                ) {
                    items(visibleLines, key = { it.id }) { line ->
                        TerminalRow(line)
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(listState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun TerminalRow(line: TraceLine) {
    val color = when (line.level) {
        KarrTrace.Level.WARN -> MaterialTheme.colorScheme.error
        KarrTrace.Level.DEBUG -> MaterialTheme.colorScheme.onSurface
    }
    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Text(
            text = TIME_FORMAT.format(Instant.ofEpochMilli(line.epochMillis)),
            style = LocalTextStyle.current.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "  ${shortTag(line.tag).padEnd(7)} ",
            style = LocalTextStyle.current.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            ),
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = line.message,
            style = LocalTextStyle.current.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            ),
            color = color,
        )
    }
}

@Composable
private fun TerminalToolbar(
    channels: Set<TraceChannel>,
    onToggleChannel: (TraceChannel) -> Unit,
    onAllChannels: () -> Unit,
    textFilter: String,
    onTextFilter: (String) -> Unit,
    autoScroll: Boolean,
    onAutoScroll: (Boolean) -> Unit,
    lineCount: Int,
    totalCount: Int,
    onCopy: () -> Unit,
    onClear: () -> Unit,
    onScrollToEnd: () -> Unit,
    onClose: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Terminal",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "  $lineCount / $totalCount lines",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(modifier = Modifier.weight(1f))
            TextButton(onClick = { onAutoScroll(!autoScroll) }) {
                Text(if (autoScroll) "Autoscroll: on" else "Autoscroll: off")
            }
            TextButton(onClick = onScrollToEnd) { Text("Bottom") }
            TextButton(onClick = onCopy) { Text("Copy") }
            TextButton(onClick = onClear) { Text("Clear") }
            if (onClose != null) {
                TextButton(onClick = onClose) { Text("Hide") }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        ) {
            FilterChip(
                selected = channels.size == TraceChannel.entries.size,
                onClick = onAllChannels,
                label = { Text("All") },
            )
            TraceChannel.entries.forEach { channel ->
                FilterChip(
                    selected = channel in channels,
                    onClick = { onToggleChannel(channel) },
                    label = { Text(channel.label) },
                )
            }
            Box(modifier = Modifier.width(12.dp))
            OutlinedTextField(
                value = textFilter,
                onValueChange = onTextFilter,
                singleLine = true,
                label = { Text("Filter text") },
                modifier = Modifier.width(240.dp),
            )
        }
    }
}

private fun shortTag(tag: String): String = tag.substringAfter('/', tag)

private fun TraceLine.render(): String =
    "${TIME_FORMAT.format(Instant.ofEpochMilli(epochMillis))} ${shortTag(tag)} $message"
