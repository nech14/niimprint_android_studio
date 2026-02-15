package com.example.niimprint_android_kotlin

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
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

    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bleManager: BleManager

    private val requestBluetoothPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.values.any { !it }) {
                Toast.makeText(this, "Не даны разрешения на Bluetooth", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        bleManager = BleManager(this)

        // Запрос разрешений на Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestBluetoothPermissions.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
                )
            )
        }

        val startButton: Button = findViewById(R.id.start_button)
        startButton.setOnClickListener {
            showBluetoothDevicesDialog()
        }
    }

    private fun showBluetoothDevicesDialog() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Нет разрешения на Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }

        val pairedDevices = bluetoothAdapter.bondedDevices.toList()
        if (pairedDevices.isEmpty()) {
            Toast.makeText(this, "Нет сопряжённых Bluetooth-устройств", Toast.LENGTH_SHORT).show()
            return
        }

        val deviceNames = pairedDevices.map { it.name ?: it.address }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, deviceNames)

        AlertDialog.Builder(this)
            .setTitle("Выберите принтер")
            .setAdapter(adapter) { dialog, which ->
                val selectedDevice = pairedDevices[which]
                connectAndPrint(selectedDevice)
                dialog.dismiss()
            }
            .setNegativeButton("Отмена") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun connectAndPrint(device: BluetoothDevice) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Нет разрешения на Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }

        bleManager.connect(device)
        Toast.makeText(this, "Подключение к ${device.name ?: device.address}...", Toast.LENGTH_SHORT).show()

        // После подключения BLE запускаем печать
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val printerClient = NiimbotPrinterClient(device.address, bluetoothAdapter)
                val bmp = BitmapFactory.decodeResource(resources, R.drawable.test_label_80x50)

                printerClient.printLabel(
                    image = bmp,
                    labelQty = 1,
                    labelType = 1,
                    labelDensity = 3
                )

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Печать отправлена", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Ошибка печати: $e", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.disconnect()
    }
}
