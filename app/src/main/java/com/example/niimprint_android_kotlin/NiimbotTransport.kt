package com.example.niimprint_android_kotlin

interface NiimbotTransport {
    suspend fun writePacket(packet: NiimbotPacket)
    suspend fun writeBytes(data: ByteArray)
    suspend fun readBytes(timeoutMs: Long = 1200): ByteArray?
}