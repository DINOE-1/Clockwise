package com.dinoe.clockwise.ui

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

enum class AppLanguage(val tag: String?) {
    SYSTEM(null),
    TRADITIONAL_CHINESE("zh-TW"),
    ENGLISH("en");

    companion object {
        fun fromTag(tag: String?): AppLanguage {
            if (tag == null) return SYSTEM
            return entries.firstOrNull { it.tag.equals(tag, ignoreCase = true) } ?: SYSTEM
        }
    }
}

private const val PREFERENCES_NAME = "language"
private const val LANGUAGE_KEY = "language_tag"

class LanguagePreferences(context: Context) {
    private val preferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    val language: AppLanguage
        get() = AppLanguage.fromTag(preferences.getString(LANGUAGE_KEY, null))

    fun setLanguage(language: AppLanguage) {
        preferences.edit().apply {
            if (language.tag == null) {
                remove(LANGUAGE_KEY)
            } else {
                putString(LANGUAGE_KEY, language.tag)
            }
        }.apply()
    }
}

fun Context.withAppLocale(): Context {
    val tag = LanguagePreferences(this).language.tag ?: return this
    val configuration = Configuration(resources.configuration)
    configuration.setLocale(Locale.forLanguageTag(tag))
    return createConfigurationContext(configuration)
}
