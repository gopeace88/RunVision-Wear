package com.runvision.wear.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.*
import com.runvision.wear.ble.RLensConnection

@Composable
fun HomeScreen(
    connectionState: RLensConnection.ConnectionState,
    onStartClick: () -> Unit
) {
    val listState = rememberScalingLazyListState()

    Scaffold(
        positionIndicator = {
            PositionIndicator(scalingLazyListState = listState)
        }
    ) {
        ScalingLazyColumn(
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            item {
                Text(
                    text = "RunVision",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
            }

            item {
                Spacer(modifier = Modifier.height(12.dp))
            }

            item {
                Button(
                    onClick = onStartClick,
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .padding(horizontal = 8.dp),
                    colors = ButtonDefaults.primaryButtonColors()
                ) {
                    Text(
                        text = "START",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(12.dp))
            }

            item {
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
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
            }
        }
    }
}
