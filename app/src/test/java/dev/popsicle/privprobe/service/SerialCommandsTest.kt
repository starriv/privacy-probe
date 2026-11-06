package dev.popsicle.privprobe.service

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SerialCommandsTest {
    @Test fun rapidRestartWaitsForCleanupEvenWhenServiceWaiterIsCancelled() = runTest {
        val commands = SerialCommands(backgroundScope)
        val order = mutableListOf<String>()
        commands.submit { order += "start" }
        val stop = commands.submit { delay(1000); order += "stop" }
        val waiter = launch { stop.join() }
        runCurrent()
        waiter.cancelAndJoin()
        val restart = commands.submit { order += "restart" }
        runCurrent()
        assertEquals(listOf("start"), order)
        advanceTimeBy(1001); runCurrent()
        restart.join()
        assertEquals(listOf("start", "stop", "restart"), order)
    }
}
