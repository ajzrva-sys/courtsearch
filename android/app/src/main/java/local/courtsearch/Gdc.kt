package local.courtsearch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

const val DIVISION_CIVIL = "V"
const val DIVISION_CRIMINAL = "T"
private val DIVISIONS = listOf(DIVISION_CIVIL, DIVISION_CRIMINAL)
private val DIVISION_LABEL = mapOf(DIVISION_CIVIL to "Civil", DIVISION_CRIMINAL to "Criminal/Traffic")
private const val STATUS_ALL = "O"
private const val PAGE_SIZE = 20
private const val MAX_PAGES = 3
private const val WORKERS = 8
private const val BASE = "https://eapps.courts.state.va.us/gdcourts"
private const val USER_AGENT = "tenant-screen/0.1 (single-applicant court lookup; +contact on request)"
private val OMIT_FIELDS = setOf("dob", "date of birth", "gender", "race", "aka", "aka1", "aka2")

class PortalError(message: String) : Exception(message)

data class ParsedCase(
    val court: String,
    val fips: String,
    val division: String,
    val caseNumber: String,
    val plaintiff: String,
    val defendant: String,
    val hearingDate: String,
    val hearingTime: String,
    val judgment: String,
    val caseType: String,
)

data class CourtResult(
    val court: String,
    val fips: String,
    val division: String,
    val cases: List<ParsedCase> = emptyList(),
    val error: String = "",
)

data class ParsedPage(
    val cases: List<ParsedCase> = emptyList(),
    val next: Map<String, String>? = null,
    val error: String? = null,
)

fun divisionCode(label: String): String = if (label == "Criminal/Traffic") DIVISION_CRIMINAL else DIVISION_CIVIL

fun divisionLabel(code: String): String = DIVISION_LABEL[code] ?: code

/** Reads the portal's result and detail HTML the same way the Python client does. */
object GdcPages {
    fun parseResults(html: String, court: String, fips: String, division: String): ParsedPage {
        if (isTermsPage(html)) return ParsedPage(error = "session expired; terms must be accepted again")
        val error = searchError(html)
        if (error != null) return ParsedPage(error = error)
        val soup = Jsoup.parse(html)
        val next = nextPagePayload(soup)
        val table = soup.selectFirst("table.tableborder") ?: return ParsedPage(next = next)
        val headerRow = table.selectFirst("tr") ?: return ParsedPage()
        val header = headerRow.select("td, th").map { cellText(it).lowercase() }
        val cases = mutableListOf<ParsedCase>()
        for (row in table.select("tr")) {
            val cells = row.select("td")
            val link = row.selectFirst("a") ?: continue
            if (cells.size < 6) continue
            val caseNumber = cellText(cells[1])
            if (!caseNumber.matches(Regex("G[A-Z]\\d.*"))) continue
            val columns = header.zip(cells.map { cellText(it) }).toMap().filterKeys { it.isNotEmpty() }
            cases.add(
                ParsedCase(
                    court = court,
                    fips = fips,
                    division = division,
                    caseNumber = caseNumber,
                    plaintiff = columns["plaintiff"].orEmpty(),
                    defendant = columns["defendant"].orEmpty(),
                    hearingDate = columns["hearing date"].orEmpty(),
                    hearingTime = columns["hearing time"].orEmpty(),
                    judgment = columns["case judgment"].orEmpty(),
                    caseType = columns["case type"] ?: columns["charge"].orEmpty(),
                ),
            )
        }
        return ParsedPage(cases = cases, next = next)
    }

    fun parseDetail(html: String): CaseDetail {
        if (isTermsPage(html)) throw PortalError("session expired; terms must be accepted again")
        val fields = linkedMapOf<String, String>()
        val tables = mutableListOf<DetailTable>()
        for (table in Jsoup.parse(html).select("table")) {
            if (table.select("table").any { it != table }) continue
            val rows = table.select("tr").map { row ->
                row.children().filter { it.tagName() == "td" }.map { cellText(it) }
            }.filter { it.any { cell -> cell.isNotEmpty() } }
            if (rows.isEmpty()) continue
            val header = rows.first()
            if (isHeader(header) && rows.size > 1) {
                val records = rows.drop(1).filter { it != header && it.any { cell -> cell.isNotEmpty() } }.map { row ->
                    header.mapIndexed { index, name -> name to row.getOrElse(index) { "" } }.toMap()
                }
                if (records.isNotEmpty()) tables.add(DetailTable(header, records))
                continue
            }
            for (row in rows) {
                var index = 0
                while (index < row.size - 1) {
                    val raw = row[index]
                    if (!raw.trimEnd().endsWith(":")) {
                        index += 2
                        continue
                    }
                    val label = raw.replace('\u00a0', ' ').trim().trimEnd(':').trim()
                    val value = row[index + 1].trim()
                    if (label.isNotEmpty() && label.lowercase() !in OMIT_FIELDS && label !in fields) {
                        fields[label] = value
                    }
                    index += 2
                }
            }
        }
        return CaseDetail(fields, tables)
    }

    private fun isTermsPage(html: String): Boolean {
        return "Terms and Conditions of Use" in html && ("name=\"accept\"" in html || "name='accept'" in html)
    }

    private fun searchError(html: String): String? {
        val match = Regex("id=\"searchError\"[^>]*>(.*?)</(?:div|span|td)>", RegexOption.DOT_MATCHES_ALL).find(html) ?: return null
        val text = match.groupValues[1].replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty() || Regex("no (cases|records|results)|0 results|not found", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return null
        }
        return text
    }

    private fun nextPagePayload(soup: org.jsoup.nodes.Document): Map<String, String>? {
        val form = soup.select("form").firstOrNull { "nameSearch.do" in it.attr("action") } ?: return null
        val hasNext = form.select("input").any { it.attr("value").equals("Next", ignoreCase = true) }
        if (!hasNext) return null
        val payload = linkedMapOf<String, String>()
        for (input in form.select("input")) {
            val name = input.attr("name")
            if (name.isNotEmpty()) payload[name] = input.attr("value")
        }
        payload["formAction"] = "next"
        payload["forward"] = "true"
        return payload
    }

    private fun isHeader(cells: List<String>): Boolean {
        if (cells.size < 2) return false
        if (cells.any { it.endsWith(":") || it.length > 40 }) return false
        return cells.filter { it.isNotEmpty() }.all { it.first().isLetter() }
    }

    private fun cellText(cell: Element): String = cell.text().replace(Regex("\\s+"), " ").trim()
}

private class MemoryCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(this.cookies) {
            for (cookie in cookies) {
                this.cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                this.cookies.add(cookie)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        return synchronized(cookies) { cookies.filter { it.matches(url) } }
    }
}

/** One accepted-terms session. Each worker keeps its own, because the portal stores the court on the session. */
class GdcSession {
    private val client = OkHttpClient.Builder()
        .cookieJar(MemoryCookieJar())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
    private var accepted = false
    private var currentFips = ""
    private var currentDivision = ""

    suspend fun searchName(court: String, fips: String, division: String, last: String, first: String, middle: String): CourtResult {
        val found = mutableListOf<ParsedCase>()
        return try {
            ensure()
            currentFips = fips
            currentDivision = division
            var html = submitSearch(last, first, middle)
            var pages = 0
            while (pages < MAX_PAGES) {
                val parsed = GdcPages.parseResults(html, court, fips, division)
                if (parsed.error != null) {
                    if (parsed.error.startsWith("session expired")) accepted = false
                    throw PortalError(parsed.error)
                }
                found += parsed.cases
                pages += 1
                val next = parsed.next
                if (next == null || parsed.cases.size < PAGE_SIZE) break
                html = post("$BASE/nameSearch.do", next)
            }
            CourtResult(court, fips, division, found)
        } catch (exc: CancellationException) {
            throw exc
        } catch (exc: Exception) {
            CourtResult(court, fips, division, found, exc.message ?: "The court portal did not answer.")
        }
    }

    suspend fun fetchDetail(court: String, fips: String, caseNumber: String, division: String): CaseDetail {
        selectCourt(court, fips, division)
        val html = get(
            "$BASE/nameSearch.do",
            mapOf(
                "formAction" to "caseDetails",
                "displayCaseNumber" to caseNumber,
                "localFipsCode" to fips,
                "caseActive" to "true",
            ),
        )
        if ("Case Number" !in html) throw PortalError("The court portal did not return a case detail page.")
        return GdcPages.parseDetail(html)
    }

    fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private suspend fun ensure() {
        if (!accepted) acceptTerms()
    }

    private suspend fun acceptTerms() {
        get("$BASE/landing.do")
        val acceptedPage = post("$BASE/landing.do", mapOf("accept" to "Accept"), referer = "$BASE/landing.do")
        if ("Terms and Conditions of Use" in acceptedPage && ("name=\"accept\"" in acceptedPage || "name='accept'" in acceptedPage)) {
            throw PortalError("The court portal did not accept the terms acknowledgement.")
        }
        accepted = true
    }

    private suspend fun selectCourt(court: String, fips: String, division: String) {
        ensure()
        get(
            "$BASE/nameSearch.do",
            mapOf(
                "fromSidebar" to "true",
                "formAction" to "searchLanding",
                "searchDivision" to division,
                "searchFipsCode" to fips,
                "localFipsCode" to fips,
            ),
        )
        post(
            "$BASE/changeCourt.do",
            mapOf(
                "selectedCourtsName" to court,
                "selectedCourtsFipCode" to fips,
                "sessionCourtsFipCode" to "",
            ),
        )
        currentFips = fips
        currentDivision = division
    }

    private suspend fun submitSearch(last: String, first: String, middle: String): String {
        return post(
            "$BASE/nameSearch.do",
            linkedMapOf(
                "formAction" to "newSearch",
                "displayCaseNumber" to "",
                "formBean" to "",
                "localFipsCode" to currentFips,
                "caseActive" to "",
                "localLastName" to "",
                "forward" to "",
                "back" to "",
                "localnamesearchlastName" to last,
                "lastName" to last,
                "localnamesearchfirstName" to first,
                "firstName" to first,
                "localnamesearchmiddleName" to middle,
                "middleName" to middle,
                "localnamesearchsuffix" to "",
                "suffix" to "",
                "localnamesearchsearchCategory" to STATUS_ALL,
                "searchCategory" to STATUS_ALL,
                "searchFipsCode" to currentFips,
                "searchDivision" to currentDivision,
                "searchType" to "name",
                "clientSearchCounter" to "1",
                "firstRowName" to "",
                "firstRowCaseNumber" to "",
                "lastRowName" to "",
                "lastRowCaseNumber" to "",
            ),
        )
    }

    private suspend fun get(url: String, query: Map<String, String> = emptyMap()): String {
        val parsed = url.toHttpUrlOrNull() ?: throw PortalError("The court portal address is not valid.")
        val built = parsed.newBuilder().apply { query.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
        return execute(Request.Builder().url(built).header("User-Agent", USER_AGENT).build())
    }

    private suspend fun post(url: String, fields: Map<String, String>, referer: String = "$BASE/nameSearch.do"): String {
        val body = FormBody.Builder().apply { fields.forEach { (key, value) -> add(key, value) } }.build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .post(body)
            .build()
        return execute(request)
    }

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            try {
                call.execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!continuation.isActive) return@use
                    if (!response.isSuccessful) {
                        continuation.resumeWithException(PortalError("The court portal returned ${response.code}."))
                    } else if (text.isEmpty()) {
                        continuation.resumeWithException(PortalError("The court portal returned an empty response."))
                    } else {
                        continuation.resume(text)
                    }
                }
            } catch (thrown: Throwable) {
                if (!continuation.isActive) return@suspendCancellableCoroutine
                if (call.isCanceled()) {
                    continuation.resumeWithException(CancellationException("Search stopped.", thrown))
                } else {
                    continuation.resumeWithException(thrown)
                }
            }
        }
    }
}

class SearchEngine(private val courts: List<Court>) {
    val queryCount: Int get() = courts.size * DIVISIONS.size

    suspend fun search(
        last: String,
        first: String,
        middle: String,
        onProgress: (done: Int, total: Int, court: String) -> Unit,
    ): SearchResults {
        val started = System.currentTimeMillis()
        val jobs = courts.flatMap { court -> DIVISIONS.map { division -> court to division } }
        val total = jobs.size
        val results = searchCourts(jobs, last, first, middle) { done, court ->
            onProgress(done, total, court)
        }
        val searchedName = listOf(first, middle, last).filter { it.isNotBlank() }.joinToString(" ")
        val cases = results.flatMap { result -> result.cases.map { it.toRecord(searchedName) } }
            .sortedByDescending { hearingKey(it.hearingDate) }
        val seconds = kotlin.math.round((System.currentTimeMillis() - started) / 100.0) / 10.0
        return SearchResults(summary = summarize(results, cases), cases = cases, seconds = seconds, fromCache = false)
    }

    suspend fun fetchDetail(court: String, fips: String, caseNumber: String, divisionLabel: String): CaseDetail {
        val session = GdcSession()
        try {
            return session.fetchDetail(court, fips, caseNumber, divisionCode(divisionLabel))
        } finally {
            session.close()
        }
    }

    private suspend fun searchCourts(
        jobs: List<Pair<Court, String>>,
        last: String,
        first: String,
        middle: String,
        onProgress: (done: Int, court: String) -> Unit,
    ): List<CourtResult> = coroutineScope {
        val pool = Channel<GdcSession>(WORKERS)
        val opened = Collections.synchronizedList(mutableListOf<GdcSession>())
        repeat(WORKERS) {
            val session = GdcSession()
            opened.add(session)
            pool.send(session)
        }
        val done = AtomicInteger()
        try {
            jobs.map { (court, division) ->
                async(Dispatchers.IO) {
                    var session = pool.receive()
                    var result = session.searchName(court.name, court.fips, division, last, first, middle)
                    if (result.error.isNotEmpty()) {
                        session.close()
                        session = GdcSession().also { opened.add(it) }
                        result = session.searchName(court.name, court.fips, division, last, first, middle)
                    }
                    pool.send(session)
                    onProgress(done.incrementAndGet(), result.court)
                    result
                }
            }.awaitAll().sortedWith(compareBy({ it.court }, { it.division }))
        } finally {
            pool.close()
            val sessions = synchronized(opened) { opened.toList() }
            sessions.forEach { runCatching { it.close() } }
        }
    }
}

private fun ParsedCase.toRecord(searchedName: String) = CaseRecord(
    fips = fips,
    caseNumber = caseNumber,
    court = court,
    division = divisionLabel(division),
    plaintiff = plaintiff,
    defendant = defendant,
    hearingDate = hearingDate,
    hearingTime = hearingTime,
    judgment = judgment,
    caseType = caseType,
    searchedName = searchedName,
)

private fun summarize(results: List<CourtResult>, cases: List<CaseRecord>): Summary {
    val failures = results.filter { it.error.isNotEmpty() }
    val byType = cases.groupingBy { it.caseType.ifBlank { "(unspecified)" } }.eachCount()
        .entries.sortedByDescending { it.value }
        .associate { it.key to it.value }
    return Summary(
        courtsSearched = results.map { it.fips }.toSet().size,
        cases = cases.size,
        courtsWithCases = results.filter { it.cases.isNotEmpty() }.map { it.fips }.toSet().size,
        failedQueries = failures.size,
        failures = failures.map { Failure(it.court, divisionLabel(it.division), it.error) },
        byCaseType = byType,
    )
}

