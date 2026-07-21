package com.freefcc.app

import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale

internal data class ProcInetSocket(
    val protocol: String,
    val localAddressHex: String,
    val port: Int,
    val stateHex: String,
    val uid: Int?,
    val inode: Long?
) {
    fun asMap(): Map<String, Any?> = linkedMapOf(
        "protocol" to protocol,
        "local_address_hex" to localAddressHex,
        "port" to port,
        "state_hex" to stateHex,
        "uid" to uid,
        "inode" to inode
    )
}

internal data class LocalSocketInventoryResult(
    val openTcpPorts: List<Int>,
    val procTcpListeners: List<ProcInetSocket>,
    val procUdpSockets: List<ProcInetSocket>,
    val unixSocketNames: List<String>,
    val readableProcSources: List<String>,
    val errors: List<String>,
    val scannedPorts: Int,
    val complete: Boolean,
    val durationMs: Long
) {
    fun asJsonFields(): Array<Pair<String, Any?>> = arrayOf(
        "ok" to true,
        "command" to "local_socket_inventory",
        "probe_host" to "127.0.0.1",
        "probe_payload_bytes" to 0,
        "scanned_ports" to scannedPorts,
        "scan_complete" to complete,
        "open_tcp_ports" to openTcpPorts,
        "proc_tcp_listeners" to procTcpListeners.map { it.asMap() },
        "proc_udp_sockets" to procUdpSockets.map { it.asMap() },
        "unix_socket_names" to unixSocketNames,
        "readable_proc_sources" to readableProcSources,
        "errors" to errors,
        "duration_ms" to durationMs
    )
}

/** One-shot, zero-payload inventory for controller-local sockets. */
internal object LocalSocketInventory {
    private const val DEFAULT_CONNECT_TIMEOUT_MS = 15
    private const val DEFAULT_TOTAL_BUDGET_MS = 20_000L
    private const val MAX_PROC_LINES = 4_096

    fun capture(
        ports: IntRange = 1..65_535,
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
        totalBudgetMs: Long = DEFAULT_TOTAL_BUDGET_MS,
        procRoot: File = File("/proc/net")
    ): LocalSocketInventoryResult {
        val startedNs = System.nanoTime()
        val errors = mutableListOf<String>()
        val readableSources = mutableListOf<String>()

        fun readProc(name: String): String? {
            return try {
                val file = File(procRoot, name)
                val text = file.bufferedReader().useLines { lines ->
                    lines.take(MAX_PROC_LINES).joinToString("\n")
                }
                readableSources += file.path
                text
            } catch (e: Exception) {
                errors += "${File(procRoot, name).path}: ${e.javaClass.simpleName}"
                null
            }
        }

        val tcp = buildList {
            readProc("tcp")?.let { addAll(parseInetSockets(it, "tcp", listenersOnly = true)) }
            readProc("tcp6")?.let { addAll(parseInetSockets(it, "tcp6", listenersOnly = true)) }
        }.distinctBy { Triple(it.protocol, it.localAddressHex, it.port) }
            .sortedWith(compareBy(ProcInetSocket::port, ProcInetSocket::protocol))

        val udp = buildList {
            readProc("udp")?.let { addAll(parseInetSockets(it, "udp", listenersOnly = false)) }
            readProc("udp6")?.let { addAll(parseInetSockets(it, "udp6", listenersOnly = false)) }
        }.distinctBy { Triple(it.protocol, it.localAddressHex, it.port) }
            .sortedWith(compareBy(ProcInetSocket::port, ProcInetSocket::protocol))

        val unix = readProc("unix")?.let(::parseUnixSocketNames).orEmpty()
        val openPorts = mutableListOf<Int>()
        val loopback = InetAddress.getByName("127.0.0.1")
        var scannedPorts = 0
        var complete = true

        for (port in ports) {
            val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L
            if (elapsedMs >= totalBudgetMs) {
                complete = false
                errors += "tcp scan deadline reached"
                break
            }
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(loopback, port), connectTimeoutMs)
                    openPorts += port
                }
            } catch (_: java.io.IOException) {
                // Closed or filtered. A connect probe never writes application payload.
            } catch (e: SecurityException) {
                complete = false
                errors += "tcp scan blocked: ${e.javaClass.simpleName}"
                break
            } finally {
                scannedPorts++
            }
        }

        return LocalSocketInventoryResult(
            openTcpPorts = openPorts,
            procTcpListeners = tcp,
            procUdpSockets = udp,
            unixSocketNames = unix,
            readableProcSources = readableSources,
            errors = errors,
            scannedPorts = scannedPorts,
            complete = complete,
            durationMs = (System.nanoTime() - startedNs) / 1_000_000L
        )
    }

    internal fun parseInetSockets(
        text: String,
        protocol: String,
        listenersOnly: Boolean
    ): List<ProcInetSocket> = text.lineSequence().drop(1).mapNotNull { line ->
        val fields = line.trim().split(Regex("\\s+"))
        if (fields.size < 4) return@mapNotNull null
        val local = fields[1].split(':', limit = 2)
        if (local.size != 2) return@mapNotNull null
        val state = fields[3].uppercase(Locale.US)
        if (listenersOnly && state != "0A") return@mapNotNull null
        val port = local[1].toIntOrNull(16) ?: return@mapNotNull null
        ProcInetSocket(
            protocol = protocol,
            localAddressHex = local[0],
            port = port,
            stateHex = state,
            uid = fields.getOrNull(7)?.toIntOrNull(),
            inode = fields.getOrNull(9)?.toLongOrNull()
        )
    }.toList()

    internal fun parseUnixSocketNames(text: String): List<String> = text.lineSequence()
        .drop(1)
        .mapNotNull { line ->
            line.trim().split(Regex("\\s+"), limit = 8)
                .getOrNull(7)
                ?.takeIf { it.isNotBlank() }
                ?.take(1_024)
        }
        .distinct()
        .sorted()
        .take(MAX_PROC_LINES)
        .toList()
}
