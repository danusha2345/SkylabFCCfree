package com.freefcc.app

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

internal enum class HomePointWaitResult {
    RECORDED,
    DISCONNECTED,
    STOPPED
}

internal object HomePointProtocol {
    fun buildProbe(builder: DumlBuilder = DumlBuilder()): ByteArray =
        builder.buildFrame(
            DumlFrame(
                sender = 0x02,
                cmdType = 0x40,
                cmdSet = 0x03,
                cmdId = 0x44,
                dst = 0x03,
                payload = ByteArray(0)
            )
        )

    fun isRecorded(frame: ByteArray): Boolean? {
        if (frame.size < 35 || frame[0] != 0x55.toByte()) return null
        val length = (frame[1].toInt() and 0xFF) or
            ((frame[2].toInt() and 0x03) shl 8)
        if (length != frame.size) return null
        if (DumlBuilder.crc8(frame, 0, 3) != (frame[3].toInt() and 0xFF)) return null
        val expectedCrc = DumlBuilder.crc16(frame, 0, frame.size - 2)
        val actualCrc = (frame[frame.lastIndex - 1].toInt() and 0xFF) or
            ((frame[frame.lastIndex].toInt() and 0xFF) shl 8)
        if (expectedCrc != actualCrc) return null

        val senderType = frame[4].toInt() and 0x1F
        val destinationType = frame[5].toInt() and 0x1F
        if (senderType !in setOf(0x03, 0x0E) || destinationType != 0x02) return null
        if (frame[9] != 0x03.toByte() || frame[10] != 0x44.toByte()) return null

        val payloadLength = frame.size - 13
        if (payloadLength < 22) return null
        val homeState = (frame[31].toInt() and 0xFF) or
            ((frame[32].toInt() and 0xFF) shl 8)
        return (homeState and 0x01) != 0
    }
}

/** Parses the outer 40007 envelope without opening additional connections. */
internal class WrappedDumlFrameParser {
    private var pending = ByteArray(0)

    fun feed(bytes: ByteArray, length: Int = bytes.size): List<ByteArray> {
        if (length <= 0) return emptyList()
        val combined = ByteArray(pending.size + length)
        pending.copyInto(combined)
        bytes.copyInto(combined, pending.size, 0, length)
        pending = combined

        val frames = mutableListOf<ByteArray>()
        while (pending.size >= 8) {
            val magic = findOuterMagic(pending)
            if (magic < 0) {
                pending = pending.takeLast(3).toByteArray()
                break
            }
            if (magic > 0) pending = pending.copyOfRange(magic, pending.size)
            if (pending.size < 8) break

            val innerLength = (pending[4].toInt() and 0xFF) or
                ((pending[5].toInt() and 0xFF) shl 8) or
                ((pending[6].toInt() and 0xFF) shl 16) or
                ((pending[7].toInt() and 0xFF) shl 24)
            if (innerLength !in 13..1023) {
                pending = pending.copyOfRange(1, pending.size)
                continue
            }
            val outerLength = 8 + innerLength
            if (pending.size < outerLength) break
            val candidate = pending.copyOfRange(8, outerLength)
            if (!isValidDuml(candidate)) {
                pending = pending.copyOfRange(1, pending.size)
                continue
            }
            frames += candidate
            pending = pending.copyOfRange(outerLength, pending.size)
        }
        return frames
    }

    private fun findOuterMagic(bytes: ByteArray): Int {
        for (index in 0..bytes.size - 4) {
            if (bytes[index] == 0x55.toByte() &&
                bytes[index + 1] == 0xCC.toByte() &&
                bytes[index + 2] == 0x30.toByte() &&
                bytes[index + 3] == 0x75.toByte()
            ) return index
        }
        return -1
    }

    private fun isValidDuml(frame: ByteArray): Boolean {
        if (frame.size < 13 || frame[0] != 0x55.toByte()) return false
        val encodedLength = (frame[1].toInt() and 0xFF) or
            ((frame[2].toInt() and 0x03) shl 8)
        if (encodedLength != frame.size) return false
        if (DumlBuilder.crc8(frame, 0, 3) != (frame[3].toInt() and 0xFF)) return false
        val expectedCrc = DumlBuilder.crc16(frame, 0, frame.size - 2)
        val actualCrc = (frame[frame.lastIndex - 1].toInt() and 0xFF) or
            ((frame[frame.lastIndex].toInt() and 0xFF) shl 8)
        return expectedCrc == actualCrc
    }
}

/** One connection that remains open only until Home Point is observed. */
internal class HomePointMonitor(
    private val host: String = "127.0.0.1",
    private val port: Int = DumlTransport.PORT_LED
) {
    fun waitUntilRecorded(shouldContinue: () -> Boolean): HomePointWaitResult {
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress(host, port), 2_000)
            socket.tcpNoDelay = true
            socket.soTimeout = 250

            val probe = HomePointProtocol.buildProbe()
            socket.getOutputStream().apply {
                write(Profiles.wrapFrame(probe))
                flush()
            }

            val parser = WrappedDumlFrameParser()
            val buffer = ByteArray(4_096)
            while (shouldContinue()) {
                val count = try {
                    socket.getInputStream().read(buffer)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (count <= 0) return HomePointWaitResult.DISCONNECTED
                for (frame in parser.feed(buffer, count)) {
                    if (HomePointProtocol.isRecorded(frame) == true) {
                        return HomePointWaitResult.RECORDED
                    }
                }
            }
            HomePointWaitResult.STOPPED
        } catch (_: IOException) {
            if (shouldContinue()) HomePointWaitResult.DISCONNECTED else HomePointWaitResult.STOPPED
        } finally {
            try { socket?.close() } catch (_: IOException) {}
        }
    }
}
