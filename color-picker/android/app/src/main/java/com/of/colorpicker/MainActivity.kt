package com.of.colorpicker

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.of.colorpicker.ui.theme.OfColorPickerTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class MainActivity : ComponentActivity() {

    private val native = NativeLib()
    private var pendingVpnStart = false
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            if (pendingVpnStart) startVpnAndOverlay()
        } else {
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
        }
        pendingVpnStart = false
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            requestVpnPermission()
        } else {
            Toast.makeText(this, "Overlay permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        loadTextures()

        PacketVpnService.onDyeParamsCaptured = { pid, params ->
            OverlayService.dyeParams = DyeParams(pid, params)
        }

        setContent {
            OfColorPickerTheme {
                LauncherScreen(
                    onStart = { requestPermissionsAndStart() },
                    onStop = { stopCapture() }
                )
            }
        }
    }

    override fun onDestroy() {
        PacketVpnService.onDyeParamsCaptured = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // If overlay service is already running, bring activity to foreground
        // so user can see the status
    }

    private fun loadTextures() {
        val names = listOf("1.png", "2.png", "3.png", "4.png")
        for (i in names.indices) {
            try {
                val data = assets.open("swirlnoisetexture/${names[i]}").readBytes()
                native.loadTexture(i + 1, data)
            } catch (_: Exception) { }
        }
    }

    private fun requestPermissionsAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            pendingVpnStart = true
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
            return
        }
        requestVpnPermission()
    }

    private fun requestVpnPermission() {
        pendingVpnStart = true
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpnAndOverlay()
            pendingVpnStart = false
        }
    }

    private fun startVpnAndOverlay() {
        // Start VPN service
        val vpnIntent = Intent(this, PacketVpnService::class.java).apply {
            action = PacketVpnService.ACTION_START
        }
        startForegroundService(vpnIntent)

        // Start overlay service
        val overlayIntent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_START
        }
        startForegroundService(overlayIntent)

        // Minimize activity so overlay is visible
        moveTaskToBack(true)
    }

    private fun stopCapture() {
        val vpnIntent = Intent(this, PacketVpnService::class.java).apply {
            action = PacketVpnService.ACTION_STOP
        }
        startService(vpnIntent)

        val overlayIntent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_STOP
        }
        startService(overlayIntent)
    }
}

@Composable
private fun LauncherScreen(
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    var vpnRunning by remember { mutableStateOf(PacketVpnService.isRunning) }
    var vpnStatus by remember { mutableStateOf(PacketVpnService.statusMessage) }

    LaunchedEffect(Unit) {
        while (isActive) {
            vpnRunning = PacketVpnService.isRunning
            vpnStatus = PacketVpnService.statusMessage
            delay(500)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E))
            .systemBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "OF Color Picker",
                color = Color.White,
                fontSize = 22.sp
            )

            Text(
                text = when {
                    vpnRunning -> "Status: Capturing"
                    vpnStatus == "Starting" -> "Status: Starting..."
                    else -> "Status: Stopped"
                },
                color = Color(170, 170, 170),
                fontSize = 14.sp
            )

            Button(
                onClick = if (vpnRunning) onStop else onStart,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (vpnRunning) Color(0xFFE53935) else Color(0xFF43A047)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = if (vpnRunning) "Stop Capture" else "Start Capture",
                    color = Color.White,
                    fontSize = 16.sp
                )
            }

            if (!vpnRunning) {
                Text(
                    text = "Requires overlay + VPN permissions",
                    color = Color(120, 120, 120),
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            val ctx = LocalContext.current
            Text(
                text = "github.com/byzp/of-tools",
                color = Color(100, 160, 220),
                fontSize = 12.sp,
                modifier = Modifier.clickable {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/byzp/of-tools")))
                }
            )
        }
    }
}
