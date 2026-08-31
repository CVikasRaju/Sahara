package com.itantra.ui

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.itantra.core.TransceiverState
import com.itantra.network.Language

// ---------------------------------------------------------------------------
// Public data contract — passed in from the integration layer (MainActivity /
// orchestrator). All lambdas default to no-ops so the composable is safely
// previewable in isolation.
// ---------------------------------------------------------------------------

/**
 * Immutable snapshot of state consumed by [MainScreen].
 *
 * The orchestration layer (ViewModel / integration merge) owns and drives these
 * values via [kotlinx.coroutines.flow.StateFlow]. This composable stays purely
 * presentational — it never reads a ViewModel directly.
 */
data class MainScreenState(
    val transceiverState: TransceiverState = TransceiverState.TransceiverOff,
    val selectedLanguage: Language = Language.HINDI,
    /** Peer device display name, shown in the status bar. Null while not connected. */
    val connectedPeerName: String? = null,
)

/**
 * Standalone PTT main screen.
 *
 * **Integration contract** (for the final merge developer):
 * ```kotlin
 * val vmState by viewModel.state.collectAsState()
 * val vmLang  by viewModel.activeLanguage.collectAsState()
 * MainScreen(
 *     state = MainScreenState(vmState, vmLang, peerName),
 *     onToggleTransceiver = { enabled -> viewModel.toggleTransceiver(context, enabled) },
 *     onPttPress   = { viewModel.startRecording() },
 *     onPttRelease = { viewModel.stopRecording() },
 *     onLanguageSelect = { lang -> viewModel.switchLanguage(lang) },
 *     onSosClick   = { /* navigate to SosScreen */ },
 * )
 * ```
 *
 * @param state              Current UI state snapshot.
 * @param onToggleTransceiver Called when the transceiver on/off switch is toggled.
 * @param onPttPress          Called on PTT button press-down.
 * @param onPttRelease        Called on PTT button release/cancel.
 * @param onLanguageSelect    Called when the user picks a language.
 * @param onSosClick          Called when the SOS button is tapped (navigate to [SosScreen]).
 */
@Composable
fun MainScreen(
    state: MainScreenState = MainScreenState(),
    onToggleTransceiver: (Boolean) -> Unit = {},
    onPttPress: () -> Unit = {},
    onPttRelease: () -> Unit = {},
    onLanguageSelect: (Language) -> Unit = {},
    onSosClick: () -> Unit = {},
) {
    val transceiverOn = state.transceiverState !is TransceiverState.TransceiverOff
    var moreLanguagesExpanded by remember { mutableStateOf(false) }

    val primaryLanguages = remember { listOf(Language.HINDI, Language.KANNADA) }
    val moreLanguages = remember { Language.entries.filterNot { it in primaryLanguages } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // ── Header ─────────────────────────────────────────────────────────
        AppHeader(
            peerName = state.connectedPeerName,
            transceiverState = state.transceiverState,
        )

        // ── Controls ───────────────────────────────────────────────────────
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Transceiver on/off toggle
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Transceiver",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = transceiverOn,
                    onCheckedChange = onToggleTransceiver,
                )
            }

            // Primary language chips: Hindi & Kannada (per Phase 1 prioritization)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                primaryLanguages.forEach { lang ->
                    FilterChip(
                        selected = state.selectedLanguage == lang,
                        onClick = { onLanguageSelect(lang) },
                        label = { Text("${lang.code} — ${lang.name}") },
                        enabled = transceiverOn,
                    )
                }
            }

            // Overflow dropdown for the remaining 8 languages
            Box {
                OutlinedButton(
                    onClick = { moreLanguagesExpanded = true },
                    enabled = transceiverOn,
                ) {
                    val suffix = if (moreLanguages.contains(state.selectedLanguage))
                        " · ${state.selectedLanguage.code}" else ""
                    Text("More languages$suffix")
                }
                DropdownMenu(
                    expanded = moreLanguagesExpanded,
                    onDismissRequest = { moreLanguagesExpanded = false },
                ) {
                    moreLanguages.forEach { lang ->
                        DropdownMenuItem(
                            text = { Text("${lang.code} — ${lang.name}") },
                            onClick = {
                                onLanguageSelect(lang)
                                moreLanguagesExpanded = false
                            },
                        )
                    }
                    HorizontalDivider()
                    Text(
                        text = "Primary: hi · kn",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            // Human-readable state label
            StateStatusLabel(state = state.transceiverState)
        }

        // ── PTT Button ─────────────────────────────────────────────────────
        PttButton(
            state = state.transceiverState,
            onPttPress = onPttPress,
            onPttRelease = onPttRelease,
        )

        // ── SOS shortcut ───────────────────────────────────────────────────
        OutlinedButton(
            onClick = onSosClick,
            modifier = Modifier.fillMaxWidth(0.6f),
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "SOS",
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Internal sub-composables (package-private — not exported to integration layer)
// ---------------------------------------------------------------------------

@Composable
private fun AppHeader(
    peerName: String?,
    transceiverState: TransceiverState,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("iTantra", style = MaterialTheme.typography.headlineMedium)
        Text(
            "SIH26173 — Offline Multilingual Transceiver",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        if (peerName != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Peer: $peerName",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (transceiverState is TransceiverState.ReceivingPlayback && transceiverState.isEmergency) {
            Spacer(Modifier.height(4.dp))
            EmergencyBanner()
        }
    }
}

@Composable
private fun EmergencyBanner() {
    val infiniteTransition = rememberInfiniteTransition(label = "emergency_pulse")
    val bgColor by infiniteTransition.animateColor(
        initialValue = MaterialTheme.colorScheme.error,
        targetValue = MaterialTheme.colorScheme.errorContainer,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "emergency_bg",
    )
    Surface(color = bgColor, shape = MaterialTheme.shapes.small) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Emergency",
                tint = MaterialTheme.colorScheme.onError,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "EMERGENCY INCOMING",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onError,
            )
        }
    }
}

@Composable
private fun StateStatusLabel(state: TransceiverState) {
    val label = when (state) {
        is TransceiverState.TransceiverOff -> "Off"
        is TransceiverState.Idle -> "Ready"
        is TransceiverState.Recording -> "Recording…"
        is TransceiverState.Processing -> "Transcribing (${state.languageBeingTranscribed})…"
        is TransceiverState.Transmitting -> "Sending: \"${state.text}\""
        is TransceiverState.ReceivingPlayback -> "Receiving: \"${state.text}\""
        is TransceiverState.ConnectionLost -> "Offline — ${state.queuedMessageCount} queued"
    }
    val color = when (state) {
        is TransceiverState.ConnectionLost -> MaterialTheme.colorScheme.error
        is TransceiverState.ReceivingPlayback -> if (state.isEmergency)
            MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        textAlign = TextAlign.Center,
    )
}

/**
 * PTT circle button. Pulsates while recording; dims when non-interactive.
 */
@Composable
private fun PttButton(
    state: TransceiverState,
    onPttPress: () -> Unit,
    onPttRelease: () -> Unit,
) {
    // Colour mapping intentionally mirrors MainActivity so the integration
    // merge can drop in this composable with zero visual delta.
    val buttonColor: Color
    val buttonText: String
    val isPttInteractive: Boolean
    when (state) {
        is TransceiverState.TransceiverOff -> {
            buttonColor = MaterialTheme.colorScheme.surfaceVariant
            buttonText = "OFF"
            isPttInteractive = false
        }
        is TransceiverState.Idle -> {
            buttonColor = MaterialTheme.colorScheme.primary
            buttonText = "HOLD\nTO TALK"
            isPttInteractive = true
        }
        is TransceiverState.Recording -> {
            buttonColor = MaterialTheme.colorScheme.error
            buttonText = "RECORDING\nRELEASE TO SEND"
            isPttInteractive = true
        }
        is TransceiverState.Processing -> {
            buttonColor = MaterialTheme.colorScheme.tertiary
            buttonText = "PROCESSING\n…"
            isPttInteractive = false
        }
        is TransceiverState.Transmitting -> {
            buttonColor = MaterialTheme.colorScheme.secondary
            buttonText = "SENDING\n…"
            isPttInteractive = false
        }
        is TransceiverState.ReceivingPlayback -> {
            buttonColor = MaterialTheme.colorScheme.primaryContainer
            buttonText = "RECEIVING\n…"
            isPttInteractive = false
        }
        is TransceiverState.ConnectionLost -> {
            buttonColor = MaterialTheme.colorScheme.errorContainer
            buttonText = "OFFLINE\nQUEUED"
            isPttInteractive = true
        }
    }

    // Scale pulse while recording
    val infiniteTransition = rememberInfiniteTransition(label = "ptt_pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (state is TransceiverState.Recording) 1.08f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ptt_scale",
    )

    Box(
        modifier = Modifier
            .size(160.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(buttonColor)
            .then(
                if (isPttInteractive) {
                    Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitFirstDown()
                                onPttPress()
                                waitForUpOrCancellation()
                                onPttRelease()
                            }
                        }
                    }
                } else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = buttonText,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.contentColorFor(buttonColor),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview(showBackground = true, name = "MainScreen – Off")
@Composable
private fun PreviewMainScreenOff() {
    MaterialTheme {
        MainScreen(state = MainScreenState(TransceiverState.TransceiverOff))
    }
}

@Preview(showBackground = true, name = "MainScreen – Idle")
@Composable
private fun PreviewMainScreenIdle() {
    MaterialTheme {
        MainScreen(
            state = MainScreenState(
                transceiverState = TransceiverState.Idle,
                selectedLanguage = Language.HINDI,
                connectedPeerName = "Device-B",
            ),
        )
    }
}

@Preview(showBackground = true, name = "MainScreen – Recording")
@Composable
private fun PreviewMainScreenRecording() {
    MaterialTheme {
        MainScreen(state = MainScreenState(TransceiverState.Recording))
    }
}

@Preview(showBackground = true, name = "MainScreen – ConnectionLost")
@Composable
private fun PreviewMainScreenOffline() {
    MaterialTheme {
        MainScreen(state = MainScreenState(TransceiverState.ConnectionLost(3)))
    }
}

@Preview(showBackground = true, name = "MainScreen – Emergency RX")
@Composable
private fun PreviewMainScreenEmergency() {
    MaterialTheme {
        MainScreen(
            state = MainScreenState(
                transceiverState = TransceiverState.ReceivingPlayback(
                    text = "Mayday! Grid ref 28.7",
                    isEmergency = true,
                ),
            ),
        )
    }
}
