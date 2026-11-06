package dev.popsicle.privprobe.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Service 生命周期之外持有命令；等待者取消不会取消已排队的资源清理。 */
internal class SerialCommands(private val scope: CoroutineScope) {
    private var tail: Job? = null

    @Synchronized
    fun submit(action: suspend () -> Unit): Job {
        val previous = tail
        return scope.launch {
            previous?.join()
            action()
        }.also { tail = it }
    }
}
