package com.workoutmaker.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

// Device-local app preferences (not tied to the cloud profile). These are the
// "how the app behaves on this phone" knobs that used to be hardcoded or live
// scattered in screens; the holistic Settings panel now edits them in one place.

enum class ThemeMode(val label: String) {
    SYSTEM("Follow system"), DARK("Dark"), LIGHT("Light");

    companion object {
        fun fromName(n: String?): ThemeMode = entries.firstOrNull { it.name == n } ?: SYSTEM
    }
}

enum class WeightUnit(val label: String, val suffix: String) {
    KG("Kilograms", "kg"),
    LB("Pounds", "lb");

    companion object {
        private const val LB_PER_KG = 2.2046226218
        fun fromName(n: String?): WeightUnit = entries.firstOrNull { it.name == n } ?: KG
        fun kgToDisplay(kg: Double, unit: WeightUnit) = if (unit == LB) kg * LB_PER_KG else kg
        fun displayToKg(v: Double, unit: WeightUnit) = if (unit == LB) v / LB_PER_KG else v
    }
}

data class AppSettings(
    val units: WeightUnit = WeightUnit.KG,
    val defaultRestSec: Int = 120,
    val barbellKg: Double = 20.0,
    val restVibrate: Boolean = true,
    val restNotify: Boolean = true,
    val keepScreenOn: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
)

private val Context.dataStore by preferencesDataStore(name = "app_prefs")

@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val units = stringPreferencesKey("units")
        val defaultRest = intPreferencesKey("default_rest_sec")
        val barbell = doublePreferencesKey("barbell_kg")
        val restVibrate = booleanPreferencesKey("rest_vibrate")
        val restNotify = booleanPreferencesKey("rest_notify")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val themeMode = stringPreferencesKey("theme_mode")
        val onboardingComplete = booleanPreferencesKey("onboarding_complete")
    }

    // Last KNOWN onboarding state — consulted only when the network check fails,
    // so an offline cold start doesn't dump an onboarded user back into the
    // welcome flow. Reset on sign-out.
    suspend fun onboardingCompleteCached(): Boolean =
        context.dataStore.data.firstOrNull()?.get(Keys.onboardingComplete) ?: false

    suspend fun setOnboardingComplete(v: Boolean) = edit { it[Keys.onboardingComplete] = v }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            units = WeightUnit.fromName(p[Keys.units]),
            defaultRestSec = p[Keys.defaultRest] ?: 120,
            barbellKg = p[Keys.barbell] ?: 20.0,
            restVibrate = p[Keys.restVibrate] ?: true,
            restNotify = p[Keys.restNotify] ?: true,
            keepScreenOn = p[Keys.keepScreenOn] ?: true,
            themeMode = ThemeMode.fromName(p[Keys.themeMode]),
        )
    }

    suspend fun setUnits(u: WeightUnit) = edit { it[Keys.units] = u.name }
    suspend fun setDefaultRest(sec: Int) = edit { it[Keys.defaultRest] = sec.coerceIn(0, 600) }
    suspend fun setBarbell(kg: Double) = edit { it[Keys.barbell] = kg.coerceIn(0.0, 50.0) }
    suspend fun setRestVibrate(on: Boolean) = edit { it[Keys.restVibrate] = on }
    suspend fun setRestNotify(on: Boolean) = edit { it[Keys.restNotify] = on }
    suspend fun setKeepScreenOn(on: Boolean) = edit { it[Keys.keepScreenOn] = on }
    suspend fun setThemeMode(m: ThemeMode) = edit { it[Keys.themeMode] = m.name }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}

// Round a kg value to a clean display number for the active unit.
fun WeightUnit.format(kg: Double): String {
    val v = WeightUnit.kgToDisplay(kg, this)
    return if (kotlin.math.abs(v - v.roundToInt()) < 0.05) v.roundToInt().toString()
    else ((v * 10).roundToInt() / 10.0).toString()
}
