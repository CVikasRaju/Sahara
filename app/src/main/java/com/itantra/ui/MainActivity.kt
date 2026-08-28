package com.itantra.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.itantra.core.TransceiverState
import com.itantra.network.Language

/**
 * PTT screen for iTantra.
 * Wired directly to [TransceiverService] via [TransceiverViewModel].
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PttScreen()
                }
            }
        }
    }
}

@Composable
fun PttScreen(viewModel: TransceiverViewModel = viewModel()) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val selectedLanguage by viewModel.activeLanguage.collectAsState()
    val transceiverOn = state !is TransceiverState.TransceiverOff
    var moreLanguagesExpanded by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        viewModel.bindService(context)
        onDispose {
            viewModel.unbindService(context)
        }
    }

    // Surface primary hi/kn pair as quick-selection chips; keep remaining
    // 8 languages accessible behind the "More languages" menu.
    val primaryLanguages = remember { listOf(Language.HINDI, Language.KANNADA) }
    val moreLanguages = remember { Language.entries.filterNot { it in primaryLanguages } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("iTantra", style = MaterialTheme.typography.headlineMedium)
            Text(
                "SIH26173 — Offline Multilingual Transceiver",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Transceiver Mode")
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = transceiverOn,
                    onCheckedChange = { enabled ->
                        viewModel.toggleTransceiver(context, enabled)
                    },
                )
            }

            // Primary quick-selects: Hindi + Kannada.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                primaryLanguages.forEach { lang ->
                    FilterChip(
                        selected = selectedLanguage == lang,
                        onClick = {
                            viewModel.switchLanguage(lang)
                        },
                        label = { Text("${lang.code} (${lang.name})") },
                        enabled = transceiverOn,
                    )
                }
            }

            // Remaining 8 languages, deprioritized behind a divider-separated submenu.
            OutlinedButton(onClick = { moreLanguagesExpanded = true }, enabled = transceiverOn) {
                Text(
                    "More languages" +
                        if (moreLanguages.contains(selectedLanguage)) " · ${selectedLanguage.code}" else "",
                )
            }
            DropdownMenu(
                expanded = moreLanguagesExpanded,
                onDismissRequest = { moreLanguagesExpanded = false },
            ) {
                moreLanguages.forEach { lang ->
                    DropdownMenuItem(
                        text = { Text("${lang.code} — ${lang.name}") },
                        onClick = {
                            viewModel.switchLanguage(lang)
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

            Text("State: ${state::class.simpleName}", style = MaterialTheme.typography.bodyMedium)
        }

        // PTT Button visual styling & label based on TransceiverState
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
                buttonText = "PROCESSING\n..."
                isPttInteractive = false
            }
            is TransceiverState.Transmitting -> {
                buttonColor = MaterialTheme.colorScheme.secondary
                buttonText = "SENDING\n..."
                isPttInteractive = false
            }
            is TransceiverState.ReceivingPlayback -> {
                buttonColor = MaterialTheme.colorScheme.primaryContainer
                buttonText = "RECEIVING\n..."
                isPttInteractive = false
            }
            is TransceiverState.ConnectionLost -> {
                buttonColor = MaterialTheme.colorScheme.errorContainer
                buttonText = "OFFLINE\nQUEUED"
                isPttInteractive = true
            }
        }

        Box(
            modifier = Modifier
                .size(150.dp)
                .clip(CircleShape)
                .background(buttonColor)
                .then(
                    if (isPttInteractive) {
                        Modifier.pointerInputPttHold(
                            onPressStart = { viewModel.startRecording() },
                            onPressEnd = { viewModel.stopRecording() },
                        )
                    } else Modifier
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

        Spacer(Modifier.height(1.dp))
    }
}

private fun Modifier.pointerInputPttHold(
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
): Modifier = this.then(
    Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitFirstDown()
                onPressStart()
                waitForUpOrCancellation()
                onPressEnd()
            }
        }
    }
)
