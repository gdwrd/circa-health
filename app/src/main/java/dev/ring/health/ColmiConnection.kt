package dev.ring.health

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

private const val TAG = "ColmiConnection"

// BLE connection to the Colmi R02.
// All suspend functions must be called from a coroutine; caller is responsible
// for calling disconnect() in a finally block.
@SuppressLint("MissingPermission")
class ColmiConnection(private val context: Context, private val address: String) {

    private var gatt: BluetoothGatt? = null

    // One-shot deferreds for connect / service-discover lifecycle
    private var connectedDeferred  = CompletableDeferred<Boolean>()
    private var discoveredDeferred = CompletableDeferred<Boolean>()

    // Channels for sequential GATT operations
    private val descriptorWriteCh = Channel<Boolean>(1)
    private val charWriteCh       = Channel<Boolean>(1)

    // Incoming notification channels — unbounded so we never drop a packet
    val notifyV1 = Channel<ByteArray>(Channel.UNLIMITED)
    val notifyV2 = Channel<ByteArray>(Channel.UNLIMITED)

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange status=$status state=$newState")
            val ok = newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS
            connectedDeferred.complete(ok)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            Log.d(TAG, "onServicesDiscovered status=$status")
            discoveredDeferred.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            descriptorWriteCh.trySend(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, char: BluetoothGattCharacteristic, status: Int) {
            charWriteCh.trySend(status == BluetoothGatt.GATT_SUCCESS)
        }

        // API 33+ path
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            char: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            dispatch(char.uuid, value)
        }

        // Pre-API-33 path (still called on older devices)
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, char: BluetoothGattCharacteristic) {
            dispatch(char.uuid, char.value ?: return)
        }

        private fun dispatch(uuid: UUID, value: ByteArray) {
            val copy = value.copyOf()
            when (uuid) {
                ColmiProtocol.CHAR_NOTIFY_V1 -> notifyV1.trySend(copy)
                ColmiProtocol.CHAR_NOTIFY_V2 -> notifyV2.trySend(copy)
                else -> Log.w(TAG, "Notification on unknown char: $uuid")
            }
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────

    suspend fun connect(timeoutMs: Long = 20_000): Boolean {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val device  = adapter.getRemoteDevice(address)
        gatt = device.connectGatt(context, false, callback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)

        val connected = withTimeoutOrNull(timeoutMs) { connectedDeferred.await() } ?: false
        if (!connected) {
            Log.w(TAG, "Connection timed out or failed")
            return false
        }

        gatt!!.discoverServices()
        val discovered = withTimeoutOrNull(10_000) { discoveredDeferred.await() } ?: false
        if (!discovered) {
            Log.w(TAG, "Service discovery failed")
            return false
        }

        return enableNotifications()
    }

    // Write a 16-byte framed packet to the V1 write characteristic (HR/HRV commands).
    suspend fun writePacket(data: ByteArray, timeoutMs: Long = 5_000): Boolean {
        return writeToChar(ColmiProtocol.CHAR_WRITE, data, timeoutMs)
    }

    // Write a raw command to the V2 command characteristic (sleep).
    suspend fun writeCommand(data: ByteArray, timeoutMs: Long = 5_000): Boolean {
        return writeToChar(ColmiProtocol.CHAR_COMMAND, data, timeoutMs)
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private suspend fun enableNotifications(): Boolean {
        val g = gatt ?: return false
        val allChars = g.services.flatMap { it.characteristics }

        for (notifyUuid in listOf(ColmiProtocol.CHAR_NOTIFY_V1, ColmiProtocol.CHAR_NOTIFY_V2)) {
            val char = allChars.find { it.uuid == notifyUuid }
            if (char == null) {
                Log.w(TAG, "Notify char not found: $notifyUuid")
                continue
            }
            g.setCharacteristicNotification(char, true)

            val cccd = char.getDescriptor(ColmiProtocol.CCCD_UUID)
            if (cccd == null) {
                Log.w(TAG, "No CCCD on $notifyUuid")
                continue
            }

            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                withTimeoutOrNull(5_000) { descriptorWriteCh.receive() } == true
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
                withTimeoutOrNull(5_000) { descriptorWriteCh.receive() } == true
            }

            if (!ok) {
                Log.w(TAG, "Failed to enable notifications on $notifyUuid")
                return false
            }
        }
        return true
    }

    private suspend fun writeToChar(charUuid: UUID, data: ByteArray, timeoutMs: Long): Boolean {
        val g = gatt ?: return false
        val char = g.services.flatMap { it.characteristics }.find { it.uuid == charUuid }
        if (char == null) {
            Log.w(TAG, "Char not found: $charUuid")
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(char, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            char.value = data
            @Suppress("DEPRECATION")
            g.writeCharacteristic(char)
        }

        return withTimeoutOrNull(timeoutMs) { charWriteCh.receive() } == true
    }
}
