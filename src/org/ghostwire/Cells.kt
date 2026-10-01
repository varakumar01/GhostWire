package org.ghostwire

import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityWcdma
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthWcdma
import android.telephony.TelephonyManager

/** A single serving/neighbor cell: the fields the OSINT lookup needs plus a
 *  full human-readable dump of everything the radio exposed. */
data class CellRec(
    val rat: String,
    val serving: Boolean,
    val mcc: String?,
    val mnc: String?,
    val lac: Int?,   // LAC (GSM/UMTS) or TAC (LTE/NR)
    val cid: Long?,  // cell id
    val dbm: Int,
    val ta: Int?,    // timing advance (RAT-specific units), null if unavailable
    val freq: Rf.Freq?,
    val dump: String,
)

object Cells {

    private fun na(v: Int): String = if (v == CellInfo.UNAVAILABLE) "n/a" else v.toString()
    private fun na(v: Long): String = if (v == CellInfo.UNAVAILABLE_LONG.toLong()) "n/a" else v.toString()
    private fun nz(v: Int): Int? = if (v == CellInfo.UNAVAILABLE) null else v

    /** Read all serving + neighbor cells. Needs ACCESS_FINE_LOCATION. */
    fun read(tm: TelephonyManager): List<CellRec> {
        val all = tm.allCellInfo ?: return emptyList()
        return all.mapNotNull { rec(it) }.sortedByDescending { it.serving }
    }

    private fun rec(info: CellInfo): CellRec? = when (info) {
        is CellInfoLte -> lte(info)
        is CellInfoNr -> nr(info)
        is CellInfoGsm -> gsm(info)
        is CellInfoWcdma -> wcdma(info)
        else -> null
    }

    private fun freqLine(f: Rf.Freq?): String =
        if (f == null) "" else "\n  RF:    ${f.band}  ${"%.1f".format(f.dlMhz)} MHz (DL)"

    private fun lte(info: CellInfoLte): CellRec {
        val id = info.cellIdentity as CellIdentityLte
        val s = info.cellSignalStrength as CellSignalStrengthLte
        val freq = Rf.lte(id.earfcn)
        val bands = runCatching { id.bands.joinToString(",") }.getOrDefault("")
        val dump = "LTE${servingTag(info)}\n" +
            "  PLMN:  ${id.mccString}/${id.mncString}\n" +
            "  CI:    ${na(id.ci)}  (eNB ${eNb(id.ci)} / sector ${sector(id.ci)})\n" +
            "  TAC:   ${na(id.tac)}   PCI: ${na(id.pci)}\n" +
            "  EARFCN:${na(id.earfcn)}  BW: ${na(id.bandwidth)} kHz  bands: $bands" +
            freqLine(freq) + "\n" +
            "  RSRP:  ${s.rsrp} dBm  RSRQ: ${s.rsrq} dB  RSSI: ${na(s.rssi)} dBm\n" +
            "  SINR:  ${na(s.rssnr)}  CQI: ${na(s.cqi)}  TA: ${na(s.timingAdvance)}"
        return CellRec("LTE", info.isRegistered, id.mccString, id.mncString,
            nz(id.tac), nz(id.ci)?.toLong(), s.dbm, nz(s.timingAdvance), freq, dump)
    }

    private fun nr(info: CellInfoNr): CellRec {
        val id = info.cellIdentity as CellIdentityNr
        val s = info.cellSignalStrength as CellSignalStrengthNr
        val freq = Rf.nr(id.nrarfcn)
        val bands = runCatching { id.bands.joinToString(",") }.getOrDefault("")
        val dump = "5G NR${servingTag(info)}\n" +
            "  PLMN:  ${id.mccString}/${id.mncString}\n" +
            "  NCI:   ${na(id.nci)}   PCI: ${na(id.pci)}\n" +
            "  TAC:   ${na(id.tac)}   NRARFCN: ${na(id.nrarfcn)}  bands: $bands" +
            freqLine(freq) + "\n" +
            "  SS-RSRP: ${s.ssRsrp} dBm  SS-RSRQ: ${s.ssRsrq} dB  SS-SINR: ${s.ssSinr} dB"
        return CellRec("NR", info.isRegistered, id.mccString, id.mncString,
            nz(id.tac), if (id.nci == CellInfo.UNAVAILABLE_LONG.toLong()) null else id.nci,
            s.dbm, null, freq, dump)
    }

    private fun gsm(info: CellInfoGsm): CellRec {
        val id = info.cellIdentity as CellIdentityGsm
        val s = info.cellSignalStrength as CellSignalStrengthGsm
        val freq = Rf.gsm(id.arfcn)
        val dump = "GSM${servingTag(info)}\n" +
            "  PLMN:  ${id.mccString}/${id.mncString}\n" +
            "  LAC:   ${na(id.lac)}   CID: ${na(id.cid)}\n" +
            "  ARFCN: ${na(id.arfcn)}  BSIC: ${na(id.bsic)}" +
            freqLine(freq) + "\n" +
            "  RSSI:  ${s.dbm} dBm  TA: ${na(s.timingAdvance)}"
        return CellRec("GSM", info.isRegistered, id.mccString, id.mncString,
            nz(id.lac), nz(id.cid)?.toLong(), s.dbm, nz(s.timingAdvance), freq, dump)
    }

    private fun wcdma(info: CellInfoWcdma): CellRec {
        val id = info.cellIdentity as CellIdentityWcdma
        val s = info.cellSignalStrength as CellSignalStrengthWcdma
        val freq = Rf.umts(id.uarfcn)
        val dump = "UMTS${servingTag(info)}\n" +
            "  PLMN:  ${id.mccString}/${id.mncString}\n" +
            "  LAC:   ${na(id.lac)}   CID: ${na(id.cid)}\n" +
            "  UARFCN:${na(id.uarfcn)}  PSC: ${na(id.psc)}" +
            freqLine(freq) + "\n" +
            "  RSCP:  ${s.dbm} dBm"
        return CellRec("UMTS", info.isRegistered, id.mccString, id.mncString,
            nz(id.lac), nz(id.cid)?.toLong(), s.dbm, null, freq, dump)
    }

    private fun servingTag(info: CellInfo): String = if (info.isRegistered) "  [SERVING]" else ""

    // LTE CI = eNB(28..8) * 256 + sector(7..0)
    private fun eNb(ci: Int): String = if (ci == CellInfo.UNAVAILABLE) "n/a" else (ci / 256).toString()
    private fun sector(ci: Int): String = if (ci == CellInfo.UNAVAILABLE) "n/a" else (ci % 256).toString()
}
