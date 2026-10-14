package com.palli.mobile.ui.chat

import com.palli.mobile.data.remote.MeResponse
import com.palli.mobile.data.remote.Wire
import com.palli.mobile.sync.ConnState

object DoorBanner {
    fun text(me: MeResponse): String? = when {
        me.apiVersion != Wire.API_VERSION ->
            "This app and PaLLi speak different versions (${Wire.API_VERSION} and ${me.apiVersion}). Update the older one."
        me.doorState == "no_brain" -> "PaLLi has no brain configured yet. Your messages will wait here until she does."
        me.doorState == "closed" -> "PaLLi's phone door is shut right now. Your messages will wait here."
        else -> null
    }

    fun connection(state: ConnState): String = when (state) {
        ConnState.CONNECTED -> "connected"
        ConnState.CONNECTING -> "connecting…"
        ConnState.WAITING -> "offline — retrying"
        ConnState.IDLE -> "not connected"
    }
}
