package com.niimprint

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.delay
import kotlin.math.ceil

/**
 * Lower-level print client that sends NIIMBOT protocol commands through a [NiimbotTransport].
 *
 * Most apps should use [NiimprintPrinter]. Use this class directly when you provide your own
 * transport implementation or need tighter control over the connection lifecycle.
 */
class PrinterClient(
    /** Transport used to write command packets and read printer responses. */
    private val transport: NiimbotTransport
) {
    private companion object {
        private const val TAG = "NIIMBOT"
        private const val BITMAP_BATCH_SIZE = 5
        private const val RASTER_START_DELAY_MS = 100L
        private const val RASTER_LINE_DELAY_MS = 3L
    }

    private val packetBuffer = mutableListOf<Byte>()

    /**
     * Prints [bitmap] with default image preparation settings and a custom [density].
     *
     * This overload is kept for simple integrations. Use [printImage] with [PrintOptions] when
     * you need to set canvas size, alignment, or rotation.
     */
    suspend fun printImage(bitmap: Bitmap, density: Int = 5) {
        printImage(bitmap, PrintOptions(density = density))
    }

    /**
     * Prints [bitmap] using explicit [options].
     *
     * The method performs printer initialization, prepares the bitmap, sends raster packets, waits
     * for raster acknowledgement/status, and finalizes the print job.
     */
    suspend fun printImage(bitmap: Bitmap, options: PrintOptions = PrintOptions()) {
        val image = prepareBitmapForPrint(bitmap, options)

        connectPrinter()
        setLabelType(1)
        setLabelDensity(options.density)

        startPrint()
        getPrintStatus()
        startPagePrint()

        Log.d(TAG, "Setting dimensions: ${image.height}x${image.width}")
        setDimension(image.height, image.width)
        delay(RASTER_START_DELAY_MS)

        val packets = encodeImage(image)
        logRasterSummary(image, packets)

        val batch = mutableListOf<NiimbotPacket>()
        var batchIndex = 0

        for (packet in packets) {
            if (packet.code != 0x85) {
                if (batch.isNotEmpty()) {
                    logBatchInfo(batchIndex, batch, "BEFORE_SEND")
                    sendBatch(batch)
                    logBatchInfo(batchIndex, batch, "AFTER_SEND")
                    batchIndex++

                    batch.clear()
                    delay(RASTER_LINE_DELAY_MS)
                }

                Log.d(
                    TAG,
                    "SPECIAL packet code=${packet.code}, y=${packetStartY(packet)}..${packetEndY(packet)}, t=${System.currentTimeMillis()}"
                )

                send(packet)
                delay(RASTER_LINE_DELAY_MS)
                continue
            }

            batch += packet

            if (batch.size >= BITMAP_BATCH_SIZE) {
                logBatchInfo(batchIndex, batch, "BEFORE_SEND")
                sendBatch(batch)
                logBatchInfo(batchIndex, batch, "AFTER_SEND")
                batchIndex++

                batch.clear()
                delay(RASTER_LINE_DELAY_MS)
            }
        }

        if (batch.isNotEmpty()) {
            logBatchInfo(batchIndex, batch, "BEFORE_FINAL_SEND")
            sendBatch(batch)
            logBatchInfo(batchIndex, batch, "AFTER_FINAL_SEND")

            delay(RASTER_LINE_DELAY_MS)
        }

        val lastY = packets.mapNotNull { packetEndY(it) }.maxOrNull() ?: image.height - 1

        Log.d(TAG, "WAIT_RASTER_ACK_BEFORE_END_PAGE expectedLastY=$lastY")

        val ackOk = waitRasterAck(lastY, 2500)

        Log.d(TAG, "WAIT_RASTER_ACK_BEFORE_END_PAGE result=$ackOk")

        delay(200)

        Log.d(TAG, "BEFORE_END_PAGE_PRINT t=${System.currentTimeMillis()}")

        endPagePrint()

        Log.d(TAG, "AFTER_END_PAGE_PRINT t=${System.currentTimeMillis()}")

        delay(1000)

        val status = waitPrintStatusDone(3000)
        Log.d(TAG, "Status: $status")

        delay(1000)
        while (!endPrint()) {
            delay(100)
        }
    }

    private suspend fun waitPrintStatusDone(timeoutMs: Long = 3000): Map<String, Int> {
        val start = System.currentTimeMillis()
        var lastStatus = mapOf("page" to 0, "progress1" to 0, "progress2" to 0)

        while (System.currentTimeMillis() - start < timeoutMs) {
            val status = getPrintStatus()
            lastStatus = status

            Log.d(TAG, "WAIT_STATUS status=$status t=${System.currentTimeMillis()}")

            val page = status["page"] ?: 0
            val progress1 = status["progress1"] ?: 0
            val progress2 = status["progress2"] ?: 0

            if (page > 0 && progress1 == 100 && progress2 == 100) {
                return status
            }

            delay(300)
        }

        return lastStatus
    }

    private suspend fun waitRasterAck(
        expectedLastY: Int,
        timeoutMs: Long = 2500
    ): Boolean {
        val start = System.currentTimeMillis()
        var maxAckY = -1

        while (System.currentTimeMillis() - start < timeoutMs) {
            val bytes = transport.readBytes(100)

            if (bytes == null || bytes.isEmpty()) {
                continue
            }

            val packets = onReceive(bytes)

            for (packet in packets) {
                if (packet.code == 0xD3 && packet.data.size >= 3) {
                    val y = ((packet.data[0].toInt() and 0xFF) shl 8) or
                            (packet.data[1].toInt() and 0xFF)

                    maxAckY = maxOf(maxAckY, y)

                    Log.d(
                        TAG,
                        "RASTER_ACK y=$y maxAckY=$maxAckY expected=$expectedLastY t=${System.currentTimeMillis()}"
                    )

                    if (maxAckY >= expectedLastY) {
                        return true
                    }
                } else {
                    Log.d(
                        TAG,
                        "WAIT_RASTER_ACK got non-raster packet code=${packet.code} data=${packet.data.toHexString()}"
                    )
                }
            }
        }

        Log.w(
            TAG,
            "RASTER_ACK_TIMEOUT maxAckY=$maxAckY expected=$expectedLastY"
        )

        return false
    }

    private fun prepareBitmapForPrint(
        bitmap: Bitmap,
        options: PrintOptions
    ): Bitmap {
        val rotated = rotateBitmap(bitmap, options.rotation)
        val result = Bitmap.createBitmap(
            options.targetWidth,
            options.targetHeight,
            Bitmap.Config.ARGB_8888
        )

        val canvas = android.graphics.Canvas(result)
        canvas.drawColor(android.graphics.Color.WHITE)

        val scale = minOf(
            options.targetWidth.toFloat() / rotated.width,
            options.targetHeight.toFloat() / rotated.height
        )

        val scaledWidth = (rotated.width * scale).toInt().coerceAtLeast(1)
        val scaledHeight = (rotated.height * scale).toInt().coerceAtLeast(1)

        val left = when (options.horizontalAlignment) {
            HorizontalAlignment.LEFT -> 0
            HorizontalAlignment.CENTER -> (options.targetWidth - scaledWidth) / 2
            HorizontalAlignment.RIGHT -> options.targetWidth - scaledWidth
        }

        val top = when (options.verticalAlignment) {
            VerticalAlignment.TOP -> 0
            VerticalAlignment.CENTER -> (options.targetHeight - scaledHeight) / 2
            VerticalAlignment.BOTTOM -> options.targetHeight - scaledHeight
        }

        val destRect = Rect(
            left,
            top,
            left + scaledWidth,
            top + scaledHeight
        )

        canvas.drawBitmap(rotated, null, destRect, null)

        return result
    }

    private fun rotateBitmap(source: Bitmap, rotation: ImageRotation): Bitmap {
        if (rotation == ImageRotation.NONE) {
            return source
        }

        val matrix = Matrix()
        matrix.postRotate(rotation.degrees)
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private suspend fun connectPrinter() {
        val packet = byteArrayOf(0x03) + NiimbotPacket(0xC1, byteArrayOf(0x01)).toBytes()
        log("send_raw", packet)
        transport.writeBytes(packet)
        delay(200)
    }

    private suspend fun setLabelType(value: Int) {
        transceive(RequestCode.SET_LABEL_TYPE, byteArrayOf(value.toByte()), responseOffset = 16)
    }

    private suspend fun setLabelDensity(value: Int) {
        transceive(RequestCode.SET_LABEL_DENSITY, byteArrayOf(value.toByte()), responseOffset = 16)
    }

    private suspend fun startPrint() {
        transceive(RequestCode.START_PRINT, byteArrayOf(0x00, 0x01))
    }

    private suspend fun startPagePrint() {
        transceive(RequestCode.START_PAGE_PRINT, byteArrayOf(0x01))
    }

    private suspend fun endPagePrint() {
        transceive(RequestCode.END_PAGE_PRINT, byteArrayOf(0x01))
    }

    private suspend fun endPrint(): Boolean {
        val packet = transceive(RequestCode.END_PRINT, byteArrayOf(0x01))
        return packet?.data?.firstOrNull()?.toInt() != 0
    }

    private suspend fun setDimension(width: Int, height: Int) {
        val data = byteArrayOf(
            ((width shr 8) and 0xFF).toByte(),
            (width and 0xFF).toByte(),
            ((height shr 8) and 0xFF).toByte(),
            (height and 0xFF).toByte(),
            0x00,
            0x01
        )
        transceive(RequestCode.SET_DIMENSION, data)
    }

    private suspend fun getPrintStatus(): Map<String, Int> {
        val packet =
            transceive(RequestCode.GET_PRINT_STATUS, byteArrayOf(0x01), responseOffset = 16)
        if (packet == null || packet.data.size < 4) {
            return mapOf("page" to 0, "progress1" to 0, "progress2" to 0)
        }

        val page = ((packet.data[0].toInt() and 0xFF) shl 8) or
                (packet.data[1].toInt() and 0xFF)
        val progress1 = packet.data[2].toInt() and 0xFF
        val progress2 = packet.data[3].toInt() and 0xFF

        return mapOf("page" to page, "progress1" to progress1, "progress2" to progress2)
    }

    private suspend fun transceive(
        requestCode: RequestCode,
        data: ByteArray,
        responseOffset: Int = 1
    ): NiimbotPacket? {
        val responseCode = (requestCode.code + responseOffset) and 0xFF
        val packet = NiimbotPacket(requestCode, data)
        log("send", packet.toBytes())
        transport.writePacket(packet)

        repeat(6) {
            val bytes = transport.readBytes(100)
            if (bytes != null && bytes.isNotEmpty()) {
                val packets = onReceive(bytes)
                for (response in packets) {
                    if (response.code == 219) {
                        throw RuntimeException("Printer error")
                    }
                    if (response.code == responseCode) {
                        return response
                    }
                }
            }
            delay(100)
        }

        Log.w(TAG, "No response for ${requestCode.name}, expected=$responseCode")
        return null
    }

    private suspend fun send(packet: NiimbotPacket) {
        log("send", packet.toBytes())
        transport.writePacket(packet)
    }

    private suspend fun sendBatch(packets: List<NiimbotPacket>) {
        if (packets.size == 1) {
            send(packets[0])
            return
        }

        var data = ByteArray(0)
        for (packet in packets) {
            val bytes = packet.toBytes()
            log("send", bytes)
            data += bytes
        }

        transport.writeBytes(data)
    }

    private fun onReceive(bytes: ByteArray): List<NiimbotPacket> {
        val packets = mutableListOf<NiimbotPacket>()
        packetBuffer.addAll(bytes.toList())

        while (packetBuffer.size > 4) {
            while (packetBuffer.size >= 2 &&
                (packetBuffer[0] != 0x55.toByte() || packetBuffer[1] != 0x55.toByte())
            ) {
                packetBuffer.removeAt(0)
            }

            if (packetBuffer.size <= 4) {
                break
            }

            val packetLength = (packetBuffer[3].toInt() and 0xFF) + 7
            if (packetBuffer.size < packetLength) {
                break
            }

            val raw = packetBuffer.take(packetLength).toByteArray()
            packetBuffer.subList(0, packetLength).clear()

            try {
                val packet = NiimbotPacket.fromBytes(raw)
                log("recv", raw)
                packets += packet
            } catch (e: Exception) {
                Log.e(TAG, "Bad packet: ${raw.toHexString()}", e)
            }
        }

        return packets
    }

    private fun encodeImage(image: Bitmap): List<NiimbotPacket> {
        val packets = mutableListOf<NiimbotPacket>()

        var pendingY: Int? = null
        var pendingLine: ByteArray? = null
        var repeat = 0
        var blankY: Int? = null
        var blankCount = 0

        fun flushPending() {
            val line = pendingLine ?: return
            val y = pendingY ?: return
            packets += encodeLinePacket(y, line, repeat)
            pendingY = null
            pendingLine = null
            repeat = 0
        }

        fun flushBlank() {
            var y = blankY ?: return
            var count = blankCount
            while (count > 0) {
                val chunk = minOf(count, 255)
                packets += NiimbotPacket(
                    0x84,
                    byteArrayOf(
                        ((y shr 8) and 0xFF).toByte(),
                        (y and 0xFF).toByte(),
                        (chunk and 0xFF).toByte()
                    )
                )
                y += chunk
                count -= chunk
            }
            blankY = null
            blankCount = 0
        }

        for (y in 0 until image.height) {
            val line = extractLine(image, y)

            if (countBits(line) == 0) {
                flushPending()
                if (blankY == null) {
                    blankY = y
                }
                blankCount += 1
                continue
            }

            if (blankCount > 0) {
                flushBlank()
            }

            val current = pendingLine
            if (current != null && current.contentEquals(line) && repeat < 255) {
                repeat += 1
                continue
            }

            flushPending()
            pendingY = y
            pendingLine = line
            repeat = 1
        }

        flushPending()
        if (blankCount > 0) {
            flushBlank()
        }

        return packets
    }

    private fun encodeLinePacket(y: Int, lineData: ByteArray, repeat: Int): NiimbotPacket {
        val counts = bitmapCounts(lineData)
        val header = byteArrayOf(
            ((y shr 8) and 0xFF).toByte(),
            (y and 0xFF).toByte(),
            counts[0].toByte(),
            counts[1].toByte(),
            counts[2].toByte(),
            (repeat and 0xFF).toByte()
        )
        return NiimbotPacket(0x85, header + lineData)
    }

    private fun extractLine(image: Bitmap, y: Int): ByteArray {
        val width = image.width
        val row = ByteArray(ceil(width / 8.0).toInt())

        for (x in 0 until width) {
            val pixel = image.getPixel(x, y)
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val gray = (r + g + b) / 3

            if (gray < 128) {
                val byteIndex = x / 8
                val bitIndex = 7 - (x % 8)
                row[byteIndex] = (row[byteIndex].toInt() or (1 shl bitIndex)).toByte()
            }
        }

        return row
    }

    private fun bitmapCounts(lineData: ByteArray): IntArray =
        intArrayOf(
            countBits(lineData, 0, 16),
            countBits(lineData, 16, 32),
            countBits(lineData, 32, 48)
        )

    private fun countBits(data: ByteArray): Int {
        var count = 0
        for (byte in data) {
            count += Integer.bitCount(byte.toInt() and 0xFF)
        }
        return count
    }

    private fun countBits(data: ByteArray, start: Int, end: Int): Int {
        var count = 0
        for (index in start until minOf(end, data.size)) {
            count += Integer.bitCount(data[index].toInt() and 0xFF)
        }
        return count
    }

    private fun logRasterSummary(image: Bitmap, packets: List<NiimbotPacket>) {
        var blankPackets = 0
        var bitmapPackets = 0
        var lastBitmapY = -1
        var lastPacketEndY = -1

        for (packet in packets) {
            val y = packetStartY(packet) ?: continue
            val endY = packetEndY(packet) ?: continue
            lastPacketEndY = maxOf(lastPacketEndY, endY)
            if (packet.code == 0x84) {
                blankPackets += 1
            } else if (packet.code == 0x85) {
                bitmapPackets += 1
                lastBitmapY = maxOf(lastBitmapY, endY)
            }
            if (y !in 0 until image.height || endY !in 0 until image.height) {
                throw IllegalStateException("Raster packet outside image: ${packet.code}, $y..$endY")
            }
        }

        Log.d(
            TAG,
            "Raster verify: image=${image.width}x${image.height}, packets=${packets.size}, " +
                    "bitmapPackets=$bitmapPackets, blankPackets=$blankPackets, " +
                    "lastPacketY=$lastPacketEndY, lastPrintedY=$lastBitmapY"
        )
    }

    private fun packetStartY(packet: NiimbotPacket): Int? {
        if (packet.data.size < 2) return null

        return when (packet.code) {
            0x84, 0x85 -> ((packet.data[0].toInt() and 0xFF) shl 8) or
                    (packet.data[1].toInt() and 0xFF)

            else -> null
        }
    }

    private fun packetEndY(packet: NiimbotPacket): Int? {
        val y = packetStartY(packet) ?: return null

        return when (packet.code) {
            0x84 -> {
                val count = packet.data.getOrNull(2)?.toInt()?.and(0xFF) ?: 1
                y + count - 1
            }

            0x85 -> {
                val repeat = packet.data.getOrNull(5)?.toInt()?.and(0xFF) ?: 1
                y + repeat - 1
            }

            else -> null
        }
    }

    private fun log(prefix: String, data: ByteArray) {
        Log.d(TAG, "$prefix: ${data.toHexString()}")
    }

    private fun ByteArray.toHexString(): String =
        joinToString(":") { "%02x".format(it.toInt() and 0xFF) }

    private fun logBatchInfo(
        batchIndex: Int,
        batch: List<NiimbotPacket>,
        stage: String
    ) {
        val firstY = batch.mapNotNull { packetStartY(it) }.minOrNull()
        val lastY = batch.mapNotNull { packetEndY(it) }.maxOrNull()
        val bytes = batch.sumOf { it.toBytes().size }

        Log.d(
            TAG,
            "BATCH[$batchIndex] $stage packets=${batch.size}, bytes=$bytes, y=$firstY..$lastY, t=${System.currentTimeMillis()}"
        )
    }
}
