/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.about

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.flowstead.BuildConfig
import app.flowstead.ui.components.BackButton
import app.flowstead.ui.components.FlowsteadMark
import app.flowstead.ui.components.Wordmark
import app.flowstead.ui.theme.NumberStyles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Bundled from docs/privacy/ (see app/build.gradle.kts). */
const val PRIVACY_ASSET = "privacy-policy.md"

/** Credits shown under About and in Settings. */
object Credits {
    const val DEVELOPER = "Developed by Hazem ElMancy, process engineer."
    const val ENGINE = "Built on the DWSIM engine: every calculation runs on DWSIM 9.0.5, the open-source chemical " +
        "process simulator (© Daniel Wagner and contributors, GPL-3.0)."
}

/** One open-source component and where its licence text is bundled (assets/licenses). */
data class OssEntry(val name: String, val licence: String, val asset: String?)

val OPEN_SOURCE = listOf(
    OssEntry("Flowstead", "GPL-3.0-or-later", "licenses/GPL-3.0.txt"),
    OssEntry("DWSIM — open-source process simulator (engine)", "GPL-3.0", "licenses/GPL-3.0.txt"),
    OssEntry("Manrope typeface", "SIL Open Font License 1.1", "licenses/OFL-Manrope.txt"),
    OssEntry("IBM Plex Mono typeface", "SIL Open Font License 1.1", "licenses/OFL-IBM-Plex-Mono.txt"),
    OssEntry("AndroidX, Jetpack Compose, Material 3", "Apache-2.0", null),
    OssEntry("Kotlin, kotlinx.coroutines, kotlinx.serialization, Ktor", "Apache-2.0", null),
    OssEntry("Dagger / Hilt", "Apache-2.0", null),
    OssEntry("OkHttp", "Apache-2.0", null),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit, onOpenLicense: (title: String, asset: String) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = { BackButton(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlowsteadMark(64.dp)
                Spacer(Modifier.padding(start = 16.dp))
                Column {
                    Wordmark(MaterialTheme.typography.headlineMedium)
                    Text("Version ${BuildConfig.VERSION_NAME}", style = NumberStyles.tag, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("Steady-state process simulation, in your pocket.", style = MaterialTheme.typography.bodyLarge)
            Text(Credits.DEVELOPER, style = MaterialTheme.typography.bodyLarge)
            Text(
                "Powered by DWSIM",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(
                "Calculations run on the open-source DWSIM process simulator (© Daniel Wagner and contributors, GPL-3.0). " +
                    "Flowstead is an independent project and is not affiliated with or endorsed by the DWSIM authors.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Results are approximate. Check them against other sources and your own engineering judgement before you rely on them.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Flowstead is free software under the GNU GPL v3 or later. Source code: github.com/hazemmancy/flowstead",
                style = MaterialTheme.typography.bodyMedium,
            )
            HorizontalDivider()
            Column(
                Modifier
                    .clickable(role = Role.Button, onClickLabel = "Read privacy policy") { onOpenLicense("Privacy policy", PRIVACY_ASSET) }
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(vertical = 6.dp)
                    .testTag("privacy_policy"),
            ) {
                Text("Privacy policy", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Your projects stay on this phone and the engine you choose. No analytics or tracking. Tap to read.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
            Text("Open-source licences", style = MaterialTheme.typography.titleMedium)
            OPEN_SOURCE.forEach { e ->
                val clickable = e.asset?.let { asset ->
                    Modifier.clickable(role = Role.Button, onClickLabel = "Read licence") { onOpenLicense(e.name, asset) }
                } ?: Modifier
                Column(clickable.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 6.dp)) {
                    Text(e.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (e.asset != null) "${e.licence} · tap to read" else e.licence,
                        style = NumberStyles.tag,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicenseTextScreen(title: String, asset: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val text by produceState("", asset) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open(asset).bufferedReader().use { it.readText() } }
                .getOrElse { "Licence text not found." }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = { BackButton(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        SelectionContainer {
            Text(
                text,
                style = NumberStyles.tag,
                modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            )
        }
    }
}
