package com.freefcc.app

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePointMonitorTest {
    @Test
    fun recordedSnapshotRequiresResetAndRisingEdge() {
        val gate = HomePointSessionGate()

        assertFalse(gate.observe(true))
        assertFalse(gate.observe(true))
        assertFalse(gate.observe(false))
        assertFalse(gate.observe(false))
        assertTrue(gate.observe(true))
        assertFalse(gate.observe(true))
    }

    @Test
    fun detectsRecordedBitInFragmentedWrappedStream() {
        val before = homePointFrame(0x0046)
        val after = homePointFrame(0x0047)
        val parser = WrappedDumlFrameParser()
        val wire = Profiles.wrapFrame(before) + Profiles.wrapFrame(after)

        val frames = mutableListOf<ByteArray>()
        frames += parser.feed(wire.copyOfRange(0, 7))
        frames += parser.feed(wire.copyOfRange(7, 41))
        frames += parser.feed(wire.copyOfRange(41, wire.size))

        assertEquals(2, frames.size)
        assertFalse(HomePointProtocol.isRecorded(frames[0])!!)
        assertTrue(HomePointProtocol.isRecorded(frames[1])!!)
    }

    @Test
    fun skipsCorruptOuterCandidateAndResynchronizes() {
        val corrupt = Profiles.wrapFrame(homePointFrame(0x0047)).also {
            it[it.lastIndex] = (it[it.lastIndex].toInt() xor 0x01).toByte()
        }
        val valid = Profiles.wrapFrame(homePointFrame(0x0047))
        val frames = WrappedDumlFrameParser().feed(corrupt + valid)

        assertEquals(1, frames.size)
        assertTrue(HomePointProtocol.isRecorded(frames.single())!!)
    }

    @Test
    fun rejectsResponseAndAckRequestWithPushLayoutBytes() {
        assertEquals(null, HomePointProtocol.isRecorded(homePointFrame(0x0047, cmdType = 0x80)))
        assertEquals(null, HomePointProtocol.isRecorded(homePointFrame(0x0047, cmdType = 0xC0)))
        assertEquals(null, HomePointProtocol.isRecorded(homePointFrame(0x0047, cmdType = 0x40)))
        assertTrue(HomePointProtocol.isRecorded(homePointFrame(0x0047, cmdType = 0x00))!!)
    }

    @Test
    fun monitorUsesOneConnectionAndStopsAfterRecordedBit() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val serverError = AtomicReference<Throwable>()
        val thread = Thread {
            try {
                server.use { listener ->
                    listener.accept().use { socket ->
                        socket.getInputStream().read(ByteArray(128))
                        val before = Profiles.wrapFrame(homePointFrame(0x0046))
                        val after = Profiles.wrapFrame(homePointFrame(0x0047))
                        socket.getOutputStream().apply {
                            write(before)
                            write(after, 0, 5)
                            flush()
                            write(after, 5, after.size - 5)
                            flush()
                        }
                    }
                }
            } catch (t: Throwable) {
                serverError.set(t)
            }
        }
        thread.start()

        val result = HomePointMonitor(port = server.localPort).waitUntilRecorded { true }

        thread.join(2_000)
        serverError.get()?.let { throw AssertionError("server failed", it) }
        assertEquals(HomePointWaitResult.RECORDED, result)
    }

    @Test
    fun initialRecordedSnapshotThenEofDoesNotCompleteWait() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val serverError = AtomicReference<Throwable>()
        val thread = Thread {
            try {
                server.use { listener ->
                    listener.accept().use { socket ->
                        socket.getInputStream().read(ByteArray(128))
                        socket.getOutputStream().apply {
                            write(Profiles.wrapFrame(homePointFrame(0x0047)))
                            flush()
                        }
                    }
                }
            } catch (t: Throwable) {
                serverError.set(t)
            }
        }
        thread.start()

        val result = HomePointMonitor(port = server.localPort).waitUntilRecorded { true }

        thread.join(2_000)
        serverError.get()?.let { throw AssertionError("server failed", it) }
        assertEquals(HomePointWaitResult.STREAM_DISCONNECTED, result)
    }

    @Test
    fun sessionGateSurvivesCooperativeReopenAndRequiresFreshEdge() {
        val firstServer = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val firstThread = Thread {
            firstServer.use { listener ->
                listener.accept().use { socket ->
                    socket.getInputStream().read(ByteArray(128))
                    socket.getOutputStream().apply {
                        write(Profiles.wrapFrame(homePointFrame(0x0047)))
                        flush()
                    }
                }
            }
        }
        firstThread.start()
        val gate = HomePointSessionGate()
        var continuationChecks = 0

        val firstResult = HomePointMonitor(port = firstServer.localPort)
            .waitUntilRecorded(gate) { ++continuationChecks == 1 }
        firstThread.join(2_000)
        assertEquals(HomePointWaitResult.STOPPED, firstResult)

        val secondServer = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val secondThread = Thread {
            secondServer.use { listener ->
                listener.accept().use { socket ->
                    socket.getInputStream().read(ByteArray(128))
                    socket.getOutputStream().apply {
                        write(Profiles.wrapFrame(homePointFrame(0x0046)))
                        write(Profiles.wrapFrame(homePointFrame(0x0047)))
                        flush()
                    }
                }
            }
        }
        secondThread.start()

        val secondResult = HomePointMonitor(port = secondServer.localPort)
            .waitUntilRecorded(gate) { true }
        secondThread.join(2_000)
        assertEquals(HomePointWaitResult.RECORDED, secondResult)
    }

    @Test
    fun monitorStopsWithoutOpeningAnotherConnection() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val keepRunning = AtomicBoolean(true)
        val thread = Thread {
            server.use { listener ->
                listener.accept().use { socket ->
                    socket.getInputStream().read(ByteArray(128))
                    Thread.sleep(400)
                }
            }
        }
        thread.start()
        Thread {
            Thread.sleep(50)
            keepRunning.set(false)
        }.start()

        val result = HomePointMonitor(port = server.localPort)
            .waitUntilRecorded { keepRunning.get() }

        thread.join(2_000)
        assertEquals(HomePointWaitResult.STOPPED, result)
    }

    private fun homePointFrame(homeState: Int, cmdType: Int = 0x00): ByteArray {
        val payload = ByteArray(102)
        payload[20] = (homeState and 0xFF).toByte()
        payload[21] = ((homeState shr 8) and 0xFF).toByte()
        return DumlBuilder().buildFrame(
            DumlFrame(
                sender = 0x0E,
                cmdType = cmdType,
                cmdSet = 0x03,
                cmdId = 0x44,
                dst = 0x02,
                payload = payload
            )
        )
    }
}
