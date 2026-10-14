package com.palli.mobile.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.work.WorkManager
import com.palli.mobile.BuildConfig
import com.palli.mobile.data.local.EventDao
import com.palli.mobile.data.local.PalliDatabase
import com.palli.mobile.data.local.PendingSendDao
import com.palli.mobile.data.remote.AuthInterceptor
import com.palli.mobile.data.session.KeystoreTokenCipher
import com.palli.mobile.data.session.ServerPrefs
import com.palli.mobile.data.session.SessionStore
import com.palli.mobile.data.session.TokenCipher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    /** A damaged file opens to the Pair screen, which says why, rather than crashing every start (see [ServerPrefs.store]). */
    @Provides @Singleton
    fun preferences(@ApplicationContext context: Context): DataStore<Preferences> =
        ServerPrefs.store { context.preferencesDataStoreFile("palli") }

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): PalliDatabase =
        Room.databaseBuilder(context, PalliDatabase::class.java, "palli.db").build()

    @Provides fun eventDao(db: PalliDatabase): EventDao = db.events()
    @Provides fun pendingSendDao(db: PalliDatabase): PendingSendDao = db.pendingSends()

    @Provides @Singleton fun tokenCipher(): TokenCipher = KeystoreTokenCipher()

    @Provides @Singleton
    fun workManager(@ApplicationContext context: Context): WorkManager = WorkManager.getInstance(context)

    @Provides @Singleton
    fun okHttp(sessions: SessionStore): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor { sessions.current })
        // BASIC logs the method, URL and status only — never headers, so the token never reaches logcat.
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
        })
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)          // a 20 MB upload on a slow link
        .build()
}
