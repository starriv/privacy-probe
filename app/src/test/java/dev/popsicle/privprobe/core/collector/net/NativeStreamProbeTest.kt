package dev.popsicle.privprobe.core.collector.net

import android.app.Application
import dev.popsicle.privprobe.core.data.AppSettings
import dev.popsicle.privprobe.core.data.ProbeId
import dev.popsicle.privprobe.core.model.PrivacyEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NativeStreamProbeTest {
    // Feed the actual NDJSON parser without launching su or changing the device.
    private fun probe(scope: CoroutineScope, events: MutableList<PrivacyEvent>): NativeStreamProbe {
        val p = NativeStreamProbe(RuntimeEnvironment.getApplication(), { AppSettings() }, { events.add(it) })
        NativeStreamProbe::class.java.getDeclaredField("stopped").apply { isAccessible = true }.set(p, false)
        NativeStreamProbe::class.java.getDeclaredField("probeScope").apply { isAccessible = true }.set(p, scope)
        return p
    }
    private fun feed(p: NativeStreamProbe, line: String) {
        NativeStreamProbe::class.java.getDeclaredMethod("handleLine", String::class.java).apply { isAccessible = true }.invoke(p, line)
    }
    @Test fun gaiOnlySuppressesSameUidDnsAndPreservesUnknownAttribution() = runTest {
        val events = mutableListOf<PrivacyEvent>()
        val p = probe(this, events)
        for (uid in listOf(12000, 12001, -1)) feed(p, """{"t":"dns_q","uid":$uid,"name":"shared.example"}""")
        feed(p, """{"t":"gai","uid":12000,"name":"shared.example"}""")
        advanceTimeBy(1100); runCurrent()
        assertEquals(setOf(12000, 12001, -1), events.map { it.uid }.toSet())
        assertEquals(1, events.count { it.uid == 12000 })
        assertEquals("getaddrinfo", events.single { it.uid == 12000 }.subType)
        // Also exercise packet-after-gai ordering.
        feed(p, """{"t":"dns_q","uid":12001,"name":"shared.example"}""")
        advanceTimeBy(1100); runCurrent()
        assertEquals(2, events.count { it.uid == 12001 })
    }
    @Test fun subsystemFailureDoesNotBecomeHealthyBecauseAnotherSubsystemRuns() = runTest {
        val p = probe(this, mutableListOf())
        feed(p, """{"t":"status","feature":"pcap","ok":true,"msg":"ready"}""")
        feed(p, """{"t":"status","feature":"uprobe","ok":false,"msg":"failed"}""")
        feed(p, """{"t":"hello","pid":123,"features":["pcap","uprobe"]}""")
        assertTrue(p.featureStatuses.value.getValue(ProbeId.PCAP).healthy)
        assertFalse(p.featureStatuses.value.getValue(ProbeId.DNS_TRACE).healthy)
    }
}
