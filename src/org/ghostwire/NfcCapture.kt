package org.ghostwire

import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NfcA
import java.util.UUID

/**
 * Turns a discovered NFC [Tag] into a [Card]. 13.56 MHz only — reads
 * NfcA/ATQA/SAK, MIFARE Classic sectors (default keys), Ultralight pages, and
 * NDEF. Locked sectors / unreadable tech are simply omitted, not faked.
 */
object NfcCapture {

    private val DEFAULT_KEYS = listOf(
        "FFFFFFFFFFFF", "A0A1A2A3A4A5", "D3F7D3F7D3F7", "000000000000",
        "B0B1B2B3B4B5", "4D3A99C351DD", "1A982C7E459A", "AABBCCDDEEFF",
    ).map { hexToBytes(it) }

    fun read(tag: Tag): Card {
        val tech = tag.techList.toList()
        val uid = bytesToHex(tag.id)
        var atqa: String? = null
        var sak: String? = null
        var ats: String? = null
        var sakInt: Int? = null
        var atqaInt: Int? = null

        NfcA.get(tag)?.let { a ->
            val aq = a.atqa
            atqa = bytesToHex(aq)
            sak = "%02X".format(a.sak)
            sakInt = a.sak.toInt() and 0xFF
            atqaInt = if (aq.size >= 2)
                ((aq[1].toInt() and 0xFF) shl 8) or (aq[0].toInt() and 0xFF) else null
        }
        IsoDep.get(tag)?.historicalBytes?.let { ats = bytesToHex(it) }

        val blocks = LinkedHashMap<Int, String>()
        val ndef = ArrayList<String>()

        MifareClassic.get(tag)?.let { mc ->
            runCatching {
                mc.connect()
                for (sector in 0 until mc.sectorCount) {
                    val authed = DEFAULT_KEYS.any { k ->
                        runCatching { mc.authenticateSectorWithKeyA(sector, k) }.getOrDefault(false) ||
                            runCatching { mc.authenticateSectorWithKeyB(sector, k) }.getOrDefault(false)
                    }
                    if (!authed) continue
                    val first = mc.sectorToBlock(sector)
                    for (b in first until first + mc.getBlockCountInSector(sector)) {
                        runCatching { blocks[b] = bytesToHex(mc.readBlock(b)) }
                    }
                }
            }
            runCatching { mc.close() }
        }

        MifareUltralight.get(tag)?.let { mu ->
            runCatching {
                mu.connect()
                var page = 0
                while (page < 64) {
                    val r = runCatching { mu.readPages(page) }.getOrNull() ?: break
                    for (i in 0 until 4) {
                        val off = i * 4
                        if (off + 4 <= r.size) blocks[page + i] = bytesToHex(r.copyOfRange(off, off + 4))
                    }
                    page += 4
                }
            }
            runCatching { mu.close() }
        }

        Ndef.get(tag)?.let { nd ->
            runCatching {
                nd.connect()
                (nd.ndefMessage ?: nd.cachedNdefMessage)?.records?.forEach { ndef.add(describeNdef(it)) }
            }
            runCatching { nd.close() }
        }

        val type = Card.typeFor(sakInt, atqaInt, tech)
        return Card(
            id = UUID.randomUUID().toString(),
            name = "${type.substringBefore(" (").take(22)} ${uid.takeLast(8)}",
            timestamp = System.currentTimeMillis(),
            techList = tech.map { it.substringAfterLast('.') },
            uid = uid, atqa = atqa, sak = sak, ats = ats,
            typeLabel = type, blocks = blocks, ndef = ndef,
        )
    }

    private fun describeNdef(rec: NdefRecord): String {
        val type = String(rec.type)
        return when {
            rec.tnf == NdefRecord.TNF_WELL_KNOWN && type == "T" -> "Text: ${parseText(rec.payload)}"
            rec.tnf == NdefRecord.TNF_WELL_KNOWN && type == "U" -> "URI: ${parseUri(rec.payload)}"
            else -> "TNF ${rec.tnf} type=$type (${rec.payload.size} B)"
        }
    }

    private fun parseText(p: ByteArray): String {
        if (p.isEmpty()) return ""
        val langLen = p[0].toInt() and 0x3F
        return if (p.size > 1 + langLen) String(p, 1 + langLen, p.size - 1 - langLen) else ""
    }

    private val URI_PREFIX = arrayOf(
        "", "http://www.", "https://www.", "http://", "https://", "tel:", "mailto:",
    )

    private fun parseUri(p: ByteArray): String {
        if (p.isEmpty()) return ""
        val prefix = URI_PREFIX.getOrElse(p[0].toInt() and 0xFF) { "" }
        return prefix + String(p, 1, p.size - 1)
    }

    fun bytesToHex(b: ByteArray): String = b.joinToString("") { "%02X".format(it) }
    private fun hexToBytes(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
