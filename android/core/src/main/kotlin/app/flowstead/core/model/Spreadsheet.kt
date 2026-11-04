/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A flowsheet spreadsheet (D105), as HYSYS's and DWSIM's: cells A1… hold numbers, text or formulas ("=…"). A formula
 * may import a solved result as [TAG.key] (in the display unit of its quantity, e.g. [K-100.power] in kW), refer to
 * other cells (B2) and ranges in sum/min/max/average (A1:A5), and use + − × / ^, parentheses, pi and
 * sqrt exp ln log10 abs min max sum average. A cell can be exported to a specification, set by "Apply".
 * Its numbers are the engineer's own arithmetic on the engine's results, shown as such.
 */
@Serializable
data class Spreadsheet(
    val cells: Map<String, String> = emptyMap(),
    val exports: List<SpreadsheetExport> = emptyList(),
)

/** Cell [cell]'s value, in the display unit of the spec, goes to [key] of [objectId] when the sheet is applied. */
@Serializable
data class SpreadsheetExport(val cell: String, val objectId: String, val key: String)

sealed interface CellValue {
    data class Number(val value: Double) : CellValue
    data class Text(val text: String) : CellValue
    data class Error(val reason: String) : CellValue
    data object Empty : CellValue
}

object SpreadsheetEval {
    const val COLUMNS = 6
    const val ROWS = 30
    val CELL = Regex("^([A-Z])([1-9][0-9]?)$")

    fun columnName(c: Int): String = ('A' + c).toString()
    fun cellName(column: Int, row: Int): String = columnName(column) + (row + 1)

    /**
     * Every cell's value. [resolve] gives a result in display units for a tag and key, or null when the flowsheet has
     * no such solved value.
     */
    fun evaluate(sheet: Spreadsheet, resolve: (tag: String, key: String) -> Double?): Map<String, CellValue> {
        val out = HashMap<String, CellValue>()
        val visiting = HashSet<String>()
        fun value(name: String): CellValue {
            out[name]?.let { return it }
            val raw = sheet.cells[name]?.trim().orEmpty()
            if (raw.isEmpty()) return CellValue.Empty
            if (!visiting.add(name)) return CellValue.Error("circular reference")
            val v = when {
                raw.startsWith("=") -> try {
                    CellValue.Number(Parser(raw.substring(1), { c -> number(value(c)) }, resolve).parse())
                } catch (e: FormulaError) {
                    CellValue.Error(e.message ?: "error")
                }
                else -> raw.replace(',', '.').toDoubleOrNull()?.let { CellValue.Number(it) } ?: CellValue.Text(raw)
            }
            visiting.remove(name)
            out[name] = v
            return v
        }
        for (name in sheet.cells.keys) value(name)
        return out
    }

    private fun number(v: CellValue): Double = when (v) {
        is CellValue.Number -> v.value
        is CellValue.Empty -> 0.0
        is CellValue.Text -> throw FormulaError("a referenced cell holds text")
        is CellValue.Error -> throw FormulaError(v.reason)
    }

    class FormulaError(message: String) : Exception(message)

    /** Recursive-descent parser and evaluator of one formula. */
    private class Parser(
        val s: String,
        val cell: (String) -> Double,
        val resolve: (String, String) -> Double?,
    ) {
        var i = 0

        fun parse(): Double {
            val v = expr()
            skip()
            if (i < s.length) throw FormulaError("unexpected '${s[i]}'")
            if (!v.isFinite()) throw FormulaError(if (v.isNaN()) "not a number" else "division by zero")
            return v
        }

        fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun eat(c: Char): Boolean { skip(); return if (i < s.length && s[i] == c) { i++; true } else false }

        fun expr(): Double {
            var v = term()
            while (true) v = when {
                eat('+') -> v + term()
                eat('-') -> v - term()
                else -> return v
            }
        }

        fun term(): Double {
            var v = power()
            while (true) v = when {
                eat('*') -> v * power()
                eat('/') -> power().let { d -> if (d == 0.0) throw FormulaError("division by zero") else v / d }
                else -> return v
            }
        }

        fun power(): Double {
            val base = unary()
            return if (eat('^')) base.pow(power()) else base
        }

        fun unary(): Double = when {
            eat('-') -> -unary()
            eat('+') -> unary()
            else -> primary()
        }

        fun primary(): Double {
            skip()
            if (i >= s.length) throw FormulaError("formula ends too soon")
            val c = s[i]
            if (eat('(')) { val v = expr(); if (!eat(')')) throw FormulaError("missing ')'"); return v }
            if (c == '[') return import()
            if (c.isDigit() || c == '.') return numberLiteral()
            if (c.isLetter()) {
                val start = i
                while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
                val word = s.substring(start, i)
                skip()
                if (i < s.length && s[i] == '(') { i++; return function(word.lowercase()) }
                if (word.lowercase() == "pi") return Math.PI
                if (CELL.matches(word.uppercase())) return cell(word.uppercase())
                throw FormulaError("unknown name '$word'")
            }
            throw FormulaError("unexpected '$c'")
        }

        fun numberLiteral(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                while (i < s.length && s[i].isDigit()) i++
            }
            return s.substring(start, i).toDoubleOrNull() ?: throw FormulaError("bad number '${s.substring(start, i)}'")
        }

        /** [TAG.key]: the solved value, in display units. The tag may contain dots; the key is after the last one. */
        fun import(): Double {
            i++ // '['
            val end = s.indexOf(']', i)
            if (end < 0) throw FormulaError("missing ']'")
            val ref = s.substring(i, end).trim()
            i = end + 1
            val dot = ref.lastIndexOf('.')
            if (dot <= 0 || dot == ref.length - 1) throw FormulaError("write an import as [TAG.key], e.g. [K-100.power]")
            val tag = ref.substring(0, dot).trim()
            val key = ref.substring(dot + 1).trim()
            return resolve(tag, key) ?: throw FormulaError("no solved $key for $tag")
        }

        fun arguments(): List<Double> {
            val args = mutableListOf<Double>()
            if (eat(')')) return args
            do {
                skip()
                // A range A1:B3 expands to its cells.
                val m = Regex("^([A-Za-z])([1-9][0-9]?)\\s*:\\s*([A-Za-z])([1-9][0-9]?)").find(s.substring(i))
                if (m != null) {
                    i += m.value.length
                    val (c1, r1, c2, r2) = m.destructured
                    val cols = minOf(c1.uppercase()[0], c2.uppercase()[0])..maxOf(c1.uppercase()[0], c2.uppercase()[0])
                    val rows = minOf(r1.toInt(), r2.toInt())..maxOf(r1.toInt(), r2.toInt())
                    for (col in cols) for (row in rows) args += cell("$col$row")
                } else args += expr()
            } while (eat(','))
            if (!eat(')')) throw FormulaError("missing ')'")
            return args
        }

        fun function(name: String): Double {
            val a = arguments()
            fun one(): Double = a.singleOrNull() ?: throw FormulaError("$name takes one value")
            return when (name) {
                "sqrt" -> one().let { if (it < 0) throw FormulaError("square root of a negative number") else sqrt(it) }
                "exp" -> exp(one())
                "ln" -> one().let { if (it <= 0) throw FormulaError("logarithm of a non-positive number") else ln(it) }
                "log10", "log" -> one().let { if (it <= 0) throw FormulaError("logarithm of a non-positive number") else log10(it) }
                "abs" -> abs(one())
                "min" -> a.minOrNull() ?: throw FormulaError("min needs values")
                "max" -> a.maxOrNull() ?: throw FormulaError("max needs values")
                "sum" -> a.sum()
                "average", "avg" -> if (a.isEmpty()) throw FormulaError("average needs values") else a.average()
                else -> throw FormulaError("unknown function '$name'")
            }
        }
    }
}
