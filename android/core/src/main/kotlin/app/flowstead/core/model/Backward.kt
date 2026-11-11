/*
 * Flowstead — backward specifications (D151, owner: "why its not like hysys, the solver is forward and backward").
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

/**
 * A product stream can take specifications, as in HYSYS: what the product must be, with the feed upstream left to be
 * found (D151, widened in D154). The line back from the product crosses, to a feed:
 * - units that keep flow and composition: valves, heaters, coolers, air coolers, pumps, compressors, expanders, pipe
 *   segments and orifice plates (outlet from inlet), and each side of a heat exchanger (side A from A, B from B);
 * - a splitter (any outlet: the composition is the feed's, the flow a share of it);
 * - a separator, 3-phase separator, tank or component splitter with one feed (any outlet: flow and composition change).
 *
 * - Composition passes only units that keep it: then the product's is the feed's (copied). Across a separator it is refused.
 * - Flow: copied where every unit keeps it; across a splitter or separator it is a target, met by varying the feed's flow.
 * - Temperature, pressure, vapour fraction or enthalpy are targets: each is met by varying one state value the feed lacks
 *   (its temperature, else its pressure; an enthalpy by the temperature). Every target is solved as an Adjust by the
 *   engine (every iteration a whole-flowsheet solve, the same Newton/Broyden as the user's Adjusts, D84). The feed then
 *   needs, of its own, two state values less the number of state targets.
 *
 * This file is the rule; the engine applies the same one (engine/src/Flowstead.Engine.Worker/BackSolver.cs).
 */
object Backward {
    /** Units with one material inlet and outlet that keep flow and composition. */
    val PASS = setOf(
        ObjectType.Valve, ObjectType.Heater, ObjectType.Cooler, ObjectType.AirCooler, ObjectType.Pump,
        ObjectType.Compressor, ObjectType.Expander, ObjectType.PipeSegment, ObjectType.OrificePlate,
    )

    /** Units whose outlets share their one feed: the composition is kept, the flow is not. */
    val SPLITS = setOf(ObjectType.Splitter)

    /** Units whose outlets are phases or cuts of their one feed: neither flow nor composition is kept. */
    val PHASES = setOf(ObjectType.Separator, ObjectType.ThreePhaseSeparator, ObjectType.Tank, ObjectType.ComponentSplitter)

    /** Every unit a backward specification crosses. */
    val UNITS = PASS + SPLITS + PHASES + ObjectType.HeatExchanger

    /** What a product stream can be given: state targets, and the flow and composition the feed takes. */
    val STATE = listOf(SpecKey.TEMPERATURE, SpecKey.PRESSURE, SpecKey.VAPOUR_FRACTION, SpecKey.MASS_ENTHALPY)
    val FLOW = listOf(SpecKey.MOLAR_FLOW, SpecKey.MASS_FLOW)
    val KEYS = STATE + FLOW

    /** One state target: the product's [productKey] met by varying the feed's [feedKey]. */
    data class Target(val productKey: String, val feedKey: String, val value: Double)

    /** A product's backward specifications, the feed they solve for and how; [problem] when they can't. */
    data class Plan(
        val product: SimObject,
        val feed: SimObject?,
        val units: List<SimObject>,
        val targets: List<Target>,
        /** What the feed takes from the product as it is (flow and composition). */
        val copied: Map<String, Spec>,
        val composition: Map<String, Double>?,
        val problem: String?,
    )

    /** The unit a product stream leaves, when a backward specification could be put on it. */
    fun producer(fs: Flowsheet, stream: SimObject): SimObject? = step(fs, stream)?.first

    /** The unit [stream] leaves and the inlet port its line goes back through; null when it can't be crossed. */
    private fun step(fs: Flowsheet, stream: SimObject): Pair<SimObject, Int>? {
        if (stream.type != ObjectType.MaterialStream) return null
        val c = fs.connections.firstOrNull { it.toObject == stream.id } ?: return null
        val u = fs.obj(c.fromObject) ?: return null
        return when (u.type) {
            in PASS -> if (c.fromPort == 0) u to 0 else null
            ObjectType.HeatExchanger -> if (c.fromPort in 0..1) u to c.fromPort else null
            in SPLITS, in PHASES -> u to 0
            else -> null
        }
    }

    /** Whether a unit keeps the flow and the composition of the line through it. */
    private fun keepsFlow(u: SimObject) = u.type in PASS || u.type == ObjectType.HeatExchanger
    private fun keepsComposition(u: SimObject) = keepsFlow(u) || u.type in SPLITS

    /** True when [stream] may take product specifications (it leaves a unit a backward specification can cross). */
    fun canTake(fs: Flowsheet, stream: SimObject): Boolean = producer(fs, stream) != null

    /** True when the product stream [o] carries backward specifications. */
    fun has(fs: Flowsheet, o: SimObject): Boolean =
        o.type == ObjectType.MaterialStream && !fs.isFeed(o) &&
            (o.specs.keys.any { it in KEYS || it == SpecKey.MASS_ENTHALPY } || o.composition.isNotEmpty())

    private fun inletOf(fs: Flowsheet, unit: SimObject, port: Int): SimObject? =
        fs.connections.firstOrNull { it.toObject == unit.id && it.toPort == port }?.let { fs.obj(it.fromObject) }?.takeIf { it.type == ObjectType.MaterialStream }

    /** A separator, tank or splitter with a second feed: the line back would have to choose between them. */
    private fun moreThanOneFeed(fs: Flowsheet, unit: SimObject) =
        (unit.type in SPLITS || unit.type in PHASES) && fs.connections.count { it.toObject == unit.id && fs.obj(it.fromObject)?.type == ObjectType.MaterialStream } > 1

    /** The plan for product [product]; null when it has no backward specifications. */
    fun plan(fs: Flowsheet, product: SimObject): Plan? {
        if (!has(fs, product)) return null
        fun fail(why: String, feed: SimObject? = null, units: List<SimObject> = emptyList()) = Plan(product, feed, units, emptyList(), emptyMap(), null, why)
        val first = producer(fs, product)
            ?: return fail("Only the product of a valve, heater, cooler, air cooler, pump, compressor, expander, pipe segment, orifice plate, heat exchanger, splitter, separator or tank can be specified: give this one's feed instead")
        // Back through the chain to a feed.
        val units = mutableListOf<SimObject>()
        var at: SimObject = product
        var feed: SimObject
        while (true) {
            val (unit, port) = step(fs, at)!!
            if (unit in units || units.size > 50) return fail("The line from ${product.tag} loops back on itself", units = units)
            if (moreThanOneFeed(fs, unit)) return fail("${unit.tag} has more than one feed: specify the feed that should change instead", units = units + unit)
            units += unit
            feed = inletOf(fs, unit, port) ?: return fail("Connect ${unit.tag}'s inlet first", units = units)
            if (fs.isFeed(feed)) break
            step(fs, feed) ?: return fail(
                "${feed.tag} comes from a unit a backward specification can't cross (a mixer, column or reactor): specify ${feed.tag}'s feed instead", feed, units)
            if (has(fs, feed)) return fail("${feed.tag} is specified too: give the specification on one stream of the line", feed, units)
            at = feed
        }
        val flowKept = units.all(::keepsFlow)
        val compositionKept = units.all(::keepsComposition)

        // Flow: copied where every unit keeps it, else a target met by the feed's flow. Composition: copied, where kept.
        val copied = LinkedHashMap<String, Spec>()
        val productFlow = FLOW.filter { it in product.specs }
        if (productFlow.size > 1) return fail("Give a molar or a mass flow, not both", feed, units)
        if (productFlow.isNotEmpty() && FLOW.any { it in feed.specs }) return fail("The flow is given on ${feed.tag} and on ${product.tag}: give it on one of them", feed, units)
        if (flowKept) productFlow.firstOrNull()?.let { copied[it] = product.specs.getValue(it) }
        val composition = product.composition.takeIf { it.isNotEmpty() }
        if (composition != null && !compositionKept) {
            val by = units.first { !keepsComposition(it) }
            return fail("${by.tag} changes the composition, so ${product.tag}'s can't be given: give ${feed.tag}'s instead", feed, units)
        }
        if (composition != null && feed.composition.isNotEmpty()) return fail("The composition is given on ${feed.tag} and on ${product.tag}: give it on one of them", feed, units)
        if (!flowKept && productFlow.isNotEmpty() && composition == null && feed.composition.isEmpty())
            return fail("${feed.tag} needs its composition before its flow can be found from ${product.tag}'s", feed, units)
        val flowTargets = if (flowKept) emptyList() else productFlow.map { Target(it, it, product.specs.getValue(it).si) }

        // State targets, each met by a state value the feed lacks: its temperature first, then its pressure.
        val wanted = STATE.filter { it in product.specs }
        val feedState = SpecKey.STREAM_STATE.filter { it in feed.specs }
        if (wanted.isEmpty()) return Plan(product, feed, units, flowTargets, copied, composition, null)
        if (feedState.size + wanted.size > 2) return fail(
            "${feed.tag} already has ${feedState.size} of its two state values: with ${wanted.size} on ${product.tag} the line is over-specified (take ${feedState.size + wanted.size - 2} away)", feed, units)
        val free = listOf(SpecKey.TEMPERATURE, SpecKey.PRESSURE).filter { it !in feed.specs }
        // A target is met by the same variable on the feed where it can be (pressure by pressure), else by what's left;
        // an enthalpy by the temperature, as it moves the enthalpy most directly.
        val pairs = mutableListOf<Pair<String, String>>()
        val open = free.toMutableList()
        fun prefer(w: String) = if (w == SpecKey.MASS_ENTHALPY) SpecKey.TEMPERATURE else w
        for (w in wanted.sortedBy { if (prefer(it) in open) 0 else 1 }) {
            val f = if (prefer(w) in open) prefer(w) else open.firstOrNull() ?: return fail("${feed.tag} has nothing left to vary for ${product.tag}'s ${SpecKey.label(w).lowercase()}", feed, units)
            open.remove(f); pairs += w to f
        }
        if (SpecKey.VAPOUR_FRACTION in wanted && first.type in PHASES)
            return fail("${first.tag}'s outlets are each one phase, so ${product.tag}'s vapour fraction can't be a target: give its temperature or pressure", feed, units)
        // A unit that fixes its own outlet leaves nothing upstream to find.
        val heats = SpecKey.TEMPERATURE in wanted || SpecKey.MASS_ENTHALPY in wanted
        if (heats && (SpecKey.OUTLET_TEMPERATURE in first.specs || (first.type == ObjectType.HeatExchanger &&
                (SpecKey.HOT_OUTLET_TEMPERATURE in first.specs || SpecKey.COLD_OUTLET_TEMPERATURE in first.specs))))
            return fail("${first.tag} fixes its outlet temperature: give its duty instead, or take the temperature off ${product.tag}", feed, units)
        if (SpecKey.PRESSURE in wanted && SpecKey.OUTLET_PRESSURE in first.specs)
            return fail("${first.tag} fixes its outlet pressure: give its pressure change instead, or take the pressure off ${product.tag}", feed, units)
        val targets = pairs.map { (w, f) -> Target(w, f, product.specs.getValue(w).si) } + flowTargets
        return Plan(product, feed, units, targets, copied, composition, null)
    }

    /** Every product's plan. */
    fun plans(fs: Flowsheet): List<Plan> = fs.objects.mapNotNull { plan(fs, it) }

    /**
     * The flowsheet as the readiness checks see it: each feed counts what its products give it (flow, composition, and
     * a state value for each target) as given. The values for the targets are only stand-ins; the engine finds them.
     */
    fun applied(fs: Flowsheet): Flowsheet {
        val ps = plans(fs).filter { it.problem == null && it.feed != null }
        if (ps.isEmpty()) return fs
        val byId = fs.objects.associateBy { it.id }.toMutableMap()
        for (p in ps) {
            val feed = byId.getValue(p.feed!!.id)
            val specs = feed.specs.toMutableMap()
            specs.putAll(p.copied)
            for (t in p.targets) specs[t.feedKey] = Spec(guess(p, t), when (t.feedKey) { SpecKey.TEMPERATURE -> "K"; SpecKey.MOLAR_FLOW -> "mol/s"; SpecKey.MASS_FLOW -> "kg/s"; else -> "Pa" })
            byId[feed.id] = feed.copy(specs = specs, composition = p.composition ?: feed.composition)
        }
        return fs.copy(objects = fs.objects.map { byId.getValue(it.id) })
    }

    /**
     * Where the engine starts the feed's value for target [t]: the product's own value where it is the same variable,
     * the product's pressure raised by the pressure drops given along the line, else a value a gas or liquid would have.
     */
    fun guess(p: Plan, t: Target): Double {
        val product = p.product.specs
        return when (t.feedKey) {
            // The feed carries at least the product's flow: start from twice it (a separator or splitter takes a share).
            SpecKey.MOLAR_FLOW, SpecKey.MASS_FLOW -> 2 * t.value
            SpecKey.TEMPERATURE -> product[SpecKey.TEMPERATURE]?.si ?: 300.0
            else -> {
                val drops = p.units.sumOf { it.specs[SpecKey.PRESSURE_DROP]?.si ?: 0.0 } - p.units.sumOf { it.specs[SpecKey.PRESSURE_RISE]?.si ?: 0.0 }
                product[SpecKey.PRESSURE]?.si?.let { maxOf(1e4, it + drops) } ?: 10e5
            }
        }
    }
}
