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
import android.content.Context
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
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import java.io.BufferedWriter
import java.io.File

/**
 * Recon: everything the radio and SIM expose, plus opt-in OSINT tower
 * geolocation. Cell/GPS reads are gated on ACCESS_FINE_LOCATION (requested at
 * runtime); the OpenCelliD lookup is the only network call and needs an
 * explicit key + tap.
 */
class ReconFragment : Fragment() {

    private lateinit var tm: TelephonyManager
    private lateinit var out: TextView
    private lateinit var keyField: EditText
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var tracing = false
    private var traceWriter: BufferedWriter? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_recon, container, false)

    override fun onViewCreated(view: View, s: Bundle?) {
        tm = requireContext().getSystemService(TelephonyManager::class.java)
        out = view.findViewById(R.id.recon_out)
        keyField = view.findViewById(R.id.recon_key)
        keyField.setText(prefs().getString("opencellid_key", ""))

        if (!hasLocation()) requestPermissions(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1
        )

        action(view, R.id.recon_cells) { dumpCells() }
        action(view, R.id.recon_sim) { dumpSim() }
        action(view, R.id.recon_towers) { locateTowers() }
        view.findViewById<Button>(R.id.recon_trace).also { b ->
            b.setOnClickListener { toggleTrace(b) }
        }
        view.findViewById<Button>(R.id.recon_copy).copyOnClick(out)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        tracing = false
        handler.removeCallbacksAndMessages(null)
        runCatching { traceWriter?.close() }
        traceWriter = null
    }

    private fun prefs() = requireContext().getSharedPreferences("ghostwire", Context.MODE_PRIVATE)

    private fun hasLocation(): Boolean =
        ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun action(view: View, id: Int, block: () -> String) {
        val b = view.findViewById<Button>(id)
        b.setOnClickListener {
            out.text = "${b.text} …"
            Thread {
                val r = try { block() } catch (e: Exception) { "error: ${e.message}" }
                out.post { out.text = r }
            }.start()
        }
    }

    private fun dumpCells(): String {
        if (!hasLocation()) return "grant location permission first (cell scan needs it)"
        val cells = Cells.read(tm)
        return if (cells.isEmpty()) "no cells reported (no service, or permission denied)"
        else cells.joinToString("\n\n") { it.dump }
    }

    private fun dumpSim(): String {
        val imsi = try { tm.subscriberId } catch (e: Exception) { null }
        val iccid = try { tm.simSerialNumber } catch (e: Exception) { null }
        return SimOsint.decodeImsi(imsi) + "\n\n" + SimOsint.decodeIccid(iccid) + "\n\n" +
            "Network: ${tm.networkOperatorName} (${tm.networkOperator}) ${tm.networkCountryIso}\n" +
            "SIM:     ${tm.simOperatorName} (${tm.simOperator}) ${tm.simCountryIso}"
    }

    private fun locateTowers(): String {
        val key = keyField.text.toString().trim()
        if (key.isEmpty()) return "enter an OpenCelliD API key above, then retry"
        prefs().edit().putString("opencellid_key", key).apply()
        if (!hasLocation()) return "grant location permission first"

        val cells = Cells.read(tm)
        if (cells.isEmpty()) return "no cells to look up"
        val here = lastLocation()
        val sb = StringBuilder()
        if (here != null) sb.append("you: ${fmt(here.latitude)}, ${fmt(here.longitude)}\n\n")
        else sb.append("your GPS fix unavailable — distance/bearing will be skipped\n\n")

        val located = ArrayList<Geo.LocatedTower>()
        for (c in cells) {
            sb.append("${c.rat} ${c.mcc}/${c.mnc} LAC ${c.lac} CID ${c.cid}  ${c.dbm} dBm\n")
            // Timing-advance distance, independent of OSINT:
            c.ta?.let { Geo.taDistanceMeters(c.rat, it)?.let { d -> sb.append("  TA distance ~${d} m\n") } }
            val r = CellDb.lookup(key, c)
            r.onSuccess { t ->
                located.add(Geo.LocatedTower(t.lat, t.lon, c.dbm))
                sb.append("  tower: ${fmt(t.lat)}, ${fmt(t.lon)}")
                if (t.rangeM >= 0) sb.append("  (range ${t.rangeM} m, ${t.samples} samples)")
                sb.append('\n')
                if (here != null) {
                    val dist = Geo.haversineMeters(here.latitude, here.longitude, t.lat, t.lon)
                    val brg = Geo.bearingDeg(here.latitude, here.longitude, t.lat, t.lon)
                    sb.append("  ${dist.toInt()} m away, bearing ${brg.toInt()}° ${Geo.compass(brg)}\n")
                }
                sb.append("  map: geo:${t.lat},${t.lon}\n")
            }.onFailure { sb.append("  OSINT: ${it.message}\n") }
            sb.append('\n')
        }

        val est = Geo.estimatePosition(located)
        if (est != null) {
            sb.append("estimated position (${est.towers} towers, spread ${est.spreadM} m): " +
                "${fmt(est.lat)}, ${fmt(est.lon)}\n")
            if (here != null) sb.append("  error vs GPS: " +
                "${Geo.haversineMeters(here.latitude, here.longitude, est.lat, est.lon).toInt()} m\n")
            sb.append("  map: geo:${est.lat},${est.lon}\n")
        } else if (located.size < 2) {
            sb.append("(need 2+ located towers for a position estimate)\n")
        }

        return sb.toString()
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

    // ponytail: naive fixed-interval logger, serving cell only; good enough for a trace,
    // not a background service. Stops when the tab is left.
    private fun toggleTrace(btn: Button) {
        if (tracing) {
            tracing = false
            handler.removeCallbacksAndMessages(null)
            runCatching { traceWriter?.close() }
            traceWriter = null
            btn.text = "Start trace"
            out.text = "trace stopped"
            return
        }
        if (!hasLocation()) { out.text = "grant location permission first"; return }
        val f = File(requireContext().getExternalFilesDir(null), "trace-${System.currentTimeMillis()}.csv")
        traceWriter = f.bufferedWriter().apply { write("time,rat,mcc,mnc,lac,cid,dbm,ta,lat,lon\n") }
        tracing = true
        btn.text = "Stop trace"
        out.text = "tracing to ${f.absolutePath}"
        traceTick()
    }

    private fun traceTick() {
        if (!tracing) return
        Thread {
            val c = Cells.read(tm).firstOrNull { it.serving }
            val loc = lastLocation()
            if (c != null) runCatching {
                traceWriter?.apply {
                    write("${System.currentTimeMillis()},${c.rat},${c.mcc},${c.mnc}," +
                        "${c.lac},${c.cid},${c.dbm},${c.ta},${loc?.latitude},${loc?.longitude}\n")
                    flush()
                }
            }
        }.start()
        handler.postDelayed({ traceTick() }, 5000)
    }

    private fun fmt(d: Double) = "%.5f".format(d)
}
