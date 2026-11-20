package com.clashflux.android.core

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

/**
 * Shared OkHttp base instance and underlying-network-aware TCP probe utilities.
 */
object NetworkClients {

    /**
     * Application-wide base client sharing one connection pool and dispatcher across repositories,
     * controller calls, and network probes. Derive call-specific clients with `newBuilder()`.
     */
    val baseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * Resolves [host] via [resolver] and opens/connects a socket from [socketFactory] within a
     * single combined [timeoutMs] budget so slow DNS resolution cannot hang beyond the timeout.
     */
    suspend fun measureTcpConnect(
        host: String,
        port: Int,
        timeoutMs: Int = 3000,
        nowMs: () -> Long = { System.currentTimeMillis() },
        resolver: (String) -> InetAddress = { InetAddress.getByName(it) },
        socketFactory: () -> Socket = { Socket() },
        executor: ExecutorService = baseClient.dispatcher.executorService
    ): Int {
        val cleanHost = host.trim()
        if (cleanHost.isEmpty() || port !in 1..65535 || timeoutMs <= 0) return -1

        val activeSocket = AtomicReference<Socket?>(null)
        return try {
            withTimeoutOrNull(timeoutMs.toLong()) {
                suspendCancellableCoroutine { cont ->
                    val future = executor.submit {
                        try {
                            val start = nowMs()
                            val resolved = resolver(cleanHost)
                            if (!cont.isActive) return@submit

                            val elapsedAfterDns = (nowMs() - start).coerceAtLeast(0L)
                            val remainingMs = (timeoutMs - elapsedAfterDns).toInt()
                            if (remainingMs <= 0) {
                                cont.resume(-1)
                                return@submit
                            }

                            val socket = socketFactory()
                            activeSocket.set(socket)
                            if (!cont.isActive) {
                                runCatching { socket.close() }
                                return@submit
                            }

                            socket.use { s ->
                                s.connect(InetSocketAddress(resolved, port), remainingMs)
                                val rtt = (nowMs() - start).toInt().coerceAtLeast(1)
                                if (cont.isActive) cont.resume(rtt)
                            }
                        } catch (_: Exception) {
                            if (cont.isActive) cont.resume(-1)
                        }
                    }
                    cont.invokeOnCancellation {
                        runCatching { activeSocket.getAndSet(null)?.close() }
                        future.cancel(true)
                    }
                }
            } ?: -1
        } catch (_: Exception) {
            -1
        } finally {
            runCatching { activeSocket.getAndSet(null)?.close() }
        }
    }
}
