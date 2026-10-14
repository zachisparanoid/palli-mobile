package com.palli.mobile.di

import android.content.Context
import coil.ImageLoader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MediaModule {
    /**
     * Coil on the app's client, so image requests to PaLLi carry the token (AuthInterceptor) in the header and nowhere
     * else. Her files never change under an id, so the disk cache is used whatever headers the server sends: a photo
     * seen once still shows offline.
     */
    @Provides @Singleton
    fun imageLoader(@ApplicationContext context: Context, client: OkHttpClient): ImageLoader =
        ImageLoader.Builder(context).okHttpClient(client).respectCacheHeaders(false).crossfade(true).build()
}
