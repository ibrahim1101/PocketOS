package com.pocketos.app

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/** Sends a graceful QMP quit request to the running QEMU process. */
class QmpClient(
    private val namespace: LocalSocketAddress.Namespace = LocalSocketAddress.Namespace.FILESYSTEM,
) {
    fun requestQuit(socketName: String, timeoutMillis: Int = 3_000) {
        require(socketName.isNotBlank()) { "QMP socket name cannot be blank" }
        require(timeoutMillis > 0) { "QMP timeout must be positive" }

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
        return try {
            LocalSocket().use { socket ->
                socket.soTimeout = timeoutMillis
                socket.connect(LocalSocketAddress(socketName, namespace))
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
                "connected" + (status?.let { " status=$it" } ?: "")
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
    }
}
