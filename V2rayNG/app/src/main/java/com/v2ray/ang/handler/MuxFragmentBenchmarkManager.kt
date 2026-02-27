package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.NetworkType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Core benchmark logic for Mux & Fragment settings.
 * Tests multiple configurations and compares them to a baseline.
 */
object MuxFragmentBenchmarkManager {

    // ── Enums ──

    enum class BenchmarkScenarioType {
        BASELINE, MUX, FRAGMENT
    }

    // ── Data classes ──

    data class BenchmarkScenario(
        val type: BenchmarkScenarioType,
        val label: String,
        val muxEnabled: Boolean = false,
        val muxConcurrency: Int = 8,
        val muxXudpConcurrency: Int = 16,
        val muxXudpQuic: String = "reject",
        val fragmentEnabled: Boolean = false,
        val fragmentPackets: String = "tlshello",
        val fragmentLength: String = "50-100",
        val fragmentInterval: String = "10-20"
    )

    data class BenchmarkRoundResult(
        val delayMs: Long   // -1L for failure
    )

    data class BenchmarkScenarioResult(
        val scenario: BenchmarkScenario,
        val rounds: List<BenchmarkRoundResult>,
        val medianMs: Long,
        val averageMs: Long,
        val successRate: Int,   // 0–100
        var improvementPercent: Double = 0.0
    )

    data class BenchmarkSession(
        val id: String = UUID.randomUUID().toString(),
        val timestamp: Long = System.currentTimeMillis(),
        val serverGuid: String,
        val serverRemarks: String,
        val configType: String,
        val security: String?,
        val supportsMux: Boolean,
        val supportsFragment: Boolean,
        val results: List<BenchmarkScenarioResult>,
        val recommendedLabel: String?,
        val testUrl: String
    )

    // ── Capability detection ──

    fun supportsMux(profile: ProfileItem): Boolean {
        if (profile.configType != EConfigType.VMESS && profile.configType != EConfigType.VLESS) {
            return false
        }
        if (profile.network == NetworkType.XHTTP.type) {
            return false
        }
        return true
    }

    fun supportsFragment(profile: ProfileItem): Boolean {
        return profile.security == AppConfig.TLS || profile.security == AppConfig.REALITY
    }

    fun isVlessWithFlow(profile: ProfileItem): Boolean {
        return profile.configType == EConfigType.VLESS && !profile.flow.isNullOrEmpty()
    }

    // ── Scenario generation ──

    fun generateScenarios(profile: ProfileItem, quickMode: Boolean): List<BenchmarkScenario> {
        val scenarios = mutableListOf<BenchmarkScenario>()

        // Always baseline
        scenarios.add(BenchmarkScenario(
            type = BenchmarkScenarioType.BASELINE,
            label = "Baseline"
        ))

        // Mux scenarios
        if (supportsMux(profile)) {
            if (isVlessWithFlow(profile)) {
                // VLESS+flow: TCP forced -1, only vary XUDP
                val xudpValues = if (quickMode) listOf(8, 16) else listOf(8, 16, 32)
                for (xudp in xudpValues) {
                    scenarios.add(BenchmarkScenario(
                        type = BenchmarkScenarioType.MUX,
                        label = "Mux (TCP=-1, XUDP=$xudp)",
                        muxEnabled = true,
                        muxConcurrency = -1,
                        muxXudpConcurrency = xudp
                    ))
                }
            } else {
                if (quickMode) {
                    // Quick: 2 mux scenarios
                    scenarios.add(BenchmarkScenario(
                        type = BenchmarkScenarioType.MUX,
                        label = "Mux (TCP=8, XUDP=16)",
                        muxEnabled = true,
                        muxConcurrency = 8,
                        muxXudpConcurrency = 16
                    ))
                    scenarios.add(BenchmarkScenario(
                        type = BenchmarkScenarioType.MUX,
                        label = "Mux (TCP=1, XUDP=8)",
                        muxEnabled = true,
                        muxConcurrency = 1,
                        muxXudpConcurrency = 8
                    ))
                } else {
                    // Full: 8 mux combos
                    for (tcp in listOf(1, 4, 8, 16)) {
                        for (xudp in listOf(8, 16)) {
                            scenarios.add(BenchmarkScenario(
                                type = BenchmarkScenarioType.MUX,
                                label = "Mux (TCP=$tcp, XUDP=$xudp)",
                                muxEnabled = true,
                                muxConcurrency = tcp,
                                muxXudpConcurrency = xudp
                            ))
                        }
                    }
                }
            }
        }

        // Fragment scenarios
        if (supportsFragment(profile)) {
            val isReality = profile.security == AppConfig.REALITY

            if (quickMode) {
                // Quick: 3 fragment scenarios
                if (isReality) {
                    scenarios.add(BenchmarkScenario(
                        type = BenchmarkScenarioType.FRAGMENT,
                        label = "Fragment (1-3, L=50-100, I=10-20)",
                        fragmentEnabled = true, fragmentPackets = "1-3",
                        fragmentLength = "50-100", fragmentInterval = "10-20"
                    ))
                    scenarios.add(BenchmarkScenario(
                        type = BenchmarkScenarioType.FRAGMENT,
                        label = "Fragment (1-3, L=100-200, I=5-10)",
                        fragmentEnabled = true, fragmentPackets = "1-3",
                        fragmentLength = "100-200", fragmentInterval = "5-10"
                    ))
                    scenarios.add(BenchmarkScenario(
                        type = BenchmarkScenarioType.FRAGMENT,
                        label = "Fragment (1-5, L=10-50, I=10-20)",
                        fragmentEnabled = true, fragmentPackets = "1-5",
                        fragmentLength = "10-50", fragmentInterval = "10-20"
                    ))
                } else {
                    scenarios.add(BenchmarkScenario(
                        type = BenchmarkScenarioType.FRAGMENT,
                        label = "Fragment (tlshello, L=50-100, I=10-20)",
                        fragmentEnabled = true, fragmentPackets = "tlshello",
                        fragmentLength = "50-100", fragmentInterval = "10-20"
                    ))
                    scenarios.add(BenchmarkScenario(
                        type = BenchmarkScenarioType.FRAGMENT,
                        label = "Fragment (tlshello, L=100-200, I=5-10)",
                        fragmentEnabled = true, fragmentPackets = "tlshello",
                        fragmentLength = "100-200", fragmentInterval = "5-10"
                    ))
                    scenarios.add(BenchmarkScenario(
                        type = BenchmarkScenarioType.FRAGMENT,
                        label = "Fragment (tlshello, L=10-50, I=10-20)",
                        fragmentEnabled = true, fragmentPackets = "tlshello",
                        fragmentLength = "10-50", fragmentInterval = "10-20"
                    ))
                }
            } else {
                // Full mode
                val packetOptions = if (isReality) listOf("1-2", "1-3", "1-5") else listOf("tlshello")
                val lengthOptions = listOf("50-100", "100-200", "10-50")
                val intervalOptions = listOf("10-20", "5-10", "20-50")

                for (packets in packetOptions) {
                    for (length in lengthOptions) {
                        for (interval in intervalOptions) {
                            scenarios.add(BenchmarkScenario(
                                type = BenchmarkScenarioType.FRAGMENT,
                                label = "Fragment ($packets, L=$length, I=$interval)",
                                fragmentEnabled = true,
                                fragmentPackets = packets,
                                fragmentLength = length,
                                fragmentInterval = interval
                            ))
                        }
                    }
                }
            }
        }

        return scenarios
    }

    // ── Config injection (org.json) ──

    fun injectMuxIntoConfig(
        baseConfigJson: String,
        scenario: BenchmarkScenario,
        profile: ProfileItem? = null
    ): String? {
        return try {
            val json = JSONObject(baseConfigJson)
            val outbounds = json.getJSONArray("outbounds")
            if (outbounds.length() == 0) return null

            val proxy = outbounds.getJSONObject(0)
            val mux = JSONObject()
            mux.put("enabled", true)

            // VLESS with flow: force TCP concurrency to -1 (matching real app behavior)
            val concurrency = if (profile != null && isVlessWithFlow(profile)) {
                -1
            } else {
                scenario.muxConcurrency
            }
            mux.put("concurrency", concurrency)
            mux.put("xudpConcurrency", scenario.muxXudpConcurrency)
            mux.put("xudpProxyUDP443", scenario.muxXudpQuic)
            proxy.put("mux", mux)

            json.toString()
        } catch (e: Exception) {
            null
        }
    }

    fun injectFragmentIntoConfig(
        baseConfigJson: String,
        scenario: BenchmarkScenario,
        security: String?
    ): String? {
        return try {
            val json = JSONObject(baseConfigJson)
            val outbounds = json.getJSONArray("outbounds")
            if (outbounds.length() == 0) return null

            val proxy = outbounds.getJSONObject(0)

            // Adjust packets for TLS vs REALITY (matching V2rayConfigManager logic)
            var packets = scenario.fragmentPackets
            if (security == AppConfig.REALITY && packets == "tlshello") {
                packets = "1-3"
            } else if (security == AppConfig.TLS && packets != "tlshello") {
                packets = "tlshello"
            }

            // Set dialerProxy on proxy's streamSettings.sockopt
            val streamSettings = if (proxy.has("streamSettings"))
                proxy.getJSONObject("streamSettings") else JSONObject()
            val sockopt = JSONObject()
            sockopt.put("dialerProxy", "fragment")
            streamSettings.put("sockopt", sockopt)
            proxy.put("streamSettings", streamSettings)

            // Remove mux from proxy (fragment and mux are mutually exclusive)
            proxy.remove("mux")

            // Build fragment outbound
            val fragmentOutbound = JSONObject()
            fragmentOutbound.put("protocol", "freedom")
            fragmentOutbound.put("tag", "fragment")

            val fragSettings = JSONObject()
            val fragBean = JSONObject()
            fragBean.put("packets", packets)
            fragBean.put("length", scenario.fragmentLength)
            fragBean.put("interval", scenario.fragmentInterval)
            fragSettings.put("fragment", fragBean)

            val noises = JSONArray()
            val noise = JSONObject()
            noise.put("type", "rand")
            noise.put("packet", "10-20")
            noise.put("delay", "10-16")
            noises.put(noise)
            fragSettings.put("noises", noises)
            fragmentOutbound.put("settings", fragSettings)

            val fragStream = JSONObject()
            val fragSockopt = JSONObject()
            fragSockopt.put("TcpNoDelay", true)
            fragSockopt.put("mark", 255)
            fragStream.put("sockopt", fragSockopt)
            fragmentOutbound.put("streamSettings", fragStream)

            outbounds.put(fragmentOutbound)
            json.toString()
        } catch (e: Exception) {
            null
        }
    }

    // ── Test execution ──

    private const val TEST_TIMEOUT_MS = 15_000L

    suspend fun runSingleTest(configJson: String, testUrl: String): Long {
        return withTimeoutOrNull(TEST_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                V2RayNativeManager.measureOutboundDelay(configJson, testUrl)
            }
        } ?: -1L
    }

    // ── Statistics ──

    fun calculateMedian(values: List<Long>): Long {
        val valid = values.filter { it > 0 }.sorted()
        if (valid.isEmpty()) return -1L
        return if (valid.size % 2 == 0) {
            (valid[valid.size / 2 - 1] + valid[valid.size / 2]) / 2
        } else {
            valid[valid.size / 2]
        }
    }

    fun calculateAverage(values: List<Long>): Long {
        val valid = values.filter { it > 0 }
        if (valid.isEmpty()) return -1L
        return valid.average().toLong()
    }

    // ── Apply settings ──

    fun applyScenarioSettings(scenario: BenchmarkScenario) {
        when (scenario.type) {
            BenchmarkScenarioType.BASELINE -> {
                MmkvManager.encodeSettings(AppConfig.PREF_MUX_ENABLED, false)
                MmkvManager.encodeSettings(AppConfig.PREF_FRAGMENT_ENABLED, false)
            }
            BenchmarkScenarioType.MUX -> {
                MmkvManager.encodeSettings(AppConfig.PREF_MUX_ENABLED, true)
                MmkvManager.encodeSettings(AppConfig.PREF_FRAGMENT_ENABLED, false)
                MmkvManager.encodeSettings(AppConfig.PREF_MUX_CONCURRENCY, scenario.muxConcurrency.toString())
                MmkvManager.encodeSettings(AppConfig.PREF_MUX_XUDP_CONCURRENCY, scenario.muxXudpConcurrency.toString())
                MmkvManager.encodeSettings(AppConfig.PREF_MUX_XUDP_QUIC, scenario.muxXudpQuic)
            }
            BenchmarkScenarioType.FRAGMENT -> {
                MmkvManager.encodeSettings(AppConfig.PREF_FRAGMENT_ENABLED, true)
                MmkvManager.encodeSettings(AppConfig.PREF_MUX_ENABLED, false)
                MmkvManager.encodeSettings(AppConfig.PREF_FRAGMENT_PACKETS, scenario.fragmentPackets)
                MmkvManager.encodeSettings(AppConfig.PREF_FRAGMENT_LENGTH, scenario.fragmentLength)
                MmkvManager.encodeSettings(AppConfig.PREF_FRAGMENT_INTERVAL, scenario.fragmentInterval)
            }
        }
    }

    // ── Export ──

    fun exportResultsToText(session: BenchmarkSession): String {
        val sb = StringBuilder()
        sb.appendLine("=== Mux & Fragment Benchmark ===")
        sb.appendLine("Server: ${session.serverRemarks}")
        sb.appendLine("Protocol: ${session.configType}, Security: ${session.security ?: "none"}")
        sb.appendLine("Mux Support: ${session.supportsMux}, Fragment Support: ${session.supportsFragment}")
        sb.appendLine()
        for ((i, r) in session.results.withIndex()) {
            val status = if (r.medianMs > 0) "${r.medianMs}ms" else "FAILED"
            val imp = if (r.improvementPercent != 0.0) " (${String.format("%+.1f", r.improvementPercent)}%)" else ""
            sb.appendLine("#${i + 1} ${r.scenario.label}: $status$imp [${r.successRate}%]")
        }
        if (session.recommendedLabel != null) {
            sb.appendLine()
            sb.appendLine("Recommended: ${session.recommendedLabel}")
        }
        return sb.toString()
    }

    fun exportResultsToJson(session: BenchmarkSession): String {
        val json = JSONObject()
        json.put("server", session.serverRemarks)
        json.put("configType", session.configType)
        json.put("security", session.security ?: "none")
        json.put("supportsMux", session.supportsMux)
        json.put("supportsFragment", session.supportsFragment)
        json.put("timestamp", session.timestamp)
        val arr = JSONArray()
        for (r in session.results) {
            val obj = JSONObject()
            obj.put("label", r.scenario.label)
            obj.put("type", r.scenario.type.name)
            obj.put("medianMs", r.medianMs)
            obj.put("averageMs", r.averageMs)
            obj.put("successRate", r.successRate)
            obj.put("improvement", r.improvementPercent)
            val rounds = JSONArray()
            for (round in r.rounds) {
                rounds.put(round.delayMs)
            }
            obj.put("rounds", rounds)
            arr.put(obj)
        }
        json.put("results", arr)
        json.put("recommended", session.recommendedLabel ?: JSONObject.NULL)
        return json.toString(2)
    }
}
