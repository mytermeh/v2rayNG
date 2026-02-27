package com.v2ray.ang.handler

import com.tencent.mmkv.MMKV
import org.json.JSONArray
import org.json.JSONObject

/**
 * MMKV-based persistence for Mux & Fragment benchmark sessions.
 */
object MuxFragmentBenchmarkHistoryManager {

    private const val ID_BENCHMARK = "MUX_FRAG_BENCHMARK"
    private const val KEY_HISTORY = "benchmark_history"
    private const val MAX_HISTORY_SESSIONS = 15

    private val storage by lazy {
        MMKV.mmkvWithID(ID_BENCHMARK, MMKV.MULTI_PROCESS_MODE)
    }

    fun saveSession(session: MuxFragmentBenchmarkManager.BenchmarkSession) {
        val history = getRawHistory()
        val sessionJson = JSONObject().apply {
            put("id", session.id)
            put("timestamp", session.timestamp)
            put("serverGuid", session.serverGuid)
            put("serverRemarks", session.serverRemarks)
            put("configType", session.configType)
            put("security", session.security ?: "")
            put("supportsMux", session.supportsMux)
            put("supportsFragment", session.supportsFragment)
            put("recommendedLabel", session.recommendedLabel ?: "")
            put("testUrl", session.testUrl)

            val resultsArr = JSONArray()
            for (r in session.results) {
                val rObj = JSONObject().apply {
                    put("scenarioType", r.scenario.type.name)
                    put("scenarioLabel", r.scenario.label)
                    put("muxEnabled", r.scenario.muxEnabled)
                    put("muxConcurrency", r.scenario.muxConcurrency)
                    put("muxXudpConcurrency", r.scenario.muxXudpConcurrency)
                    put("muxXudpQuic", r.scenario.muxXudpQuic)
                    put("fragmentEnabled", r.scenario.fragmentEnabled)
                    put("fragmentPackets", r.scenario.fragmentPackets)
                    put("fragmentLength", r.scenario.fragmentLength)
                    put("fragmentInterval", r.scenario.fragmentInterval)
                    put("medianMs", r.medianMs)
                    put("averageMs", r.averageMs)
                    put("successRate", r.successRate)
                    put("improvementPercent", r.improvementPercent)

                    val roundsArr = JSONArray()
                    for (round in r.rounds) {
                        roundsArr.put(round.delayMs)
                    }
                    put("rounds", roundsArr)
                }
                resultsArr.put(rObj)
            }
            put("results", resultsArr)
        }
        history.put(sessionJson)

        while (history.length() > MAX_HISTORY_SESSIONS) {
            history.remove(0)
        }
        storage.encode(KEY_HISTORY, history.toString())
    }

    fun getHistory(): List<MuxFragmentBenchmarkManager.BenchmarkSession> {
        val arr = getRawHistory()
        val sessions = mutableListOf<MuxFragmentBenchmarkManager.BenchmarkSession>()
        for (i in arr.length() - 1 downTo 0) {
            try {
                val obj = arr.getJSONObject(i)
                val resultsArr = obj.getJSONArray("results")
                val results = mutableListOf<MuxFragmentBenchmarkManager.BenchmarkScenarioResult>()

                for (j in 0 until resultsArr.length()) {
                    val rObj = resultsArr.getJSONObject(j)
                    val roundsArr = rObj.getJSONArray("rounds")
                    val rounds = mutableListOf<MuxFragmentBenchmarkManager.BenchmarkRoundResult>()
                    for (k in 0 until roundsArr.length()) {
                        rounds.add(MuxFragmentBenchmarkManager.BenchmarkRoundResult(roundsArr.getLong(k)))
                    }

                    val scenario = MuxFragmentBenchmarkManager.BenchmarkScenario(
                        type = MuxFragmentBenchmarkManager.BenchmarkScenarioType.valueOf(
                            rObj.getString("scenarioType")
                        ),
                        label = rObj.getString("scenarioLabel"),
                        muxEnabled = rObj.optBoolean("muxEnabled", false),
                        muxConcurrency = rObj.optInt("muxConcurrency", 8),
                        muxXudpConcurrency = rObj.optInt("muxXudpConcurrency", 16),
                        muxXudpQuic = rObj.optString("muxXudpQuic", "reject"),
                        fragmentEnabled = rObj.optBoolean("fragmentEnabled", false),
                        fragmentPackets = rObj.optString("fragmentPackets", "tlshello"),
                        fragmentLength = rObj.optString("fragmentLength", "50-100"),
                        fragmentInterval = rObj.optString("fragmentInterval", "10-20")
                    )

                    results.add(MuxFragmentBenchmarkManager.BenchmarkScenarioResult(
                        scenario = scenario,
                        rounds = rounds,
                        medianMs = rObj.getLong("medianMs"),
                        averageMs = rObj.getLong("averageMs"),
                        successRate = rObj.getInt("successRate"),
                        improvementPercent = rObj.optDouble("improvementPercent", 0.0)
                    ))
                }

                sessions.add(MuxFragmentBenchmarkManager.BenchmarkSession(
                    id = obj.getString("id"),
                    timestamp = obj.getLong("timestamp"),
                    serverGuid = obj.getString("serverGuid"),
                    serverRemarks = obj.getString("serverRemarks"),
                    configType = obj.getString("configType"),
                    security = obj.optString("security", "").ifEmpty { null },
                    supportsMux = obj.getBoolean("supportsMux"),
                    supportsFragment = obj.getBoolean("supportsFragment"),
                    results = results,
                    recommendedLabel = obj.optString("recommendedLabel", "").ifEmpty { null },
                    testUrl = obj.optString("testUrl", "")
                ))
            } catch (_: Exception) {
                // Skip malformed entries
            }
        }
        return sessions
    }

    fun getLastSession(): MuxFragmentBenchmarkManager.BenchmarkSession? {
        return getHistory().firstOrNull()
    }

    fun clearHistory() {
        storage.encode(KEY_HISTORY, JSONArray().toString())
    }

    private fun getRawHistory(): JSONArray {
        val json = storage.decodeString(KEY_HISTORY) ?: return JSONArray()
        return try {
            JSONArray(json)
        } catch (_: Exception) {
            JSONArray()
        }
    }
}
