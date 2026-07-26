package com.workoutmaker.app.data

// The device side of the coach's update_app_settings tool. The server already
// validated against its whitelist, but the phone re-validates here: only what
// parses into a typed ParsedSetting can touch AppPreferences, so a compromised
// or buggy server reply still can't reach anything sensitive.

sealed class ParsedSetting(val label: String) {
    data class Theme(val mode: ThemeMode) : ParsedSetting("theme ${mode.label.lowercase()}")
    data class Palette(val palette: ThemePalette) : ParsedSetting("palette ${palette.label}")
    data class Units(val unit: WeightUnit) : ParsedSetting("units ${unit.suffix}")
    data class RestTimer(val seconds: Int) : ParsedSetting("rest timer ${seconds}s")
    data class KeepScreenOn(val on: Boolean) : ParsedSetting("keep screen on ${onOff(on)}")
    data class MorningNotify(val on: Boolean) : ParsedSetting("morning notification ${onOff(on)}")
    data class RestVibrate(val on: Boolean) : ParsedSetting("rest vibration ${onOff(on)}")
    data class RestNotify(val on: Boolean) : ParsedSetting("rest alerts ${onOff(on)}")

    companion object {
        private fun onOff(on: Boolean) = if (on) "on" else "off"
    }
}

/** Server change → typed setting, or null when it isn't one we allow. */
fun parseChatSetting(c: ChatSettingChange): ParsedSetting? {
    val v = c.value.trim()
    val bool = when (v.lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }
    return when (c.key) {
        "theme" -> when (v.lowercase()) {
            "system" -> ParsedSetting.Theme(ThemeMode.SYSTEM)
            "dark" -> ParsedSetting.Theme(ThemeMode.DARK)
            "light" -> ParsedSetting.Theme(ThemeMode.LIGHT)
            else -> null
        }
        "palette" -> ThemePalette.entries.firstOrNull { it.name.equals(v, ignoreCase = true) }
            ?.let { ParsedSetting.Palette(it) }
        "units" -> when (v.lowercase()) {
            "kg" -> ParsedSetting.Units(WeightUnit.KG)
            "lb" -> ParsedSetting.Units(WeightUnit.LB)
            else -> null
        }
        "rest_timer_seconds" -> v.toIntOrNull()?.takeIf { it in 0..600 }?.let { ParsedSetting.RestTimer(it) }
        "keep_screen_on" -> bool?.let { ParsedSetting.KeepScreenOn(it) }
        "morning_notification" -> bool?.let { ParsedSetting.MorningNotify(it) }
        "rest_vibrate" -> bool?.let { ParsedSetting.RestVibrate(it) }
        "rest_notify" -> bool?.let { ParsedSetting.RestNotify(it) }
        else -> null
    }
}

/** Apply the coach's setting changes; returns human labels of what changed. */
suspend fun AppPreferences.applyChatSettings(changes: List<ChatSettingChange>): List<String> {
    val applied = mutableListOf<String>()
    for (c in changes) {
        val parsed = parseChatSetting(c) ?: continue
        when (parsed) {
            is ParsedSetting.Theme -> setThemeMode(parsed.mode)
            is ParsedSetting.Palette -> setThemePalette(parsed.palette)
            is ParsedSetting.Units -> setUnits(parsed.unit)
            is ParsedSetting.RestTimer -> setDefaultRest(parsed.seconds)
            is ParsedSetting.KeepScreenOn -> setKeepScreenOn(parsed.on)
            is ParsedSetting.MorningNotify -> setMorningNotify(parsed.on)
            is ParsedSetting.RestVibrate -> setRestVibrate(parsed.on)
            is ParsedSetting.RestNotify -> setRestNotify(parsed.on)
        }
        applied += parsed.label
    }
    return applied
}
