package com.astramaps.app

import android.app.Application
import com.astramaps.app.di.AppContainer

/**
 * Astra Maps application.
 *
 * Dependency injection is manual — see [AppContainer]. The container is created
 * once here and exposed for the process lifetime.
 *
 * MapLibre is *not* initialised here on purpose: `MapLibre.getInstance` touches
 * the native library, and this Application is instantiated by Robolectric in
 * every JVM unit test. It is initialised in `MainActivity`, immediately before
 * the first `MapView` can exist.
 */
class AstraApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
