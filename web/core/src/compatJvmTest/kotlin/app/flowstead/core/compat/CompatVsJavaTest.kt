/*
 * Flowstead web core (D109): every browser replacement against the Java API it replaces, on the JVM.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.compat

import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class CompatVsJavaTest {
    private val rnd = Random(108)

    /** Ordinary engineering magnitudes, exact halves and the classic binary traps, plus random bit patterns. */
    private fun doubles(): List<Double> {
        val special = listOf(0.0, -0.0, 1.0, -1.0, 0.5, 1.5, 2.5, -2.5, 0.125, 0.375, 1.005, 2.675, 1.115, 0.045, 1234.5,
            999.5, 9.995, 0.0001, 1e-7, 1.0E-5, 123456789.125, 1e15, 1e21, 4.35, 0.1 + 0.2, 273.15, 101325.0, 1e-300,
            Double.MIN_VALUE, Double.MAX_VALUE, 3.0E-320, 0.6666666666666666, -45.005, 6894.757293168361)
        val engineering = List(60_000) { (rnd.nextDouble() - 0.5) * 10.0.pow(rnd.nextInt(-8, 12)) }
        val halves = List(20_000) { (rnd.nextInt(-1_000_000, 1_000_000) + 0.5) / 10.0.pow(rnd.nextInt(0, 7)) }
        val bits = List(20_000) { Double.fromBits(rnd.nextLong()) }.filter { it.isFinite() }
        return special + engineering + halves + bits
    }
    private fun Double.pow(n: Int) = Math.pow(this, n.toDouble())

    @Test fun shortestSetScaleMatchesBigDecimalValueOf() {
        for (d in doubles()) for (n in intArrayOf(0, 1, 2, 3, 4, 6)) {
            if (Math.abs(d) > 1e30 || (d != 0.0 && Math.abs(d) < 1e-30)) continue
            assertEquals(BigDecimal.valueOf(d).setScale(n, RoundingMode.HALF_EVEN).toPlainString(),
                Dec.shortest(d).setScale(n, Rounding.HALF_EVEN).toPlainString(), "valueOf($d).setScale($n)")
        }
    }

    @Test fun exactRoundMatchesBigDecimal() {
        for (d in doubles()) {
            val java = BigDecimal(d).round(MathContext(10)).stripTrailingZeros().toPlainString()
            assertEquals(java, Dec.exact(d).round(10, Rounding.HALF_UP).stripTrailingZeros().toPlainString(), "BigDecimal($d).round(10)")
        }
    }

    @Test fun fixedMatchesStringFormat() {
        for (d in doubles()) for (n in intArrayOf(0, 1, 2, 6)) {
            if (Math.abs(d) > 1e30 || (d != 0.0 && Math.abs(d) < 1e-30)) continue
            assertEquals(String.format(Locale.ROOT, "%.${n}f", d), jformat("%.${n}f", d), "%.${n}f of $d")
        }
        for (b in -128..127) assertEquals(String.format(Locale.ROOT, "%02x", b.toByte()), jformat("%02x", b.toByte()))
        assertEquals(String.format(Locale.ROOT, "H/D %.1f is above 6, %d %s %%", 7.25, 3, "x"), jformat("H/D %.1f is above 6, %d %s %%", 7.25, 3, "x"))
    }

    @Test fun sha256MatchesMessageDigest() {
        repeat(300) { i ->
            val m = ByteArray(i * 7 % 700) { rnd.nextInt().toByte() }
            assertContentEquals(MessageDigest.getInstance("SHA-256").digest(m), Sha256.digest(m), "length ${m.size}")
        }
    }

    @Test fun zipReadsBackWithJavaUtilZip() {
        val files = (1..12).associate { "dir/file$it.xml" to ByteArray(rnd.nextInt(0, 5000)) { rnd.nextInt().toByte() } } +
            mapOf("[Content_Types].xml" to "ünïcödé ✓".encodeToByteArray())
        val zip = ZipWriter().apply { files.forEach { (k, v) -> put(k, v) } }.finish()
        val read = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z -> while (true) { val e = z.nextEntry ?: break; read[e.name] = z.readBytes() } }
        assertEquals(files.keys, read.keys)
        files.forEach { (k, v) -> assertContentEquals(v, read.getValue(k), k) }
        val crc = java.util.zip.CRC32().apply { update("123456789".toByteArray()) }.value
        assertEquals(crc, ZipWriter.crc32("123456789".encodeToByteArray()).toLong() and 0xffffffffL)
    }

    @Test fun datesMatchDateTimeFormatter() {
        val minute = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
        repeat(20_000) {
            val ms = rnd.nextLong(-2_208_988_800_000L, 4_102_444_800_000L) // 1900 … 2100
            val t = Instant.ofEpochMilli(ms).atOffset(ZoneOffset.UTC)
            assertEquals(minute.format(t), utcMinute(ms), "$ms")
            assertEquals(DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(t), utcIsoLocal(ms), "$ms")
            assertEquals(DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(t.withNano(0)), utcIsoLocal(ms - ms.mod(1000L)), "$ms whole s")
        }
    }

    @Test fun javaDoubleStringMatchesDoubleToString() {
        for (d in doubles()) assertEquals(java.lang.Double.toString(d), javaDoubleString(d), "$d")
    }

    @Test fun canonicalJsonIsTheJvmsOwnOutput() {
        // On the JVM the canonical text must be exactly what kotlinx.serialization writes: the respelling is a no-op here.
        val root = java.io.File(System.getProperty("flowstead.sharedDir")).parentFile.resolve("benchmarks")
        val cases = root.listFiles()!!.filter { it.resolve("input.json").isFile }
        for (c in cases) {
            val fs = app.flowstead.core.model.FlowsheetJson.decodeFromJsonElement(app.flowstead.core.model.Flowsheet.serializer(),
                app.flowstead.core.model.FlowsheetJson.parseToJsonElement(c.resolve("input.json").readText()).let { (it as kotlinx.serialization.json.JsonObject).getValue("flowsheet") })
            assertEquals(app.flowstead.core.model.FlowsheetJson.encodeToString(app.flowstead.core.model.Flowsheet.serializer(), fs),
                canonical(app.flowstead.core.model.FlowsheetJson, app.flowstead.core.model.Flowsheet.serializer(), fs), c.name)
        }
    }

    @Test fun urlEncodeMatchesUrlEncoder() {
        for (s in listOf("methane", "n-butane & CO2", "2,2-dimethylpropane", "ünïcödé ✓ 水", "a+b/c?d=e#f", "*._-~!'()")) {
            assertEquals(URLEncoder.encode(s, "UTF-8"), urlEncode(s), s)
        }
    }
}
