package com.niimprint

import android.bluetooth.BluetoothDevice
import android.graphics.Bitmap

/**
 * High-level printer facade for the common Bluetooth Classic workflow.
 *
 * Use this class when the app already has a paired [BluetoothDevice] and only needs to connect,
 * print a bitmap, and close the connection. Runtime Bluetooth permissions must be requested by
 * the host app before calling [connect] or [printImage].
 */
class NiimprintPrinter(
    /** Paired Bluetooth printer device selected by the host app. */
    device: BluetoothDevice,
    /** RFCOMM channel used for the first connection attempt. */
    channel: Int = 1,
    /** Delay after writes, in milliseconds, used to avoid overwhelming the printer. */
    writeDelayMs: Long = 3L,
    /** Optional write chunk size. Use 0 to write complete packets at once. */
    chunkSize: Int = 0
) {
    private val transport = NiimbotClassicManager(
        device = device,
        channel = channel,
        writeDelayMs = writeDelayMs,
        chunkSize = chunkSize
    )
    private val client = PrinterClient(transport)

    /**
     * Opens the Bluetooth Classic connection to the printer.
     *
     * This is a suspend function and should be called from a background coroutine.
     */
    suspend fun connect() {
        transport.connect()
    }

    /**
     * Prints [bitmap] using the provided image preparation and print settings.
     *
     * The bitmap is rotated, scaled to fit [PrintOptions.targetWidth] x
     * [PrintOptions.targetHeight], aligned on a white canvas, encoded into NIIMBOT raster
     * packets, and sent to the connected printer.
     */
    suspend fun printImage(
        bitmap: Bitmap,
        options: PrintOptions = PrintOptions()
    ) {
        client.printImage(bitmap, options)
    }

    /**
     * Closes streams and the Bluetooth socket.
     *
     * It is safe to call this from a finally block after failed or successful printing.
     */
    fun close() {
        transport.close()
    }
}
