package com.pocketos.app

import android.net.LocalServerSocket
import android.net.LocalSocketAddress
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QmpClientInstrumentationTest {
    @Test
    fun diagnosticProbeReportsQmpConnectivityAndVmStatus() {
        val socketName = "pocketos-qmp-probe-${UUID.randomUUID()}"
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
                    assertEquals("{\"execute\":\"query-status\"}", reader.readLine())
                    writer.write("{\"return\":{\"status\":\"running\",\"running\":true}}\n")
                    writer.flush()
                    assertEquals("{\"execute\":\"query-cpus-fast\"}", reader.readLine())
                    writer.write("{\"return\":[{\"cpu-index\":0,\"properties\":{\"core-type\":\"core\"},\"thread-id\":1234,\"qom-path\":\"/machine/unattached/device[0]\",\"halted\":false}]}\n")
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
            assertEquals(
                "connected status=running vcpus=[0:halted=false:threadId=1234]",
                QmpClient(LocalSocketAddress.Namespace.ABSTRACT).diagnosticProbe(socketName),
            )
            serverThread.join(3_000)
            assertEquals(false, serverThread.isAlive)
            assertNull(serverFailure.get())
        } finally {
            server.close()
        }
    }

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

    @Test
    fun quitRequestHasCallerDeadlineWhenPeerStopsResponding() {
        val socketName = "pocketos-qmp-stalled-${UUID.randomUUID()}"
        val server = LocalServerSocket(socketName)
        val accepted = CountDownLatch(1)
        val releasePeer = CountDownLatch(1)
        val serverThread = Thread {
            runCatching {
                server.accept().use {
                    accepted.countDown()
                    releasePeer.await(2, TimeUnit.SECONDS)
                }
            }
        }.apply { isDaemon = true; start() }

        try {
            val startedAt = System.nanoTime()
            val failure = runCatching {
                QmpClient(LocalSocketAddress.Namespace.ABSTRACT).requestQuit(socketName, timeoutMillis = 200)
            }.exceptionOrNull()
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            assertEquals(true, accepted.await(1, TimeUnit.SECONDS))
            assertNotNull("Stalled QMP peer unexpectedly completed shutdown", failure)
            assertTrue("QMP caller exceeded its bounded deadline: ${elapsedMs}ms", elapsedMs < 1_500)
        } finally {
            releasePeer.countDown()
            server.close()
            serverThread.join(1_000)
        }
    }
}
