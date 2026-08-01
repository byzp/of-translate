package com.of.colorpicker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager.NameNotFoundException
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File

/** Captures the game's traffic through hev-socks5-tunnel and a local SOCKS5 relay. */
class PacketVpnService : VpnService() {
    companion object {
        private const val TAG = "PacketVpnService"
        private const val NOTIFICATION_CHANNEL = "packet-capture"
        private const val NOTIFICATION_ID = 1652
        private const val SOCKS_PORT = 1080
        private val TARGET_CLIENT_PACKAGES = listOf(
            "com.Nekootan.kfkjos.google",
            "com.Nekootan.kfkj.android"
        )

        const val ACTION_START = "com.of.colorpicker.START"
        const val ACTION_STOP = "com.of.colorpicker.STOP"

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var statusMessage = "Stopped"
            private set

        @Volatile
        var currentTargetPackages: List<String> = emptyList()
            private set

        var onDyeParamsCaptured: ((pictureId: Int, params: DoubleArray) -> Unit)? = null
        var onStatusChanged: ((running: Boolean, message: String) -> Unit)? = null

        @Volatile
        private var instance: PacketVpnService? = null

        /** Used by the hev JNI bridge when a native socket explicitly needs bypassing. */
        @JvmStatic
        fun protectFd(fd: Int): Boolean = try {
            instance?.protect(fd) ?: false
        } catch (_: Exception) {
            false
        }

    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var socks5Server: InterceptingSocks5Server? = null
    private var starting = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        Log.i(TAG, "VPN service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                return START_NOT_STICKY
            }
            ACTION_START, null -> startCapture()
        }
        return if (isRunning || starting) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseCapture()
        instance = null
        publishStatus(false, "Stopped")
        super.onDestroy()
    }

    override fun onRevoke() {
        stopCapture()
        super.onRevoke()
    }

    private fun startCapture() {
        if (isRunning || starting) return
        starting = true
        instance = this
        startForegroundNotification("Starting capture")
        publishStatus(false, "Starting")

        try {
            val connectivity = getSystemService(ConnectivityManager::class.java)
            val server = InterceptingSocks5Server(connectivity.activeNetwork, SOCKS_PORT)
            if (!server.start()) throw IllegalStateException("local SOCKS5 port is unavailable")
            server.onGamePacketCaptured = { pictureId, params ->
                onDyeParamsCaptured?.invoke(pictureId, params)
            }
            socks5Server = server

            val builder = Builder()
                .setSession("OF Color Picker")
                .setBlocking(false)
                .setMtu(1500)
                .addAddress("198.18.0.1", 32)
                .addRoute("0.0.0.0", 0)
                .addAddress("fc00::1", 128)
                .addRoute("::", 0)
                .addDnsServer("198.18.0.2")

            val routedPackages = TARGET_CLIENT_PACKAGES.mapNotNull { targetPackage ->
                try {
                    // Per-app routing avoids intercepting unrelated system traffic and
                    // naturally keeps this relay's own sockets outside the VPN.
                    packageManager.getApplicationInfo(targetPackage, 0)
                    builder.addAllowedApplication(targetPackage)
                    targetPackage
                } catch (_: NameNotFoundException) {
                    null
                }
            }
            if (routedPackages.isEmpty()) {
                throw IllegalStateException("neither supported client package is installed")
            }
            currentTargetPackages = routedPackages
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

            vpnInterface = builder.establish()
                ?: throw IllegalStateException("Android refused to establish the VPN interface")

            val configDir = File(cacheDir, "hev-config").apply { mkdirs() }
            File(configDir, "tunnel.log").delete()
            val configFile = File(configDir, "conf.yml").apply {
                writeText(buildConfig(configDir))
            }

            if (!HevTunnelService.TProxyStartService(configFile.absolutePath, vpnInterface!!.fd) ||
                !HevTunnelService.TProxyIsRunning()
            ) {
                throw IllegalStateException("hev-socks5-tunnel did not start")
            }

            isRunning = true
            publishStatus(true, "Capturing")
            startForegroundNotification("Capturing game traffic")
            Log.i(TAG, "Capture started for ${routedPackages.joinToString()}")
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start capture", e)
            releaseCapture()
            publishStatus(false, "Start failed: ${e.message ?: e.javaClass.simpleName}")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } finally {
            starting = false
        }
    }

    private fun stopCapture() {
        releaseCapture()
        publishStatus(false, "Stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "Capture stopped")
    }

    private fun releaseCapture() {
        isRunning = false
        currentTargetPackages = emptyList()

        try {
            if (HevTunnelService.TProxyIsRunning()) HevTunnelService.TProxyStopService()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to stop hev tunnel cleanly", e)
        }

        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null

        socks5Server?.stop()
        socks5Server = null
    }

    private fun publishStatus(active: Boolean, message: String) {
        isRunning = active
        statusMessage = message
        onStatusChanged?.invoke(active, message)
    }

    private fun buildConfig(configDir: File): String {
        val logPath = File(configDir, "tunnel.log").absolutePath
        return """
tunnel:
  mtu: 1500
  ipv4: 198.18.0.1
  ipv6: 'fc00::1'
  icmp: 'reply'
socks5:
  port: $SOCKS_PORT
  address: 127.0.0.1
  udp: 'udp'
mapdns:
  address: 198.18.0.2
  port: 53
  network: 240.0.0.0
  netmask: 240.0.0.0
  cache-size: 10000
misc:
  task-stack-size: 86016
  tcp-buffer-size: 65536
  udp-recv-buffer-size: 262144
  udp-copy-buffer-nums: 8
  connect-timeout: 10000
  tcp-read-write-timeout: 300000
  udp-read-write-timeout: 60000
  max-session-count: 128
  limit-nofile: 8192
  log-file: '$logPath'
  log-level: warn
        """.trimIndent()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL,
            "Packet capture",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Game packet capture status"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startForegroundNotification(text: String) {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, PacketVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("OF Color Picker")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Stop", stopIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
