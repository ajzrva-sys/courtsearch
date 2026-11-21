package local.courtsearch

import android.app.Application
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json

const val SCREENING_TYPES = "Unlawful Detainer|Garnishment|Warrant In Debt"

class SearchController(app: Application) {
    private val json = Json { ignoreUnknownKeys = true }
    val courts: List<Court> = loadCourts(app)
    val engine = SearchEngine(courts)
    val store = CaseStore(app)

    private val _state = MutableStateFlow(
        UiState(
            booted = true,
            courtCount = courts.size,
            error = if (courts.isEmpty()) "The court list is missing from the app." else "",
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun updateLast(value: String) = _state.update { it.copy(lastName = value) }
    fun updateFirst(value: String) = _state.update { it.copy(firstName = value) }
    fun updateMiddle(value: String) = _state.update { it.copy(middle = value.take(1)) }
    fun updateRefresh(value: Boolean) = _state.update { it.copy(refresh = value) }
    fun updateScreeningOnly(value: Boolean) = _state.update { it.copy(screeningOnly = value, pdfMessage = "") }
    fun setPrinting(value: Boolean) = _state.update { it.copy(printing = value) }
    fun setPdfMessage(value: String) = _state.update { it.copy(pdfMessage = value) }

    fun cached(last: String, first: String, middle: String): SearchResults? = store.getSearch(last, first, middle)

    fun publish(results: SearchResults) {
        _state.update {
            it.copy(
                phase = Phase.Done,
                results = results,
                error = "",
                refresh = false,
                finishedGeneration = it.finishedGeneration + 1,
            )
        }
    }

    fun beginRun() {
        _state.update {
            it.copy(
                phase = Phase.Running,
                results = null,
                error = "",
                pdfMessage = "",
                progressDone = 0,
                progressTotal = engine.queryCount.coerceAtLeast(1),
                lastCourt = "",
                refresh = false,
            )
        }
    }

    suspend fun run(last: String, first: String, middle: String, onProgress: (Int, Int, String) -> Unit) {
        beginRun()
        val results = engine.search(last, first, middle) { done, total, court ->
            _state.update { it.copy(progressDone = done, progressTotal = total, lastCourt = court, error = "") }
            onProgress(done, total, court)
        }
        val savedAt = System.currentTimeMillis()
        store.storeCases(results.cases)
        store.storeSearch(last, first, middle, results, savedAt)
        publish(results.copy(cachedAt = savedAt / 1000.0))
    }

    fun markStopped() {
        _state.update { current ->
            if (current.phase != Phase.Running) current
            else current.copy(phase = Phase.Idle, error = "Search stopped.")
        }
    }

    fun fail(message: String) {
        _state.update { it.copy(phase = Phase.Failed, error = message) }
    }

    private fun loadCourts(app: Application): List<Court> {
        return try {
            val text = app.assets.open("courts.json").bufferedReader().use { it.readText() }
            json.decodeFromString<List<Court>>(text).filter { it.fips.isNotBlank() && it.name.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

enum class Phase { Idle, Running, Done, Failed }

data class UiState(
    val booted: Boolean = false,
    val courtCount: Int? = null,
    val screeningTypes: Set<String> = SCREENING_TYPES.split("|").toSet(),
    val lastName: String = "",
    val firstName: String = "",
    val middle: String = "",
    val refresh: Boolean = false,
    val phase: Phase = Phase.Idle,
    val progressDone: Int = 0,
    val progressTotal: Int = 1,
    val lastCourt: String = "",
    val error: String = "",
    val results: SearchResults? = null,
    val screeningOnly: Boolean = false,
    val printing: Boolean = false,
    val pdfMessage: String = "",
    val finishedGeneration: Int = 0,
)

data class CaseUi(
    val loading: Boolean = false,
    val record: CaseRecord? = null,
    val error: String = "",
    val printing: Boolean = false,
    val pdfMessage: String = "",
)

sealed interface UiEvent {
    data object ShowResults : UiEvent
}

fun UiState.shownCases(): List<CaseRecord> {
    val rows = results?.cases ?: return emptyList()
    if (!screeningOnly) return rows
    return rows.filter { it.caseType in screeningTypes }
}
