package dev.popsicle.privprobe.core.data.impl

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import dev.popsicle.privprobe.core.collector.CollectorRuntimeStatus
import dev.popsicle.privprobe.core.data.AppSettings
import dev.popsicle.privprobe.core.data.PrivacyRepository
import dev.popsicle.privprobe.core.data.SettingsStore
import dev.popsicle.privprobe.core.model.CallerDetailState
import dev.popsicle.privprobe.core.model.CallerSummary
import dev.popsicle.privprobe.core.model.CategoryDetailState
import dev.popsicle.privprobe.core.model.CategorySummary
import dev.popsicle.privprobe.core.model.DashboardState
import dev.popsicle.privprobe.core.model.EventSource
import dev.popsicle.privprobe.core.model.PrivacyCategory
import dev.popsicle.privprobe.core.model.PrivacyEvent
import dev.popsicle.privprobe.core.model.TargetDetailState
import dev.popsicle.privprobe.core.model.TargetSummary
import dev.popsicle.privprobe.core.model.TimelineCursor
import dev.popsicle.privprobe.core.model.TimelinePage
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * 裸 SQLite 实现的隐私事件仓储（不用 Room/KSP）。
 *
 * ## 写入
 * - 事务 + UPSERT + 逻辑 dedupe_key（见 [DedupeKey]）：同一行为多源上报只落一条，
 *   冲突时保留证据更强的来源（source_rank）。
 * - `events` 上的 AFTER INSERT 触发器同步维护**按天预聚合表**（agg_cat / agg_cat_uid /
 *   agg_uid_target / agg_target / agg_hourly），Dashboard / 大类页读聚合表而不是扫事件全表。
 *
 * ## 读取
 * - Dashboard ≤9 条小范围查询（旧实现约 36 条全表/全索引扫描，604k 行时 3.1s）。
 * - 时间线：`timelineForCategory` 按类别读取整个保留期并分页
 *   （旧实现是 12 路 observeCaller、54 条查询、约 13s）。
 * - 「今日」按设备本地时区 0 点；系统应用过滤在 SQL 里按 is_system 做。
 *
 * ## 刷新
 * - 全库共享一条 ticker（30s，UI 不可见时 WhileSubscribed 停表）+ 写入版本 sample 2s。
 *
 * ## 保留 / 容量
 * - 7 天保留语义不变；purge 后 incremental_vacuum + WAL checkpoint。
 * - 行数 / 字节硬上限（300k 行 / 150MB），超限删最旧数据。
 * - 批量删除前先摘掉聚合触发器，删完后只重建受影响的天，保证聚合与事件始终一致。
 *
 * ## 迁移（P1-6）
 * - 逐版本 ALTER TABLE / CREATE TABLE / 建索引 / backfill，不 DROP 用户数据。
 * - v4：聚合表按 (uid, package) 拆分调用者，清理相机探针自噪与自身/旧包名事件。
 */
class SqlitePrivacyRepository(
    context: Context,
    private val settings: SettingsStore,
    private val systemUidPredicate: (Int) -> Boolean = { it in 0..9999 },
) : PrivacyRepository {

    /** 采集器运行态注入点（由 AppGraph 接线），用于填充 DashboardState 的 root/running 字段。 */
    @Volatile
    var statusProvider: (() -> CollectorRuntimeStatus)? = null

    /** uid/包名 → 展示名解析（AppGraph 注入 UidResolver），事件 label 为空时兜底。 */
    @Volatile
    var displayNameResolver: ((Int, String?) -> String)? = null

    /** uid → 包名反查（AppGraph 注入 UidResolver）。NETWORK 等只有 uid 的事件靠它补齐包名。 */
    @Volatile
    var packageResolver: ((Int) -> String?)? = null

    /** target → kind 解析（域名 tracker/functional 等），UI 展示用。 */
    @Volatile
    var targetKindResolver: ((PrivacyCategory, String) -> String)? = null

    private val appContext = context.applicationContext
    private val dbFile: File = appContext.getDatabasePath(DB_NAME)

    /** 本 App 包名：自身产生的事件不是被审计对象，不写入历史。 */
    private val selfPackage: String = appContext.packageName
    private val version = MutableStateFlow(0L)
    private val insertCounter = AtomicLong(0)
    private val maintenanceLock = Any()

    @Volatile
    private var lastPurgeAt = 0L

    @Volatile
    private var lastWalCheckpointAt = 0L

    @Volatile
    private var lastVacuumAt = 0L

    @Volatile
    private var domainCountCacheAt = 0L
    private var domainCountCache = 0

    /**
     * 共享刷新信号：所有观察流共用一条 ticker，避免 N 个订阅者各跑一个 4s 定时器。
     * `WhileSubscribed(5s)`：最后一个订阅者离开 5s 后停表，后台不再空转查库。
     */
    private val refreshScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineName("repo-refresh"),
    )
    private val sharedTicker: Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(TICK_MS)
        }
    }.shareIn(refreshScope, SharingStarted.WhileSubscribed(TICK_STOP_MS), replay = 0)

    private val helper: SQLiteOpenHelper = object : SQLiteOpenHelper(
        appContext, DB_NAME, null, DB_VERSION,
    ) {
        override fun onConfigure(db: SQLiteDatabase) {
            super.onConfigure(db)
            runCatching { db.enableWriteAheadLogging() }
            // auto_vacuum 必须在建表前设置才对新建库生效；老库在 onOpen 里做一次性 VACUUM 转换。
            runCatching { db.execSQL("PRAGMA auto_vacuum = INCREMENTAL") }
            // WAL 自动 checkpoint 从默认 1000 页收紧，避免 -wal 长期停在 0.5~4MB。
            runCatching { db.execSQL("PRAGMA wal_autocheckpoint = $WAL_AUTOCHECKPOINT_PAGES") }
            runCatching { db.execSQL("PRAGMA synchronous = NORMAL") }
            runCatching { db.execSQL("PRAGMA busy_timeout = 3000") }
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(CREATE_EVENTS)
            createEventIndexes(db)
            createAggTables(db)
            createAggTriggers(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            migrate(db, oldVersion, newVersion)
        }

        override fun onOpen(db: SQLiteDatabase) {
            super.onOpen(db)
            runCatching { ensureIncrementalAutoVacuum(db) }
        }
    }

    init {
        runCatching { helper.setWriteAheadLoggingEnabled(true) }
    }

    // ------------------------------------------------------------------ 迁移

    /**
     * 逐版本迁移（P1-6）：任何一个版本都只做 ALTER TABLE / CREATE TABLE / CREATE INDEX /
     * backfill，不再 DROP TABLE；保留用户历史数据。未来加版本只追加 case 分支即可。
     */
    private fun migrate(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        var v = oldVersion
        while (v < newVersion) {
            when (v) {
                1 -> migrateV1ToV2(db)
                2 -> migrateV2ToV3(db)
                3 -> migrateV3ToV4(db)
                else -> Log.w(TAG, "no migration step for v$v; keeping data as-is")
            }
            v++
        }
    }

    /** v1 → v2：新增 source_rank（多源去重时按证据强度保序）+ 新索引，删除死索引。 */
    private fun migrateV1ToV2(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE events ADD COLUMN source_rank INTEGER NOT NULL DEFAULT 0")
        // 回填旧数据的证据强度，保证升级后的同键冲突仍能按来源优先级保序。
        db.execSQL(
            """
            UPDATE events SET source_rank = CASE source
                WHEN 'PACKET' THEN 60
                WHEN 'UPROBE' THEN 55
                WHEN 'KERNEL_TRACE' THEN 50
                WHEN 'DUMPSYS' THEN 40
                WHEN 'APPOPS' THEN 30
                WHEN 'LOGCAT' THEN 20
                WHEN 'SELF_TEST' THEN 10
                ELSE 0
            END
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_cat_uid_ts ON events(category, uid, ts)")
        // P2-7：day 维度的索引从未被查询使用（今日过滤统一走 ts），删掉减少写入放大。
        db.execSQL("DROP INDEX IF EXISTS idx_events_day_cat")
        Log.i(TAG, "migrated v1→v2: source_rank backfilled, indexes updated, data preserved")
    }

    /**
     * v2 → v3：建按天预聚合表 + AFTER INSERT 触发器。数据回填统一由 v3 → v4 完成，
     * 因为 v4 的聚合键包含 package，重复回填会白跑一遍。
     */
    private fun migrateV2ToV3(db: SQLiteDatabase) {
        createAggTables(db)
        createAggTriggers(db)
        createEventIndexes(db)
        Log.i(TAG, "migrated v2→v3: pre-aggregation tables created")
    }

    /**
     * v3 → v4：修正三类数据口径问题（聚合表是派生数据，重建不影响事件）：
     *
     * 1. 相机 kprobe `device_open` 的打开者是 cameraserver / 厂商相机 provider，本 App 的相机服务探针
     *    轮询本身就会触发这些打开（实测关闭探针 4 分钟 0 条），无法归因到用户 App，删除历史自噪；
     *    后续采集在 NativeStreamProbe 侧过滤系统进程的设备打开。
     * 2. 本 App 自身与旧包名（dev.popsicle.privradar）的事件不是被审计对象，从历史清理；后续写入跳过。
     * 3. agg_cat_uid / agg_uid_target 改为按 (uid, package) 拆分，共享 uid（典型 uid 1000）不再把
     *    多个系统应用合并成一行、再用最后一条事件的包名当标签。
     */
    private fun migrateV3ToV4(db: SQLiteDatabase) {
        dropAggTriggers(db)
        db.execSQL(
            "DELETE FROM events WHERE category IN ('camera','microphone') " +
                "AND sub_type='device_open' AND source='KERNEL_TRACE' AND is_system=1",
        )
        db.execSQL(
            "DELETE FROM events WHERE package IN (?,?)",
            arrayOf<Any>(selfPackage, LEGACY_SELF_PACKAGE),
        )
        dropAggTables(db)
        createAggTables(db)
        backfillAggregates(db)
        createAggTriggers(db)
        createEventIndexes(db)
        Log.i(TAG, "migrated v3→v4: callers split by package, probe self-noise purged")
    }

    /** 用 events 全量重建按天预聚合表（迁移使用；调用前需已摘掉触发器）。 */
    private fun backfillAggregates(db: SQLiteDatabase) {
        db.execSQL(
            "INSERT INTO agg_cat(day,category,is_system,cnt,last_ts) " +
                "SELECT day,category,is_system,COUNT(*),MAX(ts) FROM events " +
                "GROUP BY day,category,is_system",
        )
        db.execSQL(
            "INSERT INTO agg_cat_uid(day,category,is_system,uid,package,cnt,last_ts,label) " +
                "SELECT day,category,is_system,uid,COALESCE(package,''),COUNT(*),MAX(ts),MAX(label) " +
                "FROM events GROUP BY day,category,is_system,uid,COALESCE(package,'')",
        )
        db.execSQL(
            "INSERT INTO agg_uid_target(day,category,is_system,uid,package,target,cnt,last_ts) " +
                "SELECT day,category,is_system,uid,COALESCE(package,''),COALESCE(target,''),COUNT(*),MAX(ts) " +
                "FROM events GROUP BY day,category,is_system,uid,COALESCE(package,''),COALESCE(target,'')",
        )
        db.execSQL(
            "INSERT INTO agg_target(day,category,is_system,target,cnt,last_ts) " +
                "SELECT day,category,is_system,COALESCE(target,''),COUNT(*),MAX(ts) " +
                "FROM events GROUP BY day,category,is_system,COALESCE(target,'')",
        )
        db.execSQL(
            "INSERT INTO agg_hourly(day,category,is_system,hour,cnt) " +
                "SELECT day,category,is_system," +
                "CAST(strftime('%H',ts/1000,'unixepoch','localtime') AS INTEGER),COUNT(*) " +
                "FROM events GROUP BY day,category,is_system,4",
        )
    }

    /** 老库没有 auto_vacuum=INCREMENTAL；切换需要一次 VACUUM（用 prefs 保证只做一次）。 */
    private fun ensureIncrementalAutoVacuum(db: SQLiteDatabase) {
        val mode = runCatching {
            db.rawQuery("PRAGMA auto_vacuum", null).use { c -> if (c.moveToFirst()) c.getInt(0) else -1 }
        }.getOrDefault(-1)
        if (mode < 0 || mode == AUTO_VACUUM_INCREMENTAL) return
        val prefs = appContext.getSharedPreferences(DB_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_AUTO_VACUUM_DONE, false)) return
        runCatching {
            db.execSQL("PRAGMA auto_vacuum = INCREMENTAL")
            // VACUUM 不能在事务里执行；onOpen 处于自动提交模式。
            db.execSQL("VACUUM")
            prefs.edit().putBoolean(KEY_AUTO_VACUUM_DONE, true).apply()
            Log.i(TAG, "auto_vacuum switched to INCREMENTAL (one-time VACUUM)")
        }.onFailure { Log.w(TAG, "auto_vacuum conversion skipped", it) }
    }

    // ----------------------------------------------------------------- 表结构

    private fun createEventIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_ts ON events(ts)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_cat_ts ON events(category, ts)")
        // P1-3：调用者详情 / 时间线按 category+uid+ts；目标详情按 category+target+ts
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_cat_uid_ts ON events(category, uid, ts)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_cat_uid_target ON events(category, uid, target)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_cat_target_ts ON events(category, target, ts)")
        // 旧版本遗留的冗余索引（单列 uid / day 维度 / 无 ts 的 category+target）
        db.execSQL("DROP INDEX IF EXISTS idx_events_uid")
        db.execSQL("DROP INDEX IF EXISTS idx_events_day_cat")
        db.execSQL("DROP INDEX IF EXISTS idx_events_cat_target")
    }

    private fun createAggTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS agg_cat (
                day INTEGER NOT NULL,
                category TEXT NOT NULL,
                is_system INTEGER NOT NULL,
                cnt INTEGER NOT NULL,
                last_ts INTEGER NOT NULL,
                PRIMARY KEY(day, category, is_system)
            ) WITHOUT ROWID
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS agg_cat_uid (
                day INTEGER NOT NULL,
                category TEXT NOT NULL,
                is_system INTEGER NOT NULL,
                uid INTEGER NOT NULL,
                -- '' 表示该 uid 下无法归因包名的事件；同一 uid 的不同包名是不同的调用者。
                package TEXT NOT NULL,
                cnt INTEGER NOT NULL,
                last_ts INTEGER NOT NULL,
                label TEXT,
                PRIMARY KEY(day, category, is_system, uid, package)
            ) WITHOUT ROWID
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS agg_uid_target (
                day INTEGER NOT NULL,
                category TEXT NOT NULL,
                is_system INTEGER NOT NULL,
                uid INTEGER NOT NULL,
                package TEXT NOT NULL,
                target TEXT NOT NULL,
                cnt INTEGER NOT NULL,
                last_ts INTEGER NOT NULL,
                PRIMARY KEY(day, category, is_system, uid, package, target)
            ) WITHOUT ROWID
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS agg_target (
                day INTEGER NOT NULL,
                category TEXT NOT NULL,
                is_system INTEGER NOT NULL,
                target TEXT NOT NULL,
                cnt INTEGER NOT NULL,
                last_ts INTEGER NOT NULL,
                PRIMARY KEY(day, category, is_system, target)
            ) WITHOUT ROWID
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS agg_hourly (
                day INTEGER NOT NULL,
                category TEXT NOT NULL,
                is_system INTEGER NOT NULL,
                hour INTEGER NOT NULL,
                cnt INTEGER NOT NULL,
                PRIMARY KEY(day, category, is_system, hour)
            ) WITHOUT ROWID
            """.trimIndent(),
        )
    }

    /**
     * INSERT 触发器：只有真正插入 events（非 dedupe 冲突）才自增聚合，
     * 保证多源重复事件不会把 Dashboard 计数放大。
     */
    private fun createAggTriggers(db: SQLiteDatabase) {
        dropAggTriggers(db)
        db.execSQL(
            """
            CREATE TRIGGER trg_events_ai AFTER INSERT ON events BEGIN
                INSERT INTO agg_cat(day,category,is_system,cnt,last_ts)
                    VALUES (new.day,new.category,new.is_system,1,new.ts)
                    ON CONFLICT(day,category,is_system) DO UPDATE SET
                        cnt=cnt+1, last_ts=max(last_ts,new.ts);
                INSERT INTO agg_cat_uid(day,category,is_system,uid,package,cnt,last_ts,label)
                    VALUES (new.day,new.category,new.is_system,new.uid,coalesce(new.package,''),1,new.ts,new.label)
                    ON CONFLICT(day,category,is_system,uid,package) DO UPDATE SET
                        cnt=cnt+1, last_ts=max(last_ts,new.ts),
                        label=coalesce(label,excluded.label);
                INSERT INTO agg_uid_target(day,category,is_system,uid,package,target,cnt,last_ts)
                    VALUES (new.day,new.category,new.is_system,new.uid,coalesce(new.package,''),
                        coalesce(new.target,''),1,new.ts)
                    ON CONFLICT(day,category,is_system,uid,package,target) DO UPDATE SET
                        cnt=cnt+1, last_ts=max(last_ts,new.ts);
                INSERT INTO agg_target(day,category,is_system,target,cnt,last_ts)
                    VALUES (new.day,new.category,new.is_system,coalesce(new.target,''),1,new.ts)
                    ON CONFLICT(day,category,is_system,target) DO UPDATE SET
                        cnt=cnt+1, last_ts=max(last_ts,new.ts);
                INSERT INTO agg_hourly(day,category,is_system,hour,cnt)
                    VALUES (new.day,new.category,new.is_system,
                        CAST(strftime('%H',new.ts/1000,'unixepoch','localtime') AS INTEGER),1)
                    ON CONFLICT(day,category,is_system,hour) DO UPDATE SET cnt=cnt+1;
            END
            """.trimIndent(),
        )
    }

    private fun dropAggTriggers(db: SQLiteDatabase) {
        db.execSQL("DROP TRIGGER IF EXISTS trg_events_ai")
    }

    // ------------------------------------------------------------------ 观察流

    /**
     * 刷新触发器 = 写入版本（sample 2s，合并 300ms 一批的插入）+ 全库共享 ticker。
     * 持续写入时也每两秒刷新，不等待静默期。
     */
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    private fun <T> observe(build: (AppSettings) -> T): Flow<T> {
        val triggers = merge(
            version.sample(VERSION_SAMPLE_MS).map { Unit },
            sharedTicker,
        )
        return combine(triggers.onStart { emit(Unit) }, settings.state) { _, s -> s }
            .mapLatest { s -> withContext(Dispatchers.IO) { runCatching { build(s) }.getOrNull() } }
            .filterNotNull()
            .distinctUntilChanged()
    }

    override fun observeDashboard(): Flow<DashboardState> = observe { s -> buildDashboard(s) }

    override fun observeCategory(category: PrivacyCategory): Flow<CategoryDetailState> = observe { s ->
        buildCategory(category, s)
    }

    override fun observeCaller(
        category: PrivacyCategory,
        uid: Int,
        packageName: String?,
    ): Flow<CallerDetailState> = observe { s ->
        buildCaller(category, uid, packageName, s)
    }

    override fun observeTarget(category: PrivacyCategory, target: String): Flow<TargetDetailState> = observe { s ->
        buildTarget(category, target, s)
    }

    /**
     * 全保留期类别时间线，不依赖今日调用者排行榜；同毫秒事件按 id 稳定排序。
     *
     * 用 (ts, id) 游标而不是 OFFSET：时间线持续写入新事件时，offset 会让「加载更早」出现重复或跳过。
     * 多取一条用于判断是否还有更早记录，不把这一条暴露给 UI。
     */
    override suspend fun timelineForCategory(
        category: PrivacyCategory,
        cursor: TimelineCursor?,
        limit: Int,
    ): TimelinePage = withContext(Dispatchers.IO) {
        val sysFilter = if (settings.snapshot().showSystemApps) "" else " AND is_system=0"
        val cursorFilter = if (cursor == null) "" else " AND (ts<? OR (ts=? AND id<?))"
        val size = limit.coerceIn(1, 500)
        val args = ArrayList<String>(6)
        args.add(category.id)
        if (cursor != null) {
            args.add(cursor.ts.toString())
            args.add(cursor.ts.toString())
            args.add(cursor.id.toString())
        }
        args.add((size + 1).toString())
        val rows = ArrayList<Pair<PrivacyEvent, Long>>(size + 1)
        runCatching {
            helper.readableDatabase.rawQuery(
                SELECT_TIMELINE + " WHERE category=?$cursorFilter$sysFilter " +
                    "ORDER BY ts DESC,id DESC LIMIT ?",
                args.toTypedArray(),
            ).use { c ->
                while (c.moveToNext()) rows.add(readEvent(c) to c.getLong(13))
            }
        }.onFailure { Log.w(TAG, "timelineForCategory failed", it) }
        val hasMore = rows.size > size
        val pageRows = if (hasMore) rows.subList(0, size) else rows
        val nextCursor = if (hasMore && pageRows.isNotEmpty()) {
            val last = pageRows.last()
            TimelineCursor(last.first.ts, last.second)
        } else {
            null
        }
        TimelinePage(pageRows.map { it.first }, nextCursor)
    }

    override suspend fun todayEventCount(): Long = withContext(Dispatchers.IO) {
        val sysFilter = if (settings.snapshot().showSystemApps) "" else " AND is_system=0"
        scalarLong("SELECT COALESCE(SUM(cnt),0) FROM agg_cat WHERE day=?$sysFilter",
            arrayOf(dayStart().toString()))
    }

    /** 释放观察任务和数据库连接（进程级实例通常随进程退出）。 */
    internal fun close() {
        refreshScope.cancel()
        helper.close()
    }

    // ----------------------------------------------------------------- 写入

    override suspend fun insertEvents(events: List<PrivacyEvent>) {
        if (events.isEmpty()) return
        withContext(Dispatchers.IO) {
            synchronized(maintenanceLock) {
                val db = helper.writableDatabase
                var hasNetwork = false
                val statement = db.compileStatement(UPSERT_EVENT)
                try {
                    db.beginTransaction()
                    try {
                        for (e in events) {
                            if (e.category == PrivacyCategory.NETWORK) hasNetwork = true
                            val pkg = e.packageName?.takeIf { it.isNotBlank() }
                                ?: packageResolver?.invoke(e.uid)?.takeIf { it.isNotBlank() }
                            // 本 App / 旧包名的事件不是被审计对象（含自检事件），不写入历史。
                            if (isSelfPackage(pkg)) continue
                            statement.clearBindings()
                            statement.bindLong(1, e.ts)
                            statement.bindLong(2, dayStartOf(e.ts))
                            statement.bindString(3, e.category.id)
                            statement.bindString(4, e.subType.take(160))
                            statement.bindLong(5, e.uid.toLong())
                            if (e.pid != null) statement.bindLong(6, e.pid.toLong()) else statement.bindNull(6)
                            bindStringOrNull(statement, 7, pkg)
                            bindStringOrNull(statement, 8, e.appLabel ?: resolveLabel(e.uid, pkg))
                            bindStringOrNull(statement, 9, e.target)
                            bindStringOrNull(statement, 10, e.detail)
                            statement.bindString(11, e.source.name)
                            statement.bindLong(12, DedupeKey.sourceRank(e.source).toLong())
                            statement.bindLong(13, e.confidence.toLong())
                            if (e.durationMs != null) statement.bindLong(14, e.durationMs) else statement.bindNull(14)
                            bindStringOrNull(statement, 15, e.direction)
                            statement.bindLong(16, if (systemUidPredicate(e.uid)) 1L else 0L)
                            statement.bindString(17, DedupeKey.key(e))
                            statement.executeInsert()
                        }
                        db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                    }
                } finally {
                    runCatching { statement.close() }
                }
                insertCounter.addAndGet(events.size.toLong())
                // 冲突更新也算变化；直接按批次自增（观察流已 sample，不会放大查询）。
                version.update { it + 1 }
                if (hasNetwork) domainCountCacheAt = 0L
                runCatching { maybeMaintain(db) }.onFailure { Log.e(TAG, "maintenance failed", it) }
            }
        }
    }

    override suspend fun clearAll() = withContext(Dispatchers.IO) {
        synchronized(maintenanceLock) {
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                dropAggTriggers(db)
                db.execSQL("DELETE FROM events")
                db.execSQL("DELETE FROM sqlite_sequence WHERE name='events'")
                dropAggTables(db)
                createAggTables(db)
                createAggTriggers(db)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            runCatching { incrementalVacuum(db) }
            runCatching { db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() } }
            lastPurgeAt = 0L
            domainCountCacheAt = 0L
            version.update { it + 1 }
        }
    }

    override suspend fun purgeBefore(cutoff: Long): Int = withContext(Dispatchers.IO) {
        if (cutoff <= 0) return@withContext 0
        val db = helper.writableDatabase
        val deleted = runCatching {
            synchronized(maintenanceLock) { bulkDeleteBefore(db, cutoff) }
        }.getOrDefault(0)
        if (deleted > 0) {
            maintainAfterDelete(db, deleted)
            version.update { it + 1 }
            domainCountCacheAt = 0L
        }
        deleted
    }

    /** 按当前 retentionDays 清理，返回删除条数。 */
    suspend fun purgeExpired(): Int {
        val days = settings.snapshot().retentionDays.coerceAtLeast(1)
        return purgeBefore(now() - days * 86_400_000L)
    }

    // --------------------------------------------------------------- 维护

    /**
     * 保留期每 30 分钟清理一次，容量限制每批检查。
     */
    private fun maybeMaintain(db: SQLiteDatabase) = synchronized(maintenanceLock) {
        val current = android.os.SystemClock.elapsedRealtime()
        if (lastPurgeAt == 0L || current - lastPurgeAt >= PURGE_INTERVAL_MS) {
            val deleted = bulkDeleteBefore(db, retentionCutoff(settings.snapshot()))
            lastPurgeAt = current
            if (deleted > 0) maintainAfterDelete(db, deleted)
        }
        // 容量保护每批执行，与低频 retention 分离；所有写入和维护共用同一锁。
        enforceCapacity()
    }

    /**
     * 批次提交后限制行数和有效页大小；删除后先回收空闲页，再 checkpoint。
     * 有读事务占用 WAL 时不因重复计算 WAL 大小而继续删除有效数据。
     * 返回时物理文件可能仍含读事务保留的 WAL，下一批会重试 checkpoint。
     */
    internal fun enforceCapacity(rowLimit: Long = MAX_ROWS, byteLimit: Long = MAX_DB_BYTES) {
        val db = helper.writableDatabase
        synchronized(maintenanceLock) {
            var count = scalarLong("SELECT COALESCE(SUM(cnt),0) FROM agg_cat", emptyArray())
            if (count > rowLimit) {
                // 留出 10% 写入空间，避免达到上限后每一批都重建整天聚合。
                val keep = (rowLimit * 9 / 10).coerceAtLeast(1)
                deleteOldest(db, count - keep)
                count = keep
            }
            fun pragma(name: String) = scalarLong("PRAGMA $name", emptyArray())
            fun liveBytes(): Long = (pragma("page_count") - pragma("freelist_count")) * pragma("page_size")
            while (count > 0 && liveBytes() > byteLimit) {
                val remove = maxOf(1L, count / 10)
                deleteOldest(db, remove)
                count -= remove
            }
            if (dbFile.length() + File(dbFile.path + "-wal").length() > byteLimit) {
                // incremental_vacuum 默认回收全部尾部空闲页；不再每次只回收 2000 页。
                incrementalVacuum(db)
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { c ->
                    if (c.moveToFirst() && c.getInt(0) != 0) {
                        Log.w(TAG, "WAL checkpoint deferred: active reader")
                    }
                }
            }
        }
    }

    private fun deleteOldest(db: SQLiteDatabase, count: Long) {
        // id 打破相同 ts 的平局，避免仅靠 cutoff 保留无限多条同毫秒事件。
        bulkDelete(db, "id IN (SELECT id FROM events ORDER BY ts,id LIMIT ?)", arrayOf(count.toString()))
        domainCountCacheAt = 0L
        version.update { it + 1 }
    }

    /**
     * 批量删除 ts<cutoff 的事件，并在同一事务里重建被删区间对应天的聚合。
     * 先 SELECT 出受影响 day 范围 → 摘触发器 → DELETE → 重建这些天 → 装回触发器。
     * 事务提交前 readers 仍看到旧的 events+agg 快照，不会出现 Dashboard 清零闪烁。
     */
    private fun bulkDeleteBefore(db: SQLiteDatabase, cutoff: Long): Int {
        if (cutoff <= 0) return 0
        return bulkDelete(db, "ts<?", arrayOf(cutoff.toString()))
    }

    private fun bulkDelete(db: SQLiteDatabase, where: String, args: Array<String>): Int {
        db.beginTransaction()
        try {
            val range = db.rawQuery("SELECT MIN(day), MAX(day) FROM events WHERE $where", args).use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) to c.getLong(1) else null
            }
            if (range == null) {
                db.setTransactionSuccessful()
                return 0
            }
            dropAggTriggers(db)
            val deleted = db.delete("events", where, args)
            rebuildAggDays(db, range.first, range.second)
            createAggTriggers(db)
            db.setTransactionSuccessful()
            return deleted
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 从 events 重建 day ∈ [fromDay, toDay] 的聚合：只删/重建受影响的天，
     * 其他天的聚合行保持不变。事件用 ts 索引范围扫描，被整段删掉的旧天几乎无成本。
     */
    private fun rebuildAggDays(db: SQLiteDatabase, fromDay: Long, toDay: Long) {
        val dayArgs = arrayOf<Any>(fromDay, toDay)
        db.execSQL("DELETE FROM agg_cat WHERE day BETWEEN ? AND ?", dayArgs)
        db.execSQL("DELETE FROM agg_cat_uid WHERE day BETWEEN ? AND ?", dayArgs)
        db.execSQL("DELETE FROM agg_uid_target WHERE day BETWEEN ? AND ?", dayArgs)
        db.execSQL("DELETE FROM agg_target WHERE day BETWEEN ? AND ?", dayArgs)
        db.execSQL("DELETE FROM agg_hourly WHERE day BETWEEN ? AND ?", dayArgs)
        db.execSQL(
            "INSERT INTO agg_cat(day,category,is_system,cnt,last_ts) " +
                "SELECT day,category,is_system,COUNT(*),MAX(ts) FROM events " +
                "WHERE day BETWEEN ? AND ? GROUP BY day,category,is_system",
            dayArgs,
        )
        db.execSQL(
            "INSERT INTO agg_cat_uid(day,category,is_system,uid,package,cnt,last_ts,label) " +
                "SELECT day,category,is_system,uid,COALESCE(package,''),COUNT(*),MAX(ts),MAX(label) " +
                "FROM events WHERE day BETWEEN ? AND ? " +
                "GROUP BY day,category,is_system,uid,COALESCE(package,'')",
            dayArgs,
        )
        db.execSQL(
            "INSERT INTO agg_uid_target(day,category,is_system,uid,package,target,cnt,last_ts) " +
                "SELECT day,category,is_system,uid,COALESCE(package,''),COALESCE(target,''),COUNT(*),MAX(ts) " +
                "FROM events WHERE day BETWEEN ? AND ? " +
                "GROUP BY day,category,is_system,uid,COALESCE(package,''),COALESCE(target,'')",
            dayArgs,
        )
        db.execSQL(
            "INSERT INTO agg_target(day,category,is_system,target,cnt,last_ts) " +
                "SELECT day,category,is_system,COALESCE(target,''),COUNT(*),MAX(ts) " +
                "FROM events WHERE day BETWEEN ? AND ? GROUP BY day,category,is_system,COALESCE(target,'')",
            dayArgs,
        )
        db.execSQL(
            "INSERT INTO agg_hourly(day,category,is_system,hour,cnt) " +
                "SELECT day,category,is_system," +
                "CAST(strftime('%H',ts/1000,'unixepoch','localtime') AS INTEGER),COUNT(*) " +
                "FROM events WHERE day BETWEEN ? AND ? GROUP BY day,category,is_system,4",
            dayArgs,
        )
    }

    private fun dropAggTables(db: SQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS agg_cat")
        db.execSQL("DROP TABLE IF EXISTS agg_cat_uid")
        db.execSQL("DROP TABLE IF EXISTS agg_uid_target")
        db.execSQL("DROP TABLE IF EXISTS agg_target")
        db.execSQL("DROP TABLE IF EXISTS agg_hourly")
    }

    private fun incrementalVacuum(db: SQLiteDatabase) {
        // 此 PRAGMA 会返回行；execSQL 在 Android 上会拒绝执行它。
        db.rawQuery("PRAGMA incremental_vacuum", null).use { cursor ->
            while (cursor.moveToNext()) { /* drain every vacuum step */ }
        }
    }

    /** purge 之后：回收空闲页、checkpoint WAL；极低频场景再兜底一次 VACUUM。 */
    private fun maintainAfterDelete(db: SQLiteDatabase, deleted: Int) {
        runCatching { incrementalVacuum(db) }
        val now = now()
        if (now - lastWalCheckpointAt >= WAL_CHECKPOINT_INTERVAL_MS) {
            lastWalCheckpointAt = now
            runCatching { db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() } }
        }
        // 正常路径由 auto_vacuum=INCREMENTAL + incremental_vacuum 回收；
        // 只有老库转换失败（auto_vacuum 仍为 NONE）且删了较多行时，每日一次 VACUUM 兜底。
        if (deleted >= VACUUM_MIN_DELETED && now - lastVacuumAt >= VACUUM_INTERVAL_MS &&
            autoVacuumMode(db) != AUTO_VACUUM_INCREMENTAL
        ) {
            lastVacuumAt = now
            runCatching { db.execSQL("VACUUM") }
                .onFailure { Log.w(TAG, "periodic VACUUM failed", it) }
        }
    }

    private fun autoVacuumMode(db: SQLiteDatabase): Int = runCatching {
        db.rawQuery("PRAGMA auto_vacuum", null).use { c -> if (c.moveToFirst()) c.getInt(0) else -1 }
    }.getOrDefault(-1)

    // ----------------------------------------------------------------- 导出

    override suspend fun exportCsv(file: File): Int = withContext(Dispatchers.IO) {
        try {
            file.parentFile?.mkdirs()
            var count = 0
            helper.readableDatabase.rawQuery(SELECT_EVENTS + " ORDER BY ts ASC", null).use { c ->
                file.bufferedWriter().use { w ->
                    w.write(
                        "ts,time_iso,category,sub_type,uid,pid,package,label,target,detail," +
                            "source,confidence,duration_ms,direction\n",
                    )
                    while (c.moveToNext()) {
                        w.write(csvRow(readEvent(c)))
                        w.write("\n")
                        count++
                    }
                }
            }
            count
        } catch (t: Throwable) {
            Log.e(TAG, "exportCsv failed", t)
            throw t
        }
    }

    override suspend fun exportJsonl(file: File): Int = withContext(Dispatchers.IO) {
        try {
            file.parentFile?.mkdirs()
            var count = 0
            helper.readableDatabase.rawQuery(SELECT_EVENTS + " ORDER BY ts ASC", null).use { c ->
                file.bufferedWriter().use { w ->
                    while (c.moveToNext()) {
                        w.write(eventJson(readEvent(c)).toString())
                        w.write("\n")
                        count++
                    }
                }
            }
            count
        } catch (t: Throwable) {
            Log.e(TAG, "exportJsonl failed", t)
            throw t
        }
    }

    override suspend fun totalEventCount(): Long = withContext(Dispatchers.IO) {
        runCatching { scalarLong("SELECT COUNT(*) FROM events", emptyArray()) }.getOrDefault(0L)
    }

    override suspend fun domainCount(): Int = withContext(Dispatchers.IO) {
        runCatching { domainCountBlocking() }.getOrDefault(0)
    }

    // ------------------------------------------------------------- Dashboard

    /**
     * Dashboard 走按天预聚合表，≈9 条小查询（旧实现 36 条全表扫描，604k 行 3.1s）：
     * agg_cat + agg_cat_uid + agg_target + agg_hourly + 应用数 + recent + 域名数。
     */
    private fun buildDashboard(s: AppSettings): DashboardState {
        val now = now()
        val dayStart = dayStart(now)
        val status = statusProvider?.invoke() ?: CollectorRuntimeStatus()
        val sysFilter = if (s.showSystemApps) "" else " AND is_system=0"
        val db = helper.readableDatabase

        // 1) 每类今日计数 / 最后时间
        val catCounts = HashMap<PrivacyCategory, CatAgg>()
        var totalToday = 0L
        runCatching {
            db.rawQuery(
                "SELECT category, SUM(cnt), MAX(last_ts) FROM agg_cat WHERE day=?$sysFilter GROUP BY category",
                arrayOf(dayStart.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    val cat = PrivacyCategory.fromId(c.getString(0)) ?: continue
                    val count = c.getLong(1)
                    catCounts[cat] = CatAgg(count, 0, c.getLong(2))
                    totalToday += count
                }
            }
        }

        // 2) 每类调用者数 + top caller（一条查询，按类别内 count 倒序）
        val callerCounts = HashMap<PrivacyCategory, Int>()
        val topCallers = HashMap<PrivacyCategory, CallerSummary>()
        runCatching {
            db.rawQuery(
                "SELECT category, uid, package, SUM(cnt), MAX(last_ts), MAX(label) FROM agg_cat_uid " +
                    "WHERE day=?$sysFilter GROUP BY category,uid,package " +
                    "ORDER BY category ASC, SUM(cnt) DESC",
                arrayOf(dayStart.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    val cat = PrivacyCategory.fromId(c.getString(0)) ?: continue
                    callerCounts[cat] = (callerCounts[cat] ?: 0) + 1
                    if (!topCallers.containsKey(cat)) {
                        val uid = c.getInt(1)
                        val pkg = c.getString(2)?.takeIf { it.isNotBlank() }
                        topCallers[cat] = CallerSummary(
                            uid = uid,
                            packageName = pkg,
                            label = resolveLabel(uid, pkg, c.getString(5)),
                            count = c.getLong(3),
                            lastTs = c.getLong(4),
                            targetCount = 0,
                            isSystem = systemUidPredicate(uid),
                        )
                    }
                }
            }
        }

        // 3) 今日全局应用数（跨类别按包名去重；未知包名按 uid 计一个）
        val appCount = runCatching {
            scalarInt(
                "SELECT COUNT(DISTINCT CASE WHEN package<>'' THEN package ELSE 'uid:'||uid END) " +
                    "FROM agg_cat_uid WHERE day=?$sysFilter",
                arrayOf(dayStart.toString()),
            )
        }.getOrDefault(0)

        // 4) 24h 分布
        val hourly = hourlyAll(dayStart, sysFilter)

        // 6) 最近事件
        val recent = queryEvents(
            SELECT_EVENTS + " WHERE 1=1$sysFilter ORDER BY ts DESC LIMIT $RECENT_LIMIT",
            emptyArray(),
        )

        val categories = PrivacyCategory.entries.map { cat ->
            val agg = catCounts[cat] ?: CatAgg(0, 0, 0)
            CategorySummary(
                category = cat,
                countToday = agg.count,
                callerCount = callerCounts[cat] ?: 0,
                lastTs = agg.last.takeIf { it > 0 },
                topCaller = topCallers[cat],
                todayHourly = hourly[cat] ?: List(24) { 0 },
            )
        }

        return DashboardState(
            rootAvailable = status.rootAvailable,
            totalToday = totalToday,
            appCount = appCount,
            domainCount = runCatching { domainCountBlocking() }.getOrDefault(0),
            categories = categories,
            recent = recent,
            activeProbeCount = status.activeProbeCount,
            rootHint = status.rootHint,
        )
    }

    private data class CatAgg(val count: Long, val callers: Int, val last: Long)

    /** 今日 24h 分布（读 agg_hourly，每类 24 行）。 */
    private fun hourlyAll(dayStart: Long, sysFilter: String): Map<PrivacyCategory, List<Int>> {
        val map = HashMap<PrivacyCategory, IntArray>()
        runCatching {
            helper.readableDatabase.rawQuery(
                "SELECT category, hour, SUM(cnt) FROM agg_hourly WHERE day=?$sysFilter GROUP BY category,hour",
                arrayOf(dayStart.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    val cat = PrivacyCategory.fromId(c.getString(0)) ?: continue
                    val arr = map.getOrPut(cat) { IntArray(24) }
                    val h = c.getInt(1)
                    if (h in 0..23) arr[h] = c.getInt(2)
                }
            }
        }
        return map.mapValues { (_, v) -> v.toList() }
    }

    /** 全库域名数：Dashboard 刷新用 30s TTL 缓存，避免重复扫聚合表。 */
    private fun domainCountBlocking(): Int {
        val now = now()
        if (domainCountCacheAt > 0 && now - domainCountCacheAt < DOMAIN_COUNT_TTL_MS) {
            return domainCountCache
        }
        val value = scalarInt(
            "SELECT COUNT(DISTINCT target) FROM agg_target WHERE category='network' AND target<>''",
            emptyArray(),
        )
        domainCountCache = value
        domainCountCacheAt = now
        return value
    }

    // -------------------------------------------------------------- Category

    private fun buildCategory(category: PrivacyCategory, s: AppSettings): CategoryDetailState {
        val now = now()
        val dayStart = dayStart(now)
        val db = helper.readableDatabase
        val sysFilter = if (s.showSystemApps) "" else " AND is_system=0"
        var countToday = 0L
        var countTotal = 0L
        var callersToday = 0
        var targetsToday = 0
        var lastTs = 0L

        runCatching {
            db.rawQuery(
                "SELECT COALESCE(SUM(cnt),0), MAX(last_ts) FROM agg_cat WHERE day=? AND category=?$sysFilter",
                arrayOf(dayStart.toString(), category.id),
            ).use { c ->
                if (c.moveToFirst()) {
                    countToday = c.getLong(0)
                    lastTs = c.getLong(1)
                }
            }
        }
        // 累计：跨天 SUM（每类每天 1~2 行，极快）
        countTotal = runCatching {
            scalarLong(
                "SELECT COALESCE(SUM(cnt),0) FROM agg_cat WHERE category=?$sysFilter",
                arrayOf(category.id),
            )
        }.getOrDefault(0L)
        // P2-7：调用者/目标口径与下方「今日列表」一致
        callersToday = runCatching {
            scalarInt(
                "SELECT COUNT(DISTINCT CASE WHEN package<>'' THEN package ELSE 'uid:'||uid END) " +
                    "FROM agg_cat_uid WHERE day=? AND category=?$sysFilter",
                arrayOf(dayStart.toString(), category.id),
            )
        }.getOrDefault(0)
        targetsToday = runCatching {
            scalarInt(
                "SELECT COUNT(DISTINCT target) FROM agg_target WHERE day=? AND category=? AND target<>''$sysFilter",
                arrayOf(dayStart.toString(), category.id),
            )
        }.getOrDefault(0)

        val callers = runCatching { callersFor(category, dayStart, 50, sysFilter) }.getOrDefault(emptyList())
        val targets = runCatching { targetsFor(category, dayStart, 30, sysFilter) }.getOrDefault(emptyList())
        val hourly = runCatching { hourlyFor(category, dayStart, sysFilter) }.getOrDefault(List(24) { 0 })
        return CategoryDetailState(
            category = category,
            countToday = countToday,
            countTotal = countTotal,
            callerCount = callersToday,
            targetCount = targetsToday,
            lastTs = lastTs.takeIf { it > 0 },
            callers = callers,
            targets = targets,
            todayHourly = hourly,
        )
    }

    private fun hourlyFor(category: PrivacyCategory, dayStart: Long, sysFilter: String): List<Int> {
        val arr = IntArray(24)
        helper.readableDatabase.rawQuery(
            "SELECT hour, SUM(cnt) FROM agg_hourly WHERE day=? AND category=?$sysFilter GROUP BY hour",
            arrayOf(dayStart.toString(), category.id),
        ).use { c ->
            while (c.moveToNext()) {
                val h = c.getInt(0)
                if (h in 0..23) arr[h] = c.getInt(1)
            }
        }
        return arr.toList()
    }

    // ---------------------------------------------------------------- Caller

    private fun buildCaller(
        category: PrivacyCategory,
        uid: Int,
        packageName: String?,
        s: AppSettings,
    ): CallerDetailState {
        val dayStart = dayStart()
        val sysFilter = if (s.showSystemApps) "" else " AND is_system=0"
        val db = helper.readableDatabase
        val aggPkg = packageName.orEmpty()
        val (pkgWhere, pkgArgs) = packageClause(packageName)

        var todayCount = 0L
        var totalCount = 0L
        var lastTs = 0L
        var label: String? = null
        runCatching {
            db.rawQuery(
                "SELECT COALESCE(SUM(CASE WHEN day=? THEN cnt ELSE 0 END),0), " +
                    "COALESCE(SUM(cnt),0), MAX(last_ts), MAX(label) " +
                    "FROM agg_cat_uid WHERE category=? AND uid=? AND package=?$sysFilter",
                arrayOf(dayStart.toString(), category.id, uid.toString(), aggPkg),
            ).use { c ->
                if (c.moveToFirst()) {
                    todayCount = c.getLong(0)
                    totalCount = c.getLong(1)
                    lastTs = c.getLong(2)
                    label = c.getString(3)
                }
            }
        }
        // 首次出现：走 (category,uid,ts) 索引取最早一条
        val firstTs = runCatching {
            db.rawQuery(
                "SELECT ts FROM events WHERE category=? AND uid=?$pkgWhere$sysFilter ORDER BY ts ASC LIMIT 1",
                arrayOf(category.id, uid.toString()) + pkgArgs,
            ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        }.getOrNull()
        val targets = runCatching {
            targetsForCaller(category, uid, aggPkg, sysFilter)
        }.getOrDefault(emptyList())
        val caller = CallerSummary(
            uid = uid,
            packageName = packageName?.takeIf { it.isNotBlank() },
            label = resolveLabel(uid, packageName?.takeIf { it.isNotBlank() }, label),
            count = if (todayCount > 0) todayCount else totalCount,
            lastTs = lastTs,
            targetCount = targets.size,
            isSystem = systemUidPredicate(uid),
        )
        val events = queryEvents(
            SELECT_EVENTS + " WHERE category=? AND uid=?$pkgWhere$sysFilter " +
                "ORDER BY ts DESC LIMIT $CALLER_EVENT_LIMIT",
            arrayOf(category.id, uid.toString()) + pkgArgs,
        )
        return CallerDetailState(
            category = category,
            caller = caller,
            todayCount = todayCount,
            totalCount = totalCount,
            firstTs = firstTs,
            lastTs = lastTs.takeIf { it > 0 },
            targets = targets,
            events = events,
        )
    }

    /**
     * 调用者身份由 (uid, package) 决定：同一 uid 下的不同包名（典型是共享 uid 1000 的系统应用）
     * 是不同调用者。packageName 为空表示「该 uid 下无法归因包名」的事件。
     */
    private fun packageClause(packageName: String?): Pair<String, Array<String>> =
        if (packageName.isNullOrBlank()) {
            " AND package IS NULL" to emptyArray()
        } else {
            " AND package=?" to arrayOf(packageName)
        }

    // ---------------------------------------------------------------- Target

    private fun buildTarget(category: PrivacyCategory, target: String, s: AppSettings): TargetDetailState {
        val sysFilter = if (s.showSystemApps) "" else " AND is_system=0"
        val db = helper.readableDatabase
        var count = 0L
        var callerCount = 0
        var lastTs = 0L
        runCatching {
            db.rawQuery(
                "SELECT COALESCE(SUM(cnt),0), MAX(last_ts) FROM agg_target " +
                    "WHERE category=? AND target=?$sysFilter",
                arrayOf(category.id, target),
            ).use { c ->
                if (c.moveToFirst()) {
                    count = c.getLong(0)
                    lastTs = c.getLong(1)
                }
            }
        }
        callerCount = runCatching {
            scalarInt(
                "SELECT COUNT(DISTINCT CASE WHEN package<>'' THEN package ELSE 'uid:'||uid END) " +
                    "FROM agg_uid_target WHERE category=? AND target=?$sysFilter",
                arrayOf(category.id, target),
            )
        }.getOrDefault(0)
        val summary = TargetSummary(
            target = target,
            count = count,
            callerCount = callerCount,
            lastTs = lastTs,
            kind = resolveTargetKind(category, target),
        )
        val callers = runCatching { callersForTarget(category, target, sysFilter) }.getOrDefault(emptyList())
        val events = queryEvents(
            SELECT_EVENTS + " WHERE category=? AND target=?$sysFilter ORDER BY ts DESC LIMIT $TARGET_EVENT_LIMIT",
            arrayOf(category.id, target),
        )
        return TargetDetailState(category = category, target = summary, callers = callers, events = events)
    }

    // ----------------------------------------------------------- 聚合辅助查询

    /** 某类某天调用者列表（读 agg_cat_uid，按 (uid, package) 拆分，再补每人的 target 数）。 */
    private fun callersFor(
        category: PrivacyCategory,
        dayStart: Long,
        limit: Int,
        sysFilter: String,
    ): List<CallerSummary> {
        data class Row(val uid: Int, val pkg: String?, val label: String?, val count: Long, val last: Long)

        val rows = ArrayList<Row>()
        helper.readableDatabase.rawQuery(
            "SELECT uid, package, MAX(label), SUM(cnt), MAX(last_ts) FROM agg_cat_uid " +
                "WHERE day=? AND category=?$sysFilter GROUP BY uid,package " +
                "ORDER BY SUM(cnt) DESC LIMIT ?",
            arrayOf(dayStart.toString(), category.id, limit.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                rows.add(
                    Row(
                        uid = c.getInt(0),
                        pkg = c.getString(1)?.takeIf { it.isNotBlank() },
                        label = c.getString(2),
                        count = c.getLong(3),
                        last = c.getLong(4),
                    ),
                )
            }
        }
        if (rows.isEmpty()) return emptyList()

        val targetCounts = HashMap<Pair<Int, String>, Int>()
        helper.readableDatabase.rawQuery(
            "SELECT uid, package, COUNT(*) FROM agg_uid_target " +
                "WHERE day=? AND category=? AND target<>''$sysFilter GROUP BY uid,package",
            arrayOf(dayStart.toString(), category.id),
        ).use { c ->
            while (c.moveToNext()) {
                targetCounts[c.getInt(0) to c.getString(1)] = c.getInt(2)
            }
        }
        return rows.map { r ->
            CallerSummary(
                uid = r.uid,
                packageName = r.pkg,
                label = resolveLabel(r.uid, r.pkg, r.label),
                count = r.count,
                lastTs = r.last,
                targetCount = targetCounts[r.uid to r.pkg.orEmpty()] ?: 0,
                isSystem = systemUidPredicate(r.uid),
            )
        }
    }

    private fun targetsFor(
        category: PrivacyCategory,
        dayStart: Long,
        limit: Int,
        sysFilter: String,
    ): List<TargetSummary> {
        val out = ArrayList<TargetSummary>()
        helper.readableDatabase.rawQuery(
            "SELECT target, SUM(cnt), MAX(last_ts) FROM agg_target " +
                "WHERE day=? AND category=? AND target<>''$sysFilter GROUP BY target ORDER BY SUM(cnt) DESC LIMIT ?",
            arrayOf(dayStart.toString(), category.id, limit.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val target = c.getString(0) ?: continue
                out.add(
                    TargetSummary(
                        target = target,
                        count = c.getLong(1),
                        callerCount = runCatching {
                            scalarInt(
                                "SELECT COUNT(DISTINCT CASE WHEN package<>'' THEN package ELSE 'uid:'||uid END) " +
                                    "FROM agg_uid_target WHERE day=? AND category=? AND target=?$sysFilter",
                                arrayOf(dayStart.toString(), category.id, target),
                            )
                        }.getOrDefault(0),
                        lastTs = c.getLong(2),
                        kind = resolveTargetKind(category, target),
                    ),
                )
            }
        }
        return out
    }

    private fun targetsForCaller(
        category: PrivacyCategory,
        uid: Int,
        packageName: String,
        sysFilter: String,
    ): List<TargetSummary> {
        val out = ArrayList<TargetSummary>()
        helper.readableDatabase.rawQuery(
            "SELECT target, SUM(cnt) AS c, MAX(last_ts) AS last FROM agg_uid_target " +
                "WHERE category=? AND uid=? AND package=? AND target<>''$sysFilter " +
                "GROUP BY target ORDER BY c DESC LIMIT 40",
            arrayOf(category.id, uid.toString(), packageName),
        ).use { c ->
            while (c.moveToNext()) {
                val target = c.getString(0) ?: continue
                out.add(
                    TargetSummary(
                        target = target,
                        count = c.getLong(1),
                        callerCount = 1,
                        lastTs = c.getLong(2),
                        kind = resolveTargetKind(category, target),
                    ),
                )
            }
        }
        return out
    }

    private fun callersForTarget(
        category: PrivacyCategory,
        target: String,
        sysFilter: String,
    ): List<CallerSummary> {
        data class Row(val uid: Int, val pkg: String?, val count: Long, val last: Long)

        val rows = ArrayList<Row>()
        helper.readableDatabase.rawQuery(
            "SELECT uid, package, SUM(cnt) AS c, MAX(last_ts) AS last FROM agg_uid_target " +
                "WHERE category=? AND target=?$sysFilter GROUP BY uid,package ORDER BY c DESC LIMIT 60",
            arrayOf(category.id, target),
        ).use { c ->
            while (c.moveToNext()) {
                rows.add(
                    Row(
                        uid = c.getInt(0),
                        pkg = c.getString(1)?.takeIf { it.isNotBlank() },
                        count = c.getLong(2),
                        last = c.getLong(3),
                    ),
                )
            }
        }
        if (rows.isEmpty()) return emptyList()
        // 用聚合表里的调用者元信息补 label（历史事件可能没有元信息时留空）
        val meta = HashMap<Pair<Int, String>, String?>()
        helper.readableDatabase.rawQuery(
            "SELECT uid, package, MAX(label) FROM agg_cat_uid WHERE category=?$sysFilter " +
                "GROUP BY uid,package",
            arrayOf(category.id),
        ).use { c ->
            while (c.moveToNext()) meta[c.getInt(0) to c.getString(1)] = c.getString(2)
        }
        return rows.map { r ->
            CallerSummary(
                uid = r.uid,
                packageName = r.pkg,
                label = resolveLabel(r.uid, r.pkg, meta[r.uid to r.pkg.orEmpty()]),
                count = r.count,
                lastTs = r.last,
                targetCount = 1,
                isSystem = systemUidPredicate(r.uid),
            )
        }
    }

    // ------------------------------------------------------------------ 工具

    private fun queryEvents(sql: String, args: Array<String>): List<PrivacyEvent> {
        val out = ArrayList<PrivacyEvent>()
        runCatching {
            helper.readableDatabase.rawQuery(sql, args).use { c ->
                while (c.moveToNext()) out.add(readEvent(c))
            }
        }.onFailure { Log.w(TAG, "queryEvents failed", it) }
        return out
    }

    private fun readEvent(c: Cursor): PrivacyEvent {
        val category = PrivacyCategory.fromId(c.getString(1)) ?: PrivacyCategory.APP_ACTIVITY
        val source = runCatching { EventSource.valueOf(c.getString(9)) }.getOrDefault(EventSource.HEURISTIC)
        val uid = c.getInt(3)
        val pkg = c.getString(5)
        val storedLabel = c.getString(6)
        return PrivacyEvent(
            ts = c.getLong(0),
            category = category,
            subType = c.getString(2),
            uid = uid,
            pid = if (c.isNull(4)) null else c.getInt(4),
            packageName = pkg,
            appLabel = storedLabel ?: resolveLabel(uid, pkg),
            target = c.getString(7),
            detail = c.getString(8),
            source = source,
            confidence = c.getInt(10),
            durationMs = if (c.isNull(11)) null else c.getLong(11),
            direction = c.getString(12),
        )
    }

    /** 本 App 自身 / 旧包名的事件不是被审计对象，不写入历史。 */
    private fun isSelfPackage(pkg: String?): Boolean =
        pkg == selfPackage || pkg == LEGACY_SELF_PACKAGE

    private fun resolveLabel(uid: Int, pkg: String?, stored: String? = null): String {
        if (!stored.isNullOrBlank()) return stored
        displayNameResolver?.let { return it(uid, pkg) }
        return pkg ?: if (uid > 0) "uid $uid" else "未知调用者"
    }

    private fun resolveTargetKind(category: PrivacyCategory, target: String): String {
        targetKindResolver?.let { return it(category, target) }
        return when (category) {
            PrivacyCategory.NETWORK -> "domain"
            PrivacyCategory.CAMERA -> "camera"
            PrivacyCategory.MICROPHONE -> "audio"
            PrivacyCategory.LOCATION -> "location"
            PrivacyCategory.SENSOR -> "sensor"
            PrivacyCategory.SCREEN -> "screen"
            PrivacyCategory.CLIPBOARD -> "clipboard"
            else -> category.id
        }
    }

    private fun scalarInt(sql: String, args: Array<String>): Int =
        runCatching {
            helper.readableDatabase.rawQuery(sql, args).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        }.getOrDefault(0)

    private fun scalarLong(sql: String, args: Array<String>): Long =
        runCatching {
            helper.readableDatabase.rawQuery(sql, args).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        }.getOrDefault(0L)

    private fun bindStringOrNull(statement: android.database.sqlite.SQLiteStatement, index: Int, value: String?) {
        if (value == null) statement.bindNull(index) else statement.bindString(index, value.take(2000))
    }

    /** CSV 转义（P2-6 公式注入防护），实现见 [ExportFormat]。 */
    internal fun csvEscape(v: String): String = ExportFormat.csvEscape(v)

    private fun csvRow(e: PrivacyEvent): String = buildString {
        append(e.ts)
        append(',').append(csvEscape(isoTime(e.ts)))
        append(',').append(e.category.id)
        append(',').append(csvEscape(e.subType))
        append(',').append(e.uid)
        append(',').append(e.pid ?: "")
        append(',').append(csvEscape(e.packageName ?: ""))
        append(',').append(csvEscape(e.appLabel ?: ""))
        append(',').append(csvEscape(e.target ?: ""))
        append(',').append(csvEscape(e.detail ?: ""))
        append(',').append(e.source.name)
        append(',').append(e.confidence)
        append(',').append(e.durationMs ?: "")
        append(',').append(csvEscape(e.direction ?: ""))
    }

    private fun eventJson(e: PrivacyEvent): org.json.JSONObject = org.json.JSONObject().apply {
        put("ts", e.ts)
        put("time", isoTime(e.ts))
        put("category", e.category.id)
        put("sub_type", e.subType)
        put("uid", e.uid)
        if (e.pid != null) put("pid", e.pid) else put("pid", org.json.JSONObject.NULL)
        put("package", e.packageName ?: org.json.JSONObject.NULL)
        put("label", e.appLabel ?: org.json.JSONObject.NULL)
        put("target", e.target ?: org.json.JSONObject.NULL)
        put("detail", e.detail ?: org.json.JSONObject.NULL)
        put("source", e.source.name)
        put("confidence", e.confidence)
        if (e.durationMs != null) put("duration_ms", e.durationMs) else put("duration_ms", org.json.JSONObject.NULL)
        put("direction", e.direction ?: org.json.JSONObject.NULL)
    }

    private fun isoTime(ts: Long): String = ExportFormat.isoTime(ts)

    private fun now(): Long = System.currentTimeMillis()

    private fun dayStart(now: Long = now()): Long = dayStartOf(now)

    private fun dayStartOf(ts: Long): Long =
        Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate()
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun retentionCutoff(s: AppSettings): Long =
        now() - s.retentionDays.coerceAtLeast(1) * 86_400_000L

    companion object {
        private const val TAG = "SqliteRepo"
        private const val DB_NAME = "privprobe.db"

        /** v4：调用者聚合按 (uid, package) 拆分；逐版本迁移保留历史数据（P1-6）。 */
        private const val DB_VERSION = 4

        /** 1.1.0 之前的包名；改名后旧自检事件仍可能留在库里，统一过滤。 */
        private const val LEGACY_SELF_PACKAGE = "dev.popsicle.privradar"

        private const val DB_PREFS = "privprobe_db"
        private const val KEY_AUTO_VACUUM_DONE = "auto_vacuum_incremental_done"

        /** 共享 ticker 周期 / 无订阅者后停表延迟 / 写入版本 sample（性能 review：4s→30s / 800ms→2s）。 */
        private const val TICK_MS = 30_000L
        private const val TICK_STOP_MS = 5_000L
        private const val VERSION_SAMPLE_MS = 2_000L

        private const val RECENT_LIMIT = 60
        private const val CALLER_EVENT_LIMIT = 120
        private const val TARGET_EVENT_LIMIT = 120

        /** 7 天保留之外的硬上限（行数 / 文件字节），防止异常场景把库撑到几百 MB。 */
        private const val MAX_ROWS = 300_000L
        private const val MAX_DB_BYTES = 150L * 1024 * 1024

        /** 维护频率与 WAL checkpoint / VACUUM 兜底间隔。 */
        private const val PURGE_INTERVAL_MS = 30 * 60_000L
        private const val WAL_CHECKPOINT_INTERVAL_MS = 10 * 60_000L
        private const val VACUUM_INTERVAL_MS = 24 * 60 * 60_000L
        private const val VACUUM_MIN_DELETED = 20_000
        private const val WAL_AUTOCHECKPOINT_PAGES = 512
        private const val DOMAIN_COUNT_TTL_MS = 30_000L

        /** PRAGMA auto_vacuum 返回值：0=NONE 1=FULL 2=INCREMENTAL。 */
        private const val AUTO_VACUUM_INCREMENTAL = 2

        /** 事件列顺序与 [readEvent] 一一对应，导出 / 详情共用。 */
        private const val SELECT_EVENTS =
            "SELECT ts,category,sub_type,uid,pid,package,label,target,detail,source," +
                "confidence,duration,direction FROM events"

        /** 时间线额外带 id（第 14 列）作为 (ts,id) 游标，供 [timelineForCategory] 翻页。 */
        private const val SELECT_TIMELINE =
            "SELECT ts,category,sub_type,uid,pid,package,label,target,detail,source," +
                "confidence,duration,direction,id FROM events"

        private val CREATE_EVENTS = """
            CREATE TABLE events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                day INTEGER NOT NULL,
                category TEXT NOT NULL,
                sub_type TEXT NOT NULL,
                uid INTEGER NOT NULL,
                pid INTEGER,
                package TEXT,
                label TEXT,
                target TEXT,
                detail TEXT,
                source TEXT NOT NULL,
                source_rank INTEGER NOT NULL DEFAULT 0,
                confidence INTEGER NOT NULL DEFAULT 70,
                duration INTEGER,
                direction TEXT,
                is_system INTEGER NOT NULL DEFAULT 0,
                dedupe_key TEXT NOT NULL UNIQUE
            )
        """.trimIndent()

        /**
         * UPSERT：同 dedupe_key 只有证据更强的来源能覆盖 source/confidence/detail；
         * 低优先级重复直接忽略（WHERE 不成立 → 无写入，触发器也不会自增聚合）。
         */
        private val UPSERT_EVENT = """
            INSERT INTO events
            (ts, day, category, sub_type, uid, pid, package, label, target, detail,
             source, source_rank, confidence, duration, direction, is_system, dedupe_key)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(dedupe_key) DO UPDATE SET
                source = excluded.source,
                source_rank = excluded.source_rank,
                confidence = excluded.confidence,
                detail = excluded.detail,
                package = COALESCE(package, excluded.package),
                label = COALESCE(label, excluded.label),
                pid = COALESCE(pid, excluded.pid)
            WHERE excluded.source_rank > source_rank
        """.trimIndent()
    }
}
