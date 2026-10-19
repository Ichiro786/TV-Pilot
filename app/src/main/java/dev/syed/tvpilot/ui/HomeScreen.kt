@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.syed.tvpilot.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.syed.tvpilot.tv.Connection
import dev.syed.tvpilot.tv.TvApp
import dev.syed.tvpilot.tv.TvKey

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    state: UiState,
    onSend: (String) -> Unit,
    onMic: () -> Unit,
    onKey: (TvKey) -> Unit,
    onOpen: (TvApp) -> Unit,
    onConnect: () -> Unit,
    onClear: () -> Unit,
    onSettings: () -> Unit,
    onStop: () -> Unit = {},
    showApps: Boolean = true,
    showRemote: Boolean = true,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val list = rememberLazyListState()
    var showRemote by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(state.lines.size, state.thinking) {
        val count = list.layoutInfo.totalItemsCount
        if (state.lines.isNotEmpty() && count > 0) list.animateScrollToItem(count - 1)
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("TV Pilot", fontWeight = FontWeight.Black) },
                subtitle = { ConnectionLabel(state.connection, state.onScreen) },
                actions = {
                    IconButton(onClick = onConnect) { Icon(Icons.Rounded.Refresh, "Reconnect") }
                    if (state.lines.isNotEmpty()) IconButton(onClick = onClear) { Icon(Icons.Rounded.DeleteSweep, "Clear") }
                    IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Settings") }
                },
                scrollBehavior = scroll,
            )
        },
        bottomBar = { InputBar(busy = state.thinking, listening = state.listening, level = state.micLevel, onSend = onSend, onMic = onMic) },
    ) { pad ->
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.connection !is Connection.Connected) {
                item { ConnectCard(state.connection, onConnect, onSettings) }
            }
            if (showApps && state.apps.isNotEmpty()) {
                item {
                    Text("Your apps", style = MaterialTheme.typography.titleMediumEmphasized, modifier = Modifier.padding(start = 4.dp))
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        itemsIndexed(state.apps.take(16), key = { _, a -> a.packageName }) { i, app ->
                            AppTile(app, i, selected = app == state.onScreen) { onOpen(app) }
                        }
                    }
                }
            }
            if (showRemote) item {
                Card(
                    shape = RoundedCornerShape(32.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Row(
                        Modifier.fillMaxWidth().clickable { showRemote = !showRemote }.padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Tv, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text("Remote", style = MaterialTheme.typography.titleMediumEmphasized, modifier = Modifier.weight(1f))
                        Icon(if (showRemote) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, null)
                    }
                    AnimatedVisibility(showRemote) { Remote(onKey) }
                }
            }
            if (state.lines.isEmpty() && state.connection is Connection.Connected) {
                item { Suggestions(onSend) }
            }
            items(state.lines, key = { it.id }) { line -> ChatBubble(line) }
            if (state.thinking) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LoadingIndicator(Modifier.size(48.dp))
                        Text(state.status ?: "Thinking…", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 2)
                        androidx.compose.material3.TextButton(onClick = onStop) { Text("Stop") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionLabel(c: Connection, onScreen: TvApp?) {
    val text = when (c) {
        is Connection.Connected -> "Connected · " + (onScreen?.label?.let { "$it on screen" } ?: c.host)
        Connection.Connecting -> "Connecting… allow this phone on your TV"
        Connection.Disconnected -> "Not connected"
        is Connection.Failed -> "Offline"
    }
    Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ConnectCard(c: Connection, onConnect: () -> Unit, onSettings: () -> Unit) {
    Card(
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                if (c is Connection.Connecting) "Look at your TV" else "Connect your TV",
                style = MaterialTheme.typography.headlineSmallEmphasized,
            )
            Text(
                when (c) {
                    Connection.Connecting -> "Tap Allow on the \"Allow USB debugging?\" prompt, and tick Always allow."
                    is Connection.Failed -> c.reason
                    else -> "Phone and TV on the same Wi-Fi, USB debugging on, then add the TV's IP in Settings."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (c is Connection.Connecting) {
                LoadingIndicator(Modifier.size(40.dp))
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onConnect) { Text("Connect") }
                    Button(onClick = onSettings) { Text("Settings") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AppTile(app: TvApp, index: Int, selected: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.88f else 1f, spring(dampingRatio = 0.4f, stiffness = 600f), label = "tile")
    val shape = PilotShapes.appTiles[index % PilotShapes.appTiles.size].toShape()
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp)) {
        Box(
            Modifier
                .size(68.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(shape)
                .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer)
                .clickable(interactionSource = src, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                app.label.take(1),
                style = MaterialTheme.typography.headlineMediumEmphasized,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(app.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun Remote(onKey: (TvKey) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // D-pad
        Box(Modifier.size(208.dp)) {
            PadButton(Icons.Rounded.KeyboardArrowUp, "Up", Modifier.align(Alignment.TopCenter)) { onKey(TvKey.UP) }
            PadButton(Icons.Rounded.KeyboardArrowDown, "Down", Modifier.align(Alignment.BottomCenter)) { onKey(TvKey.DOWN) }
            PadButton(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Left", Modifier.align(Alignment.CenterStart)) { onKey(TvKey.LEFT) }
            PadButton(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Right", Modifier.align(Alignment.CenterEnd)) { onKey(TvKey.RIGHT) }
            val src = remember { MutableInteractionSource() }
            val pressed by src.collectIsPressedAsState()
            val p by animateFloatAsState(if (pressed) 1f else 0f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "ok")
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(84.dp)
                    .clip(MorphShape(PilotShapes.micMorph, p))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(interactionSource = src, indication = null) { onKey(TvKey.OK) },
                contentAlignment = Alignment.Center,
            ) { Text("OK", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleLargeEmphasized) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RoundKey(Icons.AutoMirrored.Rounded.ArrowBack, "Back") { onKey(TvKey.BACK) }
            RoundKey(Icons.Rounded.Home, "Home") { onKey(TvKey.HOME) }
            RoundKey(Icons.Rounded.SkipPrevious, "Previous") { onKey(TvKey.PREVIOUS) }
            RoundKey(Icons.Rounded.PlayArrow, "Play or pause") { onKey(TvKey.PLAY_PAUSE) }
            RoundKey(Icons.Rounded.SkipNext, "Next") { onKey(TvKey.NEXT) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RoundKey(Icons.AutoMirrored.Rounded.VolumeDown, "Volume down") { onKey(TvKey.VOLUME_DOWN) }
            RoundKey(Icons.AutoMirrored.Rounded.VolumeOff, "Mute") { onKey(TvKey.MUTE) }
            RoundKey(Icons.AutoMirrored.Rounded.VolumeUp, "Volume up") { onKey(TvKey.VOLUME_UP) }
            RoundKey(Icons.Rounded.PowerSettingsNew, "Power") { onKey(TvKey.SLEEP) }
        }
    }
}

@Composable
private fun PadButton(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, modifier = modifier.size(60.dp), shape = RoundedCornerShape(22.dp)) {
        Icon(icon, label, Modifier.size(32.dp))
    }
}

@Composable
private fun RoundKey(icon: ImageVector, label: String, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    // Squircle that squishes into a tighter corner when pressed, the M3 Expressive way.
    val corner by animateFloatAsState(if (pressed) 14f else 28f, spring(dampingRatio = 0.45f, stiffness = 700f), label = "corner")
    FilledTonalIconButton(
        onClick = onClick,
        interactionSource = src,
        modifier = Modifier.size(56.dp),
        shape = RoundedCornerShape(corner.dp),
    ) { Icon(icon, label) }
}

@Composable
private fun Suggestions(onSend: (String) -> Unit) {
    val ideas = listOf("Play lofi beats on YouTube", "Open Netflix", "Watch Solo Leveling on Crunchyroll", "Pause", "Volume up a bit", "Go home")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Try saying", style = MaterialTheme.typography.titleMediumEmphasized, modifier = Modifier.padding(start = 4.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ideas) { s -> AssistChip(onClick = { onSend(s) }, label = { Text(s) }, shape = RoundedCornerShape(16.dp)) }
        }
    }
}

@Composable
internal fun ChatBubble(line: ChatLine) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (line.fromUser) Arrangement.End else Arrangement.Start) {
        Surface(
            color = when {
                line.isError -> cs.errorContainer
                line.fromUser -> cs.primaryContainer
                else -> cs.surfaceContainerHigh
            },
            shape = if (line.fromUser) RoundedCornerShape(28.dp, 28.dp, 8.dp, 28.dp) else RoundedCornerShape(28.dp, 28.dp, 28.dp, 8.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(line.text, style = MaterialTheme.typography.bodyLarge)
                if (line.chips.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        line.chips.take(3).forEach { chip ->
                            Text(
                                chip,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .clip(CircleShape)
                                    .background(cs.secondaryContainer)
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun InputBar(busy: Boolean, listening: Boolean, level: Float, onSend: (String) -> Unit, onMic: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val send = { if (text.isNotBlank()) { onSend(text); text = "" } }

    val spin = rememberInfiniteTransition(label = "mic")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(4000)), label = "angle")
    val pulse by spin.animateFloat(0f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")

    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(if (listening) "Listening…" else "Tell your TV what to do") },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0f),
                    unfocusedIndicatorColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0f),
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                trailingIcon = {
                    if (text.isNotBlank()) IconButton(onClick = send) { Icon(Icons.AutoMirrored.Rounded.Send, "Send") }
                },
                modifier = Modifier.weight(1f),
            )
            // The mic: a 9-sided cookie that morphs and spins while the TV is busy.
            Box(
                Modifier
                    .size(64.dp)
                    .graphicsLayer {
                        rotationZ = if (busy) angle else 0f
                        val grow = if (listening) 1f + level * 0.25f else 1f
                        scaleX = grow; scaleY = grow
                    }
                    .clip(MorphShape(PilotShapes.micMorph, if (busy) pulse else if (listening) level.coerceIn(0f, 1f) else 0f))
                    .background(if (listening) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary)
                    .clickable(enabled = !busy, onClick = onMic),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (listening) Icons.Rounded.Stop else Icons.Rounded.Mic, if (listening) "Stop" else "Speak",
                    tint = if (listening) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(30.dp).graphicsLayer { rotationZ = if (busy) -angle else 0f },
                )
            }
        }
    }
}
