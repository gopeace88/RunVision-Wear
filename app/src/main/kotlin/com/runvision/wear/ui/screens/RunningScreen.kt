package com.runvision.wear.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.*
import com.runvision.wear.R
import com.runvision.wear.data.RunningMetrics
import com.runvision.wear.ui.components.MetricItem
import com.runvision.wear.ui.theme.*

@Composable
fun RunningScreen(
    metrics: RunningMetrics,
    onPauseClick: () -> Unit,
    onStopClick: () -> Unit
) {
    Scaffold(
        timeText = {
            TimeText(
                startLinearContent = {
                    Text(
                        text = metrics.elapsedFormatted,
                        fontSize = 14.sp,
                        color = Color.White
                    )
                }
            )
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            // 2x2 Grid (rLens layout)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Row 1: Pace, Cadence
                Row(
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
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
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
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

            // Bottom Buttons
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CompactButton(
                    onClick = onPauseClick
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_pause),
                        contentDescription = "Pause"
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
}
