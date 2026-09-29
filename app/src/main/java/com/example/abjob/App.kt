package com.example.abjob

import android.app.Application

class App : Application() {
    override fun onCreate() { super.onCreate(); AppGraph.initialize(this) }
}
