package com.palli.mobile.domain

/** Forget the pairing: the token, the timeline, the queue, and her cached files. `reason` is shown on the Pair screen (null when he chose it). */
interface Unpairer {
    suspend fun unpaired(reason: String?)
}
