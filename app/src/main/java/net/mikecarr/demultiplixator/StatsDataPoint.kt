// File: StatsDataPoint.kt
package net.mikecarr.demultiplixator

data class StatsDataPoint(
    val timestamp: Long = System.currentTimeMillis(),
    val fps: Int,
    val bitrate: Float, // Store as a number for graphing
    val packetLoss: Float,
    val latency: Long // Store as a number for graphing
)