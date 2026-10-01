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

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** JSON-file persistence for the last walk route, so the Map tab can draw it
 *  (same no-database pattern as [CardStore]). */
class RouteStore(ctx: Context) {

    private val file = File(ctx.filesDir, "route.json")

    fun save(samples: List<Walk.Sample>) {
        val arr = JSONArray()
        samples.forEach { arr.put(JSONObject().put("lat", it.lat).put("lon", it.lon).put("dbm", it.dbm)) }
        runCatching { file.writeText(arr.toString()) }
    }

    fun load(): List<Walk.Sample> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            List(arr.length()) {
                val o = arr.getJSONObject(it)
                Walk.Sample(o.getDouble("lat"), o.getDouble("lon"), o.getInt("dbm"))
            }
        }.getOrDefault(emptyList())
    }
}
