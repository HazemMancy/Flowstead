/*
 * Flowstead web core (D109): small JVM-only text helpers, for the browser.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.compat

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** As URLEncoder.encode(s, "UTF-8"): letters, digits and . - * _ stay; space is '+'; the rest %XX of the UTF-8 bytes. jvmTest compares. */
fun urlEncode(s: String): String = buildString {
    for (b in s.encodeToByteArray()) {
        val c = b.toInt() and 0xff
        val ch = c.toChar()
        when {
            ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '.' || ch == '-' || ch == '*' || ch == '_' -> append(ch)
            ch == ' ' -> append('+')
            else -> append('%').append(c.toString(16).uppercase().padStart(2, '0'))
        }
    }
}

/** A random (version 4) UUID string, as UUID.randomUUID().toString(). */
@OptIn(ExperimentalUuidApi::class)
fun randomUuid(): String = Uuid.random().toString()
