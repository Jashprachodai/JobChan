package com.example.jobchan

import android.app.Application

class App : Application() {
    override fun onCreate() { super.onCreate(); AppGraph.initialize(this) }
}
