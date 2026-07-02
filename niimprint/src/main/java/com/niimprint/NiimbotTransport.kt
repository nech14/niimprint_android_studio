package com.niimprint

/**
 * Byte transport abstraction used by [PrinterClient].
 *
 * Implement this interface to support another connection type while reusing NIIMBOT packet
 * encoding and print flow.
 */
interface NiimbotTransport {
    /** Writes one encoded NIIMBOT packet. */
    suspend fun writePacket(packet: NiimbotPacket)

    /** Writes raw bytes, which may contain one packet or several concatenated packets. */
    suspend fun writeBytes(data: ByteArray)

    /** Reads raw bytes from the printer, or returns null if no data arrives before [timeoutMs]. */
    suspend fun readBytes(timeoutMs: Long = 1200): ByteArray?
}
