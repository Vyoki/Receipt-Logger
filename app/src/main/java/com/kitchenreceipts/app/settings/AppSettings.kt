package com.kitchenreceipts.app.settings

import android.content.Context
import android.content.res.Configuration
import com.kitchenreceipts.core.ParseOptions
import java.util.Locale

/** Operator preferences, stored privately on this phone (SharedPreferences). */
class AppSettings(context: Context) {

    enum class Language(val tag: String?) { SYSTEM(null), ENGLISH("en"), ITALIAN("it") }

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var language: Language
        get() = readLanguage(prefs.getString(KEY_LANGUAGE, null))
        set(v) = prefs.edit().putString(KEY_LANGUAGE, v.name).apply()

    /** The restaurant's own name / VAT number: printed on supplier invoices, never taken as the seller. */
    var ownBusinessName: String
        get() = prefs.getString("own_name", "") ?: ""
        set(v) = prefs.edit().putString("own_name", v.trim()).apply()

    var ownVatNumber: String
        get() = prefs.getString("own_vat", "") ?: ""
        set(v) = prefs.edit().putString("own_vat", v.filter(Char::isDigit)).apply()

    var logEnabled: Boolean
        get() = prefs.getBoolean("log_enabled", true)
        set(v) = prefs.edit().putBoolean("log_enabled", v).apply()

    /** Include what was read from receipts (text, names, amounts) in the log. */
    var logIncludeText: Boolean
        get() = prefs.getBoolean("log_text", true)
        set(v) = prefs.edit().putBoolean("log_text", v).apply()

    /** Ask for fingerprint / face / screen-lock PIN when opening the app. */
    var appLock: Boolean
        get() = prefs.getBoolean("app_lock", false)
        set(v) = prefs.edit().putBoolean("app_lock", v).apply()

    /** Block screenshots and hide the app's content in the recent-apps screen. */
    var blockScreenshots: Boolean
        get() = prefs.getBoolean("secure_screen", false)
        set(v) = prefs.edit().putBoolean("secure_screen", v).apply()

    fun parseOptions() = ParseOptions(ownBusinessName.ifBlank { null }, ownVatNumber.ifBlank { null })

    companion object {
        const val FILE = "settings"
        private const val KEY_LANGUAGE = "language"

        private fun readLanguage(v: String?) = Language.entries.firstOrNull { it.name == v } ?: Language.SYSTEM

        /** Applies the chosen app language to a base context (called from the Activity's attachBaseContext). */
        fun wrapWithLanguage(base: Context): Context {
            val lang = readLanguage(base.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_LANGUAGE, null))
            val tag = lang.tag ?: return base
            val locale = Locale.forLanguageTag(tag)
            Locale.setDefault(locale)
            val config = Configuration(base.resources.configuration)
            config.setLocale(locale)
            return base.createConfigurationContext(config)
        }
    }
}
