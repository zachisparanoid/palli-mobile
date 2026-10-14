package com.palli.mobile.di

import com.palli.mobile.domain.CachedFiles
import com.palli.mobile.domain.PairingRepository
import com.palli.mobile.domain.Unpairer
import com.palli.mobile.ui.media.MediaCaches
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds abstract fun unpairer(impl: PairingRepository): Unpairer
    @Binds abstract fun cachedFiles(impl: MediaCaches): CachedFiles
}
