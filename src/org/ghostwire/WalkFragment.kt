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

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

/** Walk-test direction finder: sample serving-cell RSSI + GPS while moving,
 *  then estimate the tower bearing from the signal gradient ([Walk]). */
class WalkFragment : Fragment() {

    private lateinit var tm: TelephonyManager
    private lateinit var out: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val samples = ArrayList<Walk.Sample>()
    @Volatile private var walking = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_walk, container, false)

    override fun onViewCreated(view: View, s: Bundle?) {
        tm = requireContext().getSystemService(TelephonyManager::class.java)
        out = view.findViewById(R.id.walk_out)
        view.findViewById<Button>(R.id.walk_toggle).also { b -> b.setOnClickListener { toggle(b) } }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        walking = false
        handler.removeCallbacksAndMessages(null)
    }

    private fun toggle(btn: Button) {
        if (walking) {
            walking = false
            handler.removeCallbacksAndMessages(null)
            btn.text = "Start walk"
            out.text = report(Walk.estimate(samples))
            return
        }
        if (!hasLocation()) { out.text = "grant location permission first (via the Recon tab)"; return }
        samples.clear()
        walking = true
        btn.text = "Stop & estimate"
        out.text = "walking — move 50+ m in a straight line, then stop"
        tick()
    }

    private fun tick() {
        if (!walking) return
        Thread {
            val cell = Cells.read(tm).firstOrNull { it.serving }
            val loc = lastLocation()
            if (cell != null && loc != null) {
                samples.add(Walk.Sample(loc.latitude, loc.longitude, cell.dbm))
                out.post { out.text = "samples: ${samples.size}  last: ${cell.dbm} dBm" }
            }
        }.start()
        handler.postDelayed({ tick() }, 2000)
    }

    private fun report(d: Walk.Direction?): String = when {
        d == null -> "not enough movement/signal gradient — need 6+ samples over 5+ m. Try a longer straight walk."
        else -> "tower bearing ≈ ${d.bearing.toInt()}° ${Geo.compass(d.bearing)}\n" +
            "from ${d.samples} samples, Δ${d.deltaDb} dB over ${d.spanM} m\n" +
            "(rough — accuracy improves with a longer, straighter walk)"
    }

    private fun lastLocation(): Location? {
        if (!hasLocation()) return null
        val lm = requireContext().getSystemService(LocationManager::class.java)
        return try {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: SecurityException) {
            null
        }
    }

    private fun hasLocation(): Boolean =
        ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
