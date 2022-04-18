package com.elvitalya.kotlintonometer

import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.*
import android.content.Intent
import android.os.*
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.*


@SuppressLint("MissingPermission")
class TonometerService : Service() {

    companion object {
        val ACTION_BLE_SERVICE = "jp.co.aandd.andblelink.ble.BLE_SERVICE"
        val ACTION_BLE_DATA_RECEIVED = "jp.co.aandd.andblelink.ble.data_received"
    }


    private var tonometerService: TonometerService? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var isConnectedDevice = false
    private val isBindService = false
    private val uiThreadHandler = Handler(Looper.getMainLooper())
    private var setDateTimeDelay = Long.MIN_VALUE
    private var indicationDelay = Long.MIN_VALUE
    var operation: String = "data"


    override fun onBind(intent: Intent?): IBinder? {
        return TonometerBinder()
    }

    override fun onCreate() {
        super.onCreate()
        if (tonometerService == null) {
            tonometerService = this
        }
    }

    fun getInstance(): TonometerService? {
        return tonometerService
    }

    fun isConnectedDevice(): Boolean {
        return isConnectedDevice
    }

    fun getBluetoothManager(): BluetoothManager {
        return getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
    }


    fun connectDevice(device: BluetoothDevice?): Boolean {
        if (device == null) {
            return false
        }

        operation = "data"
        if (operation.equals("pair", ignoreCase = true)) {
            Handler().postDelayed({
                bluetoothGatt = device.connectGatt(applicationContext, false, bluetoothGattCallback)
            }, 500)
        } else {
            Log.d("AD", "Calling the connectGatt for data transfer")
            bluetoothGatt = device.connectGatt(this, true, bluetoothGattCallback)
        }
        return bluetoothGatt != null
    }

    fun getGatt(): BluetoothGatt? {
        return if (tonometerService != null) {
            tonometerService!!.bluetoothGatt
        } else null
    }

    fun disconnectDevice() {
        if (bluetoothGatt == null) {
            return
        }
        bluetoothGatt!!.close()
        bluetoothGatt!!.disconnect()
        bluetoothGatt = null
    }

    fun setupDateTime(gatt: BluetoothGatt?): Boolean {
        var isSuccess = false
        if (gatt != null) {
            isSuccess = setDateTimeSetting(gatt, Calendar.getInstance())
        }
        return isSuccess
    }

    private fun setDateTimeSetting(gatt: BluetoothGatt, cal: Calendar): Boolean {
        var isSuccess = false
        val gattService: BluetoothGattService? = getGattSearvice(gatt)
        if (gattService != null) {
            var characteristic = gattService.getCharacteristic(ADGattUUID.DateTime)
            if (characteristic != null) {

                characteristic =
                    datewriteCharacteristic(
                        characteristic,
                        cal
                    )
                isSuccess = gatt.writeCharacteristic(characteristic)
            }
        }
        return isSuccess
    }

    fun datewriteCharacteristic(
        characteristic: BluetoothGattCharacteristic,
        calendar: Calendar
    ): BluetoothGattCharacteristic? {
        val year = calendar[Calendar.YEAR]
        val month = calendar[Calendar.MONTH] + 1
        val day = calendar[Calendar.DAY_OF_MONTH]
        val hour = calendar[Calendar.HOUR_OF_DAY]
        val min = calendar[Calendar.MINUTE]
        val sec = calendar[Calendar.SECOND]
        val value = byteArrayOf(
            (year and 0x0FF).toByte(),  // year 2bit
            (year shr 8).toByte(),  //
            month.toByte(),  // month
            day.toByte(),  // day
            hour.toByte(),  // hour
            min.toByte(),  // min
            sec.toByte() // sec
        )
        characteristic.value = value
        return characteristic
    }

    fun getGattSearvice(gatt: BluetoothGatt): BluetoothGattService? {
        var service: BluetoothGattService? = null
        for (uuid in ADGattUUID.ServicesUUIDs) {
            service = gatt.getService(uuid)
            if (service != null) break
        }
        return service
    }

    fun requestReadFirmRevision() {
        if (bluetoothGatt != null) {
            val service = bluetoothGatt!!.getService(ADGattUUID.DeviceInformationService)
            if (service != null) {
                val characteristic = service.getCharacteristic(ADGattUUID.FirmwareRevisionString)
                if (characteristic != null) {
                    bluetoothGatt!!.readCharacteristic(characteristic)
                }
            }
        }
    }


    private val bluetoothGattCallback: BluetoothGattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            Log.d("A&D", "onConnectionStateChange")
            var gatt: BluetoothGatt? = gatt
            val device = gatt!!.device

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices()
                isConnectedDevice = true


                //Connected now discover services
                if (operation.equals("pair", ignoreCase = true)) {

                    if (getGatt() != null) {
                        getGatt()!!.discoverServices()
                    }
                } else if (operation.equals("data", ignoreCase = true)) {
                    if (getGatt() != null) {
                        getGatt()!!.discoverServices()
                    }
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnectedDevice = false
                //Clean up gatt
                gatt.disconnect()
                gatt.close()
                gatt = null
                disconnectDevice()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            super.onServicesDiscovered(gatt, status)
            Log.d("A&D", "onServicesDiscovered")
            val device = gatt.device

            if (operation.equals("pair", ignoreCase = true)) {
                setupDateTime(gatt)
            } else if (operation.equals("data", ignoreCase = true)) {

                if (getInstance() != null) {
                    uiThreadHandler.postDelayed({
                        getInstance()?.requestReadFirmRevision()
                    }, 50L)
                }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            super.onCharacteristicRead(gatt, characteristic, status)
            val device = gatt.device
            Log.d("A&D", "onCharacteristicRead")

            val firmRevisionBytes = characteristic.value
            var firmRevision: String? = null
            if (firmRevisionBytes == null) {
                return
            }
            firmRevision = String(firmRevisionBytes)
            if (firmRevision == null || firmRevision.isEmpty()) {
                return
            }

            // String[] firmRevisionArray = getResources().getStringArray(R.array.firm_revision_group1);
            val firmRevisionArray = arrayOf(
                "BLP008_d016",
                "BLP008_d017",
                "HTP008_134",
                "CWSP008_108",
                "CWSP008_110",
                "WSP001_201"
            )
            var isGroup1 = false
            for (revision in firmRevisionArray) {
                if (revision.contains(firmRevision)) {
                    isGroup1 = true
                    break
                }
            }
            if (isGroup1) {
                setDateTimeDelay = 40L
                indicationDelay = 40L
            } else {
                setDateTimeDelay = 100L
                indicationDelay = 100L
            }
            uiThreadHandler.postDelayed({
                val gatt: BluetoothGatt? = getGatt()
                var settingResult = false
                if (gatt != null) {

                    settingResult = setupDateTime(gatt)
                }
                if (!settingResult) {

                }
            }, setDateTimeDelay)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            val device = gatt.device
            MainFragment.logging("onCharacteristicWrite")

            val serviceUuidString = characteristic.service.uuid.toString()
            val characteristicUuidString = characteristic.uuid.toString()
            if (operation.equals("pair", ignoreCase = true)) {
                disconnectDevice()
            } else if (operation.equals("data", ignoreCase = true)) {
                if (serviceUuidString == ADGattUUID.CurrentTimeService.toString() || characteristicUuidString == ADGattUUID.DateTime.toString()) {
                    uiThreadHandler.postDelayed({
                        val gatt: BluetoothGatt? = getGatt()

                        val writeResult: Boolean = setIndication(gatt, true)
                        if (!writeResult) {

                        }
                    }, indicationDelay)
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            super.onCharacteristicChanged(gatt, characteristic)
            MainFragment.logging("onCharacteristicChanged")

            val device = gatt.device
            parseCharcteristicValue(gatt, characteristic)
        }

        override fun onDescriptorRead(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            super.onDescriptorRead(gatt, descriptor, status)
            MainFragment.logging("onDescriptorRead")
            var gatt: BluetoothGatt? = gatt
            val device = gatt!!.device
            val gattService: BluetoothGattService? = getGattSearvice(gatt!!)
            if (operation.equals("pair", ignoreCase = true)) {
                //TODO: If there is a separate pairing screen, then issue a device disconnect here.
                if (gatt != null) {
                    gatt!!.disconnect()
                    gatt!!.close()
                }
                disconnectDevice()
            } else {
                //Nothing to do since its not a pairing case.
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            super.onDescriptorWrite(gatt, descriptor, status)
            val device = gatt.device
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            super.onReadRemoteRssi(gatt, rssi, status)
            val device = gatt.device
        }

        override fun onReliableWriteCompleted(gatt: BluetoothGatt, status: Int) {
            super.onReliableWriteCompleted(gatt, status)
            val device = gatt.device
        }
    }

    fun parseCharcteristicValue(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic) {
        if (ADGattUUID.BloodPressureMeasurement.equals(characteristic.uuid)) {
            val flag = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0)
            val flagString = Integer.toBinaryString(flag)
            var systolic = ""
            var diastolic = ""
            var pulse = ""
            var systolic_display = ""
            var diastolic_display = ""
            var pulse_display = ""
            var offset = 0
            var index = flagString.length
            while (0 < index) {
                val key = flagString.substring(index - 1, index)
                if (index == flagString.length) {
                    if (key == "0") {
                        // mmHg
                        Log.d("SN", "mmHg")
                    } else {
                        // kPa
                        Log.d("SN", "kPa")
                    }
                    // Unit
                    offset += 1
                    Log.d(
                        "SN",
                        "Systolic :" + String.format(
                            "%f",
                            characteristic.getFloatValue(
                                BluetoothGattCharacteristic.FORMAT_SFLOAT,
                                offset
                            )
                        )
                    )
                    systolic = String.format(
                        "%f",
                        characteristic.getFloatValue(
                            BluetoothGattCharacteristic.FORMAT_SFLOAT,
                            offset
                        )
                    )
                    systolic_display = String.format(
                        "%.0f",
                        characteristic.getFloatValue(
                            BluetoothGattCharacteristic.FORMAT_SFLOAT,
                            offset
                        )
                    )
                    offset += 2
                    Log.d(
                        "SN",
                        "Diastolic :" + String.format(
                            "%f",
                            characteristic.getFloatValue(
                                BluetoothGattCharacteristic.FORMAT_SFLOAT,
                                offset
                            )
                        )
                    )
                    diastolic = String.format(
                        "%f",
                        characteristic.getFloatValue(
                            BluetoothGattCharacteristic.FORMAT_SFLOAT,
                            offset
                        )
                    )
                    diastolic_display = String.format(
                        "%.0f",
                        characteristic.getFloatValue(
                            BluetoothGattCharacteristic.FORMAT_SFLOAT,
                            offset
                        )
                    )
                    offset += 2
                    Log.d(
                        "SN",
                        "Mean Arterial Pressure :" + String.format(
                            "%f",
                            characteristic.getFloatValue(
                                BluetoothGattCharacteristic.FORMAT_SFLOAT,
                                offset
                            )
                        )
                    )
                    offset += 2
                } else if (index == flagString.length - 1) {
                    if (key == "1") {
                        // Time Stamp
                        Log.d(
                            "SN",
                            "Y :" + String.format(
                                "%04d",
                                characteristic.getIntValue(
                                    BluetoothGattCharacteristic.FORMAT_UINT16,
                                    offset
                                )
                            )
                        )
                        offset += 2
                        Log.d(
                            "SN",
                            "M :" + String.format(
                                "%02d",
                                characteristic.getIntValue(
                                    BluetoothGattCharacteristic.FORMAT_UINT8,
                                    offset
                                )
                            )
                        )
                        offset += 1
                        Log.d(
                            "SN",
                            "D :" + String.format(
                                "%02d",
                                characteristic.getIntValue(
                                    BluetoothGattCharacteristic.FORMAT_UINT8,
                                    offset
                                )
                            )
                        )
                        offset += 1
                        Log.d(
                            "SN",
                            "H :" + String.format(
                                "%02d",
                                characteristic.getIntValue(
                                    BluetoothGattCharacteristic.FORMAT_UINT8,
                                    offset
                                )
                            )
                        )
                        offset += 1
                        Log.d(
                            "SN",
                            "M :" + String.format(
                                "%02d",
                                characteristic.getIntValue(
                                    BluetoothGattCharacteristic.FORMAT_UINT8,
                                    offset
                                )
                            )
                        )
                        offset += 1
                        Log.d(
                            "SN",
                            "S :" + String.format(
                                "%02d",
                                characteristic.getIntValue(
                                    BluetoothGattCharacteristic.FORMAT_UINT8,
                                    offset
                                )
                            )
                        )
                        offset += 1
                    } else {
                        val calendar = Calendar.getInstance(Locale.getDefault())
                        //Use calendar to get the date and time
                    }
                } else if (index == flagString.length - 2) {
                    if (key == "1") {
                        // Pulse Rate
                        Log.d(
                            "SN",
                            "Pulse Rate :" + String.format(
                                "%f",
                                characteristic.getFloatValue(
                                    BluetoothGattCharacteristic.FORMAT_SFLOAT,
                                    offset
                                )
                            )
                        )
                        pulse = String.format(
                            "%f",
                            characteristic.getFloatValue(
                                BluetoothGattCharacteristic.FORMAT_SFLOAT,
                                offset
                            )
                        )
                        pulse_display = String.format(
                            "%.0f",
                            characteristic.getFloatValue(
                                BluetoothGattCharacteristic.FORMAT_SFLOAT,
                                offset
                            )
                        )
                        offset += 2
                    }
                } else if (index == flagString.length - 3) {
                    // UserID
                } else if (index == flagString.length - 4) {
                    // Measurement Status Flag
                    val statusFalg = characteristic.getIntValue(
                        BluetoothGattCharacteristic.FORMAT_UINT16,
                        offset
                    )
                    val statusFlagString = Integer.toBinaryString(statusFalg)
                    var i = statusFlagString.length
                    while (0 < i) {
                        val status = statusFlagString.substring(i - 1, i)
                        if (i == statusFlagString.length) {
                        } else if (i == statusFlagString.length - 1) {
                        } else if (i == statusFlagString.length - 2) {
                        } else if (i == statusFlagString.length - 3) {
                            i--
                            val secondStatus = statusFlagString.substring(i - 1, i)
                            if (status.endsWith("1") && secondStatus.endsWith("0")) {
                                Log.d("AD", "Pulse range detection is 1")
                            } else if (status.endsWith("0") && secondStatus.endsWith("1")) {
                                Log.d("AD", "Pulse range detection is 2")
                            } else if (status.endsWith("1") && secondStatus.endsWith("1")) {
                                Log.d("AD", "Pulse range detection is 3")
                            } else {
                                Log.d("AD", "Pulse range detection is 0")
                            }
                        } else if (i == statusFlagString.length - 5) {
                            Log.d("AD", "Measurment position detection")
                        }
                        i--
                    }
                }
                index--
            }
            MainFragment.data.postValue(
                TonometerData(
                    systolic_display,
                    diastolic_display,
                    pulse_display
                )
            )
        }
    }

    fun setIndication(gatt: BluetoothGatt?, enable: Boolean): Boolean {
        var isSuccess = false
        if (gatt != null) {
            val service: BluetoothGattService? = getGattSearvice(gatt)
            if (service != null) {
                val characteristic: BluetoothGattCharacteristic? =
                    getInstance()?.getGattMeasuCharacteristic(service)

                if (characteristic != null) {
                    isSuccess = gatt.setCharacteristicNotification(characteristic, enable)
                    val descriptor =
                        characteristic.getDescriptor(ADGattUUID.ClientCharacteristicConfiguration)
                    if (enable) {
                        descriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                    } else {
                        descriptor.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                    }
                } else {

                }
            } else {

            }
        }
        return isSuccess
    }

    fun getGattMeasuCharacteristic(service: BluetoothGattService): BluetoothGattCharacteristic? {
        var characteristic: BluetoothGattCharacteristic? = null
        for (uuid in ADGattUUID.MeasuCharacUUIDs) {
            characteristic = service.getCharacteristic(uuid)
            if (characteristic != null) break
        }
        return characteristic
    }


    inner class TonometerBinder : Binder() {
        val service: TonometerService
            get() = this@TonometerService
    }
}