/*
 * Copyright 2026 Varakumar.
 *
 * This file is part of GhostWire.
 *
 * GhostWire is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * GhostWire is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with GhostWire.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.ghostwire

import android.content.Context
import java.io.File

/**
 * MIFARE Classic analysis helpers — a key dictionary plus sector-trailer
 * access-condition and value-block decoding.
 *
 * The decode algorithms are the public NXP MF1S50yyX datasheet definitions
 * (reimplemented, not copied). The built-in keys are widely-published public
 * defaults; a much larger dictionary (e.g. MCT's extended-std.keys) can be
 * dropped in at runtime via [userKeyFile] rather than bundled.
 */
object Mifare {

    /** Widely-published default/transport keys. Extend at runtime, don't bloat here. */
    val BUILTIN_KEYS: List<String> = listOf(
        "FFFFFFFFFFFF", "000000000000", "A0A1A2A3A4A5", "D3F7D3F7D3F7",
        "A0B0C0D0E0F0", "A1B1C1D1E1F1", "B0B1B2B3B4B5", "B4C132439EEF",
        "4D3A99C351DD", "1A982C7E459A", "AABBCCDDEEFF", "714C5C886E97",
        "587EE5F9350F", "A0478CC39091", "533CB6C723F6", "8FD0A4F256E9",
        "0000014B5C31", "B578F38A5C61", "96A301BCE267", "E00000000000",
    )

    fun userKeyFile(ctx: Context): File = File(ctx.getExternalFilesDir(null), "keys.txt")

    /**
     * The full MIFARE Classic dictionary: built-in defaults, the bundled
     * extended-std.keys asset (~2477 public keys), then the user's keys.txt.
     * One 12-hex key per line, `#` comments; duplicates are de-duped in order.
     */
    fun allKeys(ctx: Context): List<ByteArray> {
        val hex = LinkedHashSet<String>()
        BUILTIN_KEYS.forEach { hex.add(it.uppercase()) }
        runCatching {
            ctx.assets.open("extended-std.keys").bufferedReader().useLines { seq ->
                seq.forEach { addKey(hex, it) }
            }
        }
        runCatching {
            val f = userKeyFile(ctx)
            if (f.exists()) f.forEachLine { addKey(hex, it) }
        }
        return hex.map { h -> ByteArray(6) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }
    }

    private fun addKey(set: MutableSet<String>, line: String) {
        val k = line.substringBefore('#').trim().uppercase()
        if (k.length == 12 && k.all { it in "0123456789ABCDEF" }) set.add(k)
    }

    /** Per-block access codes (C1<<2|C2<<1|C3) for the 4 block groups of a sector,
     *  decoded from trailer bytes 6..8. Returns null if the integrity inverses fail. */
    fun accessCodes(trailer: ByteArray): IntArray? {
        if (trailer.size < 9) return null
        val b6 = trailer[6].toInt() and 0xFF
        val b7 = trailer[7].toInt() and 0xFF
        val b8 = trailer[8].toInt() and 0xFF
        val c1 = (b7 shr 4) and 0x0F
        val c2 = b8 and 0x0F
        val c3 = (b8 shr 4) and 0x0F
        // integrity: inverted copies live in b6 (low=~C1, high=~C2) and b7 (low=~C3)
        if ((c1 xor (b6 and 0x0F)) != 0x0F) return null
        if ((c2 xor ((b6 shr 4) and 0x0F)) != 0x0F) return null
        if ((c3 xor (b7 and 0x0F)) != 0x0F) return null
        return IntArray(4) { i ->
            (((c1 shr i) and 1) shl 2) or (((c2 shr i) and 1) shl 1) or ((c3 shr i) and 1)
        }
    }

    /** Human-readable access for a data block given its 3-bit access code. */
    fun dataBlockAccess(code: Int): String = when (code) {
        0b000 -> "read A|B, write A|B, inc/dec A|B (transport)"
        0b001 -> "read A|B, dec A|B (value)"
        0b010 -> "read A|B (read-only)"
        0b011 -> "read B, write B"
        0b100 -> "read A|B, write B"
        0b101 -> "read B"
        0b110 -> "read A|B, write B, inc B, dec A|B (value)"
        0b111 -> "no access"
        else -> "?"
    }

    /** Key B is readable (and thus not usable as a key) for these trailer codes. */
    fun keyBReadable(trailerCode: Int): Boolean = trailerCode in intArrayOf(0b000, 0b001, 0b010)

    /** If [block] is a valid MIFARE value block, its signed value; else null. */
    fun valueOf(block: ByteArray): Long? {
        if (block.size != 16) return null
        for (i in 0 until 4) {
            val v = block[i]
            if (block[i + 8] != v) return null
            if (block[i + 4].toInt() != (v.toInt().inv() and 0xFF).toByte().toInt()) return null
        }
        val a = block[12].toInt() and 0xFF
        if ((block[14].toInt() and 0xFF) != a) return null
        if ((block[13].toInt() and 0xFF) != (a.inv() and 0xFF)) return null
        if ((block[15].toInt() and 0xFF) != (a.inv() and 0xFF)) return null
        var v = 0L
        for (i in 3 downTo 0) v = (v shl 8) or (block[i].toLong() and 0xFF)
        return v.toInt().toLong() // interpret as signed 32-bit
    }

    /** Standard 1K/4K layout: is this absolute block a sector trailer? */
    fun isTrailer(block: Int): Boolean =
        if (block < 128) block % 4 == 3 else (block - 128) % 16 == 15

    fun hexToBytes(h: String): ByteArray =
        ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
