package com.freefcc.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AircraftSerialQueryTest {

    @Test
    fun targetedSendsReadAgainAfterAircraftChanges() {
        var linkedSerial = "1581FAKE000000000001"
        var queries = 0
        fun read() = AircraftSerialQueryRunner.readCurrent(
            query = { queries++; linkedSerial },
            passive = { error("A matching reply must not open a passive listen") },
            acceptsPassive = { false }
        )
        assertEquals(linkedSerial, read())
        linkedSerial = "1581FAKE000000000002"
        assertEquals(linkedSerial, read())
        assertEquals(2, queries)
    }

    @Test
    fun targetedSendRejectsLingeringSerialInPassiveFallback() {
        assertEquals("", AircraftSerialQueryRunner.readCurrent(
            query = { "" },
            passive = { "1581FAKE000000000001" },
            acceptsPassive = { false }
        ))
        assertEquals("1581FAKE000000000002", AircraftSerialQueryRunner.readCurrent(
            query = { "" },
            passive = { "1581FAKE000000000002" },
            acceptsPassive = { true }
        ))
    }

    private fun reply(status: Int, length: Int, body: ByteArray): ByteArray =
        byteArrayOf(status.toByte(), length.toByte(), (length shr 8).toByte()) + body

    @Test
    fun requestAsksTheFlightControllerForTheSerialField() {
        val frame = AircraftSerialProtocol.buildRequest()

        // 55 <len> <ver+len_hi> <crc8> <sender> <dst> <seq lo> <seq hi> <type> <set> <id> <payload>
        assertEquals(0x55.toByte(), frame[0])
        assertEquals(0x02.toByte(), frame[4])                       // sender: mobile app
        assertEquals(0x03.toByte(), frame[5])                       // dst: flight controller
        assertEquals(0x40.toByte(), frame[8])                       // request with ACK
        assertEquals(0x00.toByte(), frame[9])                       // cmd_set
        assertEquals(0x51.toByte(), frame[10])                      // cmd_id
        assertEquals(AircraftSerialProtocol.FIELD_AIRCRAFT_SERIAL.toByte(), frame[11])
        assertEquals(14, frame.size)                                // 13 + one selector byte
    }

    @Test
    fun parsesTheSerialFromALiveReply() {
        // Captured from Avata 360 on 2026-08-08: 00 14 00 then 20 ASCII bytes.
        val serial = "1581FA8JC264600B31QZ"
        val payload = reply(0x00, serial.length, serial.toByteArray(Charsets.US_ASCII))

        assertEquals(serial, AircraftSerialProtocol.parse(payload))
    }

    @Test
    fun rejectsRepliesThatCarryNoUsableSerial() {
        val serial = "1581FA8JC264600B31QZ".toByteArray(Charsets.US_ASCII)

        // Field refused: the aircraft answers 0xfd when the selector matches no field.
        assertEquals("", AircraftSerialProtocol.parse(reply(0xFD, 0, ByteArray(20))))
        // Length longer than the bytes actually present must not over-read.
        assertEquals("", AircraftSerialProtocol.parse(reply(0x00, 40, serial)))
        // A controller serial is not an aircraft serial and must not be stored as one.
        assertEquals("", AircraftSerialProtocol.parse(reply(0x00, 14, "6UZBL7H02101J6".toByteArray())))
        // Truncated and empty replies.
        assertEquals("", AircraftSerialProtocol.parse(byteArrayOf(0x00, 0x14)))
        assertEquals("", AircraftSerialProtocol.parse(ByteArray(0)))
        assertEquals("", AircraftSerialProtocol.parse(null))
    }

    @Test
    fun ignoresTrailingBytesBeyondTheDeclaredLength() {
        val serial = "1581FA8JC264600B31QZ"
        val payload = reply(
            0x00,
            serial.length,
            serial.toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00, 0x7F, 0x11)
        )

        assertEquals(serial, AircraftSerialProtocol.parse(payload))
    }

}
