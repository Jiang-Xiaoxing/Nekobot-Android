package com.nekobot.app.data.local

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * 语言切换辅助工具。
 *
 * 支持六种模式：跟随系统（system）、简体中文（zh）、繁体中文（zh-TW）、
 * 英语（en）、日语（ja）、韩语（ko）。
 * - API 26+：通过 [Context.createConfigurationContext] 包装上下文，使资源按选定语言加载。
 * - 跟随系统时，若系统语言在已支持列表内则使用对应语言，否则回落到英语。
 */
object LocaleHelper {

    /** 根据偏好返回实际生效的 [Locale]。 */
    fun getEffectiveLocale(context: Context, languageTag: String): Locale {
        return when (languageTag) {
            PrefsManager.LANGUAGE_ZH -> Locale.SIMPLIFIED_CHINESE
            PrefsManager.LANGUAGE_ZH_TW -> Locale.TRADITIONAL_CHINESE
            PrefsManager.LANGUAGE_EN -> Locale.ENGLISH
            PrefsManager.LANGUAGE_JA -> Locale.JAPANESE
            PrefsManager.LANGUAGE_KO -> Locale.KOREAN
            else -> {
                // 跟随系统：系统语言若在已支持列表内则使用对应语言，否则回落到英语
                val sys = systemLocale(context)
                when (sys.language.lowercase(Locale.ROOT)) {
                    "zh" -> if (isTraditionalLocale(sys)) Locale.TRADITIONAL_CHINESE else Locale.SIMPLIFIED_CHINESE
                    "ja" -> Locale.JAPANESE
                    "ko" -> Locale.KOREAN
                    else -> Locale.ENGLISH
                }
            }
        }
    }

    /**
     * 根据偏好返回生效的语言代码（zh / zh-TW / en / ja / ko）。
     *
     * 与 [getEffectiveLocale] 一致，但保留繁体中文的区分（繁体统一返回 zh-TW），
     * 供 AI 输出语言、字卡生成等需要语言代码的场景使用。
     */
    fun getEffectiveLanguageTag(context: Context, languageTag: String): String {
        return when (languageTag) {
            PrefsManager.LANGUAGE_ZH -> PrefsManager.LANGUAGE_ZH
            PrefsManager.LANGUAGE_ZH_TW -> PrefsManager.LANGUAGE_ZH_TW
            PrefsManager.LANGUAGE_EN -> PrefsManager.LANGUAGE_EN
            PrefsManager.LANGUAGE_JA -> PrefsManager.LANGUAGE_JA
            PrefsManager.LANGUAGE_KO -> PrefsManager.LANGUAGE_KO
            else -> {
                val sys = systemLocale(context)
                when (sys.language.lowercase(Locale.ROOT)) {
                    "zh" -> if (isTraditionalLocale(sys)) PrefsManager.LANGUAGE_ZH_TW else PrefsManager.LANGUAGE_ZH
                    "ja" -> PrefsManager.LANGUAGE_JA
                    "ko" -> PrefsManager.LANGUAGE_KO
                    else -> PrefsManager.LANGUAGE_EN
                }
            }
        }
    }

    /** 语言代码是否属于繁体中文（zh-TW / zh-HK / zh-MO / zh-Hant 等）。 */
    fun isTraditionalChinese(languageTag: String): Boolean {
        val lower = languageTag.trim().lowercase(Locale.ROOT).replace('_', '-')
        if (!lower.startsWith("zh")) return false
        if (lower.contains("hant")) return true
        return lower.split('-').drop(1).any { it == "tw" || it == "hk" || it == "mo" }
    }

    /** 系统当前语言。 */
    private fun systemLocale(context: Context): Locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        context.resources.configuration.locales[0]
    } else {
        @Suppress("DEPRECATION")
        context.resources.configuration.locale
    }

    /**
     * 系统语言是否为繁体中文。
     *
     * 繁体统一落到 [Locale.TRADITIONAL_CHINESE]（zh-TW），因为资源目录只有
     * `values-zh-rTW`；港台澳门三地共用同一份繁体文案。
     */
    private fun isTraditionalLocale(locale: Locale): Boolean {
        if (locale.script.equals("Hant", ignoreCase = true)) return true
        if (locale.script.equals("Hans", ignoreCase = true)) return false
        return when (locale.country.uppercase(Locale.ROOT)) {
            "TW", "HK", "MO" -> true
            else -> false
        }
    }

    /** 用选定的 [Locale] 包装上下文，返回带新配置的 [Context]。 */
    fun wrap(context: Context, locale: Locale): Context {
        val config = Configuration(context.resources.configuration)
        Locale.setDefault(locale)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(locale))
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }
        return context.createConfigurationContext(config)
    }

    /** 便捷方法：读取 PrefsManager 中的语言偏好并包装上下文。 */
    fun wrap(context: Context): Context {
        val prefs = PrefsManager(context.applicationContext)
        val locale = getEffectiveLocale(context, prefs.language)
        return wrap(context, locale)
    }
}
