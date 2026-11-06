package dev.popsicle.privprobe.core.data.impl

import dev.popsicle.privprobe.core.model.EventSource
import dev.popsicle.privprobe.core.model.PrivacyCategory
import dev.popsicle.privprobe.core.model.PrivacyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-5 去重键的单测：验证逻辑事件键（忽略 pid/detail/毫秒、按 5s 分桶、subType 归一），
 * 以及「不同 target / uid / 类别不能误合并」。
 */
class DedupeKeyTest {

    private fun event(
        ts: Long = 1_000L,
        category: PrivacyCategory = PrivacyCategory.CAMERA,
        subType: String = "camera_connect",
        uid: Int = 10_150,
        target: String? = "camera 0",
        detail: String? = null,
        pid: Int? = 1,
        source: EventSource = EventSource.DUMPSYS,
    ): PrivacyEvent = PrivacyEvent(
        ts = ts,
        category = category,
        subType = subType,
        uid = uid,
        pid = pid,
        target = target,
        detail = detail,
        source = source,
    )

    @Test
    fun sameLogicalEventInSameBucketDedupesAcrossSourcesPidsAndDetails() {
        val a = event(ts = 1_000L, detail = "connect device 0", pid = 111, source = EventSource.DUMPSYS)
        val b = event(ts = 4_999L, detail = "CameraService connect", pid = 222, source = EventSource.LOGCAT)
        assertEquals(DedupeKey.key(a), DedupeKey.key(b))
    }

    @Test
    fun nextTimeBucketIsDistinct() {
        val a = event(ts = 1_000L)
        val b = event(ts = 5_000L)
        assertNotEquals(DedupeKey.key(a), DedupeKey.key(b))
    }

    @Test
    fun differentTargetOrUidOrCategoryIsNotMerged() {
        val base = event()
        assertNotEquals(DedupeKey.key(base), DedupeKey.key(event(target = "camera 1")))
        assertNotEquals(DedupeKey.key(base), DedupeKey.key(event(uid = 10_151)))
        assertNotEquals(DedupeKey.key(base), DedupeKey.key(event(category = PrivacyCategory.MICROPHONE)))
        assertNotEquals(DedupeKey.key(base), DedupeKey.key(event(subType = "camera_disconnect")))
    }

    @Test
    fun subTypeNormalizationStripsSourceSuffix() {
        assertEquals("camera_connect", DedupeKey.normalizeSubType("camera_log_connect"))
        assertEquals("camera_connect", DedupeKey.normalizeSubType("CAMERA_CONNECT"))
        assertEquals("record_start", DedupeKey.normalizeSubType("audio_record_log"))
        assertEquals("clipboard_read", DedupeKey.normalizeSubType("clipboard_read_log"))
        assertEquals("projection_start", DedupeKey.normalizeSubType("projection_log"))
        assertEquals("record_start", DedupeKey.normalizeSubType("record_start"))
        assertEquals("unknown", DedupeKey.normalizeSubType(""))
    }

    @Test
    fun nullTargetUsesDetailFingerprintToAvoidFalseMerge() {
        val a = event(category = PrivacyCategory.APP_ACTIVITY, subType = "process_exec", target = null, detail = "com.foo")
        val b = event(category = PrivacyCategory.APP_ACTIVITY, subType = "process_exec", target = null, detail = "com.bar")
        val c = event(category = PrivacyCategory.APP_ACTIVITY, subType = "process_exec", target = null, detail = "com.foo")
        assertNotEquals(DedupeKey.key(a), DedupeKey.key(b))
        assertEquals(DedupeKey.key(a), DedupeKey.key(c))
    }

    @Test
    fun emptyTargetNormalizationTreatsNullAndBlankSimilarly() {
        val a = event(target = null)
        val b = event(target = "  ")
        assertEquals(DedupeKey.key(a), DedupeKey.key(b))
    }

    @Test
    fun sourceRankPrefersStrongerEvidence() {
        val ranks = listOf(
            EventSource.PACKET,
            EventSource.UPROBE,
            EventSource.KERNEL_TRACE,
            EventSource.DUMPSYS,
            EventSource.APPOPS,
            EventSource.LOGCAT,
            EventSource.SELF_TEST,
            EventSource.HEURISTIC,
        ).map { DedupeKey.sourceRank(it) }
        assertTrue("source rank 必须严格递减: $ranks", ranks.zipWithNext().all { (a, b) -> a > b })
    }
}
