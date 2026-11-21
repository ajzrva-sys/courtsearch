package local.courtsearch

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchViewModel(app: Application) : AndroidViewModel(app) {
    private val controller = (app as CourtSearchApp).search
    val state = controller.state

    private val _case = MutableStateFlow(CaseUi())
    val case = _case.asStateFlow()

    private val eventsChannel = Channel<UiEvent>(Channel.BUFFERED)
    val events = eventsChannel.receiveAsFlow()
    private var caseJob: Job? = null

    init {
        viewModelScope.launch {
            var seen = controller.state.value.finishedGeneration
            controller.state.collect { current ->
                if (current.finishedGeneration > seen && current.phase == Phase.Done && current.results != null) {
                    seen = current.finishedGeneration
                    eventsChannel.trySend(UiEvent.ShowResults)
                }
            }
        }
    }

    fun updateLast(value: String) = controller.updateLast(value)
    fun updateFirst(value: String) = controller.updateFirst(value)
    fun updateMiddle(value: String) = controller.updateMiddle(value)
    fun updateRefresh(value: Boolean) = controller.updateRefresh(value)
    fun updateScreeningOnly(value: Boolean) = controller.updateScreeningOnly(value)

    fun search(context: Context) {
        val snapshot = state.value
        if (snapshot.phase == Phase.Running) return
        val last = cleanName(snapshot.lastName)
        val first = cleanName(snapshot.firstName)
        val middle = cleanName(snapshot.middle, initial = true)
        val problem = when {
            last.isEmpty() -> "Enter a last name using letters only."
            snapshot.firstName.isNotBlank() && first.isEmpty() -> "First name must contain only letters."
            snapshot.middle.isNotBlank() && middle.isEmpty() -> "Middle initial must be a single letter."
            controller.courts.isEmpty() -> "The court list is missing from the app."
            else -> ""
        }
        if (problem.isNotEmpty()) {
            controller.fail(problem)
            return
        }
        controller.updateLast(last)
        controller.updateFirst(first)
        controller.updateMiddle(middle)
        viewModelScope.launch {
            val cached = if (snapshot.refresh) null else withContext(Dispatchers.IO) {
                controller.cached(last, first, middle)
            }
            if (cached != null) {
                controller.publish(cached)
                return@launch
            }
            val intent = Intent(context, SearchService::class.java).apply {
                putExtra(SearchService.EXTRA_LAST, last)
                putExtra(SearchService.EXTRA_FIRST, first)
                putExtra(SearchService.EXTRA_MIDDLE, middle)
            }
            controller.beginRun()
            ContextCompat.startForegroundService(context, intent)
        }
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, SearchService::class.java))
    }

    suspend fun loadHistory(): List<SavedSearch> = withContext(Dispatchers.IO) {
        controller.store.listSearches()
    }

    fun openSaved(id: Long) {
        if (state.value.phase == Phase.Running) return
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { controller.store.loadSearch(id) } ?: return@launch
            controller.updateLast(nameForForm(saved.last))
            controller.updateFirst(nameForForm(saved.first))
            controller.updateMiddle(nameForForm(saved.middle))
            controller.publish(saved.results)
        }
    }

    fun loadCase(fips: String, caseNumber: String) {
        caseJob?.cancel()
        caseJob = viewModelScope.launch {
            try {
                val stored = withContext(Dispatchers.IO) { controller.store.getCase(fips, caseNumber) }
                if (stored == null) {
                    _case.value = CaseUi(error = "That case isn't on this phone.")
                    return@launch
                }
                _case.value = CaseUi(loading = stored.detail == null, record = stored)
                if (stored.detail != null) return@launch
                val detail = withContext(Dispatchers.IO) {
                    controller.engine.fetchDetail(stored.court, stored.fips, stored.caseNumber, stored.division)
                }
                withContext(Dispatchers.IO) { controller.store.storeDetail(fips, caseNumber, detail) }
                _case.value = CaseUi(record = stored.copy(detail = detail))
            } catch (exc: CancellationException) {
                throw exc
            } catch (exc: Exception) {
                Log.e("CourtSearch", "detail $fips $caseNumber", exc)
                val stored = withContext(Dispatchers.IO) { controller.store.getCase(fips, caseNumber) }
                val reason = exc.message?.takeIf { it.isNotBlank() } ?: "The phone couldn't read the case page."
                if (stored != null) {
                    _case.value = CaseUi(record = stored.copy(detailError = reason))
                } else {
                    _case.value = CaseUi(error = reason)
                }
            }
        }
    }

    fun printShownCases() {
        val snapshot = state.value
        val rows = snapshot.shownCases()
        if (rows.isEmpty() || snapshot.printing) return
        viewModelScope.launch {
            controller.setPrinting(true)
            controller.setPdfMessage("")
            try {
                val name = listOf(snapshot.firstName, snapshot.middle, snapshot.lastName)
                    .map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
                val bytes = withContext(Dispatchers.IO) { renderResultsPdf(getApplication(), name, rows) }
                val filename = safeFileName((name.ifBlank { "results" }).replace(Regex("\\s+"), "-") + ".pdf")
                openPdf(getApplication(), bytes, filename)
            } catch (exc: CancellationException) {
                throw exc
            } catch (_: Exception) {
                controller.setPdfMessage("This phone could not open the PDF.")
            } finally {
                controller.setPrinting(false)
            }
        }
    }

    fun printCase() {
        val record = _case.value.record ?: return
        if (_case.value.printing) return
        viewModelScope.launch {
            _case.update { it.copy(printing = true, pdfMessage = "") }
            try {
                val bytes = withContext(Dispatchers.IO) { renderCasePdf(getApplication(), record) }
                openPdf(getApplication(), bytes, safeFileName("${record.caseNumber}.pdf"))
            } catch (exc: CancellationException) {
                throw exc
            } catch (_: Exception) {
                _case.update { it.copy(pdfMessage = "This phone could not open the PDF.") }
            } finally {
                _case.update { it.copy(printing = false) }
            }
        }
    }
}
