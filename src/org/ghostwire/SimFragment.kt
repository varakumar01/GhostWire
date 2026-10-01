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
 * The APDU console is the one low-level primitive; the preset buttons are just
 * canned APDU sequences on top of it (SELECT + READ BINARY, EF_DIR walk, open a
 * logical channel to the USIM). Every response is shown raw with its decoded
 * status word so a failing step is debuggable, not silent.
 */
class SimFragment : Fragment() {

    private lateinit var session: SimSession
    private lateinit var out: TextView
    private lateinit var channelView: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_sim, container, false)

    override fun onViewCreated(view: View, s: Bundle?) {
        val tm = requireContext().getSystemService(TelephonyManager::class.java)
        session = SimSession(tm)
        out = view.findViewById(R.id.apdu_out)
        channelView = view.findViewById(R.id.sim_channel)
        view.findViewById<Button>(R.id.apdu_copy).copyOnClick(out)

        view.findViewById<TextView>(R.id.sim_info).text = buildString {
            append("IMSI:     ").append(safe { tm.subscriberId }).append('\n')
            append("ICCID:    ").append(safe { tm.simSerialNumber }).append('\n')
            append("SPN:      ").append(safe { tm.simOperatorName }).append('\n')
            append("Operator: ").append(safe { tm.simOperator })
        }
        updateChannel()

        val apduIn = view.findViewById<EditText>(R.id.apdu_in)
        view.findViewById<Button>(R.id.apdu_send).setOnClickListener {
            val hex = apduIn.text.toString()
            run("APDU") {
                val r = session.send(hex)
                "${group(r)}\n${Sim.decodeSw(r)}"
            }
        }

        preset(view, R.id.p_openusim) { val a = session.openUsim(); updateChannel(); "USIM: ${a.aid} ${a.label}" }
        preset(view, R.id.p_iccid) { "ICCID: ${Sim.decodeIccid(session.readTransparent(Sim.EF_ICCID))}" }
        preset(view, R.id.p_imsi) { "IMSI: ${Sim.decodeImsi(session.readTransparent(Sim.EF_IMSI))}" }
        preset(view, R.id.p_spn) { "SPN raw: ${group(session.readTransparent(Sim.EF_SPN))}" }
        preset(view, R.id.p_ad) { "AD: ${group(session.readTransparent(Sim.EF_AD))}" }
        preset(view, R.id.p_loci) { "LOCI: ${group(session.readTransparent(Sim.EF_LOCI))}" }
        preset(view, R.id.p_apps) {
            val apps = session.readDir()
            if (apps.isEmpty()) "no applications in EF_DIR"
            else apps.joinToString("\n") { "${it.aid}  ${it.label}" }
        }
        preset(view, R.id.p_close) { session.close(); updateChannel(); "channel closed" }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        session.close()
    }

    private fun preset(view: View, id: Int, action: () -> String) {
        val b = view.findViewById<Button>(id)
        b.setOnClickListener { run(b.text.toString(), action) }
    }

    /** Run an APDU action off the UI thread; APDU calls can be slow on some cards. */
    private fun run(label: String, action: () -> String) {
        out.text = "$label …"
        Thread {
            val result = try {
                action()
            } catch (e: ApduError) {
                e.message ?: "APDU error"
            } catch (e: Exception) {
                "error: ${e.message}"
            }
            out.post { out.text = result }
        }.start()
    }

    private fun updateChannel() {
        channelView.text = "channel: ${session.channelLabel}"
    }

    /** Group a hex string into byte pairs for readability. */
    private fun group(hex: String): String =
        hex.filterNot { it.isWhitespace() }.chunked(2).joinToString(" ")

    private inline fun safe(block: () -> String?): String =
        try {
            block() ?: "(null)"
        } catch (e: Exception) {
            "(denied: ${e.message})"
        }
}
