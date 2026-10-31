package com.example.assistant

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import org.json.JSONArray

/** Числовая настройка: ключ, значение по умолчанию, границы. Любое значение приводится к допустимому. */
data class IntSpec(val key: String, val default: Int, val min: Int, val max: Int) {
    fun clamp(value: Int): Int = value.coerceIn(min, max)
}

/**
 * Единый источник настроек. Ключи и значения по умолчанию совпадают с теми, что уже использует экран настроек
 * (файл `assistant_prefs`), поэтому сохранённые настройки не теряются.
 */
object AppSettings {

    const val PREFS = "assistant_prefs"

    val REQUEST_TIMEOUT_S = IntSpec("request_timeout_seconds", 45, 15, 120)
    val CONNECT_TIMEOUT_S = IntSpec("connect_timeout_seconds", 30, 5, 60)
    val RETRY_WAIT_S = IntSpec("retry_wait_seconds", 20, 0, 30)
    val CHAT_TEXT_SP = IntSpec("chat_text_size", 16, 13, 23)
    /** Масштаб текста всего приложения, в процентах. */
    val FONT_SCALE = IntSpec("ui_font_scale", 100, 80, 140)
    const val THEME = "app_theme" // system | light | dark

    /** Ключ переключателя команды. */
    fun featureKey(tool: String) = "feature_$tool"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun bool(context: Context, key: String, default: Boolean = true) = prefs(context).getBoolean(key, default)

    fun int(context: Context, spec: IntSpec): Int = spec.clamp(prefs(context).getInt(spec.key, spec.default))

    fun setInt(context: Context, spec: IntSpec, value: Int) {
        prefs(context).edit().putInt(spec.key, spec.clamp(value)).apply()
    }

    fun featureOn(context: Context, tool: String) = bool(context, featureKey(tool), true)

    /** Убирает из списка функций для модели те, что выключены в настройках (модель их не видит и не тратит на них токены). */
    fun filterTools(context: Context, tools: JSONArray): JSONArray {
        val p = prefs(context)
        val result = JSONArray()
        for (i in 0 until tools.length()) {
            val tool = tools.optJSONObject(i) ?: continue
            val name = tool.optJSONObject("function")?.optString("name").orEmpty()
            if (name.isEmpty() || p.getBoolean(featureKey(name), true)) result.put(tool)
        }
        return result
    }

    // ---------------------------------------------------------------- оформление

    fun theme(context: Context): String =
        prefs(context).getString(THEME, "system").let { if (it == "light" || it == "dark") it!! else "system" }

    /** Применяет сохранённую тему при запуске процесса. */
    fun applyTheme(context: Context) {
        AppCompatDelegate.setDefaultNightMode(
            when (theme(context)) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    /** Контекст с учётом масштаба текста: вызывать из attachBaseContext экранов. */
    fun wrap(base: Context): Context {
        val percent = int(base, FONT_SCALE)
        if (percent == 100) return base
        val config = Configuration(base.resources.configuration)
        config.fontScale = base.resources.configuration.fontScale * percent / 100f
        return base.createConfigurationContext(config)
    }

    /** Возвращает настройки экрана к значениям по умолчанию. Чаты, заметки, профиль и разрешения не трогаются. */
    fun resetAll(context: Context) {
        val p = prefs(context)
        val editor = p.edit()
        listOf(
            REQUEST_TIMEOUT_S, CONNECT_TIMEOUT_S, RETRY_WAIT_S, CHAT_TEXT_SP, FONT_SCALE
        ).forEach { editor.remove(it.key) }
        editor.remove(THEME)
        p.all.keys.filter { it.startsWith("feature_") }.forEach { editor.remove(it) }
        editor.apply()
        applyTheme(context)
    }
}

/** Приложение: применяет сохранённую тему при запуске процесса (из чата, службы, шторки или голосового экрана). */
class AssistantApp : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        AppSettings.applyTheme(this)
        Shell.backend = ShizukuShell
    }
}
