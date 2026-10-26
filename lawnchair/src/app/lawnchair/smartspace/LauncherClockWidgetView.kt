package app.lawnchair.smartspace

import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.format.DateFormat
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.smartspace.model.SmartspaceTarget
import app.lawnchair.smartspace.model.SmartspaceCalendar
import app.lawnchair.smartspace.model.SmartspaceTimeFormat
import app.lawnchair.smartspace.provider.SmartspaceProvider
import com.android.launcher3.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.combine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

private val WIDGET_MUTED_COLOR = Color.rgb(170, 176, 187)
private const val WIDGET_CLICK_HOLD_DURATION_MS = 500L

private class TabularDigitSpan(private val highlightRed: Boolean) : ReplacementSpan() {
    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fontMetricsInt: Paint.FontMetricsInt?,
    ): Int {
        fontMetricsInt?.let(paint::getFontMetricsInt)
        val widestDigit = ('0'..'9').maxOf { paint.measureText(it.toString()) }
        return ceil(widestDigit).toInt()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        val cellWidth = getSize(paint, text, start, end, null)
        val digitWidth = paint.measureText(text, start, end)
        val originalColor = paint.color
        if (highlightRed) paint.color = Color.RED
        canvas.drawText(text, start, end, x + (cellWidth - digitWidth) / 2f, y.toFloat(), paint)
        paint.color = originalColor
    }
}

class LauncherClockWidgetView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private lateinit var dateClock: TextView
    private lateinit var timeClock: TextView
    private lateinit var lunarClock: TextView
    private lateinit var solarTermValue: TextView
    private lateinit var weatherContainer: View
    private lateinit var weatherIcon: ImageView
    private lateinit var temperature: TextView
    private lateinit var healthData: LinearLayout
    private lateinit var lunarRow: LinearLayout
    private lateinit var solarTermRow: LinearLayout
    private lateinit var runningItem: LinearLayout
    private lateinit var steps: TextView
    private lateinit var running: TextView
    private lateinit var sleep: TextView
    private lateinit var heartRate: TextView
    private lateinit var oxygenSaturation: TextView

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            clockHandler.postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L)
        }
    }
    private val healthRefreshTick = object : Runnable {
        override fun run() {
            refreshHealthMetrics()
            clockHandler.postDelayed(this, 5 * 60_000L)
        }
    }
    private var weatherJob: Job? = null
    private var settingsJob: Job? = null
    private var healthJob: Job? = null
    private var selectedTimeFormat: SmartspaceTimeFormat = SmartspaceTimeFormat.FollowSystem
    private var selectedCalendar: SmartspaceCalendar = SmartspaceCalendar.Gregorian
    private var selectedClockFont = "orbitron"
    private var selectedTimeFontSizeSp = 108
    private var widgetWidth = 0

    override fun onFinishInflate() {
        super.onFinishInflate()
        dateClock = findViewById(R.id.clock_widget_date)
        timeClock = findViewById(R.id.clock_widget_time)
        lunarClock = findViewById(R.id.clock_widget_lunar)
        weatherContainer = findViewById(R.id.clock_widget_weather)
        weatherIcon = findViewById(R.id.clock_widget_weather_icon)
        temperature = findViewById(R.id.clock_widget_temperature)
        healthData = findViewById(R.id.clock_widget_health_data)
        steps = findViewById(R.id.clock_widget_steps)
        running = findViewById(R.id.clock_widget_running)
        sleep = findViewById(R.id.clock_widget_sleep)
        heartRate = findViewById(R.id.clock_widget_heart_rate)
        oxygenSaturation = findViewById(R.id.clock_widget_oxygen_saturation)
        configureHealthRows()
        healthData.translationY = -4f * resources.displayMetrics.density
        timeClock.translationY = -4f * resources.displayMetrics.density
        setProtectedClickListener(timeClock) { openAlarmApp() }
        setProtectedClickListener(dateClock) { openGoogleCalendar() }

        val orbitron = resources.getFont(R.font.orbitron_regular)
        dateClock.typeface = orbitron
        timeClock.typeface = orbitron
        temperature.typeface = orbitron
        lunarClock.typeface = Typeface.DEFAULT
        updateClock()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        clockHandler.removeCallbacks(clockTick)
        clockTick.run()
        clockHandler.removeCallbacks(healthRefreshTick)
        healthRefreshTick.run()
        val preferences = PreferenceManager2.getInstance(context)
        settingsJob?.cancel()
        settingsJob = combine(
            preferences.smartspaceTimeFormat.get(),
            preferences.smartspaceClockFontSize.get(),
            preferences.smartspaceCalendar.get(),
            preferences.smartspaceClockFont.get(),
        ) { timeFormat, fontSize, calendar, clockFont ->
            (timeFormat to fontSize) to (calendar to clockFont)
        }
            .onEach { (timeSettings, calendarSettings) ->
                val (timeFormat, fontSize) = timeSettings
                val (calendar, clockFont) = calendarSettings
                selectedTimeFormat = timeFormat
                selectedTimeFontSizeSp = fontSize.coerceIn(36, 108)
                selectedCalendar = calendar
                selectedClockFont = clockFont
                updateClockTypeface()
                updateClock()
                updateTextSizes()
            }
            .launchIn(scope)
        weatherJob?.cancel()
        weatherJob = SmartspaceProvider.INSTANCE.get(context).targets
            .onEach(::updateWeather)
            .launchIn(scope)
    }

    override fun onDetachedFromWindow() {
        weatherJob?.cancel()
        weatherJob = null
        settingsJob?.cancel()
        settingsJob = null
        clockHandler.removeCallbacks(clockTick)
        clockHandler.removeCallbacks(healthRefreshTick)
        healthJob?.cancel()
        healthJob = null
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE && isAttachedToWindow) {
            refreshHealthMetrics()
        }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (!::timeClock.isInitialized || width <= 0) return

        widgetWidth = width
        updateTextSizes()
    }

    private fun updateTextSizes() {
        if (!::timeClock.isInitialized || widgetWidth <= 0) return

        val density = resources.displayMetrics.density
        val availableWidthDp = (widgetWidth - paddingLeft - paddingRight) / density
        val fittingTimeSize = (availableWidthDp * 0.30f).coerceIn(36f, 108f)
        val timeSize = minOf(fittingTimeSize, selectedTimeFontSizeSp.toFloat())
        val headerSize = (availableWidthDp * 0.055f * 0.95f).coerceIn(14f, 22f)
        timeClock.setTextSize(TypedValue.COMPLEX_UNIT_SP, timeSize)
        dateClock.setTextSize(TypedValue.COMPLEX_UNIT_SP, headerSize)
        temperature.setTextSize(TypedValue.COMPLEX_UNIT_SP, headerSize)
        lunarClock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
    }

    private fun configureHealthRows() {
        solarTermValue = TextView(context)
        listOf(lunarClock, running, steps, sleep, heartRate, oxygenSaturation).forEach { value ->
            (value.parent as? android.view.ViewGroup)?.removeView(value)
            value.visibility = View.VISIBLE
            value.includeFontPadding = false
            value.maxLines = 1
            value.setTextColor(Color.WHITE)
            value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            value.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        solarTermValue.includeFontPadding = false
        solarTermValue.maxLines = 1
        solarTermValue.setTextColor(Color.RED)
        solarTermValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        solarTermValue.typeface = Typeface.create("sans-serif", Typeface.NORMAL)

        healthData.removeAllViews()
        healthData.orientation = VERTICAL
        healthData.gravity = Gravity.CENTER_HORIZONTAL
        healthData.layoutParams = LayoutParams(
            (190 * resources.displayMetrics.density).toInt(),
            LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }
        healthData.addView(createHealthRow("Lunar", lunarClock).also { lunarRow = it })
        healthData.addView(createHealthRow("Solar Term", solarTermValue).also { solarTermRow = it })
        solarTermRow.visibility = View.GONE
        healthData.addView(createHealthRow("Running", running).also {
            runningItem = it
            bindHuaweiHealthClick(it)
        })
        runningItem.id = R.id.clock_widget_running_item
        runningItem.visibility = View.GONE
        healthData.addView(createHealthRow("Steps", steps).also(::bindHuaweiHealthClick))
        healthData.addView(createHealthRow("Sleep", sleep).also(::bindHuaweiHealthClick))
        healthData.addView(createHealthRow("Pulse", heartRate).also(::bindHuaweiHealthClick))
        healthData.addView(createHealthRow("SpO₂", oxygenSaturation).also(::bindHuaweiHealthClick))
    }

    private fun bindHuaweiHealthClick(row: LinearLayout) {
        setProtectedClickListener(row) { openHuaweiHealth() }
    }

    private fun setProtectedClickListener(view: View, action: () -> Unit) {
        view.setOnClickListener(null)
        view.setOnLongClickListener(null)
        val touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var holdCancelled = false
        val holdAction = Runnable {
            if (!holdCancelled) action()
        }
        view.setOnTouchListener { touchedView, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    holdCancelled = false
                    touchedView.parent?.requestDisallowInterceptTouchEvent(true)
                    touchedView.removeCallbacks(holdAction)
                    touchedView.postDelayed(holdAction, WIDGET_CLICK_HOLD_DURATION_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!holdCancelled &&
                        (kotlin.math.abs(event.x - downX) > touchSlop ||
                            kotlin.math.abs(event.y - downY) > touchSlop)
                    ) {
                        holdCancelled = true
                        touchedView.removeCallbacks(holdAction)
                        touchedView.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    holdCancelled = true
                    touchedView.removeCallbacks(holdAction)
                    touchedView.parent?.requestDisallowInterceptTouchEvent(false)
                    true
                }
                else -> true
            }
        }
        view.isClickable = true
    }

    private fun openAlarmApp() {
        if (openFirstAvailablePackage(
                "com.oneplus.deskclock",
                "com.oplus.deskclock",
                "com.google.android.deskclock",
            )
        ) {
            return
        }

        val showAlarmsIntent = Intent(AlarmClock.ACTION_SHOW_ALARMS)
        startActivityIfPossible(showAlarmsIntent)
    }

    private fun openHuaweiHealth() {
        openPackage("com.huawei.health")
    }

    private fun openGoogleCalendar() {
        openPackage("com.google.android.calendar")
    }

    private fun openPackage(packageName: String): Boolean {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return false
        return startActivityIfPossible(launchIntent)
    }

    private fun openFirstAvailablePackage(vararg packageNames: String): Boolean {
        return packageNames.any(::openPackage)
    }

    private fun startActivityIfPossible(intent: Intent): Boolean {
        return try {
            if (context !is android.app.Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun createHealthRow(label: String, value: TextView): LinearLayout {
        val density = resources.displayMetrics.density
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (23 * density).toInt()
        }
        val labelView = TextView(context).apply {
            text = label
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            includeFontPadding = false
            setPaddingRelative(paddingStart, paddingTop, (8 * density).toInt(), paddingBottom)
            setTextColor(WIDGET_MUTED_COLOR)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        value.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        value.setPaddingRelative((8 * density).toInt(), value.paddingTop, value.paddingEnd, value.paddingBottom)
        row.addView(labelView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        row.addView(value, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        row.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        return row
    }

    private fun updateClockTypeface() {
        if (!::dateClock.isInitialized) return
        val font = if (selectedClockFont == "clockopia") R.font.clockopia else R.font.orbitron_regular
        val typeface = resources.getFont(font)
        dateClock.typeface = typeface
        timeClock.typeface = typeface
        temperature.typeface = typeface
    }

    private fun refreshHealthMetrics() {
        if (!::steps.isInitialized) return
        healthJob?.cancel()
        healthJob = scope.launch {
            val metrics = withContext(Dispatchers.IO) {
                HealthMetricsReader.read(context)
            }
            val integerFormat = java.text.NumberFormat.getIntegerInstance(Locale.US)
            steps.text = metrics.steps?.let(integerFormat::format) ?: "--"
            sleep.text = metrics.sleepMinutes?.let { minutes ->
                "${minutes / 60}h ${minutes % 60}m"
            } ?: "--"
            heartRate.text = metrics.heartRateBpm?.let { "$it bpm" } ?: "-- bpm"
            oxygenSaturation.text = metrics.oxygenSaturationPercent?.let { "$it%" } ?: "--%"
            val hasRunningDistance = metrics.hasRunningSession &&
                (metrics.runningDistanceMeters ?: 0.0) > 0.0
            runningItem.visibility = if (hasRunningDistance) View.VISIBLE else View.GONE
            running.text = metrics.runningDistanceMeters?.let {
                "${String.format(Locale.US, "%.2f", it / 1_000.0)} km"
            } ?: "-- km"
        }
    }

    private fun recolorWeatherIcon(drawable: Drawable): Drawable {
        val sourceWidth = drawable.intrinsicWidth.coerceAtLeast(1)
        val sourceHeight = drawable.intrinsicHeight.coerceAtLeast(1)
        val maxBitmapSide = (96 * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val scale = minOf(1f, maxBitmapSide / maxOf(sourceWidth, sourceHeight).toFloat())
        val bitmapWidth = (sourceWidth * scale).toInt().coerceAtLeast(1)
        val bitmapHeight = (sourceHeight * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val originalBounds = Rect(drawable.bounds)
        try {
            drawable.setBounds(0, 0, bitmapWidth, bitmapHeight)
            drawable.draw(Canvas(bitmap))
        } finally {
            drawable.bounds = originalBounds
        }

        val pixels = IntArray(bitmapWidth * bitmapHeight)
        bitmap.getPixels(pixels, 0, bitmapWidth, 0, 0, bitmapWidth, bitmapHeight)

        // Group fully visible pixels into quantized colors so antialiasing edges
        // do not make a single-color icon look like a multicolor icon.
        val buckets = HashMap<Int, IntArray>()
        var opaquePixelCount = 0
        for (index in pixels.indices) {
            val pixel = pixels[index]
            if (Color.alpha(pixel) < 192) continue
            val red = Color.red(pixel)
            val green = Color.green(pixel)
            val blue = Color.blue(pixel)
            val key = ((red shr 5) shl 6) or ((green shr 5) shl 3) or (blue shr 5)
            val totals = buckets.getOrPut(key) { IntArray(4) }
            totals[0]++
            totals[1] += red
            totals[2] += green
            totals[3] += blue
            opaquePixelCount++
        }

        val colorGroups = buckets.values.map { totals ->
            intArrayOf(
                totals[1] / totals[0],
                totals[2] / totals[0],
                totals[3] / totals[0],
                totals[0],
            )
        }.sortedByDescending { it[3] }
        fun distanceSquared(first: IntArray, second: IntArray): Int {
            val red = first[0] - second[0]
            val green = first[1] - second[1]
            val blue = first[2] - second[2]
            return red * red + green * green + blue * blue
        }
        fun distanceSquared(pixel: Int, color: IntArray): Int {
            val red = Color.red(pixel) - color[0]
            val green = Color.green(pixel) - color[1]
            val blue = Color.blue(pixel) - color[2]
            return red * red + green * green + blue * blue
        }
        fun luminance(color: IntArray): Double =
            0.2126 * color[0] + 0.7152 * color[1] + 0.0722 * color[2]

        val firstColor = colorGroups.firstOrNull()
        val secondColor = colorGroups.getOrNull(1)
        val minGroupSize = maxOf(4, (opaquePixelCount * 0.025f).toInt())
        val hasTwoColors = firstColor != null && secondColor != null &&
            secondColor[3] >= minGroupSize &&
            distanceSquared(firstColor, secondColor) >= 48 * 48 &&
            colorGroups.drop(2).none { color ->
                color[3] >= minGroupSize &&
                    distanceSquared(color, firstColor) >= 48 * 48 &&
                    distanceSquared(color, secondColor) >= 48 * 48
            }
        val firstIsLighter = firstColor != null && secondColor != null &&
            luminance(firstColor) >= luminance(secondColor)

        for (index in pixels.indices) {
            val pixel = pixels[index]
            val alpha = Color.alpha(pixel)
            if (alpha == 0) continue
            val targetColor = if (hasTwoColors && firstColor != null && secondColor != null) {
                val belongsToSecond = distanceSquared(pixel, secondColor) <
                    distanceSquared(pixel, firstColor)
                val belongsToDarker = if (belongsToSecond) firstIsLighter else !firstIsLighter
                if (belongsToDarker) WIDGET_MUTED_COLOR else Color.WHITE
            } else {
                Color.WHITE
            }
            pixels[index] = Color.argb(alpha, Color.red(targetColor), Color.green(targetColor), Color.blue(targetColor))
        }
        bitmap.setPixels(pixels, 0, bitmapWidth, 0, 0, bitmapWidth, bitmapHeight)
        return BitmapDrawable(resources, bitmap).apply { isFilterBitmap = true }
    }

    private fun updateWeather(targets: List<SmartspaceTarget>) {
        val weatherTarget = targets.firstOrNull {
            it.featureType == SmartspaceTarget.FeatureType.FEATURE_WEATHER
        }
        val action = weatherTarget?.headerAction
        val icon = action?.icon
        val weatherText = action?.subtitle
        if (icon == null || weatherText.isNullOrBlank()) {
            weatherContainer.visibility = View.GONE
            weatherContainer.setOnClickListener(null)
            weatherContainer.setOnTouchListener(null)
            return
        }

        val weatherDrawable = BcSmartSpaceUtil.getIconDrawable(icon, context)
        weatherIcon.setImageDrawable(weatherDrawable?.let(::recolorWeatherIcon))
        temperature.text = weatherText
        weatherContainer.visibility = View.VISIBLE
        setProtectedClickListener(weatherContainer) { openWeatherApp() }
    }

    private fun openWeatherApp() {
        if (openFirstAvailablePackage(
                "net.oneplus.weather",
                "com.oplus.weather",
                "com.coloros.weather2",
                "com.oneplus.weather",
                "com.heytap.weather",
                "com.huawei.android.totemweather",
                "com.miui.weather2",
                "com.samsung.android.weather",
            )
        ) {
            return
        }

        // Google Weather is hosted by the Google app rather than a standalone package.
        val googleWeatherIntent = Intent().setClassName(
            "com.google.android.googlequicksearchbox",
            "com.google.android.apps.search.weather.WeatherExportedActivity",
        )
        if (!startActivityIfPossible(googleWeatherIntent)) {
            openPackage("com.google.android.googlequicksearchbox")
        }
    }

    private fun updateClock() {
        if (!::timeClock.isInitialized) return

        val now = Date()
        val use24HourFormat = when (selectedTimeFormat) {
            SmartspaceTimeFormat.TwentyFourHourFormat -> true
            SmartspaceTimeFormat.TwelveHourFormat -> false
            else -> DateFormat.is24HourFormat(context)
        }
        val timePattern = if (use24HourFormat) "HH:mm" else "h:mm"
        val time = SimpleDateFormat(timePattern, Locale.ENGLISH).format(now)
        val styledTime = SpannableString(time)
        val hourEnd = time.indexOf(':').let { if (it < 0) time.length else it }
        for (index in time.indices) {
            if (time[index].isDigit()) {
                styledTime.setSpan(
                    TabularDigitSpan(index < hourEnd && time[index] in "12"),
                    index,
                    index + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        timeClock.text = styledTime
        dateClock.text = SimpleDateFormat("EEEE, MMM d", Locale.ENGLISH).format(now)
        if (selectedCalendar == SmartspaceCalendar.Lunar) {
            lunarClock.text = LunarDateFormatter.formatNumeric(now)
            lunarRow.visibility = View.VISIBLE
            val solarTermName = LunarDateFormatter.solarTermName(now)
            if (solarTermName != null) {
                solarTermValue.text = solarTermName
                solarTermRow.visibility = View.VISIBLE
            } else {
                solarTermRow.visibility = View.GONE
            }
        } else {
            lunarRow.visibility = View.GONE
            solarTermRow.visibility = View.GONE
        }
    }
}
