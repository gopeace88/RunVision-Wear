package com.runvision.wear.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
    onStopClick: () -> Unit,
    onScreenTouch: () -> Unit = {}
) {
    if (isAmbient) {
        AmbientRunningScreen(metrics)
    } else {
        InteractiveRunningScreen(metrics, isPaused, onPauseClick, onStopClick, onScreenTouch)
    }
}

/**
 * Ambient Mode UI - simplified, low-power display
 */
@Composable
private fun AmbientRunningScreen(metrics: RunningMetrics) {
    val scrollState = rememberScrollState()

    Scaffold(
        positionIndicator = {
            PositionIndicator(scrollState = scrollState)
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .verticalScroll(scrollState)
        ) {
            Text(
                text = metrics.elapsedFormatted,
                fontSize = 36.sp,
                fontWeight = FontWeight.Light,
                fontFamily = FontFamily.Monospace,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "♥",
                    fontSize = 16.sp,
                    color = Color(0xFFAAAAAA),
                    maxLines = 1
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "${metrics.heartRate}",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Normal,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "${metrics.distanceKmFormatted} km",
                fontSize = 16.sp,
                fontWeight = FontWeight.Light,
                color = Color(0xFFAAAAAA),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }
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
    onStopClick: () -> Unit,
    onScreenTouch: () -> Unit
) {
    val currentTime = remember(metrics.elapsedSeconds) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onScreenTouch() })
            }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .padding(horizontal = 12.dp)
                .padding(bottom = 44.dp)
        ) {
            // Time row: each item gets equal weight so they can't overflow
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = currentTime,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End
                )
                Text(
                    text = " │ ",
                    fontSize = 18.sp,
                    color = Color.Gray,
                    maxLines = 1
                )
                Text(
                    text = if (isPaused) "⏸ ${metrics.elapsedFormatted}" else metrics.elapsedFormatted,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isPaused) Color(0xFFFFEB3B) else Color(0xFF00FF00),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Start
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 2x2 Grid — each row fills full width, each MetricItem gets half
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MetricItem(
                        icon = painterResource(R.drawable.ic_runner),
                        value = metrics.paceFormatted,
                        color = CyanPace,
                        modifier = Modifier.weight(1f)
                    )
                    MetricItem(
                        icon = painterResource(R.drawable.ic_shoe),
                        value = "${metrics.cadence}",
                        color = GreenCadence,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MetricItem(
                        icon = painterResource(R.drawable.ic_route),
                        value = metrics.distanceKmFormatted,
                        color = OrangeDistance,
                        modifier = Modifier.weight(1f)
                    )
                    MetricItem(
                        icon = painterResource(R.drawable.ic_heart),
                        value = "${metrics.heartRate}",
                        color = RedHeart,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Bottom Buttons
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
