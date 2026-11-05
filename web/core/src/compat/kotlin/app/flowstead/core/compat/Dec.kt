/*
 * Flowstead web core (D109): decimal rounding without java.math, for the browser.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 *
 * A value is digits × 10^-scale with a sign: enough for what android/core does with BigDecimal
 * (valueOf(d).setScale(n, HALF_EVEN), BigDecimal(d).round(10 significant, HALF_UP).stripTrailingZeros()).
 * jvmTest checks every function against java.math.BigDecimal on many random doubles.
 */
package app.flowstead.core.compat

enum class Rounding { HALF_UP, HALF_EVEN }

class Dec private constructor(val negative: Boolean, digits: String, val scale: Int) {
    /** Unscaled magnitude, no leading zeros ("0" for zero). */
    val digits: String = digits.trimStart('0').ifEmpty { "0" }
    val isZero get() = digits == "0"
    fun signum() = if (isZero) 0 else if (negative) -1 else 1

    companion object {
        /** As BigDecimal.valueOf(d): the shortest decimal string that reads back as [d]. */
        fun shortest(d: Double): Dec {
            require(d.isFinite())
            return parse(shortestString(d))
        }

        /** As new BigDecimal(d): the exact binary value of [d]. */
        fun exact(d: Double): Dec {
            require(d.isFinite())
            if (d == 0.0) return Dec(1.0 / d < 0, "0", 0)
            val bits = d.toRawBits()
            val neg = bits < 0
            val exp = ((bits ushr 52) and 0x7ff).toInt()
            var mant = bits and 0xfffffffffffffL
            var e2: Int
            if (exp == 0) e2 = -1074 else { mant = mant or (1L shl 52); e2 = exp - 1075 }
            while (mant != 0L && mant and 1L == 0L) { mant = mant ushr 1; e2++ }
            var digs = mant.toString()
            return if (e2 >= 0) {
                repeat(e2) { digs = mulSmall(digs, 2) }
                Dec(neg, digs, 0)
            } else {
                repeat(-e2) { digs = mulSmall(digs, 5) } // m / 2^k = m·5^k / 10^k
                Dec(neg, digs, -e2)
            }
        }

        /** Parses "-12.5", "1.0E-7", "1e+21", "3". */
        fun parse(s: String): Dec {
            var t = s.trim()
            val neg = t.startsWith('-')
            t = t.removePrefix("-").removePrefix("+")
            var exp = 0
            val e = t.indexOfFirst { it == 'e' || it == 'E' }
            if (e >= 0) { exp = t.substring(e + 1).removePrefix("+").toInt(); t = t.substring(0, e) }
            val dot = t.indexOf('.')
            val intPart = if (dot >= 0) t.substring(0, dot) else t
            val frac = if (dot >= 0) t.substring(dot + 1) else ""
            return Dec(neg, intPart + frac, frac.length - exp).normalizeNegativeScale()
        }

        /** Shortest round-trip decimal of [d], the same on the JVM (Double.toString) and in JavaScript (Number#toString). */
        internal fun shortestString(d: Double): String = d.toString()

        private fun mulSmall(a: String, m: Int): String {
            val out = StringBuilder()
            var carry = 0
            for (i in a.indices.reversed()) {
                val v = (a[i] - '0') * m + carry
                out.append('0' + v % 10); carry = v / 10
            }
            while (carry > 0) { out.append('0' + carry % 10); carry /= 10 }
            return out.reverse().toString()
        }

        private fun addOne(a: String): String {
            val c = a.toCharArray()
            var i = c.size - 1
            while (i >= 0) { if (c[i] == '9') { c[i] = '0'; i-- } else { c[i] = c[i] + 1; return c.concatToString() } }
            return "1" + c.concatToString()
        }
    }

    // A negative scale (e.g. 1E+21) becomes trailing zeros, so scale ≥ 0 and toPlainString is direct.
    private fun normalizeNegativeScale(): Dec = if (scale >= 0) this else Dec(negative, digits + "0".repeat(-scale), 0)

    /** Number of significant digits (BigDecimal.precision): zero has precision 1. */
    val precision get() = digits.length

    /** As setScale(newScale, mode). */
    fun setScale(newScale: Int, mode: Rounding): Dec {
        if (newScale >= scale) return Dec(negative, digits + "0".repeat(newScale - scale), newScale)
        val drop = scale - newScale
        val padded = if (digits.length <= drop) "0".repeat(drop - digits.length + 1) + digits else digits
        val keep = padded.substring(0, padded.length - drop)
        val rest = padded.substring(padded.length - drop)
        val first = rest[0]
        val tail = rest.substring(1).any { it != '0' }
        val up = when {
            first > '5' -> true
            first < '5' -> false
            tail -> true
            mode == Rounding.HALF_UP -> true
            else -> (keep.last() - '0') % 2 == 1
        }
        return Dec(negative, if (up) addOne(keep) else keep, newScale)
    }

    /** As round(new MathContext(precision, mode)). */
    fun round(precision: Int, mode: Rounding): Dec {
        if (isZero || this.precision <= precision) return this
        val r = setScale(scale - (this.precision - precision), mode)
        // Rounding up can add a digit (999.5 → 1000): drop it again, as BigDecimal does.
        return if (r.precision > precision) r.setScale(r.scale - 1, mode) else r
    }

    /** As stripTrailingZeros(). Zero becomes 0 with scale 0. */
    fun stripTrailingZeros(): Dec {
        if (isZero) return Dec(false, "0", 0)
        var d = digits; var s = scale
        while (d.length > 1 && d.endsWith('0')) { d = d.dropLast(1); s-- }
        return Dec(negative, d, s)
    }

    fun abs(): Dec = Dec(false, digits, scale)

    /** As toPlainString(): no exponent; "-0" for a negative zero, exactly as BigDecimal can't (callers map it). */
    fun toPlainString(): String {
        val sign = if (negative && !isZero) "-" else ""
        if (scale <= 0) return sign + digits + "0".repeat(-scale).let { if (isZero) "" else it }
        val padded = if (digits.length <= scale) "0".repeat(scale - digits.length + 1) + digits else digits
        return sign + padded.substring(0, padded.length - scale) + "." + padded.substring(padded.length - scale)
    }
}
