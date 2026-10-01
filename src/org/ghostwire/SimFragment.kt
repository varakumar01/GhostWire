package org.ghostwire

import android.os.Bundle
import android.telephony.TelephonyManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * SIM lab: read the card's identifiers and send raw APDUs to it.
 *
 * The APDU console is the one low-level primitive — every higher-level SIM
 * test (EF reads, STK applet enumeration, OTA/DES key probing) is just a
 * sequence of APDUs composed on top of it, not a separate engine.
 */
class SimFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_sim, container, false)

    override fun onViewCreated(view: View, s: Bundle?) {
        val tm = requireContext().getSystemService(TelephonyManager::class.java)

        view.findViewById<TextView>(R.id.sim_info).text = buildString {
            append("IMSI:     ").append(safe { tm.subscriberId }).append('\n')
            append("ICCID:    ").append(safe { tm.simSerialNumber }).append('\n')
            append("SPN:      ").append(safe { tm.simOperatorName }).append('\n')
            append("Operator: ").append(safe { tm.simOperator })
        }

        val apduIn = view.findViewById<EditText>(R.id.apdu_in)
        val apduOut = view.findViewById<TextView>(R.id.apdu_out)
        view.findViewById<Button>(R.id.apdu_send).setOnClickListener {
            apduOut.text = runApdu(tm, apduIn.text.toString())
        }
    }

    /** Send one APDU on the basic channel. Input is hex, whitespace ignored. */
    private fun runApdu(tm: TelephonyManager, hex: String): String {
        val a = hex.filterNot { it.isWhitespace() }
        if (a.length < 10 || a.length % 2 != 0) {
            return "need an even-length hex string of at least 5 bytes: CLA INS P1 P2 P3"
        }
        return try {
            val cla = a.substring(0, 2).toInt(16)
            val ins = a.substring(2, 4).toInt(16)
            val p1 = a.substring(4, 6).toInt(16)
            val p2 = a.substring(6, 8).toInt(16)
            val p3 = a.substring(8, 10).toInt(16)
            val data = if (a.length > 10) a.substring(10) else ""
            tm.iccTransmitApduBasicChannel(cla, ins, p1, p2, p3, data) ?: "(null response)"
        } catch (e: NumberFormatException) {
            "bad hex: ${e.message}"
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }

    private inline fun safe(block: () -> String?): String =
        try {
            block() ?: "(null)"
        } catch (e: Exception) {
            "(denied: ${e.message})"
        }
}
