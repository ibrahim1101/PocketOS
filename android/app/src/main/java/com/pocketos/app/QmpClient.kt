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
            socket.soTimeout = timeoutMillis
            socket.connect(LocalSocketAddress(socketName, namespace))
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

    private fun readCommandResponse(reader: BufferedReader, command: String) {
        while (true) {
            val line = reader.readLine() ?: error("$command closed without a response")
            if (line.contains("\"error\"")) {
                error("$command failed: $line")
            }
            if (line.contains("\"return\"")) {
                return
            }
        }
    }
}
