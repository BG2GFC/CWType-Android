package com.cwcontest

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber

/**
 * USBSerialManager
 *
 * Wraps usb-serial-for-android to:
 *  - enumerate connected USB serial devices (CH340, CP210x, FTDI, …)
 *  - open the selected port
 *  - expose setRTS() / setDTR() for CW keying
 *
 * Dependency (build.gradle):
 *   implementation 'com.github.mik3y:usb-serial-for-android:3.4.6'
 */
class USBSerialManager(private val context: Context) {

    companion object {
        private const val TAG = "USBSerial"
        private const val ACTION_USB_PERMISSION = "com.cwcontest.USB_PERMISSION"
    }

    // ── State ──────────────────────────────────────────────────────────────────
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var port: UsbSerialPort? = null
    private var isOpen = false

    // ── Callbacks ──────────────────────────────────────────────────────────────
    interface Listener {
        fun onDeviceAttached(device: UsbDevice)
        fun onDeviceDetached()
        fun onPortOpened()
        fun onPortClosed()
        fun onError(msg: String)
    }
    var listener: Listener? = null

    // ── Available devices ─────────────────────────────────────────────────────
    data class DeviceInfo(
        val driver: UsbSerialDriver,
        val portIndex: Int,
        val displayName: String
    )

    fun listDevices(): List<DeviceInfo> {
        val prober = UsbSerialProber.getDefaultProber()
        return prober.findAllDrivers(usbManager).flatMapIndexed { _, driver ->
            driver.ports.mapIndexed { index, _ ->
                DeviceInfo(
                    driver     = driver,
                    portIndex  = index,
                    displayName = buildDeviceName(driver, index)
                )
            }
        }
    }

    private fun buildDeviceName(driver: UsbSerialDriver, portIndex: Int): String {
        val device  = driver.device
        val chipName = driver.javaClass.simpleName
            .replace("SerialDriver", "")
            .replace("Usb", "")
        return "$chipName VID:${"%04X".format(device.vendorId)} " +
               "PID:${"%04X".format(device.productId)} Port $portIndex"
    }

    // ── Permission + Open ─────────────────────────────────────────────────────

    /**
     * @param idleLevel level the key line must hold while idle – equals
     *        `invertLogic` from the app settings, so an inverted keyer never
     *        keys the transmitter between messages.
     */
    fun requestPermissionAndOpen(
        info: DeviceInfo,
        baudRate: Int = 9600,
        idleLevel: Boolean = false
    ) {
        this.idleLevel = idleLevel
        val device = info.driver.device
        if (usbManager.hasPermission(device)) {
            openPort(info, baudRate)
        } else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE else 0
            val permIntent = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_PERMISSION), flags
            )
            // Store pending open info for the receiver
            pendingOpen  = Pair(info, baudRate)
            usbManager.requestPermission(device, permIntent)
        }
    }

    private var pendingOpen: Pair<DeviceInfo, Int>? = null
    private var idleLevel = false

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted) {
                pendingOpen?.let { (info, baud) -> openPort(info, baud) }
            } else {
                listener?.onError("USB permission denied")
            }
            pendingOpen = null
        }
    }

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                closePort()
                listener?.onDeviceDetached()
            }
        }
    }

    private val attachReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
                val dev = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                else
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                dev?.let { listener?.onDeviceAttached(it) }
            }
        }
    }

    fun registerReceivers() {
        if (receiversRegistered) return
        receiversRegistered = true
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Context.RECEIVER_NOT_EXPORTED else 0

        context.registerReceiver(permissionReceiver, IntentFilter(ACTION_USB_PERMISSION), flags)
        context.registerReceiver(detachReceiver,
            IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED))
        context.registerReceiver(attachReceiver,
            IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED))
    }

    fun unregisterReceivers() {
        if (!receiversRegistered) return
        receiversRegistered = false
        runCatching { context.unregisterReceiver(permissionReceiver) }
        runCatching { context.unregisterReceiver(detachReceiver) }
        runCatching { context.unregisterReceiver(attachReceiver) }
    }

    private var receiversRegistered = false

    // ── Port Open / Close ─────────────────────────────────────────────────────

    private fun openPort(info: DeviceInfo, baudRate: Int) {
        try {
            val connection = usbManager.openDevice(info.driver.device)
                ?: run { listener?.onError("Cannot open USB device"); return }

            port = info.driver.ports[info.portIndex]
            port!!.open(connection)
            port!!.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

            // Start with the key released (level depends on the logic setting)
            port!!.rts = idleLevel
            port!!.dtr = idleLevel

            isOpen = true
            listener?.onPortOpened()
            Log.i(TAG, "Port opened: ${info.displayName} @ ${baudRate} baud")
        } catch (e: Exception) {
            Log.e(TAG, "Open failed", e)
            listener?.onError("Open failed: ${e.message}")
            isOpen = false
        }
    }

    fun closePort() {
        try {
            port?.rts = idleLevel
            port?.dtr = idleLevel
            port?.close()
        } catch (e: Exception) { /* ignore */ }
        port   = null
        isOpen = false
        listener?.onPortClosed()
        Log.i(TAG, "Port closed")
    }

    // ── Key Control (called from CWEngine worker thread) ──────────────────────

    fun setRTS(active: Boolean) {
        try { port?.rts = active } catch (e: Exception) { Log.w(TAG, "setRTS failed: ${e.message}") }
    }

    fun setDTR(active: Boolean) {
        try { port?.dtr = active } catch (e: Exception) { Log.w(TAG, "setDTR failed: ${e.message}") }
    }

    // ── Status ────────────────────────────────────────────────────────────────

    fun isConnected(): Boolean = isOpen && port != null

    fun getStatusString(): String {
        if (!isOpen) return "Disconnected"
        val p = port ?: return "Disconnected"
        return "Connected | RTS:${p.rts} DTR:${p.dtr}"
    }
}
