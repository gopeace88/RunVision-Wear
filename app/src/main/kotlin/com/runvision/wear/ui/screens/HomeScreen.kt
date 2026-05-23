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
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Text
import com.runvision.wear.BuildConfig
import com.runvision.wear.ble.RLensConnection

@Composable
fun HomeScreen(
    connectionState: RLensConnection.ConnectionState,
    cyclingSupported: Boolean = true,
    cyclingStartFailed: Boolean = false,
    onRunClick: () -> Unit,
    onCycleClick: () -> Unit
) {
    // 정적 화면: ScalingLazyColumn 제거 → 세로 중앙 정렬로 RunVision/모드버튼/상태 모두
    // 한 화면 fit, 시작 시 아래로 치우치는 문제 해결.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 8.dp)
    ) {
        Text(
            text = "RunVision",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onRunClick,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.primaryButtonColors()
            ) {
                Text(
                    text = "달리기",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Button(
                onClick = onCycleClick,
                enabled = cyclingSupported,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.secondaryButtonColors()
            ) {
                Text(
                    text = "자전거",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        val (statusText, statusColor) = when (cyclingHomeOverride(cyclingSupported, cyclingStartFailed)) {
            HomeOverride.UNSUPPORTED -> "자전거 미지원" to Color.Gray
            HomeOverride.START_FAILED -> "자전거 시작 실패" to Color(0xFFF44336)
            // HomeScreen entry에선 WAITING_GPS_LOCK 발생 안 함 (그 시점엔 CyclingScreen으로
            // 이미 navigate). 안전상 도달 시 진행 중 메시지로 처리.
            HomeOverride.WAITING_GPS_LOCK -> "GPS 검색 중.." to Color(0xFFFF9800)
            null -> when (connectionState) {
                RLensConnection.ConnectionState.CONNECTED -> "Connected" to Color(0xFF4CAF50)
                RLensConnection.ConnectionState.CONNECTING -> "Connecting.." to Color(0xFFFF9800)
                RLensConnection.ConnectionState.RECONNECTING -> "잠시끊김.." to Color(0xFFFF9800)
                RLensConnection.ConnectionState.SCANNING -> "Scanning.." to Color(0xFF2196F3)
                RLensConnection.ConnectionState.NOT_FOUND -> "Not Found" to Color(0xFFF44336)
                RLensConnection.ConnectionState.DISCONNECTED -> "READY" to Color.Gray
            }
        }
        Text(
            text = statusText,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = statusColor,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(6.dp))

        // 앱 버전 (build.gradle versionName 자동 동기화)
        Text(
            text = "v${BuildConfig.VERSION_NAME}",
            fontSize = 10.sp,
            color = Color.Gray,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
