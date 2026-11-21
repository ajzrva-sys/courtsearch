package local.courtsearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ParseTest {
    @Test
    fun namesFollowTheServerRules() {
        assertEquals("Wooden", cleanName("  Wooden "))
        assertEquals("Mary Ann", cleanName("Mary   Ann"))
        assertEquals("O'Brien", cleanName("O'Brien"))
        assertEquals("Anne-Marie", cleanName("Anne-Marie"))
        assertEquals("", cleanName("Wood3n"))
        assertEquals("", cleanName(""))
        assertEquals("A", cleanName("A", initial = true))
        assertEquals("", cleanName("AB", initial = true))
        assertEquals("A" + "b".repeat(39), cleanName("A" + "b".repeat(39)))
        assertEquals("", cleanName("A" + "b".repeat(40)))
    }

    @Test
    fun pdfNamesStaySafe() {
        assertEquals("Accomack-GV26002576-00.pdf", safeFileName("Accomack GV26002576-00.pdf"))
        assertEquals("download.pdf", safeFileName("***"))
        assertEquals("results.pdf", filenameFrom(null, "results.pdf"))
        assertEquals("Wooden.pdf", filenameFrom("attachment; filename=\"Wooden.pdf\"", "results.pdf"))
        assertEquals("a b.pdf", filenameFrom("attachment; filename*=UTF-8''a%20b.pdf", "results.pdf"))
    }

    @Test
    fun aSearchFromYesterdayIsNotToday() {
        val zone = ZoneId.of("America/New_York")
        val earlier = ZonedDateTime.of(2026, 11, 18, 8, 15, 0, 0, zone).toInstant().toEpochMilli()
        val later = ZonedDateTime.of(2026, 11, 18, 16, 0, 0, 0, zone).toInstant().toEpochMilli()
        val stale = ZonedDateTime.of(2026, 11, 14, 8, 29, 4, 0, zone).toInstant().toEpochMilli()
        assertTrue(savedOnCourtDay(earlier, later))
        assertFalse(savedOnCourtDay(stale, later))
        assertEquals("WOODEN|JACQUELINE|", searchKey("Wooden", "Jacqueline", ""))
        assertEquals("20261118", hearingKey("11/18/2026"))
        assertEquals("", hearingKey("soon"))
    }

    @Test
    fun savedNamesKeepTypedCaseAndRepairAllCaps() {
        assertEquals("Andrew Zimmerman", savedDisplayName("Andrew", "", "Zimmerman"))
        assertEquals("Andrew M Zimmerman", savedDisplayName("ANDREW", "M", "ZIMMERMAN"))
        assertEquals("O'Brien", nameForForm("O'Brien"))
    }
}
