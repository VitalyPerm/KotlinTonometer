package com.elvitalya.kotlintonometer

import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.*
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.*


@SuppressLint("MissingPermission")
class TonometerService : Service() {

    private lateinit var bluetoothGatt: BluetoothGatt
    private val uiThreadHandler = Handler(Looper.getMainLooper())
    private var setDateTimeDelay = Long.MIN_VALUE
    private var indicationDelay = Long.MIN_VALUE


    override fun onBind(intent: Intent?): IBinder {
        return TonometerBinder()
    }

    fun connectDevice(device: BluetoothDevice) {
        bluetoothGatt = device.connectGatt(this, false, bluetoothGattCallback)
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
        val gattService: BluetoothGattService? = getGattService(gatt)
        if (gattService != null) {
            var characteristic = gattService.getCharacteristic(ADGattUUID.DateTime)
            if (characteristic != null) {
                characteristic =
                    dateWriteCharacteristic(characteristic, cal)
                isSuccess = gatt.writeCharacteristic(characteristic)
            }
        }
        return isSuccess
    }

    private fun dateWriteCharacteristic(
        characteristic: BluetoothGattCharacteristic,
        calendar: Calendar
    ): BluetoothGattCharacteristic {
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

    private fun getGattService(gatt: BluetoothGatt): BluetoothGattService? {
        var service: BluetoothGattService? = null
        for (uuid in ADGattUUID.ServicesUUIDs) {
            service = gatt.getService(uuid)
            if (service != null) break
        }
        return service
    }

    fun requestReadFirmRevision() {
        val service = bluetoothGatt.getService(ADGattUUID.DeviceInformationService)
        if (service != null) {
            val characteristic = service.getCharacteristic(ADGattUUID.FirmwareRevisionString)
            if (characteristic != null) {
                bluetoothGatt.readCharacteristic(characteristic)
            }
        }
    }

    private val bluetoothGattCallback: BluetoothGattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                bluetoothGatt.close()
                bluetoothGatt.disconnect()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            super.onServicesDiscovered(gatt, status)
            uiThreadHandler.postDelayed({
                requestReadFirmRevision()
            }, 50L)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            super.onCharacteristicRead(gatt, characteristic, status)

            val firmRevisionBytes = characteristic.value ?: return

            val firmRevision = String(firmRevisionBytes)

            if (firmRevision.isEmpty()) return

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
                setupDateTime(gatt)
            }, setDateTimeDelay)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)

            val serviceUuidString = characteristic.service.uuid.toString()
            val characteristicUuidString = characteristic.uuid.toString()

            if (serviceUuidString == ADGattUUID.CurrentTimeService.toString()
                || characteristicUuidString == ADGattUUID.DateTime.toString()
            ) {
                uiThreadHandler.postDelayed({
                    setIndication(bluetoothGatt, true)
                }, indicationDelay)
            }

        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            super.onCharacteristicChanged(gatt, characteristic)
            parseCharacteristicValue(characteristic)
        }
    }

    fun parseCharacteristicValue(characteristic: BluetoothGattCharacteristic) {
        if (ADGattUUID.BloodPressureMeasurement.equals(characteristic.uuid)) {
            val flag = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0)
            val flagString = Integer.toBinaryString(flag)
            var systolic = ""
            var diastolic = ""
            var pulse = ""
            var offset = 0
            var index = flagString.length
            while (0 < index) {
                val key = flagString.substring(index - 1, index)
                if (index == flagString.length) {
                    offset += 1
                    systolic = String.format(
                        "%.0f",
                        characteristic.getFloatValue(
                            BluetoothGattCharacteristic.FORMAT_SFLOAT,
                            offset
                        )
                    )
                    offset += 2
                    diastolic = String.format(
                        "%.0f",
                        characteristic.getFloatValue(
                            BluetoothGattCharacteristic.FORMAT_SFLOAT,
                            offset
                        )
                    )
                    offset += 2
                    offset += 2
                } else if (index == flagString.length - 1) {
                    if (key == "1") {
                        // Time Stamp
                        offset += 2
                        offset += 1
                        offset += 1
                        offset += 1
                        offset += 1
                        offset += 1
                    }
                } else if (index == flagString.length - 2) {
                    if (key == "1") {
                        // Pulse Rate
                        pulse = String.format(
                            "%.0f",
                            characteristic.getFloatValue(
                                BluetoothGattCharacteristic.FORMAT_SFLOAT,
                                offset
                            )
                        )
                        offset += 2
                    }
                }
                index--
            }
            MainFragment.data.postValue(
                TonometerData(
                    systolic,
                    diastolic,
                    pulse
                )
            )
        }
    }

    fun setIndication(gatt: BluetoothGatt?, enable: Boolean) {
        if (gatt != null) {
            val service: BluetoothGattService? = getGattService(gatt)
            if (service != null) {
                val characteristic: BluetoothGattCharacteristic? =
                    getGattMeasureCharacteristic(service)

                if (characteristic != null) {
                    gatt.setCharacteristicNotification(characteristic, enable)
                    val descriptor =
                        characteristic.getDescriptor(ADGattUUID.ClientCharacteristicConfiguration)
                    if (enable) {
                        descriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                    } else {
                        descriptor.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                    }
                }
            }
        }
    }

    private fun getGattMeasureCharacteristic(service: BluetoothGattService): BluetoothGattCharacteristic? {
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