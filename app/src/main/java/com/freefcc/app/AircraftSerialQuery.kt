package com.freefcc.app

/**
 * Asks the aircraft for its serial instead of waiting for one to appear.
 *
 * The passive probe holds `40007` open for the whole listen window and hopes
 * the identity broadcast lands inside it. That window is shared with the FPV
 * mirror, so holding it costs the DJI Fly link, and a miss costs the full
 * window again. `00:51` replaces the wait with one request and one reply: the
 * socket closes as soon as the answer arrives.
 *
 * Confirmed live on 2026-08-08 against Avata 360 (`wa530`) through the RC 2
 * proxy on `40007`. The payload is a single byte selecting which identity
 * field to return; the observed fields were:
 *
 * | selector | reply |
 * |---|---|
 * | `0x04`, `0x06` | aircraft serial, `00 14 00` + 20 ASCII bytes |
 * | `0x01` | serial behind four extra bytes (`00 16 20 08`) |
 * | `0x0c`, `0x0d` | 16-byte device id |
 * | `0x00`, `0xff` | status `0xfd`, no field |
 *
 * Only `0x04` is asked for. It answers in both shapes — measured live on the
 * same aircraft — so the serial is found inside the field rather than assumed
 * to be the whole of it.
 */
internal object AircraftSerialProtocol {

    /** Payload selector that returns the bare aircraft serial. */
    const val FIELD_AIRCRAFT_SERIAL = 0x04

    /** Reply header: `status:u8` then the field length as `u16` little-endian. */
    private const val HEADER_SIZE = 3
    private const val STATUS_OK = 0

    fun buildRequest(
        field: Int = FIELD_AIRCRAFT_SERIAL,
        builder: DumlBuilder = DumlBuilder()
    ): ByteArray = builder.buildFrame(
        DumlFrame(
            sender = 0x02,
            cmdType = 0x40,
            cmdSet = 0x00,
            cmdId = 0x51,
            dst = 0x03,
            payload = byteArrayOf(field.toByte())
        )
    )

    /**
     * Returns the serial carried by a `00:51` reply, or an empty string when the
     * reply is absent, refuses the field, or does not hold a full factory
     * serial. A short or unrecognised value is dropped rather than stored:
     * a wrong serial is worse than no serial, because it is cached and reported.
     */
    fun parse(payload: ByteArray?): String {
        if (payload == null || payload.size < HEADER_SIZE) return ""
        if ((payload[0].toInt() and 0xFF) != STATUS_OK) return ""

        val declaredLength = (payload[1].toInt() and 0xFF) or ((payload[2].toInt() and 0xFF) shl 8)
        if (declaredLength <= 0 || HEADER_SIZE + declaredLength > payload.size) return ""

        // Observed live on Avata 360: the same request comes back either as
        // the bare serial or behind four bytes (`00 16 20 08`). Both carry the
        // same aircraft, so the serial is found inside the declared field
        // rather than required to be the whole of it. ISO-8859-1 keeps the
        // non-text bytes one-to-one so they cannot merge into the match.
        val field = String(payload, HEADER_SIZE, declaredLength, Charsets.ISO_8859_1)
        return DumlTransport.findFullAircraftSerial(field).orEmpty()
    }
}

/** Runs the `00:51` serial query over the LED port with bounded retries. */
internal object AircraftSerialQueryRunner {

    /** Для адресного запроса каждый раз читаем текущий борт, без кэша S/N. */
    fun readCurrent(
        query: () -> String,
        passive: () -> String,
        acceptsPassive: (String) -> Boolean
    ): String = query().ifEmpty { passive().takeIf(acceptsPassive).orEmpty() }

    // One request is often lost on 40007 — the port carries the FPV mirror, and
    // a live run answered four times out of six. Retries are cheap because a
    // successful exchange returns as soon as the reply is matched, unlike the
    // passive probe which always waits out its whole window.
    const val DEFAULT_ATTEMPTS = 3

    /**
     * The link-session probe gets two attempts because a single request is
     * answered only about a third of the time. Success returns immediately;
     * a miss is not retried until a real disconnect/reconnect.
     */
    const val LINK_SESSION_ATTEMPTS = 2

    private const val READ_WINDOW_MS = 600

    fun read(
        transport: DumlTransport = DumlTransport(),
        attempts: Int = DEFAULT_ATTEMPTS
    ): String {
        repeat(attempts) {
            val request = AircraftSerialProtocol.buildRequest()
            val exchange = transport.sendAndReceiveRaw(
                frame = request,
                wireFrame = Profiles.wrapFrame(request),
                readWindowMs = READ_WINDOW_MS,
                port = DumlTransport.PORT_LED,
                autoDetectPort = false
            )
            val serial = AircraftSerialProtocol.parse(exchange.validatedPayload)
            if (serial.isNotEmpty()) return serial
        }
        return ""
    }
}
