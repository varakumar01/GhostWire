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

/** Offline OSINT decode of SIM identifiers (no network). */
object SimOsint {

    // MCC -> country. Common subset; unknown MCCs are reported as the raw code.
    private val MCC = mapOf(
        "202" to "Greece", "204" to "Netherlands", "206" to "Belgium",
        "208" to "France", "212" to "Monaco", "213" to "Andorra",
        "214" to "Spain", "216" to "Hungary", "218" to "Bosnia",
        "219" to "Croatia", "222" to "Italy", "226" to "Romania",
        "228" to "Switzerland", "230" to "Czechia", "231" to "Slovakia",
        "232" to "Austria", "234" to "United Kingdom", "235" to "United Kingdom",
        "238" to "Denmark", "240" to "Sweden", "242" to "Norway",
        "244" to "Finland", "246" to "Lithuania", "247" to "Latvia",
        "248" to "Estonia", "250" to "Russia", "255" to "Ukraine",
        "260" to "Poland", "262" to "Germany", "266" to "Gibraltar",
        "268" to "Portugal", "270" to "Luxembourg", "272" to "Ireland",
        "274" to "Iceland", "276" to "Albania", "278" to "Malta",
        "280" to "Cyprus", "282" to "Georgia", "283" to "Armenia",
        "284" to "Bulgaria", "286" to "Turkey", "288" to "Faroe Islands",
        "293" to "Slovenia", "294" to "North Macedonia", "295" to "Liechtenstein",
        "297" to "Montenegro", "302" to "Canada", "308" to "Saint Pierre",
        "310" to "USA", "311" to "USA", "312" to "USA", "313" to "USA",
        "314" to "USA", "315" to "USA", "316" to "USA", "330" to "Puerto Rico",
        "334" to "Mexico", "338" to "Jamaica", "340" to "French Caribbean",
        "400" to "Azerbaijan", "401" to "Kazakhstan", "404" to "India",
        "405" to "India", "406" to "India", "410" to "Pakistan",
        "412" to "Afghanistan", "413" to "Sri Lanka", "414" to "Myanmar",
        "415" to "Lebanon", "416" to "Jordan", "417" to "Syria",
        "418" to "Iraq", "419" to "Kuwait", "420" to "Saudi Arabia",
        "424" to "UAE", "425" to "Israel", "426" to "Bahrain",
        "427" to "Qatar", "432" to "Iran", "434" to "Uzbekistan",
        "440" to "Japan", "441" to "Japan", "450" to "South Korea",
        "452" to "Vietnam", "454" to "Hong Kong", "455" to "Macau",
        "456" to "Cambodia", "457" to "Laos", "460" to "China",
        "466" to "Taiwan", "470" to "Bangladesh", "472" to "Maldives",
        "502" to "Malaysia", "505" to "Australia", "510" to "Indonesia",
        "515" to "Philippines", "520" to "Thailand", "525" to "Singapore",
        "530" to "New Zealand", "602" to "Egypt", "604" to "Morocco",
        "605" to "Tunisia", "607" to "Gambia", "621" to "Nigeria",
        "639" to "Kenya", "641" to "Uganda", "650" to "Malawi",
        "655" to "South Africa", "710" to "Nicaragua", "716" to "Peru",
        "722" to "Argentina", "724" to "Brazil", "730" to "Chile",
        "732" to "Colombia", "734" to "Venezuela", "740" to "Ecuador",
    )

    fun country(mcc: String?): String =
        if (mcc == null) "n/a" else MCC[mcc] ?: "MCC $mcc (unknown)"

    fun decodeImsi(imsi: String?): String {
        if (imsi.isNullOrBlank() || imsi.length < 5) return "IMSI: (unavailable — needs READ_PRIVILEGED_PHONE_STATE)"
        val mcc = imsi.substring(0, 3)
        val mnc2 = imsi.substring(3, 5)
        val mnc3 = if (imsi.length >= 6) imsi.substring(3, 6) else mnc2
        val msin = if (imsi.length > 5) imsi.substring(5) else ""
        return buildString {
            append("IMSI:    $imsi\n")
            append("  MCC:   $mcc  (${country(mcc)})\n")
            append("  MNC:   $mnc2  (or $mnc3 if 3-digit)\n")
            append("  MSIN:  $msin")
        }
    }

    fun decodeIccid(iccid: String?): String {
        if (iccid.isNullOrBlank()) return "ICCID: (unavailable)"
        val digits = iccid.trimEnd('F', 'f').filter { it.isDigit() }
        if (digits.length < 10) return "ICCID: $iccid (too short to decode)"
        val mii = digits.substring(0, 2)
        val luhn = if (luhnValid(digits)) "valid" else "INVALID"
        return buildString {
            append("ICCID:   $iccid\n")
            append("  MII:   $mii  ${if (mii == "89") "(telecom)" else "(non-telecom)"}\n")
            append("  Issuer prefix: ${digits.substring(2, minOf(8, digits.length))}\n")
            append("  Luhn:  $luhn")
        }
    }

    private fun luhnValid(digits: String): Boolean {
        var sum = 0
        var alt = false
        for (i in digits.indices.reversed()) {
            var d = digits[i] - '0'
            if (alt) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            alt = !alt
        }
        return sum % 10 == 0
    }
}
