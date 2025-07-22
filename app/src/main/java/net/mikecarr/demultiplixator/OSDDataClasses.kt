// File: OSDDataClasses.kt
package net.mikecarr.demultiplixator

// Custom position class instead of using Compose Offset
data class Position(val x: Float, val y: Float)

data class Resolution(val name: String, val width: Int, val height: Int)

data class OSDElement(
    val id: String,
    val name: String,
    val isEnabled: Boolean,
    val position: Position,
    val data: String = ""
)

data class VideoQualityInfo(
    val codec: String = "Unknown",
    val resolution: String = "0x0",
    val bitrate: String = "0 Mbps",
    val fps: Int = 0,
    val latency: String = "0ms",
    val packetLoss: Float = 0f
)

data class NetworkStatusInfo(
    val isConnected: Boolean = false,
    val ssid: String = "",
    val isStreamActive: Boolean = false,
    val selectedResolution: String = ""
)