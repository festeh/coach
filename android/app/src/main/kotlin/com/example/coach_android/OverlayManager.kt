package com.example.coach_android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.CountDownTimer
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

class OverlayManager(
    private val context: Context,
) {
    companion object {
        private const val TAG = "OverlayManager"
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = context.resources.displayMetrics.density

    private var overlayView: View? = null
    private var currentRuleId: String? = null
    private var currentTargetApp: String? = null
    private var longPressTimer: CountDownTimer? = null

    var onChallengeCompleted: ((ruleId: String) -> Unit)? = null

    // The bare minimum of a visible window. Android 15 only lets a background
    // service start an activity while the app is showing one, so the chat wall
    // flashes this cover for the milliseconds between show and launch; nobody
    // is meant to read it.
    fun showLaunchCover() {
        if (overlayView != null) hide()
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Cannot show launch cover: Permission not granted.")
            return
        }
        val cover = View(context).apply { setBackgroundColor(0xCC000000.toInt()) }
        val params =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT,
            )
        try {
            windowManager.addView(cover, params)
            overlayView = cover
        } catch (e: Exception) {
            Log.e(TAG, "Error adding launch cover", e)
            overlayView = null
        }
    }

    // Rule walls only: every other block goes to the chat.
    fun show(
        packageName: String,
        challengeType: String?,
        ruleId: String?,
    ) {
        if (overlayView != null) {
            Log.d(TAG, "Replacing existing overlay for $packageName")
            hide()
        }
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Cannot show overlay: Permission not granted.")
            return
        }

        currentRuleId = ruleId
        val effectiveChallengeType = challengeType ?: "none"

        Log.d(TAG, "Showing rule overlay for $packageName (challenge: $effectiveChallengeType)")

        val settings =
            com.example.coach_android.data.preferences
                .PreferencesManager(context)
                .loadSettings()
        currentTargetApp = settings.rulesOverlayTargetApp
        val customMessage = settings.rulesOverlayMessage
        val overlayColorHex = settings.rulesOverlayColor
        val customButtonText = settings.rulesOverlayButtonText
        val buttonColorHex = settings.rulesOverlayButtonColor

        val appName =
            if (packageName.isNotEmpty()) {
                try {
                    val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
                    appInfo.loadLabel(context.packageManager).toString()
                } catch (_: PackageManager.NameNotFoundException) {
                    packageName
                }
            } else {
                null
            }

        val displayText =
            if (customMessage.isNotEmpty()) {
                customMessage.replace("{app}", appName ?: "")
            } else if (appName != null) {
                "I detected $appName.\nIt's time to focus!"
            } else {
                "Focus Time!"
            }

        val bgColor = parseColor(overlayColorHex, 0xFF000000.toInt())
        val bgColorWithAlpha = (0xCC shl 24) or (bgColor and 0x00FFFFFF)
        val buttonColor = parseColor(buttonColorHex, 0xFFFF5252.toInt())

        overlayView =
            when (effectiveChallengeType) {
                "longPress" -> buildLongPressChallengeView(displayText, bgColorWithAlpha, buttonColor, settings.longPressDurationSeconds)
                "typing" -> buildTypingChallengeView(displayText, bgColorWithAlpha, buttonColor, settings.typingPhrase)
                else -> buildStandardOverlayView(displayText, bgColorWithAlpha, buttonColor, customButtonText)
            }

        val params =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT,
            )

        try {
            windowManager.addView(overlayView, params)
            Log.d(TAG, "Overlay added to window manager.")
        } catch (e: Exception) {
            Log.e(TAG, "Error adding overlay view", e)
            overlayView = null
        }
    }

    fun isShowing(): Boolean {
        val view = overlayView ?: return false
        return view.isAttachedToWindow
    }

    fun hide() {
        val view = overlayView ?: return
        Log.d(TAG, "Hiding overlay")
        longPressTimer?.cancel()
        longPressTimer = null
        currentTargetApp = null
        try {
            windowManager.removeView(view)
            overlayView = null
        } catch (e: Exception) {
            Log.e(TAG, "Error removing overlay view", e)
        }
    }

    // --- Helpers ---

    private fun parseColor(
        hex: String,
        default: Int,
    ): Int =
        try {
            java.lang.Long
                .parseLong(hex, 16)
                .toInt()
        } catch (_: NumberFormatException) {
            default
        }

    private fun dp(value: Int): Int = (value * density).toInt()

    private fun buildChallengeRoot(bgColor: Int): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            background =
                GradientDrawable().apply {
                    setColor(bgColor)
                    cornerRadius = 16f * density
                    setStroke(dp(1), 0xFFFFFFFF.toInt())
                }
        }

    private fun addCloseButton(root: LinearLayout) {
        val closeButton =
            TextView(context).apply {
                text = "\u2715"
                setTextColor(0xAAFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                gravity = Gravity.END
                setPadding(0, 0, 0, dp(8))
                setOnClickListener { dismissAndNavigate() }
            }
        root.addView(
            closeButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    private fun addDisplayText(
        root: LinearLayout,
        text: String,
    ) {
        val textView =
            TextView(context).apply {
                this.text = text
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
        root.addView(textView)
    }

    private fun dismissAndNavigate() {
        val targetApp = currentTargetApp
        if (!targetApp.isNullOrEmpty()) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(targetApp)
            if (launchIntent != null) {
                launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                try {
                    context.startActivity(launchIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Error launching target app $targetApp, falling back to home", e)
                    launchHome()
                }
            } else {
                launchHome()
            }
        } else {
            launchHome()
        }
        hide()
    }

    private fun launchHome() {
        val homeIntent =
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        try {
            context.startActivity(homeIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending Home intent", e)
        }
    }

    private fun notifyChallengeCompleted() {
        val ruleId = currentRuleId
        dismissAndNavigate()
        if (ruleId != null) {
            Log.d(TAG, "Challenge completed for rule: $ruleId")
            onChallengeCompleted?.invoke(ruleId)
        }
    }

    // --- Overlay builders ---

    private fun buildStandardOverlayView(
        displayText: String,
        bgColor: Int,
        buttonColor: Int,
        customButtonText: String,
    ): View {
        val root = buildChallengeRoot(bgColor)
        addCloseButton(root)
        addDisplayText(root, displayText)

        val button =
            Button(context).apply {
                text = if (customButtonText.isNotEmpty()) customButtonText else "Got it!"
                setTextColor(0xFFFFFFFF.toInt())
                backgroundTintList = ColorStateList.valueOf(buttonColor)
                isAllCaps = false
                setTypeface(typeface, Typeface.BOLD)
                layoutParams =
                    LinearLayout
                        .LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = dp(16) }
                setOnClickListener { dismissAndNavigate() }
            }
        root.addView(button)

        return root
    }

    private fun buildLongPressChallengeView(
        displayText: String,
        bgColor: Int,
        buttonColor: Int,
        durationSeconds: Int,
    ): View {
        val root = buildChallengeRoot(bgColor)
        addCloseButton(root)
        addDisplayText(root, displayText)

        val progressBar =
            ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = 0
                progressTintList = ColorStateList.valueOf(buttonColor)
                layoutParams =
                    LinearLayout
                        .LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(8),
                        ).apply { topMargin = dp(16) }
            }
        root.addView(progressBar)

        val holdButton =
            Button(context).apply {
                text = "Hold to dismiss"
                setTextColor(0xFFFFFFFF.toInt())
                backgroundTintList = ColorStateList.valueOf(buttonColor)
                isAllCaps = false
                setTypeface(typeface, Typeface.BOLD)
                layoutParams =
                    LinearLayout
                        .LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = dp(8) }
            }

        val durationMs = durationSeconds * 1000L

        holdButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    longPressTimer =
                        object : CountDownTimer(durationMs, 50) {
                            override fun onTick(millisUntilFinished: Long) {
                                val elapsed = durationMs - millisUntilFinished
                                progressBar.progress = (elapsed * 100 / durationMs).toInt()
                            }

                            override fun onFinish() {
                                progressBar.progress = 100
                                notifyChallengeCompleted()
                            }
                        }.start()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressTimer?.cancel()
                    longPressTimer = null
                    progressBar.progress = 0
                    true
                }
                else -> false
            }
        }
        root.addView(holdButton)

        return root
    }

    private fun buildTypingChallengeView(
        displayText: String,
        bgColor: Int,
        buttonColor: Int,
        phrase: String,
    ): View {
        val root = buildChallengeRoot(bgColor)
        addCloseButton(root)
        addDisplayText(root, displayText)

        val instructionText =
            TextView(context).apply {
                text = "Type: \"$phrase\""
                setTextColor(0xCCFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(8), dp(16), dp(8), dp(4))
            }
        root.addView(instructionText)

        val editText =
            EditText(context).apply {
                setTextColor(0xFFFFFFFF.toInt())
                setHintTextColor(0x88FFFFFF.toInt())
                hint = "Type here..."
                setBackgroundColor(0x33FFFFFF.toInt())
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams =
                    LinearLayout
                        .LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = dp(8) }
            }
        root.addView(editText)

        val submitButton =
            Button(context).apply {
                text = "Submit"
                setTextColor(0xFFFFFFFF.toInt())
                backgroundTintList = ColorStateList.valueOf(buttonColor)
                isAllCaps = false
                setTypeface(typeface, Typeface.BOLD)
                isEnabled = false
                alpha = 0.5f
                layoutParams =
                    LinearLayout
                        .LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = dp(8) }
            }

        editText.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {}

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {}

                override fun afterTextChanged(s: Editable?) {
                    val matches = s.toString().trim().equals(phrase, ignoreCase = true)
                    submitButton.isEnabled = matches
                    submitButton.alpha = if (matches) 1.0f else 0.5f
                }
            },
        )

        submitButton.setOnClickListener { notifyChallengeCompleted() }
        root.addView(submitButton)

        return root
    }
}
