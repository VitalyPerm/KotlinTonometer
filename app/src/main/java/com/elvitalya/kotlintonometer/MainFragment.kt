package com.elvitalya.kotlintonometer

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.MutableLiveData
import com.elvitalya.kotlintonometer.databinding.FragmentMainBinding
import java.util.*

@SuppressLint("MissingPermission")
class MainFragment : Fragment() {

    private val T = "T"

    private var isPermissionGranted = false

    private var isBluetoothEnabled = false

    private var isScanning = false

    private var mIsBleReceiver = false

    private lateinit var tonometerService: TonometerService

    private lateinit var mDevice: BluetoothDevice

    private lateinit var binding: FragmentMainBinding

    private val bluetoothAdapter by lazy {
        (requireActivity().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentMainBinding.inflate(layoutInflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requestBtPermission()
        isBluetoothEnabled = bluetoothAdapter.isEnabled

        data.observe(viewLifecycleOwner) {
            logging("getting data from livedata ${it.one} ${it.two} ${it.three}")
        }

        if (isPermissionGranted && isBluetoothEnabled && !isScanning) {
            startBleScan()
        }


        val BloodPressureService: UUID? = uuidFromShortString("1810")
        val WeightScaleService: UUID? = uuidFromShortString("181d")

    }

    fun uuidFromShortString(uuid: String?): UUID? {
        return UUID.fromString(String.format("0000%s-0000-1000-8000-00805f9b34fb", uuid))
    }

    private fun doStartService() {
        val intent1 = Intent(requireActivity(), TonometerService::class.java)
        requireActivity().startService(intent1)
    }

    private fun doBindBleReceivedService() {
        requireActivity().bindService(
            Intent(requireActivity(), TonometerService::class.java), mBleReceivedServiceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    private val mBleReceivedServiceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceDisconnected(name: ComponentName) {}
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            tonometerService = (service as TonometerService.TonometerBinder).service
            tonometerService.connectDevice(mDevice)
        }
    }


    private fun startBleScan() {
        if (!bluetoothAdapter.isEnabled) return
        val scanFilter = ScanFilter.Builder().build()
        val scanFilters: MutableList<ScanFilter> = mutableListOf()
        scanFilters.add(scanFilter)
        val scanSettings =
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
        bluetoothAdapter.bluetoothLeScanner.startScan(scanFilters, scanSettings, bleScanCallback)
        isScanning = true
    }

    private fun stopBleScan() {
        if (!bluetoothAdapter.isEnabled) return
        bluetoothAdapter.bluetoothLeScanner.stopScan(bleScanCallback)
        isScanning = false
    }

    private val bleScanCallback: ScanCallback by lazy {
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                super.onScanResult(callbackType, result)
                result?.device?.let {
                    if (it.name?.contains("A&D") == true) {
                        mDevice = it
                        stopBleScan()
                        doBindBleReceivedService()
                        doStartService()
                        Toast.makeText(requireContext(), "Device found", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }


    private fun requestBtPermission() {
        if (isPermissionGranted()) {
            isPermissionGranted = true
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestMultiplePermissions.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else {
            requestMultiplePermissions.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private val requestMultiplePermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            permissions.entries.forEach {
                if (it.value == false) {
                    isPermissionGranted = false
                    return@registerForActivityResult
                } else {
                    isPermissionGranted = true
                }
            }
        }


    private fun isPermissionGranted(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
                && (ActivityCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED)
                && (ActivityCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.BLUETOOTH
                ) != PackageManager.PERMISSION_GRANTED)
                && (ActivityCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED)
                && (ActivityCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED)
            ) return false
        } else {
            if ((ActivityCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED)
                && (ActivityCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED)
            ) return false
        }
        return true
    }


    companion object {
        val data = MutableLiveData<TonometerData>()
        fun logging(text: String) {
            Log.d("CHECK", text)
        }
    }

}


data class TonometerData(
    val one: String,
    val two: String,
    val three: String,
)