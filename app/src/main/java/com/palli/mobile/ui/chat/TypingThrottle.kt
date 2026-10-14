package com.palli.mobile.ui.chat

/** You are typing → she waits (the backend's burst coalescer). One frame every few seconds is enough to say so. */
class TypingThrottle(private val intervalMs: Long) {
    private var last = Long.MIN_VALUE / 2

    fun shouldSend(nowMs: Long): Boolean {
        if (nowMs - last < intervalMs) return false
        last = nowMs
        return true
    }
}
