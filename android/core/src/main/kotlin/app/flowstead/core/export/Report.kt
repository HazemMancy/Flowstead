/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.export

import app.flowstead.core.api.Phases
import app.flowstead.core.format.ValueFormat
import app.flowstead.core.model.CheckLevel
import app.flowstead.core.model.DisplayValue
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.FlowsheetCheck
import app.flowstead.core.model.ObjectType
import app.flowstead.core.model.ResultKeys
import app.flowstead.core.model.ResultView
import app.flowstead.core.model.displayName
import app.flowstead.core.units.UnitContext
import app.flowstead.core.units.UnitSet

/**
 * A Markdown report of a case (D105), as DWSIM's: the fluid, every stream's state and composition, every unit's
 * results, the engine's messages and the flowsheet check. Every number is the engine's, in the user's units; anything
 * not solved shows as "—".
 */
object Report {
    private val STREAM_ROWS = listOf("temperature", "pressure", "vapourFraction", "molarFlow", "massFlow", "volumeFlow", "massEnthalpy", "density")
    private val UNIT_ROWS = ResultKeys.UNIT_OP

    fun markdown(name: String, fs: Flowsheet, results: ResultView, unitSet: UnitSet, generated: String, appVersion: String): String = buildString {
        val ctx = UnitContext()
        fun cell(objectId: String, key: String, q: app.flowstead.core.units.Quantity): String =
            when (val v = results.value(objectId, key)) {
                is DisplayValue.Calculated -> unitSet.unitFor(q).let { u -> ValueFormat.number(u.fromSi(v.si, ctx), u.decimals) }
                else -> "—"
            }
        fun esc(s: String) = s.replace("|", "\\|")
        appendLine("# ${esc(name)}")
        appendLine()
        appendLine("Flowstead $appVersion · $generated · ${fs.propertyPackage ?: "no fluid package"}")
        if (results.stale) appendLine("\n> The results below are from before the last change to the flowsheet.")
        if (results.status == null) appendLine("\n> Not solved yet: no results.")
        appendLine()
        appendLine("## Fluid")
        appendLine()
        appendLine("Package: ${fs.propertyPackage ?: "—"}  ")
        appendLine("Components (${fs.compounds.size}): ${fs.compounds.joinToString(", ")}")
        if (fs.hypotheticals.isNotEmpty()) appendLine("\nHypothetical components: ${fs.hypotheticals.joinToString(", ") { it.name + if (it.byStructure) " (by structure)" else "" }}")
        if (fs.reactions.isNotEmpty()) {
            appendLine("\nReactions:")
            fs.reactions.forEach { appendLine("- ${esc(it.name)} (${it.type.label.lowercase()}): ${esc(it.equation())}") }
        }
        val streams = fs.objects.filter { it.type == ObjectType.MaterialStream }
        if (streams.isNotEmpty()) {
            appendLine()
            appendLine("## Streams")
            appendLine()
            appendLine("| | " + streams.joinToString(" | ") { esc(it.tag) } + " |")
            appendLine("|---|" + streams.joinToString("") { "---:|" })
            for (k in STREAM_ROWS) {
                val q = ResultKeys.quantity(k)
                appendLine("| ${ResultKeys.label(k)} (${unitSet.unitFor(q).symbol}) | " + streams.joinToString(" | ") { cell(it.id, k, q) } + " |")
            }
            appendLine()
            appendLine("Composition (mole fraction)")
            appendLine()
            appendLine("| | " + streams.joinToString(" | ") { esc(it.tag) } + " |")
            appendLine("|---|" + streams.joinToString("") { "---:|" })
            for (c in fs.compounds) {
                appendLine("| ${esc(c)} | " + streams.joinToString(" | ") { s ->
                    (results.composition(s.id, Phases.OVERALL, c) as? DisplayValue.Calculated)?.let { ValueFormat.number(it.si, 4) } ?: "—"
                } + " |")
            }
        }
        val units = fs.objects.filter { !it.type.isStream && it.type != ObjectType.ReliefValve }
        if (units.isNotEmpty()) {
            appendLine()
            appendLine("## Equipment")
            for (u in units) {
                val keys = UNIT_ROWS.filter { results.value(u.id, it) is DisplayValue.Calculated }
                appendLine()
                appendLine("### ${esc(u.tag)} — ${u.type.displayName()}")
                if (keys.isEmpty()) { appendLine(); appendLine("No results."); continue }
                appendLine()
                appendLine("| | Value | Unit |")
                appendLine("|---|---:|---|")
                for (k in keys) {
                    val q = ResultKeys.quantityFor(u, k)
                    appendLine("| ${ResultKeys.label(k)} | ${cell(u.id, k, q)} | ${unitSet.unitFor(q).symbol} |")
                }
            }
        }
        val findings = FlowsheetCheck.run(fs, results)
        appendLine()
        appendLine("## Flowsheet check")
        appendLine()
        if (findings.isEmpty()) appendLine("Nothing to report.")
        findings.forEach { f ->
            val mark = when (f.level) { CheckLevel.Error -> "Error"; CheckLevel.Warning -> "Warning"; CheckLevel.Info -> "Note" }
            appendLine("- **$mark**: ${esc(f.text)}")
        }
        appendLine()
        appendLine("---")
        appendLine("Calculated by DWSIM's engine in Flowstead (GPL-3.0). Check results before relying on them for design.")
    }
}
