package com.example.niimprint_android_kotlin

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private val discoveredDevices = mutableListOf<BluetoothDevice>()
    private val bluetoothAdapter by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    private val bleScanner by lazy { bluetoothAdapter.bluetoothLeScanner }

    // Коллбек сканирования
    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            // Добавляем только новые устройства и желательно только Niimbot (по имени)
            if (!discoveredDevices.any { it.address == device.address }) {
                discoveredDevices.add(device)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val printButton = findViewById<Button>(R.id.start_button)

        printButton.setOnClickListener {
            // Проверяем разрешения (упрощенно)
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), 1)
                return@setOnClickListener
            }

            startBleScan() // Запускаем поиск

            // Показываем диалог через пару секунд поиска или сразу
            // Для теста можно подождать 2 секунды, пока надуется список
            printButton.postDelayed({
                stopBleScan()
                if (discoveredDevices.isEmpty()) {
                    Toast.makeText(this, "Принтеры не найдены. Включите BLE на принтере!", Toast.LENGTH_LONG).show()
                }
                showPrinterSelectionDialog(this, discoveredDevices) { selectedDevice ->
                    connectAndPrint(selectedDevice)
                }
            }, 3000) // 3 секунды на поиск
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        discoveredDevices.clear()
        bleScanner.startScan(scanCallback)
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        bleScanner.stopScan(scanCallback)
    }

    @SuppressLint("MissingPermission")
    private fun connectAndPrint(device: BluetoothDevice) {
        // Создаем хардкодную картинку
//        val bitmapToPrint = createBitmapFromResource(this, R.drawable.test_label_80x50)
        val bitmapToPrint = createDummyBitmap()
        lateinit var bleManager: NiimbotBleManager

        // Подключаемся к GATT-серверу устройства
        device.connectGatt(this, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    // Успешно подключились!
                    // 1. Обязательно запрашиваем расширение MTU для картинок
                    gatt.requestMtu(512)
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    // 2. MTU расширен, теперь ищем сервисы
                    gatt.discoverServices()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    // 3. Сервисы найдены! Можно отправлять картинку.
                    bleManager = NiimbotBleManager(gatt)

                    // ВАЖНО: BluetoothGattCallback работает в фоновом потоке.
                    // Печать займет время, поэтому просто вызываем функцию из предыдущего ответа
                    lifecycleScope.launch {
                        printJob(bleManager, bitmapToPrint, gatt)
                    }
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                Log.d("NIIMBOT", "Write status: $status")
                bleManager.onWriteCompleted(status)
            }
        })
    }
}

@SuppressLint("MissingPermission")
fun showPrinterSelectionDialog(
    context: Context,
    scannedDevices: List<BluetoothDevice>,
    onDeviceSelected: (BluetoothDevice) -> Unit
) {
    // Формируем красивые имена для списка (Имя + MAC-адрес)
    val deviceNames = scannedDevices.map { device ->
        val name = device.name ?: "Неизвестное устройство"
        "$name\n${device.address}"
    }

    val adapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, deviceNames)

    AlertDialog.Builder(context)
        .setTitle("Выберите принтер B21s")
        .setAdapter(adapter) { dialog, which ->
            // При клике на элемент списка получаем соответствующий девайс
            val selectedDevice = scannedDevices[which]
            onDeviceSelected(selectedDevice)
        }
        .setNegativeButton("Отмена", null)
        .show()
}

fun createDummyBitmap(): Bitmap {
    val width = 384 // Обязательная ширина для B21s
    val height = 200 // Высота может быть любой (определяет длину этикетки)

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // Заливаем фон белым (принтер термо, белый = не печатаем)
    canvas.drawColor(Color.WHITE)

    // Настраиваем "черную" кисть для текста
    val paint = Paint().apply {
        color = Color.BLACK
        textSize = 48f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    // Рисуем текст по центру
    canvas.drawText("Привет, Niimbot!", width / 2f, height / 2f, paint)

    // Добавим рамочку для красоты
    val strokePaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    canvas.drawRect(10f, 10f, width - 10f, height - 10f, strokePaint)

    return bitmap
}


class NiimbotPacket(val type: Byte, val data: ByteArray = ByteArray(0)) {
    fun toBytes(): ByteArray {
        val len = data.size
        val buffer = ByteArray(len + 7)

        // Заголовок
        buffer[0] = 0x55.toByte()
        buffer[1] = 0x55.toByte()

        // Тип и длина
        buffer[2] = type
        buffer[3] = len.toByte()

        // Данные
        if (len > 0) {
            System.arraycopy(data, 0, buffer, 4, len)
        }

        // Контрольная сумма (XOR типа, длины и всех байт данных)
        var checksum = (type.toInt() and 0xFF) xor (len and 0xFF)
        for (b in data) {
            checksum = checksum xor (b.toInt() and 0xFF)
        }

        // Хвост пакета
        buffer[len + 4] = checksum.toByte()
        buffer[len + 5] = 0xAA.toByte()
        buffer[len + 6] = 0xAA.toByte()

        return buffer
    }
}

class NiimbotBleManager(private val gatt: BluetoothGatt) {

    private val SERVICE_UUID =
        UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb")
    private val WRITE_UUID =
        UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")

    private var writeContinuation: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    fun onWriteCompleted(status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            writeContinuation?.complete(Unit)
        } else {
            writeContinuation?.completeExceptionally(
                RuntimeException("Write failed with status $status")
            )
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun writePacket(packet: NiimbotPacket) {

        val service = gatt.getService(SERVICE_UUID) ?: return
        val characteristic =
            service.getCharacteristic(WRITE_UUID) ?: return

        characteristic.writeType =
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        characteristic.value = packet.toBytes()

        writeContinuation = kotlinx.coroutines.CompletableDeferred()

        val started = gatt.writeCharacteristic(characteristic)

        if (!started) {
            throw RuntimeException("writeCharacteristic returned false")
        }

        // ⬇️ ВОТ ЭТО ГЛАВНОЕ
        writeContinuation?.await()
    }
}


@SuppressLint("MissingPermission")
suspend fun printJob(
    bleManager: NiimbotBleManager,
    bitmap: Bitmap,
    gatt: BluetoothGatt
) = withContext(Dispatchers.IO) {

    suspend fun write(type: Int, data: ByteArray, delayAfter: Long = 80) {
        bleManager.writePacket(NiimbotPacket(type.toByte(), data))
        delay(delayAfter)
    }

    // --- 1. Сброс и настройка ---
    write(0x20, byteArrayOf(0x01)) // Clear buffer
    write(0x21, byteArrayOf(0x05)) // Density
    write(0x23, byteArrayOf(0x01)) // Label type

    // --- 2. Старт ---
    write(0x01, byteArrayOf(0x01), 150) // START_PRINT
    write(0x03, byteArrayOf(0x01), 150) // START_PAGE_PRINT

    // --- 3. Размер (width, height) ---
    val dimension = byteArrayOf(
        (bitmap.width shr 8).toByte(),
        (bitmap.width and 0xFF).toByte(),
        (bitmap.height shr 8).toByte(),
        (bitmap.height and 0xFF).toByte()
    )

    write(0x13, dimension, 150)

    // --- 4. Печать строк ---
    for (y in 0 until bitmap.height) {

        val rowBytes = extract1BitB21Row(bitmap, y)

        val rowBytesCount = rowBytes.size
        val payload = ByteArray(6 + rowBytesCount)

        payload[0] = (y shr 8).toByte()
        payload[1] = (y and 0xFF).toByte()
        payload[2] = 0
        payload[3] = 0
        payload[4] = rowBytesCount.toByte()
        payload[5] = 0x01

        System.arraycopy(rowBytes, 0, payload, 6, rowBytesCount)

        bleManager.writePacket(NiimbotPacket(0x85.toByte(), payload))

        // 🔥 КЛЮЧЕВОЕ — стабильный темп, не 5мс, не 15, а нормальный

        if (rowBytes.all { it == 0.toByte() }) {
            Log.d("NIIMBOT", "Row $y EMPTY")
        }
    }

    // --- 5. Завершение ---
    delay(300)
    write(0xE3, byteArrayOf(0x01), 200) // End page
    write(0xF3, byteArrayOf(0x01), 200) // End print

    Log.d("NIIMBOT", "Disconnecting...")
    gatt.disconnect()
    gatt.close()
}

fun extract1BitB21Row(bitmap: Bitmap, y: Int): ByteArray {
    val rowBytes = ByteArray(384 / 8)

    if (y in 50..60) {
        for (i in rowBytes.indices) {
            rowBytes[i] = 0xFF.toByte()
        }
    }

    return rowBytes
}
//
//fun extract1BitB21Row(bitmap: Bitmap, y: Int): ByteArray {
//    Log.d("NIIMBOT", "Bitmap size: ${bitmap.width} x ${bitmap.height}")
//    val width = 384
//    val rowBytes = ByteArray(width / 8)
//
//    for (x in 0 until width) {
//        if (x >= bitmap.width) break
//
//        val pixel = bitmap.getPixel(x, y)
//
//        val r = Color.red(pixel)
//        val g = Color.green(pixel)
//        val b = Color.blue(pixel)
//
//        // Нормальная яркость
//        val gray = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
//
//        // Порог (можно менять)
//        if (gray < 160) {   // попробуй 140–180
//            val byteIndex = x / 8
//            val bitPosition = 7 - (x % 8)
//            rowBytes[byteIndex] =
//                (rowBytes[byteIndex].toInt() or (1 shl bitPosition)).toByte()
//        }
//    }
//
//    return rowBytes
//}

fun createBitmapFromResource(context: Context, resId: Int): Bitmap {
    val options = BitmapFactory.Options().apply {
        inScaled = false // Чтобы Android не менял размер под плотность экрана
    }
    val source = BitmapFactory.decodeResource(context.resources, resId, options)

    val width = 384
    // Рассчитываем высоту пропорционально, чтобы не растягивать
    val height = (source.height * (width.toFloat() / source.width)).toInt()

    // Масштабируем до 384px по ширине
    val scaledBitmap = Bitmap.createScaledBitmap(source, width, height, true)

    // Создаем пустой холст нужного размера
    val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(resultBitmap)
    val paint = Paint()

    // Переводим в Ч/Б через ColorMatrix (улучшает распознавание границ)
    val cm = ColorMatrix()
    cm.setSaturation(0f) // Убираем цвет
    paint.colorFilter = ColorMatrixColorFilter(cm)

    canvas.drawBitmap(scaledBitmap, 0f, 0f, paint)

    return resultBitmap
}