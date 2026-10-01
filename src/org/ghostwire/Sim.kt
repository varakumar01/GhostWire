package org.ghostwire

import android.telephony.IccOpenLogicalChannelResponse
import android.telephony.TelephonyManager

/** APDU helpers + GSM/USIM EF decoding for the SIM lab. */
object Sim {

    // 3GPP file IDs
    const val EF_ICCID = "2FE2"
    const val EF_DIR = "2F00"
    const val EF_IMSI = "6F07"
    const val EF_SPN = "6F46"
    const val EF_AD = "6FAD"
    const val EF_LOCI = "6F7E"

    // Typical transparent-EF sizes (bytes) used as Le; a 6Cxx reply corrects it.
    fun defaultLe(fid: String): Int = when (fid) {
        EF_ICCID -> 10
        EF_IMSI -> 9
        EF_SPN -> 17
        EF_AD -> 4
        EF_LOCI -> 11
        else -> 0
    }

    fun bytesToHex(b: ByteArray): String = b.joinToString("") { "%02X".format(it) }

    fun hexToBytes(s: String): ByteArray {
        val h = s.filterNot { it.isWhitespace() }
        require(h.length % 2 == 0) { "odd-length hex" }
        return ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** Status word (last 2 bytes of a response) in human-readable form. */
    fun decodeSw(resp: String): String {
        val h = resp.filterNot { it.isWhitespace() }.uppercase()
        if (h.length < 4) return "no SW"
        val sw = h.substring(h.length - 4)
        return when {
            sw == "9000" -> "9000 OK"
            sw.startsWith("91") -> "$sw OK, ${sw.substring(2).toInt(16)} bytes of proactive data"
            sw.startsWith("61") -> "$sw OK, ${sw.substring(2).toInt(16)} bytes available (GET RESPONSE)"
            sw.startsWith("6C") -> "$sw wrong Le, correct length is ${sw.substring(2).toInt(16)}"
            sw.startsWith("63C") -> "$sw verify failed, ${sw.substring(3).toInt(16)} tries left"
            sw == "6A82" -> "6A82 file not found"
            sw == "6A83" -> "6A83 record not found"
            sw == "6A86" -> "6A86 wrong P1/P2"
            sw == "6982" -> "6982 security status not satisfied (PIN required?)"
            sw == "6983" -> "6983 authentication method blocked"
            sw == "6984" -> "6984 referenced data invalidated"
            sw == "6700" -> "6700 wrong length"
            sw == "6B00" -> "6B00 wrong P1/P2"
            sw == "6D00" -> "6D00 INS not supported"
            sw == "6E00" -> "6E00 CLA not supported"
            sw == "6F00" -> "6F00 technical problem, no diagnosis"
            else -> "$sw unknown status"
        }
    }

    fun swOk(resp: String): Boolean {
        val h = resp.filterNot { it.isWhitespace() }.uppercase()
        if (h.length < 4) return false
        val sw = h.substring(h.length - 4)
        return sw == "9000" || sw.startsWith("61") || sw.startsWith("91")
    }

    /** Response data without the trailing SW. */
    fun dataOf(resp: String): String {
        val h = resp.filterNot { it.isWhitespace() }
        return if (h.length >= 4) h.substring(0, h.length - 4) else ""
    }

    /** EF_ICCID (2FE2): BCD, nibble-swapped, F padding. */
    fun decodeIccid(dataHex: String): String {
        val sb = StringBuilder()
        for (x in hexToBytes(dataHex)) {
            val v = x.toInt() and 0xFF
            val lo = v and 0x0F
            val hi = (v shr 4) and 0x0F
            if (lo == 0xF) break
            sb.append(lo)
            if (hi == 0xF) break
            sb.append(hi)
        }
        return sb.toString()
    }

    /** EF_IMSI (6F07): length byte, then nibble-swapped digits; first digit is a parity nibble. */
    fun decodeImsi(dataHex: String): String {
        val b = hexToBytes(dataHex)
        if (b.isEmpty()) return ""
        val len = b[0].toInt() and 0xFF
        val sb = StringBuilder()
        for (i in 1..minOf(len, b.size - 1)) {
            val v = b[i].toInt() and 0xFF
            if (i == 1) {
                sb.append((v shr 4) and 0x0F) // high nibble = first digit; low nibble = parity
            } else {
                sb.append(v and 0x0F)
                sb.append((v shr 4) and 0x0F)
            }
        }
        return sb.toString()
    }

    /** An application entry from EF_DIR: its AID and label. */
    data class App(val aid: String, val label: String)

    /** Parse one EF_DIR record (application template, tag 61) into an [App]. */
    fun parseDirRecord(dataHex: String): App? {
        val b = hexToBytes(dataHex)
        var i = 0
        // outer: 61 len { 4F len AID, 50 len label }
        if (i >= b.size || (b[i].toInt() and 0xFF) != 0x61) return null
        i += 2
        var aid = ""
        var label = ""
        while (i + 1 < b.size) {
            val tag = b[i].toInt() and 0xFF
            val len = b[i + 1].toInt() and 0xFF
            val start = i + 2
            if (start + len > b.size) break
            val v = b.copyOfRange(start, start + len)
            when (tag) {
                0x4F -> aid = bytesToHex(v)
                0x50 -> label = String(v).trim { it <= ' ' }
                0xFF -> return if (aid.isEmpty()) null else App(aid, label) // padding
            }
            i = start + len
        }
        return if (aid.isEmpty()) null else App(aid, label)
    }
}

/**
 * A live SIM session over TelephonyManager APDU. Starts on the basic channel;
 * [openUsim] opens a logical channel to the USIM application (discovered via
 * EF_DIR) so the USIM-scoped EFs become reachable.
 */
class SimSession(private val tm: TelephonyManager) {

    var channel: Int = -1
        private set

    val channelLabel: String get() = if (channel >= 0) "logical ch $channel" else "basic channel"

    /** Transmit, auto-following 61xx with GET RESPONSE and retrying 6Cxx once. */
    fun transmit(cla: Int, ins: Int, p1: Int, p2: Int, p3: Int, data: String): String {
        var r = raw(cla, ins, p1, p2, p3, data)
        val h = r.filterNot { it.isWhitespace() }.uppercase()
        if (h.length >= 4) {
            val sw = h.substring(h.length - 4)
            if (sw.startsWith("61")) {
                return raw(cla, 0xC0, 0x00, 0x00, sw.substring(2).toInt(16), "") // GET RESPONSE
            }
            if (sw.startsWith("6C")) {
                return raw(cla, ins, p1, p2, sw.substring(2).toInt(16), data)
            }
        }
        return r
    }

    private fun raw(cla: Int, ins: Int, p1: Int, p2: Int, p3: Int, data: String): String =
        (if (channel >= 0)
            tm.iccTransmitApduLogicalChannel(channel, cla, ins, p1, p2, p3, data)
        else
            tm.iccTransmitApduBasicChannel(cla, ins, p1, p2, p3, data)) ?: ""

    /** Send a full APDU given as a hex string (CLA INS P1 P2 [P3 [data]]). */
    fun send(apduHex: String): String {
        val a = Sim.hexToBytes(apduHex)
        require(a.size >= 4) { "APDU needs at least CLA INS P1 P2" }
        val p3 = if (a.size >= 5) a[4].toInt() and 0xFF else 0
        val data = if (a.size > 5) Sim.bytesToHex(a.copyOfRange(5, a.size)) else ""
        return transmit(a[0].toInt() and 0xFF, a[1].toInt() and 0xFF,
            a[2].toInt() and 0xFF, a[3].toInt() and 0xFF, p3, data)
    }

    /** SELECT a file by its 2-byte file ID, then READ BINARY it. Returns data hex (no SW). */
    fun readTransparent(fid: String): String {
        val sel = transmit(0x00, 0xA4, 0x00, 0x04, 0x02, fid)
        if (!Sim.swOk(sel)) throw ApduError("SELECT $fid", sel)
        var read = transmit(0x00, 0xB0, 0x00, 0x00, Sim.defaultLe(fid), "")
        val h = read.filterNot { it.isWhitespace() }.uppercase()
        if (h.length >= 4 && h.substring(h.length - 4).startsWith("6C")) {
            read = transmit(0x00, 0xB0, 0x00, 0x00, h.substring(h.length - 2).toInt(16), "")
        }
        if (!Sim.swOk(read)) throw ApduError("READ $fid", read)
        return Sim.dataOf(read)
    }

    /** Read EF_DIR records until one comes back empty/not-found; list the applications. */
    fun readDir(): List<Sim.App> {
        val sel = transmit(0x00, 0xA4, 0x00, 0x04, 0x02, Sim.EF_DIR)
        if (!Sim.swOk(sel)) throw ApduError("SELECT EF_DIR", sel)
        val apps = ArrayList<Sim.App>()
        for (rec in 1..10) {
            var r = transmit(0x00, 0xB2, rec, 0x04, 0x00, "") // READ RECORD, Le=0
            val h = r.filterNot { it.isWhitespace() }.uppercase()
            if (h.length >= 4 && h.substring(h.length - 4).startsWith("6C")) {
                r = transmit(0x00, 0xB2, rec, 0x04, h.substring(h.length - 2).toInt(16), "")
            }
            if (!Sim.swOk(r)) break
            Sim.parseDirRecord(Sim.dataOf(r))?.let { apps.add(it) }
        }
        return apps
    }

    /** Discover the USIM AID from EF_DIR and open a logical channel to it. */
    fun openUsim(): Sim.App {
        val usim = readDir().firstOrNull { it.aid.uppercase().startsWith("A0000000871002") }
            ?: throw IllegalStateException("no USIM application in EF_DIR")
        val resp = tm.iccOpenLogicalChannel(usim.aid)
        if (resp.status != IccOpenLogicalChannelResponse.STATUS_NO_ERROR) {
            throw IllegalStateException("open logical channel failed: status=${resp.status}")
        }
        channel = resp.channel
        return usim
    }

    fun close() {
        if (channel >= 0) {
            try {
                tm.iccCloseLogicalChannel(channel)
            } catch (_: Exception) {
            }
            channel = -1
        }
    }
}

class ApduError(val step: String, val resp: String) :
    Exception("$step -> ${Sim.decodeSw(resp)}")
