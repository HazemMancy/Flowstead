/*
 * Flowstead web core (D109): the JVM's spelling of decimal numbers in JSON, on every platform.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 *
 * The model hash (SHA-256 of the flowsheet JSON) tells the app which flowsheet a stored result belongs to. In the
 * browser kotlinx.serialization writes a Double as JavaScript does ("3000000", "0.00001"), on the JVM as Java does
 * ("3000000.0", "1.0E-5"): same values, different text, different hash. [canonical] walks the JSON with the
 * serializer's descriptors and re-spells every Double (and Float) value as java.lang.Double.toString would; integers,
 * strings and everything else are left as they are. On the JVM it changes nothing (jvmTest checks that), so a
 * flowsheet hashes the same in the browser as in the app.
 */
package app.flowstead.core.compat

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/** As java.lang.Double.toString (JDK 19 and later: the shortest digits that read back as [d]). */
fun javaDoubleString(d: Double): String {
    if (d.isNaN()) return "NaN"
    if (d.isInfinite()) return if (d > 0) "Infinity" else "-Infinity"
    if (d == 0.0) return if (1.0 / d < 0) "-0.0" else "0.0"
    val dec = Dec.shortest(d).stripTrailingZeros()
    val digits = dec.digits
    val sign = if (d < 0) "-" else ""
    val exp10 = digits.length - 1 - dec.scale // value = d1.d2d3… × 10^exp10
    val a = kotlin.math.abs(d)
    return if (a >= 1e-3 && a < 1e7) {
        val plain = dec.abs().toPlainString()
        sign + if ('.' in plain) plain else "$plain.0"
    } else {
        sign + digits[0] + "." + digits.substring(1).ifEmpty { "0" } + "E" + exp10
    }
}

@OptIn(ExperimentalSerializationApi::class)
fun <T> canonical(json: Json, serializer: KSerializer<T>, value: T): String {
    val tree = json.encodeToJsonElement(serializer, value)
    return json.encodeToString(JsonElement.serializer(), respell(tree, serializer.descriptor, json))
}

@OptIn(ExperimentalSerializationApi::class)
private fun respell(e: JsonElement, d: SerialDescriptor, json: Json): JsonElement {
    if (e is JsonNull) return e
    val desc = if (d.isInline) d.getElementDescriptor(0) else d
    return when (desc.kind) {
        PrimitiveKind.DOUBLE, PrimitiveKind.FLOAT -> {
            val p = e as? JsonPrimitive ?: return e
            val v = if (p.isString) null else p.doubleOrNull
            if (v == null) e else JsonUnquotedLiteral(javaDoubleString(v))
        }
        StructureKind.CLASS, StructureKind.OBJECT -> {
            val o = e as? JsonObject ?: return e
            JsonObject(o.mapValues { (k, v) ->
                val i = (0 until desc.elementsCount).firstOrNull { desc.getElementName(it) == k }
                if (i == null) v else respell(v, desc.getElementDescriptor(i), json)
            })
        }
        StructureKind.LIST -> {
            val a = e as? JsonArray ?: return e
            val item = desc.getElementDescriptor(0)
            JsonArray(a.map { respell(it, item, json) })
        }
        StructureKind.MAP -> {
            val o = e as? JsonObject ?: return e
            val valueDesc = desc.getElementDescriptor(1)
            JsonObject(o.mapValues { (_, v) -> respell(v, valueDesc, json) })
        }
        PolymorphicKind.SEALED -> {
            // Sealed: {"type": serialName, …fields}. Element 1 lists the subclasses by serial name.
            val o = e as? JsonObject ?: return e
            val name = o[json.configuration.classDiscriminator]?.jsonPrimitive?.content ?: return e
            val subs = desc.getElementDescriptor(1)
            val sub = (0 until subs.elementsCount).map { subs.getElementDescriptor(it) }.firstOrNull { it.serialName == name } ?: return e
            respell(o, sub, json)
        }
        else -> e // enums, strings, integers, contextual and JsonElement values: unchanged
    }
}
