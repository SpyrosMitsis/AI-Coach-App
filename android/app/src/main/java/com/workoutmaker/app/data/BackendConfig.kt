package com.workoutmaker.app.data

import android.content.Context
import com.workoutmaker.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which Supabase backend this install talks to. Defaults to the values baked in
 * at build time; a self-hosted deployment can override them at runtime from the
 * sign-in screen ("Advanced: custom server"). Plain SharedPreferences (not
 * DataStore) on purpose: the Supabase client is a Hilt singleton built at
 * process start, so the values must be readable synchronously — and a change
 * only takes effect after an app restart.
 */
@Singleton
class BackendConfig @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("backend_config", Context.MODE_PRIVATE)

    val url: String
        get() = prefs.getString(KEY_URL, null)?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
            ?: BuildConfig.SUPABASE_URL

    val anonKey: String
        get() = prefs.getString(KEY_ANON_KEY, null)?.trim()?.takeIf { it.isNotEmpty() }
            ?: BuildConfig.SUPABASE_ANON_KEY

    val isCustom: Boolean
        get() = prefs.getString(KEY_URL, null)?.isNotBlank() == true

    /** Persist a custom backend; takes effect on the next app start. */
    fun setCustom(url: String, anonKey: String) {
        prefs.edit()
            .putString(KEY_URL, url.trim().trimEnd('/'))
            .putString(KEY_ANON_KEY, anonKey.trim())
            .commit()
    }

    /** Back to the built-in backend; takes effect on the next app start. */
    fun clearCustom() {
        prefs.edit().remove(KEY_URL).remove(KEY_ANON_KEY).commit()
    }

    private companion object {
        const val KEY_URL = "custom_supabase_url"
        const val KEY_ANON_KEY = "custom_supabase_anon_key"
    }
}
