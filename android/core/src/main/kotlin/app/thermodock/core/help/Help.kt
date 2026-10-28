/*
 * ThermoDock — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.thermodock.core.help

import app.thermodock.core.model.DataLibrary
import app.thermodock.core.model.Flowsheet
import app.thermodock.core.model.FlowsheetJson
import app.thermodock.core.model.InletDevice
import app.thermodock.core.model.MistEliminator
import app.thermodock.core.model.ObjectType
import app.thermodock.core.model.PipeSizes
import app.thermodock.core.model.SeparatorSizing
import app.thermodock.core.model.ValveLibrary
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** An example flowsheet: one of the repository's benchmark cases, solved and checked by CI on every engine change. */
data class Example(val id: String, val name: String, val description: String)

/** A release's notes, newest first in [Help.releases]. */
data class Release(val version: String, val notes: String)

/**
 * Help & Resources (D63), as HYSYS's Help and Resources ribbon: the manual (hand-written pages), the methods reference
 * (every method with how it was checked and how it differs from HYSYS, from docs/DECISIONS.md), the data library,
 * the examples, what's new and support. Everything ships in the app and works offline.
 */
object Help {
    const val METHODS = "Methods reference"
    const val LIBRARY = "Data library"
    const val REPOSITORY = app.thermodock.core.update.Updates.REPOSITORY
    const val ISSUES = "$REPOSITORY/issues"
    const val DWSIM = "https://dwsim.org"

    private fun resource(path: String): String? =
        Help::class.java.getResourceAsStream("/help/$path")?.use { it.readBytes().decodeToString() }

    private val index: List<String> by lazy { resource("index.txt")?.lines()?.filter { it.isNotBlank() }.orEmpty() }

    /** Methods-reference rows that are about building and shipping the app, not about the engineering. */
    private val INTERNAL = setOf("D1", "D2", "D4", "D7", "D8", "D10", "D12", "D13", "D14", "D15", "D16", "D23", "D34", "D37", "D43", "D45")

    /** The methods reference: one page per decision row (#, question, decision, why). */
    val methods: List<HelpTopic> by lazy {
        val text = resource("DECISIONS.md") ?: return@lazy emptyList()
        text.lines().filter { it.startsWith("| D") }.mapNotNull { line ->
            val cells = line.trim().trim('|').split(" | ").map { it.trim() }
            if (cells.size < 4) return@mapNotNull null
            val id = cells[0]
            if (id in INTERNAL) return@mapNotNull null
            val title = cells[1].replace("**", "").replace(Regex("\\s*\\((hard to reverse|owner's [^)]*)\\)"), "").trim()
            HelpTopic(
                id = id, section = METHODS, title = "$id · $title",
                body = "## Method\n" + clean(cells[2]) + "\n## Checked and why\n" + clean(cells.drop(3).joinToString(" | ")),
                keywords = listOf(id),
            )
        }
    }

    /** The app was called Flowstead until 1.8.0 (D64); the reference reads with today's name. */
    private fun clean(s: String) = s.replace("`", "").replace("<br>", "\n").replace("Flowstead", "ThermoDock")

    /** The data library pages, written from the library's own tables so they always show what the tools use. */
    val library: List<HelpTopic> by lazy {
        listOf(
            HelpTopic("lib_u", LIBRARY, "Overall heat-transfer coefficients (U)",
                "Typical ranges, W/m²·K; sizing uses the middle unless you give U. Coulson & Richardson vol. 6 Table 12.1 (shell and tube), GPSA section 10 (air coolers, bare tube).\n" +
                    DataLibrary.U_SERVICES.groupBy { it.group }.entries.joinToString("\n") { (g, list) ->
                        "## $g\n" + list.joinToString("\n") { "- ${it.label}: ${it.low.toInt()} – ${it.high.toInt()}" }
                    },
                listOf("U value", "heat transfer coefficient", "fouling", "overall")),
            HelpTopic("lib_sep", LIBRARY, "Separator internals",
                "## Mist eliminators (Souders–Brown K, vertical flow, near atmospheric)\n" +
                    MistEliminator.entries.joinToString("\n") { "- ${it.label}: K ${it.k} m/s" + if (it.padThickness > 0) ", element ${it.padThickness} m" else "" } +
                    "\n## K pressure factor (GPSA)\n" + listOf(0.0, 300.0, 600.0, 900.0, 1500.0).joinToString("\n") {
                        "- $it psig: × ${"%.2f".format(SeparatorSizing.pressureFactor(it * 6894.757 + 101325.0))}"
                    } +
                    "\n## Inlet devices (largest inlet momentum ρv²)\n" + InletDevice.entries.joinToString("\n") { "- ${it.label}: ${it.maxMomentum.toInt()} Pa" } +
                    "\n## Outlets\n- Gas outlet: ρv² ≤ ${SeparatorSizing.GAS_OUTLET_MOMENTUM.toInt()} Pa\n- Liquid outlets: ≤ ${SeparatorSizing.LIQUID_OUTLET_VELOCITY} m/s" +
                    "\n## Liquid times\n- Hold-up 5 min, surge 3 min (two-phase)\n- Oil and water retention 5 min each (API 12J)\n- Droplets: 500 µm water out of oil, 200 µm oil out of water (Arnold & Stewart)",
                listOf("K factor", "mist eliminator", "demister", "inlet device", "retention")),
            HelpTopic("lib_drivers", LIBRARY, "Drivers and motors",
                "## Driver margin over shaft power\n- Pumps (API 610): 125 % to 22 kW, 115 % to 55 kW, 110 % above\n- Compressors (API 617): 110 %\n" +
                    "## IEC 60072 motor ratings, kW\n" + DataLibrary.MOTORS_KW.joinToString(", ") { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() },
                listOf("motor", "driver", "API 610", "API 617", "IEC")),
            HelpTopic("lib_valves", LIBRARY, "Control valve factors (IEC 60534-2-1 Annex A)",
                "Typical FL and xT by valve type, used for valve sizing unless the vendor's are given.\n" +
                    ValveLibrary.TYPES.joinToString("\n") { "- ${it.label}: FL ${it.fl}, xT ${it.xt}" },
                listOf("FL", "xT", "valve", "Cv")),
            HelpTopic("lib_pipes", LIBRARY, "Pipe sizes (ASME B36.10M / B36.19M)",
                "Inside diameters, mm, for the common schedules.\n" + PipeSizes.ALL.joinToString("\n") { s ->
                    "- NPS ${s.nps} (DN ${s.dn}, OD ${"%.1f".format(s.outsideDiameterM * 1000)}): " +
                        s.schedules.joinToString(", ") { "${it.label} ${"%.1f".format(s.insideDiameterM(it) * 1000)}" }
                },
                listOf("pipe", "schedule", "NPS", "DN", "inside diameter")),
            HelpTopic("lib_utilities", LIBRARY, "Utility temperatures",
                "Used for heater, cooler and air cooler sizing unless you give your own.\n- Cooling water: 30 → 40 °C\n- LP steam: condensing at 150 °C\n- Air: 35 °C in, 15 K rise",
                listOf("cooling water", "steam", "air", "utility")),
        )
    }

    val topics: List<HelpTopic> by lazy { HelpTopics.ALL + library + methods }

    val sections: List<String> by lazy { topics.map { it.section }.distinct() }

    fun topic(id: String): HelpTopic? = topics.firstOrNull { it.id == id }

    /** F1: the page for a unit type. */
    fun forType(type: ObjectType): HelpTopic? = topic(HelpTopics.unitId(type))

    /** Pages matching every word of [query], titles first. */
    fun search(query: String): List<HelpTopic> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        fun hay(t: HelpTopic) = (t.title + " " + t.keywords.joinToString(" ") + " " + t.body).lowercase()
        return topics.filter { t -> words.all { it in hay(t) } }
            .sortedByDescending { t -> words.count { it in t.title.lowercase() } + words.count { w -> t.keywords.any { w in it.lowercase() } } }
    }

    val examples: List<Example> by lazy {
        index.filter { it.startsWith("examples/") }.mapNotNull { path ->
            val json = resource(path)?.let { runCatching { FlowsheetJson.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return@mapNotNull null
            Example(path.removePrefix("examples/").removeSuffix(".json"), json.text("name") ?: path, json.text("description").orEmpty())
        }
    }

    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.content

    /** The example's flowsheet, laid out left to right when the case gives no positions. */
    fun example(id: String): Flowsheet? {
        val json = resource("examples/$id.json")?.let { FlowsheetJson.parseToJsonElement(it).jsonObject } ?: return null
        val fs = FlowsheetJson.decodeFromJsonElement(Flowsheet.serializer(), json["flowsheet"] ?: return null)
        return AutoLayout.ifNeeded(fs)
    }

    val releases: List<Release> by lazy {
        index.filter { it.startsWith("release-notes/") }.mapNotNull { path ->
            val v = path.removePrefix("release-notes/").removeSuffix(".md").removePrefix("v")
            resource(path)?.let { Release(v, it.replace("Flowstead", "ThermoDock")) }
        }.sortedWith(compareByDescending<Release> { r -> r.version.split('.').map { it.toIntOrNull() ?: 0 }.let { it.getOrElse(0) { 0 } * 1_000_000 + it.getOrElse(1) { 0 } * 1000 + it.getOrElse(2) { 0 } } })
    }
}
