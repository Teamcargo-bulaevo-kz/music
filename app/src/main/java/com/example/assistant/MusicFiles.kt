package com.example.assistant

import android.content.Context
import java.io.File

/**
 * Папка приложения для таблицы «Музыка.xlsx» и файлов для другого ИИ. Лежит в личной папке приложения:
 * доступ ко всем файлам телефона не нужен. Наружу файлы уходят кнопкой «Поделиться».
 */
object MusicFiles {
    private const val ROOT_NAME = "Музыка"
    private const val OUTBOX_NAME = "Для ИИ"

    @Volatile private var base: File? = null

    /** Вызывается один раз при старте процесса ([MusicApp]). */
    fun init(context: Context) {
        base = context.applicationContext.let { it.getExternalFilesDir(null) ?: it.filesDir }
    }

    fun root(): File = File(checkNotNull(base) { "MusicFiles.init не вызван" }, ROOT_NAME)

    fun outbox(): File = File(root(), OUTBOX_NAME)

    /** Создаёт папки. false, если не получилось. */
    fun ensure(): Boolean = try {
        outbox().mkdirs()
        root().isDirectory && outbox().isDirectory
    } catch (e: Exception) {
        false
    }
}
