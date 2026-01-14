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
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = onStartClick,
                modifier = Modifier.size(width = 100.dp, height = 50.dp),
                colors = ButtonDefaults.primaryButtonColors()
            ) {
                Text(
                    text = "START",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Status text based on connection state
            val (statusText, statusColor) = when (connectionState) {
                RLensConnection.ConnectionState.CONNECTED -> "Connected" to Color(0xFF4CAF50)
                RLensConnection.ConnectionState.CONNECTING -> "Connecting.." to Color(0xFFFF9800)
                RLensConnection.ConnectionState.RECONNECTING -> "Reconnecting.." to Color(0xFFFF9800)
                RLensConnection.ConnectionState.SCANNING -> "Scanning.." to Color(0xFF2196F3)
                RLensConnection.ConnectionState.NOT_FOUND -> "Not Found" to Color(0xFFF44336)
                RLensConnection.ConnectionState.DISCONNECTED -> "READY" to Color.Gray
            }

            Text(
                text = statusText,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = statusColor,
                textAlign = TextAlign.Center
            )
        }
    }
}
