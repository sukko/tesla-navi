package dev.teslanavi.navigation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Brief stops can reuse the SDK; a new guide cancels teardown, including one awaiting initialization. */
internal class SdkIdleRelease(private val scope: CoroutineScope, private val delayMillis: Long = 30_000) {
    private var pending: Job? = null
    fun active() { pending?.cancel(); pending = null }
    fun idle(release: suspend () -> Unit) {
        active()
        pending = scope.launch { delay(delayMillis); release() }
    }
}
