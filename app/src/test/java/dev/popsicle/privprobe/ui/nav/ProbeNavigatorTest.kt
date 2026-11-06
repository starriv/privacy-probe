package dev.popsicle.privprobe.ui.nav

import dev.popsicle.privprobe.core.model.PrivacyCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页面栈 id 契约：UI 状态（列表滚动位置、分段 tab）按 [NavEntry.id] 保存，
 * 因此 id 必须唯一、稳定、不复用，否则会丢状态或把旧页面的状态串到新页面。
 */
class ProbeNavigatorTest {

    @Test
    fun rootEntryKeepsFixedId() {
        val nav = ProbeNavigator()
        assertEquals(ProbeNavigator.DASHBOARD_ID, nav.currentEntry.id)
        assertEquals(Screen.Dashboard, nav.current)
        assertFalse(nav.canGoBack)
    }

    @Test
    fun switchTabKeepsRootIds() {
        val nav = ProbeNavigator()
        nav.push(Screen.Category(PrivacyCategory.NETWORK))
        nav.switchTab(Screen.Settings)
        assertEquals(listOf(NavEntry(ProbeNavigator.SETTINGS_ID, Screen.Settings)), nav.entries)
        assertEquals(1, nav.rootTabIndex)
        assertFalse(nav.canGoBack)
        nav.switchTab(Screen.Dashboard)
        assertEquals(ProbeNavigator.DASHBOARD_ID, nav.currentEntry.id)
        assertEquals(0, nav.rootTabIndex)
    }

    @Test
    fun popReturnsToSameEntry() {
        val nav = ProbeNavigator()
        nav.push(Screen.Category(PrivacyCategory.NETWORK))
        val categoryEntry = nav.currentEntry
        nav.push(Screen.Target(PrivacyCategory.NETWORK, "beacons.gvt2.com"))
        assertEquals(3, nav.entries.size)
        assertTrue(nav.pop())
        assertEquals(categoryEntry, nav.currentEntry)
    }

    @Test
    fun reopenedScreenGetsFreshId() {
        val nav = ProbeNavigator()
        nav.push(Screen.Category(PrivacyCategory.NETWORK))
        val firstId = nav.currentEntry.id
        nav.pop()
        nav.push(Screen.Category(PrivacyCategory.NETWORK))
        assertNotEquals(firstId, nav.currentEntry.id)
    }

    @Test
    fun pushedIdsAreUnique() {
        val nav = ProbeNavigator()
        nav.push(Screen.Category(PrivacyCategory.NETWORK))
        nav.push(Screen.Caller(PrivacyCategory.NETWORK, 10135, "com.google.android.gms"))
        nav.push(Screen.Target(PrivacyCategory.NETWORK, "beacons.gvt2.com"))
        assertEquals(nav.entries.size, nav.entries.map { it.id }.toSet().size)
    }

    @Test
    fun rootIdsAreExcludedFromStateCleanup() {
        assertTrue(ProbeNavigator.isRootId(ProbeNavigator.DASHBOARD_ID))
        assertTrue(ProbeNavigator.isRootId(ProbeNavigator.SETTINGS_ID))
        assertFalse(ProbeNavigator.isRootId(ProbeNavigator.SETTINGS_ID + 1))
    }

    @Test
    fun duplicatePushIsIgnored() {
        val nav = ProbeNavigator()
        val screen = Screen.Category(PrivacyCategory.NETWORK)
        nav.push(screen)
        nav.push(screen)
        assertEquals(2, nav.entries.size)
    }
}
