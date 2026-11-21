package local.courtsearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GdcParseTest {
    @Test
    fun civilRowUsesTheHeaderNames() {
        val html = """
            <table class="tableborder">
              <tr><td></td><td>Case Number</td><td>Plaintiff</td><td>Defendant</td><td>Hearing Date</td><td>Hearing Time</td><td>Case Judgment</td><td>Case Type</td></tr>
              <tr>
                <td></td>
                <td><a href="nameSearch.do?formAction=caseDetails">GV26002576-00</a></td>
                <td>Landlord</td><td>Tenant</td><td>11/18/2026</td><td>09:00 AM</td><td>Judgment</td><td>Unlawful Detainer</td>
              </tr>
            </table>
        """.trimIndent()
        val page = GdcPages.parseResults(html, "Accomack General District Court", "001", DIVISION_CIVIL)
        assertNull(page.error)
        assertEquals(1, page.cases.size)
        val case = page.cases.single()
        assertEquals("GV26002576-00", case.caseNumber)
        assertEquals("Landlord", case.plaintiff)
        assertEquals("Tenant", case.defendant)
        assertEquals("Unlawful Detainer", case.caseType)
        assertEquals("11/18/2026", case.hearingDate)
    }

    @Test
    fun criminalRowReadsTheCharge() {
        val html = """
            <table class="tableborder">
              <tr><td></td><td>Case Number</td><td>Defendant</td><td>Hearing Date</td><td>Hearing Time</td><td>Case Judgment</td><td>Charge</td></tr>
              <tr><td></td><td><a href="x">GT26000001-00</a></td><td>Tenant</td><td>01/02/2026</td><td></td><td></td><td>Speeding</td></tr>
            </table>
        """.trimIndent()
        val case = GdcPages.parseResults(html, "Court", "510", DIVISION_CRIMINAL).cases.single()
        assertEquals("Speeding", case.caseType)
        assertEquals("", case.plaintiff)
        assertEquals("Tenant", case.defendant)
    }

    @Test
    fun noCasesIsNotAFailureAndARealErrorIs() {
        val none = """<div id="searchError">No records found</div>"""
        assertNull(GdcPages.parseResults(none, "Court", "001", DIVISION_CIVIL).error)
        val bad = """<div id="searchError">Please select a court</div>"""
        assertEquals("Please select a court", GdcPages.parseResults(bad, "Court", "001", DIVISION_CIVIL).error)
    }

    @Test
    fun nextPageUsesThePortalForm() {
        val html = """
            <form action="nameSearch.do">
              <input name="firstRowCaseNumber" value="GV1">
              <input type="submit" value="Next">
            </form>
        """.trimIndent()
        val next = GdcPages.parseResults(html, "Court", "001", DIVISION_CIVIL).next
        assertEquals("next", next?.get("formAction"))
        assertEquals("true", next?.get("forward"))
        assertEquals("GV1", next?.get("firstRowCaseNumber"))
    }

    @Test
    fun detailDropsDateOfBirthGenderAndRace() {
        val html = """
            <table>
              <tr><td>Case Number:</td><td>GV26002576-00</td><td>Date of Birth:</td><td>01/01/1990</td></tr>
              <tr><td>Gender:</td><td>F</td><td>Race:</td><td>U</td></tr>
            </table>
            <table>
              <tr><td>Hearing Date</td><td>Hearing Time</td></tr>
              <tr><td>11/18/2026</td><td>09:00 AM</td></tr>
            </table>
        """.trimIndent()
        val detail = GdcPages.parseDetail(html)
        assertEquals("GV26002576-00", detail.fields["Case Number"])
        assertTrue(detail.fields.keys.none { it.lowercase() in setOf("date of birth", "gender", "race") })
        assertEquals("11/18/2026", detail.tables.single().rows.single()["Hearing Date"])
    }
}
