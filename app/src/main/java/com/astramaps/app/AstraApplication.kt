package com.astramaps.app

import android.app.Application
import com.astramaps.app.di.AppContainer

/**
 * Astra Maps application.
 *
 * Dependency injection is manual — see [AppContainer]. The container is created
 * once here and exposed for the process lifetime.
 */
class AstraApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
