package com.palli.mobile.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether sending is refused because PaLLi reports another api version than this app speaks. One flag for the whole
 * process: the chat sets it from each `/me` answer, and every way of sending reads it (the composer, the attach menu,
 * the camera, the microphone, and the Reply typed into a notification).
 *
 * It holds only what has been heard since the process started: until a `/me` has answered, nothing is blocked.
 */
@Singleton
class SendGate @Inject constructor() {
    private val _blocked = MutableStateFlow(false)
    val blocked: StateFlow<Boolean> = _blocked.asStateFlow()

    fun set(blocked: Boolean) { _blocked.value = blocked }
}
