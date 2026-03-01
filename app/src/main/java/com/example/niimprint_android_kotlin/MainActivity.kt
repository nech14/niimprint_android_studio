package com.example.niimprint_android_kotlin

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Log
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
import java.util.*

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

//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        setContentView(R.layout.activity_main)
//
//        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
//        bluetoothAdapter = bluetoothManager.adapter
//        bleManager = BleManager(this)
//
//        // Запрос разрешений на Android 12+
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
//            requestBluetoothPermissions.launch(
//                arrayOf(
//                    Manifest.permission.BLUETOOTH_CONNECT,
//                    Manifest.permission.BLUETOOTH_SCAN
//                )
//            )
//        }
//
//        findViewById<Button>(R.id.start_button).setOnClickListener {
//            showBluetoothDevicesDialog()
//        }
//    }

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

        // Захардкодим MAC-адрес принтера
        val printerMac = "C3:08:13:07:15:85"  // <- сюда вставь свой MAC

        findViewById<Button>(R.id.start_button).setOnClickListener {
            connectToPrinterByMac(printerMac)
        }
    }

    private fun connectToPrinterByMac(mac: String) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Нет разрешения на Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }

        val device = bluetoothAdapter.getRemoteDevice(mac)
        Toast.makeText(this, "Подключение к ${device.name ?: device.address}...", Toast.LENGTH_SHORT).show()

        bleManager.connect(device) { gatt, writeChar, notifyChar ->
            val printerClient = NiimbotPrinterClientBle(gatt, writeChar, notifyChar)

            lifecycleScope.launch(Dispatchers.IO) {
                try {
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

        Toast.makeText(this, "Подключение к ${device.name ?: device.address}...", Toast.LENGTH_SHORT).show()

        // Подключаемся к BLE и передаём callback для печати после discovery
        bleManager.connect(device) { gatt, writeChar, notifyChar ->
            val printerClient = NiimbotPrinterClientBle(gatt, writeChar, notifyChar)

            lifecycleScope.launch(Dispatchers.IO) {
                try {
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
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.disconnect()
    }
}

// ----------------------- BleManager -----------------------
class BleManager(private val context: Context) {

    private var bluetoothGatt: BluetoothGatt? = null
    private var isConnecting = false

    // Callback с передачей характеристик write/notify
    fun connect(device: BluetoothDevice, onReady: (BluetoothGatt, BluetoothGattCharacteristic, BluetoothGattCharacteristic) -> Unit) {
        if (isConnecting || bluetoothGatt != null) return
        isConnecting = true

        bluetoothGatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            Log.d("BLE", "Connected to device")
                            gatt.discoverServices()
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            Log.d("BLE", "Disconnected from device")
                            cleanup()
                        }
                    }
                } else {
                    Log.w("BLE", "GATT error: $status")
                    cleanup()
                }
                isConnecting = false
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d("BLE", "Services discovered")
                    // TODO: Укажи реальные UUID сервиса и характеристик твоего Niimbot принтера
                    val serviceUUID = UUID.fromString("bef8d6c9-9c21-4c9e-b632-bd58c1009f9f")
                    val writeUUID = UUID.fromString("bef8d6c9-9c21-4c9e-b632-bd58c1009f9f")
                    val notifyUUID = UUID.fromString("bef8d6c9-9c21-4c9e-b632-bd58c1009f9f")

                    val service = gatt.services.firstOrNull { it.uuid == serviceUUID } ?: return
                    val writeChar = service.getCharacteristic(writeUUID)
                    val notifyChar = service.getCharacteristic(notifyUUID)

                    gatt.setCharacteristicNotification(notifyChar, true)
                    val descriptor = notifyChar.getDescriptor(UUID.fromString("bef8d6c9-9c21-4c9e-b632-bd58c1009f9f"))
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)

                    onReady(gatt, writeChar, notifyChar)
                } else {
                    Log.w("BLE", "Service discovery failed with status: $status")
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                // Здесь нужно передавать уведомления в NiimbotPrinterClientBle.onNotificationReceived
            }
        })
        Log.d("BLE", "Trying to connect...")
    }

    fun disconnect() {
        bluetoothGatt?.disconnect()
        cleanup()
    }

    private fun cleanup() {
        bluetoothGatt?.close()
        bluetoothGatt = null
    }
}
