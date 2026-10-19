package dev.syed.tvpilot.tv

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * The phone pretends to be a Bluetooth keyboard + media remote (HID), so remote buttons reach the TV
 * instantly instead of through `adb shell input keyevent`. Taps, typing and screenshots still use ADB.
 */
@SuppressLint("MissingPermission", "NewApi")
object BtRemote {
    enum class State { OFF, NO_PERMISSION, UNSUPPORTED, STARTING, READY, PAIRING, CONNECTING, CONNECTED, FAILED }
    data class Status(val state: State, val text: String)

    private val _status = MutableStateFlow(Status(State.OFF, "Off"))
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Called with the TV's address when it connects, so it can be remembered. */
    var onHostConnected: (String) -> Unit = {}

    private lateinit var app: Context
    private var hid: BluetoothHidDevice? = null
    private var registered = false
    private var host: BluetoothDevice? = null
    private var target: String? = null
    private var bondReceiver: BroadcastReceiver? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val sendLock = Mutex()

    val isConnected get() = host != null && _status.value.state == State.CONNECTED

    fun hasPermission(ctx: Context) = Build.VERSION.SDK_INT < 31 ||
        ctx.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun adapter(): BluetoothAdapter? = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun set(state: State, text: String) { _status.value = Status(state, text) }

    /** Registers the phone as a remote and reconnects to [mac] when given. Safe to call repeatedly. */
    fun start(ctx: Context, mac: String?) {
        app = ctx.applicationContext
        if (!mac.isNullOrBlank()) target = mac.uppercase()
        if (Build.VERSION.SDK_INT < 28) { set(State.UNSUPPORTED, "Needs Android 9 or newer"); return }
        if (!hasPermission(app)) { set(State.NO_PERMISSION, "Allow Nearby devices to use Bluetooth"); return }
        val ad = adapter()
        if (ad == null) { set(State.UNSUPPORTED, "This phone has no Bluetooth"); return }
        if (!ad.isEnabled) { set(State.FAILED, "Turn on Bluetooth on your phone"); return }
        if (registered) { connectTarget(); return }
        if (hid != null) return
        set(State.STARTING, "Starting Bluetooth remote…")
        val ok = ad.getProfileProxy(app, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                hid = proxy as BluetoothHidDevice
                register()
            }
            override fun onServiceDisconnected(profile: Int) {
                hid = null; registered = false; host = null
                set(State.OFF, "Bluetooth remote stopped")
            }
        }, BluetoothProfile.HID_DEVICE)
        if (!ok) set(State.UNSUPPORTED, "This phone doesn't allow Bluetooth remotes")
    }

    private fun register() {
        val h = hid ?: return
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "TV Pilot Remote", "Bluetooth HID Device", "TV Pilot",
            BluetoothHidDevice.SUBCLASS2_UNCATEGORIZED, DESCRIPTOR,
        )
        val ok = h.registerApp(sdp, null, null, executor, callback)
        if (!ok) set(State.FAILED, "Another app is using the Bluetooth remote slot")
    }

    private val callback by lazy { object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, isRegistered: Boolean) {
            registered = isRegistered
            if (!isRegistered) { host = null; set(State.OFF, "Bluetooth remote stopped"); return }
            if (pluggedDevice != null) hid?.connect(pluggedDevice)
            if (target != null) connectTarget() else set(State.READY, "Ready. Tap Pair with TV")
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device; target = device.address
                    set(State.CONNECTED, "Connected to ${device.name ?: "your TV"}")
                    onHostConnected(device.address)
                }
                BluetoothProfile.STATE_CONNECTING -> set(State.CONNECTING, "Connecting to the TV…")
                BluetoothProfile.STATE_DISCONNECTED -> if (host == null || host == device) {
                    host = null
                    set(State.READY, "Not connected. Using ADB for now")
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            hid?.replyReport(device, type, id, ByteArray(2))
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }
    } }

    /** Bonds with the TV if needed, then opens the HID link. */
    fun pair(ctx: Context, mac: String) {
        target = mac.uppercase()
        if (!registered) { start(ctx, mac); return }
        connectTarget()
    }

    fun isBonded(mac: String): Boolean = runCatching {
        adapter()?.getRemoteDevice(mac.uppercase())?.bondState == BluetoothDevice.BOND_BONDED
    }.getOrDefault(false)

    private fun connectTarget() {
        val mac = target ?: return
        val h = hid ?: return
        val dev = runCatching { adapter()?.getRemoteDevice(mac) }.getOrNull() ?: run { set(State.FAILED, "Bad TV Bluetooth address"); return }
        if (host == dev) return
        if (dev.bondState == BluetoothDevice.BOND_BONDED) {
            set(State.CONNECTING, "Connecting to the TV…")
            if (!h.connect(dev)) set(State.READY, "Couldn't connect yet. Using ADB for now")
            return
        }
        set(State.PAIRING, "Pairing… accept on the TV and phone")
        listenForBond()
        if (dev.bondState != BluetoothDevice.BOND_BONDING && !dev.createBond()) set(State.FAILED, "The TV didn't answer the pairing request")
    }

    private fun listenForBond() {
        if (bondReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val dev: BluetoothDevice = (if (Build.VERSION.SDK_INT >= 33)
                    i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)) ?: return
                if (!dev.address.equals(target, true)) return
                when (i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                    BluetoothDevice.BOND_BONDED -> { set(State.CONNECTING, "Paired. Connecting…"); hid?.connect(dev) }
                    BluetoothDevice.BOND_NONE -> set(State.FAILED, "Pairing was cancelled. Try again")
                }
            }
        }
        bondReceiver = r
        val f = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_EXPORTED) else app.registerReceiver(r, f)
    }

    fun stop() {
        runCatching { bondReceiver?.let { app.unregisterReceiver(it) } }
        bondReceiver = null
        runCatching { hid?.unregisterApp() }
        runCatching { hid?.let { adapter()?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) } }
        hid = null; registered = false; host = null
        set(State.OFF, "Off")
    }

    /** Sends [key] over Bluetooth. Returns false when it can't (not connected, or a key Bluetooth can't do). */
    suspend fun send(key: TvKey, times: Int = 1): Boolean = withContext(Dispatchers.IO) {
        val h = hid ?: return@withContext false
        val dev = host ?: return@withContext false
        if (_status.value.state != State.CONNECTED) return@withContext false
        val usage = map[key] ?: return@withContext false
        sendLock.withLock {
            repeat(times.coerceIn(1, 25)) { n ->
                val bytes = if (usage.consumer) byteArrayOf((usage.code and 0xFF).toByte(), (usage.code shr 8).toByte())
                    else byteArrayOf(0, usage.code.toByte())
                val id = if (usage.consumer) ID_CONSUMER else ID_KEYBOARD
                // Hold the button briefly like a real remote; an instant release gets dropped by some TVs.
                val ok = h.sendReport(dev, id, bytes)
                delay(HOLD_MS)
                h.sendReport(dev, id, ByteArray(2))
                if (!ok) return@withContext n > 0
                if (n < times - 1) delay(GAP_MS)
            }
        }
        true
    }

    private data class Usage(val code: Int, val consumer: Boolean)

    private val map = mapOf(
        // D-pad on the consumer "Menu" usages, same as OK, which Google TV maps to DPAD_* (as BT Remote does).
        TvKey.UP to Usage(0x42, true),
        TvKey.DOWN to Usage(0x43, true),
        TvKey.LEFT to Usage(0x44, true),
        TvKey.RIGHT to Usage(0x45, true),
        TvKey.OK to Usage(0x41, true),          // Menu Pick → DPAD_CENTER
        TvKey.BACK to Usage(0x224, true),       // AC Back
        TvKey.HOME to Usage(0x223, true),       // AC Home
        TvKey.PLAY_PAUSE to Usage(0xCD, true),
        TvKey.NEXT to Usage(0xB5, true),
        TvKey.PREVIOUS to Usage(0xB6, true),
        TvKey.FAST_FORWARD to Usage(0xB3, true),
        TvKey.REWIND to Usage(0xB4, true),
        TvKey.VOLUME_UP to Usage(0xE9, true),
        TvKey.VOLUME_DOWN to Usage(0xEA, true),
        TvKey.MUTE to Usage(0xE2, true),
        TvKey.SLEEP to Usage(0x30, true),       // Power
        // WAKE stays on ADB.
    )

    private const val ID_KEYBOARD = 1
    private const val ID_CONSUMER = 2

    private const val HOLD_MS = 40L
    private const val GAP_MS = 60L

    // Same layout as Atharok's BT Remote, which works on the user's TCL: consumer control (report 2)
    // first, then a 2-byte keyboard (report 1: modifiers + one key).
    private val DESCRIPTOR = intArrayOf(
        0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x85, ID_CONSUMER,
        0x19, 0x00, 0x2A, 0xFF, 0x03, 0x75, 0x10, 0x95, 0x01, 0x15, 0x00, 0x26, 0xFF, 0x03, 0x81, 0x00,
        0xC0,
        0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, ID_KEYBOARD,
        0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
        0x75, 0x08, 0x95, 0x01, 0x15, 0x00, 0x26, 0xFF, 0x00, 0x05, 0x07, 0x19, 0x00, 0x29, 0xFF, 0x81, 0x00,
        0xC0,
    ).map { it.toByte() }.toByteArray()
}
