# Android RTP Video Decoder (Demultiplixator)

This is an Android application built in Kotlin that demonstrates how to receive, decode, and display a raw H.264 (AVC) or H.265 (HEVC) video stream sent over a UDP multicast socket. It also includes functionality to record the incoming stream to an `.mp4` file on the device.

The project is a modern re-implementation of the core logic found in the `com.openipc.decoder` Java class, built with Jetpack Compose, Kotlin Coroutines, and the latest Android media APIs.

![App Screenshot](https://user-images.githubusercontent.com/18461561/220224958-3d14902b-8a8b-4b16-953e-257a3e141a7c.png)
*(Replace with a screenshot of your app running)*

## Core Features

-   **UDP Multicast Reception:** Listens on a specific port for incoming RTP video packets on the local network.
-   **Dynamic Codec Detection:** Introspects RTP packets to automatically detect and switch between H.264 (AVC) and H.265 (HEVC) video codecs.
-   **Real-time Video Decoding:** Utilizes Android's low-level `MediaCodec` API for efficient, hardware-accelerated video decoding.
-   **Jetpack Compose UI:** The entire user interface is built with Jetpack Compose, including an `AndroidView` wrapper for the `SurfaceView` used for rendering.
-   **Robust RTP Packet Handling:**
    -   Parses RTP headers to extract the video payload.
    -   Handles **fragmented NAL units**, correctly reassembling video frames that are split across multiple UDP packets.
    -   Identifies and feeds **Codec-Specific Data (CSD)**—SPS, PPS, and VPS—to the decoder for proper initialization.
-   **Video Recording:** Uses `MediaMuxer` to efficiently save the raw, encoded video stream directly to an `.mp4` file in the device's "Movies" directory, avoiding any transcoding.

## Tech Stack & Key Concepts

-   **Language:** [Kotlin](https://kotlinlang.org/)
-   **UI Toolkit:** [Jetpack Compose](https://developer.android.com/jetpack/compose)
-   **Concurrency:** [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html) for background network listening.
-   **Media APIs:**
    -   `MediaCodec`: For low-level video decoding.
    -   `MediaMuxer`: For writing the video stream to an MP4 container.
    -   `SurfaceView`: For high-performance video rendering.
-   **Networking:** `java.net.MulticastSocket` for receiving UDP packets.
-   **Storage:** `MediaStore` API for saving the recorded video files to public storage.

## Setup and Installation

1.  Clone this repository.
2.  Open the project in the latest version of Android Studio.
3.  Let Gradle sync and build the project.
4.  Run the app on a physical Android device. (An emulator may not be able to receive multicast packets from your local network).
5.  **Important:** Ensure your Android device is on the **same Wi-Fi network** as the IP camera or streaming source.

## How It Works

The application's architecture is designed to separate networking, decoding, and UI logic.

1.  **`UdpDataSource.kt`**: A dedicated class running in a coroutine (`Dispatchers.IO`) opens a `MulticastSocket` on port `5600`. It continuously listens for UDP packets and passes the raw `ByteArray` data up via a callback.

2.  **`MainActivity.kt`**:
    -   The `VideoStreamPlayer` composable manages the overall state.
    -   It uses an `AndroidView` to host a `SurfaceView`. The `SurfaceHolder.Callback` is used to know when the rendering surface is ready.
    -   When the surface is created, it launches a coroutine that starts the `UdpDataSource`.
    -   For each packet received, it peeks at the **RTP payload type** (byte 1) to determine if the stream is H.264 or H.265 and calls `videoDecoder.setCodecType()`.
    -   It then passes the full packet to `videoDecoder.decodeRtpPacket()`.
    -   A `Button` in the UI toggles the recording state by calling `videoDecoder.startRecording()` and `stopRecording()`.

3.  **`VideoDecoder.kt`**: This is the core of the application.
    -   It manages the `MediaCodec` and `MediaMuxer` instances.
    -   When `decodeRtpPacket()` is called, it parses the **NAL unit type** from the payload.
    -   **Configuration:** If the NAL unit is a VPS (32), SPS (33), or PPS (34) for H.265, or an SPS (7) or PPS (8) for H.264, it feeds this data to the `MediaCodec` with the `BUFFER_FLAG_CODEC_CONFIG` flag. It waits until all required configuration frames are received before proceeding.
    -   **Fragmentation:** If the NAL unit is a fragment (type 49 for H.265, 28 for H.264), it reassembles the pieces in a buffer until the final fragment arrives.
    -   **Decoding & Rendering:** Once a complete frame is available (and the codec is configured), it is queued into the `MediaCodec`. The decoder automatically renders the output to the `SurfaceView` it was configured with.
    -   **Recording:** If recording is active, the complete frame is also written to the `MediaMuxer` along with a calculated presentation timestamp to ensure a valid `.mp4` file is created.

## Future Improvements

-   **Audio Support:** Implement a parallel `AudioTrack` and `MediaCodec` pipeline to decode and play common audio codecs like AAC or Opus.
-   **Enhanced UI:** Display stream statistics like resolution, bitrate, and frames per second.
-   **Error Handling:** Show user-friendly messages on the UI for network errors or decoder failures.
-   **Settings Screen:** Allow the user to configure the IP address and port to listen on.
-   **Remote Control:** Re-implement the joystick/key event handling from the original Java class to send control commands back to the source.

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.