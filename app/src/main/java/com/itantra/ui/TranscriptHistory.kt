package com.itantra.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.itantra.network.Language
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------------
// Public data contract
// ---------------------------------------------------------------------------

/**
 * Direction of a transcript entry relative to this device.
 */
enum class MessageDirection {
    /** This device sent the message. */
    OUTBOUND,
    /** This device received the message from a peer. */
    INBOUND,
}

/**
 * A single decoded message entry in the transcript log.
 *
 * @param id            Unique stable ID (maps to iBFS-v1 sequence ID).
 * @param text          Decoded UTF-8 text of the packet.
 * @param language      Language the packet was encoded/decoded in.
 * @param direction     Whether this was sent or received by this device.
 * @param isEmergency   True if [com.itantra.network.Priority.EMERGENCY] flag was set.
 * @param timestampMs   Wall-clock epoch milliseconds at time of receipt/send.
 * @param peerName      Display name of the remote peer (null if unknown).
 */
data class TranscriptEntry(
    val id: Long,
    val text: String,
    val language: Language,
    val direction: MessageDirection,
    val isEmergency: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis(),
    val peerName: String? = null,
)

/**
 * Immutable snapshot of state consumed by [TranscriptHistory].
 *
 * @param entries       Full ordered list of transcript entries (oldest first).
 * @param filterLanguage If non-null, only entries matching this language are shown.
 */
data class TranscriptHistoryState(
    val entries: List<TranscriptEntry> = emptyList(),
    val filterLanguage: Language? = null,
)

/**
 * Standalone scrollable transcript / message-log screen.
 *
 * **Integration contract** (for the final merge developer):
 * ```kotlin
 * TranscriptHistory(
 *     state = TranscriptHistoryState(
 *         entries        = viewModel.transcriptEntries.collectAsState().value,
 *         filterLanguage = viewModel.transcriptFilter.collectAsState().value,
 *     ),
 *     onFilterChange = { lang -> viewModel.setTranscriptFilter(lang) },
 *     onClearHistory = { viewModel.clearTranscript() },
 *     onBack         = { navController.popBackStack() },
 * )
 * ```
 *
 * @param state          Current transcript state snapshot.
 * @param onFilterChange Called when the user changes the active language filter.
 *                       Pass `null` to clear the filter.
 * @param onClearHistory Called when the user taps "Clear history".
 * @param onBack         Called when the user taps the back button in the top bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptHistory(
    state: TranscriptHistoryState = TranscriptHistoryState(),
    onFilterChange: (Language?) -> Unit = {},
    onClearHistory: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    val visibleEntries = remember(state.entries, state.filterLanguage) {
        if (state.filterLanguage == null) state.entries
        else state.entries.filter { it.language == state.filterLanguage }
    }

    val listState = rememberLazyListState()

    // Auto-scroll to the newest entry whenever the list grows
    LaunchedEffect(visibleEntries.size) {
        if (visibleEntries.isNotEmpty()) {
            listState.animateScrollToItem(visibleEntries.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Transcript History")
                        Text(
                            text = "${visibleEntries.size} message${if (visibleEntries.size != 1) "s" else ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text(
                            text = "←",
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onClearHistory,
                        enabled = state.entries.isNotEmpty(),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Clear history",
                            tint = if (state.entries.isNotEmpty())
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.outline,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // ── Language filter chips ───────────────────────────────────────
            LanguageFilterRow(
                activeFilter = state.filterLanguage,
                availableLanguages = remember(state.entries) {
                    state.entries.map { it.language }.distinct().sortedBy { it.name }
                },
                onFilterChange = onFilterChange,
            )

            // ── Entry list or empty state ───────────────────────────────────
            if (visibleEntries.isEmpty()) {
                EmptyTranscriptPlaceholder(
                    hasEntries = state.entries.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        items = visibleEntries,
                        key = { it.id },
                    ) { entry ->
                        TranscriptBubble(entry = entry)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Internal sub-composables
// ---------------------------------------------------------------------------

@Composable
private fun LanguageFilterRow(
    activeFilter: Language?,
    availableLanguages: List<Language>,
    onFilterChange: (Language?) -> Unit,
) {
    if (availableLanguages.isEmpty()) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Filter:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
        )
        FilterChip(
            selected = activeFilter == null,
            onClick = { onFilterChange(null) },
            label = { Text("All") },
        )
        availableLanguages.forEach { lang ->
            FilterChip(
                selected = activeFilter == lang,
                onClick = { onFilterChange(if (activeFilter == lang) null else lang) },
                label = { Text(lang.code) },
            )
        }
    }
}

/**
 * Chat-bubble style row for a single transcript entry.
 *
 * Outbound messages align right (primary colour); inbound align left (surface).
 * Emergency entries show a warning badge and use error colour tones.
 */
@Composable
private fun TranscriptBubble(entry: TranscriptEntry) {
    val isOutbound = entry.direction == MessageDirection.OUTBOUND
    val bubbleColor = when {
        entry.isEmergency -> MaterialTheme.colorScheme.errorContainer
        isOutbound -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = when {
        entry.isEmergency -> MaterialTheme.colorScheme.onErrorContainer
        isOutbound -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isOutbound) Arrangement.End else Arrangement.Start,
    ) {
        // Peer avatar (inbound only)
        if (!isOutbound) {
            PeerAvatar(
                initial = entry.peerName?.firstOrNull()?.uppercaseChar() ?: '?',
                isEmergency = entry.isEmergency,
            )
            Spacer(Modifier.width(8.dp))
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = bubbleColor),
            modifier = Modifier.fillMaxWidth(0.80f),
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                // Header row: peer name + timestamp
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (entry.isEmergency) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Emergency",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            text = if (isOutbound) "You" else (entry.peerName ?: "Peer"),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = textColor,
                        )
                    }
                    Text(
                        text = formatTimestamp(entry.timestampMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }

                Spacer(Modifier.height(4.dp))

                // Message text
                Text(
                    text = entry.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor,
                )

                Spacer(Modifier.height(4.dp))

                // Footer: language tag
                Text(
                    text = "${entry.language.code} · ${entry.language.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = if (isOutbound) TextAlign.End else TextAlign.Start,
                )
            }
        }

        // Self avatar (outbound only)
        if (isOutbound) {
            Spacer(Modifier.width(8.dp))
            PeerAvatar(initial = 'M', isEmergency = false)
        }
    }
}

@Composable
private fun PeerAvatar(initial: Char, isEmergency: Boolean) {
    val bg = if (isEmergency) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.tertiary
    val fg = if (isEmergency) MaterialTheme.colorScheme.onError
    else MaterialTheme.colorScheme.onTertiary

    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = fg,
        )
    }
}

@Composable
private fun EmptyTranscriptPlaceholder(
    hasEntries: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (hasEntries) "No messages match the selected filter."
                else "No messages yet.\nStart the transceiver and press PTT.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

private fun formatTimestamp(epochMs: Long): String = timeFormat.format(Date(epochMs))

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

private val previewEntries = listOf(
    TranscriptEntry(
        id = 1L,
        text = "सभी इकाइयाँ, स्थिति रिपोर्ट करें।",
        language = Language.HINDI,
        direction = MessageDirection.OUTBOUND,
        timestampMs = System.currentTimeMillis() - 60_000,
        peerName = "Device-A",
    ),
    TranscriptEntry(
        id = 2L,
        text = "ಇಲ್ಲಿ ಯೂನಿಟ್ 2, ಎಲ್ಲಾ ಸ್ಪಷ್ಟ.",
        language = Language.KANNADA,
        direction = MessageDirection.INBOUND,
        timestampMs = System.currentTimeMillis() - 45_000,
        peerName = "Device-B",
    ),
    TranscriptEntry(
        id = 3L,
        text = "MAYDAY — position 28.7°N 77.2°E, requesting immediate assistance!",
        language = Language.ENGLISH_IN,
        direction = MessageDirection.INBOUND,
        isEmergency = true,
        timestampMs = System.currentTimeMillis() - 10_000,
        peerName = "Device-C",
    ),
    TranscriptEntry(
        id = 4L,
        text = "Acknowledged. Dispatching support.",
        language = Language.ENGLISH_IN,
        direction = MessageDirection.OUTBOUND,
        timestampMs = System.currentTimeMillis() - 5_000,
    ),
)

@Preview(showBackground = true, name = "TranscriptHistory – populated")
@Composable
private fun PreviewTranscriptPopulated() {
    MaterialTheme {
        TranscriptHistory(state = TranscriptHistoryState(entries = previewEntries))
    }
}

@Preview(showBackground = true, name = "TranscriptHistory – filtered (kn)")
@Composable
private fun PreviewTranscriptFiltered() {
    MaterialTheme {
        TranscriptHistory(
            state = TranscriptHistoryState(
                entries = previewEntries,
                filterLanguage = Language.KANNADA,
            ),
        )
    }
}

@Preview(showBackground = true, name = "TranscriptHistory – empty")
@Composable
private fun PreviewTranscriptEmpty() {
    MaterialTheme {
        TranscriptHistory(state = TranscriptHistoryState(entries = emptyList()))
    }
}
