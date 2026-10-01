package org.ghostwire

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * DIAG tab: inspect and raw-capture the Qualcomm DIAG surface (/dev/diag) via
 * root. This is the on-device status/control panel for the DIAG track — full
 * signaling decode (RRC/NAS → pcap) is QCSuper/SnoopSnitch territory and is not
 * reimplemented here; the raw dump this produces is parseable by those tools.
 */
class DiagFragment : Fragment() {

    private lateinit var out: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_diag, container, false)

    override fun onViewCreated(view: View, s: Bundle?) {
        out = view.findViewById(R.id.diag_out)
        action(view, R.id.diag_status) { diagStatus() }
        action(view, R.id.diag_capture) { rawCapture() }
    }

    private fun action(view: View, id: Int, block: () -> String) {
        val b = view.findViewById<Button>(id)
        b.setOnClickListener { run(b.text.toString(), block) }
    }

    private fun run(label: String, block: () -> String) {
        out.text = "$label …"
        Thread {
            val result = try {
                block()
            } catch (e: Exception) {
                "error: ${e.message}"
            }
            out.post { out.text = result }
        }.start()
    }

    /** Node presence + SELinux label, diag daemons, recent kernel diag activity. */
    private fun diagStatus(): String {
        val cmd = "ls -laZ /dev/diag 2>&1; echo '--- daemons ---'; " +
            "ps -A 2>/dev/null | grep -iE 'diag-router|devicediagnostics|diag_mdlog' | grep -v grep; " +
            "echo '--- kernel ---'; dmesg 2>/dev/null | grep -i diag | tail -n 15"
        return Root.run(cmd).out
    }

    /** 5-second raw read of /dev/diag to a file. Not decoded — hand to QCSuper. */
    private fun rawCapture(): String {
        val cmd = "d=/sdcard/ghostwire; mkdir -p \$d; f=\$d/diag-\$(date +%s).bin; " +
            "timeout 5 cat /dev/diag > \$f 2>/dev/null; echo \"saved \$f\"; ls -l \$f 2>&1"
        return Root.run(cmd).out +
            "\n\nRaw DIAG stream, not decoded. Parse with QCSuper " +
            "(qcsuper --dlf-read <file>) or SnoopSnitch."
    }
}
