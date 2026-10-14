package com.palli.mobile.di

import com.palli.mobile.data.remote.ApiFactory
import com.palli.mobile.data.session.SessionStore
import com.palli.mobile.domain.SendRepository
import com.palli.mobile.domain.TimelineRepository
import com.palli.mobile.domain.Unpairer
import com.palli.mobile.sync.CatchUpProcessor
import com.palli.mobile.sync.NotifyingSink
import com.palli.mobile.sync.OkHttpSocketFactory
import com.palli.mobile.sync.SocketFactory
import com.palli.mobile.sync.StreamSession
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class AppScope

@Module
@InstallIn(SingletonComponent::class)
object SyncModule {
    /**
     * Deliberately no CoroutineExceptionHandler: an Error thrown on this scope must reach the thread's uncaught-exception
     * handler and crash visibly rather than vanish. The SupervisorJob only keeps one failed child from cancelling the rest.
     */
    @Provides @Singleton @AppScope
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides @Singleton
    fun sockets(client: OkHttpClient): SocketFactory = OkHttpSocketFactory(client)

    @Provides @Singleton
    fun stream(@AppScope scope: CoroutineScope, sessions: SessionStore, sockets: SocketFactory,
               timeline: TimelineRepository, sink: NotifyingSink, unpairer: Unpairer): StreamSession =
        StreamSession(scope, { sessions.current ?: sessions.load() }, sockets, timeline::insertNew, timeline::maxId,
            sink, unpairer)

    @Provides
    fun catchUp(sessions: SessionStore, apis: ApiFactory, timeline: TimelineRepository, sink: NotifyingSink,
                unpairer: Unpairer, sends: SendRepository): CatchUpProcessor =
        CatchUpProcessor({ sessions.current ?: sessions.load() }, apis, timeline::insertNew, timeline::maxId, sink, unpairer,
            expireUnrecorded = { sends.expireUnrecorded() })
}
