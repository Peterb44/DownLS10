package com.downls10

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.net.ConnectivityManager
import android.net.Network
import android.net.LinkProperties
import android.net.NetworkRequest
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Socket
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
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
    private val tcpFlows = ConcurrentHashMap<String, TcpFlow>()
    @Volatile private var upstreams: List<InetAddress> = emptyList()
    private lateinit var connectivityManager: ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val prefs by lazy { getSharedPreferences("browser_settings", MODE_PRIVATE) }

    private data class TcpFlow(
        val clientIp: ByteArray,
        val serverIp: ByteArray,
        val clientPort: Int,
        var clientNext: Long,
        var serverNext: Long,
        var queryBuffer: ByteArray = ByteArray(0)
    )

    private fun refreshUpstreams(linkProperties: LinkProperties?) {
        val discovered = linkProperties?.dnsServers.orEmpty()
            .filterIsInstance<java.net.Inet4Address>()
            .distinct()
        if (discovered.isNotEmpty()) upstreams = discovered
    }

    private fun watchNetworkChanges() {
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        refreshUpstreams(connectivityManager.getLinkProperties(connectivityManager.activeNetwork))
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (network == connectivityManager.activeNetwork) {
                    refreshUpstreams(connectivityManager.getLinkProperties(network))
                }
            }
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                if (network == connectivityManager.activeNetwork) refreshUpstreams(linkProperties)
            }
        }
        networkCallback = callback
        try {
            connectivityManager.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
        } catch (_: Exception) {
            networkCallback = null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val raw = intent?.getStringArrayListExtra(EXTRA_UPSTREAM_DNS).orEmpty()
        val supplied = raw.mapNotNull { value ->
            try { InetAddress.getByName(value).takeIf { it is java.net.Inet4Address } } catch (_: Exception) { null }
        }.distinct()
        if (supplied.isNotEmpty()) upstreams = supplied
        if (networkCallback == null) watchNetworkChanges()
        if (upstreams.isEmpty()) {
            prefs.edit().putBoolean("browser_dns_vpn_enabled", false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        startNotification()
        if (!running.get()) startDns()
        return START_NOT_STICKY
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
            prefs.edit().putBoolean("browser_dns_vpn_enabled", true).apply()
            worker = thread(name = "DownLS10-DNS", isDaemon = true) {
                val input = FileInputStream(descriptor.fileDescriptor)
                val output = FileOutputStream(descriptor.fileDescriptor)
                val packet = ByteArray(32767)
                try {
                    while (running.get()) {
                        val count = input.read(packet)
                        if (count <= 0) continue
                        val responses = handleDnsPacket(packet, count)
                        if (running.get()) responses.forEach { output.write(it) }
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
            prefs.edit().putBoolean("browser_dns_vpn_enabled", false).apply()
            try { tun?.close() } catch (_: Exception) {}
            tun = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun handleDnsPacket(packet: ByteArray, length: Int): List<ByteArray> {
        if (length < 20 || (packet[0].toInt() and 0xF0) != 0x40) return emptyList()
        val ipHeaderLength = (packet[0].toInt() and 0x0F) * 4
        if (ipHeaderLength < 20 || length < ipHeaderLength) return emptyList()
        val protocol = packet[9].toInt() and 0xff
        return when (protocol) {
            17 -> handleUdpDns(packet, length, ipHeaderLength)?.let { listOf(it) } ?: emptyList()
            6 -> handleTcpDns(packet, length, ipHeaderLength)
            else -> emptyList()
        }
    }

    private fun handleUdpDns(packet: ByteArray, length: Int, ipHeaderLength: Int): ByteArray? {
        if (length < ipHeaderLength + 8) return null
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
        System.arraycopy(answer, 0, out, 28, answer.size)
        put16(out, 26, transportChecksum(out, 20, 8 + answer.size, 17))
        return out
    }

    /**
     * Minimal DNS-over-TCP endpoint for port 53 inside the per-app VPN.
     * It handles the TCP handshake, length-prefixed DNS query, response and close.
     */
    private fun handleTcpDns(packet: ByteArray, length: Int, ipHeaderLength: Int): List<ByteArray> {
        if (length < ipHeaderLength + 20) return emptyList()
        val tcpOffset = ipHeaderLength
        val sourcePort = u16(packet, tcpOffset)
        val destinationPort = u16(packet, tcpOffset + 2)
        if (destinationPort != 53) return emptyList()
        val seq = u32(packet, tcpOffset + 4)
        val ack = u32(packet, tcpOffset + 8)
        val tcpHeaderLength = ((packet[tcpOffset + 12].toInt() ushr 4) and 0x0f) * 4
        if (tcpHeaderLength < 20 || length < tcpOffset + tcpHeaderLength) return emptyList()
        val flags = packet[tcpOffset + 13].toInt() and 0xff
        val syn = (flags and 0x02) != 0
        val ackFlag = (flags and 0x10) != 0
        val fin = (flags and 0x01) != 0
        val rst = (flags and 0x04) != 0
        val clientIp = packet.copyOfRange(12, 16)
        val serverIp = packet.copyOfRange(16, 20)
        val key = clientIp.joinToString(".") { (it.toInt() and 255).toString() } + ":" + sourcePort
        if (rst) {
            tcpFlows.remove(key)
            return emptyList()
        }
        if (syn && !ackFlag) {
            val serverSeq = (key.hashCode().toLong() and 0xffffffffL)
            val flow = TcpFlow(clientIp, serverIp, sourcePort, (seq + 1) and 0xffffffffL, (serverSeq + 1) and 0xffffffffL)
            tcpFlows[key] = flow
            return listOf(buildTcpPacket(flow, serverSeq, flow.clientNext, 0x12, ByteArray(0)))
        }
        val flow = tcpFlows[key] ?: return emptyList()
        if (fin) {
            flow.clientNext = (seq + 1) and 0xffffffffL
            val reply = buildTcpPacket(flow, flow.serverNext, flow.clientNext, 0x11, ByteArray(0))
            tcpFlows.remove(key)
            return listOf(reply)
        }
        if (!ackFlag || seq != flow.clientNext) return emptyList()
        val payloadStart = tcpOffset + tcpHeaderLength
        val payloadLength = length - payloadStart
        if (payloadLength <= 0) return emptyList()
        flow.clientNext = (flow.clientNext + payloadLength) and 0xffffffffL
        flow.queryBuffer = flow.queryBuffer + packet.copyOfRange(payloadStart, length)
        val ackPacket = buildTcpPacket(flow, flow.serverNext, flow.clientNext, 0x10, ByteArray(0))
        if (flow.queryBuffer.size < 2) return listOf(ackPacket)
        val expected = u16(flow.queryBuffer, 0)
        if (expected < 12 || flow.queryBuffer.size < expected + 2) return listOf(ackPacket)
        val query = flow.queryBuffer.copyOfRange(2, expected + 2)
        flow.queryBuffer = flow.queryBuffer.copyOfRange(expected + 2, flow.queryBuffer.size)
        val answer = queryUpstreamTcpFirst(query) ?: return listOf(ackPacket)
        val framed = ByteArray(answer.size + 2)
        put16(framed, 0, answer.size)
        System.arraycopy(answer, 0, framed, 2, answer.size)
        val packets = mutableListOf<ByteArray>()
        packets.add(ackPacket)
        var offset = 0
        while (offset < framed.size) {
            val end = minOf(offset + 1300, framed.size)
            val chunk = framed.copyOfRange(offset, end)
            val flagsOut = 0x18 // PSH + ACK
            packets.add(buildTcpPacket(flow, flow.serverNext, flow.clientNext, flagsOut, chunk))
            flow.serverNext = (flow.serverNext + chunk.size) and 0xffffffffL
            offset = end
        }
        return packets
    }

    private fun queryUpstreamTcpFirst(query: ByteArray): ByteArray? {
        for (server in upstreams) {
            queryUpstreamTcp(server, query)?.let { return it }
        }
        return queryUpstream(query)
    }

    private fun buildTcpPacket(flow: TcpFlow, seq: Long, ack: Long, flags: Int, payload: ByteArray): ByteArray {
        val total = 20 + 20 + payload.size
        val out = ByteArray(total)
        out[0] = 0x45
        put16(out, 2, total)
        put16(out, 4, 0)
        put16(out, 6, 0x4000)
        out[8] = 64
        out[9] = 6
        System.arraycopy(flow.serverIp, 0, out, 12, 4)
        System.arraycopy(flow.clientIp, 0, out, 16, 4)
        put16(out, 10, checksum(out, 0, 20))
        put16(out, 20, 53)
        put16(out, 22, flow.clientPort)
        put32(out, 24, seq)
        put32(out, 28, ack)
        out[32] = 0x50
        out[33] = flags.toByte()
        put16(out, 34, 65535)
        put16(out, 36, 0)
        put16(out, 38, 0)
        if (payload.isNotEmpty()) System.arraycopy(payload, 0, out, 40, payload.size)
        put16(out, 36, transportChecksum(out, 20, 20 + payload.size, 6))
        return out
    }

    private fun u32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 255) shl 24) or
        ((bytes[offset + 1].toLong() and 255) shl 16) or
        ((bytes[offset + 2].toLong() and 255) shl 8) or
        (bytes[offset + 3].toLong() and 255)

    private fun put32(bytes: ByteArray, offset: Int, value: Long) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun transportChecksum(packet: ByteArray, offset: Int, length: Int, protocol: Int): Int {
        var sum = 0L
        for (i in 12..18 step 2) sum += u16(packet, i).toLong()
        sum += protocol.toLong()
        sum += length.toLong()
        var index = offset
        while (index + 1 < offset + length) {
            sum += u16(packet, index).toLong()
            index += 2
        }
        if (index < offset + length) sum += ((packet[index].toInt() and 255) shl 8).toLong()
        while ((sum ushr 16) != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.inv().toInt() and 0xffff
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
                if (response.length >= 12) {
                    val answer = buffer.copyOf(response.length)
                    if ((answer[2].toInt() and 0x02) != 0) {
                        queryUpstreamTcp(server, query)?.let { return it }
                    }
                    return answer
                }
            } catch (_: Exception) {
                // Try the next DNS server provided by Android.
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }
        return null
    }

    private fun queryUpstreamTcp(server: InetAddress, query: ByteArray): ByteArray? {
        var socket: Socket? = null
        return try {
            socket = Socket()
            if (!protect(socket)) return null
            socket.connect(InetSocketAddress(server, 53), 2500)
            socket.soTimeout = 3500
            val output = DataOutputStream(socket.getOutputStream())
            output.writeShort(query.size)
            output.write(query)
            output.flush()
            val input = DataInputStream(socket.getInputStream())
            val size = input.readUnsignedShort()
            if (size < 12 || size > 65535) return null
            ByteArray(size).also { input.readFully(it) }
        } catch (_: Exception) {
            null
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private fun stopDns() {
        running.set(false)
        tcpFlows.clear()
        try { networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) } } catch (_: Exception) {}
        networkCallback = null
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        worker = null
        prefs.edit().putBoolean("browser_dns_vpn_enabled", false).apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onRevoke() {
        stopSelf()
        super.onRevoke()
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
