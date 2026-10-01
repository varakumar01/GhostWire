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

import android.app.AlertDialog
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Contactless lab: read a card with the phone's NFC, store it, browse the
 * wallet, and export. Card UI mirrors Walrus's layout (name header, logo,
 * info band) in GhostWire's Material theme. 13.56 MHz only — see [NfcCapture].
 */
class WalrusFragment : Fragment() {

    private lateinit var store: CardStore
    private val cards = ArrayList<Card>()
    private lateinit var adapter: CardAdapter
    private lateinit var status: TextView
    private var nfc: NfcAdapter? = null
    private var keys: List<ByteArray> = emptyList()
    @Volatile private var capturing = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_walrus, container, false)

    override fun onViewCreated(view: View, s: Bundle?) {
        store = CardStore(requireContext())
        cards.addAll(store.load())
        keys = Mifare.allKeys(requireContext())
        nfc = NfcAdapter.getDefaultAdapter(requireContext())
        status = view.findViewById(R.id.walrus_status)

        adapter = CardAdapter(cards) { showDetail(it) }
        view.findViewById<RecyclerView>(R.id.card_list).also {
            it.layoutManager = LinearLayoutManager(requireContext())
            it.adapter = adapter
        }
        view.findViewById<Button>(R.id.card_read).setOnClickListener { startCapture() }
        view.findViewById<Button>(R.id.card_export).setOnClickListener { exportCards() }
        view.findViewById<Button>(R.id.card_limits).setOnClickListener { showLimits() }
        updateStatus()
    }

    override fun onPause() {
        super.onPause()
        stopCapture()
    }

    private fun updateStatus() {
        status.text = when {
            nfc == null -> "NFC not available on this device"
            nfc?.isEnabled == false -> "NFC is off — enable it in system settings"
            capturing -> "Tap a card to the phone…"
            else -> "${cards.size} card(s) saved"
        }
    }

    private fun startCapture() {
        val a = nfc ?: return updateStatus()
        if (!a.isEnabled) return updateStatus()
        capturing = true
        updateStatus()
        a.enableReaderMode(
            requireActivity(), { tag -> onTag(tag) },
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_NFC_F,
            null,
        )
    }

    private fun stopCapture() {
        if (capturing) {
            capturing = false
            runCatching { nfc?.disableReaderMode(requireActivity()) }
        }
    }

    // Runs on an NFC binder thread.
    private fun onTag(tag: Tag) {
        val card = runCatching {
            NfcCapture.read(tag, keys) { msg -> activity?.runOnUiThread { status.text = msg } }
        }.getOrNull()
        activity?.runOnUiThread {
            stopCapture()
            if (card != null) {
                cards.add(0, card)
                store.save(cards)
                adapter.notifyDataSetChanged()
                status.text = if (card.keys.isNotEmpty())
                    "saved — recovered ${card.keys.size} sector key(s) from the dictionary"
                else "${cards.size} card(s) saved"
            } else {
                updateStatus()
            }
        }
    }

    private fun exportCards() {
        if (cards.isEmpty()) {
            status.text = "nothing to export"
            return
        }
        val f = store.export(cards)
        val uri = FileProvider.getUriForFile(requireContext(), "org.ghostwire.fileprovider", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Export cards"))
        status.text = "exported ${cards.size} card(s) to ${f.absolutePath}"
    }

    private fun showLimits() {
        val msg = """
            Phone NFC is 13.56 MHz only. Hard limits:

            • 125 kHz LF cards (HID Prox, EM4100, Indala): impossible on any phone — needs external hardware (Proxmark). [universal]

            • MIFARE Classic read: needs an NXP-class controller. This chip supports it; some (e.g. Broadcom) phones can't read Classic at all. [device-specific]

            • MIFARE Classic key cracking — nested / darkside / hardnested: NOT possible via Android NFC. The API never exposes the nonces/parity those attacks need (auth happens inside the controller). On-device cracking = dictionary only; real nonce attacks need a Proxmark/libnfc reader. [universal to phones]

            • MIFARE Classic emulation (HCE): not supported — needs a secure element. [device-specific]

            • FeliCa (NfcF) block read and full ISO-DEP/EMV depth: vary by device/region.

            Some limits are universal; others depend on this phone's NFC controller and secure element.
        """.trimIndent()
        AlertDialog.Builder(requireContext())
            .setTitle("NFC limits")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showDetail(card: Card) {
        val sb = StringBuilder()
        sb.append("Type:  ${card.typeLabel}\n")
        sb.append("UID:   ${card.uid}\n")
        card.atqa?.let { sb.append("ATQA:  $it\n") }
        card.sak?.let { sb.append("SAK:   $it\n") }
        card.ats?.let { sb.append("ATS/hist: $it\n") }
        sb.append("Tech:  ${card.techList.joinToString(", ")}\n")
        if (card.ndef.isNotEmpty()) {
            sb.append("\nNDEF:\n")
            card.ndef.forEach { sb.append("  $it\n") }
        }
        if (card.extra.isNotEmpty()) {
            sb.append("\nProtocol data:\n")
            card.extra.forEach { sb.append("  $it\n") }
        }
        if (card.keys.isNotEmpty()) {
            sb.append("\nKeys found (${card.keys.size} sectors):\n")
            card.keys.toSortedMap().forEach { (s, k) -> sb.append("  sector %2d: %s\n".format(s, k)) }
        }
        if (card.blocks.isNotEmpty()) {
            sb.append("\nBlocks (${card.blocks.size} read):\n")
            card.blocks.toSortedMap().forEach { (b, hex) ->
                sb.append("  %3d: %s".format(b, hex))
                val bytes = runCatching { Mifare.hexToBytes(hex) }.getOrNull()
                if (bytes != null && card.techList.contains("MifareClassic")) {
                    if (Mifare.isTrailer(b)) {
                        Mifare.accessCodes(bytes)?.let { c ->
                            sb.append("  [trailer] d0:${Mifare.dataBlockAccess(c[0])}")
                            sb.append(if (Mifare.keyBReadable(c[3])) "; keyB readable" else "; keyB protected")
                        }
                    } else {
                        Mifare.valueOf(bytes)?.let { sb.append("  [value=$it]") }
                    }
                }
                sb.append('\n')
            }
        }
        AlertDialog.Builder(requireContext())
            .setTitle(card.name)
            .setMessage(sb.toString())
            .setPositiveButton("Close", null)
            .setNeutralButton("Delete") { _, _ ->
                cards.remove(card)
                store.save(cards)
                adapter.notifyDataSetChanged()
                updateStatus()
            }
            .show()
    }
}

/** Walrus-style card list: name header, logo, human-readable info band. */
class CardAdapter(
    private val items: List<Card>,
    private val onClick: (Card) -> Unit,
) : RecyclerView.Adapter<CardAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.card_name)
        val info: TextView = v.findViewById(R.id.card_info)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_card, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val c = items[position]
        h.name.text = c.name
        h.info.text = "${c.typeLabel}\nUID ${c.uid}"
        h.itemView.setOnClickListener { onClick(c) }
    }
}
