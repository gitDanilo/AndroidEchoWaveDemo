package com.example.echowavedemo

enum class MsgType(val type: Int) {
    READY(0x51),
    RX_REQUEST(0x52),
    STOP(0x53),
    TX_REQUEST(0x54),
    REPLY(0x55),
    RX_REPLY(0x56),
}

enum class ReplyType(val type: Int) {
    OK(0x00),
    BAD_CRC(0x01),
    INVALID_SIZE(0x02),
    INVALID_MSG(0x03),
}

interface MsgData {
    fun toBytes(): ByteArray
    override fun toString(): String
}

data class RcData(
    val code: Int, // 4 bytes int
    val length: Int, // 2 bytes int
    val repeat: Int, // 1 byte int
    // Protocol
    val pulseLength: Int, // 2 bytes int
    val syncFactor: Int, // 2 bytes int
    val one: Int, // 2 bytes int
    val zero: Int, // 2 bytes int
    val inverted: Boolean, // 1 byte bool
) : MsgData {
    companion object {
        fun fromBytes(bytes: ByteArray): RcData? {
            if (bytes.size < 16) return null

            val code = bytes[0].toInt() and 0xFF or
                    ((bytes[1].toInt() and 0xFF) shl 8) or
                    ((bytes[2].toInt() and 0xFF) shl 16) or
                    ((bytes[3].toInt() and 0xFF) shl 24)

            val length = bytes[4].toInt() and 0xFF or
                    ((bytes[5].toInt() and 0xFF) shl 8)

            val repeat = bytes[6].toInt() and 0xFF

            val pulseLength = bytes[7].toInt() and 0xFF or
                    ((bytes[8].toInt() and 0xFF) shl 8)

            val syncFactor = bytes[9].toInt() and 0xFF or
                    ((bytes[10].toInt() and 0xFF) shl 8)

            val one = bytes[11].toInt() and 0xFF or
                    ((bytes[12].toInt() and 0xFF) shl 8)

            val zero = bytes[13].toInt() and 0xFF or
                    ((bytes[14].toInt() and 0xFF) shl 8)

            val inverted = bytes[15].toInt() != 0

            return RcData(
                code = code,
                length = length,
                repeat = repeat,
                pulseLength = pulseLength,
                syncFactor = syncFactor,
                one = one,
                zero = zero,
                inverted = inverted,
            )
        }
    }

    override fun toBytes(): ByteArray {
        val result = ByteArray(16)
        // code (4 bytes)
        result[0] = (code and 0xFF).toByte()
        result[1] = ((code shr 8) and 0xFF).toByte()
        result[2] = ((code shr 16) and 0xFF).toByte()
        result[3] = ((code shr 24) and 0xFF).toByte()

        // length (2 bytes)
        result[4] = (length and 0xFF).toByte()
        result[5] = ((length shr 8) and 0xFF).toByte()

        // repeat (1 byte)
        result[6] = (repeat and 0xFF).toByte()

        // pulseLength (2 bytes)
        result[7] = (pulseLength and 0xFF).toByte()
        result[8] = ((pulseLength shr 8) and 0xFF).toByte()

        // syncFactor (2 bytes)
        result[9] = (syncFactor and 0xFF).toByte()
        result[10] = ((syncFactor shr 8) and 0xFF).toByte()

        // one (2 bytes)
        result[11] = (one and 0xFF).toByte()
        result[12] = ((one shr 8) and 0xFF).toByte()

        // zero (2 bytes)
        result[13] = (zero and 0xFF).toByte()
        result[14] = ((zero shr 8) and 0xFF).toByte()

        // inverted (1 byte)
        result[15] = if (inverted) 1.toByte() else 0.toByte()
        return result
    }

    override fun toString(): String =
        "RcData(code=$code, length=$length, repeat=$repeat, pulseLength=$pulseLength, syncFactor=$syncFactor, one=$one, zero=$zero, inverted=$inverted)"
}

data class ReplyData(
    val type: ReplyType,
) : MsgData {
    companion object {
        fun fromBytes(bytes: ByteArray): ReplyData? {
            if (bytes.isEmpty()) return null

            val value = bytes[0].toInt() and 0xFF
            val type = ReplyType.entries.find { it.type == value } ?: return null

            return ReplyData(
                type = type,
            )
        }
    }

    override fun toBytes(): ByteArray = byteArrayOf(type.type.toByte())

    override fun toString(): String = "ReplyData(type=${type.name})"
}

data class Msg(
    val type: MsgType,
    val data: MsgData? = null,
    val crc: Int? = null,
) {
    companion object {
        fun fromBytes(bytes: ByteArray): Msg? {
            if (bytes.size < Constants.MSG_SIZE) return null

            val value = bytes[0].toInt() and 0xFF
            val type = MsgType.entries.find { it.type == value } ?: return null

            val data: MsgData? = when (type) {
                MsgType.RX_REPLY, MsgType.TX_REQUEST -> {
                    val bytes = bytes.copyOfRange(1, bytes.size - 1)
                    RcData.fromBytes(bytes)
                }
                MsgType.REPLY -> {
                    val bytes = bytes.copyOfRange(1, bytes.size - 1)
                    ReplyData.fromBytes(bytes)
                }
                else -> null
            }

            val crc = bytes[Constants.MSG_SIZE - 1].toInt() and 0xFF

            return Msg(
                type = type,
                data = data,
                crc = crc,
            )
        }
    }

    fun toBytes(): ByteArray {
        val result = ByteArray(Constants.MSG_SIZE)

        result[0] = (type.type and 0xFF).toByte()

        when (data) {
            is RcData -> {
                val bytes = data.toBytes()
                bytes.copyInto(result, 1, 0, bytes.size)
            }
            is ReplyData -> {
                val bytes = data.toBytes()
                bytes.copyInto(result, 1, 0, bytes.size)
                val padding = ByteArray(16 - bytes.size) { 0 }
                padding.copyInto(result, 1 + bytes.size, 0, padding.size)
            }
            else -> {
                val padding = ByteArray(16) { 0 }
                padding.copyInto(result, 1, 0, padding.size)
            }
        }

        val data = result.slice(0 until Constants.MSG_SIZE - 1).toByteArray()
        val crc = data.crc8()

        result[Constants.MSG_SIZE - 1] = crc.toByte()

        return result
    }

    fun checkCrc(): Boolean {
        val value = crc ?: return false
        val crc = toBytes().slice(0 until Constants.MSG_SIZE - 1).toByteArray().crc8()
        return value == crc
    }

    override fun toString(): String = "Msg(type=${type.name}, data=$data, crc=$crc)"
}
