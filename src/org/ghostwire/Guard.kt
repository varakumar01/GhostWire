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

/**
 * IMSI-catcher / rogue-cell heuristics from the telephony data alone (no root).
 *
 * These are *indicators*, not proof — every one has benign causes (a real 2G-only
 * area, a genuinely new tower, a small cell). The strong tells that need the
 * cipher/null-cipher/paging view aren't exposed to apps; those need DIAG
 * (SnoopSnitch), which is flagged here rather than faked.
 */
object Guard {

    data class Finding(val sev: String, val text: String)

    fun areaKey(c: CellRec): String? =
        if (c.mcc != null && c.lac != null) "${c.mcc}-${c.mnc}-${c.lac}" else null

    fun analyze(cells: List<CellRec>, knownAreas: Set<String>): List<Finding> {
        val f = ArrayList<Finding>()
        val serving = cells.firstOrNull { it.serving }
        if (serving == null) {
            f.add(Finding("INFO", "no serving cell registered"))
            return f
        }
        val neighbors = cells.count { !it.serving }

        if (serving.rat == "GSM") {
            f.add(Finding("HIGH", "serving on 2G/GSM — a forced downgrade is a classic IMSI-catcher tell"))
        }
        if (neighbors == 0) {
            f.add(Finding("MED", "serving cell reports no neighbors — atypical for a real macro cell"))
        }
        areaKey(serving)?.let { area ->
            if (area !in knownAreas) {
                f.add(Finding("MED", "new LAC/TAC ${serving.lac} ($area) not seen before"))
            }
        }
        if (serving.dbm > -60) {
            f.add(Finding("LOW", "serving signal very strong (${serving.dbm} dBm) — possible very close/rogue cell"))
        }
        f.add(Finding("INFO", "cipher / null-cipher / paging checks need DIAG (SnoopSnitch) — not exposed to apps"))
        return f
    }
}
