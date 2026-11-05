/*
 * Flowstead web core (D109): a ZIP writer (stored entries, CRC-32) without java.util.zip, for .xlsx files.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 * Entries are stored, not deflated: every spreadsheet program reads them. jvmTest reads the result back with
 * java.util.zip.ZipInputStream and compares each entry.
 */
package app.flowstead.core.compat

class ZipWriter {
    private val out = ArrayList<Byte>()
    private class Entry(val name: ByteArray, val crc: Int, val size: Int, val offset: Int)
    private val entries = mutableListOf<Entry>()

    fun put(name: String, data: ByteArray) {
        val n = name.encodeToByteArray()
        val crc = crc32(data)
        entries += Entry(n, crc, data.size, out.size)
        le32(0x04034b50); le16(20); le16(0x0800); le16(0); le16(0); le16(0x21) // version, UTF-8 names, stored, time
        le32(crc); le32(data.size); le32(data.size); le16(n.size); le16(0)
        bytes(n); bytes(data)
    }

    fun put(name: String, text: String) = put(name, text.encodeToByteArray())

    fun finish(): ByteArray {
        val cdStart = out.size
        for (e in entries) {
            le32(0x02014b50); le16(20); le16(20); le16(0x0800); le16(0); le16(0); le16(0x21)
            le32(e.crc); le32(e.size); le32(e.size); le16(e.name.size); le16(0); le16(0); le16(0); le16(0); le32(0); le32(e.offset)
            bytes(e.name)
        }
        val cdSize = out.size - cdStart
        le32(0x06054b50); le16(0); le16(0); le16(entries.size); le16(entries.size); le32(cdSize); le32(cdStart); le16(0)
        return out.toByteArray()
    }

    private fun bytes(b: ByteArray) { for (x in b) out.add(x) }
    private fun le16(v: Int) { out.add(v.toByte()); out.add((v ushr 8).toByte()) }
    private fun le32(v: Int) { le16(v and 0xffff); le16(v ushr 16) }

    companion object {
        private val TABLE = IntArray(256) { n -> var c = n; repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor -0x12477ce0 else c ushr 1 }; c }
        fun crc32(data: ByteArray): Int {
            var c = -1
            for (b in data) c = TABLE[(c xor b.toInt()) and 0xff] xor (c ushr 8)
            return c.inv()
        }
    }
}
