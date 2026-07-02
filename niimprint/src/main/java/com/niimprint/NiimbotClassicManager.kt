package com.niimprint

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Bluetooth Classic RFCOMM transport for NIIMBOT printers.
 *
 * The manager opens a socket to a paired [BluetoothDevice], writes NIIMBOT packets, reads printer
 * responses, and closes the socket. Runtime Bluetooth permissions are the responsibility of the
 * host app.
 */
class NiimbotClassicManager(
    /** Paired Bluetooth device representing the target printer. */
    private val device: BluetoothDevice,
    /** RFCOMM channel used for the first connection attempt. */
    private val channel: Int = 1,
    /** Delay after each write or chunk, in milliseconds. */
    private val writeDelayMs: Long = 3L,
    /** Optional write chunk size. Use 0 to send each byte array as one write. */
    private val chunkSize: Int = 0
) : NiimbotTransport {

    private companion object {
        private const val TAG = "NIIMBOT"
        private val SPP_UUID: UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private val writeLock = Any()

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    /**
     * Opens the Bluetooth socket and prepares input/output streams.
     *
     * The method first tries a direct RFCOMM channel and falls back to the standard SPP UUID.
     */
    @SuppressLint("MissingPermission")
    suspend fun connect(): Unit = withContext(Dispatchers.IO) {
        if (socket?.isConnected == true) {
            return@withContext
        }

        BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
        Log.d(TAG, "Classic connect to ${device.name} ${device.address}, channel=$channel")

        val newSocket = connectLikePythonRfcomm()

        socket = newSocket
        input = newSocket.inputStream
        output = newSocket.outputStream

        Log.d(TAG, "Classic connected")
    }

    @SuppressLint("MissingPermission")
    private fun connectLikePythonRfcomm(): BluetoothSocket {
        try {
            val method = device.javaClass.getMethod(
                "createRfcommSocket",
                Int::class.javaPrimitiveType
            )
            val directSocket = method.invoke(device, channel) as BluetoothSocket
            directSocket.connect()
            Log.d(TAG, "Classic connected via RFCOMM channel $channel")
            return directSocket
        } catch (e: Exception) {
            Log.w(TAG, "RFCOMM channel $channel failed, fallback to SPP UUID", e)
        }

        val fallbackSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
        fallbackSocket.connect()
        Log.d(TAG, "Classic connected via SPP UUID")
        return fallbackSocket
    }

    /** Sends a complete NIIMBOT packet over the socket. */
    override suspend fun writePacket(packet: NiimbotPacket) {
        writeBytes(packet.toBytes())
    }

    /** Writes raw bytes to the socket output stream. */
    override suspend fun writeBytes(data: ByteArray): Unit = withContext(Dispatchers.IO) {
        val stream = output ?: throw IllegalStateException("Printer is not connected")

        Log.d(TAG, "classic send: ${data.toHexString()}")

        synchronized(writeLock) {
            if (chunkSize > 0) {
                var position = 0
                while (position < data.size) {
                    val count = minOf(chunkSize, data.size - position)
                    stream.write(data, position, count)
                    stream.flush()
                    position += count
                    if (writeDelayMs > 0) {
                        Thread.sleep(writeDelayMs)
                    }
                }
            } else {
                stream.write(data, 0, data.size)
                stream.flush()
                if (writeDelayMs > 0) {
                    Thread.sleep(writeDelayMs)
                }
            }
        }
    }

    /**
     * Reads available bytes from the socket input stream until [timeoutMs] expires.
     *
     * Returns null when the printer sends no data in the given timeout.
     */
    override suspend fun readBytes(timeoutMs: Long): ByteArray? = withContext(Dispatchers.IO) {
        val stream = input ?: return@withContext null
        val buffer = ByteArray(1024)

        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val available = stream.available()
            if (available <= 0) {
                Thread.sleep(10)
                continue
            }

            val count = stream.read(buffer, 0, minOf(buffer.size, available))
            if (count > 0) {
                return@withContext buffer.copyOf(count)
            }
        }

        null
    }

    /** Closes input stream, output stream, and socket, ignoring close failures. */
    fun close() {
        try {
            input?.close()
        } catch (_: Exception) {
        }

        try {
            output?.close()
        } catch (_: Exception) {
        }

        try {
            socket?.close()
        } catch (_: Exception) {
        }

        input = null
        output = null
        socket = null
    }

    private fun ByteArray.toHexString(): String =
        joinToString(":") { "%02x".format(it.toInt() and 0xFF) }
}
