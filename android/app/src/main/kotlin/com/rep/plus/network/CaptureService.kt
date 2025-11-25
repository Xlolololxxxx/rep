package com.rep.plus.network

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.net.DatagramPacket
import java.net.DatagramSocket

class CaptureService : Service() {

    private var isRunning = false
    private lateinit var captureThread: Thread
    private var udpSocket: DatagramSocket? = null

    companion object {
        const val ACTION_REQUEST_CAPTURED = "com.rep.plus.REQUEST_CAPTURED"
        const val EXTRA_REQUEST_DATA = "com.rep.plus.REQUEST_DATA"
        private const val UDP_PORT = 5123
        private const val TAG = "CaptureService"
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // We don't provide binding, so return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            isRunning = true
            startCaptureThread()
            Log.d(TAG, "CaptureService started.")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRunning) {
            isRunning = false
            captureThread.interrupt()
            udpSocket?.close()
            Log.d(TAG, "CaptureService stopped.")
        }
    }

    private fun startCaptureThread() {
        captureThread = Thread {
            try {
                udpSocket = DatagramSocket(UDP_PORT)
                val buffer = ByteArray(65535) // Max UDP packet size

                while (isRunning) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        udpSocket?.receive(packet)

                        // For now, we assume the packet contains the raw HTTP request.
                        // A proper implementation would require parsing IP/TCP headers.
                        val requestData = String(packet.data, 0, packet.length)

                        // Broadcast the captured data to MainActivity
                        broadcastRequest(requestData)

                    } catch (e: Exception) {
                        if (isRunning) {
                            Log.e(TAG, "Error receiving packet: ${e.message}", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start UDP listener: ${e.message}", e)
            } finally {
                udpSocket?.close()
            }
        }
        captureThread.start()
    }

    private fun broadcastRequest(requestData: String) {
        val intent = Intent(ACTION_REQUEST_CAPTURED).apply {
            putExtra(EXTRA_REQUEST_DATA, requestData)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        Log.d(TAG, "Broadcasted captured request.")
    }
}
