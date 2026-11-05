/*
 * Flowstead web core (D109): the String.format patterns android/core uses, without java.util.Formatter.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Supported: %.Nf and %f (Java rounds the shortest decimal form half-up, unlike JavaScript's toFixed), %d, %s,
 * %x/%02x (a Byte as two unsigned hex digits), %%. Always '.' as decimal separator (Locale.ROOT).
 * jvmTest checks it against java.lang.String.format(Locale.ROOT, …).
 */
package app.flowstead.core.compat

private val SPEC = Regex("%(0?)(\\d*)(?:\\.(\\d+))?([fdsx%])")

fun jformat(pattern: String, vararg args: Any?): String {
    var i = 0
    return SPEC.replace(pattern) { m ->
        val (zero, widthS, precS, conv) = m.destructured
        if (conv == "%") return@replace "%"
        val arg = args[i++]
        val body = when (conv) {
            "f" -> fixed((arg as Number).toDouble(), precS.ifEmpty { "6" }.toInt())
            "d" -> (arg as Number).toLong().toString()
            "s" -> arg.toString()
            "x" -> when (arg) {
                is Byte -> (arg.toInt() and 0xff).toString(16)
                is Short -> (arg.toInt() and 0xffff).toString(16)
                is Int -> arg.toUInt().toString(16)
                is Long -> arg.toULong().toString(16)
                else -> error("%x needs an integer")
            }
            else -> error("unsupported conversion %$conv")
        }
        val width = widthS.toIntOrNull() ?: 0
        if (body.length >= width) body
        else if (zero == "0") { val neg = body.startsWith('-'); (if (neg) "-" else "") + "0".repeat(width - body.length) + body.removePrefix("-") }
        else " ".repeat(width - body.length) + body
    }
}

/** Java's %.Nf: the shortest decimal form of [v] rounded half-up to [decimals] places. */
fun fixed(v: Double, decimals: Int): String = when {
    v.isNaN() -> "NaN"
    v.isInfinite() -> if (v > 0) "Infinity" else "-Infinity"
    else -> {
        val r = Dec.shortest(v).abs().setScale(decimals, Rounding.HALF_UP).toPlainString()
        // Java keeps the sign of a negative value that rounds to zero ("-0.0"), and of -0.0 itself.
        if (v < 0 || (v == 0.0 && 1.0 / v < 0)) "-$r" else r
    }
}

/** In each package that formats: `"%.1f".format(x)` reads as before. */
fun String.jfmt(vararg args: Any?): String = jformat(this, *args)
