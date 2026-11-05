/*
 * Flowstead web core (D109): the same checks on the JVM and in JavaScript (Node).
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Real references where there are any (the shared unit vectors, FIPS SHA-256 and CRC-32 vectors, Java's printf
 * rounding), and for everything else one fingerprint of the core's outputs: model hashes, specification problems,
 * value formatting and the bytes of the Excel and CSV exports for every benchmark. The fingerprint was computed on
 * the JVM, where the app's own 196 core tests pass on this code; JavaScript must reproduce it exactly.
 * If a deliberate change moves it, run `./gradlew jvmTest` and copy the printed value into FINGERPRINT.
 */
package app.flowstead.core

import app.flowstead.core.compat.Sha256
import app.flowstead.core.compat.ZipWriter
import app.flowstead.core.compat.jformat
import app.flowstead.core.compat.urlEncode
import app.flowstead.core.compat.utcIsoLocal
import app.flowstead.core.compat.utcMinute
import app.flowstead.core.export.Csv
import app.flowstead.core.export.ResultsWorkbook
import app.flowstead.core.export.Xlsx
import app.flowstead.core.format.ValueFormat
import app.flowstead.core.model.Flowsheet
import app.flowstead.core.model.FlowsheetJson
import app.flowstead.core.api.SolveResult
import app.flowstead.core.model.Specification
import app.flowstead.core.model.StoredSolve
import app.flowstead.core.model.modelHash
import app.flowstead.core.units.Quantity
import app.flowstead.core.units.StandardConditions
import app.flowstead.core.units.UnitContext
import app.flowstead.core.units.UnitSet
import app.flowstead.core.units.Units
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrossPlatformTest {
    private fun benchmarks() = TestData.benchmarks.map { (name, text) ->
        name to FlowsheetJson.decodeFromJsonElement(Flowsheet.serializer(), FlowsheetJson.parseToJsonElement(text).jsonObject.getValue("flowsheet"))
    }

    @Test fun sharedUnitVectors() {
        val root = Json.parseToJsonElement(TestData.unitVectors).jsonObject
        val rel = root.getValue("relativeTolerance").jsonPrimitive.double
        val vectors = root.getValue("vectors").jsonArray
        var checked = 0
        for (v in vectors) {
            val o = v.jsonObject
            val unit = Units.byId(o.getValue("unit").jsonPrimitive.contentOrNull!!)
            val ctx = UnitContext(o["patmPa"]?.jsonPrimitive?.doubleOrNull ?: StandardConditions.DEFAULT_PATM_PA)
            val value = o.getValue("value").jsonPrimitive.double; val si = o.getValue("si").jsonPrimitive.double
            assertTrue(abs(unit.toSi(value, ctx) - si) <= rel * max(1.0, abs(si)), "$value ${unit.id} → SI")
            assertTrue(abs(unit.fromSi(si, ctx) - value) <= rel * max(1.0, abs(value)), "$si SI → ${unit.id}")
            checked++
        }
        assertEquals(vectors.size, checked)
    }

    @Test fun everyBenchmarkPassesTheAppsChecks() {
        val cases = benchmarks()
        assertTrue(cases.size >= 20)
        for ((name, fs) in cases) assertEquals(emptyList(), Specification.problems(fs), name)
    }

    @Test fun referenceVectors() {
        // FIPS 180-4 examples.
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hex("abc".encodeToByteArray()))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hex(ByteArray(0)))
        assertEquals("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()))
        // CRC-32 check value.
        assertEquals(0xCBF43926.toInt(), ZipWriter.crc32("123456789".encodeToByteArray()))
        // Java's printf rounds the shortest decimal half-up; JavaScript's toFixed would give 1.00, 2.67, -0.04 → "-0".
        assertEquals("1.01", jformat("%.2f", 1.005)); assertEquals("2.68", jformat("%.2f", 2.675)); assertEquals("-0.0", jformat("%.1f", -0.04))
        assertEquals("1,234.6", ValueFormat.number(1234.55, 1)) // half-even on the shortest decimal
        assertEquals("1,234.4", ValueFormat.number(1234.45, 1))
        assertEquals("0.1", ResultsWorkbook.plain(0.1)); assertEquals("0.3000000000", "0.3000000000"); assertEquals("0.3", ResultsWorkbook.plain(0.1 + 0.2))
        assertEquals("2025-11-05 18:50 UTC", utcMinute(1_762_368_616_123)); assertEquals("2025-11-05T18:50:16.123", utcIsoLocal(1_762_368_616_123))
        assertEquals("n-butane+%26+CO2", urlEncode("n-butane & CO2"))
    }

    /** Everything else the core computes for the benchmarks, in one string; its SHA-256 must match the JVM's. */
    private fun fingerprintText(): String = buildString {
        val units = UnitSet.SI.with(Quantity.Temperature, Units.C).with(Quantity.Pressure, Units.bara)
        for ((name, fs) in benchmarks()) {
            appendLine("$name ${fs.modelHash()} ${Specification.problems(fs)}")
            for (o in fs.objects) appendLine(" ${o.id} ${Specification.status(fs, o)}")
        }
        for ((fixture, text) in listOf("06" to TestData.`solve-result-06`, "letdown" to TestData.`solve-result-letdown`, "lines" to TestData.`solve-result-lines`)) {
            val result = FlowsheetJson.decodeFromString(SolveResult.serializer(), text)
            val fs = Flowsheet(
                propertyPackage = "Peng-Robinson (PR)",
                objects = result.objects.keys.sorted().map { app.flowstead.core.model.SimObject(it, it.uppercase(), app.flowstead.core.model.ObjectType.MaterialStream) },
            )
            val sheets = ResultsWorkbook.build("Fixture $fixture", fs, StoredSolve(result, fs.modelHash(), 1_760_000_000_000), units)
            appendLine("xlsx $fixture ${Sha256.hex(Xlsx.write(sheets))}")
            appendLine("csv $fixture ${Sha256.hex(Csv.write(sheets).encodeToByteArray())}")
        }
        var x = 0.001
        while (x < 1e9) { for (d in 0..4) append(ValueFormat.number(x, d)).append(' ').append(ValueFormat.number(-x * 1.37, d)).append(' '); x *= 1.618 }
        appendLine()
    }

    @Test fun fingerprintMatchesTheJvm() {
        val text = fingerprintText()
        val hash = Sha256.hex(text.encodeToByteArray())
        println("CrossPlatformTest fingerprint: $hash")
        if (hash != FINGERPRINT) text.lines().forEachIndexed { i, l -> println("FPLINE $i $l") }
        assertEquals(FINGERPRINT, hash, "outputs differ from the JVM's")
    }

    companion object { const val FINGERPRINT = "950a68c88079c558772e817b242c891862d3ae858a91ca401c24707b39c0b2f7" }
}
