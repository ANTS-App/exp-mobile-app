package com.example.attendanceapp

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.attendanceapp.ui.theme.AttendanceAppTheme
import com.example.attendanceapp.utilities.GeolocationHelper

class StudentActivity : ComponentActivity() {
    private lateinit var databaseHelper: DatabaseHelper
    private lateinit var geolocationHelper: GeolocationHelper
    private lateinit var bluetoothHelper: BluetoothHelper

    private var teacherMacAddress: String? = null
    private var bluetoothAdapter: BluetoothAdapter? = null

    private val isTeacherNearby = mutableStateOf(false)
    private val scanInProgress = mutableStateOf(false)

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        Toast.makeText(this,
            if (allGranted) "All permissions granted" else "Some permissions were denied",
            Toast.LENGTH_SHORT
        ).show()
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothDevice.ACTION_FOUND) {
                val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                if (device?.address.equals(teacherMacAddress, ignoreCase = true)) {
                    isTeacherNearby.value = true
                    scanInProgress.value = false
                    try {
                        unregisterReceiver(this)
                    } catch (_: Exception) {}
                    Toast.makeText(this@StudentActivity, "Teacher found nearby via Bluetooth!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        databaseHelper = DatabaseHelper()
        geolocationHelper = GeolocationHelper(this)
        bluetoothHelper = BluetoothHelper(this)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        requestPermissions()
        fetchTeacherBluetoothMac()

        setContent {
            AttendanceAppTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    StudentScreen(
                        isBluetoothEnabled = bluetoothHelper.isBluetoothEnabled(),
                        scanInProgress = scanInProgress.value,
                        isTeacherNearby = isTeacherNearby.value,
                        onStartScan = { startBluetoothScan() },
                        onRequestPermissions = { requestPermissions() },
                        onVerifyLocation = { verifyLocationBeforeProceed() }
                    )
                }
            }
        }
    }

    private fun fetchTeacherBluetoothMac() {
        val ref = com.google.firebase.database.FirebaseDatabase.getInstance()
            .getReference("teachers/bluetoothSignature")

        ref.get().addOnSuccessListener {
            teacherMacAddress = it.getValue(String::class.java)
        }.addOnFailureListener {
            Toast.makeText(this, "Failed to get teacher MAC", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startBluetoothScan() {
        if (!bluetoothHelper.isBluetoothEnabled()) {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Bluetooth scan permission not granted", Toast.LENGTH_SHORT).show()
            return
        }

        scanInProgress.value = true
        isTeacherNearby.value = false

        registerReceiver(bluetoothReceiver, IntentFilter(BluetoothDevice.ACTION_FOUND))
        bluetoothAdapter?.startDiscovery()
    }

    private fun verifyLocationBeforeProceed() {
        geolocationHelper.verifyAttendance("3") { isNear ->
            if (isNear) {
                startActivity(Intent(this, TimetableSelectionActivity::class.java))
            } else {
                Toast.makeText(this, "Not within 10 meters of the teacher.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun requestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        permissionsToRequest.addAll(
            BluetoothHelper.getRequiredPermissions().filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
        )

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        geolocationHelper.stopLocationUpdates()
        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (_: Exception) {}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudentScreen(
    isBluetoothEnabled: Boolean,
    scanInProgress: Boolean,
    isTeacherNearby: Boolean,
    onStartScan: () -> Unit,
    onRequestPermissions: () -> Unit,
    onVerifyLocation: () -> Unit
) {

    var statusMessage by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (!isBluetoothEnabled) {
            statusMessage = "Bluetooth is off. Please enable it."
            onRequestPermissions()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Student Dashboard") }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Mark Attendance", fontSize = 24.sp, fontWeight = FontWeight.Bold)

            Button(onClick = onStartScan, enabled = !scanInProgress) {
                Text("Scan for Teacher")
            }

            if (scanInProgress) {
                Text("Scanning for teacher's Bluetooth...", color = Color.Blue)
            }

            if (isTeacherNearby) {
                Button(onClick = onVerifyLocation) {
                    Text("Verify Location & Proceed")
                }
            }

            if (statusMessage.isNotEmpty()) {
                Text(text = statusMessage, color = Color.Red)
            }
        }
    }
}
