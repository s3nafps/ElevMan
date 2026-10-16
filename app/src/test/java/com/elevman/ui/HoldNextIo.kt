package com.elevman.ui

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * A stand-in for `Dispatchers.IO` that can keep one piece of work waiting. After [holdNext], the next
 * block handed to it is set aside until [releaseHeld]; every other block runs on `Dispatchers.IO` as
 * usual. It lets a test decide exactly when a background step (a delete, a save) finishes.
 */
internal class HoldNextIo : CoroutineDispatcher() {
    private val holding = AtomicBoolean(false)
    private val held = AtomicReference<Runnable?>(null)

    fun holdNext() = holding.set(true)

    /** Waits until a block has been set aside. */
    fun awaitHeld() {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (held.get() == null && System.nanoTime() < deadline) Thread.sleep(5)
        check(held.get() != null) { "nothing was held within 10 seconds" }
    }

    fun releaseHeld() {
        held.getAndSet(null)?.let { Dispatchers.IO.dispatch(EmptyCoroutineContext, it) }
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (holding.compareAndSet(true, false)) held.set(block) else Dispatchers.IO.dispatch(context, block)
    }
}
