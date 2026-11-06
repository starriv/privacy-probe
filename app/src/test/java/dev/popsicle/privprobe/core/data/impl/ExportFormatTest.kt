package dev.popsicle.privprobe.core.data.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 导出格式单测：CSV 转义 + 公式注入防护（P2-6）。 */
class ExportFormatTest {

    @Test
    fun escapesCommaQuoteAndNewline() {
        assertEquals("\"a,b\"", ExportFormat.csvEscape("a,b"))
        assertEquals("\"a\"\"b\"", ExportFormat.csvEscape("a\"b"))
        assertEquals("\"a\nb\"", ExportFormat.csvEscape("a\nb"))
        assertEquals("plain", ExportFormat.csvEscape("plain"))
        assertEquals("", ExportFormat.csvEscape(""))
    }

    @Test
    fun prefixesFormulaInjectionPayloads() {
        assertEquals("'=1+1", ExportFormat.csvEscape("=1+1"))
        assertEquals("'+cmd", ExportFormat.csvEscape("+cmd"))
        assertEquals("'-2+3", ExportFormat.csvEscape("-2+3"))
        assertEquals("'@SUM(A1)", ExportFormat.csvEscape("@SUM(A1)"))
        // 需要包引号的公式注入同样先生成前缀
        assertEquals("\"'=HYPERLINK(1,2)\"", ExportFormat.csvEscape("=HYPERLINK(1,2)"))
    }

    @Test
    fun isoTimeIsStableFormat() {
        val text = ExportFormat.isoTime(1_760_000_000_000L)
        assertEquals(23, text.length)
        assertTrue(text[4] == '-' && text[10] == ' ' && text[13] == ':' && text[16] == ':')
    }
}
