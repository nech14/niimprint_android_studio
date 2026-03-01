package com.example.niimprint_android_kotlin

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

class NiimbotPrinterClientBle(
    private val gatt: BluetoothGatt,
    private val writeCharacteristic: BluetoothGattCharacteristic,
    private val notifyCharacteristic: BluetoothGattCharacteristic
) {

    private val pendingResponses = mutableListOf<NiimbotPacket>()

    enum class RequestCodeEnum(val value: Int) {
        GET_INFO(64),
        GET_RFID(26),
        HEARTBEAT(220),
        SET_LABEL_TYPE(35),
        SET_LABEL_DENSITY(33),
        START_PRINT(1),
        END_PRINT(243),
        START_PAGE_PRINT(3),
        END_PAGE_PRINT(227),
        ALLOW_PRINT_CLEAR(32),
        SET_DIMENSION(19),
        SET_QUANTITY(21),
        GET_PRINT_STATUS(163)
    }

    class NiimbotPacket(val type: Byte, val data: ByteArray) {
        companion object {

            fun fromBytes(pkt: ByteArray): NiimbotPacket {
                require(pkt.sliceArray(0..1).contentEquals(byteArrayOf(0x55, 0x55))) { "Invalid start bytes" }
                require(pkt.sliceArray(pkt.size - 2 until pkt.size).contentEquals(byteArrayOf(0xaa.toByte(), 0xaa.toByte()))) { "Invalid end bytes" }

                val type = pkt[2]
                val len = pkt[3].toInt()
                val data = pkt.sliceArray(4 until 4 + len)

                var checksum = type.toInt() xor len
                for (i in data) checksum = checksum xor i.toInt()

                require(checksum.toByte() == pkt[pkt.size - 3]) { "Invalid checksum" }
                return NiimbotPacket(type, data)
            }

            fun cropOrPadTo384(bitmap: Bitmap): Bitmap {
                val targetWidth = 384
                val height = bitmap.height
                if (bitmap.width == targetWidth) return bitmap

                val newBitmap = Bitmap.createBitmap(targetWidth, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(newBitmap)
                canvas.drawColor(Color.WHITE)
                val copyWidth = minOf(bitmap.width, targetWidth)
                canvas.drawBitmap(bitmap, Rect(0, 0, copyWidth, height), Rect(0, 0, copyWidth, height), null)
                return newBitmap
            }

            fun naiveEncoder(img: Bitmap): Sequence<NiimbotPacket> = sequence {
                val processedImg = cropOrPadTo384(img)
                val width = processedImg.width
                val height = processedImg.height
                val bytesPerRow = 48 // 384/8

                for (y in 0 until height) {
                    val rowBytes = ByteArray(bytesPerRow)
                    for (xByte in 0 until bytesPerRow) {
                        var byteValue = 0
                        for (bit in 0 until 8) {
                            val x = xByte * 8 + bit
                            val pixel = processedImg.getPixel(x, y)
                            val gray = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3
                            if (gray < 128) byteValue = byteValue or (1 shl (7 - bit))
                        }
                        rowBytes[xByte] = byteValue.toByte()
                    }
                    val rowLow = (y and 0xFF).toByte()
                    val rowHigh = ((y shr 8) and 0xFF).toByte()
                    val header = byteArrayOf(rowLow, rowHigh, 0, 0, 0, 1)
                    yield(NiimbotPacket(0x85.toByte(), header + rowBytes))
                }
            }
        }

        fun toBytes(): ByteArray {
            var checksum = type.toInt() xor data.size
            for (i in data) checksum = checksum xor i.toInt()
            return byteArrayOf(0x55, 0x55, type) + data.size.toByte() + data + checksum.toByte() + byteArrayOf(0xaa.toByte(), 0xaa.toByte())
        }
    }

    init {
        // Подписка на уведомления BLE
        gatt.setCharacteristicNotification(notifyCharacteristic, true)
        val descriptor = notifyCharacteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        gatt.writeDescriptor(descriptor)
    }

    fun send(packet: NiimbotPacket) {
        writeCharacteristic.value = packet.toBytes()
        gatt.writeCharacteristic(writeCharacteristic)
    }

    suspend fun transceive(reqCode: RequestCodeEnum, data: ByteArray, timeoutMs: Long = 2000): NiimbotPacket? {
        val packet = NiimbotPacket(reqCode.value.toByte(), data)
        send(packet)

        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (pendingResponses.isNotEmpty()) return pendingResponses.removeAt(0)
            delay(20)
        }
        return null
    }

    fun onNotificationReceived(value: ByteArray) {
        pendingResponses.add(NiimbotPacket.fromBytes(value))
    }

    suspend fun printLabel(image: Bitmap, labelQty: Int = 1, labelType: Int = 1, labelDensity: Int = 2) {
        val processedImg = NiimbotPacket.cropOrPadTo384(image)

        transceive(RequestCodeEnum.SET_LABEL_TYPE, byteArrayOf(labelType.toByte()))
        transceive(RequestCodeEnum.SET_LABEL_DENSITY, byteArrayOf(labelDensity.toByte()))
        transceive(RequestCodeEnum.START_PRINT, byteArrayOf(0x01))
        transceive(RequestCodeEnum.START_PAGE_PRINT, byteArrayOf(0x01))
        transceive(RequestCodeEnum.SET_DIMENSION, ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putShort(processedImg.width.toShort()).putShort(processedImg.height.toShort()).array())
        transceive(RequestCodeEnum.SET_QUANTITY, ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(labelQty.toShort()).array())

        for (pkt in NiimbotPacket.naiveEncoder(processedImg)) {
            send(pkt)
            delay(1)
        }

        transceive(RequestCodeEnum.END_PAGE_PRINT, byteArrayOf(0x01))

        while ((getPrintStatus()["page"] ?: 0) != labelQty) delay(100)

        transceive(RequestCodeEnum.END_PRINT, byteArrayOf(0x01))
    }

    suspend fun getPrintStatus(): Map<String, Int> {
        val pkt = transceive(RequestCodeEnum.GET_PRINT_STATUS, byteArrayOf(0x01)) ?: return emptyMap()
        val buffer = ByteBuffer.wrap(pkt.data).order(ByteOrder.BIG_ENDIAN)
        return mapOf(
            "page" to buffer.short.toInt(),
            "progress1" to buffer.get().toInt(),
            "progress2" to buffer.get().toInt()
        )
    }
}
