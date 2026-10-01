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
