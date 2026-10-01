package org.ghostwire

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tower geolocation via OpenCelliD (opt-in OSINT).
 *
 * This is the only part of GhostWire that talks to a third party: it sends the
 * cell identifiers (MCC/MNC/LAC/CID) of a tower to opencellid.org to get back
 * its crowd-sourced lat/lon. Requires a user-supplied API key and an explicit
 * action — nothing is sent in the background.
 */
object CellDb {

    data class Tower(val lat: Double, val lon: Double, val rangeM: Int, val samples: Int)

    fun lookup(apiKey: String, rec: CellRec): Result<Tower> {
        val mcc = rec.mcc?.toIntOrNull()
        val mnc = rec.mnc?.toIntOrNull()
        val lac = rec.lac
        val cid = rec.cid
        if (mcc == null || mnc == null || lac == null || cid == null) {
            return Result.failure(IllegalArgumentException("cell has no MCC/MNC/LAC/CID to look up"))
        }
        val radio = when (rec.rat) {
            "LTE" -> "LTE"; "UMTS" -> "UMTS"; "GSM" -> "GSM"; "NR" -> "NR"; else -> "LTE"
        }
        val url = "https://opencellid.org/cell/get?key=$apiKey&radio=$radio" +
            "&mcc=$mcc&mnc=$mnc&lac=$lac&cellid=$cid&format=json"
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                .bufferedReader().use { it.readText() }
            conn.disconnect()
            val j = JSONObject(body)
            if (j.optString("status") == "ok" || j.has("lat")) {
                Result.success(
                    Tower(
                        j.getDouble("lat"), j.getDouble("lon"),
                        j.optInt("range", -1), j.optInt("samples", -1),
                    )
                )
            } else {
                Result.failure(RuntimeException(j.optString("message", "not found (HTTP $code)")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
