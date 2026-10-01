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
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

/**
 * GhostWire shell: a tabbed host. Each capability is one entry in [tabs] — a
 * title and a fragment factory. Adding a capability = adding one line here plus
 * its fragment, nothing else.
 */
class MainActivity : AppCompatActivity() {

    private val tabs: List<Pair<String, () -> Fragment>> = listOf(
        "SIM" to { SimFragment() },
        "Recon" to { ReconFragment() },
        "Guard" to { GuardFragment() },
        "Walk" to { WalkFragment() },
        "DIAG" to { DiagFragment() },
        "SDR" to { SdrFragment() },
        "Walrus" to { WalrusFragment() },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val pager = findViewById<ViewPager2>(R.id.pager)
        pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = tabs.size
            override fun createFragment(position: Int) = tabs[position].second()
        }
        val tabLayout = findViewById<TabLayout>(R.id.tabs)
        TabLayoutMediator(tabLayout, pager) { tab, pos -> tab.text = tabs[pos].first }.attach()
    }
}
