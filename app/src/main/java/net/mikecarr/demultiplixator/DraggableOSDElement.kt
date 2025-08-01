// File: DraggableOSDElement.kt
package net.mikecarr.demultiplixator

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
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
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .offset { IntOffset(element.position.x.roundToInt(), element.position.y.roundToInt()) }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = {
                        // Drag is finished, update the state in the parent
                        onPositionChanged(Position(element.position.x + offset.x, element.position.y + offset.y))
                        // Reset the local offset
                        offset = Offset.Zero
                    }
                ) { change, dragAmount ->
                    change.consume()
                    // Just update the local offset during the drag
                    offset += dragAmount
                }
            }
    ) {
        Surface(
            modifier = Modifier.offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) },
            color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.7f),
            shape = RoundedCornerShape(8.dp)
        ) {
            // This is the actual OSD content
            Box(modifier = Modifier.padding(8.dp)) {
                when (element.id) {
                    "video_quality" -> Text(
                        text = "📺 ${videoQualityInfo.resolution} ${videoQualityInfo.codec} ${videoQualityInfo.bitrate} ${videoQualityInfo.latency}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    "fps_counter" -> Text(
                        text = "🎯 ${videoQualityInfo.fps} FPS",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    "bitrate" -> Text(
                        text = "📊 ${videoQualityInfo.bitrate}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    "network_stats" -> Text(
                        text = "🌐 ${videoQualityInfo.latency} | Loss: ${String.format("%.1f%%", videoQualityInfo.packetLoss)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    "network_status" -> {
                        val statusText = if (networkStatusInfo.isConnected) {
                            if (networkStatusInfo.isStreamActive) "🟢 Stream Active (${networkStatusInfo.selectedResolution})" else "🟠 Searching..."
                        } else {
                            "🔴 No Connection"
                        }
                        Text(
                            text = "📡 $statusText",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }
    }
}