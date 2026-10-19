@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.syed.tvpilot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.syed.tvpilot.data.BrainMode
import dev.syed.tvpilot.data.GEMINI_URL
import dev.syed.tvpilot.data.SpeechEngine
import dev.syed.tvpilot.data.Settings

@Composable
fun SettingsScreen(
    initial: Settings,
    onSave: (Settings) -> Unit,
    onBack: () -> Unit,
    btStatus: dev.syed.tvpilot.tv.BtRemote.Status = dev.syed.tvpilot.tv.BtRemote.Status(dev.syed.tvpilot.tv.BtRemote.State.OFF, "Off"),
    onBtPair: (Settings) -> Unit = {},
    onBtFromTv: (Settings) -> Unit = {},
) {
    var s by remember(initial) { mutableStateOf(initial) }
    var portText by remember(initial) { mutableStateOf(initial.tvPort.toString()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section("TV") {
                OutlinedTextField(
                    value = s.tvHost, onValueChange = { s = s.copy(tvHost = it) },
                    label = { Text("TV IP address") }, placeholder = { Text("192.168.1.50") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    supportingText = { Text("TV: Settings › Network & Internet › your Wi-Fi") },
                )
                OutlinedTextField(
                    value = portText, onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                    label = { Text("ADB port") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }

            Section("Brain") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = s.brainMode == BrainMode.CHAT,
                        onClick = { s = s.copy(brainMode = BrainMode.CHAT) },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text("Vision model") }
                    SegmentedButton(
                        selected = s.brainMode == BrainMode.DECISION,
                        onClick = { s = s.copy(brainMode = BrainMode.DECISION) },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text("d1 only") }
                }
                Text(
                    if (s.brainMode == BrainMode.CHAT)
                        "Looks at the TV screen and taps, swipes and presses its way to what you asked for. If it fails or runs out of quota, d1 takes over. Any OpenAI-compatible API with images; Gemini: $GEMINI_URL"
                    else
                        "Liquid AI d1-3B only: fast, but blind. It opens apps and searches but can't see the screen.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (s.brainMode == BrainMode.CHAT) {
                    OutlinedTextField(
                        value = s.chatUrl, onValueChange = { s = s.copy(chatUrl = it) },
                        label = { Text("Vision API URL") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    OutlinedTextField(
                        value = s.chatKey, onValueChange = { s = s.copy(chatKey = it) },
                        label = { Text("Vision API key") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    OutlinedTextField(
                        value = s.chatModel, onValueChange = { s = s.copy(chatModel = it) },
                        label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Section(if (s.brainMode == BrainMode.CHAT) "Backup: d1" else "d1 server") {
                Text("Liquid AI d1-3B on your server. The app calls /v1/systemone.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = s.serverUrl, onValueChange = { s = s.copy(serverUrl = it) },
                    label = { Text("d1 server URL") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = s.apiKey, onValueChange = { s = s.copy(apiKey = it) },
                    label = { Text("d1 API key") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }

            Section("Custom instructions") {
                Text("Rules the agent always follows. Write one per line, and name the app when a rule is for one app.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = s.customInstructions, onValueChange = { s = s.copy(customInstructions = it.take(4000)) },
                    label = { Text("Instructions") },
                    placeholder = { Text("In Nuvio: after pressing Play on a movie or episode, wait for the providers to load, then pick the best one (PenguPlay first).") },
                    minLines = 4, maxLines = 12, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("${s.customInstructions.length} / 4000") },
                )
            }

            Section("Remote") {
                Toggle("Bluetooth remote", s.btRemote) { s = s.copy(btRemote = it) }
                Text(
                    "Your phone acts as a Bluetooth remote, so buttons and the agent's D-pad presses reach the TV instantly. Taps, typing and screenshots still go through ADB.",
                    style = MaterialTheme.typography.bodySmall,
                )
                androidx.compose.animation.AnimatedVisibility(s.btRemote) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val st = btStatus.state
                        val color = when (st) {
                            dev.syed.tvpilot.tv.BtRemote.State.CONNECTED -> MaterialTheme.colorScheme.primary
                            dev.syed.tvpilot.tv.BtRemote.State.FAILED, dev.syed.tvpilot.tv.BtRemote.State.UNSUPPORTED,
                            dev.syed.tvpilot.tv.BtRemote.State.NO_PERMISSION -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Text(btStatus.text, color = color, style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onBtPair(s.copy(tvPort = portText.toIntOrNull() ?: 5555)) }, shape = RoundedCornerShape(16.dp)) {
                                Text(if (st == dev.syed.tvpilot.tv.BtRemote.State.CONNECTED) "Re-pair" else "Pair with TV")
                            }
                            androidx.compose.material3.OutlinedButton(onClick = { onBtFromTv(s.copy(tvPort = portText.toIntOrNull() ?: 5555)) }, shape = RoundedCornerShape(16.dp)) {
                                Text("Pair from the TV")
                            }
                        }
                        Text(
                            "Pair with TV does it all over ADB. If that fails, tap Pair from the TV, then on the TV open Settings › Remotes & accessories › Pair accessory and pick your phone.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            Section("Voice") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = s.speechEngine == SpeechEngine.PARAKEET,
                        onClick = { s = s.copy(speechEngine = SpeechEngine.PARAKEET) },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text("Parakeet") }
                    SegmentedButton(
                        selected = s.speechEngine != SpeechEngine.PARAKEET,
                        onClick = { if (s.speechEngine == SpeechEngine.PARAKEET) s = s.copy(speechEngine = SpeechEngine.GOOGLE) },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text("Google") }
                }
                androidx.compose.animation.AnimatedVisibility(s.speechEngine != SpeechEngine.PARAKEET) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = s.speechEngine == SpeechEngine.GOOGLE,
                                onClick = { s = s.copy(speechEngine = SpeechEngine.GOOGLE) },
                                shape = SegmentedButtonDefaults.itemShape(0, 2),
                            ) { Text("Phone") }
                            SegmentedButton(
                                selected = s.speechEngine == SpeechEngine.GEMINI,
                                onClick = { s = s.copy(speechEngine = SpeechEngine.GEMINI) },
                                shape = SegmentedButtonDefaults.itemShape(1, 2),
                            ) { Text("Gemini API") }
                        }
                        if (s.speechEngine == SpeechEngine.GOOGLE) {
                            Text("Your phone's built-in Google speech recogniser. Free, no key needed.", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text("Records your voice and sends it to Gemini 3.5 Transcribe (free tier). Great with Hinglish and names, and it knows your TV app names. Leave the key blank to reuse the agent's Gemini key.",
                                style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(
                                value = s.geminiSpeechKey, onValueChange = { s = s.copy(geminiSpeechKey = it) },
                                label = { Text("Gemini API key") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            )
                            OutlinedTextField(
                                value = s.geminiSpeechModel, onValueChange = { s = s.copy(geminiSpeechModel = it) },
                                label = { Text("Gemini model") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                if (s.speechEngine == SpeechEngine.PARAKEET) {
                    Text("NVIDIA Parakeet TDT 0.6B on your server. Tap the mic, talk, and it stops when you go quiet.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = s.asrUrl, onValueChange = { s = s.copy(asrUrl = it) },
                        label = { Text("Speech server URL") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    OutlinedTextField(
                        value = s.asrKey, onValueChange = { s = s.copy(asrKey = it) },
                        label = { Text("Speech server key") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }
            }

            Section("Appearance") {
                Toggle("AMOLED black", s.amoled) { s = s.copy(amoled = it) }
                Toggle("Focus home (centered mic)", s.focusHome) { s = s.copy(focusHome = it) }
                androidx.compose.animation.AnimatedVisibility(!s.focusHome) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Toggle("Show apps on home", s.showApps) { s = s.copy(showApps = it) }
                        Toggle("Show remote on home", s.showRemote) { s = s.copy(showRemote = it) }
                    }
                }
                Text(
                    if (s.focusHome) "Apps and the remote live behind the remote button at the bottom." else "The classic home with your apps and remote on screen.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Section("Behaviour") {
                Toggle("Speak replies", s.speakReplies) { s = s.copy(speakReplies = it) }
                Toggle("Auto-play first YouTube result", s.autoPlayFirst) { s = s.copy(autoPlayFirst = it) }
            }

            Button(
                onClick = { onSave(s.copy(tvPort = portText.toIntOrNull() ?: 5555)); onBack() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
            ) { Text("Save and connect", modifier = Modifier.padding(vertical = 6.dp)) }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLargeEmphasized)
            content()
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
