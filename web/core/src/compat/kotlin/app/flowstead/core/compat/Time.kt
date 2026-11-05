/*
 * Flowstead web core (D109): UTC date formatting without java.time, for the browser.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 * jvmTest checks both functions against java.time.format.DateTimeFormatter.
 */
package app.flowstead.core.compat

private fun civil(epochMillis: Long): IntArray {
    val days = floorDiv(epochMillis, 86_400_000L)
    val ms = (epochMillis - days * 86_400_000L).toInt()
    // Howard Hinnant's days-to-civil.
    val z = days + 719468
    val era = floorDiv(z, 146097L)
    val doe = (z - era * 146097).toInt()
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val y = (yoe + era * 400 + if (m <= 2) 1 else 0).toInt()
    return intArrayOf(y, m, d, ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
}

private fun floorDiv(a: Long, b: Long): Long { val q = a / b; return if ((a % b != 0L) && ((a < 0) != (b < 0))) q - 1 else q }
private fun p2(v: Int) = v.toString().padStart(2, '0')

/** As DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'") at UTC. */
fun utcMinute(epochMillis: Long): String {
    val c = civil(epochMillis)
    return "${c[0].toString().padStart(4, '0')}-${p2(c[1])}-${p2(c[2])} ${p2(c[3])}:${p2(c[4])} UTC"
}

/** As DateTimeFormatter.ISO_LOCAL_DATE_TIME at UTC: seconds always, the fraction only when non-zero, trailing zeros dropped. */
fun utcIsoLocal(epochMillis: Long): String {
    val c = civil(epochMillis)
    val frac = if (c[6] == 0) "" else "." + c[6].toString().padStart(3, '0').trimEnd('0')
    return "${c[0].toString().padStart(4, '0')}-${p2(c[1])}-${p2(c[2])}T${p2(c[3])}:${p2(c[4])}:${p2(c[5])}$frac"
}
