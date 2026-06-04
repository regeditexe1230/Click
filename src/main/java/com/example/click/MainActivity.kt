package com.example.click

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var radioGroupMode: RadioGroup
    private lateinit var radioSwipe: RadioButton
    private lateinit var swipeParams: LinearLayout
    private lateinit var radioGroupSwipeMethod: RadioGroup
    private lateinit var radioSwipeManual: RadioButton
    private lateinit var radioSwipeGesture: RadioButton
    private lateinit var manualSwipeParams: LinearLayout
    private lateinit var gestureSwipeSection: LinearLayout
    private lateinit var inputSwipeX1: EditText
    private lateinit var inputSwipeY1: EditText
    private lateinit var inputSwipeX2: EditText
    private lateinit var inputSwipeY2: EditText
    private lateinit var inputSwipeDuration: EditText
    private lateinit var inputDelay: EditText
    private lateinit var inputRepeat: EditText
    private lateinit var checkInfinite: CheckBox
    private lateinit var btnStartFloating: Button
    private lateinit var btnStartOverlay: View
    private lateinit var btnStopFloating: Button
    private lateinit var btnRecordGesture: Button
    private lateinit var lblRecordedStatus: TextView
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        radioGroupMode = findViewById(R.id.radioGroupMode)
        radioSwipe = findViewById(R.id.radioSwipe)
        swipeParams = findViewById(R.id.swipeParams)
        radioGroupSwipeMethod = findViewById(R.id.radioGroupSwipeMethod)
        radioSwipeManual = findViewById(R.id.radioSwipeManual)
        radioSwipeGesture = findViewById(R.id.radioSwipeGesture)
        manualSwipeParams = findViewById(R.id.manualSwipeParams)
        gestureSwipeSection = findViewById(R.id.gestureSwipeSection)
        inputSwipeX1 = findViewById(R.id.inputSwipeX1)
        inputSwipeY1 = findViewById(R.id.inputSwipeY1)
        inputSwipeX2 = findViewById(R.id.inputSwipeX2)
        inputSwipeY2 = findViewById(R.id.inputSwipeY2)
        inputSwipeDuration = findViewById(R.id.inputSwipeDuration)
        inputDelay = findViewById(R.id.inputDelay)
        inputRepeat = findViewById(R.id.inputRepeat)
        checkInfinite = findViewById(R.id.checkInfinite)
        btnStartFloating = findViewById(R.id.btnStartFloating)
        btnStartOverlay = findViewById(R.id.btnStartOverlay)
        btnStopFloating = findViewById(R.id.btnStopFloating)
        btnRecordGesture = findViewById(R.id.btnRecordGesture)
        lblRecordedStatus = findViewById(R.id.lblRecordedStatus)

        findViewById<Button>(R.id.btnEnableOverlay).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "请开启悬浮窗权限", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    ))
                } else {
                    Toast.makeText(this, "悬浮窗权限已开启", Toast.LENGTH_SHORT).show()
                }
            }
        }

        radioGroupMode.setOnCheckedChangeListener { _, id ->
            swipeParams.visibility = if (id == R.id.radioSwipe) View.VISIBLE else View.GONE
            saveConfig()
            if (id == R.id.radioSwipe && !isSwipeConfigValid()) {
                val hint = if (radioSwipeManual.isChecked) "请填写手动参数" else "请先录制手势"
                Toast.makeText(this, hint, Toast.LENGTH_SHORT).show()
            }
            updateStatus()
        }

        radioGroupSwipeMethod.setOnCheckedChangeListener { _, id ->
            val isManual = id == R.id.radioSwipeManual
            manualSwipeParams.visibility = if (isManual) View.VISIBLE else View.GONE
            gestureSwipeSection.visibility = if (isManual) View.GONE else View.VISIBLE
            saveConfig()
            updateStatus()
        }

        checkInfinite.setOnCheckedChangeListener { _, checked ->
            inputRepeat.isEnabled = !checked
            saveConfig()
        }

        onTextChanged(inputSwipeX1) { saveConfig(); updateStatus() }
        onTextChanged(inputSwipeY1) { saveConfig(); updateStatus() }
        onTextChanged(inputSwipeX2) { saveConfig(); updateStatus() }
        onTextChanged(inputSwipeY2) { saveConfig(); updateStatus() }
        onTextChanged(inputSwipeDuration) { saveConfig(); updateStatus() }
        onTextChanged(inputDelay) { saveConfig(); updateStatus() }
        onTextChanged(inputRepeat) { saveConfig(); updateStatus() }

        btnRecordGesture.setOnClickListener {
            if (!hasOverlayPermission()) {
                Toast.makeText(this, "需要悬浮窗权限才能录制手势", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            saveConfig()
            AppConfig.recordRequested = true
            AppConfig.preventExecution = false
            if (!AppConfig.running) {
                AppConfig.running = true
                startFloatingService()
            }
            Toast.makeText(this, "请在屏幕上滑动手指录制手势", Toast.LENGTH_SHORT).show()
        }

        btnStartOverlay.setOnClickListener { handleStartButtonClick() }

        btnStartFloating.setOnClickListener {
            if (btnStartFloating.isEnabled) handleStartButtonClick()
        }

        btnStopFloating.setOnClickListener {
            AppConfig.running = false
            stopService(Intent(this, FloatingService::class.java))
            updateStatus()
        }
    }

    override fun onResume() {
        super.onResume()
        AppConfig.preventExecution = true
        if (AppConfig.running) {
            AppConfig.running = false
            stopService(Intent(this, FloatingService::class.java))
        }
        updateStatus()
    }

    override fun onPause() {
        super.onPause()
        AppConfig.preventExecution = false
    }

    private fun updateStatus() {
        val overlayGranted = hasOverlayPermission()

        statusText.text = buildString {
            append("悬浮窗权限：${if (overlayGranted) "✅ 已开启" else "❌ 未开启"}")
        }

        val ready = overlayGranted && isSwipeConfigValid()
        val canStart = ready && !AppConfig.running
        btnStartFloating.isEnabled = canStart
        btnStartOverlay.visibility = if (canStart) View.GONE else View.VISIBLE
        btnStopFloating.isEnabled = AppConfig.running
        btnRecordGesture.isEnabled = !AppConfig.running

        val gesture = AppConfig.recordedGesture
        if (gesture.points.size >= 2) {
            lblRecordedStatus.text = "已录制手势：${gesture.points.size}个点，${gesture.totalDuration}ms"
            lblRecordedStatus.visibility = View.VISIBLE
        } else if (radioSwipe.isChecked && radioSwipeGesture.isChecked) {
            lblRecordedStatus.text = "尚未录制手势"
            lblRecordedStatus.visibility = View.VISIBLE
        } else {
            lblRecordedStatus.visibility = View.GONE
        }
    }

    private fun isSwipeConfigValid(): Boolean {
        if (!radioSwipe.isChecked) return true
        return if (radioSwipeManual.isChecked) {
            inputSwipeX1.text.isNotEmpty() &&
            inputSwipeY1.text.isNotEmpty() &&
            inputSwipeX2.text.isNotEmpty() &&
            inputSwipeY2.text.isNotEmpty() &&
            inputSwipeDuration.text.isNotEmpty()
        } else {
            AppConfig.recordedGesture.points.size >= 2
        }
    }

    private fun saveConfig() {
        val mode = if (radioSwipe.isChecked) Mode.SWIPE else Mode.CLICK
        val swipeMethod = if (radioSwipeManual.isChecked) SwipeMethod.MANUAL else SwipeMethod.GESTURE
        AppConfig.current = AppConfig(
            mode = mode,
            swipeMethod = swipeMethod,
            swipeX1 = inputSwipeX1.text.toString().toFloatOrNull() ?: 0f,
            swipeY1 = inputSwipeY1.text.toString().toFloatOrNull() ?: 0f,
            swipeX2 = inputSwipeX2.text.toString().toFloatOrNull() ?: 0f,
            swipeY2 = inputSwipeY2.text.toString().toFloatOrNull() ?: 0f,
            swipeDuration = inputSwipeDuration.text.toString().toLongOrNull() ?: 0L,
            delayMs = inputDelay.text.toString().toLongOrNull() ?: 0L,
            repeatCount = if (checkInfinite.isChecked) -1 else (inputRepeat.text.toString().toIntOrNull() ?: 1)
        )
    }

    private fun handleStartButtonClick() {
        if (AppConfig.running) return

        if (!isSwipeConfigValid()) {
            val hint = if (radioSwipeManual.isChecked) "请填写手动参数" else "请先录制手势"
            Toast.makeText(this, hint, Toast.LENGTH_LONG).show()
            return
        }
        saveConfig()
        AppConfig.running = true
        AppConfig.preventExecution = false
        startFloatingService()
    }

    private fun onTextChanged(editText: EditText, action: () -> Unit) {
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { action() }
        })
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true
    }

    private fun startFloatingService() {
        val intent = Intent(this, FloatingService::class.java)
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, "悬浮球已启动", Toast.LENGTH_SHORT).show()
        updateStatus()
    }

}
