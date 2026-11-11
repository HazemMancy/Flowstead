/*
 * Flowstead web (D125): dynamics and PID tuning in the browser, as the app's DynamicsSheet and ToolsSheet's PID tool.
 * The setup, the engine requests, the trends and the tuning are the app's own core (Dynamics, PidTuning,
 * FlowsheetEditing); the page collects inputs and lays the reports out. Every number shown is the engine's, or a
 * rule's formula applied to the model fitted to the engine's response.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
@file:OptIn(ExperimentalJsExport::class)

package app.flowstead.web

import app.flowstead.core.api.DynamicsRequest
import app.flowstead.core.api.DynamicsResult
import app.flowstead.core.api.MessageLevel
import app.flowstead.core.format.ValueFormat
import app.flowstead.core.model.AdjustKeys
import app.flowstead.core.model.DynamicEvent
import app.flowstead.core.model.DynamicKey
import app.flowstead.core.model.Dynamics
import app.flowstead.core.model.EditRejected
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.FlowsheetEditing
import app.flowstead.core.model.FlowsheetJson
import app.flowstead.core.model.ObjectType
import app.flowstead.core.model.Plot
import app.flowstead.core.model.PlotSeries
import app.flowstead.core.model.PidTuning
import app.flowstead.core.model.ResultKeys
import app.flowstead.core.model.Spec
import app.flowstead.core.model.displayName
import app.flowstead.core.model.modelHash
import app.flowstead.core.model.obj
import app.flowstead.core.units.EngUnit
import app.flowstead.core.units.Quantity
import app.flowstead.core.units.UnitContext
import app.flowstead.core.units.Units
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

@JsExport
object DynamicsTools {
    private fun fs(json: String): Flowsheet = FlowsheetJson.decodeFromString(Flowsheet.serializer(), json)
    private fun err(message: String) = buildJsonObject { put("error", message) }.toString()
    private fun out(f: Flowsheet) = buildJsonObject { put("flowsheet", FlowsheetJson.encodeToJsonElement(Flowsheet.serializer(), f)) }.toString()
    private fun call(req: DynamicsRequest) = buildJsonObject { put("method", "dynamics"); put("params", FlowsheetJson.encodeToJsonElement(DynamicsRequest.serializer(), req)) }.toString()
    private fun obj(json: String): JsonObject = FlowsheetJson.parseToJsonElement(json.ifBlank { "{}" }).jsonObject

    /** The unit a field starts in: the user's unit for the quantity, as the app's defaultUnit. */
    private fun unitFor(q: Quantity): EngUnit = displayUnits.unitFor(q)

    /** Tuning times and the step are seconds, as their hints and every PID rule give them; the run's length is in the user's unit. */
    private val SECONDS = setOf("step", DynamicKey.INTEGRAL_TIME, DynamicKey.DERIVATIVE_TIME)

    /**
     * The app's DynamicsSheet as form items: {problem, items: [...]}, each item one of note, label, q (a quantity with
     * its units and the key it edits) and choice (a controller's action).
     */
    fun form(flowsheetJson: String): String {
        val f = fs(flowsheetJson)
        val ctx = UnitContext(f.atmosphericPressurePa)
        val setup = Dynamics.setup(f)
        val items = mutableListOf<JsonElement>()
        fun label(t: String) { items += buildJsonObject { put("kind", "label"); put("text", t) } }
        fun sub(t: String) { items += buildJsonObject { put("kind", "sub"); put("text", t) } }
        fun q(id: String, key: String, label: String, si: Double?, quantity: Quantity, hint: String? = null) {
            // A starting level and opening read as their hints do ("50 %"), in %.
            val def = if (key in SECONDS) Units.second else if (key == DynamicKey.INITIAL_LEVEL || key == DynamicKey.OPENING) Units.percent else unitFor(quantity)
            items += buildJsonObject {
                put("kind", "q"); put("id", id); put("key", key); put("label", label); put("unit", def.id)
                putJsonArray("units") {
                    Units.forQuantity(quantity).forEach { u ->
                        addJsonObject { put("id", u.id); put("symbol", u.symbol.ifEmpty { u.id }); put("text", si?.let { ValueFormat.number(u.fromSi(it, ctx), u.decimals).replace(",", "") } ?: "") }
                    }
                }
                hint?.let { put("hint", it) }
            }
        }
        fun param(id: String, key: String, hint: String? = null, quantity: Quantity = DynamicKey.quantity(key), label: String = DynamicKey.label(key)) =
            q(id, key, label, Dynamics.parameter(f, id, key)?.si, quantity, hint)

        label("Run")
        q("", "duration", "Duration", setup.duration, Quantity.Time)
        q("", "step", "Step", setup.step, Quantity.Time, "smaller steps are taken where a vessel changes fast")
        val vessels = f.objects.filter { it.type == ObjectType.Separator }
        if (vessels.isNotEmpty()) {
            label("Vessels")
            for (v in vessels) {
                sub("${v.tag} · ${v.type.displayName()}")
                param(v.id, DynamicKey.VOLUME)
                param(v.id, DynamicKey.HEIGHT, "three diameters if empty")
                param(v.id, DynamicKey.INITIAL_LEVEL, "50 % if empty")
            }
        }
        val valves = f.objects.filter { it.type == ObjectType.Valve }
        if (valves.isNotEmpty()) {
            label("Valves")
            for (v in valves) {
                sub(v.tag)
                param(v.id, DynamicKey.RATED_CV, "sized to pass the steady flow if empty")
                param(v.id, DynamicKey.OPENING, "50 %, or what passes the steady flow with a rated Cv")
            }
        }
        val controllers = f.objects.filter { it.type == ObjectType.Controller }
        if (controllers.isNotEmpty()) {
            label("Controllers (PID)")
            for (c in controllers) {
                val pv = c.refs[AdjustKeys.CONTROLLED_PROPERTY]
                val measured = c.refs[AdjustKeys.CONTROLLED_OBJECT]?.let { f.obj(it)?.tag }
                val moved = c.refs[AdjustKeys.MANIPULATED_OBJECT]?.let { f.obj(it)?.tag }
                sub("${c.tag} · ${measured ?: "?"} ${pv?.let { ResultKeys.label(it).lowercase() } ?: ""} → ${moved ?: "?"}")
                param(c.id, DynamicKey.GAIN, "%/% of span; level 2, pressure 2, flow 0.5, temperature 1 if empty")
                param(c.id, DynamicKey.INTEGRAL_TIME, "level 300 s, pressure 30 s, flow 5 s, temperature 120 s if empty")
                param(c.id, DynamicKey.DERIVATIVE_TIME, "none if empty")
                pv?.let { key ->
                    val quantity = ResultKeys.quantity(key)
                    param(c.id, DynamicKey.PV_MINIMUM, "the span: twice the setpoint, the vessel's height for level, 100 K for temperature", quantity)
                    param(c.id, DynamicKey.PV_MAXIMUM, null, quantity)
                }
                val action = Dynamics.parameter(f, c.id, DynamicKey.ACTION)?.si
                items += buildJsonObject {
                    put("kind", "action"); put("id", c.id); put("label", "Action")
                    put("value", when { action == null -> "auto"; action > 0 -> "direct"; else -> "reverse" })
                    putJsonArray("options") {
                        listOf("auto" to "From the process", "direct" to "Direct (output rises with the measurement)", "reverse" to "Reverse (output falls as the measurement rises)")
                            .forEach { (i, l) -> addJsonObject { put("id", i); put("label", l) } }
                    }
                }
            }
        }
        return buildJsonObject {
            Dynamics.problem(f)?.let { put("problem", it) }
            put("items", kotlinx.serialization.json.JsonArray(items))
            putJsonArray("events") {
                setup.events.forEach { e ->
                    addJsonObject {
                        put("text", "At ${ValueFormat.number(unitFor(Quantity.Time).fromSi(e.time, ctx), unitFor(Quantity.Time).decimals)} ${unitFor(Quantity.Time).symbol} · " +
                            "${f.obj(e.objectId)?.tag ?: "?"} ${Dynamics.eventLabel(e.key).lowercase()} → ${eventValue(f, e, ctx)}")
                    }
                }
            }
            // What an event can change: each object with its keys, and each key's quantity with its units.
            putJsonArray("eventObjects") {
                f.objects.forEach { o ->
                    val keys = Dynamics.eventKeys(f, o)
                    if (keys.isEmpty()) return@forEach
                    addJsonObject {
                        put("id", o.id); put("label", "${o.tag} · ${o.type.displayName()}")
                        putJsonArray("keys") {
                            keys.forEach { (k, q) ->
                                addJsonObject {
                                    put("key", k); put("label", Dynamics.eventLabel(k))
                                    if (k == DynamicKey.MODE) put("mode", true)
                                    else {
                                        val def = if (k == DynamicKey.OPENING) Units.percent else unitFor(q)
                                        put("unit", def.id)
                                        putJsonArray("units") { Units.forQuantity(q).forEach { u -> addJsonObject { put("id", u.id); put("symbol", u.symbol.ifEmpty { u.id }) } } }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            put("timeUnit", unitFor(Quantity.Time).id)
            putJsonArray("timeUnits") { Units.forQuantity(Quantity.Time).forEach { u -> addJsonObject { put("id", u.id); put("symbol", u.symbol) } } }
            put("controllers", kotlinx.serialization.json.JsonArray(controllers.map { c -> buildJsonObject { put("id", c.id); put("label", c.tag) } }))
        }.toString()
    }

    private fun eventValue(f: Flowsheet, e: DynamicEvent, ctx: UnitContext): String {
        val o = f.obj(e.objectId) ?: return ValueFormat.number(e.value, 3)
        if (e.key == DynamicKey.MODE) return if (e.value >= 0.5) "automatic" else "manual"
        val q = Dynamics.eventKeys(f, o).firstOrNull { it.first == e.key }?.second ?: return ValueFormat.number(e.value, 3)
        val u = if (e.key == DynamicKey.OPENING) Units.percent else unitFor(q)
        return (ValueFormat.number(u.fromSi(e.value, ctx), u.decimals) + " " + u.symbol).trimEnd()
    }

    private fun number(text: String?): Double? {
        val t = text?.trim()?.replace(',', '.') ?: return null
        if (t.isEmpty()) return null
        return t.toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw EditRejected("Not a number: $t")
    }
    private fun unit(id: String?): EngUnit = Units.byIdOrNull(id ?: "") ?: throw EditRejected("Unknown unit")

    /**
     * An edit, as the app's DynamicsActions: {op: "param", id, key, value, unit} (blank clears it; "duration" and "step"
     * with no id set the run), {op: "action", id, value: auto|direct|reverse}, {op: "addEvent", time, timeUnit,
     * objectId, key, value, unit}, {op: "removeEvent", index}. Returns {flowsheet} or {error}.
     */
    fun apply(flowsheetJson: String, actionJson: String): String = try {
        val f = fs(flowsheetJson)
        val a = obj(actionJson)
        fun s(k: String) = a[k]?.jsonPrimitive?.content
        val ctx = UnitContext(f.atmosphericPressurePa)
        out(when (s("op")) {
            "param" -> {
                val id = s("id").orEmpty()
                val key = s("key")!!
                val v = number(s("value"))
                if (id.isEmpty()) {
                    val si = v?.let { unit(s("unit")).toSi(it, ctx) } ?: throw EditRejected("Give the run's ${key}")
                    if (!(si > 0)) throw EditRejected("The ${key} must be positive")
                    FlowsheetEditing.setDynamics(f) { if (key == "duration") it.copy(duration = si) else it.copy(step = si) }
                } else {
                    f.obj(id) ?: throw EditRejected("That object no longer exists")
                    val u = unit(s("unit"))
                    FlowsheetEditing.setDynamicParameter(f, id, key, v?.let { Spec(u.toSi(it, ctx), u.id) })
                }
            }
            "action" -> FlowsheetEditing.setDynamicParameter(f, s("id")!!, DynamicKey.ACTION, when (s("value")) {
                "direct" -> Spec(1.0, "ratio"); "reverse" -> Spec(-1.0, "ratio"); else -> null
            })
            "addEvent" -> {
                val o = f.obj(s("objectId") ?: "") ?: throw EditRejected("Choose the object")
                val key = s("key") ?: throw EditRejected("Choose what changes")
                val q = Dynamics.eventKeys(f, o).firstOrNull { it.first == key }?.second ?: throw EditRejected("${o.tag} has no $key to change")
                val time = unit(s("timeUnit")).toSi(number(s("time")) ?: throw EditRejected("Give the time"), ctx)
                if (time < 0) throw EditRejected("The time can't be negative")
                val raw = number(s("value")) ?: throw EditRejected("Give the value")
                val value = if (key == DynamicKey.MODE) raw else unit(s("unit")).also { if (it.quantity != q) throw EditRejected("Unknown unit") }.toSi(raw, ctx)
                FlowsheetEditing.setDynamics(f) { it.copy(events = (it.events + DynamicEvent(time, o.id, key, value)).sortedBy { e -> e.time }) }
            }
            "removeEvent" -> {
                val i = s("index")!!.toInt()
                FlowsheetEditing.setDynamics(f) { it.copy(events = it.events.filterIndexed { j, _ -> j != i }) }
            }
            else -> throw EditRejected("Unknown change")
        })
    } catch (e: IllegalArgumentException) { err(e.message ?: "Not allowed") } catch (e: NullPointerException) { err("Incomplete change") }

    /** The dynamic run as an engine call ({method, params}), or {error} with the app's reason it can't run yet. */
    fun request(flowsheetJson: String): String {
        val f = fs(flowsheetJson)
        Dynamics.problem(f)?.let { return err(it) }
        return call(Dynamics.request(f))
    }

    /** What a run's result belongs to: the model and the dynamic setup (the app's dynamicsKey). */
    fun key(flowsheetJson: String): String {
        val f = fs(flowsheetJson)
        return f.modelHash() + ":" + FlowsheetJson.encodeToString(app.flowstead.core.model.DynamicsSetup.serializer(), Dynamics.setup(f)).hashCode()
    }

    /** The run's trends as the app draws them (Dynamics.plots), with the engine's warnings and errors. */
    fun report(flowsheetJson: String, resultJson: String): String {
        val f = fs(flowsheetJson)
        val r = FlowsheetJson.decodeFromString(DynamicsResult.serializer(), resultJson)
        val rep = Report(UnitContext(f.atmosphericPressurePa))
        val t = unitFor(Quantity.Time)
        rep.note("${r.times.lastOrNull()?.let { ValueFormat.number(t.fromSi(it, rep.ctx), t.decimals) } ?: "0"} ${t.symbol} simulated by DWSIM.")
        r.messages.filter { it.level != MessageLevel.Info }.forEach { rep.note(it.text, if (it.level == MessageLevel.Error) "error" else "warn") }
        Dynamics.plots(f, r).forEach { rep.plot(it) }
        return rep.json()
    }

    // ---------- PID tuning from an open-loop step test (the app's PidTool and FlowsheetViewModel.runStepTest) ----------

    /**
     * The first, short run that reads the controller's output at the start ({method, params}), or {error}: the app's
     * checks (the flowsheet can run; a level needs the vessel's height or the controller's range, the span of the gain).
     */
    fun stepTestStart(flowsheetJson: String, controllerId: String): String {
        val f = fs(flowsheetJson)
        Dynamics.problem(f)?.let { return err(it) }
        val c = f.obj(controllerId)?.takeIf { it.type == ObjectType.Controller } ?: return err("Choose a controller")
        val params = Dynamics.setup(f).parameters
        val ranged = params[controllerId]?.let { it[DynamicKey.PV_MINIMUM] != null && it[DynamicKey.PV_MAXIMUM] != null } == true
        if (c.refs[AdjustKeys.CONTROLLED_PROPERTY] == "liquidLevel" && !ranged && params[c.refs[AdjustKeys.CONTROLLED_OBJECT]]?.get(DynamicKey.HEIGHT) == null) {
            return err("Give the vessel's height (or ${c.tag}'s measurement range) in Dynamics first: the tuning is in % of that span")
        }
        val setup = Dynamics.setup(f)
        return call(Dynamics.request(f).copy(duration = 2 * setup.step, events = emptyList()))
    }

    /**
     * The step test itself from the first run's result: {method, params, stepTime, step} (the step's sign turned so the
     * output stays within 0–100 %), or {error}.
     */
    fun stepTestRun(flowsheetJson: String, controllerId: String, step: Double, duration: Double, firstResultJson: String): String {
        val f = fs(flowsheetJson)
        val c = f.obj(controllerId) ?: return err("Choose a controller")
        if (step == 0.0 || !step.isFinite()) return err("Give the output step, in %")
        if (!(duration > 0)) return err("Give how long to run")
        val first = FlowsheetJson.decodeFromString(DynamicsResult.serializer(), firstResultJson)
        val op0 = first.trends.firstOrNull { it.objectId == controllerId && it.key == "output" }?.values?.firstOrNull()
            ?: return err(first.messages.firstOrNull()?.text ?: "The dynamic run gave no output for ${c.tag}")
        val setup = Dynamics.setup(f)
        val stepTime = 5 * setup.step
        val s = if (op0 + step > 100) -kotlin.math.abs(step) else if (op0 + step < 0) kotlin.math.abs(step) else step
        val req = PidTuning.stepTest(f, controllerId, op0, s, stepTime, duration)
        return buildJsonObject {
            put("method", "dynamics"); put("params", FlowsheetJson.encodeToJsonElement(DynamicsRequest.serializer(), req))
            put("stepTime", stepTime); put("step", s)
        }.toString()
    }

    /**
     * The step test's report: the measurement in % of the span with the fitted FOPDT model drawn over it, the model,
     * and each rule's settings. Returns {report, settings: [{rule, gain, integralTime, derivativeTime, action}]}, or
     * {error}.
     */
    fun stepTestReport(flowsheetJson: String, controllerId: String, stepTime: Double, step: Double, resultJson: String): String {
        val f = fs(flowsheetJson)
        val c = f.obj(controllerId) ?: return err("That controller no longer exists")
        val r = FlowsheetJson.decodeFromString(DynamicsResult.serializer(), resultJson)
        fun trend(k: String) = r.trends.firstOrNull { it.objectId == controllerId && it.key == k }?.values
        val pv = trend("controlledValue")
        val sp = trend("target")?.firstOrNull()
        if (pv == null || sp == null) return err(r.messages.firstOrNull()?.text ?: "The run gave no measurement for ${c.tag}")
        val span = PidTuning.span(f, c, sp)
        val pct = pv.map { it / span * 100 }
        val model = PidTuning.fit(r.times, pct, stepTime, step)
        val rep = Report(UnitContext(f.atmosphericPressurePa))
        val series = mutableListOf(PlotSeries("Measurement, % of span", r.times, pct))
        model?.let { m -> series += PlotSeries("Fitted model", r.times, r.times.map { PidTuning.response(m, pct.first(), step, it - stepTime) }, dashed = true) }
        rep.plot(Plot("Step test: output ${if (step > 0) "+" else ""}${ValueFormat.number(step, 1)} % at ${ValueFormat.number(stepTime, 0)} s", "Time", Quantity.Time, "Measurement (% of span)", Quantity.Ratio, series))
        val settings = model?.let { PidTuning.settings(it) }.orEmpty()
        if (model == null) rep.note("The response did not settle within the run, or hardly moved: run longer or step further.", "warn")
        else {
            rep.kv(listOf(
                Triple("Process gain K", ValueFormat.number(model.gain, 3), "% / %"),
                Triple("Time constant τ", ValueFormat.number(model.timeConstant, 1), "s"),
                Triple("Dead time θ", ValueFormat.number(model.deadTime, 1), "s"),
                Triple("Action the process needs", if (PidTuning.action(model) > 0) "Direct" else "Reverse", ""),
            ))
            rep.table(listOf("Rule", "Kc (%/%)", "Ti (s)", "Td (s)"), settings.map {
                listOf(it.rule, ValueFormat.number(it.gain, 3), ValueFormat.number(it.integralTime, 1), if (it.derivativeTime > 0) ValueFormat.number(it.derivativeTime, 1) else "—")
            })
        }
        return buildJsonObject {
            put("report", FlowsheetJson.parseToJsonElement(rep.json()))
            putJsonArray("settings") {
                settings.forEach { s ->
                    addJsonObject {
                        put("rule", s.rule); put("gain", s.gain); put("integralTime", s.integralTime); put("derivativeTime", s.derivativeTime)
                        put("action", PidTuning.action(model!!))
                    }
                }
            }
        }.toString()
    }

    /** Puts a rule's settings and the action the process needs into the controller's dynamic tuning (the app's applyTuning). */
    fun applyTuning(flowsheetJson: String, controllerId: String, gain: Double, integralTime: Double, derivativeTime: Double, action: Int): String = try {
        val f = fs(flowsheetJson)
        f.obj(controllerId) ?: throw EditRejected("That controller no longer exists")
        out(listOf(
            DynamicKey.GAIN to Spec(gain, ""),
            DynamicKey.INTEGRAL_TIME to Spec(integralTime, "s"),
            DynamicKey.DERIVATIVE_TIME to Spec(derivativeTime, "s"),
            DynamicKey.ACTION to Spec(action.toDouble(), ""),
        ).fold(f) { acc, (k, v) -> FlowsheetEditing.setDynamicParameter(acc, controllerId, k, v) })
    } catch (e: IllegalArgumentException) { err(e.message ?: "Not allowed") }
}
