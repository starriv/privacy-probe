package dev.popsicle.privprobe.core.data.impl

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import dev.popsicle.privprobe.core.data.AppSettings
import dev.popsicle.privprobe.core.data.SettingsStore
import dev.popsicle.privprobe.core.model.EventSource
import dev.popsicle.privprobe.core.model.PrivacyCategory
import dev.popsicle.privprobe.core.model.PrivacyEvent
import java.time.ZonedDateTime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

class TestSettings : SettingsStore {
    override val state = MutableStateFlow(AppSettings())
    override fun snapshot() = state.value
    override fun update(transform: (AppSettings) -> AppSettings) { state.value = transform(state.value) }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SqlitePrivacyRepositoryTest {
    private lateinit var repo: SqlitePrivacyRepository
    private val settings = TestSettings()
    private val noon get() = ZonedDateTime.now().withHour(12).withMinute(0).withSecond(0).withNano(0).toInstant().toEpochMilli()
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun setUp() {
        context.deleteDatabase("privprobe.db")
        repo = SqlitePrivacyRepository(context, settings)
    }
    @After fun tearDown() { repo.close(); context.deleteDatabase("privprobe.db") }
    private fun event(i: Int, uid: Int = 12000, ts: Long = noon + i * 6000L) = PrivacyEvent(
        ts = ts, category = PrivacyCategory.NETWORK, subType = "dns_q", uid = uid,
        target = "shared.example", source = EventSource.PACKET,
    )
    private fun db() = SQLiteDatabase.openDatabase(context.getDatabasePath("privprobe.db").path, null, SQLiteDatabase.OPEN_READWRITE)

    @Test fun mixedSystemRowsHaveIdenticalTotalsChartsAndTargets() = runBlocking {
        repo.insertEvents((0..9).map { event(it, if (it < 3) 12000 else 1000) })
        val dashboard = repo.observeDashboard().first()
        val category = repo.observeCategory(PrivacyCategory.NETWORK).first()
        assertEquals(10L, dashboard.totalToday)
        assertEquals(10L, dashboard.categories.first { it.category == PrivacyCategory.NETWORK }.countToday)
        assertEquals(10L, category.countToday)
        assertEquals(10, category.todayHourly.sum())
        assertEquals(1, category.targetCount)
        assertEquals(1, category.targets.size)
        assertEquals(10L, category.targets.single().count)
        assertEquals(2, category.targets.single().callerCount)
        assertEquals(10L, repo.todayEventCount())
        settings.update { it.copy(showSystemApps = false) }
        assertEquals(3L, repo.observeDashboard().first().totalToday)
        assertEquals(3, repo.observeCategory(PrivacyCategory.NETWORK).first().todayHourly.sum())
        assertEquals(3L, repo.todayEventCount())
    }

    @Test fun failedTransactionRollsBackAndThrowsSoRetryCanSucceed() = runBlocking {
        repo.totalEventCount()
        db().use { it.execSQL("CREATE TRIGGER fail_insert BEFORE INSERT ON events WHEN new.uid=12001 BEGIN SELECT RAISE(ABORT,'injected'); END") }
        val batch = listOf(event(0), event(1, 12001))
        assertTrue(runCatching { repo.insertEvents(batch) }.isFailure)
        assertEquals(0L, repo.totalEventCount())
        assertEquals(0L, repo.todayEventCount())
        db().use { it.execSQL("DROP TRIGGER fail_insert") }
        repo.insertEvents(batch)
        assertEquals(2L, repo.todayEventCount())
    }

    @Test fun timelineIncludesLowRankAndHistoricalCallersAndPagesEqualTimestamps() = runBlocking {
        val old = noon - 86_400_000
        repo.insertEvents((0..149).map { event(it, 12000 + it, old) })
        assertTrue(repo.observeCategory(PrivacyCategory.NETWORK).first().callers.isEmpty())
        val first = repo.timelineForCategory(PrivacyCategory.NETWORK, 120, 0)
        val second = repo.timelineForCategory(PrivacyCategory.NETWORK, 120, 120)
        assertEquals(120, first.size)
        assertEquals(30, second.size)
        assertEquals(150, (first + second).map { it.uid }.toSet().size)
    }

    @Test fun capacityTrimsEqualTimestampRowsAndRebuildsAggregates() = runBlocking {
        repo.insertEvents((0..29).map { event(it, 12000 + it, noon) })
        repo.enforceCapacity(rowLimit = 10)
        val kept = repo.totalEventCount()
        assertTrue(kept in 1..10)
        assertEquals(kept, repo.todayEventCount())
        assertEquals(kept.toInt(), repo.observeCategory(PrivacyCategory.NETWORK).first().todayHourly.sum())
    }

    @Test fun byteCapacityReclaimsPagesRatherThanRepeatedlyDiscardingEverything() = runBlocking {
        repo.insertEvents((0..599).map { event(it).copy(detail = "x".repeat(16000)) })
        repo.enforceCapacity(byteLimit = 512 * 1024)
        val count = repo.totalEventCount()
        assertTrue(count in 1..599)
        assertEquals(count, repo.todayEventCount())
        db().use { database ->
            fun pragma(name: String) = database.rawQuery("PRAGMA $name", null).use { it.moveToFirst(); it.getLong(0) }
            assertTrue((pragma("page_count") - pragma("freelist_count")) * pragma("page_size") <= 512 * 1024)
        }
        repo.enforceCapacity(byteLimit = 512 * 1024)
        assertEquals(count, repo.totalEventCount())
    }

    @Test fun v2MigrationPreservesEventsAndBuildsMixedAggregates() = runBlocking {
        repo.insertEvents(listOf(event(0), event(1, 1000)))
        repo.close()
        db().use {
            it.execSQL("DROP TRIGGER trg_events_ai")
            listOf("agg_cat", "agg_cat_uid", "agg_uid_target", "agg_target", "agg_hourly").forEach { table -> it.execSQL("DROP TABLE $table") }
            it.version = 2
        }
        repo = SqlitePrivacyRepository(context, settings)
        assertEquals(2L, repo.totalEventCount())
        assertEquals(2L, repo.observeCategory(PrivacyCategory.NETWORK).first().countToday)
    }

    @Test fun continuousWritesRefreshBeforeTheyBecomeIdle() = runBlocking {
        val observed = async {
            withTimeout(7000) { repo.observeDashboard().first { it.totalToday >= 2 } }
        }
        val writer = launch {
            repeat(20) { repo.insertEvents(listOf(event(it))); delay(300) }
        }
        observed.await()
        assertTrue("refresh must arrive during sustained writes", writer.isActive)
        writer.cancelAndJoin()
    }
}
