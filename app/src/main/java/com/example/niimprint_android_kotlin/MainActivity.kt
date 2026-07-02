package com.example.niimprint_android_kotlin

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.niimprint.HorizontalAlignment
import com.niimprint.ImageRotation
import com.niimprint.NiimprintPrinter
import com.niimprint.PrintOptions
import com.niimprint.VerticalAlignment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private val bluetoothAdapter by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    private var isPrinting = false

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
                    "Classic printer not found. Pair a NIIMBOT printer in Android Bluetooth settings first.",
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
            Toast.makeText(this, "Print is already running", Toast.LENGTH_SHORT).show()
            return
        }

        isPrinting = true
        var printer: NiimprintPrinter? = null

        val bitmapToPrint = BitmapFactory.decodeResource(
            resources,
            R.drawable.test_label_80x50,
            BitmapFactory.Options().apply {
                inScaled = false
            }
        )

        lifecycleScope.launch {
            try {
                printer = NiimprintPrinter(device)

                val options = PrintOptions(
                    density = 5,
                    targetWidth = 320,
                    targetHeight = 320,
                    horizontalAlignment = HorizontalAlignment.CENTER,
                    verticalAlignment = VerticalAlignment.CENTER,
                    rotation = ImageRotation.ROTATE_90
                )

                withContext(Dispatchers.IO) {
                    printer.connect()
                    printer.printImage(bitmapToPrint, options)
                }

                Toast.makeText(
                    this@MainActivity,
                    "Print sent",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Log.e("NIIMBOT", "Classic print failed", e)

                Toast.makeText(
                    this@MainActivity,
                    "Classic print error: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                isPrinting = false

                try {
                    printer?.close()
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
    val deviceNames = scannedDevices.map { device ->
        val name = device.name ?: "Unknown device"
        "$name\n${device.address}"
    }

    val adapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, deviceNames)

    AlertDialog.Builder(context)
        .setTitle("Select NIIMBOT printer")
        .setAdapter(adapter) { _, which ->
            val selectedDevice = scannedDevices[which]
            onDeviceSelected(selectedDevice)
        }
        .setNegativeButton("Cancel", null)
        .show()
}
