package com.example.niimprint_android_kotlin

class NiimbotPacket(
    val code: Int,
    val data: ByteArray = ByteArray(0)
) {
    constructor(requestCode: RequestCode, data: ByteArray = ByteArray(0)) :
            this(requestCode.code, data)

    fun toBytes(): ByteArray {
        require(data.size <= 255) { "Packet payload too large: ${data.size}" }

        val type = code and 0xFF
        val length = data.size
        var checksum = type xor length
        for (byte in data) {
            checksum = checksum xor (byte.toInt() and 0xFF)
        }

        return byteArrayOf(
            0x55,
            0x55,
            type.toByte(),
            length.toByte()
        ) + data + byteArrayOf(
            checksum.toByte(),
            0xAA.toByte(),
            0xAA.toByte()
        )
    }

    companion object {
        fun fromBytes(packet: ByteArray): NiimbotPacket {
            require(packet.size >= 7) { "Packet too short" }
            require(packet[0] == 0x55.toByte() && packet[1] == 0x55.toByte()) {
                "Invalid packet header"
            }
            require(packet[packet.size - 2] == 0xAA.toByte() && packet[packet.size - 1] == 0xAA.toByte()) {
                "Invalid packet footer"
            }

            val type = packet[2].toInt() and 0xFF
            val length = packet[3].toInt() and 0xFF
            require(packet.size == length + 7) { "Invalid packet length" }

            val data = packet.copyOfRange(4, 4 + length)
            var checksum = type xor length
            for (byte in data) {
                checksum = checksum xor (byte.toInt() and 0xFF)
            }

            val packetChecksum = packet[4 + length].toInt() and 0xFF
            require(checksum == packetChecksum) { "Invalid packet checksum" }

            return NiimbotPacket(type, data)
        }
    }
}
