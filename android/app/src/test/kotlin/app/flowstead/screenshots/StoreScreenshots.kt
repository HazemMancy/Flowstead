/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.screenshots

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import app.flowstead.ui.flowsheet.drawUnitIcon
import app.flowstead.ui.theme.valueColors
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.flowstead.core.api.SolveResult
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.FlowsheetJson
import app.flowstead.core.model.ResultView
import app.flowstead.core.model.Specification
import app.flowstead.core.model.StoredSolve
import app.flowstead.core.model.modelHash
import app.flowstead.core.units.Quantity
import app.flowstead.core.units.UnitContext
import app.flowstead.core.units.UnitSet
import app.flowstead.core.units.Units
import app.flowstead.data.ProjectSummary
import app.flowstead.ui.flowsheet.CanvasActions
import app.flowstead.ui.flowsheet.FlowsheetCanvas
import app.flowstead.ui.flowsheet.Hit
import app.flowstead.ui.flowsheet.Viewport
import app.flowstead.ui.projects.ProjectsContent
import app.flowstead.ui.projects.ProjectsUiState
import app.flowstead.ui.results.ResultsContent
import app.flowstead.ui.results.ResultsUi
import app.flowstead.ui.results.SolverLogContent
import app.flowstead.ui.theme.FlowsteadTheme
import app.flowstead.ui.theme.ThemeMode
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertTrue

/**
 * Renders the main screens in light and dark as PNGs (Play Store screenshots and a visual check of
 * the dark theme). The flowsheet is benchmark 06 and the result is a real engine response for it
 * (shared/fixtures/solve-result-06.json), so every number in the images came from the engine.
 * 1080 × 2160 px: Play's 2:1 aspect limit. Output: app/build/screenshots (CI artifact).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h720dp-xxhdpi")
class StoreScreenshots {
    @get:Rule val compose = createComposeRule()

    private val repo = File(System.getProperty("flowstead.repoDir") ?: error("flowstead.repoDir not set"))
    private val out = File(System.getProperty("flowstead.screenshotDir") ?: "build/screenshots").apply { mkdirs() }

    private val flowsheet: Flowsheet by lazy {
        val root = FlowsheetJson.parseToJsonElement(File(repo, "benchmarks/06-compressor-aftercooler/input.json").readText()).jsonObject
        FlowsheetJson.decodeFromJsonElement(Flowsheet.serializer(), root.getValue("flowsheet"))
    }
    private val solve: StoredSolve by lazy {
        val r = FlowsheetJson.decodeFromString(SolveResult.serializer(), File(repo, "shared/fixtures/solve-result-06.json").readText())
        StoredSolve(r, flowsheet.modelHash(), 1_792_000_000_000)
    }
    private val units = UnitSet.SI.with(Quantity.Pressure, Units.barg).with(Quantity.Temperature, Units.C)
    private val ui by lazy { ResultsUi("Compressor + aftercooler", flowsheet, solve, ResultView(solve, flowsheet.modelHash()), units, UnitContext()) }

    private fun shoot(name: String, dark: Boolean, before: () -> Unit = {}, content: @Composable () -> Unit) {
        compose.setContent {
            FlowsteadTheme(if (dark) ThemeMode.Dark else ThemeMode.Light) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        compose.waitForIdle()
        before()
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val file = File(out, "$name-${if (dark) "dark" else "light"}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(bitmap.width == 1080 && bitmap.height >= 2000, "unexpected size ${bitmap.width}×${bitmap.height}")
    }

    private val projects = ProjectsUiState(
        loading = false,
        projects = listOf(
            ProjectSummary("1", "Compressor + aftercooler", 1_792_000_000_000, 5, true),
            ProjectSummary("2", "JT valve letdown", 1_791_900_000_000, 3, true),
            ProjectSummary("3", "Recycle loop", 1_791_800_000_000, 13, true),
            ProjectSummary("4", "Three-phase separator", 1_791_700_000_000, 4, false),
        ),
    )

    private val noActions = object : CanvasActions {
        override fun tap(hit: Hit) = Unit
        override fun longPress(objectId: String) = Unit
        override fun move(objectId: String, x: Double, y: Double) = Unit
        override fun wire(unitId: String, port: app.flowstead.core.model.Port, target: Hit, world: Offset) = Unit
        override fun delete(objectId: String) = Unit
    }

    @Composable
    private fun Canvas() {
        // World x 0…480 dp centred on a 360 dp wide screen at 0.65× (3 px per dp at xxhdpi).
        val zoom = 0.65f
        val viewport = Viewport(offset = Offset(540f - 240f * zoom * 3f, 900f), zoom = zoom)
        FlowsheetCanvas(
            flowsheet = flowsheet,
            status = flowsheet.objects.associate { it.id to Specification.status(flowsheet, it) },
            selectedId = null,
            results = ResultView(solve, flowsheet.modelHash()),
            viewport = viewport,
            onViewport = {},
            actions = noActions,
            modifier = Modifier.fillMaxSize(),
        )
    }

    @Test fun projectsLight() = shoot("1-projects", false) { ProjectsContent(projects) }
    @Test fun projectsDark() = shoot("1-projects", true) { ProjectsContent(projects) }
    @Test fun canvasLight() = shoot("2-flowsheet", false) { Canvas() }
    @Test fun canvasDark() = shoot("2-flowsheet", true) { Canvas() }
    @Test fun resultsLight() = shoot("3-results", false) { ResultsContent(ui) }
    @Test fun resultsDark() = shoot("3-results", true) { ResultsContent(ui) }
    @Test fun logLight() = shoot("4-solver-log", false) { SolverLogContent(ui) }
    @Test fun logDark() = shoot("4-solver-log", true) { SolverLogContent(ui) }

    /** Every palette group stacked, so all symbols can be reviewed in one image per theme. */
    @Composable
    private fun AllSymbols() {
        androidx.compose.foundation.layout.Column(
            Modifier.fillMaxSize(),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
        ) {
            app.flowstead.core.model.Category.entries.forEach { g ->
                androidx.compose.material3.Text(g.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 12.dp))
                SymbolRow(app.flowstead.core.model.ObjectType.entries.filter { it.category == g })
            }
        }
    }

    @Composable
    private fun SymbolRow(types: List<app.flowstead.core.model.ObjectType>) {
        val colors = MaterialTheme.colorScheme
        val values = MaterialTheme.valueColors
        androidx.compose.foundation.layout.Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
            types.forEach { t ->
                androidx.compose.foundation.Canvas(Modifier.size(64.dp)) {
                    val s = app.flowstead.ui.flowsheet.CanvasGeometry.size(t)
                    val fit = minOf(size.width / (s.width + 8f), size.height / (s.height + 8f))
                    val p = app.flowstead.ui.flowsheet.IconPalette(colors.surface, colors.onSurface, colors.secondary, values.vapour, values.liquid, values.energy)
                    androidx.compose.ui.graphics.drawscope.scale(fit, fit, pivot = Offset.Zero) {
                        drawUnitIcon(t, center / fit, s, p, 1.5f * density / fit)
                    }
                }
            }
        }
    }

    @Test fun symbolsLight() = shoot("5-symbols", false) { AllSymbols() }
    @Test fun symbolsDark() = shoot("5-symbols", true) { AllSymbols() }
    @Test fun paletteColumns() = shoot("6-palette-columns", false, before = {
        compose.onNodeWithTag("group_Columns").performClick()
    }) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
            app.flowstead.ui.flowsheet.Palette(onAdd = {}, onDrag = { _, _ -> }, onDrop = { _, _ -> })
        }
    }
}
