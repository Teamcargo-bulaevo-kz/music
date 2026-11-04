package com.example.assistant

import android.app.Application

class MusicApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MusicFiles.init(this)
        AppSettings.init(this)
        // Запоминаем причину падения, чтобы показать её при следующем запуске
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { java.io.File(filesDir, "last_crash.txt").writeText(android.util.Log.getStackTraceString(e).take(6000)) } catch (_: Throwable) {}
            old?.uncaughtException(t, e)
        }
    }
}
