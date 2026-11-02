package io.github.jerahya.camheadtracker

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

// OpenTrack UDP: 6 little-endian doubles, x y z (cm) yaw pitch roll (deg)
class UdpSender {
    private val executor = Executors.newSingleThreadExecutor()
    private var socket: DatagramSocket? = null
    private var address: InetAddress? = null
    private var port = 0
    private val buffer = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN)

    fun start(host: String, port: Int) {
        executor.execute {
            close()
            try {
                address = InetAddress.getByName(host)
                this.port = port
                socket = DatagramSocket()
            } catch (e: Exception) {
                Log.e(TAG, "Cannot open UDP target $host:$port", e)
                close()
            }
        }
    }

    fun stop() {
        executor.execute { close() }
    }

    fun send(pose: DoubleArray) {
        val copy = pose.copyOf()
        executor.execute {
            val s = socket ?: return@execute
            buffer.clear()
            copy.forEach { buffer.putDouble(it) }
            try {
                s.send(DatagramPacket(buffer.array(), 48, address, port))
            } catch (e: Exception) {
                Log.w(TAG, "UDP send failed", e)
            }
        }
    }

    fun shutdown() {
        executor.execute { close() }
        executor.shutdown()
    }

    private fun close() {
        socket?.close()
        socket = null
        address = null
    }

    private companion object {
        const val TAG = "UdpSender"
    }
}
