package com.palli.mobile.ui.media

import coil.ImageLoader
import coil.annotation.ExperimentalCoilApi
import com.palli.mobile.domain.CachedFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything of hers the media code keeps: the files [FileCache] fetched, and Coil's memory and disk caches for her
 * pictures. The primary constructor takes the files' clearing as a call, so a test can make it fail; the `@Inject` one
 * is what Hilt uses.
 */
@OptIn(ExperimentalCoilApi::class)           // ImageLoader.diskCache and memoryCache are still marked experimental
@Singleton
class MediaCaches(
    private val clearFiles: () -> Unit,
    private val images: ImageLoader,
) : CachedFiles {
    @Inject constructor(files: FileCache, images: ImageLoader) : this(files::clear, images)

    /** Every cache is tried even if an earlier one fails; the first failure is then thrown, with the rest attached to it. */
    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            val failures = listOf<() -> Unit>(clearFiles, { images.memoryCache?.clear() }, { images.diskCache?.clear() })
                .mapNotNull { step -> try { step(); null } catch (e: Exception) { e } }
            failures.firstOrNull()?.let { first ->
                failures.drop(1).forEach(first::addSuppressed)
                throw first
            }
        }
    }
}
