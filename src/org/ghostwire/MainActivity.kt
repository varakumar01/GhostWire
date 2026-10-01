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
