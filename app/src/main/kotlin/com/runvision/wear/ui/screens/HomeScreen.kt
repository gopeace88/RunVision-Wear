package com.runvision.wear.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Text
import com.runvision.wear.ble.RLensConnection

@Composable
fun HomeScreen(
    connectionState: RLensConnection.ConnectionState,
    onStartClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "RunVision",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onStartClick,
                colors = ButtonDefaults.primaryButtonColors()
            ) {
                Text("START")
            }

            Spacer(modifier = Modifier.height(16.dp))

            val statusText = when (connectionState) {
                RLensConnection.ConnectionState.CONNECTED -> "● rLens"
                RLensConnection.ConnectionState.CONNECTING -> "○ Connecting..."
                RLensConnection.ConnectionState.RECONNECTING -> "○ Reconnecting..."
                RLensConnection.ConnectionState.DISCONNECTED -> "○ rLens"
            }
            val statusColor = when (connectionState) {
                RLensConnection.ConnectionState.CONNECTED -> Color(0xFF4CAF50)
                else -> Color.Gray
            }

            Text(
                text = statusText,
                fontSize = 12.sp,
                color = statusColor,
                textAlign = TextAlign.Center
            )
        }
    }
}
