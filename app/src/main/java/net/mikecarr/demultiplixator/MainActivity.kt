// File: MainActivity.kt
package net.mikecarr.demultiplixator

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.core.entry.ChartEntryModelProducer
import com.patrykandpatrick.vico.core.entry.entryOf
import kotlinx.coroutines.launch
import net.mikecarr.demultiplixator.ui.theme.DemultiplixatorTheme
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var hasStoragePermission = false

    private val videoDecoder = VideoDecoder()
    private val udpDataSource = UdpDataSource()

    private val requestMultiplePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        hasStoragePermission = allGranted
        if (allGranted) {
            Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Storage permissions required for recording", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // This single call will now correctly stop the recording (if any)
        // and release all muxer and codec resources without conflict.
        videoDecoder.release()
        udpDataSource.close()
        Log.d("MainActivity", "onDestroy: Resources released.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        hideSystemUI()
        checkAndRequestPermissions()

        setContent {
            DemultiplixatorTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    VideoStreamPlayer(
                        videoDecoder = videoDecoder,
                        udpDataSource = udpDataSource
                    )
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        if (permissionsToRequest.isNotEmpty()) {
            requestMultiplePermissions.launch(permissionsToRequest.toTypedArray())
        } else {
            hasStoragePermission = true
        }
    }

    fun hasStoragePermission(): Boolean = hasStoragePermission

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    fun getAppVersion(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "Unknown"
        }
    }
}



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoStreamPlayer(videoDecoder: VideoDecoder, udpDataSource: UdpDataSource) {
    val context = LocalContext.current
    val activity = context as MainActivity
    val haptics = LocalHapticFeedback.current

    var showAboutDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showOSDDialog by remember { mutableStateOf(false) }
    var showStatsSheet by remember { mutableStateOf(false) }
    var selectedResolution by remember { mutableStateOf(Resolution("1080p", 1920, 1080)) }
    val statsHistory = remember { mutableStateListOf<StatsDataPoint>() }
    var osdElements by remember {
        mutableStateOf(listOf(
            OSDElement("video_quality", "Video Quality", true, Position(50f, 50f)),
            OSDElement("fps_counter", "FPS Counter", true, Position(50f, 153f)),
            OSDElement("bitrate", "Bitrate Monitor", true, Position(50f, 256f)),
            OSDElement("network_stats", "Network Stats", true, Position(50f, 359f)),
            OSDElement("network_status", "Network Status", true, Position(50f, 462f))
        ))
    }
    val availableResolutions = listOf(
        Resolution("720p", 1280, 720),
        Resolution("1080p", 1920, 1080),
        Resolution("1440p", 2560, 1440),
        Resolution("4K", 3840, 2160)
    )

    val sheetState = rememberModalBottomSheetState()

    Box(modifier = Modifier.fillMaxSize()) {
        key(selectedResolution) {
            val coroutineScope = rememberCoroutineScope()
            var isRecording by remember { mutableStateOf(false) }
            var isStreamActive by remember { mutableStateOf(false) }
            var videoQualityInfo by remember { mutableStateOf(VideoQualityInfo()) }
            var networkStatusInfo by remember { mutableStateOf(NetworkStatusInfo()) }
            var lastPacketTimestamp by remember { mutableLongStateOf(0L) }
            var recordingTime by remember { mutableLongStateOf(0L) }

            LaunchedEffect(isRecording) {
                if (isRecording) {
                    val startTime = System.currentTimeMillis()
                    while (true) {
                        recordingTime = (System.currentTimeMillis() - startTime) / 1000
                        kotlinx.coroutines.delay(1000)
                    }
                }
            }

            videoDecoder.onResolutionChanged = { width, height ->
                val detected = availableResolutions.find { it.width == width && it.height == height }
                if (detected != null && detected != selectedResolution) {
                    selectedResolution = detected
                }
            }

            LaunchedEffect(isStreamActive, lastPacketTimestamp) {
                if (isStreamActive) {
                    while (true) {
                        if (System.currentTimeMillis() - lastPacketTimestamp > 2000L) {
                            isStreamActive = false
                            break
                        }
                        val netInfo = NetworkUtils.getNetworkInfo(context)
                        networkStatusInfo = NetworkStatusInfo(
                            isConnected = NetworkUtils.isOpenIPCNetwork(netInfo),
                            ssid = netInfo.ssid,
                            isStreamActive = isStreamActive,
                            selectedResolution = selectedResolution.name
                        )
                        videoQualityInfo = VideoQualityInfo(
                            codec = if (videoDecoder.isHevc()) "H265" else "H264",
                            resolution = videoDecoder.getCurrentResolution(),
                            bitrate = videoDecoder.getCurrentBitrate(),
                            fps = videoDecoder.getCurrentFPS(),
                            latency = videoDecoder.getLatency(),
                            packetLoss = videoDecoder.getPacketLoss()
                        )
                        statsHistory.add(StatsDataPoint(
                            fps = videoQualityInfo.fps,
                            bitrate = videoQualityInfo.bitrate.replace(" Mbps", "").toFloatOrNull() ?: 0f,
                            packetLoss = videoQualityInfo.packetLoss,
                            latency = videoQualityInfo.latency.replace("ms", "").toLongOrNull() ?: 0L
                        ))
                        while (statsHistory.size > 600) {
                            statsHistory.removeAt(0)
                        }
                        kotlinx.coroutines.delay(500)
                    }
                }
            }

            AndroidView(
                factory = { ctx ->
                    // Create an instance of our custom view
                    AspectRatioSurfaceView(ctx).apply {
                        // FIX #1: Set the initial aspect ratio right away.
                        // This prevents the view from starting with the wrong shape.
                        setVideoDimensions(selectedResolution.width, selectedResolution.height)

                        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                // The configure call still uses the user-selected resolution to start.
                                // It will be updated if the stream reports a different size.
                                videoDecoder.configure(holder.surface, selectedResolution.width, selectedResolution.height)
                                coroutineScope.launch {
                                    udpDataSource.listen(5600) { data, len ->
                                        lastPacketTimestamp = System.currentTimeMillis()
                                        if (!isStreamActive && len > 1) isStreamActive = true
                                        videoDecoder.decodeRtpPacket(data, len)
                                    }
                                }
                            }
                            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, h2: Int) {}
                            override fun surfaceDestroyed(h: SurfaceHolder) {}
                        })
                    }
                },
                update = { view ->
                    // This block now correctly handles updates if the stream's resolution
                    // is different from the user's selection.
                    val res = videoDecoder.getCurrentResolution().split("x")
                    if (res.size == 2) {
                        val streamWidth = res[0].toIntOrNull() ?: selectedResolution.width
                        val streamHeight = res[1].toIntOrNull() ?: selectedResolution.height
                        view.setVideoDimensions(streamWidth, streamHeight)
                    }
                },
                modifier = Modifier.align(Alignment.Center) // This is the fix

            )

            if (isRecording) {
                val minutes = recordingTime / 60
                val seconds = recordingTime % 60
                val timeString = String.format(Locale.US, "%02d:%02d", minutes, seconds)
                RecordingIndicator(timeString = timeString, modifier = Modifier.align(Alignment.TopStart).padding(16.dp))
            }

            osdElements.forEach { el ->
                if (el.isEnabled) {
                    DraggableOSDElement(
                        element = el,
                        videoQualityInfo = videoQualityInfo,
                        networkStatusInfo = networkStatusInfo,
                        onPositionChanged = { newPosition ->
                            // This lambda is now only called ONCE at the end of the drag
                            osdElements = osdElements.map {
                                if (it.id == el.id) it.copy(position = newPosition) else it
                            }
                        }
                    )
                }
            }

            Button(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (!activity.hasStoragePermission()) {
                        Toast.makeText(context, "Storage permission required", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    if (isRecording) {
                        videoDecoder.stopRecording()
                        isRecording = false
                    } else {
                        if (videoDecoder.startRecording(context)) {
                            isRecording = true
                            recordingTime = 0L
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = if (isRecording) Color.Red else MaterialTheme.colorScheme.primary),
                modifier = Modifier.align(Alignment.CenterEnd).padding(16.dp)
            ) {
                val minutes = recordingTime / 60
                val seconds = recordingTime % 60
                val timeString = String.format(Locale.US, "%02d:%02d", minutes, seconds)
                Text(text = if (isRecording) "Stop $timeString" else "Record")
            }

            if (!isStreamActive && lastPacketTimestamp > 0) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Warning, "Warning", tint = Color.Yellow, modifier = Modifier.size(64.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Connection Lost", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                        Text("Searching for stream...", style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.7f))
                    }
                }
            }
        }

        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val osdButtonColor = if (showOSDDialog) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.6f)
            IconButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); showOSDDialog = true }, modifier = Modifier.size(48.dp).clip(CircleShape).background(osdButtonColor)) { Icon(Icons.Default.Add, "OSD", tint = Color.White) }
            val settingsButtonColor = if (showSettingsDialog) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.6f)
            IconButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); showSettingsDialog = true }, modifier = Modifier.size(48.dp).clip(CircleShape).background(settingsButtonColor)) { Icon(Icons.Default.Settings, "Settings", tint = Color.White) }
            val aboutButtonColor = if (showAboutDialog) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.6f)
            IconButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); showAboutDialog = true }, modifier = Modifier.size(48.dp).clip(CircleShape).background(aboutButtonColor)) { Icon(Icons.Default.Info, "About", tint = Color.White) }
            val statsButtonColor = if (showStatsSheet) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.6f)
            IconButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); showStatsSheet = true }, modifier = Modifier.size(48.dp).clip(CircleShape).background(statsButtonColor)) { Icon(Icons.Default.Analytics, "Stats", tint = Color.White) }
        }
    }

    if (showStatsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showStatsSheet = false },
            sheetState = sheetState
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Real-time Statistics", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 16.dp))
                StatsGraph(title = "Frames Per Second (FPS)", data = statsHistory.mapIndexed { index, dataPoint -> index to dataPoint.fps })
                StatsGraph(title = "Bitrate (Mbps)", data = statsHistory.mapIndexed { index, dataPoint -> index to dataPoint.bitrate })
                StatsGraph(title = "Packet Loss (%)", data = statsHistory.mapIndexed { index, dataPoint -> index to dataPoint.packetLoss })
            }
        }
    }

    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false }, appVersion = activity.getAppVersion())
    }
    if (showSettingsDialog) {
        SettingsDialog(
            selectedResolution = selectedResolution,
            availableResolutions = availableResolutions,
            onResolutionSelected = { newRes -> selectedResolution = newRes; showSettingsDialog = false },
            onDismiss = { showSettingsDialog = false }
        )
    }
    if (showOSDDialog) {
        OSDSettingsDialog(
            osdElements = osdElements,
            onOSDElementsChanged = { newElements -> osdElements = newElements },
            onDismiss = { showOSDDialog = false }
        )
    }
}

@Composable
fun RecordingIndicator(timeString: String, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "BlinkingDot")
    val blinkingAlpha by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "BlinkingDotAlpha"
    )
    Row(
        modifier = modifier.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(Color.Red.copy(alpha = blinkingAlpha)))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = "REC $timeString", color = Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun StatsGraph(title: String, data: List<Pair<Number, Number>>, modifier: Modifier = Modifier) {
    val modelProducer = remember { ChartEntryModelProducer() }
    LaunchedEffect(data) {
        modelProducer.setEntries(data.map { (x, y) -> entryOf(x, y) })
    }
    Column(modifier = modifier.padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        if (data.isNotEmpty()) {
            Chart(
                chart = lineChart(),
                chartModelProducer = modelProducer,
                startAxis = rememberStartAxis(),
                bottomAxis = rememberBottomAxis(),
                modifier = Modifier.height(150.dp)
            )
        } else {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.height(150.dp).fillMaxWidth()) {
                Text("Waiting for data...", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
            }
        }
    }


}