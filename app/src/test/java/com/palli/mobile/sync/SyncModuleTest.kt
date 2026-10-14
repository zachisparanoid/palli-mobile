package com.palli.mobile.sync

import com.palli.mobile.di.SyncModule
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.ContinuationInterceptor

/**
 * The app scope must never hide an error. A launched coroutine that fails with no CoroutineExceptionHandler in its
 * context goes to the thread's uncaught-exception handler, which is what crashes the app, so the whole guarantee is that
 * no handler is installed. (The test does not throw an uncaught Error itself: kotlinx-coroutines-test records those
 * process-wide and fails whatever test runs next.)
 */
class SyncModuleTest {
    @Test fun `the app scope is a supervisor on the default dispatcher with no exception handler`() = runBlocking {
        val scope: CoroutineScope = SyncModule.appScope()
        try {
            assertNull(scope.coroutineContext[CoroutineExceptionHandler])
            assertSame(Dispatchers.Default, scope.coroutineContext[ContinuationInterceptor])

            // A supervisor: a child that fails, even with an Error, is reported to whoever awaits it as that same Error
            // (coroutines may hand back a stack-recovered copy of it), and neither cancels the scope nor its other children.
            val failed = scope.async<Unit> { throw AssertionError("an Error must not vanish") }
            val seen = runCatching { failed.await() }.exceptionOrNull()
            assertTrue(seen is AssertionError)
            assertEquals("an Error must not vanish", seen?.message)
            assertTrue(scope.coroutineContext[Job]!!.isActive)
            assertEquals(7, scope.async { 7 }.await())
        } finally {
            scope.cancel()
        }
    }
}
