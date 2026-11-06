package dev.popsicle.privprobe.core.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun patchVersionIsNewer() {
        assertTrue(isNewerVersion("1.1.1", "1.1.0"))
        assertFalse(isNewerVersion("1.1.0", "1.1.1"))
    }

    @Test
    fun minorAndMajorVersionAreNewer() {
        assertTrue(isNewerVersion("1.2.0", "1.1.9"))
        assertTrue(isNewerVersion("2.0.0", "1.99.99"))
        assertFalse(isNewerVersion("1.9.9", "2.0.0"))
    }

    @Test
    fun sameVersionIsNotNewer() {
        assertFalse(isNewerVersion("1.1.0", "1.1.0"))
        assertFalse(isNewerVersion("v1.1.0", "1.1.0"))
    }

    @Test
    fun releaseNotesAreShownAsPlainText() {
        val notes = "## PrivacyProbe 1.2.1\n\n- 修复弹窗展示\n- `mapping.txt` 说明\n\n---\n> 仅限授权设备"
        val cleaned = cleanupReleaseNotes(notes)
        assert(cleaned != null)
        assert(!cleaned!!.contains("#"))
        assert(!cleaned.contains("`"))
        assert(!cleaned.contains("---"))
        assert(cleaned.contains("PrivacyProbe 1.2.1"))
        assert(cleaned.contains("mapping.txt"))
        assert(cleanupReleaseNotes("   ") == null)
    }

    @Test
    fun handlesMissingSegmentsAndSuffixes() {
        assertTrue(isNewerVersion("1.2", "1.1.9"))
        assertFalse(isNewerVersion("1.2.0", "1.2"))
        assertTrue(isNewerVersion("1.2.1", "1.2.0-rc1"))
        assertFalse(isNewerVersion("v1.1", "1.1.0"))
    }
}
