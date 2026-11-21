package local.courtsearch

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Court(val name: String, val fips: String)

@Serializable
data class SearchResults(
    val summary: Summary = Summary(),
    val cases: List<CaseRecord> = emptyList(),
    val seconds: Double = 0.0,
    @SerialName("cached_at") val cachedAt: Double? = null,
    @SerialName("from_cache") val fromCache: Boolean = false,
)

@Serializable
data class Summary(
    @SerialName("courts_searched") val courtsSearched: Int = 0,
    val cases: Int = 0,
    @SerialName("courts_with_cases") val courtsWithCases: Int = 0,
    @SerialName("failed_queries") val failedQueries: Int = 0,
    val failures: List<Failure> = emptyList(),
    @SerialName("by_case_type") val byCaseType: Map<String, Int> = emptyMap(),
)

@Serializable
data class Failure(
    val court: String = "",
    val division: String = "",
    val error: String = "",
)

@Serializable
data class CaseRecord(
    val fips: String = "",
    @SerialName("case_number") val caseNumber: String = "",
    val court: String = "",
    val division: String = "",
    val plaintiff: String = "",
    val defendant: String = "",
    @SerialName("hearing_date") val hearingDate: String = "",
    @SerialName("hearing_time") val hearingTime: String = "",
    val judgment: String = "",
    @SerialName("case_type") val caseType: String = "",
    @SerialName("searched_name") val searchedName: String = "",
    val detail: CaseDetail? = null,
    @SerialName("detail_error") val detailError: String = "",
)

@Serializable
data class CaseDetail(
    val fields: Map<String, String> = emptyMap(),
    val tables: List<DetailTable> = emptyList(),
)

@Serializable
data class DetailTable(
    val columns: List<String> = emptyList(),
    val rows: List<Map<String, String>> = emptyList(),
)
