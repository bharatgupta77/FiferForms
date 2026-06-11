package com.fifer.forms

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.fifer.forms.databinding.ActivityMainBinding
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        val loadingView = TextView(this).apply { text = "Loading domains..." }
        binding.domainsContainer.addView(loadingView)

        lifecycleScope.launch {
            try {
                val domains = RetrofitClient.api.getDomains()
                binding.domainsContainer.removeAllViews()
                for (domain in domains) {
                    binding.domainsContainer.addView(makeDomainCard(domain))
                }
            } catch (e: Exception) {
                binding.domainsContainer.removeAllViews()
                binding.domainsContainer.addView(TextView(this@MainActivity).apply {
                    text = "Error: ${e.message}"
                })
            }
        }
    }

    private fun formatDomainName(domain: String): String =
        domain.replace('_', ' ')
            .split(' ')
            .joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }

    private fun themeColor(attr: Int): Int {
        val ta = obtainStyledAttributes(intArrayOf(attr))
        val color = ta.getColor(0, 0)
        ta.recycle()
        return color
    }

    private fun makeDomainCard(domain: String): MaterialCardView {
        val density = resources.displayMetrics.density
        val dp4 = (4 * density).toInt()
        val dp12 = (12 * density).toInt()
        val dp16 = (16 * density).toInt()

        val card = MaterialCardView(this).apply {
            radius = 12 * density
            cardElevation = 2 * density
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp12 }
            setOnClickListener {
                startActivity(
                    Intent(this@MainActivity, FormActivity::class.java)
                        .putExtra("domain", domain)
                )
            }
        }

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp16, dp16, dp16, dp16)
        }

        inner.addView(TextView(this).apply {
            text = formatDomainName(domain)
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(themeColor(android.R.attr.textColorPrimary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp4 }
        })

        inner.addView(TextView(this).apply {
            text = "Tap to capture a record"
            textSize = 13f
            setTextColor(themeColor(android.R.attr.textColorSecondary))
        })

        card.addView(inner)
        return card
    }
}
