// File: VideoDecoder.kt
package net.mikecarr.demultiplixator

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VideoDecoder {

    // Lock ONLY for MediaCodec state changes (configure, release, decode)
    private val lock = Any()
    private var mediaCodec: MediaCodec? = null

    // --- State for OSD and stream detection ---
    @Volatile private var currentStreamWidth = 0
    @Volatile private var currentStreamHeight = 0
    var onResolutionChanged: ((width: Int, height: Int) -> Unit)? = null

    // --- State for statistics (marked Volatile for thread safety) ---
    @Volatile private var packetsReceived = 0L
    @Volatile private var packetsLost = 0L
    private var lastSequenceNumber = -1

    // --- Properties for Bitrate Calculation ---
    private val bitrateCalculationInterval = 2000L // a 2-second window
    @Volatile private var bitrateBytesReceived: Long = 0
    @Volatile private var bitrateLastTimestamp: Long = System.currentTimeMillis()
    private var lastBitrateString = "--- Mbps"

    private val TAG = "VideoDecoder"
    private val NAL_START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)
    private val frameAssemblyBuffer = ByteArray(1048576)
    private var frameAssemblyBufferPtr = 0

    // --- General State Management ---
    private var isHevc = true
    private var surface: Surface? = null
    private var width: Int = 0
    private var height: Int = 0
    private var hasVps = false
    private var hasSps = false
    private var hasPps = false

    // --- Recording state ---
    private var mediaMuxer: MediaMuxer? = null
    private var videoTrackIndex: Int = -1
    @Volatile private var isRecording = false
    private var frameCount: Long = 0L
    private var vpsData: ByteArray? = null
    private var spsData: ByteArray? = null
    private var ppsData: ByteArray? = null
    private var rtpTimestampBase: Long = 0L
    private var isFirstRecordedFrame = true
    private var lastPresentationTimeUs: Long = 0L

    // --- FPS estimation ---
    private var frameTimestamps = mutableListOf<Long>()
    @Volatile private var estimatedFPS = 30L

    // --- Public Getters (NON-SYNCHRONIZED) ---
    fun isHevc(): Boolean = isHevc
    fun getCurrentResolution(): String = if (currentStreamWidth > 0) "${currentStreamWidth}x${currentStreamHeight}" else "${width}x${height}"
    fun getCurrentBitrate(): String {
        val currentTime = System.currentTimeMillis()
        val elapsedTime = currentTime - bitrateLastTimestamp
        if (elapsedTime >= bitrateCalculationInterval) {
            val bitrateMbps = (bitrateBytesReceived * 8.0) / (elapsedTime / 1000.0) / 1_000_000.0
            bitrateBytesReceived = 0
            bitrateLastTimestamp = currentTime
            lastBitrateString = String.format("%.1f Mbps", bitrateMbps)
        }
        return lastBitrateString
    }
    fun getCurrentFPS(): Int = estimatedFPS.toInt()
    fun getLatency(): String = "0ms"
    fun getPacketLoss(): Float {
        val received = packetsReceived
        val lost = packetsLost
        if ((received + lost) == 0L) return 0f
        return (lost * 100f) / (received + lost)
    }
    fun isRecording(): Boolean = isRecording

    // --- Internal and State-Changing Methods (SYNCHRONIZED) ---
    private fun isConfigured(): Boolean = if (isHevc) hasVps && hasSps && hasPps else hasSps && hasPps

    private fun updatePacketStats(rtpPacket: ByteArray) {
        if (rtpPacket.size < 4) return
        bitrateBytesReceived += rtpPacket.size.toLong()
        packetsReceived++
        val sequenceNumber = ((rtpPacket[2].toInt() and 0xFF) shl 8) or (rtpPacket[3].toInt() and 0xFF)
        if (lastSequenceNumber != -1) {
            val expectedSequence = (lastSequenceNumber + 1) and 0xFFFF
            if (sequenceNumber != expectedSequence) {
                val lost = if (sequenceNumber > expectedSequence) (sequenceNumber - expectedSequence) else (0x10000 - expectedSequence) + sequenceNumber
                packetsLost += lost.toLong()
            }
        }
        lastSequenceNumber = sequenceNumber
    }

    private fun calculateOptimalBitrate(width: Int, height: Int, fps: Int): Int {
        val pixels = width * height
        // Quality factor: bits per pixel. Higher value means more bits per pixel.
        val bitsPerPixel = when {
            pixels >= 3840 * 2160 -> 0.06f // 4K
            pixels >= 2560 * 1440 -> 0.07f // 1440p
            pixels >= 1920 * 1080 -> 0.10f // 1080p
            else -> 0.12f                 // 720p and below
        }

        // HEVC is about 40% more efficient than AVC (H.264)
        val codecEfficiencyFactor = if (isHevc) 0.6 else 1.0

        val calculatedBitrate = (pixels * fps * bitsPerPixel * codecEfficiencyFactor).toInt()

        // Enforce a reasonable minimum and maximum
        val minBitrate = 2_000_000  // 2 Mbps
        val maxBitrate = 40_000_000 // 40 Mbps

        return calculatedBitrate.coerceIn(minBitrate, maxBitrate)
    }

    fun startRecording(context: Context): Boolean {
        synchronized(lock) {
            Log.d(TAG, "startRecording() called.")
            if (isRecording) {
                Log.w(TAG, "Cannot start recording: Already recording.")
                return false
            }
            if (mediaCodec == null) {
                Log.w(TAG, "Cannot start recording: MediaCodec is null.")
                return false
            }
            if (!isConfigured()) {
                Log.w(TAG, "Cannot start recording: Codec is not configured (missing SPS/PPS/VPS).")
                return false
            }
            try {
                Log.d(TAG, "Proceeding to create MediaMuxer.")
                val fileName = "REC_${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())}.mp4"
                val mediaFormat = createMediaFormat()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resolver = context.contentResolver
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/Demultiplixator")
                    }
                    val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues) ?: throw IOException("Failed MediaStore entry.")
                    resolver.openFileDescriptor(uri, "w")?.use { pfd ->
                        mediaMuxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    }
                } else {
                    val moviesDir = File(context.getExternalFilesDir(null), "Movies").apply { mkdirs() }
                    val outputFile = File(moviesDir, fileName)
                    mediaMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                }
                Log.d(TAG, "MediaMuxer created. Adding track.")
                videoTrackIndex = mediaMuxer!!.addTrack(mediaFormat)
                Log.d(TAG, "Track added. Starting MediaMuxer.")
                mediaMuxer!!.start()
                Log.d(TAG, "MediaMuxer started successfully.")
                isRecording = true
                frameCount = 0L
                isFirstRecordedFrame = true
                rtpTimestampBase = 0L
                lastPresentationTimeUs = 0L
                Log.d(TAG, "Recording state is now ON for file: $fileName")
                return true
            } catch (e: Exception) {
                // Log the specific exception that occurred
                Log.e(TAG, "CRITICAL: Error starting recording. This is a likely cause of failure.", e)
                mediaMuxer?.release()
                mediaMuxer = null
                return false
            }
        }
    }

    private fun createMediaFormat(): MediaFormat {
        val recordWidth = if (currentStreamWidth > 0) currentStreamWidth else width
        val recordHeight = if (currentStreamHeight > 0) currentStreamHeight else height
        val mime = if (isHevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val fps = getEstimatedFPS().toInt().coerceIn(15, 60)

        Log.d(TAG, "Creating MediaFormat for recording: ${recordWidth}x${recordHeight} @ ${fps}fps, Codec: $mime")

        return MediaFormat.createVideoFormat(mime, recordWidth, recordHeight).apply {
            // 1. Calculate and set a dynamic bitrate
            val targetBitrate = calculateOptimalBitrate(recordWidth, recordHeight, fps)
            setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
            Log.d(TAG, "Setting recording bitrate to: $targetBitrate")

            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1-second I-frame interval for better seeking
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)

            // 2. Attempt to use a higher quality profile
            if (isHevc) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
                // Tier can be set for quality vs bitrate tradeoff if needed
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41)
            } else {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel42)
            }

            // 3. Set bitrate mode to Variable if possible
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                try {
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                } catch (e: Exception) {
                    Log.w(TAG, "VBR mode not supported, using default.")
                }
            }

            // Add the codec-specific data (SPS/PPS/VPS) needed to initialize the muxer
            if (isHevc) {
                vpsData?.let { vps ->
                    spsData?.let { sps ->
                        ppsData?.let { pps ->
                            val csd = ByteBuffer.allocate(vps.size + sps.size + pps.size)
                            csd.put(vps).put(sps).put(pps).flip()
                            setByteBuffer("csd-0", csd)
                        }
                    }
                }
            } else {
                spsData?.let { setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
                ppsData?.let { setByteBuffer("csd-1", ByteBuffer.wrap(it)) }
            }
        }
    }

    fun stopRecording() {
        synchronized(lock) {
            Log.d(TAG, "stopRecording() called. Current recording state: $isRecording")
            if (!isRecording) return
            isRecording = false // Set state to false immediately to prevent new frames from being written
            Log.d(TAG, "Recording state is now OFF.")
            try {
                if (mediaMuxer != null) {
                    Log.d(TAG, "Attempting to call mediaMuxer.stop()...")
                    mediaMuxer?.stop()
                    Log.d(TAG, "mediaMuxer.stop() completed successfully.")

                    Log.d(TAG, "Attempting to call mediaMuxer.release()...")
                    // mediaMuxer?.release()
                    Log.d(TAG, "mediaMuxer.release() completed successfully.")
                    Log.i(TAG, "File should now be saved and playable.")
                } else {
                    Log.w(TAG, "mediaMuxer was null when stopRecording() was called.")
                }
            } catch (e: Exception) {
                // This is the most important log message to look for!
                Log.e(TAG, "CRITICAL: Error during mediaMuxer.stop() or .release(). The file is likely corrupt.", e)
            } finally {
                mediaMuxer = null
                videoTrackIndex = -1
                Log.d(TAG, "Muxer resources set to null.")
            }
        }
    }


    fun setCodecType(isHevcStream: Boolean) = synchronized(lock) {
        if (this.isHevc != isHevcStream || mediaCodec == null) {
            Log.d(TAG, "Codec changing to ${if (isHevcStream) "H265" else "H264"}. Re-initializing.")
            val currentSurface = surface
            val currentWidth = width
            val currentHeight = height

            release()

            this.isHevc = isHevcStream
            hasVps = false; hasSps = false; hasPps = false
            vpsData = null; spsData = null; ppsData = null

            if (currentSurface != null && currentWidth > 0 && currentHeight > 0) {
                configure(currentSurface, currentWidth, currentHeight)
            }
        }
    }

    fun configure(surface: Surface, width: Int, height: Int) = synchronized(lock) {
        Log.d(TAG, "configure() called for ${width}x${height}.")
        if (mediaCodec != null) {
            Log.w(TAG, "Configure called on an already-configured codec. Releasing first.")
            // If configure is called again, it means we need a fresh start.
            release()
        }
        this.surface = surface
        this.width = width
        this.height = height
        try {
            val mimeType = if (isHevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            val format = MediaFormat.createVideoFormat(mimeType, width, height)
            Log.d(TAG, "Creating decoder for $mimeType")
            mediaCodec = MediaCodec.createDecoderByType(mimeType).apply {
                Log.d(TAG, "Calling mediaCodec.configure()")
                configure(format, surface, null, 0)
                Log.d(TAG, "Calling mediaCodec.start()")
                start()
            }
            Log.i(TAG, "MediaCodec configured and started successfully for $mimeType")
        } catch (e: Exception) {
            Log.e(TAG, "CRITICAL: Error configuring MediaCodec", e)
            mediaCodec = null
        }
    }

    fun decodeRtpPacket(rtpPacket: ByteArray, length: Int) {

        updatePacketStats(rtpPacket)

        if (length <= 12) return

        // --- FIX: Extract the RTP Timestamp from the packet header ---
        val rtpTimestamp = if (length >= 8) {
            ((rtpPacket[4].toInt() and 0xFF) shl 24) or
                    ((rtpPacket[5].toInt() and 0xFF) shl 16) or
                    ((rtpPacket[6].toInt() and 0xFF) shl 8) or
                    (rtpPacket[7].toInt() and 0xFF)
        } else {
            0
        }.toLong() and 0xFFFFFFFFL // Ensure it's treated as an unsigned 32-bit integer

        val nalUnitHeaderByte1 = rtpPacket[12]
        val nalUnitType = if (isHevc) {
            (nalUnitHeaderByte1.toInt() shr 1) and 0x3F
        } else {
            nalUnitHeaderByte1.toInt() and 0x1F
        }

        val isSps = (isHevc && nalUnitType == 33) || (!isHevc && nalUnitType == 7)
        val isPps = (isHevc && nalUnitType == 34) || (!isHevc && nalUnitType == 8)
        val isVps = isHevc && nalUnitType == 32

//        if (!isSps && !isPps && !isVps) {
//            updateFPSEstimate()
//        }

        if (isSps || isPps || isVps) {
            val payload = rtpPacket.sliceArray(12 until length)
            val nalUnit = NAL_START_CODE + payload
            when {
                isVps -> { vpsData = nalUnit; hasVps = true }
                isSps -> { spsData = nalUnit; hasSps = true }
                isPps -> { ppsData = nalUnit; hasPps = true }
            }
            // Pass the timestamp for config frames too
            decode(nalUnit, isConfigFrame = true, rtpTimestamp = rtpTimestamp)
            return
        }

        if (!isConfigured()) {
            return
        }

        val isFragment = (isHevc && nalUnitType == 49) || (!isHevc && nalUnitType == 28)
        if (isFragment) {
            val fuHeaderIndex = if (isHevc) 14 else 13
            if (length <= fuHeaderIndex) return

            val fuHeader = rtpPacket[fuHeaderIndex]
            val isStart = (fuHeader.toInt() and 0x80) != 0
            val isEnd = (fuHeader.toInt() and 0x40) != 0

            if (isStart) {
                frameAssemblyBufferPtr = 0
                System.arraycopy(NAL_START_CODE, 0, frameAssemblyBuffer, 0, NAL_START_CODE.size)
                frameAssemblyBufferPtr += NAL_START_CODE.size
                if (isHevc) {
                    val originalNalType = fuHeader.toInt() and 0x3F
                    frameAssemblyBuffer[frameAssemblyBufferPtr++] = ((nalUnitHeaderByte1.toInt() and 0x81) or (originalNalType shl 1)).toByte()
                    frameAssemblyBuffer[frameAssemblyBufferPtr++] = rtpPacket[13]
                } else {
                    val originalNalType = fuHeader.toInt() and 0x1F
                    frameAssemblyBuffer[frameAssemblyBufferPtr++] = (nalUnitHeaderByte1.toInt() and 0xE0 or originalNalType).toByte()
                }
            }

            val payloadDataOffset = fuHeaderIndex + 1
            if (frameAssemblyBufferPtr + (length - payloadDataOffset) <= frameAssemblyBuffer.size) {
                System.arraycopy(rtpPacket, payloadDataOffset, frameAssemblyBuffer, frameAssemblyBufferPtr, length - payloadDataOffset)
                frameAssemblyBufferPtr += length - payloadDataOffset
            } else {
                Log.w(TAG, "Frame assembly buffer overflow!")
                return
            }

            if (isEnd) {
                // Pass the timestamp for the completed frame
                decode(frameAssemblyBuffer.copyOf(frameAssemblyBufferPtr), rtpTimestamp = rtpTimestamp)
            }
        } else {
            val payload = rtpPacket.sliceArray(12 until length)
            // Pass the timestamp for single NAL unit frames
            decode(NAL_START_CODE + payload, rtpTimestamp = rtpTimestamp)
        }
    }

    private fun decode(data: ByteArray, isConfigFrame: Boolean = false, rtpTimestamp: Long = 0L) {
        synchronized(lock) {
            if (mediaCodec == null) return@synchronized

            if (!isConfigFrame) {
                updateFPSEstimate()
            }

            if (isRecording && !isConfigFrame && videoTrackIndex != -1) {
                val bufferInfo = MediaCodec.BufferInfo()
                bufferInfo.size = data.size
                bufferInfo.flags = if (isKeyFrame(data)) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0

                var presentationTimeUs = if (rtpTimestamp > 0) {
                    if (isFirstRecordedFrame) {
                        rtpTimestampBase = rtpTimestamp
                        isFirstRecordedFrame = false
                        0L
                    } else {
                        ((rtpTimestamp - rtpTimestampBase) * 1000000L) / 90000L
                    }
                } else {
                    (frameCount * 1000000L) / getEstimatedFPS()
                }

                if (presentationTimeUs <= lastPresentationTimeUs) {
                    presentationTimeUs = lastPresentationTimeUs + 1
                }
                bufferInfo.presentationTimeUs = presentationTimeUs
                lastPresentationTimeUs = presentationTimeUs

                frameCount++
                try {
                    // This is where timestamp errors would occur.
                    mediaMuxer?.writeSampleData(videoTrackIndex, ByteBuffer.wrap(data), bufferInfo)
                } catch (e: Exception) {
                    // If this happens, the muxer is now in a bad state and stop() will fail.
                    Log.e(TAG, "CRITICAL: Muxer writeSampleData error. The recording is now likely corrupt.", e)
                    // We should stop recording to prevent further damage and log the failure.
                    stopRecording()
                }
            }

            try {
                val inputBufferIndex = mediaCodec!!.dequeueInputBuffer(10000L)
                if (inputBufferIndex >= 0) {
                    mediaCodec!!.getInputBuffer(inputBufferIndex)?.put(data, 0, data.size)
                    val flags = if (isConfigFrame) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
                    mediaCodec!!.queueInputBuffer(inputBufferIndex, 0, data.size, 0, flags)
                }

                val bufferInfo = MediaCodec.BufferInfo()
                var outputBufferIndex = mediaCodec!!.dequeueOutputBuffer(bufferInfo, 0)
                while (outputBufferIndex != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    when {
                        outputBufferIndex >= 0 -> mediaCodec!!.releaseOutputBuffer(outputBufferIndex, true)
                        outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val newFormat = mediaCodec!!.outputFormat
                            val newWidth = newFormat.getInteger("width")
                            val newHeight = newFormat.getInteger("height")
                            Log.d(TAG, "Decoder format change: ${newWidth}x${newHeight}")
                            if (currentStreamWidth != newWidth || currentStreamHeight != newHeight) {
                                currentStreamWidth = newWidth
                                currentStreamHeight = newHeight
                                onResolutionChanged?.invoke(newWidth, newHeight)
                            }
                        }
                    }
                    outputBufferIndex = mediaCodec!!.dequeueOutputBuffer(bufferInfo, 0)
                }
            } catch (e: Exception) {
                // An error here could indicate a problem with the decoder itself.
                Log.e(TAG, "Error during mediaCodec decoding loop", e)
            }
        }
    }

    private fun getEstimatedFPS(): Long {
        return estimatedFPS
    }

    private fun updateFPSEstimate() {
        val currentTime = System.currentTimeMillis()
        frameTimestamps.add(currentTime)
        frameTimestamps.removeAll { it < currentTime - 2000 }
        if (frameTimestamps.size >= 10) {
            val timeSpan = frameTimestamps.last() - frameTimestamps.first()
            if (timeSpan > 0) {
                estimatedFPS = ((frameTimestamps.size - 1) * 1000L) / timeSpan
            }
        }
    }

    private fun isKeyFrame(data: ByteArray): Boolean {
        if (data.size < 5) return false
        val nalUnitType = if (isHevc) (data[4].toInt() shr 1) and 0x3F else data[4].toInt() and 0x1F
        return if (isHevc) nalUnitType in 16..21 else nalUnitType == 5
    }

    fun release() = synchronized(lock) {
        Log.d(TAG, "release() called.")

        // First, stop any active recording if it's still running.
        if (isRecording) {
            stopRecording()
        }

        // Now, release the muxer resources.
        try {
            if (mediaMuxer != null) {
                Log.d(TAG, "Releasing MediaMuxer resources.")
                mediaMuxer?.release()
                mediaMuxer = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing MediaMuxer", e)
        }

        // Finally, release the codec resources.
        try {
            if (mediaCodec != null) {
                Log.d(TAG, "Releasing MediaCodec resources.")
                mediaCodec?.stop()
                mediaCodec?.release()
                mediaCodec = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing MediaCodec", e)
        }

        Log.d(TAG, "All resources have been released.")
    }
}