package org.ghostwire

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import java.util.Locale

/**
 * SDR tab: detect a USB-OTG SDR front-end and hand off to a userspace libusb
 * SDR app. GhostWire never owns demod/RX — RTL/HackRF/Airspy RX runs in
 * userspace over libusb, no kernel driver and no radio stack in this app.
 * ponytail: external app owns the radio; we only detect + hand off.
 */
class SdrFragment : Fragment() {

    private data class Sdr(val vid: Int, val pid: Int, val label: String)

    private val known = listOf(
        Sdr(0x0bda, 0x2838, "RTL-SDR (RTL2832U)"),
        Sdr(0x0bda, 0x2832, "RTL-SDR (RTL2832U)"),
        Sdr(0x1d50, 0x6089, "HackRF One"),
        Sdr(0x1d50, 0x604b, "HackRF Jawbreaker"),
        Sdr(0x1d50, 0x60a1, "Airspy"),
        Sdr(0x03eb, 0x800c, "Airspy HF+"),
        Sdr(0x1d50, 0x6108, "LimeSDR Mini"),
        Sdr(0x0403, 0x6010, "bladeRF"),
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_sdr, container, false)

    override fun onViewCreated(view: View, s: Bundle?) {
        val out = view.findViewById<TextView>(R.id.sdr_out)
        view.findViewById<Button>(R.id.sdr_scan).setOnClickListener { out.text = scan() }
    }

    private fun scan(): String {
        val usb = requireContext().getSystemService(UsbManager::class.java)
        val devices = usb.deviceList.values
        if (devices.isEmpty()) return "No USB devices. Plug an SDR into the OTG port."
        return devices.joinToString("\n\n") { describe(it) }
    }

    private fun describe(d: UsbDevice): String {
        val match = known.firstOrNull { it.vid == d.vendorId && it.pid == d.productId }
        val id = String.format(Locale.US, "%04x:%04x", d.vendorId, d.productId)
        return if (match != null) {
            "$id  ${match.label}\n  -> SDR detected. Hand off to a libusb SDR app for RX."
        } else {
            "$id  ${d.productName ?: "unknown"} (not a known SDR)"
        }
    }
}
