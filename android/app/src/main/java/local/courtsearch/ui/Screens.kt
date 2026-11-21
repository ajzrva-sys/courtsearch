package local.courtsearch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import local.courtsearch.CaseRecord
import local.courtsearch.CaseUi
import local.courtsearch.DetailTable
import local.courtsearch.SavedSearch
import local.courtsearch.savedOnCourtDay
import local.courtsearch.Phase
import local.courtsearch.SearchResults
import local.courtsearch.SearchViewModel
import local.courtsearch.UiState
import local.courtsearch.shownCases
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    state: UiState,
    vm: SearchViewModel,
    onResults: () -> Unit,
    onHistory: () -> Unit,
    onPrivacy: () -> Unit,
) {
    val context = LocalContext.current
    val running = state.phase == Phase.Running
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.search(context)
    }
    fun start() {
        val needsNotice = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsNotice) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.search(context)
    }
    Scaffold(
        containerColor = Paper,
        topBar = {
            TopAppBar(
                title = { Text("Virginia court search") },
                actions = {
                    TextButton(onClick = onHistory, enabled = !running) {
                        Text("Previous", color = androidx.compose.ui.graphics.Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy, titleContentColor = androidx.compose.ui.graphics.Color.White),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(subtitle(state), color = Muted, style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = state.lastName,
                onValueChange = vm::updateLast,
                label = { Text("Last name") },
                singleLine = true,
                enabled = !running,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                value = state.firstName,
                onValueChange = vm::updateFirst,
                label = { Text("First name") },
                singleLine = true,
                enabled = !running,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                value = state.middle,
                onValueChange = vm::updateMiddle,
                label = { Text("Middle initial") },
                singleLine = true,
                enabled = !running,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { start() }),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = state.refresh, onCheckedChange = vm::updateRefresh, enabled = !running)
                Text("Full refresh")
            }
            if (running) {
                Button(onClick = { vm.stop(context) }, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
            } else {
                Button(onClick = { start() }, modifier = Modifier.fillMaxWidth()) { Text("Search all courts") }
            }
            if (!running && state.results != null) {
                TextButton(onClick = onResults) { Text("View results") }
            }
            if (state.phase == Phase.Running) {
                val fraction = state.progressDone.toFloat() / state.progressTotal.coerceAtLeast(1)
                LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text(progressLabel(state), color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            if (state.error.isNotBlank()) Text(state.error, color = Danger)
            TextButton(onClick = onPrivacy) { Text("Privacy policy") }
            Text(
                "Results come from the Virginia General District Court Online Case Information System, which only searches one court at a time. This runs that search for every court and combines them. A result saved earlier today is shown again. A search from a previous day checks the courts again, and Full refresh checks them now. Common names match other people; confirm identity against the case detail before relying on a row. The portal's own records are the source of truth, and they are not the official court record.",
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ResultsScreen(state: UiState, vm: SearchViewModel, onBack: () -> Unit, onOpen: (CaseRecord) -> Unit) {
    val results = state.results
    Scaffold(
        containerColor = Paper,
        topBar = {
            TopAppBar(
                title = { Text("Results") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to search")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy, titleContentColor = androidx.compose.ui.graphics.Color.White, navigationIconContentColor = androidx.compose.ui.graphics.Color.White),
            )
        },
    ) { padding ->
        if (results == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No results yet.", color = Muted)
            }
            return@Scaffold
        }
        val rows = state.shownCases()
        var showFailures by rememberSaveable { mutableStateOf(false) }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                val (value, caption) = savedStat(results)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat(results.summary.cases.toString(), "cases found")
                    Stat(results.summary.courtsWithCases.toString(), "courts with cases")
                    Stat(results.summary.courtsSearched.toString(), "courts searched")
                    Stat(results.summary.failedQueries.toString(), "courts that failed")
                    Stat(value, caption)
                }
            }
            if (results.isOlderThanToday()) {
                item {
                    Text(
                        "Saved from an earlier court day. Search again to check for cases filed since.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            item {
                val types = results.summary.byCaseType.entries.sortedByDescending { it.value }.take(8)
                    .joinToString(" · ") { "${it.value} ${it.key}" }
                if (types.isNotBlank()) Text(types, color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            item {
                Button(onClick = vm::printShownCases, enabled = rows.isNotEmpty() && !state.printing) {
                    Text(if (state.printing) "Printing…" else "Print")
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = state.screeningOnly, onCheckedChange = vm::updateScreeningOnly)
                    Text("Show only unlawful detainers, garnishments, and warrants in debt")
                }
            }
            if (state.pdfMessage.isNotBlank()) {
                item { Text(state.pdfMessage, color = Danger) }
            }
            if (results.summary.failures.isNotEmpty()) {
                item {
                    TextButton(onClick = { showFailures = !showFailures }) {
                        Text(if (showFailures) "Hide courts that failed" else "Show courts that failed")
                    }
                }
                if (showFailures) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            results.summary.failures.forEach { failure ->
                                Text(
                                    "${failure.court} (${failure.division}): ${failure.error}",
                                    color = Muted,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
            if (rows.isEmpty()) {
                item { Text("No cases matched.", color = Muted, modifier = Modifier.padding(vertical = 24.dp)) }
            } else {
                items(rows, key = { "${it.fips}/${it.caseNumber}" }) { row ->
                    CaseCard(row, marked = row.caseType in state.screeningTypes, onClick = { onOpen(row) })
                }
                item {
                    Text(
                        "${rows.size} shown. Marked cards are unlawful detainers, garnishments, or warrants in debt.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    Scaffold(
        containerColor = Paper,
        topBar = {
            TopAppBar(
                title = { Text("Privacy policy") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to search")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy, titleContentColor = androidx.compose.ui.graphics.Color.White, navigationIconContentColor = androidx.compose.ui.graphics.Color.White),
            )
        },
    ) { padding ->
        androidx.compose.ui.viewinterop.AndroidView(
            modifier = Modifier.fillMaxSize().padding(padding),
            factory = { host ->
                android.webkit.WebView(host).apply {
                    setBackgroundColor(android.graphics.Color.parseColor("#FAFAF9"))
                    loadUrl("file:///android_asset/privacy-policy.html")
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(vm: SearchViewModel, onBack: () -> Unit) {
    var rows by remember { mutableStateOf<List<SavedSearch>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        rows = vm.loadHistory()
        loaded = true
    }
    Scaffold(
        containerColor = Paper,
        topBar = {
            TopAppBar(
                title = { Text("Previous searches") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to search")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy, titleContentColor = androidx.compose.ui.graphics.Color.White, navigationIconContentColor = androidx.compose.ui.graphics.Color.White),
            )
        },
    ) { padding ->
        when {
            !loaded -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            rows.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text("A finished search is kept here.", color = Muted)
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        "Opening one shows that saved list. Searching the name again checks the courts if the list is from an earlier day.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                items(rows, key = { it.id }) { saved ->
                    Card(
                        onClick = { vm.openSaved(saved.id) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(saved.name, fontWeight = FontWeight.SemiBold)
                            Text(historyLine(saved), color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaseScreen(case: CaseUi, onBack: () -> Unit, onPrint: () -> Unit, onRetry: () -> Unit) {
    Scaffold(
        containerColor = Paper,
        topBar = {
            TopAppBar(
                title = { Text(case.record?.caseNumber ?: "Case") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to results")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy, titleContentColor = androidx.compose.ui.graphics.Color.White, navigationIconContentColor = androidx.compose.ui.graphics.Color.White),
            )
        },
    ) { padding ->
        when {
            case.record == null && case.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            case.record == null -> Column(
                Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(case.error.ifBlank { "Couldn't load the case." }, color = Danger)
                Button(onClick = onRetry) { Text("Try again") }
            }
            else -> {
                val record = case.record
                Column(
                    Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(record.caseType.ifBlank { "Case" }, style = MaterialTheme.typography.headlineSmall)
                    Text(record.court, color = Muted)
                    Text(record.division, color = Muted, style = MaterialTheme.typography.bodySmall)
                    if (record.plaintiff.isNotBlank()) Field("Plaintiff", record.plaintiff)
                    if (record.defendant.isNotBlank()) Field("Defendant", record.defendant)
                    if (record.judgment.isNotBlank()) Field("Judgment", record.judgment)
                    Field("Hearing", hearing(record))
                    if (record.searchedName.isNotBlank()) {
                        Text("Matched ${record.searchedName}", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = onPrint, enabled = !case.printing) {
                        Text(if (case.printing) "Printing…" else "Print")
                    }
                    if (case.pdfMessage.isNotBlank()) Text(case.pdfMessage, color = Danger)
                    if (case.loading) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(modifier = Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                            Text("Loading the case record…", color = Muted)
                        }
                    }
                    if (record.detailError.isNotBlank()) {
                        Section("Case record") {
                            Text("The court portal did not return the rest of this case.", color = Danger)
                            Text(record.detailError, color = Muted, style = MaterialTheme.typography.bodySmall)
                            Button(onClick = onRetry) { Text("Try again") }
                        }
                    }
                    val detail = record.detail
                    if (detail != null) {
                        val fields = detail.fields.filter { (label, value) ->
                            value.isNotBlank() && label.lowercase() !in REDUNDANT_FIELDS
                        }
                        if (fields.isNotEmpty()) {
                            Section("Case record") {
                                fields.forEach { (label, value) -> Field(label, value) }
                            }
                        }
                        val nameTables = detail.tables.filter { it.isPartyTable() }
                        detail.tables.forEach { table ->
                            Section(tableTitle(table, nameTables)) {
                                table.rows.forEachIndexed { index, row ->
                                    if (index > 0) HorizontalDivider(color = Line)
                                    table.columns.forEach { column ->
                                        val value = row[column].orEmpty()
                                        if (value.isNotBlank()) Field(column, value)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CaseCard(row: CaseRecord, marked: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            if (marked) Box(Modifier.width(4.dp).fillMaxHeight().background(Amber))
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(row.caseType.ifBlank { row.caseNumber }, fontWeight = FontWeight.SemiBold)
                Text(row.caseNumber, color = Navy, fontWeight = FontWeight.SemiBold)
                Text(row.court, color = Muted, style = MaterialTheme.typography.bodySmall)
                Text(row.division, color = Muted, style = MaterialTheme.typography.bodySmall)
                if (row.plaintiff.isNotBlank()) Text("Plaintiff: ${row.plaintiff}", style = MaterialTheme.typography.bodySmall)
                if (row.defendant.isNotBlank()) Text("Defendant: ${row.defendant}", style = MaterialTheme.typography.bodySmall)
                if (row.judgment.isNotBlank()) Text(row.judgment, style = MaterialTheme.typography.bodySmall)
                Text(hearing(row), color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Stat(value: String, caption: String) {
    Card(colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge)
            Text(caption, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title.uppercase(), color = Muted, style = MaterialTheme.typography.labelMedium)
            content()
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column {
        Text(label, color = Muted, style = MaterialTheme.typography.bodySmall)
        Text(value.ifBlank { "—" })
    }
}

private fun subtitle(state: UiState): String {
    val courts = state.courtCount?.let { "all $it general district courts" } ?: "every general district court"
    return "One name, searched across $courts. Civil and criminal/traffic."
}

private fun progressLabel(state: UiState): String {
    if (state.progressDone == 0 && state.lastCourt.isBlank()) return "Starting…"
    val where = if (state.lastCourt.isBlank()) "Searching…" else "Searching ${state.lastCourt}"
    return "$where (${state.progressDone} of ${state.progressTotal})"
}

private val REDUNDANT_FIELDS = setOf("case number", "case type")

private fun DetailTable.isPartyTable(): Boolean {
    val names = columns.map { it.lowercase() }
    return "name" in names && "attorney" in names
}

private fun DetailTable.isHearingTable(): Boolean {
    val names = columns.map { it.lowercase() }
    return "hearing type" in names || ("date" in names && "result" in names)
}

private fun tableTitle(table: DetailTable, nameTables: List<DetailTable>): String {
    if (table.isHearingTable()) return "Hearings"
    if (table.isPartyTable()) {
        val index = nameTables.indexOf(table)
        return when {
            nameTables.size == 1 -> "Party"
            index == 0 -> "Plaintiff"
            else -> "Defendant"
        }
    }
    return table.columns.firstOrNull().orEmpty().ifBlank { "Details" }
}

private fun SearchResults.isOlderThanToday(): Boolean {
    if (!fromCache) return false
    val at = cachedAt ?: return false
    return !savedOnCourtDay((at * 1000).toLong(), System.currentTimeMillis())
}

private fun historyLine(saved: SavedSearch): String {
    val whenSaved = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .format(Instant.ofEpochMilli(saved.cachedAtMillis).atZone(ZoneId.systemDefault()))
    val cases = if (saved.caseCount == 1) "1 case" else "${saved.caseCount} cases"
    return "$whenSaved · $cases"
}

private fun hearing(record: CaseRecord): String {
    return listOf(record.hearingDate, record.hearingTime).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "—" }
}

private fun savedStat(results: SearchResults): Pair<String, String> {
    if (!results.fromCache) {
        val seconds = if (results.seconds % 1.0 == 0.0) results.seconds.toInt().toString() else results.seconds.toString()
        return "${seconds}s" to "search time"
    }
    val whenSaved = results.cachedAt?.let {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
            .format(Instant.ofEpochMilli((it * 1000).toLong()).atZone(ZoneId.systemDefault()))
    } ?: "earlier today"
    return "saved" to "from $whenSaved"
}
