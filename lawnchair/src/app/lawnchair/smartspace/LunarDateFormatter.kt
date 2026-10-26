package app.lawnchair.smartspace

import android.graphics.Color
import android.icu.util.Calendar as IcuCalendar
import android.icu.util.ChineseCalendar
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

internal object LunarDateFormatter {

    private val monthNames = arrayOf("正月", "二月", "三月", "四月", "五月", "六月", "七月", "八月", "九月", "十月", "冬月", "腊月")
    private val solarTerms = listOf(
        SolarTerm("小寒", 285), SolarTerm("大寒", 300), SolarTerm("立春", 315), SolarTerm("雨水", 330),
        SolarTerm("惊蛰", 345), SolarTerm("春分", 0), SolarTerm("清明", 15), SolarTerm("谷雨", 30),
        SolarTerm("立夏", 45), SolarTerm("小满", 60), SolarTerm("芒种", 75), SolarTerm("夏至", 90),
        SolarTerm("小暑", 105), SolarTerm("大暑", 120), SolarTerm("立秋", 135), SolarTerm("处暑", 150),
        SolarTerm("白露", 165), SolarTerm("秋分", 180), SolarTerm("寒露", 195), SolarTerm("霜降", 210),
        SolarTerm("立冬", 225), SolarTerm("小雪", 240), SolarTerm("大雪", 255), SolarTerm("冬至", 270),
    )
    private val termDatesByYear = ConcurrentHashMap<Int, List<SolarTermDate>>()

    fun format(date: Date): CharSequence {
        val lunar = ChineseCalendar().apply { timeInMillis = date.time }
        val month = monthNames[lunar.get(IcuCalendar.MONTH).coerceIn(monthNames.indices)]
        val leapPrefix = if (lunar.get(IcuCalendar.IS_LEAP_MONTH) == 1) "闰" else ""
        val day = chineseDay(lunar.get(IcuCalendar.DAY_OF_MONTH))
        val lunarDate = "农历$leapPrefix$month$day"
        val term = solarTermOnDate(date.time) ?: return lunarDate
        val text = "$lunarDate · ${term.name}"
        return SpannableString(text).apply {
            setSpan(
                ForegroundColorSpan(Color.RED),
                text.length - term.name.length,
                text.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    fun formatNumeric(date: Date): CharSequence {
        val lunar = ChineseCalendar().apply { timeInMillis = date.time }
        return String.format(
            Locale.ENGLISH,
            "%02d-%02d",
            lunar.get(IcuCalendar.MONTH) + 1,
            lunar.get(IcuCalendar.DAY_OF_MONTH),
        )
    }

    fun solarTermName(date: Date): String? =
        solarTermOnDate(date.time)?.let { SolarTermTranslations.englishOrChinese(it.name) }

    private fun chineseDay(day: Int): String = when (day) {
        in 1..9 -> "初${digit(day)}"
        10 -> "初十"
        in 11..19 -> "十${digit(day - 10)}"
        20 -> "二十"
        in 21..29 -> "廿${digit(day - 20)}"
        30 -> "三十"
        else -> ""
    }

    private fun digit(value: Int) = when (value) {
        1 -> "一"; 2 -> "二"; 3 -> "三"; 4 -> "四"; 5 -> "五"
        6 -> "六"; 7 -> "七"; 8 -> "八"; 9 -> "九"
        else -> ""
    }

    private fun solarTermOnDate(now: Long): SolarTermDate? {
        val calendar = GregorianCalendar().apply { timeInMillis = now }
        val year = calendar.get(Calendar.YEAR)
        return termsForYear(year).firstOrNull { term ->
            val termCalendar = GregorianCalendar().apply { timeInMillis = term.timestamp }
            calendar.get(Calendar.ERA) == termCalendar.get(Calendar.ERA) &&
                calendar.get(Calendar.YEAR) == termCalendar.get(Calendar.YEAR) &&
                calendar.get(Calendar.DAY_OF_YEAR) == termCalendar.get(Calendar.DAY_OF_YEAR)
        }
    }

    private fun termsForYear(year: Int): List<SolarTermDate> = termDatesByYear.getOrPut(year) {
        val start = GregorianCalendar().apply {
            clear()
            set(year, Calendar.JANUARY, 1, 0, 0, 0)
        }.timeInMillis
        val end = GregorianCalendar().apply {
            clear()
            set(year + 1, Calendar.JANUARY, 1, 0, 0, 0)
        }.timeInMillis
        val stepMillis = 6L * 60L * 60L * 1000L
        var previousTime = start
        var previousLongitude = solarLongitude(start)
        var previousUnwrapped = previousLongitude
        val results = ArrayList<SolarTermDate>(24)

        while (previousTime < end) {
            val nextTime = minOf(previousTime + stepMillis, end)
            val nextLongitude = solarLongitude(nextTime)
            val longitudeDelta = if (nextLongitude < previousLongitude) {
                nextLongitude + 360.0 - previousLongitude
            } else {
                nextLongitude - previousLongitude
            }
            val nextUnwrapped = previousUnwrapped + longitudeDelta

            solarTerms.forEach { term ->
                val cycle = floor(previousUnwrapped / 360.0).toInt()
                var target = term.longitude + cycle * 360.0
                if (target <= previousUnwrapped) target += 360.0
                if (target > nextUnwrapped) return@forEach

                var low = previousTime
                var high = nextTime
                repeat(40) {
                    val middle = low + (high - low) / 2
                    if (longitudeNear(solarLongitude(middle), target) >= target) high = middle else low = middle
                }
                results += SolarTermDate(term.name, high)
            }

            previousTime = nextTime
            previousLongitude = nextLongitude
            previousUnwrapped = nextUnwrapped
        }
        results
    }

    private fun longitudeNear(longitude: Double, target: Double): Double =
        longitude + 360.0 * ((target - longitude) / 360.0).roundToInt()

    private fun solarLongitude(timestamp: Long): Double {
        val julianDay = timestamp / 86_400_000.0 + 2_440_587.5
        val centuries = (julianDay - 2_451_545.0) / 36_525.0
        val meanLongitude = normalize(280.46646 + centuries * (36_000.76983 + 0.0003032 * centuries))
        val meanAnomaly = Math.toRadians(normalize(357.52911 + centuries * (35_999.05029 - 0.0001537 * centuries)))
        val equationOfCenter =
            (1.914602 - centuries * (0.004817 + 0.000014 * centuries)) * sin(meanAnomaly) +
                (0.019993 - 0.000101 * centuries) * sin(2 * meanAnomaly) +
                0.000289 * sin(3 * meanAnomaly)
        val omega = Math.toRadians(125.04 - 1_934.136 * centuries)
        return normalize(meanLongitude + equationOfCenter - 0.00569 - 0.00478 * sin(omega))
    }

    private fun normalize(angle: Double): Double = ((angle % 360.0) + 360.0) % 360.0

    private data class SolarTerm(val name: String, val longitude: Int)
    private data class SolarTermDate(val name: String, val timestamp: Long)
}
