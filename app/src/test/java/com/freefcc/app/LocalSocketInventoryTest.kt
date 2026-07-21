package com.freefcc.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files

class LocalSocketInventoryTest {

    @Test
    fun parsesListeningTcpAndBoundUdpSockets() {
        val table = """
            sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode
             0: 0100007F:9C47 00000000:0000 0A 00000000:00000000 00:00000000 00000000 1000 0 12345
             1: 0100007F:C350 0100007F:1234 01 00000000:00000000 00:00000000 00000000 1001 0 12346
        """.trimIndent()

        val tcp = LocalSocketInventory.parseInetSockets(table, "tcp", listenersOnly = true)
        val udp = LocalSocketInventory.parseInetSockets(table, "udp", listenersOnly = false)

        assertEquals(listOf(40_007), tcp.map { it.port })
        assertEquals(listOf(40_007, 50_000), udp.map { it.port })
        assertEquals(1000, tcp.single().uid)
        assertEquals(12345L, tcp.single().inode)
    }

    @Test
    fun parsesNamedUnixSocketsOnly() {
        val table = """
            Num RefCount Protocol Flags Type St Inode Path
            0001: 00000002 00000000 00010000 0002 01 123 @/duss/mb/0x205
            0002: 00000002 00000000 00000000 0001 01 124
        """.trimIndent()

        assertEquals(
            listOf("@/duss/mb/0x205"),
            LocalSocketInventory.parseUnixSocketNames(table)
        )
    }

    @Test
    fun oneShotProbeFindsListenerWithoutSendingPayload() {
        ServerSocket(0).use { server ->
            val procRoot = Files.createTempDirectory("empty-proc-net").toFile()
            try {
                val result = LocalSocketInventory.capture(
                    ports = server.localPort..server.localPort,
                    connectTimeoutMs = 100,
                    totalBudgetMs = 1_000,
                    procRoot = procRoot
                )

                assertEquals(listOf(server.localPort), result.openTcpPorts)
                assertEquals(1, result.scannedPorts)
                assertTrue(result.complete)
            } finally {
                procRoot.deleteRecursively()
            }
        }
    }
}
