package com.downls10

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Per-app DNS relay. Only the synthetic DNS address is routed through the VPN;
 * other Internet traffic is not routed through this service. The VPN is limited
 * to the DownLS10 package, and queries are forwarded to DNS servers from the
 * currently active Android network.
 */
class BrowserDnsVpnService : VpnService() {
    companion object {
        const val EXTRA_UPSTREAM_DNS = "com.downls10.extra.UPSTREAM_DNS"
        private const val CHANNEL_ID = "downls10_dns"
        private const val NOTIFICATION_ID = 3107
        private const val DNS_IP = "10.77.0.2"
    }

    private var tun: ParcelFileDescriptor? = null
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var upstreams: List<InetAddress> = emptyList()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val raw = intent?.getStringArrayListExtra(EXTRA_UPSTREAM_DNS).orEmpty()
        upstreams = raw.mapNotNull { value ->
            try { InetAddress.getByName(value) } catch (_: Exception) { null }
        }.distinct()
        if (upstreams.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startNotification()
        if (!running.get()) startDns()
        return START_STICKY
    }

    private fun startNotification() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "DNS DownLS10", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("DNS الخاص بـ DownLS10")
            .setContentText("يستخدم DNS الشبكة الحالية لهذا التطبيق")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startDns() {
        try {
            val descriptor = Builder()
                .setSession("DownLS10 DNS")
                .setMtu(1500)
                .addAddress("10.77.0.1", 32)
                .addDnsServer(DNS_IP)
                .addRoute(DNS_IP, 32)
                .addAllowedApplication(packageName)
                .establish() ?: throw IllegalStateException("VPN interface unavailable")
            tun = descriptor
            running.set(true)
            worker = thread(name = "DownLS10-DNS", isDaemon = true) {
                val input = FileInputStream(descriptor.fileDescriptor)
                val output = FileOutputStream(descriptor.fileDescriptor)
                val packet = ByteArray(32767)
                try {
                    while (running.get()) {
                        val count = input.read(packet)
                        if (count <= 0) continue
                        val response = handleDnsPacket(packet, count)
                        if (response != null && running.get()) output.write(response)
                    }
                } catch (_: Exception) {
                    // Closing the TUN descriptor ends the worker.
                } finally {
                    try { input.close() } catch (_: Exception) {}
                    try { output.close() } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {
            running.set(false)
            try { tun?.close() } catch (_: Exception) {}
            tun = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun handleDnsPacket(packet: ByteArray, length: Int): ByteArray? {
        if (length < 28 || (packet[0].toInt() and 0xF0) != 0x40) return null
        val ipHeaderLength = (packet[0].toInt() and 0x0F) * 4
        if (ipHeaderLength < 20 || length < ipHeaderLength + 8) return null
        if ((packet[9].toInt() and 0xff) != 17) return null
        val sourcePort = u16(packet, ipHeaderLength)
        val destinationPort = u16(packet, ipHeaderLength + 2)
        if (destinationPort != 53) return null
        val udpLength = u16(packet, ipHeaderLength + 4)
        if (udpLength < 20 || ipHeaderLength + udpLength > length) return null
        val dnsStart = ipHeaderLength + 8
        val query = packet.copyOfRange(dnsStart, ipHeaderLength + udpLength)
        if (query.size < 12) return null
        val sourceAddress = packet.copyOfRange(12, 16)
        val destinationAddress = packet.copyOfRange(16, 20)
        val answer = queryUpstream(query) ?: return null
        val totalLength = 20 + 8 + answer.size
        if (totalLength > 65535) return null
        val out = ByteArray(totalLength)
        out[0] = 0x45
        out[1] = 0
        put16(out, 2, totalLength)
        put16(out, 4, u16(packet, 4))
        put16(out, 6, 0x4000)
        out[8] = 64
        out[9] = 17
        System.arraycopy(destinationAddress, 0, out, 12, 4)
        System.arraycopy(sourceAddress, 0, out, 16, 4)
        put16(out, 10, checksum(out, 0, 20))
        put16(out, 20, 53)
        put16(out, 22, sourcePort)
        put16(out, 24, 8 + answer.size)
        put16(out, 26, 0)
        System.arraycopy(answer, 0, out, 28, answer.size)
        return out
    }

    private fun queryUpstream(query: ByteArray): ByteArray? {
        for (server in upstreams) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                if (!protect(socket)) continue
                socket.soTimeout = 2500
                socket.connect(InetSocketAddress(server, 53))
                socket.send(DatagramPacket(query, query.size))
                val buffer = ByteArray(4096)
                val response = DatagramPacket(buffer, buffer.size)
                socket.receive(response)
                if (response.length >= 12) return buffer.copyOf(response.length)
            } catch (_: Exception) {
                // Try the next DNS server provided by Android.
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }
        return null
    }

    private fun stopDns() {
        running.set(false)
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        worker = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopDns()
        super.onDestroy()
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun put16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun checksum(bytes: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var index = offset
        while (index + 1 < offset + length) {
            sum += u16(bytes, index).toLong()
            index += 2
        }
        if (index < offset + length) {
            sum += ((bytes[index].toInt() and 0xff) shl 8).toLong()
        }
        while ((sum ushr 16) != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.inv().toInt() and 0xffff
    }
}
