package org.ghostwire

import org.json.JSONArray
import org.json.JSONObject

private fun JSONObject.strOrNull(key: String): String? =
    if (isNull(key)) null else optString(key)

/** A contactless card captured from the phone's NFC. Serialises to JSON for
 *  storage and export — no database. */
data class Card(
    val id: String,
    var name: String,
    val timestamp: Long,
    val techList: List<String>,
    val uid: String,
    val atqa: String?,
    val sak: String?,
    val ats: String?,
    val typeLabel: String,
    val blocks: Map<Int, String>,
    val ndef: List<String>,
    val keys: Map<Int, String> = emptyMap(), // sector -> "A <hex>" / "B <hex>" found
    var notes: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("timestamp", timestamp)
        put("techList", JSONArray(techList))
        put("uid", uid)
        put("atqa", atqa ?: JSONObject.NULL)
        put("sak", sak ?: JSONObject.NULL)
        put("ats", ats ?: JSONObject.NULL)
        put("typeLabel", typeLabel)
        put("blocks", JSONObject().also { b -> blocks.forEach { (k, v) -> b.put(k.toString(), v) } })
        put("ndef", JSONArray(ndef))
        put("keys", JSONObject().also { j -> keys.forEach { (k, v) -> j.put(k.toString(), v) } })
        put("notes", notes)
    }

    companion object {
        fun fromJson(o: JSONObject): Card {
            val tech = ArrayList<String>()
            o.optJSONArray("techList")?.let { for (i in 0 until it.length()) tech.add(it.getString(i)) }
            val blocks = HashMap<Int, String>()
            o.optJSONObject("blocks")?.let { bj -> bj.keys().forEach { k -> blocks[k.toInt()] = bj.getString(k) } }
            val ndef = ArrayList<String>()
            o.optJSONArray("ndef")?.let { for (i in 0 until it.length()) ndef.add(it.getString(i)) }
            val keys = HashMap<Int, String>()
            o.optJSONObject("keys")?.let { kj -> kj.keys().forEach { k -> keys[k.toInt()] = kj.getString(k) } }
            return Card(
                o.getString("id"), o.optString("name"), o.getLong("timestamp"),
                tech, o.optString("uid"),
                o.strOrNull("atqa"), o.strOrNull("sak"), o.strOrNull("ats"),
                o.optString("typeLabel"), blocks, ndef, keys, o.optString("notes"),
            )
        }

        /** ISO 14443-A product type from SAK (+ tech list). Public reference data. */
        fun typeFor(sak: Int?, atqa: Int?, tech: List<String>): String {
            if (sak != null) when (sak and 0xFF) {
                0x00 -> return "MIFARE Ultralight / NTAG"
                0x01 -> return "MIFARE Classic (TNP3xxx)"
                0x08 -> return "MIFARE Classic 1K"
                0x09 -> return "MIFARE Mini"
                0x10 -> return "MIFARE Plus 2K (SL2)"
                0x11 -> return "MIFARE Plus 4K (SL2)"
                0x18 -> return "MIFARE Classic 4K"
                0x20 -> return "ISO 14443-4 (DESFire / JCOP / Plus SL3)"
                0x28 -> return "JCOP (SmartMX + Classic)"
            }
            return when {
                tech.any { it.endsWith("NfcV") } -> "ISO 15693 (NfcV)"
                tech.any { it.endsWith("NfcB") } -> "ISO 14443-B"
                tech.any { it.endsWith("IsoDep") } -> "ISO 14443-4 (IsoDep)"
                tech.any { it.endsWith("NfcF") } -> "FeliCa (NfcF)"
                else -> "Unknown"
            }
        }
    }
}
