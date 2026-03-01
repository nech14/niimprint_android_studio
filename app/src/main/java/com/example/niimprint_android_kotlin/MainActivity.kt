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
        val bitmapToPrint = createBitmapFromResource(this, R.drawable.test_label_80x50)

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
                    val bleManager = NiimbotBleManager(gatt)

                    // ВАЖНО: BluetoothGattCallback работает в фоновом потоке.
                    // Печать займет время, поэтому просто вызываем функцию из предыдущего ответа
                    lifecycleScope.launch {
                        printJob(bleManager, bitmapToPrint)
                    }
                }
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

    // Стандартные UUID для Niimbot
    private val SERVICE_UUID = UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb")
    private val WRITE_UUID = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")

    @SuppressLint("MissingPermission")
    fun writePacket(packet: NiimbotPacket): Boolean {
        val service = gatt.getService(SERVICE_UUID) ?: return false
        val characteristic = service.getCharacteristic(WRITE_UUID)
            ?: service.getCharacteristic(UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb"))
            ?: return false

        characteristic.value = packet.toBytes()
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        return gatt.writeCharacteristic(characteristic)
    }
}



@SuppressLint("MissingPermission")
suspend fun printJob(bleManager: NiimbotBleManager, bitmap: Bitmap) {
    withContext(Dispatchers.IO) {
        // Функция-помощник для безопасной отправки
        val safeWrite = { packet: NiimbotPacket, sleep: Long ->
            val res = bleManager.writePacket(packet)
            android.util.Log.d("NIIMBOT", "Packet ${packet.type} sent: $res")
            Thread.sleep(sleep)
            res
        }

        // 1. Старт
        safeWrite(NiimbotPacket(0x01, byteArrayOf(0x01)), 200)

        // 2. Очистка очереди (Команда 32 / 0x20)
        safeWrite(NiimbotPacket(0x20, byteArrayOf(0x01)), 100)

        // 3. Размеры (Команда 19 / 0x13) - ОЧЕНЬ ВАЖНО, чтобы было TRUE
        val dimension = byteArrayOf(
            (bitmap.height shr 8).toByte(), (bitmap.height and 0xFF).toByte(),
            (bitmap.width shr 8).toByte(), (bitmap.width and 0xFF).toByte()
        )
        safeWrite(NiimbotPacket(0x13, dimension), 100)

        // 4. Плотность и Тип (33 и 35)
        safeWrite(NiimbotPacket(0x21, byteArrayOf(0x03)), 100)
        safeWrite(NiimbotPacket(0x23, byteArrayOf(0x01)), 100)

        // 5. СТАРТ СТРАНИЦЫ (Команда 3 / 0x03) - ЕСЛИ ТУТ FALSE, ПЕЧАТИ НЕ БУДЕТ
        safeWrite(NiimbotPacket(0x03, byteArrayOf(0x01)), 200)

        // 6. Данные строк
        val height = bitmap.height
        android.util.Log.d("NIIMBOT", "Starting to send $height rows...")

        for (y in 0 until height) {
            val rowBytes = extract1BitB21Row(bitmap, y)
            val payload = ByteArray(3 + rowBytes.size)
            payload[0] = (y shr 8).toByte()
            payload[1] = (y and 0xFF).toByte()
            payload[2] = 0x01.toByte() // Количество повторений строки
            System.arraycopy(rowBytes, 0, payload, 3, rowBytes.size)

            val packet = NiimbotPacket(0x85.toByte(), payload)
            var success = bleManager.writePacket(packet)

            if (!success) {
                // Если не ушло, пробуем еще раз с задержкой
                delay(50)
                success = bleManager.writePacket(packet)
            }

            // ОБЯЗАТЕЛЬНО добавьте этот лог, чтобы увидеть, уходят ли строки!
            if (y % 10 == 0) { // Логаем каждую 10-ю строку, чтобы не спамить
                android.util.Log.d("NIIMBOT", "Row $y sent: $success")
            }

            delay(20) // Увеличим немного задержку для стабильности
        }

        // 7. КОНЕЦ (0xE3 и 0xF3)
        delay(200)
        safeWrite(NiimbotPacket(0xE3.toByte(), byteArrayOf(0x01)), 200)
        safeWrite(NiimbotPacket(0xF3.toByte(), byteArrayOf(0x01)), 200)

        android.util.Log.d("NIIMBOT", "Print Job Finished")
    }
}

fun extract1BitB21Row(bitmap: Bitmap, y: Int): ByteArray {
    val width = 384
    val rowBytes = ByteArray(width / 8)

    for (x in 0 until width) {
        if (x >= bitmap.width) {
            // Если картинка уже закончилась по ширине, забиваем остаток "белым"
            // В Niimbot обычно 0 — это белый, но попробуем логику 0xFF для пустоты ниже
            continue
        }

        val pixel = bitmap.getPixel(x, y)
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF

        // Порог яркости (сделаем чуть чувствительнее)
        val luminance = (0.299 * r + 0.587 * g + 0.114 * b).toInt()

        // ВАЖНО: Попробуем логику, где 1 — это ЧЕРНЫЙ
        if (luminance < 150) {
            val byteIndex = x / 8
            val bitPosition = 7 - (x % 8)
            rowBytes[byteIndex] = (rowBytes[byteIndex].toInt() or (1 shl bitPosition)).toByte()
        }
    }
    return rowBytes
}

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