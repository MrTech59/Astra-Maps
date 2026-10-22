package com.astramaps.app.di

import android.content.Context
import androidx.room.Room
import com.astramaps.app.BuildConfig
import com.astramaps.app.data.local.AstraDatabase
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Manual dependency-injection container.
 *
 * Astra Maps deliberately avoids Hilt/Dagger: the graph is small, the build is
 * faster, and there is no annotation processor in the hot path.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    /**
     * HTTP client used for manifest, pack and (optional) Photon requests.
     *
     * The User-Agent is the one registered for OSMF tile policy and is sent on
     * every request, including the optional online geocoder calls.
     */
    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", BuildConfig.TILE_USER_AGENT)
                .build()
            chain.proceed(request)
        }
        .build()

    /**
     * On-device database. Holds installed-pack bookkeeping and, from Phase 2
     * onwards, the local POI/place/street search index.
     */
    val database: AstraDatabase = Room
        .databaseBuilder(appContext, AstraDatabase::class.java, AstraDatabase.NAME)
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()
}
