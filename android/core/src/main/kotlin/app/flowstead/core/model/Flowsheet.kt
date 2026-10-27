/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import app.flowstead.core.units.StandardConditions
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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
    /** Reactions reactors can use. Written only when there are some, so flowsheets without reactions keep their hash. */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val reactions: List<Reaction> = emptyList(),
    /** User-defined pseudo-components (HYSYS hypotheticals); each name is also in [compounds]. Written only when there are some. */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val hypotheticals: List<Hypothetical> = emptyList(),
    /** Liquid density method: null for the engine default, [LiquidDensityMethods.COSTALD] or [LiquidDensityMethods.EOS]. Written only when set. */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val liquidDensity: String? = null,
    /** Cubic EOS volume shift: null for DWSIM's Peneloux shift, [VolumeShiftMethods.NONE] for the plain EOS (as HYSYS's PR). Written only when set. */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val volumeShift: String? = null,
    /** Vapour viscosity: null for DWSIM's, [VapourViscosityMethods.LGE] for Lee–Gonzalez–Eakin. Written only when set. */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val vapourViscosity: String? = null,
    /** The user's kij for the cubic EOS (D58), over DWSIM's databank for those pairs. Written only when there are some. */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val interactionParameters: List<BinaryParameter> = emptyList(),
    /** Line-sizing limits by service. Not part of the solved model: excluded from [modelHash]. */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val sizingCriteria: SizingCriteria? = null,
    /** Title-block details for data sheets. Not part of the solved model: excluded from [modelHash]. */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val documentInfo: DocumentInfo? = null,
    /** Property tables shown on the flowsheet (and the printed PFD). Not part of the solved model: excluded from [modelHash]. */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val tables: List<PropertyTable> = emptyList(),
    /**
     * Solver mode, as HYSYS: false = Hold (solve on demand), true = Active (re-solve after every change). Saved with
     * the project. Not part of the solved model: excluded from [modelHash]. Written only when Active.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val solverActive: Boolean = false,
    /** Case studies and their last results (D51). Not part of the solved model: excluded from [modelHash]. */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val caseStudies: List<CaseStudy> = emptyList(),
    /** Relief valves and depressuring cases (D54–D57). Not part of the solved model: excluded from [modelHash]. */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val safety: SafetyStudy? = null,
) {
    fun reaction(id: String): Reaction? = reactions.firstOrNull { it.id == id }
    fun hypothetical(name: String): Hypothetical? = hypotheticals.firstOrNull { it.name == name }

    companion object {
        const val SCHEMA_VERSION = 1
        val EMPTY = Flowsheet()
    }
}

/** Liquid density methods; same strings as the engine's LiquidDensityMethods. */
object LiquidDensityMethods {
    const val COSTALD = "costald"
    /** Liquid density from the cubic EOS itself (D53). */
    const val EOS = "eos"
}

/** Volume shift settings; same strings as the engine's VolumeShiftMethods. */
object VolumeShiftMethods {
    const val NONE = "none"
}

/** Vapour viscosity methods; same strings as the engine's VapourViscosityMethods. */
object VapourViscosityMethods {
    const val LGE = "lge"
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
    /** Data-sheet fields typed by the user ([SheetKeys]). Not part of the solved model: excluded from [modelHash]. */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val sheet: Map<String, String> = emptyMap(),
    /** Pump or compressor performance curve; when given, the machine runs on it (D48). Written only when set. */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val curve: PerformanceCurve? = null,
    /** Separator sizing basis (D61); not part of the solved model. Written only when set. */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val sizing: SeparatorSizingInput? = null,
    /** Sizing basis of exchangers, heaters, coolers, machines and tanks (D62); not part of the solved model. */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val equipmentSizing: EquipmentSizingInput? = null,
)

/**
 * A pump or compressor curve at its rated speed, as the vendor gives it. The lowest flow is the compressor's surge
 * limit and the highest its stonewall; for a pump, its minimum and run-out flows. Same shape as the engine's.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PerformanceCurve(
    val points: List<CurvePoint> = emptyList(),
    /** Speed of [points], rpm; needed with [speedLines] or [operatingSpeed]. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val speed: Double? = null,
    /** Curves at other speeds (HYSYS's multiple curves) for a variable-speed machine. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val speedLines: List<SpeedLine> = emptyList(),
    /**
     * The speed it runs at, rpm; null: [speed], or with an outlet pressure given, the speed that meets it, which the
     * engine finds. The engine reads the map at the speed by the affinity laws (D48).
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val operatingSpeed: Double? = null,
    /** How the points are joined: null (straight lines) or [CurveFits.POLYNOMIAL], a least-squares fit as HYSYS's. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val fit: String? = null,
    /** Polynomial order; null: the smaller of 3 and points − 1. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val order: Int? = null,
) {
    /** A variable-speed machine whose speed the engine finds from the pressure given. */
    val solvesSpeed: Boolean get() = (speed ?: 0.0) > 0 && operatingSpeed == null
}

object CurveFits {
    const val POLYNOMIAL = "polynomial"
}

/** kij between compounds [a] and [b] (symmetric). Same as the engine's BinaryParameter. */
@Serializable
data class BinaryParameter(val a: String, val b: String, val kij: Double)

/** One curve of a multi-speed map. */
@Serializable
data class SpeedLine(val speed: Double, val points: List<CurvePoint> = emptyList())

/** [flow]: actual inlet m³/s; [head]: m (compressors: adiabatic head); [efficiency]: fraction; [npshr]: pumps, m. */
@Serializable
data class CurvePoint(
    val flow: Double,
    val head: Double,
    @OptIn(ExperimentalSerializationApi::class) @EncodeDefault(EncodeDefault.Mode.NEVER) val efficiency: Double? = null,
    @OptIn(ExperimentalSerializationApi::class) @EncodeDefault(EncodeDefault.Mode.NEVER) val npshr: Double? = null,
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
    @SerialName("relief_valve") ReliefValve(Category.Piping),

    @SerialName("mixer") Mixer(Category.Mixing),
    @SerialName("splitter") Splitter(Category.Mixing),
    @SerialName("component_splitter") ComponentSplitter(Category.Mixing),

    @SerialName("separator") Separator(Category.Separators),
    @SerialName("three_phase_separator") ThreePhaseSeparator(Category.Separators),
    @SerialName("tank") Tank(Category.Separators),
    @SerialName("filter") Filter(Category.Separators, available = false),
    @SerialName("solids_separator") SolidsSeparator(Category.Separators, available = false),

    @SerialName("shortcut_column") ShortcutColumn(Category.Columns),
    @SerialName("distillation_column") DistillationColumn(Category.Columns),
    @SerialName("absorber") Absorber(Category.Columns),
    @SerialName("refluxed_absorber") RefluxedAbsorber(Category.Columns, available = false),
    @SerialName("reboiled_absorber") ReboiledAbsorber(Category.Columns),

    @SerialName("conversion_reactor") ConversionReactor(Category.Reactors),
    @SerialName("equilibrium_reactor") EquilibriumReactor(Category.Reactors),
    @SerialName("gibbs_reactor") GibbsReactor(Category.Reactors),
    @SerialName("cstr") Cstr(Category.Reactors),
    @SerialName("pfr") Pfr(Category.Reactors),

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
