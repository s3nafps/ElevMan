package com.elevman.ui.viewer

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The rule the viewer uses to survive a bad PDF: a failed read or draw becomes a value. */
class AttemptTest {

    @Test
    fun aResultComesBackAsDone() = runBlocking {
        assertEquals(Attempt.Done(42), attempt { 42 })
    }

    @Test
    fun anIoExceptionBecomesFailed() = runBlocking {
        assertEquals(Attempt.Failed, attempt<Int> { throw IOException("unreadable") })
    }

    @Test
    fun aRuntimeExceptionBecomesFailed() = runBlocking {
        // What a damaged PDF can do inside the PDF library or the renderer.
        assertEquals(Attempt.Failed, attempt<Int> { throw IllegalStateException("bad page") })
        assertEquals(Attempt.Failed, attempt<Int> { throw NullPointerException() })
        assertEquals(Attempt.Failed, attempt<Int> { throw PageDrawException("no memory", OutOfMemoryError()) })
    }

    @Test
    fun cancellationIsRethrownNotTurnedIntoFailed() = runBlocking {
        try {
            attempt<Int> { throw CancellationException("screen left") }
            fail("the cancellation was swallowed")
        } catch (expected: CancellationException) {
            assertEquals("screen left", expected.message)
        }
    }

    @Test
    fun aCancelledCallerStopsInsteadOfReportingFailed() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            attempt<Int> {
                started.complete(Unit)
                CompletableDeferred<Int>().await()
            }
        }
        started.await()

        result.cancel()

        assertTrue(result.isCancelled)
        try {
            result.await()
            fail("a cancelled attempt must not produce a value")
        } catch (expected: CancellationException) {
            // The caller sees its own cancellation, not a Failed.
        }
    }

    @Test
    fun anErrorIsNotCaught() = runBlocking {
        // Only the one spot that allocates the bitmap turns an OutOfMemoryError into an exception.
        try {
            attempt<Int> { throw OutOfMemoryError("heap") }
            fail("an Error must reach the caller")
        } catch (expected: OutOfMemoryError) {
            assertEquals("heap", expected.message)
        }
    }
}
