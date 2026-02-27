package com.v2ray.ang.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityMuxFragmentBenchmarkBinding
import com.v2ray.ang.extension.toast
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.MuxFragmentBenchmarkHistoryManager
import com.v2ray.ang.handler.MuxFragmentBenchmarkManager
import com.v2ray.ang.handler.MuxFragmentBenchmarkManager.BenchmarkScenarioResult
import com.v2ray.ang.handler.MuxFragmentBenchmarkManager.BenchmarkScenarioType
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.V2rayConfigManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MuxFragmentBenchmarkActivity : BaseActivity() {

    private val binding by lazy { ActivityMuxFragmentBenchmarkBinding.inflate(layoutInflater) }

    private val resultsList = mutableListOf<BenchmarkScenarioResult>()
    private lateinit var adapter: BenchmarkResultsAdapter
    private var benchmarkJob: Job? = null
    private var quickMode = true
    private var lastSession: MuxFragmentBenchmarkManager.BenchmarkSession? = null

    // ──────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(
            binding.root,
            showHomeAsUp = true,
            title = getString(R.string.title_mux_fragment_benchmark)
        )

        adapter = BenchmarkResultsAdapter(resultsList) { result ->
            applyResult(result)
        }
        binding.recyclerResults.layoutManager = LinearLayoutManager(this)
        binding.recyclerResults.adapter = adapter

        setupModeChips()
        setupButtons()
        detectCapabilities()
    }

    override fun onDestroy() {
        benchmarkJob?.cancel()
        super.onDestroy()
    }

    // ──────────────────────────────────────────────
    // Setup
    // ──────────────────────────────────────────────

    private fun setupModeChips() {
        binding.chipQuick.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                quickMode = true
                binding.tvModeDesc.text = getString(R.string.benchmark_quick_desc)
            }
        }
        binding.chipFull.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                quickMode = false
                binding.tvModeDesc.text = getString(R.string.benchmark_full_desc)
            }
        }
    }

    private fun setupButtons() {
        binding.btnStartBenchmark.setOnClickListener { startBenchmark() }
        binding.btnCancel.setOnClickListener { cancelBenchmark() }
        binding.btnHistory.setOnClickListener { showHistory() }
        binding.btnExport.setOnClickListener { exportResults() }
        binding.btnApplyRecommendation.setOnClickListener {
            val best = lastSession?.results
                ?.filter { it.medianMs > 0 }
                ?.minByOrNull { it.medianMs }
            if (best != null) applyResult(best)
        }
    }

    // ──────────────────────────────────────────────
    // Capability detection
    // ──────────────────────────────────────────────

    private fun detectCapabilities() {
        val guid = MmkvManager.getSelectServer()
        if (guid.isNullOrEmpty()) {
            binding.tvServerName.text = getString(R.string.benchmark_no_server)
            binding.btnStartBenchmark.isEnabled = false
            return
        }
        val profile = MmkvManager.decodeServerConfig(guid)
        if (profile == null) {
            binding.tvServerName.text = getString(R.string.benchmark_no_server)
            binding.btnStartBenchmark.isEnabled = false
            return
        }

        val sMux = MuxFragmentBenchmarkManager.supportsMux(profile)
        val sFrag = MuxFragmentBenchmarkManager.supportsFragment(profile)

        // Server info
        binding.tvServerName.text = profile.remarks
        binding.tvProtocolInfo.text = getString(R.string.benchmark_config_type, profile.configType.name)
        binding.tvSecurityInfo.text = getString(R.string.benchmark_security, profile.security ?: "none")

        // Capability indicators
        binding.tvMuxSupport.text = if (sMux) getString(R.string.benchmark_yes) else getString(R.string.benchmark_no)
        binding.tvMuxSupport.setTextColor(if (sMux) Color.parseColor("#4CAF50") else Color.parseColor("#F44336"))
        binding.tvFragmentSupport.text = if (sFrag) getString(R.string.benchmark_yes) else getString(R.string.benchmark_no)
        binding.tvFragmentSupport.setTextColor(if (sFrag) Color.parseColor("#4CAF50") else Color.parseColor("#F44336"))

        // Current settings
        val muxEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_MUX_ENABLED, false)
        val muxConc = MmkvManager.decodeSettingsString(AppConfig.PREF_MUX_CONCURRENCY) ?: "8"
        val muxXudp = MmkvManager.decodeSettingsString(AppConfig.PREF_MUX_XUDP_CONCURRENCY) ?: "8"
        val fragEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_FRAGMENT_ENABLED, false)
        val fragPackets = MmkvManager.decodeSettingsString(AppConfig.PREF_FRAGMENT_PACKETS) ?: "tlshello"
        val fragLength = MmkvManager.decodeSettingsString(AppConfig.PREF_FRAGMENT_LENGTH) ?: "50-100"
        val fragInterval = MmkvManager.decodeSettingsString(AppConfig.PREF_FRAGMENT_INTERVAL) ?: "10-20"

        binding.tvCurrentMux.text = getString(
            R.string.benchmark_current_mux,
            if (muxEnabled) getString(R.string.benchmark_enabled) else getString(R.string.benchmark_disabled),
            muxConc, muxXudp
        )
        binding.tvCurrentFragment.text = getString(
            R.string.benchmark_current_fragment,
            if (fragEnabled) getString(R.string.benchmark_enabled) else getString(R.string.benchmark_disabled),
            fragPackets, fragLength, fragInterval
        )

        // VLESS flow note
        if (MuxFragmentBenchmarkManager.isVlessWithFlow(profile)) {
            binding.tvVlessFlowNote.visibility = View.VISIBLE
        }

        // Unsupported
        if (!sMux && !sFrag) {
            binding.tvUnsupported.visibility = View.VISIBLE
            binding.btnStartBenchmark.isEnabled = false
        }
    }

    // ──────────────────────────────────────────────
    // Benchmark
    // ──────────────────────────────────────────────

    private fun startBenchmark() {
        val guid = MmkvManager.getSelectServer() ?: return
        val profile = MmkvManager.decodeServerConfig(guid) ?: return
        val testUrl = SettingsManager.getDelayTestUrl()
        val rounds = 5

        val scenarios = MuxFragmentBenchmarkManager.generateScenarios(profile, quickMode)

        resultsList.clear()
        adapter.notifyDataSetChanged()
        binding.cardRecommendation.visibility = View.GONE
        binding.btnStartBenchmark.isEnabled = false
        binding.btnCancel.visibility = View.VISIBLE
        binding.tvProgress.visibility = View.VISIBLE
        binding.benchmarkProgressBar.visibility = View.VISIBLE
        binding.benchmarkProgressBar.max = scenarios.size * (rounds + 1) // +1 for warm-up
        binding.benchmarkProgressBar.progress = 0
        setChipsEnabled(false)

        benchmarkJob = lifecycleScope.launch {
            // Get base config (mux stripped — perfect baseline)
            val baseConfigResult = withContext(Dispatchers.IO) {
                V2rayConfigManager.getV2rayConfig4Speedtest(this@MuxFragmentBenchmarkActivity, guid)
            }
            if (!baseConfigResult.status) {
                withContext(Dispatchers.Main) {
                    toast(getString(R.string.benchmark_failed))
                    resetUI()
                }
                return@launch
            }
            val baseJson = baseConfigResult.content

            val allResults = mutableListOf<BenchmarkScenarioResult>()
            var totalProgress = 0

            for ((index, scenario) in scenarios.withIndex()) {
                if (!isActive) break

                withContext(Dispatchers.Main) {
                    binding.tvProgress.text = getString(
                        R.string.benchmark_progress, index + 1, scenarios.size, scenario.label
                    )
                }

                // Build config for this scenario
                val configJson = when (scenario.type) {
                    BenchmarkScenarioType.BASELINE -> baseJson
                    BenchmarkScenarioType.MUX ->
                        MuxFragmentBenchmarkManager.injectMuxIntoConfig(baseJson, scenario, profile)
                    BenchmarkScenarioType.FRAGMENT ->
                        MuxFragmentBenchmarkManager.injectFragmentIntoConfig(baseJson, scenario, profile.security)
                }
                if (configJson == null) {
                    totalProgress += rounds + 1
                    continue
                }

                // Warm-up round (discarded — eliminates cold-start bias)
                MuxFragmentBenchmarkManager.runSingleTest(configJson, testUrl)
                totalProgress++
                withContext(Dispatchers.Main) {
                    binding.benchmarkProgressBar.progress = totalProgress
                }
                if (!isActive) break

                // Real measurement rounds
                val roundResults = mutableListOf<MuxFragmentBenchmarkManager.BenchmarkRoundResult>()
                for (r in 1..rounds) {
                    if (!isActive) break
                    val delay = MuxFragmentBenchmarkManager.runSingleTest(configJson, testUrl)
                    roundResults.add(MuxFragmentBenchmarkManager.BenchmarkRoundResult(delay))
                    totalProgress++
                    withContext(Dispatchers.Main) {
                        binding.benchmarkProgressBar.progress = totalProgress
                    }
                }

                val delays = roundResults.map { it.delayMs }
                val median = MuxFragmentBenchmarkManager.calculateMedian(delays)
                val average = MuxFragmentBenchmarkManager.calculateAverage(delays)
                val successRate = (roundResults.count { it.delayMs > 0 } * 100) / rounds

                allResults.add(BenchmarkScenarioResult(
                    scenario = scenario,
                    rounds = roundResults,
                    medianMs = median,
                    averageMs = average,
                    successRate = successRate
                ))

                // Update UI incrementally
                withContext(Dispatchers.Main) {
                    resultsList.clear()
                    resultsList.addAll(allResults)
                    adapter.notifyDataSetChanged()
                }
            }

            // Calculate improvement % vs baseline
            val baseline = allResults.firstOrNull { it.scenario.type == BenchmarkScenarioType.BASELINE }
            if (baseline != null && baseline.medianMs > 0) {
                for (r in allResults) {
                    if (r.medianMs > 0 && r.scenario.type != BenchmarkScenarioType.BASELINE) {
                        r.improvementPercent =
                            ((baseline.medianMs - r.medianMs).toDouble() / baseline.medianMs) * 100.0
                    }
                }
            }

            // Sort by median (ascending), baseline first if tie
            val sorted = allResults.sortedWith(compareBy<BenchmarkScenarioResult> {
                if (it.medianMs <= 0) Long.MAX_VALUE else it.medianMs
            }.thenBy { it.scenario.type.ordinal })

            val best = sorted.firstOrNull { it.medianMs > 0 }

            // Build session
            val session = MuxFragmentBenchmarkManager.BenchmarkSession(
                serverGuid = guid,
                serverRemarks = profile.remarks,
                configType = profile.configType.name,
                security = profile.security,
                supportsMux = MuxFragmentBenchmarkManager.supportsMux(profile),
                supportsFragment = MuxFragmentBenchmarkManager.supportsFragment(profile),
                results = sorted,
                recommendedLabel = best?.scenario?.label,
                testUrl = testUrl
            )
            MuxFragmentBenchmarkHistoryManager.saveSession(session)
            lastSession = session

            withContext(Dispatchers.Main) {
                // Set baseline reference for adapter comparison display
                adapter.baselineMedianMs = baseline?.medianMs ?: -1L

                resultsList.clear()
                resultsList.addAll(sorted)
                adapter.notifyDataSetChanged()

                // Show recommendation
                if (best != null) {
                    binding.cardRecommendation.visibility = View.VISIBLE
                    if (best.scenario.type == BenchmarkScenarioType.BASELINE) {
                        binding.tvRecommendation.text = getString(R.string.benchmark_recommendation_baseline)
                        binding.btnApplyRecommendation.visibility = View.GONE
                    } else {
                        binding.tvRecommendation.text = getString(
                            R.string.benchmark_recommendation_text,
                            best.scenario.label,
                            best.medianMs,
                            best.improvementPercent
                        )
                        binding.btnApplyRecommendation.visibility = View.VISIBLE
                    }
                }

                resetUI()
            }
        }
    }

    private fun cancelBenchmark() {
        benchmarkJob?.cancel()
        resetUI()
    }

    private fun resetUI() {
        binding.btnStartBenchmark.isEnabled = true
        binding.btnCancel.visibility = View.GONE
        binding.tvProgress.visibility = View.GONE
        binding.benchmarkProgressBar.visibility = View.GONE
        setChipsEnabled(true)
    }

    private fun setChipsEnabled(enabled: Boolean) {
        binding.chipQuick.isEnabled = enabled
        binding.chipFull.isEnabled = enabled
    }

    // ──────────────────────────────────────────────
    // Apply
    // ──────────────────────────────────────────────

    private fun applyResult(result: BenchmarkScenarioResult) {
        AlertDialog.Builder(this)
            .setTitle(R.string.benchmark_apply_title)
            .setMessage(getString(R.string.benchmark_apply_message, result.scenario.label))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                MuxFragmentBenchmarkManager.applyScenarioSettings(result.scenario)
                toast(getString(R.string.benchmark_settings_applied))
                // Refresh current settings display
                detectCapabilities()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ──────────────────────────────────────────────
    // History
    // ──────────────────────────────────────────────

    private fun showHistory() {
        val history = MuxFragmentBenchmarkHistoryManager.getHistory()
        if (history.isEmpty()) {
            toast(getString(R.string.benchmark_history_empty))
            return
        }

        val dateFormat = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
        val items = history.map { session ->
            val date = dateFormat.format(Date(session.timestamp))
            val best = session.recommendedLabel ?: "—"
            "$date | ${session.serverRemarks} → $best"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.benchmark_history_title)
            .setItems(items, null)
            .setNeutralButton(R.string.benchmark_history_clear) { _, _ ->
                MuxFragmentBenchmarkHistoryManager.clearHistory()
                toast(getString(R.string.benchmark_history_cleared))
            }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ──────────────────────────────────────────────
    // Export
    // ──────────────────────────────────────────────

    private fun exportResults() {
        val session = lastSession
        if (session == null) {
            toast(getString(R.string.benchmark_export_no_results))
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.benchmark_export_title)
            .setItems(arrayOf(
                getString(R.string.benchmark_export_text),
                getString(R.string.benchmark_export_json),
                getString(R.string.benchmark_export_share)
            )) { _, which ->
                when (which) {
                    0 -> {
                        val text = MuxFragmentBenchmarkManager.exportResultsToText(session)
                        copyToClipboard(text)
                    }
                    1 -> {
                        val json = MuxFragmentBenchmarkManager.exportResultsToJson(session)
                        copyToClipboard(json)
                    }
                    2 -> {
                        val text = MuxFragmentBenchmarkManager.exportResultsToText(session)
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        startActivity(Intent.createChooser(intent, getString(R.string.benchmark_export_share)))
                    }
                }
            }
            .show()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("benchmark", text))
        toast(getString(R.string.benchmark_export_copied))
    }
}
