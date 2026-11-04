package com.example.assistant

import android.content.Context
import android.content.SharedPreferences

/** Все настройки приложения в одном месте. Значения по умолчанию: «чистый» минималистичный вид. */
object AppSettings {

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences("harmonia_settings", Context.MODE_PRIVATE)
    }

    private fun ready() = ::sp.isInitialized

    // --- Основные ---
    /** Свечение за обложкой, реагирующее на ритм и настроение. */
    var auraEnabled: Boolean
        get() = !ready() || sp.getBoolean("aura", true)
        set(v) = sp.edit().putBoolean("aura", v).apply()

    /** Яркость свечения, 30..100 %. */
    var auraBrightness: Int
        get() = if (ready()) sp.getInt("aura_brightness", 100).coerceIn(30, 100) else 100
        set(v) = sp.edit().putInt("aura_brightness", v.coerceIn(30, 100)).apply()

    /** Размер обложки: 0 маленькая, 1 средняя, 2 большая. */
    var coverSize: Int
        get() = if (ready()) sp.getInt("cover_size", 1).coerceIn(0, 2) else 1
        set(v) = sp.edit().putInt("cover_size", v.coerceIn(0, 2)).apply()

    /** Размер кнопок управления: 0 маленькие, 1 обычные, 2 крупные. */
    var buttonSize: Int
        get() = if (ready()) sp.getInt("button_size", 0).coerceIn(0, 2) else 0
        set(v) = sp.edit().putInt("button_size", v.coerceIn(0, 2)).apply()

    /** Размер текста названия: 0 мелкий, 1 обычный, 2 крупный. */
    var textSize: Int
        get() = if (ready()) sp.getInt("text_size", 1).coerceIn(0, 2) else 1
        set(v) = sp.edit().putInt("text_size", v.coerceIn(0, 2)).apply()

    var showSeek: Boolean
        get() = !ready() || sp.getBoolean("show_seek", true)
        set(v) = sp.edit().putBoolean("show_seek", v).apply()

    var showRating: Boolean
        get() = !ready() || sp.getBoolean("show_rating", true)
        set(v) = sp.edit().putBoolean("show_rating", v).apply()

    // --- Продвинутые ---
    /** Отладочный вид плеера: спектр, карта настроения, строка характеристик, вперемешку/повтор. */
    var debugView: Boolean
        get() = ready() && sp.getBoolean("debug_view", false)
        set(v) = sp.edit().putBoolean("debug_view", v).apply()

    /** Частота кадров фона: 20, 30 или 60. */
    var fps: Int
        get() = if (ready()) sp.getInt("fps", 30).let { if (it in setOf(20, 30, 60)) it else 30 } else 30
        set(v) = sp.edit().putInt("fps", v).apply()

    /** Чувствительность к ритму, 50..150 %. */
    var beatSensitivity: Int
        get() = if (ready()) sp.getInt("beat_sens", 100).coerceIn(50, 150) else 100
        set(v) = sp.edit().putInt("beat_sens", v.coerceIn(50, 150)).apply()

    /** Папки, которые приложение не показывает и не сканирует. */
    var disabledFolders: Set<String>
        get() = if (ready()) sp.getStringSet("disabled_folders", emptySet()).orEmpty().toSet() else emptySet()
        set(v) = sp.edit().putStringSet("disabled_folders", v).apply()

    fun isFolderEnabled(dir: String) = dir !in disabledFolders

    /** Путь файла лежит в выключенной папке? */
    fun isPathHidden(path: String): Boolean {
        if (path.isEmpty()) return false
        val off = disabledFolders
        if (off.isEmpty()) return false
        val dir = path.substringBeforeLast('/', "")
        return dir in off
    }

    fun resetAll() { if (ready()) sp.edit().clear().apply() }

    val coverFactor get() = floatArrayOf(0.45f, 0.6f, 0.8f)[coverSize]
    val buttonScale get() = floatArrayOf(0.85f, 1.0f, 1.2f)[buttonSize]
    val titleSp get() = floatArrayOf(18f, 22f, 26f)[textSize]
}
