package com.palli.mobile.domain

/**
 * What PaLLi takes in one send (`bots/mobile/intake.py`): each part at most 20 MB, the whole send at most 60 MB.
 * Past either she answers 413, and past 60 MB she may answer before reading the body, which reaches the phone as a
 * broken pipe rather than a 413. So the phone refuses such a send itself, before it copies or uploads anything.
 */
object SendLimits {
    const val MAX_PART_BYTES = 20L * 1024 * 1024
    const val MAX_SEND_BYTES = 60L * 1024 * 1024

    /** Whether parts of these sizes can go in one send. */
    fun fits(sizes: List<Long>): Boolean = sizes.all { it <= MAX_PART_BYTES } && sizes.sum() <= MAX_SEND_BYTES
}
