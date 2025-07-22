// File: MainActivity.kt
package net.mikecarr.demultiplixator

import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import net.mikecarr.demultiplixator.ui.theme.DemultiplixatorTheme

class MainActivity : ComponentActivity() {

    private var hasStoragePermission = false

    private val requestMultiplePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        hasStoragePermission = allGranted

        if (allGranted) {
            Log.d("MainActivity", "All storage permissions granted")
            Toast.makeText(this, "Permissions granted - recording available", Toast.LENGTH_SHORT).show()
        } else {
            Log.w("MainActivity", "Some storage permissions denied")
            Toast.makeText(this, "Storage permissions required for recording", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Force landscape orientation
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        // Call the new function to enable full-screen mode
        hideSystemUI()

        // Check and request appropriate permissions based on Android version
        checkAndRequestPermissions()

        setContent {
            DemultiplixatorTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    VideoStreamPlayer()
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ - use new media permissions
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10-12 - use scoped storage with READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        } else {
            // Android 9 and below - use legacy storage permissions
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            Log.d("MainActivity", "Requesting permissions: $permissionsToRequest")
            requestMultiplePermissions.launch(permissionsToRequest.toTypedArray())
        } else {
            hasStoragePermission = true
            Log.d("MainActivity", "All required permissions already granted")
        }
    }

    fun hasStoragePermission(): Boolean = hasStoragePermission

    private fun hideSystemUI() {
        // Enable support for drawing behind the system bars
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val controller = WindowInsetsControllerCompat(window, window.decorView)

        // Hide the status bar and the navigation bar
        controller.hide(WindowInsetsCompat.Type.systemBars())

        // Configure the system bars to be revealed with a swipe gesture
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    fun getAppVersion(): String {
        return try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                "${packageInfo.versionName} (${packageInfo.longVersionCode})"
            } else {
                @Suppress("DEPRECATION")
                "${packageInfo.versionName} (${packageInfo.versionCode})"
            }
        } catch (e: Exception) {
            "Unknown"
        }
    }
}

@Composable
fun VideoStreamPlayer() {
    val context = LocalContext.current
    val activity = context as MainActivity

    // --- STATES THAT MUST PERSIST ACROSS RESOLUTION CHANGES ---
    var showAboutDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showOSDDialog by remember { mutableStateOf(false) }
    var selectedResolution by remember { mutableStateOf(Resolution("1080p", 1920, 1080)) }

    // default all osd elements disabled
    var osdElements by remember {
        mutableStateOf(listOf(
            OSDElement("video_quality", "Video Quality", false, Position(50f, 50f)),
            OSDElement("fps_counter", "FPS Counter", false, Position(50f, 120f)),
            OSDElement("bitrate", "Bitrate Monitor", false, Position(50f, 190f)),
            OSDElement("network_stats", "Network Stats", false, Position(50f, 260f)),
            OSDElement("network_status", "Network Status", false, Position(16f, 16f))
        ))
    }

    val availableResolutions = listOf(
        Resolution("720p", 1280, 720),
        Resolution("1080p", 1920, 1080),
        Resolution("1440p", 2560, 1440),
        Resolution("4K", 3840, 2160)
    )

    // The main container
    Box(modifier = Modifier.fillMaxSize()) {

        // --- THE KEY BLOCK ---
        // Everything inside here is now tied to the lifecycle of the selectedResolution.
        // It will be completely destroyed and recreated when the key changes.
        key(selectedResolution) {

            // --- STATES AND OBJECTS TIED TO THE VIDEO STREAM ---
            // These are now *inside* the key block.
            val coroutineScope = rememberCoroutineScope()
            val videoDecoder = remember { VideoDecoder() }
            val udpDataSource = remember { UdpDataSource() }

            var isRecording by remember { mutableStateOf(false) }
            var networkInfo by remember { mutableStateOf<NetworkUtils.NetworkInfo?>(null) }
            var isStreamActive by remember { mutableStateOf(false) }
            var videoQualityInfo by remember { mutableStateOf(VideoQualityInfo()) }
            var networkStatusInfo by remember { mutableStateOf(NetworkStatusInfo()) }

            var lastPacketTimestamp by remember { mutableStateOf(0L) }

            // --- THIS IS THE NEW CODE BLOCK YOU ASKED ABOUT ---
            // It sets up the listener that will be called by the VideoDecoder.
            videoDecoder.onResolutionChanged = { width, height ->
                val detectedResolution = availableResolutions.find { it.width == width && it.height == height }
                if (detectedResolution != null && detectedResolution != selectedResolution) {
                    Log.d("VideoStreamPlayer", "Stream resolution detected: ${detectedResolution.name}. Updating UI.")
                    // This state change will trigger the key block to recompose with the correct resolution.
                    selectedResolution = detectedResolution
                }
            }
            // --- END OF NEW CODE BLOCK ---

            // This effect now correctly disposes of the decoder and data source
            // when the key changes.
            DisposableEffect(Unit) {
                onDispose {
                    Log.d("VideoStreamPlayer", "Disposing resources for ${selectedResolution.name}")
                    videoDecoder.release()
                    udpDataSource.close()
                }
            }

            // Stats polling logic remains the same, but is now inside the key block.
            LaunchedEffect(isStreamActive, lastPacketTimestamp) {
                if (isStreamActive) {
                    while (true) {

                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastPacketTimestamp > 2000L) { // 2-second timeout
                            Log.w("VideoStreamPlayer", "Stream timed out. No packets received.")
                            isStreamActive = false
                            break // Exit the loop
                        }

                        val currentNetworkInfo = NetworkUtils.getNetworkInfo(context)
                        networkInfo = currentNetworkInfo // Update the local state
                        networkStatusInfo = NetworkStatusInfo(
                            isConnected = currentNetworkInfo?.let { NetworkUtils.isOpenIPCNetwork(it) } ?: false,
                            ssid = currentNetworkInfo?.ssid ?: "",
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
                        kotlinx.coroutines.delay(500)
                    }
                }
            }

            // The AndroidView no longer needs the complex `update` block.
            // It relies on the `key` to recreate it.
            AndroidView(
                factory = { ctx ->
                    Log.d("VideoStreamPlayer", "AndroidView factory created for ${selectedResolution.name}")
                    SurfaceView(ctx).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                Log.d("VideoStreamPlayer", "Surface created for ${selectedResolution.name}")
                                holder.setFixedSize(selectedResolution.width, selectedResolution.height)
                                videoDecoder.configure(holder.surface, selectedResolution.width, selectedResolution.height)

                                coroutineScope.launch {
                                    udpDataSource.listen(5600) { packetData, packetLength ->

                                        lastPacketTimestamp = System.currentTimeMillis() // Mark that we received a packet

                                        if (!isStreamActive && packetLength > 1) {
                                            isStreamActive = true
                                        }
                                        videoDecoder.decodeRtpPacket(packetData, packetLength)
                                    }
                                }
                            }
                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                Log.d("VideoStreamPlayer", "Surface destroyed for ${selectedResolution.name}")
                            }
                        })
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // OSD elements are now rendered inside the key block.
            osdElements.forEach { element ->
                if (element.isEnabled) {
                    DraggableOSDElement(
                        element = element,
                        videoQualityInfo = videoQualityInfo,
                        networkStatusInfo = networkStatusInfo,
                        onPositionChanged = { newPosition ->
                            osdElements = osdElements.map {
                                if (it.id == element.id) it.copy(position = newPosition) else it
                            }
                        }
                    )
                }
            }

            // The record button now correctly references the decoder inside the key block.
            Button(
                onClick = {
                    if (!activity.hasStoragePermission()) {
                        Toast.makeText(context, "Storage permission required", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    if (isRecording) {
                        videoDecoder.stopRecording()
                    } else {
                        videoDecoder.startRecording(context)
                    }
                    isRecording = videoDecoder.isRecording()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording) Color.Red else MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
            ) {
                Text(text = if (isRecording) "Stop Recording" else "Record")
            }

            if (!isStreamActive && lastPacketTimestamp > 0) { // Show only after the stream has started once
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Warning",
                            tint = Color.Yellow,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Connection Lost",
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.White
                        )
                        Text(
                            text = "Searching for stream...",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        } // --- END OF KEY BLOCK ---

        // --- UI CONTROLS ARE OUTSIDE THE KEY BLOCK ---
        // This ensures they don't get recreated and remember their state (like being open).
        // Top Menu Bar
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // OSD Button
            IconButton(
                onClick = { showOSDDialog = true },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.6f))
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "OSD Settings",
                    tint = Color.White
                )
            }

            // Settings Button
            IconButton(
                onClick = { showSettingsDialog = true },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.6f))
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = Color.White
                )
            }

            // About Button
            IconButton(
                onClick = { showAboutDialog = true },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.6f))
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = "About",
                    tint = Color.White
                )
            }
        }
    }

    // --- DIALOGS ARE OUTSIDE THE KEY BLOCK ---
    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false }, appVersion = activity.getAppVersion())
    }

    if (showSettingsDialog) {
        SettingsDialog(
            selectedResolution = selectedResolution,
            availableResolutions = availableResolutions,
            onResolutionSelected = { newResolution ->
                selectedResolution = newResolution // This triggers the key change
                showSettingsDialog = false
            },
            onDismiss = { showSettingsDialog = false }
        )
    }

    if (showOSDDialog) {
        OSDSettingsDialog(
            osdElements = osdElements,
            onOSDElementsChanged = { newElements ->
                osdElements = newElements
            },
            onDismiss = { showOSDDialog = false }
        )
    }
}