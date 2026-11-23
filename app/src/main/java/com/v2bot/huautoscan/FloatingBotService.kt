package com.v2bot.huautoscan

import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.util.Locale

class FloatingBotService : Service() {

    companion object {
        const val ACTION_START = "com.v2bot.huautoscan.ACTION_START"
        private const val CHANNEL_ID = "hu_auto_scan_channel"
        private const val NOTIF_ID = 4201
    }

    private enum class BotState {
        IDLE,
        RUNNING,
        PAUSED_BETWEEN_ARTICLES,
        PAUSED_MANUAL
    }

    private lateinit var windowManager: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var toneGenerator: ToneGenerator? = null

    private var controlView: View? = null
    private var controlParams: WindowManager.LayoutParams? = null

    private var eanTargetView: View? = null
    private var eanTargetParams: WindowManager.LayoutParams? = null

    private var saveTargetView: View? = null
    private var saveTargetParams: WindowManager.LayoutParams? = null

    private lateinit var tvStatus: TextView
    private lateinit var btnPlayPause: Button
    private lateinit var btnToggleTargets: TextView
    private lateinit var tvDelayQuick: TextView

    private var state: BotState = BotState.IDLE
    private var targetsHidden: Boolean = false
    private var lockedEanX: Float = 0f
    private var lockedEanY: Float = 0f
    private var lockedSaveX: Float = 0f
    private var lockedSaveY: Float = 0f

    private var articleQueue: List<Int> = emptyList()
    private var articleLabels: List<String> = emptyList()
    private var articleBarcodes: List<String> = emptyList()
    private var currentArticleIdx: Int = 0
    private var currentArticleDone: Int = 0
    private var totalDone: Int = 0
    private var totalPlanned: Int = 0
    private var countdownRemaining: Int = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
        } catch (_: Exception) {
            toneGenerator = null
        }

        startForegroundNotification()
        setupOverlayViews()

        AutoTapAccessibilityService.onEmergencyStopRequested = {
            handler.post {
                if (state != BotState.IDLE) {
                    stopBotRun("Volume Down se ruk gaya")
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        reloadConfigAndRefreshUI()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        AutoTapAccessibilityService.onEmergencyStopRequested = null
        removeOverlayView(controlView)
        removeOverlayView(eanTargetView)
        removeOverlayView(saveTargetView)
        try {
            toneGenerator?.release()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun startForegroundNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "HU Auto Scan Bot",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HU Auto Scan Bot v2 Active")
            .setContentText("Floating target ready. Volume Down = Emergency Stop.")
            .setSmallIcon(R.drawable.ic_bot_notif)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun setupOverlayViews() {
        val themedContext = ContextThemeWrapper(this, R.style.Theme_HUAutoScanBot)
        val inflater = LayoutInflater.from(themedContext)
        val dm = resources.displayMetrics
        val overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        val defaultEanX = (dm.widthPixels * 0.84f).toInt()
        val defaultEanY = (dm.heightPixels * 0.36f).toInt()
        val (eanX, eanY) = BotConfig.getEanPos(this, defaultEanX, defaultEanY)

        eanTargetView = inflater.inflate(R.layout.overlay_target, null).apply {
            findViewById<TextView>(R.id.tvTargetLabel).text = "EAN"
            findViewById<FrameLayout>(R.id.targetCircle).setBackgroundResource(R.drawable.bg_target_ean)
        }
        eanTargetParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = eanX
            y = eanY
        }
        makeDraggable(eanTargetView!!, eanTargetParams!!) { x, y ->
            BotConfig.setEanPos(this, x, y)
        }
        windowManager.addView(eanTargetView, eanTargetParams)

        val defaultSaveX = (dm.widthPixels * 0.72f).toInt()
        val defaultSaveY = (dm.heightPixels * 0.56f).toInt()
        val (saveX, saveY) = BotConfig.getSavePos(this, defaultSaveX, defaultSaveY)

        saveTargetView = inflater.inflate(R.layout.overlay_target, null).apply {
            findViewById<TextView>(R.id.tvTargetLabel).text = "SAVE"
            findViewById<FrameLayout>(R.id.targetCircle).setBackgroundResource(R.drawable.bg_target_save)
        }
        saveTargetParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = saveX
            y = saveY
        }
        makeDraggable(saveTargetView!!, saveTargetParams!!) { x, y ->
            BotConfig.setSavePos(this, x, y)
        }
        windowManager.addView(saveTargetView, saveTargetParams)

        controlView = inflater.inflate(R.layout.overlay_controls, null)
        controlParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (dm.widthPixels * 0.05f).toInt()
            y = (dm.heightPixels * 0.68f).toInt()
        }

        tvStatus = controlView!!.findViewById(R.id.tvOverlayStatus)
        btnPlayPause = controlView!!.findViewById(R.id.btnPlayPause)
        btnToggleTargets = controlView!!.findViewById(R.id.btnToggleTargets)
        controlView!!.findViewById<TextView>(R.id.btnTestTap).setOnClickListener { performTestTap() }
        tvDelayQuick = controlView!!.findViewById(R.id.tvDelayQuick)

        val dragHandle = controlView!!.findViewById<View>(R.id.dragHandle)
        makeDraggable(dragHandle, controlParams!!, viewToUpdate = controlView)

        controlView!!.findViewById<TextView>(R.id.btnOverlayClose).setOnClickListener {
            stopSelf()
        }

        btnPlayPause.setOnClickListener { onPlayPauseClicked() }

        controlView!!.findViewById<Button>(R.id.btnStopRun).setOnClickListener {
            stopBotRun("Stopped")
        }

        controlView!!.findViewById<Button>(R.id.btnSyncOverlay).setOnClickListener {
            tvStatus.text = "Syncing PC..."
            PcSyncClient.pullQueue(this) { res ->
                handler.post {
                    if (res.ok) {
                        if (state != BotState.IDLE) stopBotRun("Synced")
                        reloadConfigAndRefreshUI()
                        Toast.makeText(this, res.message, Toast.LENGTH_SHORT).show()
                    } else {
                        tvStatus.text = res.message
                        Toast.makeText(this, res.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        controlView!!.findViewById<Button>(R.id.btnQuickEdit).setOnClickListener {
            showQuickEditDialog()
        }

        btnToggleTargets.setOnClickListener {
            targetsHidden = !targetsHidden
            applyTargetVisibilityAndLock()
        }

        windowManager.addView(controlView, controlParams)
        reloadConfigAndRefreshUI()
    }

    private fun reloadConfigAndRefreshUI() {
        if (state == BotState.IDLE) {
            articleQueue = BotConfig.getQueueList(this)
            articleLabels = BotConfig.getLabelsList(this)
            articleBarcodes = BotConfig.getBarcodesList(this)
            totalPlanned = articleQueue.sum()
            val syncTag = if (BotConfig.isSyncEnabled(this)) " [PC Sync]" else ""
            tvStatus.text = if (articleQueue.isEmpty()) {
                "QTY set karein ya SYNC dabayein"
            } else {
                "Ready$syncTag • ${articleQueue.size} Art ($totalPlanned)"
            }
        }
        val sec = BotConfig.getTapDelayMs(this) / 1000.0
        tvDelayQuick.text = String.format(Locale.US, "Delay: %.1fs", sec)
        applyTargetVisibilityAndLock()
    }

    private fun captureTargetCoordinates() {
        val (ex, ey) = getCenterOnScreen(eanTargetView, eanTargetParams)
        lockedEanX = ex
        lockedEanY = ey
        val (sx, sy) = getCenterOnScreen(saveTargetView, saveTargetParams)
        lockedSaveX = sx
        lockedSaveY = sy
    }

    private fun applyTargetVisibilityAndLock() {
        val isRunning = state == BotState.RUNNING
        val showSave = BotConfig.isAutoSave(this)

        // Hide targets completely (View.GONE) while running so Realme/ColorOS/MIUI never blocks tap gestures
        eanTargetView?.visibility = if (targetsHidden || isRunning) View.GONE else View.VISIBLE
        saveTargetView?.visibility = if (targetsHidden || isRunning || !showSave) View.GONE else View.VISIBLE
        btnToggleTargets.text = if (targetsHidden) "Show Targets" else "Hide Targets"

        setTargetTouchPassThrough(eanTargetView, eanTargetParams, passThrough = isRunning)
        setTargetTouchPassThrough(saveTargetView, saveTargetParams, passThrough = isRunning)
    }

    private fun performTestTap() {
        val service = AutoTapAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, "Accessibility Service OFF hai!", Toast.LENGTH_SHORT).show()
            return
        }
        captureTargetCoordinates()
        eanTargetView?.visibility = View.GONE
        saveTargetView?.visibility = View.GONE
        tvStatus.text = "Testing tap..."
        handler.postDelayed({
            val firstBc = BotConfig.getBarcodesList(this).firstOrNull() ?: ""
            service.inputEanBarcode(firstBc, lockedEanX, lockedEanY) { ok, info ->
                handler.post {
                    applyTargetVisibilityAndLock()
                    tvStatus.text = if (ok) "$info OK" else "Action Failed! Accessibility check karein"
                }
            }
        }, 120L)
    }

    private fun setTargetTouchPassThrough(
        view: View?,
        params: WindowManager.LayoutParams?,
        passThrough: Boolean
    ) {
        if (view == null || params == null) return
        val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        params.flags = if (passThrough) {
            baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            baseFlags
        }
        view.alpha = if (passThrough) 0.45f else 1.0f
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
        }
    }

    private fun onPlayPauseClicked() {
        when (state) {
            BotState.IDLE -> startNewBoxRun()
            BotState.RUNNING -> {
                handler.removeCallbacksAndMessages(null)
                state = BotState.PAUSED_MANUAL
                btnPlayPause.text = "RESUME"
                tvStatus.text = "Paused • Art ${currentArticleIdx + 1}/${articleQueue.size} ($currentArticleDone/${articleQueue[currentArticleIdx]})"
                applyTargetVisibilityAndLock()
            }
            BotState.PAUSED_MANUAL, BotState.PAUSED_BETWEEN_ARTICLES -> {
                handler.removeCallbacksAndMessages(null)
                captureTargetCoordinates()
                state = BotState.RUNNING
                btnPlayPause.text = "PAUSE"
                applyTargetVisibilityAndLock()
                scheduleNextTap(350L)
            }
        }
    }

    private fun startNewBoxRun() {
        if (!AutoTapAccessibilityService.isServiceActive()) {
            Toast.makeText(this, "Accessibility Service OFF hai! App me jaakar ON karein.", Toast.LENGTH_LONG).show()
            return
        }
        articleQueue = BotConfig.getQueueList(this)
        articleLabels = BotConfig.getLabelsList(this)
        articleBarcodes = BotConfig.getBarcodesList(this)
            articleBarcodes = BotConfig.getBarcodesList(this)
        if (articleQueue.isEmpty()) {
            Toast.makeText(this, "SYNC ya QTY se article quantities daalein", Toast.LENGTH_SHORT).show()
            return
        }
        currentArticleIdx = 0
        currentArticleDone = 0
        totalDone = 0
        totalPlanned = articleQueue.sum()
        captureTargetCoordinates()
        state = BotState.RUNNING
        btnPlayPause.text = "PAUSE"
        applyTargetVisibilityAndLock()

        // Tell PC Display to show Article 1 (index 0)
        PcSyncClient.sendBotEvent(
            this,
            "/api/bot-state",
            mapOf("index" to 0, "done" to 0, "status" to "SCANNING")
        )

        AutoTapAccessibilityService.instance?.ensureMixArticleState(articleQueue.size > 1)
        tvStatus.text = "Starting Art 1/${articleQueue.size}..."
        scheduleNextTap(700L)
    }

    private fun stopBotRun(reason: String) {
        handler.removeCallbacksAndMessages(null)
        state = BotState.IDLE
        btnPlayPause.text = "PLAY"
        applyTargetVisibilityAndLock()
        tvStatus.text = "$reason • ${articleQueue.size} Art ($totalPlanned)"
        PcSyncClient.sendBotEvent(
            this,
            "/api/bot-state",
            mapOf("index" to currentArticleIdx, "done" to currentArticleDone, "status" to reason)
        )
    }

    private fun scheduleNextTap(delayMs: Long) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ executeSingleTap() }, delayMs)
    }

    private fun executeSingleTap() {
        if (state != BotState.RUNNING) return
        val service = AutoTapAccessibilityService.instance
        if (service == null) {
            stopBotRun("Accessibility OFF")
            return
        }

        val targetQty = articleQueue[currentArticleIdx]
        val label = articleLabels.getOrNull(currentArticleIdx) ?: "Art ${currentArticleIdx + 1}"
        val barcode = articleBarcodes.getOrNull(currentArticleIdx) ?: ""
        currentArticleDone++
        totalDone++
        tvStatus.text = "$label • $currentArticleDone/$targetQty (Tot $totalDone/$totalPlanned)"

        PcSyncClient.sendBotEvent(
            this,
            "/api/bot-state",
            mapOf("index" to currentArticleIdx, "done" to currentArticleDone, "status" to "SCANNING")
        )

        service.inputEanBarcode(barcode, lockedEanX, lockedEanY) { ok, _ ->
            handler.post {
                if (state != BotState.RUNNING) return@post
                if (!ok) {
                    stopBotRun("Tap Failed (Accessibility check karein)")
                    return@post
                }
                if (currentArticleDone >= targetQty) {
                    onArticleCompleted()
                } else {
                    scheduleNextTap(BotConfig.getTapDelayMs(this))
                }
            }
        }
    }

    private fun onArticleCompleted() {
        val completedArtNo = currentArticleIdx + 1
        currentArticleIdx++
        currentArticleDone = 0

        if (currentArticleIdx >= articleQueue.size) {
            playAlert(isBoxComplete = true)
            PcSyncClient.sendBotEvent(
                this,
                "/api/bot-state",
                mapOf("index" to (articleQueue.size - 1), "done" to articleQueue.last(), "status" to "BOX_COMPLETE")
            )
            if (BotConfig.isAutoSave(this)) {
                tvStatus.text = "Box Done! Tapping SAVE..."
                handler.postDelayed({
                    AutoTapAccessibilityService.instance?.performTap(lockedSaveX, lockedSaveY) {
                        handler.post {
                            stopBotRun("Saved ($totalDone/$totalPlanned)")
                        }
                    }
                }, BotConfig.getTapDelayMs(this))
            } else {
                stopBotRun("Box Done ($totalDone/$totalPlanned)")
            }
            return
        }

        // Immediately switch PC display to NEXT article!
        PcSyncClient.sendBotEvent(
            this,
            "/api/bot-state",
            mapOf("index" to currentArticleIdx, "done" to 0, "status" to "SWITCHING")
        )

        playAlert(isBoxComplete = false)
        state = BotState.PAUSED_BETWEEN_ARTICLES
        btnPlayPause.text = "NEXT"
        applyTargetVisibilityAndLock()

        val nextQty = articleQueue[currentArticleIdx]
        val nextLabel = articleLabels.getOrNull(currentArticleIdx) ?: "Art ${currentArticleIdx + 1}"
        if (BotConfig.isAutoResumeNext(this)) {
            countdownRemaining = BotConfig.getArticlePauseSec(this)
            runArticleCountdown(completedArtNo, nextLabel, nextQty)
        } else {
            tvStatus.text = "Art $completedArtNo Done! Next: $nextLabel ($nextQty)"
        }
    }

    private fun runArticleCountdown(completedArtNo: Int, nextLabel: String, nextQty: Int) {
        if (state != BotState.PAUSED_BETWEEN_ARTICLES) return
        if (countdownRemaining <= 0) {
            state = BotState.RUNNING
            btnPlayPause.text = "PAUSE"
            applyTargetVisibilityAndLock()
            scheduleNextTap(200L)
            return
        }
        tvStatus.text = "Next: $nextLabel ($nextQty) in ${countdownRemaining}s..."
        countdownRemaining--
        handler.postDelayed({ runArticleCountdown(completedArtNo, nextLabel, nextQty) }, 1000L)
    }

    private fun getCenterOnScreen(view: View?, params: WindowManager.LayoutParams?): Pair<Float, Float> {
        if (view != null && view.width > 0 && view.height > 0) {
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            return Pair(loc[0] + view.width / 2f, loc[1] + view.height / 2f)
        }
        val x = (params?.x ?: 300).toFloat() + 60f
        val y = (params?.y ?: 600).toFloat() + 60f
        return Pair(x, y)
    }

    private fun playAlert(isBoxComplete: Boolean) {
        if (!BotConfig.isSoundVibrate(this)) return
        try {
            if (isBoxComplete) {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 350)
            } else {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 250)
            }
        } catch (_: Exception) {
        }

        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            val duration = if (isBoxComplete) 400L else 180L
            vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
    }

    private fun showQuickEditDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 12)
        }
        val inputQueue = EditText(this).apply {
            hint = "Example: 10, 15, 5"
            setText(BotConfig.getQueueRaw(this@FloatingBotService))
        }
        val inputDelay = EditText(this).apply {
            hint = "Delay seconds (e.g. 2.5)"
            setText(String.format(Locale.US, "%.1f", BotConfig.getTapDelayMs(this@FloatingBotService) / 1000.0))
        }
        val lbl1 = TextView(this).apply { text = "Article Quantities (comma separated):" }
        val lbl2 = TextView(this).apply {
            text = "Scan Delay (seconds, 1.0 to 6.0):"
            setPadding(0, 16, 0, 0)
        }
        container.addView(lbl1)
        container.addView(inputQueue)
        container.addView(lbl2)
        container.addView(inputDelay)

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Box Program Edit")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                BotConfig.setQueueRaw(this, inputQueue.text.toString())
                val sec = inputDelay.text.toString().trim().toDoubleOrNull()
                if (sec != null) {
                    BotConfig.setTapDelayMs(this, (sec * 1000.0).toLong())
                }
                if (state != BotState.IDLE) {
                    stopBotRun("Updated")
                }
                reloadConfigAndRefreshUI()
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
    }

    private fun makeDraggable(
        touchView: View,
        params: WindowManager.LayoutParams,
        viewToUpdate: View? = null,
        onDrop: ((Int, Int) -> Unit)? = null
    ) {
        val target = viewToUpdate ?: touchView
        touchView.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchStartX = 0f
            private var touchStartY = 0f

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        touchStartX = event.rawX
                        touchStartY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = startX + (event.rawX - touchStartX).toInt()
                        params.y = startY + (event.rawY - touchStartY).toInt()
                        try {
                            windowManager.updateViewLayout(target, params)
                        } catch (_: Exception) {
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        onDrop?.invoke(params.x, params.y)
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun removeOverlayView(view: View?) {
        if (view != null) {
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {
            }
        }
    }
}
