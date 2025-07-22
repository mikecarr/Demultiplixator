// File: OSDComponents.kt - Crash-safe version
package net.mikecarr.demultiplixator

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun DraggableOSDElement(
    element: OSDElement,
    videoQualityInfo: VideoQualityInfo,
    networkStatusInfo: NetworkStatusInfo,
    onPositionChanged: (Position) -> Unit
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current

    // Get screen dimensions safely
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }

    // Define element dimensions
    val elementWidthPx = with(density) { 300.dp.toPx() }
    val elementHeightPx = with(density) { 100.dp.toPx() }

    // Calculate safe bounds
    val maxX = (screenWidthPx - elementWidthPx).coerceAtLeast(0f)
    val maxY = (screenHeightPx - elementHeightPx).coerceAtLeast(0f)

    // Ensure current position is within bounds
    val safeX = element.position.x.coerceIn(0f, maxX)
    val safeY = element.position.y.coerceIn(0f, maxY)

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    safeX.roundToInt(),
                    safeY.roundToInt()
                )
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    // Use the pre-calculated safe bounds
                    val newPosition = Position(
                        (safeX + change.position.x).coerceIn(0f, maxX),
                        (safeY + change.position.y).coerceIn(0f, maxY)
                    )
                    onPositionChanged(newPosition)
                }
            }
    ) {
        Surface(
            color = when (element.id) {
                "network_status" -> {
                    if (networkStatusInfo.isConnected) {
                        if (networkStatusInfo.isStreamActive) Color.Green.copy(alpha = 0.8f)
                        else Color(0xFFFF9800).copy(alpha = 0.8f) // Orange
                    } else {
                        Color.Red.copy(alpha = 0.8f)
                    }
                }
                else -> Color.Black.copy(alpha = 0.8f)
            },
            shape = RoundedCornerShape(8.dp)
        ) {
            when (element.id) {
                "video_quality" -> {
                    Text(
                        text = "📺 ${videoQualityInfo.resolution} ${videoQualityInfo.codec} ${videoQualityInfo.bitrate} ${videoQualityInfo.latency}",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp)
                    )
                }
                "fps_counter" -> {
                    Text(
                        text = "🎯 ${videoQualityInfo.fps} FPS",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp)
                    )
                }
                "bitrate" -> {
                    Text(
                        text = "📊 ${videoQualityInfo.bitrate}",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp)
                    )
                }
                "network_stats" -> {
                    Text(
                        text = "🌐 ${videoQualityInfo.latency} | Loss: ${String.format("%.1f", videoQualityInfo.packetLoss)}%",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp)
                    )
                }
                "network_status" -> {
                    Text(
                        text = if (networkStatusInfo.isConnected) {
                            if (networkStatusInfo.isStreamActive) {
                                "📹 Camera Connected\n📶 ${networkStatusInfo.ssid}\n🟢 Stream Active (${networkStatusInfo.selectedResolution})"
                            } else {
                                "📱 WiFi Connected\n📶 ${networkStatusInfo.ssid}\n🔍 Searching for stream..."
                            }
                        } else {
                            "❌ No WiFi Connection\nConnect to camera WiFi"
                        },
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }
    }
}