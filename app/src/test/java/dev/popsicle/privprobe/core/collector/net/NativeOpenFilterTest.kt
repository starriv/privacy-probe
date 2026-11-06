package dev.popsicle.privprobe.core.collector.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备打开事件只认应用进程：系统媒体服务（cameraserver/audioserver）的打开既无法归因，
 * 也会被相机服务探针自身的 dumpsys 轮询触发（1.x 实测关闭探针即消失）。
 */
class NativeOpenFilterTest {

    @Test
    fun systemMediaServiceOpensAreIgnored() {
        assertFalse(NativeOpenFilter.shouldRecord(1047)) // cameraserver
        assertFalse(NativeOpenFilter.shouldRecord(1041)) // audioserver
        assertFalse(NativeOpenFilter.shouldRecord(1000)) // system 共享 uid
        assertFalse(NativeOpenFilter.shouldRecord(-1)) // 未归因
    }

    @Test
    fun appOpensAreRecorded() {
        assertTrue(NativeOpenFilter.shouldRecord(10000))
        assertTrue(NativeOpenFilter.shouldRecord(10377))
        assertTrue(NativeOpenFilter.shouldRecord(10870))
    }
}
