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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private val bluetoothAdapter by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    private lateinit var classicManager: NiimbotClassicManager
    private lateinit var printerClient: PrinterClient

    private var isPrinting = false

    // Коллбек сканирования

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val printButton = findViewById<Button>(R.id.start_button)

        printButton.setOnClickListener {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                    1
                )
                return@setOnClickListener
            }

            val devices = getPairedClassicPrinters()

            if (devices.isEmpty()) {
                Toast.makeText(
                    this,
                    "Classic-принтер не найден. Сначала спарьте NIIMBOT в настройках Bluetooth Android.",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            showPrinterSelectionDialog(this, devices) { selectedDevice ->
                connectAndPrintClassic(selectedDevice)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun getPairedClassicPrinters(): List<BluetoothDevice> {
        return bluetoothAdapter.bondedDevices
            .filter { device ->
                val name = device.name.orEmpty()
                name.contains("B21", ignoreCase = true) ||
                        name.contains("NIIMBOT", ignoreCase = true) ||
                        name.contains("C308", ignoreCase = true)
            }
    }

    @SuppressLint("MissingPermission")
    private fun connectAndPrintClassic(device: BluetoothDevice) {
        if (isPrinting) {
            Toast.makeText(this, "Печать уже выполняется", Toast.LENGTH_SHORT).show()
            return
        }

        isPrinting = true

        val bitmapToPrint = BitmapFactory.decodeResource(
            resources,
            R.drawable.test_label_80x50,
            BitmapFactory.Options().apply {
                inScaled = false
            }
        )

        lifecycleScope.launch {
            try {
                classicManager = NiimbotClassicManager(device)
                printerClient = PrinterClient(classicManager)

                withContext(Dispatchers.IO) {
                    classicManager.connect()
                    printerClient.printImage(bitmapToPrint)
                }

                Toast.makeText(
                    this@MainActivity,
                    "Печать отправлена",
                    Toast.LENGTH_SHORT
                ).show()

            } catch (e: Exception) {
                Log.e("NIIMBOT", "Classic print failed", e)

                Toast.makeText(
                    this@MainActivity,
                    "Ошибка Classic-печати: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()

            } finally {
                isPrinting = false

                try {
                    classicManager.close()
                } catch (_: Exception) {
                }
            }
        }
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





