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

/**
 * Run a shell command as root via `su` (KernelSU-Next on this device, u:r:ksu).
 *
 * DIAG lives behind a vendor-scope SELinux type (vendor_diag_device) that an
 * app domain can never be granted — appdomain carries blanket neverallows on
 * device-node chr_file access, and the type isn't even visible to system_ext
 * scope. So, like SnoopSnitch/QCSuper, GhostWire reaches /dev/diag through root
 * rather than its own domain.
 */
object Root {
    data class Result(val ok: Boolean, val out: String)

    fun run(cmd: String): Result = try {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        val code = p.waitFor()
        Result(code == 0, out.ifBlank { "(no output)" })
    } catch (e: Exception) {
        Result(false, "su failed (root not granted?): ${e.message}")
    }
}
