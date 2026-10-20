/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import app.flowstead.core.units.StandardConditions
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Flowstead's flowsheet document: the master copy of a project (decision D5).
 * All numbers are stored in SI; [Spec.unit] remembers what the user typed in, for display.
 * Mirrors `shared/schema/flowsheet.schema.json`.
 */
@Serializable
data class Flowsheet(
    val schemaVersion: Int = SCHEMA_VERSION,
    val propertyPackage: String? = null,
    val compounds: List<String> = emptyList(),
    val atmosphericPressurePa: Double = StandardConditions.DEFAULT_PATM_PA,
    val objects: List<SimObject> = emptyList(),
    val connections: List<Connection> = emptyList(),
) {
    companion object {
        const val SCHEMA_VERSION = 1
        val EMPTY = Flowsheet()
    }
}

@Serializable
data class SimObject(
    val id: String,
    val tag: String,
    val type: ObjectType,
    val x: Double = 0.0,
    val y: Double = 0.0,
    /** User specifications keyed by property id (e.g. "temperature"), in SI. */
    val specs: Map<String, Spec> = emptyMap(),
    /** Mole fractions by compound name; material streams only. */
    val composition: Map<String, Double> = emptyMap(),
    /** References to other objects; used by Adjust (see [AdjustKeys]). */
    val refs: Map<String, String> = emptyMap(),
)

/** Keys of [SimObject.refs] and specs used by an Adjust. Same strings as the engine's AdjustKeys. */
object AdjustKeys {
    const val MANIPULATED_OBJECT = "manipulatedObject"
    const val MANIPULATED_SPEC = "manipulatedSpec"
    const val CONTROLLED_OBJECT = "controlledObject"
    const val CONTROLLED_PROPERTY = "controlledProperty"
    const val TARGET = "target"
    const val TOLERANCE = "tolerance"
    const val MINIMUM = "minimum"
    const val MAXIMUM = "maximum"
}

@Serializable
data class Spec(val si: Double, val unit: String)

@Serializable
data class Connection(
    val fromObject: String,
    val fromPort: Int,
    val toObject: String,
    val toPort: Int,
)

/** Object types. MVP set plus placeholders kept so the model is ready for later unit ops. */
@Serializable
enum class ObjectType(val isStream: Boolean = false, val mvp: Boolean = true) {
    @SerialName("material_stream") MaterialStream(isStream = true),
    @SerialName("energy_stream") EnergyStream(isStream = true),
    @SerialName("mixer") Mixer,
    @SerialName("splitter") Splitter,
    @SerialName("heater") Heater,
    @SerialName("cooler") Cooler,
    @SerialName("heat_exchanger") HeatExchanger,
    @SerialName("valve") Valve,
    @SerialName("pump") Pump,
    @SerialName("compressor") Compressor,
    @SerialName("expander") Expander,
    @SerialName("separator") Separator,
    @SerialName("three_phase_separator") ThreePhaseSeparator,
    @SerialName("recycle") Recycle,
    @SerialName("adjust") Adjust,
    @SerialName("distillation_column") DistillationColumn(mvp = false),
    @SerialName("reactor") Reactor(mvp = false),
    @SerialName("pipe_segment") PipeSegment(mvp = false),
}

/** The one JSON configuration used for flowsheets everywhere (storage, API, export). */
val FlowsheetJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}
