package com.palli.mobile.ui.battery

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/** Android's battery optimisation, which can hold back the connection her messages arrive on. */
object BatteryExemption {
    /** True when Android is limiting this app in the background. False when it is not, and when it cannot be told. */
    fun limited(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == false

    /**
     * Puts the system's own question, "Let PaLLi run in the background?". A messaging door that holds one connection open
     * is what this exemption exists for. A phone without that question gets the list of apps to find PaLLi in instead.
     * Returns false if the phone has neither.
     */
    @SuppressLint("BatteryLife")
    fun ask(context: Context): Boolean = listOf(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    ).any { start(context, it) }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    const val NO_SCREEN = "This phone has no screen for that. Look for PaLLi under Battery in the phone's settings."
}
