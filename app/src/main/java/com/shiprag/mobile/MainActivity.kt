package com.shiprag.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shiprag.mobile.data.*
import com.shiprag.mobile.ui.SourceScreen
import com.shiprag.mobile.ui.ReferenceRoutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Tab(val label: String) { Library("Library"), Ask("Ask"), Settings("Models") }

/** Keep search state outside of AskPage: the source reader temporarily removes AskPage from composition. */
private class AskSession {
    val query = mutableStateOf("")
    val selectedManual = mutableStateOf<String?>(null)
    val filterOpen = mutableStateOf(false)
    val hybrid = mutableStateOf(false)
    val busy = mutableStateOf(false)
    val generating = mutableStateOf(false)
    val results = mutableStateOf<List<Source>>(emptyList())
    val response = mutableStateOf<Generated?>(null)
    val error = mutableStateOf("")
    val evidenceFilter = mutableStateOf("All")

    fun clear() {
        query.value = ""
        selectedManual.value = null
        filterOpen.value = false
        hybrid.value = false
        busy.value = false
        generating.value = false
        results.value = emptyList()
        response.value = null
        error.value = ""
        evidenceFilter.value = "All"
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = LibraryStore(applicationContext)
        setContent { ShipRagApp(store) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShipRagApp(store: LibraryStore) {
    val scope = rememberCoroutineScope()
    val askSession = remember { AskSession() }
    val askListState = rememberLazyListState()
    var tab by remember { mutableStateOf(Tab.Library) }
    var source by remember { mutableStateOf<Source?>(null) }
    var sourceHistory by remember { mutableStateOf<List<Source>>(emptyList()) }
    val linksResolver = remember(store.root) { ManualReferenceResolver(store) }
    val openSource: (Source) -> Unit = { next ->
        val previous = source
        if (previous != null && previous.id != next.id) sourceHistory = sourceHistory + previous
        source = next
    }
    val closeSource: () -> Unit = {
        if (sourceHistory.isEmpty()) source = null
        else {
            source = sourceHistory.last()
            sourceHistory = sourceHistory.dropLast(1)
        }
    }
    var report by remember { mutableStateOf<ImportReport?>(null) }
    var inventory by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var manuals by remember { mutableStateOf<List<String>>(emptyList()) }
    var importing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val refresh: suspend () -> Unit = {
        val state = withContext(Dispatchers.IO) { Triple(store.statistics(), store.inventory(), if (store.root != null) store.manuals() else emptyList()) }
        report = state.first; inventory = state.second; manuals = state.third
    }
    LaunchedEffect(Unit) { refresh() }
    val chooseZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importing = true; message = ""; progress = "Reading backup…"
            runCatching {
                withContext(Dispatchers.IO) {
                    store.importZip(uri) { status -> scope.launch { progress = status } }
                }
            }.onSuccess {
                refresh()
                askSession.clear()  // An imported backup invalidates the previous search corpus.
                sourceHistory = emptyList()
                source = null
                scope.launch { askListState.scrollToItem(0) }
                progress = ""; message = "Imported ${it.manuals} manuals and ${it.chunks} searchable chunks."
                tab = Tab.Library
            }.onFailure { message = "Import failed: ${it.message ?: "Invalid backup"}"; progress = "" }
            importing = false
        }
    }
    val openZip = { if (!importing) chooseZip.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
    val dark = isSystemInDarkTheme()
    val colors = if (dark) darkColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF8BD2EB),
        secondary = androidx.compose.ui.graphics.Color(0xFFB0C6D0)) else lightColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFF125A73), secondary = androidx.compose.ui.graphics.Color(0xFF5B7080))
    BackHandler(enabled = source != null) { closeSource() }
    MaterialTheme(colorScheme = colors) {
        val selected = source
        if (selected != null) {
            SourceScreen(selected, store, resolver = linksResolver, onBack = closeSource, onFollow = openSource)
        } else Scaffold(
            topBar = { TopAppBar(title = {
                Column {
                    Text("ShipRAG", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Private technical library", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }, actions = {
                if (store.root != null) IconButton(onClick = { tab = Tab.Library }) { Icon(Icons.Default.FolderOpen, "Open library") }
            }) },
            bottomBar = { NavigationBar {
                Tab.entries.forEach { item ->
                    NavigationBarItem(selected = item == tab, onClick = { tab = item },
                        icon = { Icon(when(item) { Tab.Library -> Icons.Default.LibraryBooks; Tab.Ask -> Icons.Default.ManageSearch; Tab.Settings -> Icons.Default.Settings }, null) },
                        label = { Text(item.label) })
                }
            } }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (message.isNotBlank()) {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            IconButton(onClick = { message = "" }) { Icon(Icons.Default.Close, "Dismiss") }
                        }
                    }
                }
                when (tab) {
                    Tab.Library -> LibraryPage(report, inventory, manuals, importing, progress, openZip)
                    Tab.Ask -> AskPage(store, manuals, session = askSession, listState = askListState, scope = scope,
                        resolver = linksResolver, onOpen = openSource, onConfigure = { tab = Tab.Settings }, onImport = openZip)
                    Tab.Settings -> SettingsPage(store, report?.embeddedModels ?: emptyList()) { message = it }
                }
            }
        }
    }
}

@Composable
private fun LibraryPage(report: ImportReport?, inventory: Map<String, Int>, manuals: List<String>,
                        importing: Boolean, progress: String, pickZip: () -> Unit) {
    var filter by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        item {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.FolderZip, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
                    Text("Your offline manual library", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text("Import one full app-data ZIP. Original processed, input, output and database files stay on this phone. No conversion or review.")
                    Button(onClick = pickZip, enabled = !importing, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text(if (report == null) "Select backup ZIP" else "Import another ZIP")
                    }
                    if (importing) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(progress, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        if (report != null) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard(report.manuals.toString(), "Manuals", Modifier.weight(1f))
                    StatCard((report.chunks - report.visualSources).toString(), "Text", Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard(report.visualSources.toString(), "Visual sources", Modifier.weight(1f))
                    StatCard(report.vectors.toString(), "Vectors", Modifier.weight(1f))
                }
            }
            item {
                Text("Included files", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    inventory.forEach { (name, count) -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(name, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(count.toString(), fontWeight = FontWeight.SemiBold)
                    } }
                } }
            }
            item {
                Text("Searchable manuals", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(filter, { filter = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) }, label = { Text("Filter manuals") })
            }
            items(manuals.filter { it.contains(filter, ignoreCase = true) }) { manual ->
                ListItem(headlineContent = { Text(manual, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Icon(Icons.Default.MenuBook, null, tint = MaterialTheme.colorScheme.primary) },
                    supportingContent = { Text("Search-ready source") })
                HorizontalDivider()
            }
        } else item {
            Text("Your imported library will appear here, including the number of source PDFs, images, databases, embeddings and manuals.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun StatCard(value: String, label: String, modifier: Modifier = Modifier) {
    Card(modifier) { Column(Modifier.padding(12.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall)
    } }
}

@Composable
private fun AskPage(store: LibraryStore, manuals: List<String>, session: AskSession, listState: LazyListState,
                    scope: CoroutineScope, resolver: ManualReferenceResolver, onOpen: (Source) -> Unit,
                    onConfigure: () -> Unit, onImport: () -> Unit) {
    var query by session.query
    var selectedManual by session.selectedManual
    var filterOpen by session.filterOpen
    var hybrid by session.hybrid
    var busy by session.busy
    var generating by session.generating
    var results by session.results
    var response by session.response
    var error by session.error
    var evidenceFilter by session.evidenceFilter
    val textResults = results.filter { it.evidenceKind != "visual" }
    val visualResults = results.filter { it.evidenceKind == "visual" }
    val enoughToCite = results.count {
        Relevance.strongEnoughForAnswer(query, Relevance.Candidate(it.content, it.headings.joinToString(" > "), it.manual))
    }
    fun search() {
        if (query.isBlank() || busy || store.root == null) return
        busy = true; response = null; error = ""; results = emptyList(); evidenceFilter = "All"
        scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                val engine = Retriever(store)
                if (hybrid) {
                    val settings = store.settings()
                    require(settings.embeddingModel.isNotBlank()) { "Select an indexed embedding model under Models." }
                    val vector = LocalModels.embed(settings, query)
                    engine.hybrid(query, settings.embeddingModel, vector, selectedManual)
                } else engine.search(query, selectedManual)
            } }.onSuccess { results = it }
                .onFailure { error = it.message ?: "Search failed" }
            busy = false
        }
    }
    fun generate() {
        if (generating || results.isEmpty()) return
        generating = true; error = ""
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { LocalModels.answer(store.settings(), query, results) } }
                .onSuccess { response = it }
                .onFailure { error = it.message ?: "Answer generation failed" }
            generating = false
        }
    }
    if (store.root == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.FolderZip, null, Modifier.size(58.dp))
            Spacer(Modifier.height(16.dp)); Text("Import your manual ZIP first", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(14.dp)); Button(onClick = onImport) { Text("Choose backup") }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Ask your manuals", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text("Search is on-device. Answer generation uses only the chosen LLM provider and cited excerpts.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Fault, part number or technical question") }, minLines = 2, maxLines = 5,
                placeholder = { Text("e.g. What causes a crane hoist overload alarm?") })
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedButton(onClick = { filterOpen = true }) {
                        Icon(Icons.Default.FilterList, null); Spacer(Modifier.width(5.dp))
                        Text(selectedManual?.substringBeforeLast('.')?.take(16) ?: "All manuals")
                    }
                    DropdownMenu(filterOpen, onDismissRequest = { filterOpen = false }) {
                        DropdownMenuItem(text = { Text("All manuals") }, onClick = { selectedManual = null; filterOpen = false })
                        manuals.forEach { manual -> DropdownMenuItem(text = { Text(manual, maxLines = 2) }, onClick = { selectedManual = manual; filterOpen = false }) }
                    }
                }
                FilterChip(selected = hybrid, onClick = { hybrid = !hybrid }, label = { Text(if (hybrid) "Hybrid" else "Lexical") })
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onConfigure) { Icon(Icons.Default.Tune, "Model settings") }
            }
        }
        item {
            Button(onClick = ::search, enabled = !busy && query.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Search, null); Spacer(Modifier.width(8.dp)); Text(if (busy) "Searching…" else "Search evidence")
            }
        }
        if (busy || generating) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (error.isNotBlank()) item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Text(error, Modifier.padding(15.dp), color = MaterialTheme.colorScheme.onErrorContainer)
        } }
        if (results.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("${results.size} ranked evidence matches", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = onConfigure) { Text("Generator") }
                }
                Button(onClick = ::generate, enabled = !generating && enoughToCite > 0,
                    modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(8.dp))
                    Text(if (generating) "Generating answer…" else "Generate grounded answer")
                }
                if (enoughToCite == 0) {
                    Text("No strongly matching sources yet. Select the correct manual or add equipment/part numbers; an unsupported answer will not be generated.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        } else if (!busy && query.isNotBlank() && error.isBlank()) item {
            Text("No results yet. Search existing indexes first, then generate an answer.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (results.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Evidence type", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = evidenceFilter == "All", onClick = { evidenceFilter = "All" },
                        label = { Text("All (${results.size})") })
                    FilterChip(selected = evidenceFilter == "Text", onClick = { evidenceFilter = "Text" },
                        label = { Text("Text (${textResults.size})") })
                    FilterChip(selected = evidenceFilter == "Visual", onClick = { evidenceFilter = "Visual" },
                        label = { Text("Visual (${visualResults.size})") })
                }
            }
        }
        if (response != null) item { AnswerCard(requireNotNull(response), requireNotNull(response).sources, onOpen) }
        if (evidenceFilter != "Visual" && textResults.isNotEmpty()) {
            item { Text("Text sources", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            items(textResults, key = { "text-${it.id}" }) { result -> SourceCard(result, query, resolver, onOpen) }
        }
        if (evidenceFilter != "Text" && visualResults.isNotEmpty()) {
            item { Text("Visual sources — diagrams and pictures", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            items(visualResults, key = { "visual-${it.id}" }) { result -> SourceCard(result, query, resolver, onOpen) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun AnswerCard(answer: Generated, sources: List<Source>, onOpen: (Source)->Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(8.dp))
                Text("Grounded answer", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
            Text(answer.answer, style = MaterialTheme.typography.bodyLarge)
            Text(answer.warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text("Source links", fontWeight = FontWeight.SemiBold)
            sources.take(8).forEachIndexed { i, row ->
                OutlinedButton(onClick = { onOpen(row) }, modifier = Modifier.fillMaxWidth()) {
                    Text("[S${i+1}] ${row.manual.take(24)} • p.${row.page ?: "?"}", maxLines = 1)
                    Spacer(Modifier.weight(1f)); Icon(Icons.Default.OpenInNew, null)
                }
            }
        }
    }
}

@Composable private fun SourceCard(source: Source, query: String, resolver: ManualReferenceResolver, onOpen: (Source) -> Unit) {
    OutlinedCard(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().clickable { onOpen(source) }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(if (source.evidenceKind == "visual") Icons.Default.Image else Icons.Default.Description, null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Text(if (source.evidenceKind == "visual") "VISUAL · ${source.manual}" else source.manual,
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 2)
            }
            Text(source.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Text(source.content.replace('\n', ' ').take(250) + if (source.content.length > 250) "…" else "",
                style = MaterialTheme.typography.bodyMedium, maxLines = 5)
            val supportsAnswer = Relevance.strongEnoughForAnswer(
                query, Relevance.Candidate(source.content, source.headings.joinToString(" > "), source.manual))
            Text(if (supportsAnswer) "Strong query match · candidate citation" else "Related excerpt · check relevance before citing",
                style = MaterialTheme.typography.labelSmall,
                color = if (supportsAnswer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            ReferenceRoutes(source, resolver, onOpen, compact = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Page ${source.page ?: "?"}  •  ${source.evidenceKind}  •  ${source.method}",
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (source.evidenceKind == "visual") "View image" else "View source",
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun SettingsPage(store: LibraryStore, indexedModels: List<String>, onMessage: (String)->Unit) {
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(store.settings()) }
    var discovered by remember { mutableStateOf<List<String>>(emptyList()) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var modelMenu by remember { mutableStateOf(false) }
    var embedMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Model connections", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text("Use a cloud OpenAI-compatible API or llama.cpp on localhost/LAN. No provider is contacted until you explicitly test it or ask a question.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Text("Answer model", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(settings.chatUrl, { settings = settings.copy(chatUrl = it) }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Base URL / llama.cpp URL") }, singleLine = true,
            supportingText = { Text("e.g. http://127.0.0.1:8080 or https://api.example.com/v1") })
        OutlinedTextField(settings.chatApiKey, { settings = settings.copy(chatApiKey = it) }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Cloud API key (optional for llama.cpp)") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(settings.chatModel, { settings = settings.copy(chatModel = it) }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Model ID") }, singleLine = true)
        Button(onClick = {
            working = true; error = ""
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { LocalModels.models(settings.chatUrl, settings.chatApiKey) } }
                    .onSuccess { models -> discovered = models
                        if (models.isNotEmpty() && (settings.chatModel == "local" || settings.chatModel.isBlank() || settings.chatModel !in models))
                            settings = settings.copy(chatModel = models.first())
                        if (models.isEmpty()) error = "Endpoint connected, but it returned no model names. Enter model ID manually."
                    }.onFailure { error = "Model detection: ${it.message}" }
                working = false
            }
        }, enabled = !working, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Sync, null); Spacer(Modifier.width(8.dp)); Text(if (working) "Connecting…" else "Connect & auto-detect models")
        }
        if (discovered.isNotEmpty()) {
            Box {
                OutlinedButton(onClick = { modelMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Choose detected model (${discovered.size})"); Spacer(Modifier.weight(1f)); Icon(Icons.Default.ArrowDropDown, null)
                }
                DropdownMenu(modelMenu, onDismissRequest = { modelMenu = false }) {
                    discovered.forEach { model -> DropdownMenuItem(text = { Text(model) }, onClick = { settings = settings.copy(chatModel = model); modelMenu = false }) }
                }
            }
        }
        HorizontalDivider()
        Text("Optional semantic retrieval", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Lexical search always works offline. To enable hybrid search, the query embedding provider MUST use the same embedding model and prefix as the imported vectors.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(settings.embeddingUrl, { settings = settings.copy(embeddingUrl = it) }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Embedding URL") }, singleLine = true,
            supportingText = { Text("Existing Docling service: http://127.0.0.1:8090/embed; or OpenAI /v1/embeddings") })
        OutlinedTextField(settings.embeddingApiKey, { settings = settings.copy(embeddingApiKey = it) }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Embedding API key (optional)") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(settings.embeddingModel, { settings = settings.copy(embeddingModel = it) }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Indexed embedding model") }, singleLine = true)
        if (indexedModels.isNotEmpty()) {
            Box {
                OutlinedButton(onClick = { embedMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Select model from archive"); Spacer(Modifier.weight(1f)); Icon(Icons.Default.ArrowDropDown, null)
                }
                DropdownMenu(embedMenu, onDismissRequest = { embedMenu = false }) {
                    indexedModels.forEach { model -> DropdownMenuItem(text = { Text(model) }, onClick = { settings = settings.copy(embeddingModel = model); embedMenu = false }) }
                }
            }
        }
        OutlinedTextField(settings.queryPrefix, { settings = settings.copy(queryPrefix = it) }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Query prefix from original embedding config") }, maxLines = 3)
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Button(onClick = { store.saveSettings(settings); onMessage("Model settings saved securely.") }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Save, null); Spacer(Modifier.width(8.dp)); Text("Save configuration")
        }
        Text("Privacy: Imported backups are stored in app-private storage. A cloud answer request sends the question and selected source excerpts to the chosen provider, never the full ZIP. Any credentials inside your original backup remain in the local backup, so do not share the ZIP.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(30.dp))
    }
}