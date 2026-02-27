package com.v2ray.ang.service

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.V2RayNativeManager
import com.v2ray.ang.handler.V2rayConfigManager
import com.v2ray.ang.util.MessageUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Worker-pool based real ping tester.
 *
 * Instead of chunked batches that block on the slowest server,
 * N independent workers continuously pull from a shared queue.
 * As soon as a worker finishes one server, it immediately grabs the next.
 */
class RealPingWorkerService(
    private val context: Context,
    private val guids: List<String>,
    private val onFinish: (status: String) -> Unit = {}
) {
    companion object {
        private const val WORKER_COUNT = 8
        private const val SINGLE_TEST_TIMEOUT_SEC = 3L
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO + CoroutineName("RealPingWorkerPool"))

    // Shared work queue - workers pull from this concurrently (lock-free)
    private val workQueue = ConcurrentLinkedQueue(guids)

    // Thread pool for timeout-controlled native calls
    private val pingExecutor = Executors.newFixedThreadPool(WORKER_COUNT)

    private val completedCount = AtomicInteger(0)
    private val totalCount: Int = guids.size

    fun start() {
        scope.launch {
            try {
                // Launch N independent workers that continuously pull from the queue
                val workers = mutableListOf<Job>()
                repeat(WORKER_COUNT) {
                    workers.add(launch {
                        while (isActive) {
                            val guid = workQueue.poll() ?: break

                            val result = try {
                                testServer(guid)
                            } catch (_: Exception) {
                                -1L
                            }

                            // Report result
                            MessageUtil.sendMsg2UI(
                                context,
                                AppConfig.MSG_MEASURE_CONFIG_SUCCESS,
                                Pair(guid, result)
                            )

                            // Update progress
                            val done = completedCount.incrementAndGet()
                            val remaining = totalCount - done
                            MessageUtil.sendMsg2UI(
                                context,
                                AppConfig.MSG_MEASURE_CONFIG_NOTIFY,
                                "$remaining / $totalCount"
                            )
                        }
                    })
                }

                // Wait for all workers to finish
                workers.joinAll()
                onFinish("0")
            } catch (_: CancellationException) {
                onFinish("-1")
            } finally {
                close()
            }
        }
    }

    fun cancel() {
        workQueue.clear()
        job.cancel()
    }

    private fun close() {
        try {
            pingExecutor.shutdownNow()
        } catch (_: Throwable) { }
    }

    /**
     * Tests a single server with a hard timeout.
     */
    private fun testServer(guid: String): Long {
        val configResult = V2rayConfigManager.getV2rayConfig4Speedtest(context, guid)
        if (!configResult.status) {
            return -1L
        }

        val testUrl = SettingsManager.getDelayTestUrl()
        return measureWithTimeout(configResult.content, testUrl)
    }

    /**
     * Runs measureOutboundDelay with a hard timeout.
     * Returns -1 if the test times out or fails.
     */
    private fun measureWithTimeout(config: String, testUrl: String): Long {
        return try {
            val future = pingExecutor.submit(Callable {
                V2RayNativeManager.measureOutboundDelay(config, testUrl)
            })
            try {
                future.get(SINGLE_TEST_TIMEOUT_SEC, TimeUnit.SECONDS)
            } catch (_: TimeoutException) {
                future.cancel(true)
                -1L
            }
        } catch (_: Exception) {
            -1L
        }
    }
}
