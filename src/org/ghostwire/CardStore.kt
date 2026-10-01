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
import java.io.File

/** JSON-file persistence for captured cards (no database). */
class CardStore(private val ctx: Context) {

    private val file = File(ctx.filesDir, "cards.json")

    fun load(): MutableList<Card> {
        if (!file.exists()) return mutableListOf()
        return runCatching {
            val arr = JSONArray(file.readText())
            MutableList(arr.length()) { Card.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(mutableListOf())
    }

    fun save(cards: List<Card>) {
        val arr = JSONArray()
        cards.forEach { arr.put(it.toJson()) }
        file.writeText(arr.toString(2))
    }

    /** Write all cards to a timestamped file in external files dir; return it. */
    fun export(cards: List<Card>): File {
        val arr = JSONArray()
        cards.forEach { arr.put(it.toJson()) }
        val out = File(ctx.getExternalFilesDir(null), "cards-export-${System.currentTimeMillis()}.json")
        out.writeText(arr.toString(2))
        return out
    }
}
