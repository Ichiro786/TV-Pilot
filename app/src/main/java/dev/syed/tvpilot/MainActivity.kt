package dev.syed.tvpilot

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.syed.tvpilot.ui.HomeScreen
import dev.syed.tvpilot.ui.MainViewModel
import dev.syed.tvpilot.ui.SettingsScreen
import dev.syed.tvpilot.ui.theme.TvPilotTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    // Android 17 blocks LAN sockets until the user grants "Nearby devices / local network".
    private val lanPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.connect()
        else Toast.makeText(this, "TV Pilot needs Local network access to reach your TV", Toast.LENGTH_LONG).show()
    }

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.toggleListening()
        else Toast.makeText(this, "TV Pilot needs the mic to hear you", Toast.LENGTH_LONG).show()
    }

    private var afterBt: (() -> Unit)? = null
    private val btPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[android.Manifest.permission.BLUETOOTH_CONNECT] != false) afterBt?.invoke()
        else Toast.makeText(this, "TV Pilot needs Nearby devices to be a Bluetooth remote", Toast.LENGTH_LONG).show()
        afterBt = null
    }

    private fun withBt(block: () -> Unit) {
        if (dev.syed.tvpilot.tv.BtRemote.hasPermission(this)) { block(); return }
        afterBt = block
        btPermission.launch(arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT, android.Manifest.permission.BLUETOOTH_ADVERTISE))
    }

    private val discoverable = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    private fun ensureLanAccess(): Boolean {
        if (Build.VERSION.SDK_INT < 37) return true
        val perm = "android.permission.ACCESS_LOCAL_NETWORK"
        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) return true
        lanPermission.launch(perm)
        return false
    }

    /** Set when the home-screen widget's mic was tapped; the UI starts listening once it's up. */
    private val widgetListen = mutableStateOf(0)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == dev.syed.tvpilot.widget.TvWidgets.ACTION_LISTEN) widgetListen.value++
    }

    private fun settingsBtOnStart() = vm.settings.value.btRemote

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        ensureLanAccess()
        if (savedInstanceState == null && intent?.action == dev.syed.tvpilot.widget.TvWidgets.ACTION_LISTEN) widgetListen.value++
        if (settingsBtOnStart()) withBt { vm.btStart() }
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            TvPilotTheme(amoled = settings.amoled) {
                val ui by vm.ui.collectAsStateWithLifecycle()
                var inSettings by rememberSaveable { mutableStateOf(false) }

                // Voice goes through the phone's own speech recogniser, so no mic permission is needed.
                val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
                    if (res.resultCode == Activity.RESULT_OK) {
                        res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(vm::send)
                    }
                }
                val googleListen = {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_PROMPT, "What should the TV do?")
                    try { speech.launch(intent) } catch (_: ActivityNotFoundException) {
                        Toast.makeText(this, "No speech recogniser on this phone", Toast.LENGTH_SHORT).show()
                    }
                }

                val listen = {
                    if (settings.speechEngine == dev.syed.tvpilot.data.SpeechEngine.GOOGLE) googleListen()
                    else if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.toggleListening()
                    else micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                }

                val wl = widgetListen.value
                androidx.compose.runtime.LaunchedEffect(wl) {
                    if (wl > 0) {
                        inSettings = false
                        kotlinx.coroutines.delay(250)
                        if (!vm.ui.value.listening) listen()
                    }
                }

                BackHandler(enabled = inSettings) { inSettings = false }

                androidx.compose.animation.AnimatedContent(
                    targetState = inSettings,
                    transitionSpec = {
                        val dir = if (targetState) 1 else -1
                        (androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.spring(0.9f, 380f)) { it / 4 * dir } +
                            androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220))) togetherWith
                            (androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.spring(0.9f, 380f)) { -it / 4 * dir } +
                                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160)))
                    },
                    label = "screen",
                ) { settingsOpen ->
                if (settingsOpen) {
                    val bt by dev.syed.tvpilot.tv.BtRemote.status.collectAsStateWithLifecycle()
                    SettingsScreen(
                        initial = settings, onSave = vm::saveSettings, onBack = { inSettings = false },
                        btStatus = bt,
                        onBtPair = { cur -> withBt { vm.btPair(cur) } },
                        onBtFromTv = { cur ->
                            withBt {
                                vm.saveSettings(cur.copy(btRemote = true))
                                vm.btStart()
                                runCatching {
                                    discoverable.launch(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                                        .putExtra(android.bluetooth.BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 180))
                                }
                            }
                        },
                    )
                } else if (settings.focusHome) {
                    dev.syed.tvpilot.ui.FocusHome(
                        state = ui,
                        onSend = vm::send,
                        onMic = listen,
                        onKey = vm::press,
                        onOpen = vm::open,
                        onConnect = { if (ensureLanAccess()) vm.connect() },
                        onClear = vm::clear,
                        onSettings = { inSettings = true },
                        onStop = vm::stop,
                    )
                } else {
                    HomeScreen(
                        state = ui,
                        onSend = vm::send,
                        onMic = listen,
                        onKey = vm::press,
                        onOpen = vm::open,
                        onConnect = { if (ensureLanAccess()) vm.connect() },
                        onClear = vm::clear,
                        onSettings = { inSettings = true },
                        onStop = vm::stop,
                        showApps = settings.showApps,
                        showRemote = settings.showRemote,
                    )
                }
                }
            }
        }
    }
}
