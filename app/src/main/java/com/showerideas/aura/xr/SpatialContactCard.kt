package com.showerideas.aura.xr

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Task 101 — Spatial contact card Composable for Android XR.
 *
 * Rendered inside a Jetpack XR `SpatialPanel` anchored to the peer's detected
 * spatial position. Displays the peer's avatar and identity key hash (truncated)
 * with an "Exchange" action affordance.
 *
 * This is a standard Material 3 Composable — the spatial anchoring and panel
 * lifecycle are managed by the calling [XrExchangeActivity] via the
 * `androidx.xr.compose` APIs (not yet declared as a dependency; see
 * [XrExchangeActivity] doc comment).
 *
 * ## Gesture input
 * In XR mode the "Exchange" button is confirmed via hand tracking (pinch gesture
 * on the dominant hand). The [onExchangeConfirmed] callback fires once
 * [ArGestureExchangeBridge] returns [VerificationResult.Success].
 *
 * See: ROADMAP §Task 101
 */
@Composable
fun SpatialContactCard(
    identityKeyHash: String,
    displayName: String = "Unknown Peer",
    distanceM: Float,
    onExchangeConfirmed: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .padding(16.dp)
            .fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Initials avatar — colour derived from identity key hash
            val initials = displayName.trim().split(" ")
                .take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }
                .joinToString("")
                .ifEmpty { "?" }
            val hashColor = Color(0xFF000000.toInt() or (identityKeyHash.take(6)
                .padEnd(6, '0').toLong(16).toInt() or 0xFF404040.toInt()))
            val textMeasurer = rememberTextMeasurer()
            Canvas(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
            ) {
                drawCircle(color = hashColor)
                val textLayout = textMeasurer.measure(
                    text = initials,
                    style = TextStyle(color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                )
                drawText(
                    textLayoutResult = textLayout,
                    topLeft = Offset(
                        (size.width  - textLayout.size.width)  / 2f,
                        (size.height - textLayout.size.height) / 2f
                    )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = displayName,
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                text = identityKeyHash.take(16) + "…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = "%.1fm away".format(distanceM),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onExchangeConfirmed,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Exchange")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Dismiss")
            }
        }
    }
}
