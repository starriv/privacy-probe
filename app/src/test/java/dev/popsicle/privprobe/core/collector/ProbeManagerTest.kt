package dev.popsicle.privprobe.core.collector

import android.app.Application
import dev.popsicle.privprobe.core.data.*
import dev.popsicle.privprobe.core.data.impl.*
import dev.popsicle.privprobe.core.model.*
import dev.popsicle.privprobe.core.root.RootShell
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProbeManagerTest {
    private val settings = AppSettings(probes = ProbeId.entries.associateWith { it == ProbeId.PCAP })
    @Test fun stopWaitsForProducerCleanupAndFlushRetry() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("privprobe.db")
        val repo = SqlitePrivacyRepository(context, TestSettings())
        val attempts = AtomicInteger()
        val retryRepo = object : PrivacyRepository by repo {
            override suspend fun insertEvents(events: List<PrivacyEvent>) {
                if (attempts.incrementAndGet() == 1) error("transient write failure")
                repo.insertEvents(events)
            }
        }
        val manager = ProbeManager(RootShell, retryRepo)
        val ready = CompletableDeferred<Unit>()
        var cleaned = false
        manager.externalProbeFactory = {
            listOf(object : Probe {
                override val id = ProbeId.PCAP
                override suspend fun run(sink: ProbeSink) {
                    try { ready.complete(Unit); awaitCancellation() }
                    finally {
                        withContext(NonCancellable) {
                            delay(100)
                            sink.emit(PrivacyEvent(System.currentTimeMillis(), PrivacyCategory.NETWORK,
                                "dns_q", 12000, target = "test.example", source = EventSource.PACKET))
                            cleaned = true
                        }
                    }
                }
            })
        }
        try {
            manager.start(settings)
            withTimeout(5000) { ready.await() }
            withTimeout(5000) { manager.stop() }
            assertTrue(cleaned)
            assertEquals(2, attempts.get())
            assertEquals(1L, repo.totalEventCount())
            assertEquals(0, manager.runningProbeCount())
        } finally { manager.stop(); repo.close() }
    }
}
