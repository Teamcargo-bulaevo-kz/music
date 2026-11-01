package com.example.assistant

import android.app.Application

class MusicApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MusicFiles.init(this)
    }
}
