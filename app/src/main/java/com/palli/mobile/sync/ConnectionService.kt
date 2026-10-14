package com.palli.mobile.sync

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Holds the connection while the app is closed. Type specialUse: a persistent link to a self-hosted server. */
@AndroidEntryPoint
class ConnectionService : Service() {
    @Inject lateinit var stream: StreamSession
    @Inject lateinit var notifier: Notifier

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = stream.kick()
    }

    override fun onCreate() {
        super.onCreate()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, Notifier.ID_CONNECTION, notifier.connectionNotification(), type)
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
        stream.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // start() is idempotent: a no-op while the loop runs, and it brings back a loop that ended (no session at the
        // time, say) whenever the service is started again.
        stream.start()
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        stream.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        /** False when Android refuses a start from the background; the catch-up worker covers that gap. */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java))
            true
        } catch (e: Exception) {
            Log.w("PalliConnection", "the connection could not start now: ${e.message}")
            false
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionService::class.java))
        }
    }
}
