package com.yjc.click

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ClickableSpan
import android.view.View
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.materialswitch.MaterialSwitch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        var floatTutorialPending = false
    }

    enum class PermissionStep { NONE, ACCESSIBILITY, OVERLAY }
    private var pendingPermissionStep = PermissionStep.NONE

    private var isSwipeMode = false
    private var isGestureMode = false
    private var isWarningDialogShowing = false

    private lateinit var radioClick: Button
    private lateinit var radioSwipe: Button
    private lateinit var swipeParams: LinearLayout
    private lateinit var radioSwipeManual: Button
    private lateinit var radioSwipeGesture: Button
    private lateinit var manualSwipeParams: LinearLayout
    private lateinit var gestureSwipeSection: LinearLayout
    private lateinit var inputSwipeX1: EditText
    private lateinit var inputSwipeY1: EditText
    private lateinit var inputSwipeX2: EditText
    private lateinit var inputSwipeY2: EditText
    private lateinit var inputSwipeDuration: EditText
    private lateinit var inputDelay: EditText
    private lateinit var inputRepeat: EditText
    private lateinit var checkInfinite: MaterialSwitch
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
        radioClick = findViewById(R.id.radioClick)
        radioSwipe = findViewById(R.id.radioSwipe)
        swipeParams = findViewById(R.id.swipeParams)
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

        savedInstanceState?.let {
            pendingPermissionStep = try {
                PermissionStep.valueOf(it.getString("pending_step", "NONE")!!)
            } catch (e: Exception) {
                PermissionStep.NONE
            }
        }

        findViewById<Button>(R.id.btnEnableService).setOnClickListener {
            showWarningDialog {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        findViewById<Button>(R.id.btnEnableOverlay).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "请开启悬浮窗权限", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    ))
                } else {
                    Toast.makeText(this, "悬浮窗权限已开�?, Toast.LENGTH_SHORT).show()
                }
            }
        }

        // 初始状�?
        radioClick.isSelected = true
        radioSwipeManual.isSelected = true

        radioClick.setOnClickListener {
            isSwipeMode = false
            radioClick.isSelected = true
            radioSwipe.isSelected = false
            collapseView(swipeParams)
            saveConfig()
            updateStatus()
        }

        radioSwipe.setOnClickListener {
            isSwipeMode = true
            radioClick.isSelected = false
            radioSwipe.isSelected = true
            expandView(swipeParams)
            saveConfig()
            if (!isSwipeConfigValid()) {
                val hint = if (!isGestureMode) "请填写手动参�? else "请先录制手势"
                Toast.makeText(this, hint, Toast.LENGTH_SHORT).show()
            }
            updateStatus()
        }

        radioSwipeManual.setOnClickListener {
            if (!isGestureMode) return@setOnClickListener
            isGestureMode = false
            radioSwipeManual.isSelected = true
            radioSwipeGesture.isSelected = false
            expandView(manualSwipeParams)
            collapseView(gestureSwipeSection)
            saveConfig()
            updateStatus()
        }

        radioSwipeGesture.setOnClickListener {
            if (isGestureMode) return@setOnClickListener
            isGestureMode = true
            radioSwipeManual.isSelected = false
            radioSwipeGesture.isSelected = true
            collapseView(manualSwipeParams)
            expandView(gestureSwipeSection)
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
            if (!isServiceEnabled()) {
                Toast.makeText(this, "需要无障碍服务才能录制手势", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            saveConfig()
            val floatPrefs = getPreferences(Context.MODE_PRIVATE)
            if (floatPrefs.getBoolean("float_tutorial_done", false).not()) {
                floatPrefs.edit().putBoolean("float_tutorial_done", true).apply()
                floatTutorialPending = true
            }
            AppConfig.recordRequested = true
            AppConfig.preventExecution = false
            if (!AppConfig.running) {
                AppConfig.running = true
                startFloatingService()
            }
            Toast.makeText(this, "请在屏幕上滑动手指录制手�?, Toast.LENGTH_SHORT).show()
        }

        btnStartOverlay.setOnClickListener { handleStartButtonClick() }

        btnStartFloating.setOnClickListener { handleStartButtonClick() }

        btnStopFloating.setOnClickListener {
            AppConfig.running = false
            stopService(Intent(this, FloatingService::class.java))
            updateStatus()
        }

        val prefs = getPreferences(Context.MODE_PRIVATE)
        if (prefs.getBoolean("first_launch_done", false).not()) {
            prefs.edit().putBoolean("first_launch_done", true).apply()
            // 等待布局完全稳定后再显示弹窗
            val decorView = window.decorView
            decorView.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    decorView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    decorView.postDelayed({
                        showFirstLaunchDialog()
                    }, 100)
                }
            })
        }
    }

    override fun onResume() {
        super.onResume()
        AppConfig.preventExecution = true
        AppConfig.running = false
        stopService(Intent(this, FloatingService::class.java))

        if (intent.getBooleanExtra("show_float_tutorial", false)) {
            intent.removeExtra("show_float_tutorial")
            showFloatTutorialDialog {
                AppConfig.running = true
                AppConfig.preventExecution = false
                startFloatingService()
            }
            updateStatus()
            return
        }

        if (pendingPermissionStep == PermissionStep.ACCESSIBILITY) {
            if (isServiceEnabled()) {
                pendingPermissionStep = PermissionStep.NONE
                updateStatus()
                if (!hasOverlayPermission()) {
                    Toast.makeText(applicationContext, "请开启悬浮窗权限", Toast.LENGTH_SHORT).show()
                    openOverlaySettings()
                    return
                }
            }
        } else if (pendingPermissionStep == PermissionStep.OVERLAY) {
            if (hasOverlayPermission()) {
                pendingPermissionStep = PermissionStep.NONE
            }
        }
        updateStatus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("pending_step", pendingPermissionStep.name)
    }

    override fun onPause() {
        super.onPause()
        AppConfig.preventExecution = false
    }

    private fun updateStatus() {
        val serviceEnabled = isServiceEnabled()
        val overlayGranted = hasOverlayPermission()

        statusText.text = buildString {
            append("无障碍服务：${if (serviceEnabled) "�?已开�? else "�?未开�?}\n")
            append("悬浮窗权限：${if (overlayGranted) "�?已开�? else "�?未开�?}")
        }

        val ready = serviceEnabled && overlayGranted && isSwipeConfigValid()
        val canStart = ready && !AppConfig.running
        btnStartFloating.isEnabled = canStart
        btnStartOverlay.visibility = if (canStart) View.GONE else View.VISIBLE
        btnStopFloating.isEnabled = AppConfig.running
        btnRecordGesture.isEnabled = !AppConfig.running

        val gesture = AppConfig.recordedGesture
        if (gesture.points.size >= 2) {
            lblRecordedStatus.text = "已录制手势：${gesture.points.size}个点�?{gesture.totalDuration}ms"
            lblRecordedStatus.visibility = View.VISIBLE
        } else if (isSwipeMode && isGestureMode) {
            lblRecordedStatus.text = "尚未录制手势"
            lblRecordedStatus.visibility = View.VISIBLE
        } else {
            lblRecordedStatus.visibility = View.GONE
        }
    }

    private fun isSwipeConfigValid(): Boolean {
        if (!isSwipeMode) return true
        return if (!isGestureMode) {
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
        val mode = if (isSwipeMode) Mode.SWIPE else Mode.CLICK
        val swipeMethod = if (!isGestureMode) SwipeMethod.MANUAL else SwipeMethod.GESTURE
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

        val serviceEnabled = isServiceEnabled()
        val overlayGranted = hasOverlayPermission()

        if (serviceEnabled && overlayGranted) {
            if (!isSwipeConfigValid()) {
                val hint = if (!isGestureMode) "请填写手动参�? else "请先录制手势"
                Toast.makeText(this, hint, Toast.LENGTH_LONG).show()
                return
            }
            val floatPrefs = getPreferences(Context.MODE_PRIVATE)
            if (floatPrefs.getBoolean("float_tutorial_done", false).not()) {
                floatPrefs.edit().putBoolean("float_tutorial_done", true).apply()
                saveConfig()
                showFloatTutorialDialog {
                    AppConfig.running = true
                    AppConfig.preventExecution = false
                    startFloatingService()
                }
                return
            }
            saveConfig()
            AppConfig.running = true
            AppConfig.preventExecution = false
            startFloatingService()
            return
        }

        when (pendingPermissionStep) {
            PermissionStep.ACCESSIBILITY -> {
                if (!serviceEnabled) {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    return
                }
                pendingPermissionStep = PermissionStep.NONE
            }
            PermissionStep.OVERLAY -> {
                if (!overlayGranted) {
                    openOverlaySettings()
                    return
                }
                pendingPermissionStep = PermissionStep.NONE
            }
            PermissionStep.NONE -> {}
        }

        if (!serviceEnabled) {
            showWarningDialog {
                pendingPermissionStep = PermissionStep.ACCESSIBILITY
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            return
        }

        if (!overlayGranted) {
            Toast.makeText(this, "请开启悬浮窗权限", Toast.LENGTH_SHORT).show()
            openOverlaySettings()
        }
    }

    private fun decodeWarning(): String {
        val key = "YJCyjc303030."
        val encoded = byteArrayOf(
            177.toByte(), 229.toByte(), 244.toByte(), 158.toByte(), 203.toByte(), 205.toByte(), 219.toByte(), 158.toByte(), 151.toByte(), 214.toByte(), 158.toByte(), 148.toByte(),
            198.toByte(), 228.toByte(), 229.toByte(), 167.toByte(), 194.toByte(), 220.toByte(), 133.toByte(), 171.toByte(), 159.toByte(), 215.toByte(), 139.toByte(), 189.toByte(),
            87.toByte(), 71.toByte(), 45.toByte(), 34.toByte(), 54.toByte(), 27.toByte(), 142.toByte(), 219.toByte(), 184.toByte(), 216.toByte(), 142.toByte(), 141.toByte(),
            219.toByte(), 190.toByte(), 153.toByte(), 188.toByte(), 244.toByte(), 212.toByte(), 150.toByte(), 214.toByte(), 239.toByte(), 215.toByte(), 139.toByte(), 189.toByte(),
            213.toByte(), 182.toByte(), 134.toByte(), 202.toByte(), 226.toByte(), 220.toByte(), 166.toByte(), 229.toByte(), 218.toByte(), 133.toByte(), 165.toByte(), 137.toByte(),
            215.toByte(), 136.toByte(), 184.toByte(), 216.toByte(), 147.toByte(), 228.toByte(), 172.toByte(), 238.toByte(), 221.toByte(), 130.toByte(), 222.toByte(), 156.toByte(),
            212.toByte(), 136.toByte(), 134.toByte(), 214.toByte(), 191.toByte(), 129.toByte(), 177.toByte(), 201.toByte(), 254.toByte(), 157.toByte(), 214.toByte(), 249.toByte(),
            214.toByte(), 160.toByte(), 152.toByte(), 214.toByte(), 175.toByte(), 185.toByte(), 200.toByte(), 216.toByte(), 252.toByte(), 165.toByte(), 253.toByte(), 229.toByte(),
            135.toByte(), 136.toByte(), 147.toByte(), 212.toByte(), 144.toByte(), 178.toByte(), 223.toByte(), 146.toByte(), 213.toByte(), 162.toByte(), 236.toByte(), 206.toByte(),
            130.toByte(), 211.toByte(), 155.toByte(), 214.toByte(), 182.toByte(), 190.toByte(), 213.toByte(), 190.toByte(), 166.toByte(), 189.toByte(), 240.toByte(), 203.toByte(),
            159.toByte(), 253.toByte(), 195.toByte(), 218.toByte(), 170.toByte(), 175.toByte(), 215.toByte(), 145.toByte(), 189.toByte(), 200.toByte(), 196.toByte(), 201.toByte(),
            170.toByte(), 224.toByte(), 250.toByte(),
        )
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        val decoded = ByteArray(encoded.size) { i ->
            (encoded[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
        }
        return String(decoded, Charsets.UTF_8)
    }

    private fun showWarningDialog(onConfirmed: () -> Unit) {
        if (isWarningDialogShowing) return
        
        val message = decodeWarning()
        if (!message.contains("github")) {
            throw RuntimeException("App integrity verification failed")
        }
        
        isWarningDialogShowing = true
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("安全警告")
            .setMessage(message)
            .setPositiveButton("确认") { _, _ -> onConfirmed() }
            .setCancelable(false)
            .create()
        dialog.window?.setWindowAnimations(android.R.style.Animation_Dialog)
        dialog.setOnDismissListener { isWarningDialogShowing = false }
        dialog.show()
    }

    private fun openOverlaySettings() {
        pendingPermissionStep = PermissionStep.OVERLAY
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ))
        }
    }

    private fun expandView(view: View) {
        if (view.visibility == View.VISIBLE && view.height > 0) return
        
        view.animate().cancel()
        
        // 测量目标高度
        view.measure(
            View.MeasureSpec.makeMeasureSpec((view.parent as View).width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val targetHeight = view.measuredHeight
        
        view.visibility = View.VISIBLE
        view.alpha = 0f
        
        val params = view.layoutParams
        params.height = 1
        view.layoutParams = params
        
        // 使用 Choreographer 确保帧同�?
        view.animate()
            .alpha(1f)
            .setDuration(250)
            .setInterpolator(android.view.animation.DecelerateInterpolator(2f))
            .start()
        
        // 使用 ValueAnimator 平滑改变高度
        val animator = android.animation.ValueAnimator.ofInt(1, targetHeight)
        animator.duration = 250
        animator.interpolator = android.view.animation.DecelerateInterpolator(2f)
        animator.addUpdateListener { anim ->
            params.height = anim.animatedValue as Int
            view.layoutParams = params
        }
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                params.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                view.layoutParams = params
                view.alpha = 1f
            }
        })
        animator.start()
    }

    private fun collapseView(view: View) {
        if (view.visibility == View.GONE) return
        
        view.animate().cancel()
        
        val currentHeight = view.height
        if (currentHeight <= 0) {
            view.visibility = View.GONE
            return
        }
        
        view.alpha = 1f
        
        val params = view.layoutParams
        
        // 使用 ValueAnimator 平滑改变高度
        val animator = android.animation.ValueAnimator.ofInt(currentHeight, 0)
        animator.duration = 200
        animator.interpolator = android.view.animation.AccelerateInterpolator(2f)
        animator.addUpdateListener { anim ->
            params.height = anim.animatedValue as Int
            view.layoutParams = params
            view.alpha = (anim.animatedValue as Int).toFloat() / currentHeight
        }
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                view.visibility = View.GONE
                view.alpha = 1f
                params.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                view.layoutParams = params
            }
        })
        animator.start()
    }

    private fun onTextChanged(editText: EditText, action: () -> Unit) {
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { action() }
        })
    }

    private fun isServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return enabledServices?.contains("$packageName/$packageName.ClickAccessibilityService") == true
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



    private fun showFirstLaunchDialog() {
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("使用说明")
            .setMessage("\n" + decodeTutorialText())
            .setPositiveButton("知道�?) { d, _ -> d.dismiss() }
            .setCancelable(false)
            .create()
        // 平滑显示，避免闪�?
        dialog.window?.setWindowAnimations(android.R.style.Animation_Dialog)
        dialog.show()
    }

    private fun showFloatTutorialDialog(onStart: () -> Unit) {
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("悬浮球使用说�?)
            .setMessage("\n" + decodeFloatTutorialText())
            .setPositiveButton("知道�?) { _, _ -> onStart() }
            .setCancelable(false)
            .create()
        dialog.window?.setWindowAnimations(android.R.style.Animation_Dialog)
        dialog.show()
    }

    private fun decodeFloatTutorialText(): String {
        val key = "YJCyjc303030."
        val encoded = byteArrayOf(
            104.toByte(), 100.toByte(), 166.toByte(), 241.toByte(), 237.toByte(), 134.toByte(), 187.toByte(), 128.toByte(), 213.toByte(), 179.toByte(), 128.toByte(), 214.toByte(), 167.toByte(), 254.toByte(), 162.toByte(), 226.toByte(), 245.toByte(), 140.toByte(), 240.toByte(), 190.toByte(), 212.toByte(), 142.toByte(), 172.toByte(), 212.toByte(), 170.toByte(), 170.toByte(), 188.toByte(), 240.toByte(), 215.toByte(), 158.toByte(), 254.toByte(), 203.toByte(), 57.toByte(), 58.toByte(), 1.toByte(), 30.toByte(), 213.toByte(), 188.toByte(), 167.toByte(), 189.toByte(), 247.toByte(), 204.toByte(), 159.toByte(), 232.toByte(), 207.toByte(), 213.toByte(), 133.toByte(), 157.toByte(), 215.toByte(), 163.toByte(), 179.toByte(), 200.toByte(), 210.toByte(), 220.toByte(), 166.toByte(), 243.toByte(), 194.toByte(), 134.toByte(), 190.toByte(), 131.toByte(), 214.toByte(), 191.toByte(), 156.toByte(), 214.toByte(), 165.toByte(), 207.toByte(), 175.toByte(), 201.toByte(), 209.toByte(), 140.toByte(), 225.toByte(), 159.toByte(), 214.toByte(), 134.toByte(), 158.toByte(), 212.toByte(), 160.toByte(), 173.toByte(), 182.toByte(), 246.toByte(), 207.toByte(), 156.toByte(), 229.toByte(), 204.toByte(), 213.toByte(), 187.toByte(), 165.toByte(), 213.toByte(), 185.toByte(), 152.toByte(), 203.toByte(), 209.toByte(), 250.toByte(), 165.toByte(), 250.toByte(), 217.toByte(), 133.toByte(), 186.toByte(), 151.toByte(), 219.toByte(), 145.toByte(), 191.toByte(), 214.toByte(), 189.toByte(), 212.toByte(), 174.toByte(), 254.toByte(), 229.toByte(), 141.toByte(), 249.toByte(), 183.toByte(), 212.toByte(), 142.toByte(), 189.toByte(), 212.toByte(), 141.toByte(), 128.toByte(), 182.toByte(), 246.toByte(), 207.toByte(), 159.toByte(), 225.toByte(), 245.toByte(), 214.toByte(), 186.toByte(), 155.toByte(), 214.toByte(), 164.toByte(), 134.toByte(), 200.toByte(), 219.toByte(), 230.toByte(), 165.toByte(), 204.toByte(), 196.toByte(), 132.toByte(), 163.toByte(), 179.toByte(), 215.toByte(), 140.toByte(), 169.toByte(), 213.toByte(), 163.toByte(), 211.toByte(), 163.toByte(), 195.toByte(), 246.toByte(), 140.toByte(), 251.toByte(), 189.toByte(), 223.toByte(), 143.toByte(), 188.toByte(), 215.toByte(), 139.toByte(), 139.toByte(), 189.toByte(), 244.toByte(), 252.toByte(), 156.toByte(), 196.toByte(), 249.toByte(), 215.toByte(), 141.toByte(), 190.toByte(), 58.toByte(), 57.toByte(), 3.toByte(), 0.toByte(), 191.toByte(), 193.toByte(), 213.toByte(), 156.toByte(), 224.toByte(), 203.toByte(), 214.toByte(), 158.toByte(), 191.toByte(), 214.toByte(), 156.toByte(), 165.toByte(), 203.toByte(), 201.toByte(), 196.toByte(), 164.toByte(), 251.toByte(), 211.toByte(), 134.toByte(), 180.toByte(), 139.toByte(), 215.toByte(), 136.toByte(), 179.toByte(), 212.toByte(), 150.toByte(), 210.toByte(), 172.toByte(), 193.toByte(), 213.toByte(), 140.toByte(), 214.toByte(), 157.toByte(), 215.toByte(), 163.toByte(), 179.toByte(), 214.toByte(), 189.toByte(), 157.toByte(), 188.toByte(), 197.toByte(), 236.toByte(), 156.toByte(), 214.toByte(), 227.toByte(), 214.toByte(), 151.toByte(), 184.toByte(), 214.toByte(), 160.toByte(), 189.toByte(), 202.toByte(), 228.toByte(), 214.toByte(), 73.toByte(), 115.toByte(), 94.toByte(), 77.toByte(), 214.toByte(), 191.toByte(), 191.toByte(), 213.toByte(), 180.toByte(), 139.toByte(), 200.toByte(), 219.toByte(), 230.toByte(), 165.toByte(), 204.toByte(), 196.toByte(), 132.toByte(), 163.toByte(), 179.toByte(), 214.toByte(), 191.toByte(), 156.toByte(), 214.toByte(), 180.toByte(), 219.toByte(), 175.toByte(), 194.toByte(), 229.toByte(), 140.toByte(), 240.toByte(), 190.toByte(), 212.toByte(), 142.toByte(), 172.toByte(), 57.toByte(), 58.toByte(), 200.toByte(), 234.toByte(), 226.toByte(), 165.toByte(), 253.toByte(), 229.toByte(), 140.toByte(), 143.toByte(), 170.toByte(), 215.toByte(), 139.toByte(), 189.toByte(), 212.toByte(), 150.toByte(), 217.toByte(), 174.toByte(), 251.toByte(), 211.toByte(), 143.toByte(), 217.toByte(), 167.toByte(), 215.toByte(), 167.toByte(), 152.toByte(), 214.toByte(), 184.toByte(), 169.toByte(), 188.toByte(), 209.toByte(), 221.toByte(), 159.toByte(), 246.toByte(), 207.toByte(), 219.toByte(), 141.toByte(), 156.toByte(), 212.toByte(), 136.toByte(), 134.toByte(), 200.toByte(), 206.toByte(), 252.toByte(), 165.toByte(), 251.toByte(), 198.toByte(), 133.toByte(), 134.toByte(), 158.toByte(), 212.toByte(), 160.toByte(), 176.toByte(), 212.toByte(), 146.toByte(), 195.toByte(), 162.toByte(), 196.toByte(), 211.toByte(), 143.toByte(), 233.toByte(), 155.toByte(), 213.toByte(), 178.toByte(), 172.toByte(), 213.toByte(), 157.toByte(), 140.toByte(), 182.toByte(), 246.toByte(), 207.toByte(), 159.toByte(), 235.toByte(), 193.toByte(), 214.toByte(), 148.toByte(), 190.toByte(), 213.toByte(), 188.toByte(), 159.toByte(), 202.toByte(), 226.toByte(), 239.toByte(), 166.toByte(), 255.toByte(), 231.toByte(), 132.toByte(), 177.toByte(), 137.toByte(), 214.toByte(), 183.toByte(), 136.toByte(), 213.toByte(), 190.toByte(), 246.toByte(), 175.toByte(), 201.toByte(), 209.toByte(), 140.toByte(), 225.toByte(), 159.toByte(), 214.toByte(), 134.toByte(), 158.toByte(), 212.toByte(), 160.toByte(), 173.toByte(), 191.toByte(), 198.toByte(), 202.toByte(), 144.toByte(), 248.toByte(), 205.toByte()
        )
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        val decoded = ByteArray(encoded.size) { i ->
            (encoded[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
        }
        return String(decoded, Charsets.UTF_8)
    }

    private fun decodeTutorialText(): String {
        val key = "YJCyjc303030."
        val encoded = byteArrayOf(
            176.toByte(), 236.toByte(), 213.toByte(), 159.toByte(), 198.toByte(), 194.toByte(), 215.toByte(), 141.toByte(), 140.toByte(), 215.toByte(), 167.toByte(), 152.toByte(), 193.toByte(), 229.toByte(), 208.toByte(), 73.toByte(), 115.toByte(), 91.toByte(), 77.toByte(), 19.toByte(), 213.toByte(), 143.toByte(), 176.toByte(), 214.toByte(), 160.toByte(), 129.toByte(), 191.toByte(), 221.toByte(), 227.toByte(), 144.toByte(), 240.toByte(), 255.toByte(), 212.toByte(), 146.toByte(), 190.toByte(), 214.toByte(), 175.toByte(), 189.toByte(), 203.toByte(), 211.toByte(), 235.toByte(), 166.toByte(), 235.toByte(), 230.toByte(), 133.toByte(), 177.toByte(), 156.toByte(), 213.toByte(), 133.toByte(), 157.toByte(), 215.toByte(), 132.toByte(), 206.toByte(), 172.toByte(), 222.toByte(), 250.toByte(), 131.toByte(), 250.toByte(), 163.toByte(), 223.toByte(), 143.toByte(), 188.toByte(), 212.toByte(), 178.toByte(), 151.toByte(), 188.toByte(), 205.toByte(), 248.toByte(), 145.toByte(), 215.toByte(), 204.toByte(), 215.toByte(), 139.toByte(), 133.toByte(), 212.toByte(), 139.toByte(), 186.toByte(), 200.toByte(), 207.toByte(), 243.toByte(), 167.toByte(), 193.toByte(), 206.toByte(), 135.toByte(), 139.toByte(), 154.toByte(), 213.toByte(), 188.toByte(), 186.toByte(), 217.toByte(), 188.toByte(), 247.toByte(), 175.toByte(), 206.toByte(), 202.toByte(), 143.toByte(), 236.toByte(), 156.toByte(), 216.toByte(), 132.toByte(), 131.toByte(), 219.toByte(), 141.toByte(), 130.toByte(), 177.toByte(), 228.toByte(), 253.toByte(), 158.toByte(), 215.toByte(), 205.toByte(), 218.toByte(), 145.toByte(), 134.toByte(), 223.toByte(), 143.toByte(), 184.toByte(), 200.toByte(), 206.toByte(), 234.toByte(), 170.toByte(), 227.toByte(), 246.toByte(), 132.toByte(), 145.toByte(), 189.toByte(), 213.toByte(), 173.toByte(), 176.toByte(), 217.toByte(), 183.toByte(), 201.toByte(), 173.toByte(), 215.toByte(), 200.toByte(), 142.toByte(), 217.toByte(), 189.toByte(), 213.toByte(), 157.toByte(), 185.toByte(), 214.toByte(), 189.toByte(), 189.toByte(), 188.toByte(), 228.toByte(), 202.toByte(), 156.toByte(), 239.toByte(), 203.toByte(), 213.toByte(), 172.toByte(), 137.toByte(), 213.toByte(), 187.toByte(), 134.toByte(), 200.toByte(), 246.toByte(), 197.toByte(), 165.toByte(), 213.toByte(), 203.toByte(), 138.toByte(), 180.toByte(), 189.toByte(), 214.toByte(), 160.toByte(), 156.toByte(), 216.toByte(), 147.toByte(), 246.toByte(), 174.toByte(), 248.toByte(), 207.toByte(), 131.toByte(), 255.toByte(), 179.toByte(), 217.toByte(), 180.toByte(), 189.toByte(), 213.toByte(), 166.toByte(), 158.toByte(), 191.toByte(), 196.toByte(), 203.toByte(), 159.toByte(), 247.toByte(), 224.toByte(), 220.toByte(), 140.toByte(), 186.toByte(), 58.toByte(), 57.toByte(), 2.toByte(), 0.toByte(), 121.toByte(), 163.toByte(), 195.toByte(), 240.toByte(), 140.toByte(), 232.toByte(), 154.toByte(), 214.toByte(), 136.toByte(), 161.toByte(), 214.toByte(), 186.toByte(), 134.toByte(), 191.toByte(), 226.toByte(), 226.toByte(), 156.toByte(), 214.toByte(), 236.toByte(), 220.toByte(), 140.toByte(), 169.toByte(), 215.toByte(), 177.toByte(), 137.toByte(), 203.toByte(), 222.toByte(), 241.toByte(), 165.toByte(), 241.toByte(), 252.toByte(), 133.toByte(), 136.toByte(), 161.toByte(), 214.toByte(), 186.toByte(), 155.toByte(), 58.toByte(), 36.toByte(), 106.toByte(), 100.toByte(), 99.toByte(), 158.toByte(), 232.toByte(), 218.toByte(), 214.toByte(), 183.toByte(), 136.toByte(), 214.toByte(), 155.toByte(), 145.toByte(), 203.toByte(), 229.toByte(), 197.toByte(), 172.toByte(), 197.toByte(), 240.toByte(), 139.toByte(), 157.toByte(), 142.toByte(), 212.toByte(), 141.toByte(), 157.toByte(), 215.toByte(), 172.toByte(), 224.toByte(), 175.toByte(), 196.toByte(), 194.toByte(), 143.toByte(), 216.toByte(), 133.toByte(), 214.toByte(), 164.toByte(), 134.toByte(), 209.toByte(), 182.toByte(), 188.toByte(), 177.toByte(), 228.toByte(), 253.toByte(), 158.toByte(), 215.toByte(), 205.toByte(), 218.toByte(), 183.toByte(), 190.toByte(), 213.toByte(), 151.toByte(), 189.toByte(), 200.toByte(), 245.toByte(), 235.toByte(), 165.toByte(), 236.toByte(), 218.toByte(), 129.toByte(), 181.toByte(), 162.toByte(), 214.toByte(), 160.toByte(), 156.toByte(), 213.toByte(), 164.toByte(), 241.toByte(), 172.toByte(), 193.toByte(), 213.toByte(), 140.toByte(), 214.toByte(), 157.toByte(), 215.toByte(), 163.toByte(), 179.toByte(), 220.toByte(), 140.toByte(), 166.toByte(), 190.toByte(), 246.toByte(), 249.toByte(), 159.toByte(), 247.toByte(), 224.toByte(), 218.toByte(), 169.toByte(), 163.toByte(), 212.toByte(), 143.toByte(), 170.toByte(), 203.toByte(), 229.toByte(), 243.toByte(), 166.toByte(), 254.toByte(), 208.toByte(), 133.toByte(), 189.toByte(), 184.toByte(), 213.toByte(), 173.toByte(), 176.toByte(), 216.toByte(), 128.toByte(), 231.toByte(), 173.toByte(), 254.toByte(), 215.toByte(), 131.toByte(), 194.toByte(), 134.toByte(), 223.toByte(), 143.toByte(), 185.toByte(), 57.toByte(), 58.toByte(), 26.toByte(), 119.toByte(), 106.toByte(), 165.toByte(), 194.toByte(), 251.toByte(), 134.toByte(), 185.toByte(), 152.toByte(), 213.toByte(), 152.toByte(), 146.toByte(), 213.toByte(), 146.toByte(), 214.toByte(), 165.toByte(), 255.toByte(), 227.toByte(), 140.toByte(), 234.toByte(), 184.toByte(), 213.toByte(), 185.toByte(), 143.toByte(), 214.toByte(), 141.toByte(), 187.toByte(), 188.toByte(), 194.toByte(), 245.toByte(), 150.toByte(), 214.toByte(), 235.toByte(), 214.toByte(), 172.toByte(), 155.toByte(), 213.toByte(), 130.toByte(), 191.toByte(), 203.toByte(), 224.toByte(), 223.toByte(), 165.toByte(), 194.toByte(), 251.toByte(), 134.toByte(), 185.toByte(), 152.toByte(), 220.toByte(), 140.toByte(), 186.toByte(), 214.toByte(), 166.toByte(), 207.toByte(), 172.toByte(), 202.toByte(), 242.toByte(), 143.toByte(), 233.toByte(), 155.toByte(), 213.toByte(), 188.toByte(), 178.toByte(), 213.toByte(), 165.toByte(), 158.toByte(), 182.toByte(), 246.toByte(), 203.toByte(), 156.toByte(), 203.toByte(), 200.toByte(), 214.toByte(), 182.toByte(), 170.toByte(), 216.toByte(), 134.toByte(), 135.toByte(), 201.toByte(), 219.toByte(), 243.toByte(), 164.toByte(), 194.toByte(), 226.toByte(), 132.toByte(), 177.toByte(), 137.toByte(), 214.toByte(), 173.toByte(), 163.toByte(), 214.toByte(), 142.toByte(), 222.toByte(), 165.toByte(), 255.toByte(), 240.toByte(), 136.toByte(), 229.toByte(), 161.toByte(), 216.toByte(), 157.toByte(), 142.toByte(), 212.toByte(), 141.toByte(), 128.toByte(), 188.toByte(), 241.toByte(), 245.toByte(), 159.toByte(), 253.toByte(), 213.toByte(), 209.toByte(), 182.toByte(), 161.toByte(), 216.toByte(), 157.toByte(), 142.toByte(), 201.toByte(), 228.toByte(), 228.toByte(), 170.toByte(), 254.toByte(), 231.toByte(), 134.toByte(), 151.toByte(), 189.toByte(), 213.toByte(), 156.toByte(), 146.toByte(), 214.toByte(), 187.toByte(), 233.toByte(), 168.toByte(), 197.toByte(), 235.toByte(), 143.toByte(), 243.toByte(), 156.toByte(), 213.toByte(), 185.toByte(), 152.toByte(), 213.toByte(), 178.toByte(), 130.toByte(), 191.toByte(), 255.toByte(), 237.toByte(), 158.toByte(), 250.toByte(), 224.toByte(), 220.toByte(), 140.toByte(), 187.toByte(), 215.toByte(), 143.toByte(), 138.toByte(), 200.toByte(), 196.toByte(), 201.toByte(), 170.toByte(), 224.toByte(), 250.toByte(), 135.toByte(), 143.toByte(), 170.toByte(), 214.toByte(), 140.toByte(), 138.toByte(), 213.toByte(), 169.toByte(), 227.toByte(), 172.toByte(), 205.toByte(), 241.toByte(), 140.toByte(), 254.toByte(), 176.toByte(), 216.toByte(), 157.toByte(), 142.toByte(), 212.toByte(), 141.toByte(), 128.toByte(), 176.toByte(), 235.toByte(), 246.toByte(), 150.toByte(), 214.toByte(), 234.toByte()
        )
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        val decoded = ByteArray(encoded.size) { i ->
            (encoded[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
        }
        return String(decoded, Charsets.UTF_8)
    }
}
