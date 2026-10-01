package org.ghostwire

/**
 * Channel-number → frequency/band decode. This is the "point the SDR here"
 * layer: given the modem's current ARFCN/EARFCN/NRARFCN it reports the actual
 * MHz the cellular antenna is working on.
 *
 * Band tables cover the common bands, not every 3GPP allocation; an unknown
 * channel still yields a frequency where a direct formula exists (NR global
 * formula, UMTS), and otherwise is reported as unknown rather than guessed.
 */
object Rf {

    data class Freq(val band: String, val dlMhz: Double)

    // LTE: band, EARFCN DL low, EARFCN DL high, FDL_low (MHz). FDL = low + 0.1*(earfcn-earfcnLow)
    private data class LteBand(val band: Int, val lo: Int, val hi: Int, val fLow: Double)

    private val lte = listOf(
        LteBand(1, 0, 599, 2110.0), LteBand(2, 600, 1199, 1930.0),
        LteBand(3, 1200, 1949, 1805.0), LteBand(4, 1950, 2399, 2110.0),
        LteBand(5, 2400, 2649, 869.0), LteBand(7, 2750, 3449, 2620.0),
        LteBand(8, 3450, 3799, 925.0), LteBand(12, 5010, 5179, 729.0),
        LteBand(13, 5180, 5279, 746.0), LteBand(14, 5280, 5379, 758.0),
        LteBand(17, 5730, 5849, 734.0), LteBand(18, 5850, 5999, 860.0),
        LteBand(19, 6000, 6149, 875.0), LteBand(20, 6150, 6449, 791.0),
        LteBand(25, 8040, 8689, 1930.0), LteBand(26, 8690, 9039, 859.0),
        LteBand(28, 9210, 9659, 758.0), LteBand(38, 37750, 38249, 2570.0),
        LteBand(40, 38650, 39649, 2300.0), LteBand(41, 39650, 41589, 2496.0),
        LteBand(66, 66436, 67335, 2110.0), LteBand(71, 68586, 68935, 617.0),
    )

    fun lte(earfcn: Int): Freq? {
        if (earfcn < 0) return null
        val b = lte.firstOrNull { earfcn in it.lo..it.hi } ?: return null
        return Freq("B${b.band}", b.fLow + 0.1 * (earfcn - b.lo))
    }

    /** NR global frequency formula (3GPP TS 38.104 §5.4.2). Returns DL MHz. */
    fun nr(nrarfcn: Int): Freq? {
        if (nrarfcn < 0) return null
        val hz = when {
            nrarfcn <= 599999 -> 5_000.0 * nrarfcn
            nrarfcn <= 2016666 -> 3_000_000_000.0 + 15_000.0 * (nrarfcn - 600000)
            else -> 24_250_080_000.0 + 60_000.0 * (nrarfcn - 2016667)
        }
        return Freq("NR", hz / 1_000_000.0)
    }

    /** UMTS downlink: FDL ≈ 0.2 × UARFCN (band I and most FDD bands). */
    fun umts(uarfcn: Int): Freq? =
        if (uarfcn <= 0) null else Freq("UMTS", 0.2 * uarfcn)

    /** GSM ARFCN → DL MHz across the four common bands. */
    fun gsm(arfcn: Int): Freq? = when (arfcn) {
        in 1..124 -> Freq("GSM900", 935.0 + 0.2 * arfcn)
        in 975..1023 -> Freq("EGSM900", 935.0 + 0.2 * (arfcn - 1024))
        in 128..251 -> Freq("GSM850", 869.2 + 0.2 * (arfcn - 128))
        in 512..810 -> Freq("DCS1800/PCS1900", 1805.2 + 0.2 * (arfcn - 512))
        in 811..885 -> Freq("DCS1800", 1805.2 + 0.2 * (arfcn - 512))
        else -> null
    }
}
