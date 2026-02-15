package com.example.niimprint_android_kotlin

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        BluetoothAdapter.getDefaultAdapter()
    }

    // Runtime permission launcher для Android 12+
    private val requestBluetoothPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.values.any { !it }) {
                Toast.makeText(this, "Не даны разрешения на Bluetooth", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Запросим разрешения на Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestBluetoothPermissions.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
                )
            )
        }

        // Кнопка запуска
        findViewById<Button>(R.id.start_button).setOnClickListener {
            showBluetoothDevicesDialog()
        }
    }

    private fun showBluetoothDevicesDialog() {
        // Проверка разрешений
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Нет разрешения на Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }

        val pairedDevices = bluetoothAdapter?.bondedDevices?.toList()
        if (pairedDevices.isNullOrEmpty()) {
            Toast.makeText(this, "Нет сопряжённых Bluetooth-устройств", Toast.LENGTH_SHORT).show()
            return
        }

        // Создаём список имён устройств, если имя null — используем MAC
        val deviceNames = pairedDevices.map { it.name ?: it.address }

        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, deviceNames)

        AlertDialog.Builder(this)
            .setTitle("Выберите принтер")
            .setAdapter(adapter) { dialog, which ->
                val selectedDevice = pairedDevices[which]
                findPrinterAndPrint(selectedDevice)
                dialog.dismiss()
            }
            .setNegativeButton("Отмена") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun findPrinterAndPrint(device: BluetoothDevice) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Нет разрешения на Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Инициализация клиента через MAC
                val printerClient = NiimbotPrinterClient(device.address, bluetoothAdapter!!)

                val bmp: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.test_label_80x50)
//                val bmp = Bitmap.createBitmap(384, 100, Bitmap.Config.ARGB_8888)
//                bmp.eraseColor(Color.BLACK)

                val success = printerClient.printLabel(
                    image = bmp,
                    labelQty = 1,
                    labelType = 1,   // стандартная этикетка B21S
                    labelDensity = 3 // средняя плотность
                )

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        if (success) "Печать отправлена" else "Ошибка печати",
                        Toast.LENGTH_SHORT
                    ).show()
                }

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Ошибка печати: $e", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}


