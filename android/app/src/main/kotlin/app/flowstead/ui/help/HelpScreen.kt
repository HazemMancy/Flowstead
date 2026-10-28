/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.help

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.flowstead.BuildConfig
import app.flowstead.core.help.Help
import app.flowstead.core.help.HelpTopic
import app.flowstead.ui.components.BackButton
import app.flowstead.ui.theme.NumberStyles

/** Where the help screen is: the home page, a section's list, a page, the examples, what's new or support. */
private sealed interface Page {
    data object Home : Page
    data class Section(val name: String) : Page
    data class Topic(val id: String) : Page
    data object Examples : Page
    data object WhatsNew : Page
    data object Support : Page
}

private fun Page.key(): String = when (this) {
    Page.Home -> "home"
    is Page.Section -> "section:$name"
    is Page.Topic -> "topic:$id"
    Page.Examples -> "examples"
    Page.WhatsNew -> "whatsnew"
    Page.Support -> "support"
}

private fun page(key: String): Page = when {
    key.startsWith("section:") -> Page.Section(key.removePrefix("section:"))
    key.startsWith("topic:") -> Page.Topic(key.removePrefix("topic:"))
    key == "examples" -> Page.Examples
    key == "whatsnew" -> Page.WhatsNew
    key == "support" -> Page.Support
    else -> Page.Home
}

/**
 * Help & Resources (D63), as HYSYS's Help and Resources ribbon: a searchable manual, the methods reference, the data
 * library, examples that open as projects, tutorials, what's new and support. [topicId] opens a page directly (F1).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(
    topicId: String?,
    onBack: () -> Unit,
    onOpenProject: (String) -> Unit,
    viewModel: HelpViewModel = hiltViewModel(),
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect {
            when (it) {
                is HelpEffect.OpenProject -> onOpenProject(it.id)
                is HelpEffect.Message -> snackbar.showSnackbar(it.text)
            }
        }
    }
    val stack: SnapshotStateList<String> = rememberSaveable(
        saver = listSaver<SnapshotStateList<String>, String>(save = { it.toList() }, restore = { it.toMutableStateList() }),
    ) { mutableStateListOf(topicId?.let { Page.Topic(it).key() } ?: Page.Home.key()) }
    var query by rememberSaveable { mutableStateOf("") }
    val current = page(stack.last())
    fun open(p: Page) { query = ""; stack.add(p.key()) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) else onBack() }
    BackHandler(enabled = stack.size > 1) { back() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (current) {
                            Page.Home -> "Help & Resources"
                            is Page.Section -> current.name
                            is Page.Topic -> Help.topic(current.id)?.section ?: "Help"
                            Page.Examples -> "Examples"
                            Page.WhatsNew -> "What's new"
                            Page.Support -> "Support"
                        },
                        maxLines = 1,
                    )
                },
                navigationIcon = { BackButton(::back) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (current == Page.Home || current is Page.Section) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search the help: dew point, PSV, Souders–Brown…") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("help_search"),
                )
            }
            when {
                query.isNotBlank() -> TopicList(Help.search(query), showSection = true) { open(Page.Topic(it)) }
                current == Page.Home -> Home(::open)
                current is Page.Section -> TopicList(Help.topics.filter { it.section == current.name }, showSection = false) { open(Page.Topic(it)) }
                current is Page.Topic -> Help.topic(current.id)?.let { TopicPage(it) { id -> open(Page.Topic(id)) } }
                    ?: Text("That page is not in this version.", Modifier.padding(16.dp))
                current == Page.Examples -> Examples(viewModel::openExample)
                current == Page.WhatsNew -> WhatsNew()
                current == Page.Support -> Support()
            }
        }
    }
}

@Composable
private fun Home(open: (Page) -> Unit) {
    data class Entry(val title: String, val subtitle: String, val page: Page)
    val entries = buildList {
        add(Entry("Examples", "Ready-made flowsheets, checked by the benchmark suite: open one as a new project", Page.Examples))
        add(Entry("Tutorials", "Step by step: first flowsheet, separator sizing, relief valve, phase envelope", Page.Section("Tutorials")))
        for (s in listOf("Getting started", "Thermodynamics", "Unit operations", "Analysis tools", "Safety", "Sizing")) {
            add(Entry(s, "${Help.topics.count { it.section == s }} pages", Page.Section(s)))
        }
        add(Entry(Help.LIBRARY, "U values, separator internals, motors, valve factors, pipe sizes, utilities", Page.Section(Help.LIBRARY)))
        add(Entry(Help.METHODS, "Every method: the equations, how it was checked, how it differs from HYSYS", Page.Section(Help.METHODS)))
        add(Entry("What's new", "Release notes, Flowstead ${BuildConfig.VERSION_NAME}", Page.WhatsNew))
        add(Entry("Support", "Report a problem, ask a question, DWSIM's documentation", Page.Support))
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(entries) { e -> Row1(e.title, e.subtitle) { open(e.page) } }
    }
}

@Composable
private fun Row1(title: String, subtitle: String?, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
    }
    HorizontalDivider()
}

@Composable
private fun TopicList(topics: List<HelpTopic>, showSection: Boolean, open: (String) -> Unit) {
    if (topics.isEmpty()) {
        Text("Nothing found.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(topics, key = { it.id }) { t -> Row1(t.title, if (showSection) t.section else null) { open(t.id) } }
    }
}

/** Renders the light markup of a help page. */
@Composable
private fun TopicPage(t: HelpTopic, open: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(t.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp)) }
        items(t.body.lines().filter { it.isNotBlank() }) { line -> Markup(line) }
        if (t.methods.isNotEmpty()) {
            item { Text("Methods reference", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
            items(t.methods) { id ->
                val m = Help.topic(id) ?: return@items
                AssistChip(onClick = { open(id) }, label = { Text(m.title, maxLines = 1) })
            }
        }
        item { Text(" ", Modifier.padding(bottom = 24.dp)) }
    }
}

@Composable
internal fun Markup(line: String) {
    when {
        line.startsWith("## ") -> Text(line.removePrefix("## "), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        line.startsWith("- ") -> Row { Text("•  "); Text(bold(line.removePrefix("- ")), style = MaterialTheme.typography.bodyMedium) }
        line.startsWith("= ") -> Text(line.removePrefix("= "), style = NumberStyles.value, modifier = Modifier.padding(start = 12.dp))
        line.startsWith("> ") -> Text(bold(line.removePrefix("> ")), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        else -> Text(bold(line), style = MaterialTheme.typography.bodyMedium)
    }
}

/** **bold** spans; the markers are removed. */
private fun bold(s: String): AnnotatedString = buildAnnotatedString {
    s.split("**").forEachIndexed { i, part -> if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(part) } else append(part) }
}

@Composable
private fun Examples(onOpen: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                "Each example is one of Flowstead's benchmark cases, solved and checked on every engine change. Tap one to open it as a new project, then solve it.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp),
            )
        }
        items(Help.examples, key = { it.id }) { e -> Row1(e.name, e.description) { onOpen(e.id) } }
    }
}

@Composable
private fun WhatsNew() {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (r in Help.releases) {
            item(key = r.version) { Text("Flowstead ${r.version}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp)) }
            items(r.notes.lines().filter { it.isNotBlank() && !it.startsWith("# ") }) { line ->
                // Release notes are Markdown: bold paragraphs and bullets read the same as help pages.
                Markup(if (line.startsWith("### ")) "## " + line.removePrefix("### ") else line.removePrefix("#"))
            }
        }
    }
}

@Composable
private fun Support() {
    val uri = LocalUriHandler.current
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Flowstead ${BuildConfig.VERSION_NAME}, with DWSIM 9.0.5", style = NumberStyles.tag, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row1("Report a problem or ask for a feature", "GitHub issues: include the project (Export DWSIM file) and what you expected") { uri.openUri(Help.ISSUES) }
        Row1("Source code and releases", Help.REPOSITORY) { uri.openUri(Help.REPOSITORY) }
        Row1("DWSIM documentation", "The thermodynamics engine behind every number") { uri.openUri(Help.DWSIM) }
    }
}
