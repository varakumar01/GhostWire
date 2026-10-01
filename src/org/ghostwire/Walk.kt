package org.ghostwire

import kotlin.math.roundToInt

/**
 * Direction finding by walk test — the honest way a phone infers tower bearing.
 *
 * As you move, signal rises toward the tower. Split the samples by signal into
 * a weak half and a strong half; the bearing from the weak-centroid to the
 * strong-centroid points roughly at the tower. Needs real movement and a signal
 * gradient — a tight cluster of samples (small span) gives no usable direction.
 */
object Walk {

    data class Sample(val lat: Double, val lon: Double, val dbm: Int)
    data class Direction(val bearing: Double, val samples: Int, val deltaDb: Int, val spanM: Int)

    fun estimate(samples: List<Sample>): Direction? {
        if (samples.size < 6) return null
        val sorted = samples.sortedBy { it.dbm }
        val half = samples.size / 2
        val weak = sorted.take(half)
        val strong = sorted.takeLast(half)
        val w = centroid(weak)
        val s = centroid(strong)
        val span = Geo.haversineMeters(w.first, w.second, s.first, s.second)
        if (span < 5.0) return null // didn't move enough / no gradient to read
        val bearing = Geo.bearingDeg(w.first, w.second, s.first, s.second)
        val deltaDb = (strong.map { it.dbm }.average() - weak.map { it.dbm }.average()).roundToInt()
        return Direction(bearing, samples.size, deltaDb, span.toInt())
    }

    private fun centroid(s: List<Sample>): Pair<Double, Double> =
        Pair(s.map { it.lat }.average(), s.map { it.lon }.average())
}
