package com.freefcc.app

import java.util.Locale

internal data class FccCountryRegionResult(
    val targetCountry: String,
    val initialCountry: String?,
    val writeAttempts: Int,
    val writeCompleted: Boolean,
    val writeAckMatched: Boolean,
    val readCompleted: Boolean,
    val readAckMatched: Boolean,
    val observedCountry: String?
) {
    val verified: Boolean
        get() = observedCountry == targetCountry

    /** True when the controller already reported the target country. */
    val skippedWrite: Boolean
        get() = writeAttempts == 0
}

internal object FccCountryRegion {
    const val MAX_ATTEMPTS = 3

    private const val SENDER = 0x2A
    private const val DESTINATION = 0x09
    private const val COMMAND_TYPE = 0x40
    private const val COMMAND_SET = 0x07
    private const val WRITE_COMMAND_ID = 0x30
    private const val READ_COMMAND_ID = 0x19
    private const val READ_WINDOW_MS = 500

    /**
     * Reads the current country first and writes only when it differs from
     * [targetCountry]. A write is repeated while the readback still reports a
     * different country, up to [MAX_ATTEMPTS] times, so an unreachable
     * controller is reported instead of being retried forever.
     */
    fun ensure(
        transport: DumlTransport,
        port: Int,
        targetCountry: String = FccRegion.DEFAULT.countryCode,
        shouldContinue: () -> Boolean = { true }
    ): FccCountryRegionResult = executeEnsure(targetCountry, shouldContinue) { frame, readWindowMs ->
        transport.sendAndReceiveRaw(
            frame = frame,
            readWindowMs = readWindowMs,
            port = port,
            autoDetectPort = port == DumlTransport.PORT
        )
    }

    internal fun executeEnsure(
        targetCountry: String = FccRegion.DEFAULT.countryCode,
        shouldContinue: () -> Boolean = { true },
        exchange: (frame: ByteArray, readWindowMs: Int) -> DumlRawExchange
    ): FccCountryRegionResult {
        require(targetCountry.length == 2 && targetCountry.all { it in 'A'..'Z' }) {
            "Country code must be two uppercase ASCII letters"
        }
        val firstRead = readCountry(exchange)
        val initialCountry = parseReadback(firstRead.validatedPayload)
        if (initialCountry == targetCountry) {
            return FccCountryRegionResult(
                targetCountry = targetCountry,
                initialCountry = initialCountry,
                writeAttempts = 0,
                writeCompleted = false,
                writeAckMatched = false,
                readCompleted = firstRead.writeCompleted,
                readAckMatched = firstRead.matchedFrame != null,
                observedCountry = initialCountry
            )
        }
        var result = writeThenRead(exchange, targetCountry, initialCountry, attempt = 1)
        while (!result.verified && result.writeAttempts < MAX_ATTEMPTS && shouldContinue()) {
            result = writeThenRead(
                exchange,
                targetCountry,
                initialCountry,
                attempt = result.writeAttempts + 1
            )
        }
        return result
    }

    private fun writeThenRead(
        exchange: (frame: ByteArray, readWindowMs: Int) -> DumlRawExchange,
        targetCountry: String,
        initialCountry: String?,
        attempt: Int
    ): FccCountryRegionResult {
        val writeExchange = exchange(
            DumlBuilder().buildFrame(
                DumlFrame(
                    sender = SENDER,
                    cmdType = COMMAND_TYPE,
                    cmdSet = COMMAND_SET,
                    cmdId = WRITE_COMMAND_ID,
                    dst = DESTINATION,
                    payload = buildWritePayload(targetCountry)
                )
            ),
            READ_WINDOW_MS
        )
        val readExchange = readCountry(exchange)
        return FccCountryRegionResult(
            targetCountry = targetCountry,
            initialCountry = initialCountry,
            writeAttempts = attempt,
            writeCompleted = writeExchange.writeCompleted,
            writeAckMatched = writeExchange.matchedFrame != null,
            readCompleted = readExchange.writeCompleted,
            readAckMatched = readExchange.matchedFrame != null,
            observedCountry = parseReadback(readExchange.validatedPayload)
        )
    }

    private fun buildWritePayload(targetCountry: String): ByteArray {
        val country = targetCountry.toByteArray(Charsets.US_ASCII)
        return byteArrayOf(
            country[0], country[1], 0x00, 0x00,
            country[0], country[1], 0x00, 0x00,
            0x01, 0x00
        )
    }

    private fun readCountry(
        exchange: (frame: ByteArray, readWindowMs: Int) -> DumlRawExchange
    ): DumlRawExchange = exchange(
        DumlBuilder().buildFrame(
            DumlFrame(
                sender = SENDER,
                cmdType = COMMAND_TYPE,
                cmdSet = COMMAND_SET,
                cmdId = READ_COMMAND_ID,
                dst = DESTINATION,
                payload = byteArrayOf()
            )
        ),
        READ_WINDOW_MS
    )

    internal fun parseReadback(payload: ByteArray?): String? {
        if (payload == null) return null
        val offset = when {
            payload.size >= 3 && payload[0] == 0.toByte() -> 1
            payload.size >= 2 -> 0
            else -> return null
        }
        val first = payload[offset].toInt() and 0xFF
        val second = payload[offset + 1].toInt() and 0xFF
        if (first !in 'A'.code..'Z'.code || second !in 'A'.code..'Z'.code) return null
        return byteArrayOf(first.toByte(), second.toByte())
            .toString(Charsets.US_ASCII)
            .uppercase(Locale.US)
    }
}
