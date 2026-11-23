package com.v2bot.huautoscan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvOverlayStatus: TextView
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var cbSyncEnabled: CheckBox
    private lateinit var etServerUrl: EditText
    private lateinit var tvSyncStatus: TextView
    private lateinit var etArticleQueue: EditText
    private lateinit var tvQueueSummary: TextView
    private lateinit var tvTapDelayLabel: TextView
    private lateinit var sbTapDelay: SeekBar
    private lateinit var cbAutoResumeNext: CheckBox
    private lateinit var tvArticlePauseLabel: TextView
    private lateinit var sbArticlePause: SeekBar
    private lateinit var cbAutoSave: CheckBox
    private lateinit var cbSoundVibrate: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        loadSavedSettings()
        setupListeners()
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
        etArticleQueue.setText(BotConfig.getQueueRaw(this))
        updateQueueSummary()
    }

    private fun bindViews() {
        tvOverlayStatus = findViewById(R.id.tvOverlayStatus)
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        cbSyncEnabled = findViewById(R.id.cbSyncEnabled)
        etServerUrl = findViewById(R.id.etServerUrl)
        tvSyncStatus = findViewById(R.id.tvSyncStatus)
        etArticleQueue = findViewById(R.id.etArticleQueue)
        tvQueueSummary = findViewById(R.id.tvQueueSummary)
        tvTapDelayLabel = findViewById(R.id.tvTapDelayLabel)
        sbTapDelay = findViewById(R.id.sbTapDelay)
        cbAutoResumeNext = findViewById(R.id.cbAutoResumeNext)
        tvArticlePauseLabel = findViewById(R.id.tvArticlePauseLabel)
        sbArticlePause = findViewById(R.id.sbArticlePause)
        cbAutoSave = findViewById(R.id.cbAutoSave)
        cbSoundVibrate = findViewById(R.id.cbSoundVibrate)
    }

    private fun loadSavedSettings() {
        cbSyncEnabled.isChecked = BotConfig.isSyncEnabled(this)
        etServerUrl.setText(BotConfig.getServerUrl(this))

        etArticleQueue.setText(BotConfig.getQueueRaw(this))
        updateQueueSummary()

        val delayMs = BotConfig.getTapDelayMs(this)
        sbTapDelay.progress = ((delayMs - 1000L) / 100L).toInt().coerceIn(0, 50)
        updateDelayLabel(delayMs)

        cbAutoResumeNext.isChecked = BotConfig.isAutoResumeNext(this)

        val pauseSec = BotConfig.getArticlePauseSec(this)
        sbArticlePause.progress = (pauseSec - 2).coerceIn(0, 13)
        tvArticlePauseLabel.text = "Article Switch Pause: ${pauseSec} sec"

        cbAutoSave.isChecked = BotConfig.isAutoSave(this)
        cbSoundVibrate.isChecked = BotConfig.isSoundVibrate(this)
    }

    private fun setupListeners() {
        findViewById<Button>(R.id.btnGrantOverlay).setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        findViewById<Button>(R.id.btnGrantAccessibility).setOnClickListener {
            Toast.makeText(
                this,
                "Installed Apps me 'HU Auto Scan Bot' ko ON karein",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        cbSyncEnabled.setOnCheckedChangeListener { _, isChecked ->
            BotConfig.setSyncEnabled(this, isChecked)
        }

        etServerUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                BotConfig.setServerUrl(this@MainActivity, s?.toString().orEmpty())
            }
        })

        findViewById<Button>(R.id.btnPullFromPc).setOnClickListener {
            BotConfig.setServerUrl(this, etServerUrl.text.toString())
            tvSyncStatus.text = "Connecting..."
            tvSyncStatus.setTextColor(ContextCompat.getColor(this, R.color.brand_blue))
            PcSyncClient.pullQueue(this) { result ->
                runOnUiThread {
                    if (result.ok) {
                        etArticleQueue.setText(BotConfig.getQueueRaw(this))
                        updateQueueSummary()
                        tvSyncStatus.text = result.message
                        tvSyncStatus.setTextColor(ContextCompat.getColor(this, R.color.brand_green))
                    } else {
                        tvSyncStatus.text = result.message
                        tvSyncStatus.setTextColor(ContextCompat.getColor(this, R.color.brand_red))
                    }
                }
            }
        }

        etArticleQueue.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                BotConfig.setQueueRaw(this@MainActivity, s?.toString().orEmpty())
                updateQueueSummary()
            }
        })

        findViewById<Button>(R.id.btnAdd5).setOnClickListener { appendQty(5) }
        findViewById<Button>(R.id.btnAdd10).setOnClickListener { appendQty(10) }
        findViewById<Button>(R.id.btnAdd15).setOnClickListener { appendQty(15) }
        findViewById<Button>(R.id.btnAdd20).setOnClickListener { appendQty(20) }
        findViewById<Button>(R.id.btnClearQueue).setOnClickListener {
            etArticleQueue.setText("")
            BotConfig.setLabelsRaw(this, "")
        }

        sbTapDelay.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val ms = 1000L + progress * 100L
                BotConfig.setTapDelayMs(this@MainActivity, ms)
                updateDelayLabel(ms)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        cbAutoResumeNext.setOnCheckedChangeListener { _, isChecked ->
            BotConfig.setAutoResumeNext(this, isChecked)
        }

        sbArticlePause.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val sec = 2 + progress
                BotConfig.setArticlePauseSec(this@MainActivity, sec)
                tvArticlePauseLabel.text = "Article Switch Pause: ${sec} sec"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        cbAutoSave.setOnCheckedChangeListener { _, isChecked ->
            BotConfig.setAutoSave(this, isChecked)
        }

        cbSoundVibrate.setOnCheckedChangeListener { _, isChecked ->
            BotConfig.setSoundVibrate(this, isChecked)
        }

        findViewById<Button>(R.id.btnStartOverlay).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Pehle Overlay Permission Allow karein", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!AutoTapAccessibilityService.isServiceActive()) {
                Toast.makeText(this, "Pehle Accessibility Service ON karein", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val queue = BotConfig.getQueueList(this)
            if (queue.isEmpty()) {
                Toast.makeText(this, "Kam se kam 1 article quantity daalein ya PC se Sync karein", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(this, FloatingBotService::class.java).apply {
                action = FloatingBotService.ACTION_START
            }
            ContextCompat.startForegroundService(this, intent)
            Toast.makeText(this, "Floating Bot start ho gaya! Ab Vendor HU app open karein.", Toast.LENGTH_LONG).show()
        }

        findViewById<Button>(R.id.btnStopOverlay).setOnClickListener {
            stopService(Intent(this, FloatingBotService::class.java))
            Toast.makeText(this, "Floating Bot band kar diya gaya.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun appendQty(qty: Int) {
        val current = etArticleQueue.text.toString().trim()
        val updated = if (current.isEmpty()) "$qty" else "$current, $qty"
        etArticleQueue.setText(updated)
        etArticleQueue.setSelection(updated.length)
    }

    private fun updateQueueSummary() {
        val list = BotConfig.getQueueList(this)
        if (list.isEmpty()) {
            tvQueueSummary.text = "Koi valid quantity nahi hai"
            tvQueueSummary.setTextColor(ContextCompat.getColor(this, R.color.brand_red))
        } else {
            val total = list.sum()
            tvQueueSummary.text = "Total: ${list.size} Article(s) • $total Pcs  ${list}"
            tvQueueSummary.setTextColor(ContextCompat.getColor(this, R.color.brand_green))
        }
    }

    private fun updateDelayLabel(ms: Long) {
        val sec = ms / 1000.0
        tvTapDelayLabel.text = String.format("Scan Delay (har tap ke beech): %.1f sec", sec)
    }

    private fun refreshPermissionStatus() {
        val hasOverlay = Settings.canDrawOverlays(this)
        if (hasOverlay) {
            tvOverlayStatus.text = "Overlay Permission: ALLOWED"
            tvOverlayStatus.setTextColor(ContextCompat.getColor(this, R.color.brand_green))
        } else {
            tvOverlayStatus.text = "Overlay Permission: Pending"
            tvOverlayStatus.setTextColor(ContextCompat.getColor(this, R.color.brand_red))
        }

        val hasAccess = AutoTapAccessibilityService.isServiceActive()
        if (hasAccess) {
            tvAccessibilityStatus.text = "Accessibility Service: ON"
            tvAccessibilityStatus.setTextColor(ContextCompat.getColor(this, R.color.brand_green))
        } else {
            tvAccessibilityStatus.text = "Accessibility Service: OFF"
            tvAccessibilityStatus.setTextColor(ContextCompat.getColor(this, R.color.brand_red))
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1001
                )
            }
        }
    }
}
