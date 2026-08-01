package com.of.colorpicker

import android.net.Network
import android.util.Log
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Local SOCKS5 endpoint used by hev-socks5-tunnel.
 *
 * Only the supported game applications are routed into the VPN, so this
 * process's outbound sockets naturally stay on the underlying network.
 */
class InterceptingSocks5Server(
    private val underlyingNetwork: Network?,
    private val port: Int = 1080
) {
    companion object {
        private const val TAG = "Socks5Server"
        private const val GAME_PORT_MIN = 11001
        private const val GAME_PORT_MAX = 11003
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val HANDSHAKE_TIMEOUT_MS = 10_000
        private const val MAX_UDP_PACKET_SIZE = 65_535
        private const val MAX_CONNECTIONS = 24
    }

    private data class SocksAddress(
        val address: InetAddress?,
        val hostname: String?,
        val port: Int
    )

    private data class SocksRequest(val command: Int, val target: SocksAddress)

    private data class UdpRequest(
        val target: SocksAddress,
        val payloadOffset: Int,
        val payloadLength: Int
    )

    private val running = AtomicBoolean(false)
    private val nextFlowId = AtomicInteger(1)
    private val openResources = ConcurrentHashMap.newKeySet<Closeable>()
    private val connectionExecutor = Executors.newFixedThreadPool(
        MAX_CONNECTIONS,
        namedThreadFactory("socks-client")
    )
    private val relayExecutor = Executors.newFixedThreadPool(
        MAX_CONNECTIONS,
        namedThreadFactory("socks-relay")
    )
    private val native = NativeLib()

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    var onGamePacketCaptured: ((pictureId: Int, params: DoubleArray) -> Unit)? = null

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true

        return try {
            serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
            }
            acceptThread = Thread(::acceptLoop, "socks-accept").apply { start() }
            Log.i(TAG, "SOCKS5 server listening on 127.0.0.1:$port")
            true
        } catch (e: Exception) {
            running.set(false)
            Log.e(TAG, "Unable to start SOCKS5 server", e)
            closeQuietly(serverSocket)
            serverSocket = null
            false
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return

        closeQuietly(serverSocket)
        serverSocket = null
        openResources.forEach(::closeQuietly)
        openResources.clear()
        connectionExecutor.shutdownNow()
        relayExecutor.shutdownNow()
        connectionExecutor.awaitTermination(2, TimeUnit.SECONDS)
        relayExecutor.awaitTermination(2, TimeUnit.SECONDS)
        acceptThread = null
        Log.i(TAG, "SOCKS5 server stopped")
    }

    private fun acceptLoop() {
        while (running.get()) {
            try {
                val client = serverSocket?.accept() ?: break
                client.tcpNoDelay = true
                track(client)
                connectionExecutor.execute { handleClient(client) }
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "SOCKS accept failed", e)
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = HANDSHAKE_TIMEOUT_MS
            val input = client.getInputStream()
            val output = client.getOutputStream()

            if (!negotiateNoAuth(input, output)) return
            val request = readRequest(input) ?: run {
                sendReply(output, 0x08)
                return
            }
            client.soTimeout = 0

            when (request.command) {
                0x01 -> handleConnect(client, output, request.target)
                0x03 -> handleUdpAssociate(client, output, request.target)
                else -> sendReply(output, 0x07)
            }
        } catch (e: Exception) {
            if (running.get() && e !is EOFException && e !is SocketException) {
                Log.d(TAG, "SOCKS client closed: ${e.message}")
            }
        } finally {
            untrackAndClose(client)
        }
    }

    private fun negotiateNoAuth(input: InputStream, output: OutputStream): Boolean {
        if (input.read() != 0x05) return false
        val methodCount = input.read()
        if (methodCount !in 1..255) return false
        val methods = ByteArray(methodCount)
        input.readFully(methods)
        val noAuth = methods.any { (it.toInt() and 0xff) == 0x00 }
        output.write(byteArrayOf(0x05, (if (noAuth) 0x00 else 0xff).toByte()))
        output.flush()
        return noAuth
    }

    private fun readRequest(input: InputStream): SocksRequest? {
        val header = ByteArray(4)
        input.readFully(header)
        if ((header[0].toInt() and 0xff) != 0x05) return null
        if ((header[2].toInt() and 0xff) != 0x00) return null

        val target = readAddress(input, header[3].toInt() and 0xff) ?: return null
        return SocksRequest(header[1].toInt() and 0xff, target)
    }

    private fun readAddress(input: InputStream, type: Int): SocksAddress? {
        val address: InetAddress?
        val hostname: String?
        when (type) {
            0x01 -> {
                val bytes = ByteArray(4)
                input.readFully(bytes)
                address = InetAddress.getByAddress(bytes)
                hostname = null
            }
            0x03 -> {
                val length = input.read()
                if (length !in 1..255) return null
                val bytes = ByteArray(length)
                input.readFully(bytes)
                address = null
                hostname = String(bytes, StandardCharsets.US_ASCII)
            }
            0x04 -> {
                val bytes = ByteArray(16)
                input.readFully(bytes)
                address = InetAddress.getByAddress(bytes)
                hostname = null
            }
            else -> return null
        }

        val high = input.read()
        val low = input.read()
        if (high < 0 || low < 0) throw EOFException()
        return SocksAddress(address, hostname, (high shl 8) or low)
    }

    private fun handleConnect(client: Socket, output: OutputStream, target: SocksAddress) {
        val remote = Socket()
        track(remote)
        try {
            remote.tcpNoDelay = true
            val destination = resolve(target)
            remote.connect(destination, CONNECT_TIMEOUT_MS)

            sendReply(output, 0x00, remote.localAddress, remote.localPort)
            Log.d(TAG, "TCP ${destination.address.hostAddress}:${destination.port}")
            relayTcp(client, remote, target.port)
        } catch (e: Exception) {
            if (!remote.isConnected) {
                sendReply(output, replyFor(e))
                Log.w(TAG, "TCP connect failed for ${target.hostname ?: target.address}: ${e.message}")
            }
        } finally {
            untrackAndClose(remote)
        }
    }

    private fun relayTcp(client: Socket, remote: Socket, destinationPort: Int) {
        val flowId = nextFlowId.getAndIncrement()
        val upstream = relayExecutor.submit {
            try {
                copy(client.getInputStream(), remote.getOutputStream())
                shutdownOutput(remote)
            } catch (_: Exception) {
                closeQuietly(client)
                closeQuietly(remote)
            }
        }

        try {
            val input = remote.getInputStream()
            val output = client.getOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (running.get()) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue

                if (destinationPort in GAME_PORT_MIN..GAME_PORT_MAX) {
                    val result = native.feedTcpPayload(flowId, buffer.copyOf(count))
                    if (result != null && result.size > 1) {
                        val pictureId = result[0].toInt()
                        val params = result.copyOfRange(1, result.size)
                        Log.i(TAG, "Captured dye params: pictureId=$pictureId")
                        onGamePacketCaptured?.invoke(pictureId, params)
                    }
                }

                output.write(buffer, 0, count)
            }
            shutdownOutput(client)
        } finally {
            native.closeTcpFlow(flowId)
            closeQuietly(client)
            closeQuietly(remote)
            waitQuietly(upstream)
        }
    }

    private fun handleUdpAssociate(client: Socket, output: OutputStream, requested: SocksAddress) {
        val udpSocket = DatagramSocket(null)
        track(udpSocket)
        try {
            udpSocket.reuseAddress = true
            udpSocket.bind(InetSocketAddress(0))

            sendReply(output, 0x00, InetAddress.getByName("127.0.0.1"), udpSocket.localPort)
            val relay = relayExecutor.submit { relayUdp(udpSocket, requested) }

            // A SOCKS5 UDP association lives as long as its TCP control channel.
            try {
                while (client.getInputStream().read() >= 0) Unit
            } finally {
                closeQuietly(udpSocket)
                waitQuietly(relay)
            }
        } finally {
            untrackAndClose(udpSocket)
        }
    }

    private fun relayUdp(socket: DatagramSocket, requested: SocksAddress) {
        val buffer = ByteArray(MAX_UDP_PACKET_SIZE)
        var clientEndpoint = requested.toClientEndpointOrNull()

        while (running.get() && !socket.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
                val source = InetSocketAddress(packet.address, packet.port)

                if (clientEndpoint == null || source == clientEndpoint) {
                    val request = parseUdpRequest(packet.data, packet.offset, packet.length)
                    if (request == null) continue
                    if (clientEndpoint == null) clientEndpoint = source

                    val destination = resolve(request.target)
                    socket.send(
                        DatagramPacket(
                            packet.data,
                            request.payloadOffset,
                            request.payloadLength,
                            destination
                        )
                    )
                } else {
                    val response = encodeUdpResponse(packet)
                    socket.send(DatagramPacket(response, response.size, clientEndpoint))
                }
            } catch (e: SocketException) {
                if (!socket.isClosed && running.get()) Log.d(TAG, "UDP relay stopped: ${e.message}")
                break
            } catch (e: Exception) {
                if (running.get()) Log.d(TAG, "UDP packet dropped: ${e.message}")
            }
        }
    }

    private fun parseUdpRequest(data: ByteArray, offset: Int, length: Int): UdpRequest? {
        if (length < 7) return null
        var cursor = offset
        val end = offset + length
        if (data[cursor++].toInt() != 0 || data[cursor++].toInt() != 0) return null
        if (data[cursor++].toInt() != 0) return null // Fragmentation is not supported.
        val type = data[cursor++].toInt() and 0xff

        val address: InetAddress?
        val hostname: String?
        when (type) {
            0x01 -> {
                if (cursor + 4 > end) return null
                address = InetAddress.getByAddress(data.copyOfRange(cursor, cursor + 4))
                hostname = null
                cursor += 4
            }
            0x03 -> {
                if (cursor >= end) return null
                val hostLength = data[cursor++].toInt() and 0xff
                if (hostLength == 0 || cursor + hostLength > end) return null
                address = null
                hostname = String(data, cursor, hostLength, StandardCharsets.US_ASCII)
                cursor += hostLength
            }
            0x04 -> {
                if (cursor + 16 > end) return null
                address = InetAddress.getByAddress(data.copyOfRange(cursor, cursor + 16))
                hostname = null
                cursor += 16
            }
            else -> return null
        }

        if (cursor + 2 > end) return null
        val port = ((data[cursor].toInt() and 0xff) shl 8) or
            (data[cursor + 1].toInt() and 0xff)
        cursor += 2
        return UdpRequest(SocksAddress(address, hostname, port), cursor, end - cursor)
    }

    private fun encodeUdpResponse(packet: DatagramPacket): ByteArray {
        val address = packet.address.address
        val addressType = if (packet.address is Inet4Address) 0x01 else 0x04
        val headerLength = 4 + address.size + 2
        return ByteArray(headerLength + packet.length).also { output ->
            output[3] = addressType.toByte()
            address.copyInto(output, 4)
            var cursor = 4 + address.size
            output[cursor++] = (packet.port ushr 8).toByte()
            output[cursor++] = packet.port.toByte()
            packet.data.copyInto(
                destination = output,
                destinationOffset = cursor,
                startIndex = packet.offset,
                endIndex = packet.offset + packet.length
            )
        }
    }

    private fun sendReply(
        output: OutputStream,
        status: Int,
        bindAddress: InetAddress = InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0)),
        bindPort: Int = 0
    ) {
        val address = bindAddress.address
        val type = if (bindAddress is Inet6Address) 0x04 else 0x01
        val reply = ByteArray(4 + address.size + 2)
        reply[0] = 0x05
        reply[1] = status.toByte()
        reply[3] = type.toByte()
        address.copyInto(reply, 4)
        reply[reply.size - 2] = (bindPort ushr 8).toByte()
        reply[reply.size - 1] = bindPort.toByte()
        output.write(reply)
        output.flush()
    }

    private fun resolve(target: SocksAddress): InetSocketAddress {
        val address = target.address ?: resolveHostname(target.hostname ?: throw UnknownHostException())
        return InetSocketAddress(address, target.port)
    }

    private fun resolveHostname(hostname: String): InetAddress {
        val addresses = try {
            underlyingNetwork?.getAllByName(hostname) ?: InetAddress.getAllByName(hostname)
        } catch (_: Exception) {
            InetAddress.getAllByName(hostname)
        }
        return addresses.firstOrNull() ?: throw UnknownHostException(hostname)
    }

    private fun SocksAddress.toClientEndpointOrNull(): InetSocketAddress? {
        val addr = address ?: return null
        if (port == 0 || addr.isAnyLocalAddress) return null
        return InetSocketAddress(addr, port)
    }

    private fun replyFor(error: Exception): Int = when (error) {
        is SecurityException -> 0x02
        is UnknownHostException, is SocketTimeoutException -> 0x04
        is ConnectException -> 0x05
        else -> 0x01
    }

    private fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(16 * 1024)
        while (running.get()) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) output.write(buffer, 0, count)
        }
    }

    private fun track(resource: Closeable) {
        openResources.add(resource)
    }

    private fun untrackAndClose(resource: Closeable?) {
        if (resource != null) openResources.remove(resource)
        closeQuietly(resource)
    }

    private fun closeQuietly(resource: Closeable?) {
        try {
            resource?.close()
        } catch (_: Exception) {
        }
    }

    private fun shutdownOutput(socket: Socket) {
        try {
            if (!socket.isOutputShutdown) socket.shutdownOutput()
        } catch (_: Exception) {
        }
    }

    private fun waitQuietly(future: Future<*>) {
        try {
            future.get(1, TimeUnit.SECONDS)
        } catch (_: Exception) {
            future.cancel(true)
        }
    }

    private fun InputStream.readFully(buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val count = read(buffer, offset, buffer.size - offset)
            if (count < 0) throw EOFException()
            offset += count
        }
    }

    private fun namedThreadFactory(prefix: String): ThreadFactory {
        val index = AtomicInteger(1)
        return ThreadFactory { task -> Thread(task, "$prefix-${index.getAndIncrement()}") }
    }
}
