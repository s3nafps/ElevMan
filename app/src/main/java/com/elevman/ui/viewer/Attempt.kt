package com.elevman.ui.viewer

import kotlin.coroutines.cancellation.CancellationException

/** The outcome of [attempt]: the result, or the fact that it failed. */
internal sealed interface Attempt<out T> {
    data class Done<T>(val value: T) : Attempt<T>
    data object Failed : Attempt<Nothing>
}

/**
 * Runs [block] and turns any [Exception] it throws into [Attempt.Failed], so a damaged PDF shows a
 * message instead of crashing the app.
 *
 * A [CancellationException] is rethrown: it means the screen is gone, not that the read failed.
 * An [Error] such as [OutOfMemoryError] is not caught either.
 */
internal suspend fun <T> attempt(block: suspend () -> T): Attempt<T> =
    try {
        Attempt.Done(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Attempt.Failed
    }
