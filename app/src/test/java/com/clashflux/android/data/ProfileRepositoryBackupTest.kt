package com.clashflux.android.data

import com.clashflux.android.core.ClashConfigEditor
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileRepositoryBackupTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun prepareRuntimeYaml_stripsDesktopTunAndAppliesCustomPortsAndLanBypass() {
        val raw = """
            tun:
              enable: true
              stack: gvisor
            proxies:
              - name: Node1
                type: ss
                server: 1.2.3.4
                port: 8388
                cipher: aes-128-gcm
                password: pwd
            rules:
              - MATCH,DIRECT
        """.trimIndent()

        val runtime = ProfileRepository.prepareRuntimeYaml(
            rawProfileYaml = raw,
            bypassLan = true,
            mixedPort = 8892,
            httpPort = 8890,
            socksPort = 8891
        )

        assertFalse(runtime.lines().any { it.trimStart().startsWith("tun:") })
        assertTrue(runtime.contains("port: 8890"))
        assertTrue(runtime.contains("socks-port: 8891"))
        assertTrue(runtime.contains("mixed-port: 8892"))
        assertTrue(runtime.contains("IP-CIDR,192.168.0.0/16,DIRECT,no-resolve"))
        assertTrue(runtime.contains("GEOIP,lan,DIRECT,no-resolve"))
        assertEquals(null, ClashConfigEditor.validateYamlSyntax(runtime))
    }

    @Test
    fun writeProfileYaml_createsBackupAndRestoreRollsBackPreviousContent() = runTest {
        val repo = ProfileRepository(tempFolder.root)
        val v1 = """
            mixed-port: 7892
            proxies:
              - name: NodeV1
                type: ss
                server: 1.1.1.1
                port: 443
                cipher: aes-128-gcm
                password: v1
            rules:
              - MATCH,DIRECT
        """.trimIndent()
        val v2 = v1.replace("NodeV1", "NodeV2")

        val (_, created) = repo.importLocalYamlProfile("Local", v1).getOrThrow()
        assertFalse(repo.hasProfileBackup(created.id))

        repo.writeProfileYaml(created.id, v2)
        assertTrue(repo.hasProfileBackup(created.id))
        assertTrue(repo.readProfileYaml(created.id).contains("NodeV2"))

        val restored = repo.restoreProfileBackup(created.id).getOrThrow()
        assertEquals(created.id, restored.id)
        assertTrue(repo.readProfileYaml(created.id).contains("NodeV1"))
        assertFalse(repo.hasProfileBackup(created.id))
    }

    @Test
    fun deleteProfile_removesYamlAndBakFiles() = runTest {
        val repo = ProfileRepository(tempFolder.root)
        val v1 = "mixed-port: 7892\nrules:\n  - MATCH,DIRECT\n"
        val v2 = "mixed-port: 7893\nrules:\n  - MATCH,DIRECT\n"

        val (_, created) = repo.importLocalYamlProfile("ToDelete", v1).getOrThrow()
        repo.writeProfileYaml(created.id, v2)
        val yamlFile = File(created.filePath)
        val bakFile = File("${created.filePath}.bak")
        assertTrue(yamlFile.exists())
        assertTrue(bakFile.exists())

        val (remaining, _) = repo.deleteProfile(created.id)
        assertTrue(remaining.isEmpty())
        assertFalse(yamlFile.exists())
        assertFalse(bakFile.exists())
    }

    @Test
    fun addProfile_rejectsResponseExceedingContentLengthLimit() = runTest {
        val oversized = ProfileRepository.MAX_SUBSCRIPTION_BYTES + 1024L
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val worker = thread(isDaemon = true) {
                runCatching {
                    server.accept().use { client ->
                        val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        val response = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/plain\r\n" +
                            "Content-Length: $oversized\r\n" +
                            "Connection: close\r\n\r\n"
                        client.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
                        client.getOutputStream().flush()
                    }
                }
            }

            val repo = ProfileRepository(tempFolder.root)
            val result = repo.addProfile("Oversized", "http://127.0.0.1:${server.localPort}/sub")
            worker.join(2_000L)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("大小限制"))
        }
    }

    @Test
    fun addProfile_abortsChunkedStreamExceeding16MbLimit() = runTest {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val worker = thread(isDaemon = true) {
                runCatching {
                    server.accept().use { client ->
                        client.soTimeout = 5_000
                        val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        val out = client.getOutputStream()
                        out.write(
                            ("HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/plain\r\n" +
                                "Transfer-Encoding: chunked\r\n" +
                                "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
                        )
                        val chunkData = ByteArray(64 * 1024) { 'a'.code.toByte() }
                        val chunkHeader = "${chunkData.size.toString(16)}\r\n".toByteArray(Charsets.US_ASCII)
                        val chunkTail = "\r\n".toByteArray(Charsets.US_ASCII)
                        val chunksNeeded = (ProfileRepository.MAX_SUBSCRIPTION_BYTES / chunkData.size).toInt() + 2
                        repeat(chunksNeeded) {
                            out.write(chunkHeader)
                            out.write(chunkData)
                            out.write(chunkTail)
                        }
                        out.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        out.flush()
                    }
                }
            }

            val repo = ProfileRepository(tempFolder.root)
            val result = repo.addProfile("ChunkedOversized", "http://127.0.0.1:${server.localPort}/chunked")
            worker.join(5_000L)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("大小限制"))
        }
    }
}
