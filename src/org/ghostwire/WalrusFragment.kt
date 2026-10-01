package org.ghostwire

import android.nfc.NfcAdapter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * Contactless-card (RFID/NFC) lab — placeholder, to be built on Walrus
 * (TeamWalrus, GPLv3). For now it reports NFC availability and launches the
 * standalone Walrus app when installed; the in-app card tooling comes later.
 */
class WalrusFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_walrus, container, false)

    override fun onViewCreated(view: View, s: Bundle?) {
        val status = view.findViewById<TextView>(R.id.walrus_status)
        val nfc = NfcAdapter.getDefaultAdapter(requireContext())
        status.text = buildString {
            append("NFC: ").append(
                when {
                    nfc == null -> "not available"
                    !nfc.isEnabled -> "present but disabled"
                    else -> "ready"
                }
            ).append('\n')
            val installed = launchIntent() != null
            append("Walrus app: ").append(if (installed) "installed" else "not installed")
        }

        view.findViewById<Button>(R.id.walrus_launch).setOnClickListener {
            val intent = launchIntent()
            if (intent != null) {
                startActivity(intent)
            } else {
                status.append("\n\nWalrus not installed. In-app contactless lab is in progress.")
            }
        }
    }

    private fun launchIntent() =
        requireContext().packageManager.getLaunchIntentForPackage(WALRUS_PKG)

    companion object {
        private const val WALRUS_PKG = "com.bugfuzz.android.projectwalrus"
    }
}
