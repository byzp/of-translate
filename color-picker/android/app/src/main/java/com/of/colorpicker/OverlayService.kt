package com.of.colorpicker

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class OverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    companion object {
        private const val TAG = "OverlayService"
        private const val NOTIFICATION_CHANNEL = "overlay"
        private const val NOTIFICATION_ID = 1653

        const val ACTION_START = "com.of.colorpicker.OVERLAY_START"
        const val ACTION_STOP = "com.of.colorpicker.OVERLAY_STOP"

        @Volatile
        var dyeParams: DyeParams? = null

        @Volatile
        var isRunning = false
            private set
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    // Drag state
    private var isDragging = false
    private var dragStartX = 0
    private var dragStartY = 0
    private var overlayStartX = 0
    private var overlayStartY = 0

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                startForegroundNotification()
                showOverlay()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeOverlay()
        isRunning = false
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay() {
        if (overlayView != null) return
        isRunning = true

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val density = resources.displayMetrics.density
        val width = (320 * density).toInt()
        val height = (260 * density).toInt()

        val params = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 100
        }
        layoutParams = params

        val composeView = ComposeView(this).also {
            it.setViewTreeLifecycleOwner(this)
            it.setViewTreeSavedStateRegistryOwner(this)
        }

        composeView.setContent {
            MaterialTheme {
                OverlayContent()
            }
        }

        // Handle drag on the root view — dispatch touch to Compose children normally,
        // but intercept drags that start on the drag bar area.
        composeView.setOnTouchListener { _, event ->
            handleDrag(event)
        }

        wm.addView(composeView, params)
        overlayView = composeView
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    private fun handleDrag(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = false
                dragStartX = event.rawX.toInt()
                dragStartY = event.rawY.toInt()
                val p = layoutParams ?: return false
                overlayStartX = p.x
                overlayStartY = p.y
                return false // let Compose children handle the down
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX.toInt() - dragStartX
                val dy = event.rawY.toInt() - dragStartY
                if (!isDragging && (Math.abs(dx) > 10 || Math.abs(dy) > 10)) {
                    isDragging = true
                }
                if (isDragging) {
                    val p = layoutParams ?: return true
                    val wm = windowManager ?: return true
                    p.x = overlayStartX + dx
                    p.y = overlayStartY + dy
                    try { wm.updateViewLayout(overlayView, p) } catch (_: Exception) { }
                    return true // consumed
                }
                return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDragging = isDragging
                isDragging = false
                return wasDragging // consume only if we were dragging
            }
        }
        return false
    }

    /** Resize and reposition the overlay (for collapse/expand). */
    fun updateOverlayLayout(x: Int, width: Int, height: Int) {
        val params = layoutParams ?: return
        val wm = windowManager ?: return
        params.x = x
        params.width = width
        params.height = height
        try { wm.updateViewLayout(overlayView, params) } catch (_: Exception) { }
    }

    /** Get current layout position/size. Returns [x, y, width, height] or null. */
    fun getLayoutBounds(): IntArray? {
        val p = layoutParams ?: return null
        return intArrayOf(p.x, p.y, p.width, p.height)
    }

    /** Toggle FLAG_NOT_FOCUSABLE so text input can work in the overlay. */
    fun setFocusable(focusable: Boolean) {
        val params = layoutParams ?: return
        val wm = windowManager ?: return
        if (focusable) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        try { wm.updateViewLayout(overlayView, params) } catch (_: Exception) { }
    }

    private fun removeOverlay() {
        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) { }
        }
        overlayView = null
        windowManager = null
        layoutParams = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL,
            "Overlay",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Color picker overlay"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startForegroundNotification() {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("OF Color Picker")
            .setContentText("Overlay active")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Stop", stopIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}

@Composable
private fun OverlayContent() {
    val native = remember { NativeLib() }
    var targetHex by remember { mutableStateOf("#ffffff") }
    var result by remember { mutableStateOf<SearchResult?>(null) }
    var logLines by remember { mutableStateOf(listOf<String>()) }
    var latestParams by remember { mutableStateOf<DyeParams?>(null) }
    var showColorPicker by remember { mutableStateOf(false) }

    // Poll for dye params from PacketVpnService
    LaunchedEffect(Unit) {
        while (isActive) {
            val params = OverlayService.dyeParams
            if (params != null) {
                OverlayService.dyeParams = null
                latestParams = params
            }
            delay(100)
        }
    }

    // Run search when target or params change
    LaunchedEffect(targetHex, latestParams) {
        val params = latestParams ?: return@LaunchedEffect
        val searchResult = withContext(Dispatchers.Default) {
            native.search(targetHex, params.pictureId, params.params, params.pictureId)
        } ?: return@LaunchedEffect

        val sim = searchResult[0]
        val uvy = searchResult[1]
        val slot = searchResult[2].toInt()
        val colors = (0 until 5).map { i ->
            val r = searchResult[3 + i * 4].toInt()
            val g = searchResult[3 + i * 4 + 1].toInt()
            val b = searchResult[3 + i * 4 + 2].toInt()
            androidx.compose.ui.graphics.Color(r, g, b)
        }
        val matchedHex = native.lastMatchedHex
        val searchedTargetHex = native.lastTargetHex

        result = SearchResult(
            targetHex = searchedTargetHex,
            matchedHex = matchedHex,
            similarity = sim,
            uvy = uvy,
            slot = slot,
            colors = colors
        )
        val line = "$searchedTargetHex → $matchedHex  sim=${"%.1f".format(sim * 100)}%  " +
            "uvy=${"%.3f".format(uvy)}  slot=$slot"
        logLines = logLines + line
    }

    ColorPickerOverlay(
        targetHex = targetHex,
        onTargetClick = { showColorPicker = true },
        result = result,
        logLines = logLines
    )

    // When color picker closes, restore overlay size
    val context = LocalContext.current
    LaunchedEffect(showColorPicker) {
        if (!showColorPicker) {
            val svc = context as? OverlayService ?: return@LaunchedEffect
            val bounds = svc.getLayoutBounds() ?: return@LaunchedEffect
            val d = context.resources.displayMetrics.density
            val normalWidth = (280 * d).toInt()
            val normalHeight = (220 * d).toInt()
            val curRight = bounds[0] + bounds[2]
            val newX = curRight - normalWidth
            svc.updateOverlayLayout(newX, normalWidth, normalHeight)
        }
    }

    if (showColorPicker) {
        SimpleColorPickerDialog(
            currentColor = targetHex,
            onColorSelected = { hex ->
                targetHex = hex
                showColorPicker = false
            },
            onDismiss = { showColorPicker = false }
        )
    }
}
