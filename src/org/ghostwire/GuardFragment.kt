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
import android.os.Bundle
import android.telephony.TelephonyManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

/** IMSI-catcher / rogue-cell monitor: runs [Guard] heuristics over the current
 *  cells and remembers the LAC/TACs it has seen so a new area stands out. */
class GuardFragment : Fragment() {

    private lateinit var tm: TelephonyManager
    private lateinit var out: TextView

    private val locationPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_guard, container, false)

    override fun onViewCreated(view: View, s: Bundle?) {
        tm = requireContext().getSystemService(TelephonyManager::class.java)
        out = view.findViewById(R.id.guard_out)
        val b = view.findViewById<Button>(R.id.guard_scan)
        b.setOnClickListener {
            out.text = "scanning …"
            Thread {
                val r = try { scan() } catch (e: Exception) { "error: ${e.message}" }
                out.post { out.text = r }
            }.start()
        }
        view.findViewById<Button>(R.id.guard_copy).copyOnClick(out)
        if (!hasLocation()) locationPerm.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun scan(): String {
        if (!hasLocation()) return "grant location permission first (via the Recon tab)"
        val cells = Cells.read(tm)
        if (cells.isEmpty()) return "no cells to analyze"

        val prefs = requireContext().getSharedPreferences("ghostwire", Context.MODE_PRIVATE)
        val known = prefs.getStringSet("known_areas", emptySet()) ?: emptySet()
        val findings = Guard.analyze(cells, known)

        // Learn the current serving area so it isn't re-flagged as "new" next time.
        cells.firstOrNull { it.serving }?.let { Guard.areaKey(it) }?.let { area ->
            prefs.edit().putStringSet("known_areas", known + area).apply()
        }

        return findings.joinToString("\n\n") { "[${it.sev}] ${it.text}" }
    }

    private fun hasLocation(): Boolean =
        ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
