package com.fesu.renjana.core

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.fesu.renjana.R
import com.fesu.renjana.RenjanaApplication
import com.fesu.renjana.utils.RenjanaLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * QuickSwitchBubbleService — Floating "Chat Heads" style overlay for quick instance switching.
 *
 * Shows a draggable 56dp bubble when at least one instance is running.
 * Tap  → expand to a vertical list of running instances.
 * Tap instance row → launch that instance via InstanceLauncher.
 * Long press bubble → dismiss (stop service).
 * Auto-dismissed when InstanceLifecycleService stops all instances.
 *
 * Requires SYSTEM_ALERT_WINDOW permission (Settings.canDrawOverlays).
 * Uses TYPE_APPLICATION_OVERLAY (API 26+; minSdk 29 in this project).
 */
class QuickSwitchBubbleService : Service() {

    companion object {
        private const val TAG = "BubbleService"

        /** Bubble diameter in dp. */
        private const val BUBBLE_SIZE_DP = 56

        /** Elevation shadow dp. */
        private const val BUBBLE_ELEVATION_DP = 8f

        /** Corner radius for the expanded panel in dp. */
        private const val PANEL_RADIUS_DP = 16

        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) {
                RenjanaLog.w(TAG, "SYSTEM_ALERT_WINDOW not granted — bubble service not started")
                return
            }
            context.startService(Intent(context, QuickSwitchBubbleService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, QuickSwitchBubbleService::class.java))
        }
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private val handler = Handler(Looper.getMainLooper())
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    private lateinit var windowManager: WindowManager

    /** Root container holding both bubble and expanded panel. */
    private var rootView: FrameLayout? = null

    /** The small circular bubble. */
    private var bubbleView: FrameLayout? = null

    /** The expanded list panel. */
    private var panelView: LinearLayout? = null

    /** Current WindowManager layout params (position tracking). */
    private lateinit var params: WindowManager.LayoutParams

    private var isExpanded = false

    // ── Touch drag state ──────────────────────────────────────────────────────

    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var initialParamX = 0
    private var initialParamY = 0

    /** true if the finger moved enough to be a drag (suppress tap). */
    private var isDragging = false
    private val DRAG_THRESHOLD_PX by lazy { 8f * resources.displayMetrics.density }

    // ── Service lifecycle ──────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        if (!Settings.canDrawOverlays(this)) {
            RenjanaLog.w(TAG, "SYSTEM_ALERT_WINDOW not granted — stopping bubble service")
            stopSelf()
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        RenjanaApplication.get().bubbleService = this
        createBubble()
        RenjanaLog.i(TAG, "QuickSwitchBubbleService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Refresh the instance list every time we receive a start command
        // (InstanceLifecycleService calls start() whenever instances change).
        handler.post { refreshPanel() }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        job.cancel()
        removeBubbleFromWindow()
        RenjanaApplication.get().bubbleService = null
        RenjanaLog.i(TAG, "QuickSwitchBubbleService destroyed")
        super.onDestroy()
    }

    // ── View construction ──────────────────────────────────────────────────────

    private fun createBubble() {
        val density = resources.displayMetrics.density
        val bubbleSizePx = (BUBBLE_SIZE_DP * density).toInt()

        // WindowManager layout params — position at top-right corner initially
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - bubbleSizePx - (16 * density).toInt()
            y = (120 * density).toInt()
        }

        // ── Root container (WRAP_CONTENT, click-through except on children) ──
        rootView = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // ── Bubble circle ──────────────────────────────────────────────────
        bubbleView = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(bubbleSizePx, bubbleSizePx)
            elevation = BUBBLE_ELEVATION_DP * density

            // Purple gradient circle background
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(0xFF6200EE.toInt(), 0xFF3700B3.toInt())
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
            }

            // Renjana launcher icon in the centre
            val icon = ImageView(this@QuickSwitchBubbleService).apply {
                val iconSizePx = (32 * density).toInt()
                layoutParams = FrameLayout.LayoutParams(iconSizePx, iconSizePx, Gravity.CENTER)
                setImageResource(R.mipmap.ic_launcher_round)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            addView(icon)

            setOnTouchListener(buildBubbleTouchListener())
        }

        rootView!!.addView(bubbleView)
        windowManager.addView(rootView, params)

        // Wire long-press dismiss after view is added to window
        attachLongPressListener()
    }

    // ── Touch listener (drag + tap + long-press) ───────────────────────────────

    private fun buildBubbleTouchListener() = View.OnTouchListener { _, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                initialParamX = params.x
                initialParamY = params.y
                isDragging = false
                false // let long-press still fire
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                if (!isDragging && (Math.abs(dx) > DRAG_THRESHOLD_PX || Math.abs(dy) > DRAG_THRESHOLD_PX)) {
                    isDragging = true
                    // Collapse panel when dragging starts
                    if (isExpanded) collapsePanel()
                }
                if (isDragging) {
                    params.x = (initialParamX + dx).toInt()
                    params.y = (initialParamY + dy).toInt()
                    windowManager.updateViewLayout(rootView, params)
                }
                isDragging
            }

            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    // Tap: toggle expanded panel
                    if (isExpanded) collapsePanel() else expandPanel()
                }
                isDragging = false
                true
            }

            else -> false
        }
    }

    // ── Long-press dismiss ─────────────────────────────────────────────────────

    private fun attachLongPressListener() {
        bubbleView?.setOnLongClickListener {
            RenjanaLog.i(TAG, "Bubble long-pressed — dismissing")
            stopSelf()
            true
        }
    }

    // ── Expanded panel ─────────────────────────────────────────────────────────

    private fun expandPanel() {
        if (isExpanded) return
        isExpanded = true

        val instances = getRunningInstances()
        if (instances.isEmpty()) {
            // Nothing to show — auto-dismiss
            stopSelf()
            return
        }

        val density = resources.displayMetrics.density
        val panelWidthPx = (220 * density).toInt()
        val itemHeightPx = (52 * density).toInt()
        val radiusPx = (PANEL_RADIUS_DP * density)

        panelView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                panelWidthPx,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                // Position panel below the bubble
                val bubbleSizePx = (BUBBLE_SIZE_DP * density).toInt()
                topMargin = bubbleSizePx + (4 * density).toInt()
            }
            elevation = (6 * density)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radiusPx
                setColor(0xFF1E1E2E.toInt())
                setStroke((1 * density).toInt(), 0xFF3700B3.toInt())
            }
            setPadding(
                (8 * density).toInt(), (8 * density).toInt(),
                (8 * density).toInt(), (8 * density).toInt()
            )

            // Header label
            addView(TextView(this@QuickSwitchBubbleService).apply {
                text = "Running Instances"
                setTextColor(0xFFBBBBCC.toInt())
                textSize = 11f
                setPadding(
                    (8 * density).toInt(), (4 * density).toInt(),
                    (8 * density).toInt(), (8 * density).toInt()
                )
            })

            // Instance rows
            instances.forEach { running ->
                val row = buildInstanceRow(running, itemHeightPx, density)
                addView(row)
            }
        }

        rootView?.addView(panelView)

        // Switch to FOCUSABLE so the panel can receive touches
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        windowManager.updateViewLayout(rootView, params)
    }

    private fun collapsePanel() {
        if (!isExpanded) return
        isExpanded = false
        panelView?.let { rootView?.removeView(it) }
        panelView = null

        // Back to not-focusable
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        windowManager.updateViewLayout(rootView, params)
    }

    private fun buildInstanceRow(
        running: RunningInstance,
        heightPx: Int,
        density: Float
    ): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, heightPx
            ).apply { bottomMargin = (4 * density).toInt() }
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                (12 * density).toInt(), 0,
                (12 * density).toInt(), 0
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = (8 * density)
                setColor(Color.TRANSPARENT)
            }

            // App icon placeholder (coloured circle with first letter)
            val iconSizePx = (36 * density).toInt()
            val iconView = FrameLayout(this@QuickSwitchBubbleService).apply {
                layoutParams = LinearLayout.LayoutParams(iconSizePx, iconSizePx)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    // Deterministic colour from packageName hash
                    val hue = (running.packageName.hashCode() and 0xFFFFFF) % 360
                    setColor(Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.6f, 0.8f)))
                }
                addView(TextView(this@QuickSwitchBubbleService).apply {
                    text = running.appName.firstOrNull()?.uppercase() ?: "?"
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                })
            }
            addView(iconView)

            // Instance name + status
            val textCol = LinearLayout(this@QuickSwitchBubbleService).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding((10 * density).toInt(), 0, 0, 0)

                addView(TextView(this@QuickSwitchBubbleService).apply {
                    text = running.appName
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(TextView(this@QuickSwitchBubbleService).apply {
                    text = running.state.name.lowercase()
                        .replaceFirstChar { it.uppercase() }
                    setTextColor(
                        when (running.state) {
                            InstanceState.RUNNING -> 0xFF4CAF50.toInt()
                            InstanceState.PAUSED  -> 0xFFFFB74D.toInt()
                            else                  -> 0xFF9E9E9E.toInt()
                        }
                    )
                    textSize = 11f
                })
            }
            addView(textCol)

            // Tap to launch
            setOnClickListener {
                collapsePanel()
                launchInstance(running.instanceId)
            }

            // Highlight on press
            isClickable = true
            isFocusable = true
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** Pull running instances directly from InstanceLifecycleService via application reference. */
    private fun getRunningInstances(): List<RunningInstance> {
        return try {
            RenjanaApplication.get().lifecycleService?.getRunningInstances() ?: emptyList()
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Could not read running instances: ${e.message}")
            emptyList()
        }
    }

    private fun launchInstance(instanceId: String) {
        scope.launch {
            try {
                RenjanaApplication.get().instanceLauncher.launchInstance(instanceId)
            } catch (e: Throwable) {
                RenjanaLog.e(TAG, "Failed to launch instance $instanceId from bubble: ${e.message}")
            }
        }
    }

    /** Called by InstanceLifecycleService whenever the running set changes. */
    fun refreshPanel() {
        if (!isExpanded) return
        collapsePanel()
        expandPanel()
    }

    private fun removeBubbleFromWindow() {
        try {
            rootView?.let { windowManager.removeView(it) }
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "removeView failed: ${e.message}")
        }
        rootView = null
        bubbleView = null
        panelView = null
    }
}
