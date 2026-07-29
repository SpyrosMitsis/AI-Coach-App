package com.workoutmaker.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// The device-side whitelist for coach-driven settings changes. The server
// validates too, but this layer is what actually guards AppPreferences — a
// malformed or hostile change must parse to null, never to an action.
class ChatSettingsTest {
    @Test
    fun `valid changes parse to typed settings with readable labels`() {
        assertEquals(
            ParsedSetting.Theme(ThemeMode.DARK),
            parseChatSetting(ChatSettingChange("theme", "dark")),
        )
        assertEquals(
            ParsedSetting.Units(WeightUnit.LB),
            parseChatSetting(ChatSettingChange("units", "lb")),
        )
        assertEquals(
            ParsedSetting.RestTimer(90),
            parseChatSetting(ChatSettingChange("rest_timer_seconds", "90")),
        )
        assertEquals(
            ParsedSetting.MorningNotify(false),
            parseChatSetting(ChatSettingChange("morning_notification", "false")),
        )
        assertEquals(
            ParsedSetting.Palette(ThemePalette.EMBER),
            parseChatSetting(ChatSettingChange("palette", "ember")),
        )
        assertEquals(
            "theme dark",
            parseChatSetting(ChatSettingChange("theme", "dark"))?.label,
        )
    }

    @Test
    fun `unknown keys never parse - the safe subset guarantee`() {
        assertNull(parseChatSetting(ChatSettingChange("api_key", "sk-evil")))
        assertNull(parseChatSetting(ChatSettingChange("delete_account", "true")))
        assertNull(parseChatSetting(ChatSettingChange("calendar_write", "true")))
        assertNull(parseChatSetting(ChatSettingChange("spend_cap_usd", "0")))
    }

    @Test
    fun `invalid values bounce even on whitelisted keys`() {
        assertNull(parseChatSetting(ChatSettingChange("theme", "neon")))
        assertNull(parseChatSetting(ChatSettingChange("rest_timer_seconds", "5000")))
        assertNull(parseChatSetting(ChatSettingChange("rest_timer_seconds", "abc")))
        assertNull(parseChatSetting(ChatSettingChange("keep_screen_on", "yes")))
        assertNull(parseChatSetting(ChatSettingChange("palette", "rainbow")))
    }
}
