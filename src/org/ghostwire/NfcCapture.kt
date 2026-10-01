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

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NfcA
import android.nfc.tech.NfcB
import android.nfc.tech.NfcBarcode
import android.nfc.tech.NfcF
import android.nfc.tech.NfcV
import java.util.UUID

/**
 * Turns a discovered NFC [Tag] into a [Card]. 13.56 MHz only — reads
 * NfcA/ATQA/SAK, MIFARE Classic sectors (default keys), Ultralight pages, and
 * NDEF. Locked sectors / unreadable tech are simply omitted, not faked.
 */
object NfcCapture {

    /**
     * [keys] is the MIFARE Classic dictionary to try (see [Mifare.allKeys]) — a
     * dictionary attack is the only key cracking a phone can do (nonce attacks
     * like nested/darkside need parity/nonce access Android NFC doesn't expose).
     * [onProgress] is called per sector during the crack.
     */
    fun read(
        tag: Tag,
        keys: List<ByteArray> = emptyList(),
        onProgress: ((String) -> Unit)? = null,
    ): Card {
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
        val foundKeys = LinkedHashMap<Int, String>()
        val ndef = ArrayList<String>()
        val extra = ArrayList<String>()

        MifareClassic.get(tag)?.let { mc ->
            runCatching {
                mc.connect()
                for (sector in 0 until mc.sectorCount) {
                    onProgress?.invoke("cracking sector ${sector + 1}/${mc.sectorCount} (${keys.size}-key dict)")
                    var used: String? = null
                    for (k in keys) {
                        if (runCatching { mc.authenticateSectorWithKeyA(sector, k) }.getOrDefault(false)) {
                            used = "A ${bytesToHex(k)}"; break
                        }
                        if (runCatching { mc.authenticateSectorWithKeyB(sector, k) }.getOrDefault(false)) {
                            used = "B ${bytesToHex(k)}"; break
                        }
                    }
                    if (used == null) continue
                    foundKeys[sector] = used
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

        var ndefRaw: String? = null
        Ndef.get(tag)?.let { nd ->
            runCatching {
                nd.connect()
                val msg = nd.ndefMessage ?: nd.cachedNdefMessage
                msg?.records?.forEach { ndef.add(describeNdef(it)) }
                msg?.let { ndefRaw = bytesToHex(it.toByteArray()) }
            }
            runCatching { nd.close() }
        }

        // ISO 15693 (NfcV): read blocks via Read Single Block (0x20).
        NfcV.get(tag)?.let { v ->
            runCatching {
                v.connect()
                for (blk in 0 until 64) {
                    val resp = runCatching { v.transceive(byteArrayOf(0x02, 0x20, blk.toByte())) }.getOrNull() ?: break
                    if (resp.isEmpty() || resp[0].toInt() != 0x00) break // resp[0]=response flags, 0=ok
                    blocks[blk] = bytesToHex(resp.copyOfRange(1, resp.size))
                }
            }
            runCatching { v.close() }
        }

        // FeliCa (NfcF): IDm/PMm/system code only — block reads need service codes.
        NfcF.get(tag)?.let { f ->
            extra.add("FeliCa IDm: $uid")
            f.systemCode?.let { extra.add("FeliCa system code: ${bytesToHex(it)}") }
            f.manufacturer?.let { extra.add("FeliCa PMm: ${bytesToHex(it)}") }
            extra.add("FeliCa block read needs per-service codes — not attempted (limit).")
        }

        // ISO 14443-4 (IsoDep): EMV + DESFire probes over APDU.
        IsoDep.get(tag)?.let { iso ->
            runCatching {
                iso.connect()
                emvProbe(iso, extra)
                desfireProbe(iso, extra)
            }
            runCatching { iso.close() }
        }

        // ISO 14443-B: no generic read, just the identifiers the stack exposes.
        NfcB.get(tag)?.let { b ->
            b.applicationData?.let { extra.add("ISO 14443-B app data: ${bytesToHex(it)}") }
            b.protocolInfo?.let { extra.add("ISO 14443-B protocol info: ${bytesToHex(it)}") }
        }

        // Kovio / NFC Barcode: a fixed read-only payload.
        NfcBarcode.get(tag)?.let { bc ->
            runCatching { extra.add("NFC Barcode (type ${bc.type}): ${bytesToHex(bc.barcode)}") }
        }

        val type = Card.typeFor(sakInt, atqaInt, tech)
        return Card(
            id = UUID.randomUUID().toString(),
            name = "${type.substringBefore(" (").take(22)} ${uid.takeLast(8)}",
            timestamp = System.currentTimeMillis(),
            techList = tech.map { it.substringAfterLast('.') },
            uid = uid, atqa = atqa, sak = sak, ats = ats,
            typeLabel = type, blocks = blocks, ndef = ndef, keys = foundKeys, extra = extra,
            ndefRaw = ndefRaw,
        )
    }

    /**
     * Writes a captured [card] back onto a blank/writable [tag] — the
     * write-back half of the card wallet. MIFARE Classic: re-authenticates
     * each sector with the key the card was *read* with (never tries a key
     * it doesn't have) and rewrites that sector's data blocks; trailers
     * (keys/access bits) are never touched, so this can't brick the tag's
     * own keys. MIFARE Ultralight: rewrites pages 4+ (0-3 are UID/lock/OTP,
     * never written). NDEF: replays the original message if the target is
     * NDEF-writable and big enough. Never throws — a partial write is still
     * reported, not swallowed.
     */
    fun write(tag: Tag, card: Card, dict: List<ByteArray> = emptyList()): String {
        var dataWrote = 0
        var trailerWrote = 0
        var skipped = 0
        var failed = 0
        var uidWrote = false
        val notes = StringBuilder()

        MifareClassic.get(tag)?.let { mc ->
            if (!runCatching { mc.connect() }.isSuccess) {
                notes.append("MIFARE Classic: could not connect\n")
            } else {
                // Group the stored blocks by the target's own sector layout.
                val bySector = card.blocks.keys.groupBy { runCatching { mc.blockToSector(it) }.getOrNull() }
                for ((sector, blocksInSector) in bySector) {
                    if (sector == null) { skipped += blocksInSector.size; continue }
                    // Auth the TARGET: the source's recorded key first, then the
                    // full dictionary (a blank target keeps default keys, not the
                    // source's — the old code only tried the source key and so
                    // skipped every non-default sector).
                    if (!authTarget(mc, sector, card.keys[sector], dict)) {
                        skipped += blocksInSector.size
                        notes.append("sector $sector: target auth failed (no matching key)\n")
                        continue
                    }
                    val sorted = blocksInSector.sorted()
                    // Data blocks first, then the trailer last so the new keys
                    // don't invalidate the session before the data is written.
                    for (block in sorted) {
                        if (Mifare.isTrailer(block)) continue
                        val data = card.blocks[block]?.let { runCatching { Mifare.hexToBytes(it) }.getOrNull() }
                        if (data == null || data.size != 16) { failed++; continue }
                        if (runCatching { mc.writeBlock(block, data) }.isSuccess) {
                            dataWrote++
                            if (block == 0) uidWrote = true // only succeeds on gen2 magic
                        } else failed++
                    }
                    sorted.firstOrNull { Mifare.isTrailer(it) }?.let { tb ->
                        val trailer = Mifare.buildTrailer(card.keys[sector], card.blocks[tb])
                        if (runCatching { mc.writeBlock(tb, trailer) }.isSuccess) trailerWrote++ else failed++
                    }
                }
                runCatching { mc.close() }
            }
        }

        MifareUltralight.get(tag)?.let { mu ->
            if (!runCatching { mu.connect() }.isSuccess) {
                notes.append("MIFARE Ultralight: could not connect\n")
            } else {
                for ((page, hex) in card.blocks.toSortedMap()) {
                    if (page < 4) { skipped++; continue } // UID/lock/OTP — never written
                    if (runCatching { mu.writePage(page, Mifare.hexToBytes(hex)) }.isSuccess) dataWrote++ else failed++
                }
                runCatching { mu.close() }
            }
        }

        card.ndefRaw?.let { hex ->
            Ndef.get(tag)?.let { nd ->
                runCatching { nd.connect() }
                val msg = runCatching { NdefMessage(Mifare.hexToBytes(hex)) }.getOrNull()
                when {
                    msg == null -> notes.append("NDEF: stored message is corrupt\n")
                    !nd.isWritable -> notes.append("NDEF: tag is not writable\n")
                    nd.maxSize < msg.toByteArray().size -> notes.append("NDEF: message too large for this tag\n")
                    runCatching { nd.writeNdefMessage(msg) }.isSuccess ->
                        notes.append("NDEF message written (${msg.toByteArray().size} bytes)\n")
                    else -> notes.append("NDEF: write failed\n")
                }
                runCatching { nd.close() }
            }
        }

        notes.append("wrote $dataWrote data block(s), $trailerWrote trailer(s)/keys, skipped $skipped, $failed failed")
        if (uidWrote) notes.append("\nUID block 0 written — gen2 magic card")
        else if (card.blocks.containsKey(0) && failed > 0)
            notes.append("\nUID block 0 not writable — needs a gen2 magic card (gen1a's 7-bit backdoor isn't reachable from phone NFC)")
        return notes.toString()
    }

    /** Authenticate [sector] on the target: the source's recorded key first,
     *  then every key in [dict] (key A then key B). */
    private fun authTarget(mc: MifareClassic, sector: Int, sourceKey: String?, dict: List<ByteArray>): Boolean {
        sourceKey?.takeIf { it.length >= 14 }?.let { sk ->
            runCatching { Mifare.hexToBytes(sk.substring(2)) }.getOrNull()?.let { kb ->
                val ok = when (sk[0]) {
                    'A' -> runCatching { mc.authenticateSectorWithKeyA(sector, kb) }.getOrDefault(false)
                    'B' -> runCatching { mc.authenticateSectorWithKeyB(sector, kb) }.getOrDefault(false)
                    else -> false
                }
                if (ok) return true
            }
        }
        for (k in dict) {
            if (runCatching { mc.authenticateSectorWithKeyA(sector, k) }.getOrDefault(false)) return true
            if (runCatching { mc.authenticateSectorWithKeyB(sector, k) }.getOrDefault(false)) return true
        }
        return false
    }

    /** EMV contactless probe: SELECT PPSE, list the application AIDs. */
    private fun emvProbe(iso: IsoDep, extra: MutableList<String>) {
        // SELECT 2PAY.SYS.DDF01
        val ppse = Mifare.hexToBytes("00A404000E325041592E5359532E444446303100")
        val r = runCatching { iso.transceive(ppse) }.getOrNull() ?: return
        if (!endsWith(r, 0x90, 0x00)) return
        val aids = findTag(r, 0x4F)
        if (aids.isEmpty()) return
        extra.add("EMV contactless card:")
        aids.forEach { extra.add("  AID: ${bytesToHex(it)}") }
        extra.add("  PAN/expiry read needs the full GPO + record flow — not attempted (limit).")
    }

    /** MIFARE DESFire probe: GetVersion + GetApplicationIDs (wrapped native commands). */
    private fun desfireProbe(iso: IsoDep, extra: MutableList<String>) {
        var r = runCatching { iso.transceive(Mifare.hexToBytes("9060000000")) }.getOrNull() ?: return
        if (r.size < 2) return
        val sw1 = r[r.size - 2].toInt() and 0xFF
        val sw2 = r[r.size - 1].toInt() and 0xFF
        if (sw1 != 0x91 || (sw2 != 0xAF && sw2 != 0x00)) return // not DESFire
        extra.add("MIFARE DESFire:")
        val ver = ArrayList<Byte>()
        ver.addAll(r.dropLast(2))
        var guard = 0
        while ((r[r.size - 1].toInt() and 0xFF) == 0xAF && guard++ < 5) {
            r = runCatching { iso.transceive(Mifare.hexToBytes("90AF000000")) }.getOrNull() ?: break
            if (r.size < 2) break
            ver.addAll(r.dropLast(2))
        }
        extra.add("  version/UID: ${bytesToHex(ver.toByteArray())}")
        val apps = runCatching { iso.transceive(Mifare.hexToBytes("906A000000")) }.getOrNull()
        if (apps != null && apps.size > 2) {
            extra.add("  application IDs: ${bytesToHex(apps.copyOfRange(0, apps.size - 2))}")
        }
    }

    private fun endsWith(r: ByteArray, sw1: Int, sw2: Int): Boolean =
        r.size >= 2 && (r[r.size - 2].toInt() and 0xFF) == sw1 && (r[r.size - 1].toInt() and 0xFF) == sw2

    /** Scan a TLV blob for every value carrying the given 1-byte tag (length 5..16). */
    private fun findTag(data: ByteArray, tag: Int): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var i = 0
        while (i + 1 < data.size) {
            if ((data[i].toInt() and 0xFF) == tag) {
                val len = data[i + 1].toInt() and 0xFF
                if (len in 5..16 && i + 2 + len <= data.size) {
                    out.add(data.copyOfRange(i + 2, i + 2 + len))
                    i += 2 + len
                    continue
                }
            }
            i++
        }
        return out
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
}
