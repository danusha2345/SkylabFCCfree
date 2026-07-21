package com.freefcc.app

enum class GpsState {
    UNKNOWN,
    OFF,
    ON,
    UNEXPECTED
}

data class GpsReadback(
    val state: GpsState,
    val rawValue: Int
)

/** Hash-based access to the aircraft master `g_config.gps_cfg.gps_enable` parameter. */
internal object GpsControlProtocol {
    // Stable DJI parameter hash 0xC5429582, encoded little-endian on the wire.
    // Read-only 03:F8 was verified live through the RC2 port-40007 proxy.
    private val parameterHash = byteArrayOf(
        0x82.toByte(),
        0x95.toByte(),
        0x42,
        0xC5.toByte()
    )

    fun buildReadRequest(builder: DumlBuilder = DumlBuilder()): ByteArray =
        buildRequest(builder, commandId = 0xF8, value = null)

    fun buildWriteRequest(enabled: Boolean, builder: DumlBuilder = DumlBuilder()): ByteArray =
        buildRequest(builder, commandId = 0xF9, value = if (enabled) 1 else 0)

    fun parse(payload: ByteArray?): GpsReadback? {
        if (payload == null || payload.size != 6 || payload[0] != 0.toByte()) return null
        if (!payload.copyOfRange(1, 5).contentEquals(parameterHash)) return null

        val value = payload[5].toInt() and 0xFF
        val state = when (value) {
            0 -> GpsState.OFF
            1 -> GpsState.ON
            else -> GpsState.UNEXPECTED
        }
        return GpsReadback(state, value)
    }

    private fun buildRequest(builder: DumlBuilder, commandId: Int, value: Int?): ByteArray {
        val payload = if (value == null) {
            parameterHash.copyOf()
        } else {
            parameterHash + value.toByte()
        }
        return builder.buildFrame(
            DumlFrame(
                sender = 0x02,
                cmdType = 0x40,
                cmdSet = 0x03,
                cmdId = commandId,
                dst = 0x03,
                payload = payload
            )
        )
    }
}
