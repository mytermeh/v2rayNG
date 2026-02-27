package com.v2ray.ang.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.databinding.ItemBenchmarkResultBinding
import com.v2ray.ang.handler.MuxFragmentBenchmarkManager
import com.v2ray.ang.handler.MuxFragmentBenchmarkManager.BenchmarkScenarioResult
import com.v2ray.ang.handler.MuxFragmentBenchmarkManager.BenchmarkScenarioType

class BenchmarkResultsAdapter(
    private val results: MutableList<BenchmarkScenarioResult>,
    private val onApplyClick: ((BenchmarkScenarioResult) -> Unit)? = null
) : RecyclerView.Adapter<BenchmarkResultsAdapter.ViewHolder>() {

    var baselineMedianMs: Long = -1L

    class ViewHolder(val binding: ItemBenchmarkResultBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemBenchmarkResultBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = results[position]
        val b = holder.binding

        // Rank
        b.tvRank.text = "#${position + 1}"
        b.tvRank.setTextColor(
            when (position) {
                0 -> Color.parseColor("#4CAF50")
                1 -> Color.parseColor("#2196F3")
                2 -> Color.parseColor("#FF9800")
                else -> Color.parseColor("#999999")
            }
        )

        // Type badge
        when (item.scenario.type) {
            BenchmarkScenarioType.BASELINE -> {
                b.tvTypeBadge.text = "BASE"
                b.tvTypeBadge.background.setTint(Color.parseColor("#9E9E9E"))
            }
            BenchmarkScenarioType.MUX -> {
                b.tvTypeBadge.text = "MUX"
                b.tvTypeBadge.background.setTint(Color.parseColor("#2196F3"))
            }
            BenchmarkScenarioType.FRAGMENT -> {
                b.tvTypeBadge.text = "FRAG"
                b.tvTypeBadge.background.setTint(Color.parseColor("#7C4DFF"))
            }
        }

        // Scenario label
        b.tvScenarioLabel.text = item.scenario.label

        // Round details
        val roundsText = item.rounds.mapIndexed { i, r ->
            "R${i + 1}: ${if (r.delayMs > 0) "${r.delayMs}ms" else "fail"}"
        }.joinToString("  ")
        // For non-baseline items, show baseline comparison
        val baselineRef = if (item.scenario.type != BenchmarkScenarioType.BASELINE
            && baselineMedianMs > 0 && item.medianMs > 0) {
            "  |  Base: ${baselineMedianMs}ms → ${item.medianMs}ms"
        } else ""
        b.tvRoundDetails.text = "$roundsText$baselineRef"

        // Median delay
        if (item.medianMs > 0) {
            b.tvMedian.text = "${item.medianMs} ms"
            b.tvMedian.setTextColor(getDelayColor(item.medianMs))
        } else {
            b.tvMedian.text = "FAIL"
            b.tvMedian.setTextColor(Color.RED)
        }

        // Improvement %
        if (item.scenario.type == BenchmarkScenarioType.BASELINE) {
            b.tvImprovement.text = "baseline"
            b.tvImprovement.setTextColor(Color.parseColor("#999999"))
        } else if (item.medianMs > 0) {
            val imp = item.improvementPercent
            b.tvImprovement.text = String.format("%+.1f%%", imp)
            b.tvImprovement.setTextColor(
                if (imp > 0) Color.parseColor("#4CAF50")
                else if (imp < -5) Color.parseColor("#F44336")
                else Color.parseColor("#FF9800")
            )
        } else {
            b.tvImprovement.text = ""
        }

        // Success rate
        val total = item.rounds.size
        val success = item.rounds.count { it.delayMs > 0 }
        b.tvSuccessRate.text = "$success/$total"

        // Long click to apply
        holder.itemView.setOnLongClickListener {
            if (item.medianMs > 0) onApplyClick?.invoke(item)
            true
        }
    }

    override fun getItemCount() = results.size

    private fun getDelayColor(ms: Long): Int {
        return when {
            ms <= 0 -> Color.parseColor("#999999")
            ms < 300 -> Color.parseColor("#4CAF50")
            ms < 800 -> Color.parseColor("#FF9800")
            else -> Color.parseColor("#F44336")
        }
    }
}
