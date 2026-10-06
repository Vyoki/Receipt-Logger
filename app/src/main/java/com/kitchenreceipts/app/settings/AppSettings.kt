package com.kitchenreceipts.app.settings

import android.content.Context
import android.content.res.Configuration
import com.kitchenreceipts.core.ParseOptions
import java.util.Locale

/** Operator preferences, stored privately on this phone (SharedPreferences). */
class AppSettings(context: Context) {

    enum class Language(val tag: String?) { SYSTEM(null), ENGLISH("en"), ITALIAN("it") }

    /** When the on-phone AI reader runs (only if a model is installed). */
    enum class AiMode { OFF, WHEN_NEEDED, ALWAYS }

    /**
     * How knowledge reaches the phone. In no mode does anything about the documents leave it: the only connection
     * ever made downloads the public knowledge pack, the same file for everyone.
     * OFFLINE: never connects; packs arrive with app updates or as a file the operator opens.
     * HYBRID: connects only when the operator taps "Check for updates".
     * AUTOMATIC: also checks by itself about once a week, on Wi-Fi only.
     */
    enum class NetworkMode { OFFLINE, HYBRID, AUTOMATIC }

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

    /** Product categories stored by the old guesser were handed back to the new rules (done once, October 2026). */
    var categoriesRegrouped: Boolean
        get() = prefs.getBoolean("categories_regrouped_v2", false)
        set(v) = prefs.edit().putBoolean("categories_regrouped_v2", v).apply()

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

    /** Save a scan straight away when everything was read with confidence and the amounts add up. */
    var autoSave: Boolean
        get() = prefs.getBoolean("auto_save", true)
        set(v) = prefs.edit().putBoolean("auto_save", v).apply()

    /** Link lines to products automatically (remembered, recognised despite typos, or new). */
    var autoLinkProducts: Boolean
        get() = prefs.getBoolean("auto_link", true)
        set(v) = prefs.edit().putBoolean("auto_link", v).apply()

    /** Notify when a document read in the background has prices 5% or more above/below the last purchase. */
    var priceAlerts: Boolean
        get() = prefs.getBoolean("price_alerts", true)
        set(v) = prefs.edit().putBoolean("price_alerts", v).apply()

    var networkMode: NetworkMode
        get() = NetworkMode.entries.firstOrNull { it.name == prefs.getString("network_mode", null) } ?: NetworkMode.OFFLINE
        set(v) = prefs.edit().putString("network_mode", v.name).apply()

    /**
     * Product lookup in a public product database (Open Food Facts), only with Hybrid or Automatic. Off unless the
     * operator turns it on; sends only product words (see ProductLookupClient).
     */
    var onlineProductLookup: Boolean
        get() = prefs.getBoolean("online_product_lookup", false)
        set(v) = prefs.edit().putBoolean("online_product_lookup", v).apply()

    /** When the knowledge pack was last looked for online (millis), for the weekly automatic check. */
    var knowledgeCheckedAt: Long
        get() = prefs.getLong("knowledge_checked_at", 0L)
        set(v) = prefs.edit().putLong("knowledge_checked_at", v).apply()

    /** The app version that last re-read the saved documents (see ReadingChecker). */
    var readingCheckVersion: String
        get() = prefs.getString("reading_check_version", "") ?: ""
        set(v) = prefs.edit().putString("reading_check_version", v).apply()

    var aiMode: AiMode
        get() = AiMode.entries.firstOrNull { it.name == prefs.getString("ai_mode", null) } ?: AiMode.WHEN_NEEDED
        set(v) = prefs.edit().putString("ai_mode", v.name).apply()

    /** Language of the reports sent to others (the boss may read Italian while the app is in English). */
    var reportLanguage: Language
        get() = readLanguage(prefs.getString("report_language", null)).takeIf { it != Language.SYSTEM } ?: Language.ITALIAN
        set(v) = prefs.edit().putString("report_language", v.name).apply()

    /** Finds what was learned about a supplier's documents (set by the app container; see LearningStore). */
    @Volatile var layoutLookup: ((String) -> com.kitchenreceipts.core.SupplierLayout?)? = null

    fun parseOptions() = ParseOptions(ownBusinessName.ifBlank { null }, ownVatNumber.ifBlank { null }, layoutLookup = layoutLookup, today = java.time.LocalDate.now())

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
