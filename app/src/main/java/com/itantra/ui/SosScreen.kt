package com.itantra.ui

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.itantra.network.Language

// ---------------------------------------------------------------------------
// Public data contract
// ---------------------------------------------------------------------------

/**
 * The send state of an in-flight SOS burst.
 */
enum class SosSendStatus {
    /** No SOS in progress; button is active. */
    IDLE,
    /** SOS packet encoding + queuing underway. */
    SENDING,
    /** Packet delivered to at least one peer. */
    DELIVERED,
    /** No peers reachable; packet queued for store-and-forward delivery. */
    QUEUED,
}

/**
 * Immutable snapshot of state consumed by [SosScreen].
 *
 * @param sendStatus     Current transmission status of the SOS packet.
 * @param gpsLat         Device latitude at time of SOS, null if unavailable.
 * @param gpsLon         Device longitude at time of SOS, null if unavailable.
 * @param selectedLanguage Language to encode the SOS beacon in.
 * @param queuedCount    Number of messages already queued (shown as context).
 */
data class SosScreenState(
    val sendStatus: SosSendStatus = SosSendStatus.IDLE,
    val gpsLat: Float? = null,
    val gpsLon: Float? = null,
    val selectedLanguage: Language = Language.HINDI,
    val queuedCount: Int = 0,
)

/**
 * Standalone SOS distress-signal screen.
 *
 * **Integration contract** (for the final merge developer):
 * ```kotlin
 * SosScreen(
 *     state = SosScreenState(
 *         sendStatus  = viewModel.sosSendStatus.collectAsState().value,
 *         gpsLat      = locationManager.lastLat,
 *         gpsLon      = locationManager.lastLon,
 *         selectedLanguage = viewModel.activeLanguage.collectAsState().value,
 *         queuedCount = viewModel.queuedMessageCount.collectAsState().value,
 *     ),
 *     onSosConfirmed = { viewModel.sendSosBeacon() },
 *     onBack         = { navController.popBackStack() },
 * )
 * ```
 *
 * The composable owns the **confirmation dialog** UI state internally — the
 * caller only receives [onSosConfirmed] after the user explicitly confirms.
 *
 * @param state         Current SOS state snapshot.
 * @param onSosConfirmed Called once after the user confirms the SOS send.
 * @param onBack        Called when the user taps the back/cancel button.
 */
@Composable
fun SosScreen(
    state: SosScreenState = SosScreenState(),
    onSosConfirmed: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    var showConfirmDialog by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // ── Header ─────────────────────────────────────────────────────
            SosHeader()

            // ── GPS card ───────────────────────────────────────────────────
            GpsCard(lat = state.gpsLat, lon = state.gpsLon)

            // ── Status summary ─────────────────────────────────────────────
            SosStatusCard(state = state)

            // ── Main SOS button or progress indicator ──────────────────────
            when (state.sendStatus) {
                SosSendStatus.IDLE -> {
                    SosTriggerButton(onClick = { showConfirmDialog = true })
                }
                SosSendStatus.SENDING -> {
                    SosSendingIndicator()
                }
                SosSendStatus.DELIVERED -> {
                    SosResultBadge(
                        text = "SOS DELIVERED",
                        isSuccess = true,
                    )
                }
                SosSendStatus.QUEUED -> {
                    SosResultBadge(
                        text = "SOS QUEUED — will deliver when peer is reachable",
                        isSuccess = false,
                    )
                }
            }

            // ── Back / cancel ──────────────────────────────────────────────
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("← Back to PTT")
            }
        }
    }

    // ── Confirmation dialog ─────────────────────────────────────────────────
    if (showConfirmDialog) {
        SosConfirmDialog(
            gpsLat = state.gpsLat,
            gpsLon = state.gpsLon,
            language = state.selectedLanguage,
            onConfirm = {
                showConfirmDialog = false
                onSosConfirmed()
            },
            onDismiss = { showConfirmDialog = false },
        )
    }
}

// ---------------------------------------------------------------------------
// Internal sub-composables
// ---------------------------------------------------------------------------

@Composable
private fun SosHeader() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Emergency SOS",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            text = "Sends a SILENT_SOS packet with EMERGENCY priority\nto all connected peers (iBFS-v1 §3).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun GpsCard(lat: Float?, lon: Float?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (lat != null)
                MaterialTheme.colorScheme.secondaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.LocationOn,
                contentDescription = null,
                tint = if (lat != null) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.width(8.dp))
            if (lat != null && lon != null) {
                Column {
                    Text(
                        text = "GPS attached to SOS packet",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        text = "Lat %.5f  Lon %.5f".format(lat, lon),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            } else {
                Text(
                    text = "GPS unavailable — SOS sent without coordinates",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun SosStatusCard(state: SosScreenState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Packet details",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
            SosDetailRow("Type", "SILENT_SOS")
            SosDetailRow("Priority", "EMERGENCY (0xF)")
            SosDetailRow("Language", "${state.selectedLanguage.name} (${state.selectedLanguage.code})")
            SosDetailRow("Queued messages", "${state.queuedCount}")
        }
    }
}

@Composable
private fun SosDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun SosTriggerButton(onClick: () -> Unit) {
    // Pulsating red SOS button
    val infiniteTransition = rememberInfiniteTransition(label = "sos_pulse")
    val bgColor by infiniteTransition.animateColor(
        initialValue = MaterialTheme.colorScheme.error,
        targetValue = MaterialTheme.colorScheme.errorContainer,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sos_bg",
    )

    Box(
        modifier = Modifier
            .size(140.dp)
            .clip(CircleShape)
            .background(bgColor),
        contentAlignment = Alignment.Center,
    ) {
        Button(
            onClick = onClick,
            modifier = Modifier.size(140.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = bgColor),
        ) {
            Text(
                text = "SOS",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onError,
            )
        }
    }
}

@Composable
private fun SosSendingIndicator() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Transmitting SOS…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun SosResultBadge(text: String, isSuccess: Boolean) {
    val containerColor = if (isSuccess)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.errorContainer
    val contentColor = if (isSuccess)
        MaterialTheme.colorScheme.onPrimaryContainer
    else
        MaterialTheme.colorScheme.onErrorContainer

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
        )
    }
}

@Composable
private fun SosConfirmDialog(
    gpsLat: Float?,
    gpsLon: Float?,
    language: Language,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = {
            Text("Send Emergency SOS?", fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                Text(
                    "This will broadcast a SILENT_SOS packet at EMERGENCY priority " +
                        "to all peers. It will override their audio output.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (gpsLat != null && gpsLon != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "GPS: %.4f, %.4f will be included.".format(gpsLat, gpsLon),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("SEND SOS", color = MaterialTheme.colorScheme.onError)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview(showBackground = true, name = "SosScreen – Idle (no GPS)")
@Composable
private fun PreviewSosIdle() {
    MaterialTheme { SosScreen() }
}

@Preview(showBackground = true, name = "SosScreen – Idle (with GPS)")
@Composable
private fun PreviewSosIdleGps() {
    MaterialTheme {
        SosScreen(
            state = SosScreenState(
                gpsLat = 28.6139f,
                gpsLon = 77.2090f,
            ),
        )
    }
}

@Preview(showBackground = true, name = "SosScreen – Sending")
@Composable
private fun PreviewSosSending() {
    MaterialTheme {
        SosScreen(state = SosScreenState(sendStatus = SosSendStatus.SENDING))
    }
}

@Preview(showBackground = true, name = "SosScreen – Delivered")
@Composable
private fun PreviewSosDelivered() {
    MaterialTheme {
        SosScreen(state = SosScreenState(sendStatus = SosSendStatus.DELIVERED))
    }
}

@Preview(showBackground = true, name = "SosScreen – Queued (offline)")
@Composable
private fun PreviewSosQueued() {
    MaterialTheme {
        SosScreen(
            state = SosScreenState(
                sendStatus = SosSendStatus.QUEUED,
                queuedCount = 4,
            ),
        )
    }
}
