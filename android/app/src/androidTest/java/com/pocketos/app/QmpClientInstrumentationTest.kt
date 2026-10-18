package com.pocketos.app

import android.net.LocalServerSocket
import android.net.LocalSocket
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QmpClientInstrumentationTest {
    @Test
    fun negotiatesQmpAndRequestsQuit() {
        val socketName = "pocketos-qmp-${UUID.randomUUID()}"
        val server = LocalServerSocket(socketName)
        val serverFailure = AtomicReference<Throwable?>(null)

        val serverThread = Thread {
            try {
                server.accept().use { peer ->
                    val reader = BufferedReader(InputStreamReader(peer.inputStream, Charsets.UTF_8))
                    val writer = OutputStreamWriter(peer.outputStream, Charsets.UTF_8)
                    writer.write("{\"QMP\":{\"version\":{}}}\n")
                    writer.flush()

                    assertEquals("{\"execute\":\"qmp_capabilities\"}", reader.readLine())
                    writer.write("{\"return\":{}}\n")
                    writer.flush()

                    assertEquals("{\"execute\":\"quit\"}", reader.readLine())
                    writer.write("{\"return\":{}}\n")
                    writer.flush()
                }
            } catch (failure: Throwable) {
                serverFailure.set(failure)
            }
        }.apply {
            isDaemon = true
            start()
        }

        try {
            QmpClient(LocalSocketAddress.Namespace.ABSTRACT)
                .requestQuit(socketName, timeoutMillis = 3_000)
            serverThread.join(3_000)
            assertEquals(false, serverThread.isAlive)
            assertNull(serverFailure.get())
        } finally {
            server.close()
        }
    }
}
