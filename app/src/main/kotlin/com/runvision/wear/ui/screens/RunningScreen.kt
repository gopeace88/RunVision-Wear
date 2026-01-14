package com.runvision.wear.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.*
import com.runvision.wear.R
import com.runvision.wear.data.RunningMetrics
import com.runvision.wear.ui.components.MetricItem
import com.runvision.wear.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun RunningScreen(
    metrics: RunningMetrics,
    isAmbient: Boolean = false,
    isPaused: Boolean = false,
    onPauseClick: () -> Unit,
    onStopClick: () -> Unit
) {
    if (isAmbient) {
        AmbientRunningScreen(metrics)
    } else {
        InteractiveRunningScreen(metrics, isPaused, onPauseClick, onStopClick)
    }
}

/**
 * Ambient Mode UI - simplified, low-power display
 * - Black background with dim white text
 * - No icons, no buttons (saves battery, prevents burn-in)
 * - Essential metrics only: time, HR, distance
 */
@Composable
private fun AmbientRunningScreen(metrics: RunningMetrics) {
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
            // Elapsed time (large, prominent)
            Text(
                text = metrics.elapsedFormatted,
                fontSize = 36.sp,
                fontWeight = FontWeight.Light,
                fontFamily = FontFamily.Monospace,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Heart Rate
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "♥",
                    fontSize = 18.sp,
                    color = Color(0xFFAAAAAA)  // Dim gray
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${metrics.heartRate}",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Normal,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Distance
            Text(
                text = "${metrics.distanceKmFormatted} km",
                fontSize = 18.sp,
                fontWeight = FontWeight.Light,
                color = Color(0xFFAAAAAA)  // Dim gray
            )
        }
    }
}

/**
 * Interactive Mode UI - full color, all controls
 */
@Composable
private fun InteractiveRunningScreen(
    metrics: RunningMetrics,
    isPaused: Boolean,
    onPauseClick: () -> Unit,
    onStopClick: () -> Unit
) {
    // Current time (updates with metrics)
    val currentTime = remember(metrics.elapsedSeconds) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Main content column - centered vertically
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .padding(bottom = 40.dp)  // Offset to account for buttons
        ) {
            // Top: Current Time + Elapsed Time (same size, both important)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Current time (white)
                Text(
                    text = currentTime,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                // Separator
                Text(
                    text = "│",
                    fontSize = 20.sp,
                    color = Color.Gray
                )
                // Elapsed time (green when running, yellow when paused)
                Text(
                    text = if (isPaused) "⏸ ${metrics.elapsedFormatted}" else metrics.elapsedFormatted,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isPaused) Color(0xFFFFEB3B) else Color(0xFF00FF00)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 2x2 Grid (rLens layout)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Row 1: Pace, Cadence
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    MetricItem(
                        icon = painterResource(R.drawable.ic_runner),
                        value = metrics.paceFormatted,
                        color = CyanPace
                    )
                    MetricItem(
                        icon = painterResource(R.drawable.ic_shoe),
                        value = "${metrics.cadence}",
                        color = GreenCadence
                    )
                }

                // Row 2: Distance, Heart Rate
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    MetricItem(
                        icon = painterResource(R.drawable.ic_route),
                        value = metrics.distanceKmFormatted,
                        color = OrangeDistance
                    )
                    MetricItem(
                        icon = painterResource(R.drawable.ic_heart),
                        value = "${metrics.heartRate}",
                        color = RedHeart
                    )
                }
            }
        }

        // Bottom Buttons - closer to content
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CompactButton(
                onClick = onPauseClick,
                colors = if (isPaused) {
                    ButtonDefaults.primaryButtonColors(backgroundColor = Color(0xFF4CAF50))
                } else {
                    ButtonDefaults.primaryButtonColors()
                }
            ) {
                Icon(
                    painter = painterResource(
                        if (isPaused) R.drawable.ic_play else R.drawable.ic_pause
                    ),
                    contentDescription = if (isPaused) "Resume" else "Pause"
                )
            }
            CompactButton(
                onClick = onStopClick,
                colors = ButtonDefaults.secondaryButtonColors()
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_stop),
                    contentDescription = "Stop"
                )
            }
        }
    }
}
