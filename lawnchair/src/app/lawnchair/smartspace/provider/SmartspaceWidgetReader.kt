package app.lawnchair.smartspace.provider

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.descendants
import app.lawnchair.BlankActivity
import app.lawnchair.HeadlessWidgetsManager
import app.lawnchair.smartspace.model.SmartspaceAction
import app.lawnchair.smartspace.model.SmartspaceScores
import app.lawnchair.smartspace.model.SmartspaceTarget
import app.lawnchair.util.Temperature
import com.android.launcher3.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class SmartspaceWidgetReader(context: Context) :
    SmartspaceDataSource(
        context,
        R.string.smartspace_weather,
        { smartspaceAagWidget },
    ) {
    override val isAvailable: Boolean

    override val disabledTargets = listOf(dummyTarget)
    private val widget: HeadlessWidgetsManager.Widget?
    override val internalTargets: Flow<List<SmartspaceTarget>>

    init {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val provider = appWidgetManager.installedProviders.firstOrNull {
            it.provider.packageName == WEATHER_PACKAGE && it.provider.className == WIDGET_CLASS_NAME
        }
        isAvailable = provider != null
        if (provider != null) {
            kotlinx.coroutines.MainScope().launch { enabledPref.set(true) }
        }
        widget = provider?.let {
            val widgetsManager = HeadlessWidgetsManager.INSTANCE.get(context)
            widgetsManager.getWidget(provider, "smartspaceWidgetId")
        }
        internalTargets = widget?.updates?.map(this::extractWidgetLayout) ?: flowOf(disabledTargets)
    }

    override suspend fun requiresSetup() = widget?.isBound == false

    override suspend fun startSetup(activity: Activity) {
        val intent = widget?.getBindIntent() ?: return
        BlankActivity.startBlankActivityForResult(activity, intent)
    }

    private fun extractWidgetLayout(appWidgetHostView: ViewGroup): List<SmartspaceTarget> {
        val descendants = appWidgetHostView.descendants
        val texts = descendants.filterIsInstance<TextView>().filter { !it.text.isNullOrEmpty() }.toList()
        val imageViews = descendants.filterIsInstance<ImageView>().toList()
        val temperature = extractTemperatureText(texts.map { it.text.toString().trim() })
        if (texts.isEmpty()) return listOf(dummyTarget)
        val temperatureView = findTemperatureTextView(texts)
        // OnePlus puts the current weather glyph beside the city and
        // temperature. The final ImageView belongs to the Hourly forecast
        // notice, so it must not be used as the weather icon.
        val weatherImageView = temperatureView?.parent
            ?.let { it as? ViewGroup }
            ?.descendants
            ?.filterIsInstance<ImageView>()
            ?.firstOrNull()
        val weatherIcon = weatherImageView?.drawable?.let(::extractBitmap)
            ?: imageViews.getOrNull(2)?.drawable?.let(::extractBitmap)
        return parseData(weatherIcon, temperature)
    }

    private fun parseData(
        weatherIcon: Bitmap?,
        temperature: String?,
    ): List<SmartspaceTarget> {
        val weather = parseWeatherData(weatherIcon, temperature) ?: dummyTarget
        return listOf(weather)
    }

    private fun parseWeatherData(
        weatherIcon: Bitmap?,
        temperature: String?,
    ): SmartspaceTarget? {
        val weatherData = parseWeatherData(
            weatherIcon,
            temperature,
            null,
        ) ?: return null
        return SmartspaceTarget(
            id = "smartspaceWidgetWeather",
            headerAction = SmartspaceAction(
                id = "smartspaceWidgetWeatherAction",
                icon = weatherIcon?.let { Icon.createWithBitmap(it) },
                title = "",
                subtitle = weatherData.getTitle(),
                pendingIntent = weatherData.pendingIntent,
                intent = context.packageManager.getLaunchIntentForPackage(WEATHER_PACKAGE),
            ),
            score = SmartspaceScores.SCORE_WEATHER,
            featureType = SmartspaceTarget.FeatureType.FEATURE_WEATHER,
        )
    }

    private fun extractBitmap(drawable: Drawable): Bitmap {
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 1
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 1
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(canvas)
        }
    }

    private fun extractTemperatureText(values: List<String>): String? {
        val explicit = values.firstOrNull { value ->
            Regex("^-?\\d{1,3}\\s*(?:°\\s*[CF]?|[CFK])$", RegexOption.IGNORE_CASE).matches(value)
        }
        if (explicit != null) return explicit

        return values.zipWithNext().firstOrNull { (number, unit) ->
            number.matches(Regex("^-?\\d{1,3}$")) &&
                unit.matches(Regex("^°\\s*[CF]?$", RegexOption.IGNORE_CASE))
        }?.let { (number, unit) -> number + unit }
    }

    private fun findTemperatureTextView(texts: List<TextView>): TextView? {
        val explicit = texts.firstOrNull { view ->
            Regex("^-?\\d{1,3}\\s*(?:°\\s*[CF]?|[CFK])$", RegexOption.IGNORE_CASE)
                .matches(view.text.toString().trim())
        }
        if (explicit != null) return explicit

        return texts.zipWithNext().firstOrNull { (number, unit) ->
            number.text.toString().trim().matches(Regex("^-?\\d{1,3}$")) &&
                unit.text.toString().trim().matches(Regex("^°\\s*[CF]?$", RegexOption.IGNORE_CASE))
        }?.first
    }

    data class WeatherData(
        val icon: Bitmap,
        private val temperature: Temperature,
        val forecastUrl: String? = "https://www.google.com/search?q=weather",
        val forecastIntent: Intent? = null,
        val pendingIntent: PendingIntent? = null,
    ) {

        fun getTitle(unit: Temperature.Unit = temperature.unit): String {
            return "${temperature.inUnit(unit)}${unit.suffix}"
        }
    }

    companion object {
        private const val WEATHER_PACKAGE = "net.oneplus.weather"
        private const val WIDGET_CLASS_NAME = "com.oplus.weather.appWidget.TodayWeatherWidgetProvider"

        private val dummyTarget = SmartspaceTarget(
            id = "dummyTarget",
            featureType = SmartspaceTarget.FeatureType.FEATURE_WEATHER,
        )

        fun parseWeatherData(
            weatherIcon: Bitmap?,
            temperature: String?,
            intent: PendingIntent? = null,
        ): WeatherData? {
            return if (weatherIcon != null && temperature != null) {
                try {
                    val value = temperature.substring(0, temperature.indexOfFirst { (it < '0' || it > '9') && it != '-' }).toInt()
                    WeatherData(
                        weatherIcon,
                        Temperature(
                            value,
                            when {
                                temperature.contains("C") -> Temperature.Unit.Celsius
                                temperature.contains("F") -> Temperature.Unit.Fahrenheit
                                temperature.contains("K") -> Temperature.Unit.Kelvin
                                temperature.contains("°") -> Temperature.Unit.Celsius
                                else -> throw IllegalArgumentException("missing temperature unit")
                            },
                        ),
                        pendingIntent = intent,
                    )
                } catch (_: NumberFormatException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            } else {
                null
            }
        }

    }
}
