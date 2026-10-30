package dev.volumeflow.app.audio

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import dev.volumeflow.app.R
import kotlin.math.roundToInt

class VolumeOverlayAccessibilityService : AccessibilityService() {
    private lateinit var audioManager: AudioManager
    private lateinit var windowManager: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var foregroundPackage: String? = null
    private var overlay: View? = null
    private var label: TextView? = null
    private var seekBar: SeekBar? = null
    private var hideTask: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        serviceInfo = serviceInfo.apply {
            flags = flags or FLAG_REQUEST_FILTER_KEY_EVENTS or FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED
            notificationTimeout = 50
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg != packageName && pkg != "com.android.systemui") foregroundPackage = pkg
    }

    override fun onInterrupt() = hideOverlay()

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return false
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP && event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (!::audioManager.isInitialized || !audioManager.isMusicActive) return false
        val target = foregroundPackage ?: return false
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val current = prefs.getFloat(target, 1f).coerceIn(0f, 1f)
        val delta = if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1f / 120f else -1f / 120f
        val next = (current + delta).coerceIn(0f, 1f)
        prefs.edit().putFloat(target, next).apply()
        VolumeFlowShizuku.applyAppGain(target, next)
        showOverlay(target, next)
        return true
    }

    private fun showOverlay(pkg: String, gain: Float) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { showOverlay(pkg, gain) }
            return
        }
        val root = overlay ?: createOverlay().also { overlay = it }
        label?.text = appLabel(pkg) + "  ·  " + (gain * 120f).roundToInt() + "/120"
        seekBar?.progress = (gain * 120f).roundToInt()
        if (root.parent == null) {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (resources.displayMetrics.heightPixels * 0.10f).roundToInt()
                width = (resources.displayMetrics.widthPixels * 0.88f).roundToInt()
            }
            runCatching { windowManager.addView(root, params) }
        }
        hideTask?.let(handler::removeCallbacks)
        hideTask = Runnable { hideOverlay() }.also { handler.postDelayed(it, 2200L) }
    }

    private fun createOverlay(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(12))
            background = GradientDrawable().apply {
                setColor(0xEE252630.toInt())
                cornerRadius = dp(28).toFloat()
                setStroke(dp(1), 0x445F6270)
            }
            elevation = dp(12).toFloat()
        }
        label = TextView(this).apply {
            text = "Media volume"
            textSize = 15f
            setTextColor(0xFFF4F1FA.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        seekBar = SeekBar(this).apply {
            max = 120
            progress = 120
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val pkg = foregroundPackage ?: return
                    val gain = progress / 120f
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putFloat(pkg, gain).apply()
                    VolumeFlowShizuku.applyAppGain(pkg, gain)
                    label?.text = appLabel(pkg) + "  ·  " + progress + "/120"
                }
                override fun onStartTrackingTouch(bar: SeekBar?) { hideTask?.let(handler::removeCallbacks) }
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    hideTask = Runnable { hideOverlay() }.also { handler.postDelayed(it, 2200L) }
                }
            })
        }
        panel.addView(label)
        panel.addView(seekBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)))
        return panel
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun hideOverlay() {
        handler.post {
            hideTask?.let(handler::removeCallbacks)
            hideTask = null
            overlay?.let { view -> runCatching { if (view.parent != null) windowManager.removeView(view) } }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    override fun onDestroy() {
        hideOverlay()
        super.onDestroy()
    }

    companion object {
        private const val PREFS = "volume_overlay_profiles"
    }
}
