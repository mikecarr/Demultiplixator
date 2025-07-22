// In UdpDataSource.kt

package net.mikecarr.demultiplixator

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.MulticastSocket
import java.net.SocketTimeoutException

class UdpDataSource {

    private var multicastSocket: MulticastSocket? = null
    private val TAG = "UdpDataSource"
    private var packetCount = 0L
    private var lastLogTime = 0L

    // Use a flow or a callback to send data to the decoder
    suspend fun listen(port: Int, onPacketReceived: (ByteArray, Int) -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting UDP listener on port $port")
                multicastSocket = MulticastSocket(port).apply {
                    soTimeout = 1000 // 1 second timeout for receive operations
                    joinGroup(java.net.InetAddress.getByName("224.0.0.1"))
                    Log.d(TAG, "Joined multicast group 224.0.0.1 on port $port")
                }

                val buffer = ByteArray(4096) // Standard buffer size for RTP packets
                Log.d(TAG, "Listening for UDP packets...")

                while (isActive) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        multicastSocket?.receive(packet)

                        packetCount++

                        // Log packet reception periodically (every 100 packets or every 5 seconds)
                        val currentTime = System.currentTimeMillis()
                        if (packetCount % 100 == 0L || (currentTime - lastLogTime) > 5000) {
                            Log.d(TAG, "Received packet #$packetCount from ${packet.address}:${packet.port}, size: ${packet.length}")
                            lastLogTime = currentTime
                        }

                        // Log first few packets in detail
                        if (packetCount <= 3) {
                            Log.d(TAG, "Packet #$packetCount details: from=${packet.address}:${packet.port}, size=${packet.length}")
                            if (packet.length >= 12) {
                                val rtpHeader = packet.data.sliceArray(0..11)
                                Log.d(TAG, "RTP header: ${rtpHeader.joinToString(" ") { "%02x".format(it) }}")
                            }
                        }

                        // Pass a copy of the relevant data
                        onPacketReceived(packet.data.copyOf(packet.length), packet.length)

                    } catch (e: SocketTimeoutException) {
                        // Timeout is normal - just continue listening
                        if (packetCount == 0L) {
                            val currentTime = System.currentTimeMillis()
                            if ((currentTime - lastLogTime) > 10000) { // Log every 10 seconds if no packets
                                Log.w(TAG, "No UDP packets received yet on port $port (multicast 224.0.0.1)")
                                lastLogTime = currentTime
                            }
                        }
                        continue
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in UDP listener", e)
            } finally {
                close()
            }
        }
    }

    fun close() {
        Log.d(TAG, "Closing UDP listener. Total packets received: $packetCount")
        // Only try to leave the group if the socket is not null and is still open
        if (multicastSocket != null && !multicastSocket!!.isClosed) {
            try {
                multicastSocket?.leaveGroup(java.net.InetAddress.getByName("224.0.0.1"))
                multicastSocket?.close()
                Log.d(TAG, "Left multicast group and closed socket")
            } catch (e: Exception) {
                Log.e(TAG, "Error closing UDP socket", e)
            }
        }
        multicastSocket = null
        packetCount = 0L
    }
}