package com.freefcc.app

enum class LedState {
    UNKNOWN,
    OFF,
    ON,
    PARTIAL
}

data class LedReadback(
    val state: LedState,
    val rawValue: Int
)

internal object LedReadbackProtocol {
    private val parameterHash = byteArrayOf(
        0xA2.toByte(),
        0x59,
        0xCE.toByte(),
        0xED.toByte()
    )

    fun buildRequest(builder: DumlBuilder = DumlBuilder()): ByteArray =
        builder.buildFrame(
            DumlFrame(
                sender = 0x02,
                cmdType = 0x40,
                cmdSet = 0x03,
                cmdId = 0xF8,
                dst = 0x03,
                payload = parameterHash
            )
        )

    fun parse(payload: ByteArray?): LedReadback? {
        if (payload == null || payload.size != 6 || payload[0] != 0.toByte()) return null
        if (!payload.copyOfRange(1, 5).contentEquals(parameterHash)) return null

        val value = payload[5].toInt() and 0xFF
        val state = when (value) {
            0x00 -> LedState.OFF
            0xEF -> LedState.ON
            else -> LedState.PARTIAL
        }
        return LedReadback(state, value)
    }
}
