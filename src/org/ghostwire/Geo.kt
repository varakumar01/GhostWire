package org.ghostwire

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Spherical geometry + the cellular distance approximations. */
object Geo {

    private const val R = 6_371_000.0 // earth radius, metres

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial bearing (degrees, 0=N) from point 1 to point 2. */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    private val COMPASS = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    fun compass(bearing: Double): String = COMPASS[(((bearing + 22.5) % 360) / 45).toInt()]

    /**
     * Rough distance to the serving tower from Timing Advance. This is the best
     * on-device distance estimate (far better than signal-strength path-loss),
     * but it's quantised: LTE ~78 m per TA unit, GSM ~554 m per unit.
     */
    fun taDistanceMeters(rat: String, ta: Int): Int? = when (rat) {
        "LTE" -> (ta * 78.13).roundToInt()
        "GSM" -> (ta * 553.6).roundToInt()
        else -> null // NR/UMTS TA not exposed as a simple linear unit here
    }

    data class LocatedTower(val lat: Double, val lon: Double, val dbm: Int)
    data class Estimate(val lat: Double, val lon: Double, val towers: Int, val spreadM: Int)

    /**
     * Weighted-centroid position estimate from several OSINT-located towers.
     * Weight rises with signal strength in the dB domain, so the nearest few
     * towers pull hardest without any single one swamping the rest. The
     * reported spread (widest tower-to-tower gap) is a rough geometry/confidence
     * hint — a tight cluster can't pin position well.
     * ponytail: dB-weighted centroid; upgrade to range-based least-squares
     * trilateration if accuracy matters (needs reliable per-tower distances).
     */
    fun estimatePosition(towers: List<LocatedTower>): Estimate? {
        if (towers.size < 2) return null
        val minDbm = towers.minOf { it.dbm }
        var sumW = 0.0
        var sumLat = 0.0
        var sumLon = 0.0
        for (t in towers) {
            val w = (t.dbm - minDbm + 1).toDouble() // >=1; stronger signal = larger weight
            sumW += w
            sumLat += w * t.lat
            sumLon += w * t.lon
        }
        var spread = 0.0
        for (i in towers.indices) for (j in i + 1 until towers.size) {
            spread = maxOf(
                spread,
                haversineMeters(towers[i].lat, towers[i].lon, towers[j].lat, towers[j].lon)
            )
        }
        return Estimate(sumLat / sumW, sumLon / sumW, towers.size, spread.toInt())
    }
}
