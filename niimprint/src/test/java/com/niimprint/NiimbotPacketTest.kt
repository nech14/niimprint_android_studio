package com.niimprint

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class NiimbotPacketTest {
    @Test
    fun toBytesAddsHeaderFooterLengthAndChecksum() {
        val packet = NiimbotPacket(0x21, byteArrayOf(0x05))

        assertArrayEquals(
            byteArrayOf(
                0x55,
                0x55,
                0x21,
                0x01,
                0x05,
                0x25,
                0xAA.toByte(),
                0xAA.toByte()
            ),
            packet.toBytes()
        )
    }

    @Test
    fun fromBytesParsesValidPacket() {
        val parsed = NiimbotPacket.fromBytes(
            byteArrayOf(
                0x55,
                0x55,
                0x13,
                0x02,
                0x01,
                0x40,
                0x50,
                0xAA.toByte(),
                0xAA.toByte()
            )
        )

        assertEquals(0x13, parsed.code)
        assertArrayEquals(byteArrayOf(0x01, 0x40), parsed.data)
    }
}
