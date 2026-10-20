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

/**
 * Keys of a Set (HYSYS-style): target.variable = multiplier × source.variable + offset, applied by the
 * engine before every solve. Same strings as the engine's SetKeys.
 */
object SetKeys {
    const val TARGET_OBJECT = "targetObject"
    const val SOURCE_OBJECT = "sourceObject"
    /** A spec key both objects take. */
    const val VARIABLE = "variable"
    const val MULTIPLIER = "multiplier"
    /** Offset, as a difference in the variable's quantity (SI). */
    const val OFFSET = "offset"
}

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

/** Palette groups, in HYSYS order. */
enum class Category(val label: String) {
    Streams("Streams"),
    HeatTransfer("Heat transfer"),
    Rotating("Rotating"),
    Piping("Piping"),
    Mixing("Mixing"),
    Separators("Separators"),
    Columns("Columns"),
    Reactors("Reactors"),
    Logical("Logical"),
}

/**
 * Every object type Flowstead knows. [available] is true once the engine solves it; the palette
 * shows the others as "coming" so the model and file format are ready for them.
 */
@Serializable
enum class ObjectType(val category: Category, val available: Boolean = true, val isStream: Boolean = false) {
    @SerialName("material_stream") MaterialStream(Category.Streams, isStream = true),
    @SerialName("energy_stream") EnergyStream(Category.Streams, isStream = true),

    @SerialName("heater") Heater(Category.HeatTransfer),
    @SerialName("cooler") Cooler(Category.HeatTransfer),
    @SerialName("heat_exchanger") HeatExchanger(Category.HeatTransfer),
    @SerialName("air_cooler") AirCooler(Category.HeatTransfer),

    @SerialName("pump") Pump(Category.Rotating),
    @SerialName("compressor") Compressor(Category.Rotating),
    @SerialName("expander") Expander(Category.Rotating),

    @SerialName("valve") Valve(Category.Piping),
    @SerialName("pipe_segment") PipeSegment(Category.Piping),
    @SerialName("orifice_plate") OrificePlate(Category.Piping),
    @SerialName("relief_valve") ReliefValve(Category.Piping, available = false),

    @SerialName("mixer") Mixer(Category.Mixing),
    @SerialName("splitter") Splitter(Category.Mixing),
    @SerialName("component_splitter") ComponentSplitter(Category.Mixing),

    @SerialName("separator") Separator(Category.Separators),
    @SerialName("three_phase_separator") ThreePhaseSeparator(Category.Separators),
    @SerialName("tank") Tank(Category.Separators),
    @SerialName("filter") Filter(Category.Separators, available = false),
    @SerialName("solids_separator") SolidsSeparator(Category.Separators, available = false),

    @SerialName("shortcut_column") ShortcutColumn(Category.Columns, available = false),
    @SerialName("distillation_column") DistillationColumn(Category.Columns, available = false),
    @SerialName("absorber") Absorber(Category.Columns, available = false),
    @SerialName("refluxed_absorber") RefluxedAbsorber(Category.Columns, available = false),
    @SerialName("reboiled_absorber") ReboiledAbsorber(Category.Columns, available = false),

    @SerialName("conversion_reactor") ConversionReactor(Category.Reactors, available = false),
    @SerialName("equilibrium_reactor") EquilibriumReactor(Category.Reactors, available = false),
    @SerialName("gibbs_reactor") GibbsReactor(Category.Reactors, available = false),
    @SerialName("cstr") Cstr(Category.Reactors, available = false),
    @SerialName("pfr") Pfr(Category.Reactors, available = false),

    @SerialName("recycle") Recycle(Category.Logical),
    @SerialName("adjust") Adjust(Category.Logical),
    @SerialName("set") Set(Category.Logical),
    @SerialName("energy_recycle") EnergyRecycle(Category.Logical, available = false),
}

/** The one JSON configuration used for flowsheets everywhere (storage, API, export). */
val FlowsheetJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}
