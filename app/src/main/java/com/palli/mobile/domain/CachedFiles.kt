package com.palli.mobile.domain

/**
 * Her files kept on this phone only so they show or play again without the network: the fetched files and the image
 * caches. They belong to the pairing, so [clear] runs whenever it ends. It throws if something could not be cleared,
 * after it has tried everything.
 */
fun interface CachedFiles {
    suspend fun clear()
}
