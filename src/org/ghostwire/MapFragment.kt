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

import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import java.io.File

/**
 * Dark OSM map (osmdroid) showing the last recorded walk route as an accent
 * polyline with distance/points/signal stats — the maps.webp screen. The dark
 * look comes from the built-in INVERT_COLORS tile filter over standard MAPNIK
 * tiles, so no third-party tile server is wired in. Route data is written by
 * [WalkFragment] via [RouteStore]; tiles need a network connection.
 */
class MapFragment : Fragment() {

    private lateinit var map: MapView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        // osmdroid needs a user agent (OSM blocks blank ones) and a writable
        // cache; the app-internal cache dir avoids any storage permission.
        val cfg = Configuration.getInstance()
        cfg.userAgentValue = requireContext().packageName
        cfg.osmdroidBasePath = File(requireContext().cacheDir, "osmdroid")
        cfg.osmdroidTileCache = File(cfg.osmdroidBasePath, "tiles")
        return inflater.inflate(R.layout.fragment_map, container, false)
    }

    override fun onViewCreated(view: View, s: Bundle?) {
        map = view.findViewById(R.id.map)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS) // dark map
        map.controller.setZoom(16.0)

        val status = view.findViewById<TextView>(R.id.map_status)
        val samples = RouteStore(requireContext()).load()
        if (samples.isNotEmpty()) {
            drawRoute(samples)
            status.text = "Last walk route"
            fillStats(view, samples)
        } else {
            status.text = "Walk a route (Walk tab) to see it here"
            centerOnLastLocation()
        }
    }

    private fun drawRoute(samples: List<Walk.Sample>) {
        val pts = samples.map { GeoPoint(it.lat, it.lon) }
        val accent = ContextCompat.getColor(requireContext(), R.color.gw_accent)
        val line = Polyline().apply {
            setPoints(pts)
            outlinePaint.color = accent
            outlinePaint.strokeWidth = 10f
        }
        map.overlays.add(line)
        // Fit the route once the map has been laid out.
        map.post { map.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts).increaseByScale(1.4f), false) }
    }

    private fun fillStats(view: View, samples: List<Walk.Sample>) {
        var dist = 0.0
        for (i in 1 until samples.size)
            dist += Geo.haversineMeters(samples[i - 1].lat, samples[i - 1].lon, samples[i].lat, samples[i].lon)
        view.findViewById<TextView>(R.id.stat_distance).text =
            if (dist >= 1000) "%.1f km".format(dist / 1000) else "${dist.toInt()} m"
        view.findViewById<TextView>(R.id.stat_points).text = samples.size.toString()
        view.findViewById<TextView>(R.id.stat_dbm).text = "${samples.last().dbm}"
    }

    private fun centerOnLastLocation() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val lm = requireContext().getSystemService(LocationManager::class.java)
        val loc = try {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: SecurityException) {
            null
        } ?: return
        map.controller.setCenter(GeoPoint(loc.latitude, loc.longitude))
    }

    override fun onResume() {
        super.onResume()
        if (this::map.isInitialized) map.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (this::map.isInitialized) map.onPause()
    }
}
