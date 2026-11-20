package com.clashflux.android.core

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkClientsTest {

    @Test
    fun `measureTcpConnect uses custom resolver and socketFactory within remaining budget`() = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val resolverCalls = AtomicInteger(0)
            val customSocketCreated = AtomicInteger(0)
            val delay = NetworkClients.measureTcpConnect(
                host = "custom.probe.test",
                port = server.localPort,
                timeoutMs = 2000,
                resolver = { host ->
                    assertEquals("custom.probe.test", host)
                    resolverCalls.incrementAndGet()
                    InetAddress.getByName("127.0.0.1")
                },
                socketFactory = {
                    customSocketCreated.incrementAndGet()
                    Socket()
                }
            )
            assertTrue("Expected positive delay, got $delay", delay > 0)
            assertEquals(1, resolverCalls.get())
            assertEquals(1, customSocketCreated.get())
        }
    }

    @Test
    fun `measureTcpConnect aborts when DNS resolution consumes timeout budget or fails`() = runBlocking {
        val fakeClock = AtomicLong(1000L)
        val socketAttempts = AtomicInteger(0)
        val exhaustedDelay = NetworkClients.measureTcpConnect(
            host = "slow.dns.test",
            port = 443,
            timeoutMs = 3000,
            nowMs = { fakeClock.get() },
            resolver = {
                fakeClock.addAndGet(3100L)
                InetAddress.getByName("127.0.0.1")
            },
            socketFactory = {
                socketAttempts.incrementAndGet()
                Socket()
            }
        )
        assertEquals(-1, exhaustedDelay)
        assertEquals(0, socketAttempts.get())

        val failedDns = NetworkClients.measureTcpConnect(
            host = "missing.example.invalid",
            port = 443,
            timeoutMs = 1500,
            resolver = { throw UnknownHostException("NXDOMAIN") }
        )
        assertEquals(-1, failedDns)
        assertEquals(-1, NetworkClients.measureTcpConnect(host = "", port = 443))
        assertEquals(-1, NetworkClients.measureTcpConnect(host = "127.0.0.1", port = 70000))
    }

    @Test
    fun `measureTcpConnect enforces timeout even if blocking resolver hangs`() = runBlocking {
        val timedOut = NetworkClients.measureTcpConnect(
            host = "hanging.dns.test",
            port = 443,
            timeoutMs = 120,
            resolver = {
                Thread.sleep(2000)
                InetAddress.getByName("127.0.0.1")
            }
        )
        assertEquals(-1, timedOut)
    }
}
