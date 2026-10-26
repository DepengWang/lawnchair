package app.lawnchair.smartspace

internal object SolarTermTranslations {

    private data class SolarTerm(val chinese: String, val english: String)

    private val solarTerms = listOf(
        SolarTerm("立春", "Start of Spring"),
        SolarTerm("雨水", "Rain Water"),
        SolarTerm("惊蛰", "Insects Awaken"),
        SolarTerm("春分", "Spring Equinox"),
        SolarTerm("清明", "Clear and Bright"),
        SolarTerm("谷雨", "Grain Rain"),
        SolarTerm("立夏", "Start of Summer"),
        SolarTerm("小满", "Grain Buds"),
        SolarTerm("芒种", "Grain in Ear"),
        SolarTerm("夏至", "Summer Solstice"),
        SolarTerm("小暑", "Minor Heat"),
        SolarTerm("大暑", "Major Heat"),
        SolarTerm("立秋", "Start of Autumn"),
        SolarTerm("处暑", "End of Heat"),
        SolarTerm("白露", "White Dew"),
        SolarTerm("秋分", "Autumn Equinox"),
        SolarTerm("寒露", "Cold Dew"),
        SolarTerm("霜降", "Frost Descent"),
        SolarTerm("立冬", "Start of Winter"),
        SolarTerm("小雪", "Minor Snow"),
        SolarTerm("大雪", "Major Snow"),
        SolarTerm("冬至", "Winter Solstice"),
        SolarTerm("小寒", "Minor Cold"),
        SolarTerm("大寒", "Major Cold"),
    )
    private val translations = solarTerms.associate { it.chinese to it.english }

    fun englishOrChinese(chinese: String): String = translations[chinese] ?: chinese
}
