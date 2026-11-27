// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.PowerManager
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Notifications with the screen off, in place of the LED of old phones: while the screen is off, a black field over the
 * whole always-on display, hiding the clock and the date of the system, with a clock of its own (hours over minutes, in
 * grey, no date) where the clock of the system is, the icon of Keyarchy on the fingerprint sensor and the white icons
 * of the apps that have a notification neither silent nor ongoing where they are in the status bar, at the top left. No
 * wake lock and no animation (the clock changes with the minute ticks of the system); the clock and the icons of the
 * apps a few pixels elsewhere at each screen off, against burn-in. Turned on in the settings ("Notifications with the
 * screen off"); needs the accessibility service, whose window type draws over the lock screen.
 */
class NotificationIcons(private val service: AccessibilityService) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density
    /** the black field, while shown */
    private var field: FrameLayout? = null
    /** the icons, in the [field] */
    private var icons: LinearLayout? = null
    private var screenOff = !service.getSystemService(PowerManager::class.java).isInteractive

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOff = intent.action == Intent.ACTION_SCREEN_OFF
            hide() // shown again elsewhere
            update()
        }
    }

    fun start() {
        service.registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) })
        update()
    }

    fun stop() {
        service.unregisterReceiver(receiver)
        hide()
    }

    /** shows or hides the field and what is in it for the screen and the notifications now; called when either changes */
    fun update() {
        if (!screenOff || Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                || !service.prefs().getBoolean(Settings.PREF_NOTIFICATION_ICONS, Defaults.PREF_NOTIFICATION_ICONS)) {
            hide()
            return
        }
        if (field == null) show()
        val row = icons ?: return
        row.removeAllViews()
        val size = dp(ICON_DP)
        for (sbn in LauncherNotificationService.loud().distinctBy { it.packageName }) {
            val icon = try { sbn.notification.smallIcon?.loadDrawable(service) } catch (e: Exception) { null } ?: continue
            icon.setTint(Color.WHITE)
            row.addView(ImageView(service).apply { setImageDrawable(icon) },
                LinearLayout.LayoutParams(size, size).apply { if (row.childCount > 0) marginStart = dp(ICON_GAP_DP) })
        }
    }

    private fun show() {
        val metrics = windowManager.maximumWindowMetrics
        val shift = { Random.nextInt(-SHIFT_DP, SHIFT_DP + 1) * density }
        val row = LinearLayout(service).apply { gravity = Gravity.CENTER_VERTICAL }
        val logo = ImageView(service).apply {
            setImageResource(R.drawable.ic_launcher_monochrome)
            setColorFilter(LOGO_COLOR)
        }
        val clock = TextClock(service).apply {
            // the hours over the minutes, in thin figures
            format12Hour = "hh\nmm"
            format24Hour = "HH\nmm"
            gravity = Gravity.CENTER_HORIZONTAL
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, CLOCK_TEXT_DP)
            setLineSpacing(0f, CLOCK_LINE_SPACING)
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            includeFontPadding = false
            setTextColor(LOGO_COLOR)
        }
        val view = FrameLayout(service).apply {
            setBackgroundColor(Color.BLACK)
            addView(clock, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(CLOCK_TOP_DP) })
            addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, dp(ICON_DP), Gravity.TOP or Gravity.START)
                .apply { marginStart = dp(START_DP); topMargin = dp(ICONS_CENTER_DP) - dp(ICON_DP) / 2 })
            addView(logo, FrameLayout.LayoutParams((LOGO_DP * density).roundToInt(), (LOGO_DP * density).roundToInt(),
                Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = ((SENSOR_CENTER_DP - LOGO_DP / 2) * density).roundToInt() })
        }
        // the icon of Keyarchy stays put, on the sensor
        row.translationX = shift()
        row.translationY = shift()
        clock.translationX = shift()
        clock.translationY = shift()
        val params = WindowManager.LayoutParams(metrics.bounds.width(), metrics.bounds.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        try {
            windowManager.addView(view, params)
            field = view
            icons = row
        } catch (e: Exception) {
            Log.w(TAG, "can't show the notification light", e)
        }
    }

    private fun hide() {
        val view = field ?: return
        field = null
        icons = null
        try { windowManager.removeView(view) } catch (_: Exception) { }
    }

    private fun dp(value: Int) = (value * density).roundToInt()

    companion object {
        private const val TAG = "notification-icons"
        /**
         * From the left edge to the first icon, and from the top to the middle of the icons: the middle is that of
         * the icons in the status bar of the Pixel 8 Pro, and the first icon half as far from the edge as there.
         */
        private const val START_DP = 42
        private const val ICONS_CENTER_DP = 25
        private const val ICON_DP = 32
        private const val ICON_GAP_DP = 12
        /**
         * The fingerprint sensor under the screen, where the icon of Keyarchy is to know where to put the thumb: its
         * size and the middle of it from the top, from the bounds that SystemUI of the Pixel 8 Pro reports
         * (udfpsBounds 557,2146-787,2376 at 3 pixels per dp); it is in the middle across.
         */
        private const val SENSOR_DP = 230 / 3f
        private const val SENSOR_CENTER_DP = 2261 / 3f
        /**
         * The monochrome icon, without its background: the crown is 42 % as wide as the drawable, so this makes it two
         * thirds as wide as the sensor
         */
        private const val LOGO_DP = SENSOR_DP * 1.6f
        /** a discreet grey, also of the clock */
        private const val LOGO_COLOR = 0xFF808080.toInt()
        /** the clock where the system has its clock and date on the Pixel 8 Pro (425 to 604 pixels from the top) */
        private const val CLOCK_TOP_DP = 142
        private const val CLOCK_TEXT_DP = 120f
        /** the two lines of the clock close together */
        private const val CLOCK_LINE_SPACING = 0.85f
        /** the most it moves each time, in each direction */
        private const val SHIFT_DP = 3
    }
}
