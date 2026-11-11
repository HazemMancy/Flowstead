/*
 * Flowstead — backward specifications (D151, owner: "why its not like hysys, the solver is forward and backward").
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

/**
 * A product stream of a valve, heater, cooler, air cooler, pump, compressor, expander or pipe segment can take
 * specifications, as in HYSYS: what the product must be, with the feed upstream left to be found. The chain back from
 * the product crosses such units only, each with one material inlet, to a feed.
 *
 * - Composition and flow pass through those units unchanged: the product's are the feed's (copied to it).
 * - Its temperature, pressure or vapour fraction are targets: each is met by varying one state value the feed lacks
 *   (its temperature, else its pressure), solved as an Adjust by the engine (every iteration a whole-flowsheet solve,
 *   the same Newton/Broyden as the user's Adjusts, D84). The feed then needs, of its own, two state values less the
 *   number of targets.
 *
 * This file is the rule; the engine applies the same one (engine/src/Flowstead.Engine.Worker/BackSolver.cs).
 */
object Backward {
    /** Units a backward specification crosses: one material inlet, one outlet, flow and composition unchanged. */
    val UNITS = setOf(
        ObjectType.Valve, ObjectType.Heater, ObjectType.Cooler, ObjectType.AirCooler, ObjectType.Pump,
        ObjectType.Compressor, ObjectType.Expander, ObjectType.PipeSegment,
    )

    /** What a product stream can be given: state targets, and the flow and composition the feed takes. */
    val STATE = listOf(SpecKey.TEMPERATURE, SpecKey.PRESSURE, SpecKey.VAPOUR_FRACTION)
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
    fun producer(fs: Flowsheet, stream: SimObject): SimObject? {
        if (stream.type != ObjectType.MaterialStream) return null
        val c = fs.connections.firstOrNull { it.toObject == stream.id } ?: return null
        return fs.obj(c.fromObject)?.takeIf { it.type in UNITS && c.fromPort == 0 }
    }

    /** True when [stream] may take product specifications (it leaves a unit a backward specification can cross). */
    fun canTake(fs: Flowsheet, stream: SimObject): Boolean = producer(fs, stream) != null

    /** True when the product stream [o] carries backward specifications. */
    fun has(fs: Flowsheet, o: SimObject): Boolean =
        o.type == ObjectType.MaterialStream && !fs.isFeed(o) &&
            (o.specs.keys.any { it in KEYS || it == SpecKey.MASS_ENTHALPY } || o.composition.isNotEmpty())

    private fun inletOf(fs: Flowsheet, unit: SimObject): SimObject? =
        fs.connections.firstOrNull { it.toObject == unit.id && it.toPort == 0 }?.let { fs.obj(it.fromObject) }?.takeIf { it.type == ObjectType.MaterialStream }

    /** The plan for product [product]; null when it has no backward specifications. */
    fun plan(fs: Flowsheet, product: SimObject): Plan? {
        if (!has(fs, product)) return null
        fun fail(why: String, feed: SimObject? = null, units: List<SimObject> = emptyList()) = Plan(product, feed, units, emptyList(), emptyMap(), null, why)
        if (SpecKey.MASS_ENTHALPY in product.specs) return fail("A product takes its temperature, pressure or vapour fraction, not its enthalpy")
        val first = producer(fs, product)
            ?: return fail("Only the product of a valve, heater, cooler, air cooler, pump, compressor, expander or pipe segment can be specified: give this one's feed instead")
        // Back through the chain to a feed.
        val units = mutableListOf<SimObject>()
        var unit: SimObject = first
        var feed: SimObject
        while (true) {
            units += unit
            feed = inletOf(fs, unit) ?: return fail("Connect ${unit.tag}'s inlet first", units = units)
            if (fs.isFeed(feed)) break
            val up = producer(fs, feed) ?: return fail(
                "${feed.tag} comes from a unit a backward specification can't cross (only valves, heaters, coolers, air coolers, pumps, compressors, expanders and pipe segments): specify ${feed.tag}'s feed instead", feed, units)
            if (has(fs, feed)) return fail("${feed.tag} is specified too: give the specification on one stream of the line", feed, units)
            if (up in units || units.size > 50) return fail("The line from ${product.tag} loops back on itself", feed, units)
            unit = up
        }

        // Flow and composition: copied, never on both.
        val copied = LinkedHashMap<String, Spec>()
        val productFlow = FLOW.filter { it in product.specs }
        if (productFlow.size > 1) return fail("Give a molar or a mass flow, not both", feed, units)
        productFlow.firstOrNull()?.let { k ->
            if (FLOW.any { it in feed.specs }) return fail("The flow is given on ${feed.tag} and on ${product.tag}: give it on one of them", feed, units)
            copied[k] = product.specs.getValue(k)
        }
        val composition = product.composition.takeIf { it.isNotEmpty() }
        if (composition != null && feed.composition.isNotEmpty()) return fail("The composition is given on ${feed.tag} and on ${product.tag}: give it on one of them", feed, units)

        // State targets, each met by a state value the feed lacks: its temperature first, then its pressure.
        val wanted = STATE.filter { it in product.specs }
        val feedState = SpecKey.STREAM_STATE.filter { it in feed.specs }
        if (wanted.isEmpty()) return Plan(product, feed, units, emptyList(), copied, composition, null)
        if (feedState.size + wanted.size > 2) return fail(
            "${feed.tag} already has ${feedState.size} of its two state values: with ${wanted.size} on ${product.tag} the line is over-specified (take ${feedState.size + wanted.size - 2} away)", feed, units)
        val free = listOf(SpecKey.TEMPERATURE, SpecKey.PRESSURE).filter { it !in feed.specs }
        // A target is met by the same variable on the feed where it can be (pressure by pressure), else by what's left.
        val pairs = mutableListOf<Pair<String, String>>()
        val open = free.toMutableList()
        for (w in wanted.sortedBy { if (it in open) 0 else 1 }) {
            val f = if (w in open) w else open.firstOrNull() ?: return fail("${feed.tag} has nothing left to vary for ${product.tag}'s ${SpecKey.label(w).lowercase()}", feed, units)
            open.remove(f); pairs += w to f
        }
        // A unit that fixes its own outlet leaves nothing upstream to find.
        for (u in units) {
            if (SpecKey.TEMPERATURE in wanted && SpecKey.OUTLET_TEMPERATURE in u.specs && u === first)
                return fail("${u.tag} fixes its outlet temperature: give its duty instead, or take the temperature off ${product.tag}", feed, units)
            if (SpecKey.PRESSURE in wanted && SpecKey.OUTLET_PRESSURE in u.specs && u === first)
                return fail("${u.tag} fixes its outlet pressure: give its pressure change instead, or take the pressure off ${product.tag}", feed, units)
        }
        val targets = pairs.map { (w, f) -> Target(w, f, product.specs.getValue(w).si) }
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
            for (t in p.targets) specs[t.feedKey] = Spec(guess(p, t), if (t.feedKey == SpecKey.TEMPERATURE) "K" else "Pa")
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
            SpecKey.TEMPERATURE -> product[SpecKey.TEMPERATURE]?.si ?: 300.0
            else -> {
                val drops = p.units.sumOf { it.specs[SpecKey.PRESSURE_DROP]?.si ?: 0.0 } - p.units.sumOf { it.specs[SpecKey.PRESSURE_RISE]?.si ?: 0.0 }
                product[SpecKey.PRESSURE]?.si?.let { maxOf(1e4, it + drops) } ?: 10e5
            }
        }
    }
}
