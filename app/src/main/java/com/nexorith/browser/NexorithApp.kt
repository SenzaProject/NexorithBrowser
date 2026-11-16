package com.nexorith.browser

import android.app.Application
import com.nexorith.browser.di.AppContainer

class NexorithApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer()
    }
}
