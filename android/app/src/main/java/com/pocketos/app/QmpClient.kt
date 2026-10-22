package com.pocketos.app

import android.net.LocalSocket
import android.net.LocalSocketAddress
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Sends a graceful QMP quit request to the running QEMU process. */
class QmpClient(
    private val namespace: LocalSocketAddress.Namespace = LocalSocketAddress.Namespace.FILESYSTEM,
) {
    fun requestQuit(socketName: String, timeoutMillis: Int = 3_000) {
        require(socketName.isNotBlank()) { "QMP socket name cannot be blank" }
        require(timeoutMillis > 0) { "QMP timeout must be positive" }

        val future = probeExecutor.submit { requestQuitBlocking(socketName, timeoutMillis) }
        try {
            future.get(timeoutMillis.toLong() + PROBE_CONNECT_MARGIN_MS, TimeUnit.MILLISECONDS)
        } catch (failure: Exception) {
            future.cancel(true)
            throw IllegalStateException("QMP quit request did not complete within its bounded deadline", failure)
        }
    }

    private fun requestQuitBlocking(socketName: String, timeoutMillis: Int) {
        LocalSocket().use { socket ->
            socket.connect(LocalSocketAddress(socketName, namespace))
            socket.soTimeout = timeoutMillis
            val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(socket.outputStream, Charsets.UTF_8))

            val greeting = reader.readLine() ?: error("QMP closed before greeting")
            check(greeting.contains("\"QMP\"")) { "Invalid QMP greeting" }

            writer.write("{\"execute\":\"qmp_capabilities\"}\n")
            writer.flush()
            readCommandResponse(reader, "QMP capabilities")

            writer.write("{\"execute\":\"quit\"}\n")
            writer.flush()
            readCommandResponse(reader, "QMP quit")
        }
    }

    /** Read-only QMP probe for bounded failure diagnostics. The detail contains no socket path. */
    fun diagnosticProbe(socketName: String, timeoutMillis: Int = 500): String {
        if (socketName.isBlank()) return "unavailable(no-socket)"
        if (timeoutMillis <= 0) return "unavailable(invalid-timeout)"
        val future = probeExecutor.submit<String> { diagnosticProbeBlocking(socketName, timeoutMillis) }
        return try {
            future.get(timeoutMillis.toLong() + PROBE_CONNECT_MARGIN_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            "unavailable(probe-timeout)"
        } catch (failure: Exception) {
            "unavailable(${failure.javaClass.simpleName})"
        }
    }

    private fun diagnosticProbeBlocking(socketName: String, timeoutMillis: Int): String {
        if (socketName.isBlank()) return "unavailable(no-socket)"
        return try {
            LocalSocket().use { socket ->
                socket.connect(LocalSocketAddress(socketName, namespace))
                socket.soTimeout = timeoutMillis
                val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
                val writer = BufferedWriter(OutputStreamWriter(socket.outputStream, Charsets.UTF_8))
                val greeting = reader.readLine() ?: return "unavailable(no-greeting)"
                if (!greeting.contains("\"QMP\"")) return "unavailable(invalid-greeting)"
                writer.write("{\"execute\":\"qmp_capabilities\"}\n")
                writer.flush()
                readCommandResponse(reader, "QMP capabilities")
                writer.write("{\"execute\":\"query-status\"}\n")
                writer.flush()
                val response = readCommandResponse(reader, "QMP query-status")
                val status = QMP_STATUS.find(response)?.groupValues?.get(1)
                writer.write("{\"execute\":\"query-cpus-fast\"}\n")
                writer.flush()
                val cpuResponse = readCommandResponse(reader, "QMP query-cpus-fast")
                val cpuArray = JSONObject(cpuResponse).optJSONArray("return")
                val cpus = (0 until minOf(cpuArray?.length() ?: 0, MAX_REPORTED_CPUS))
                    .joinToString(prefix = "[", postfix = "]") { index ->
                        val cpu = cpuArray!!.getJSONObject(index)
                        val cpuIndex = cpu.optInt("cpu-index", index)
                        val halted = cpu.opt("halted")?.toString() ?: "unknown"
                        val threadId = cpu.opt("thread-id")?.toString() ?: "unknown"
                        "$cpuIndex:halted=$halted:threadId=$threadId"
                    }
                "connected" + (status?.let { " status=$it" } ?: "") +
                    " vcpus=${if (cpus == "[]") "unavailable" else cpus}"
            }
        } catch (failure: Exception) {
            "unavailable(${failure.javaClass.simpleName})"
        }
    }

    private fun readCommandResponse(reader: BufferedReader, command: String): String {
        while (true) {
            val line = reader.readLine() ?: error("$command closed without a response")
            if (line.contains("\"error\"")) {
                error("$command failed")
            }
            if (line.contains("\"return\"")) {
                return line
            }
        }
    }

    private companion object {
        val QMP_STATUS = Regex("\\\"status\\\"\\s*:\\s*\\\"([A-Za-z0-9_-]{1,32})\\\"")
        const val MAX_REPORTED_CPUS = 8
        const val PROBE_CONNECT_MARGIN_MS = 500L
        val probeExecutor = Executors.newFixedThreadPool(2, ThreadFactory { task ->
            Thread(task, "pocketos-qmp-diagnostic").apply { isDaemon = true }
        })
    }
}
