@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package dev.syed.tvpilot.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.syed.tvpilot.tv.Connection
import dev.syed.tvpilot.tv.TvApp
import dev.syed.tvpilot.tv.TvKey
import kotlin.math.roundToInt

/**
 * The focus home: one big mic in the middle. Once you talk, the mic glides down into the bottom bar
 * and the conversation (with the live agent card) takes the stage.
 */
@Composable
fun FocusHome(
    state: UiState,
    onSend: (String) -> Unit,
    onMic: () -> Unit,
    onKey: (TvKey) -> Unit,
    onOpen: (TvApp) -> Unit,
    onConnect: () -> Unit,
    onClear: () -> Unit,
    onSettings: () -> Unit,
    onStop: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val idle = state.lines.isEmpty() && !state.thinking
    var showSheet by rememberSaveable { mutableStateOf(false) }
    var typing by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    val send = { if (text.isNotBlank()) { onSend(text.trim()); text = "" } }

    // One spring for the whole layout, so the mic, header and bar move as one gesture.
    val stage by animateFloatAsState(if (idle) 0f else 1f, spring(dampingRatio = 0.82f, stiffness = 260f), label = "stage")

    androidx.activity.compose.BackHandler(enabled = typing) { typing = false }
    val imeOpen = WindowInsets.isImeVisible
    Surface(Modifier.fillMaxSize(), color = cs.surface, contentColor = cs.onSurface) {
        BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            val h = maxHeight
            val micBig = 168.dp
            val micSmall = 76.dp
            val micSize = micBig + (micSmall - micBig) * stage
            val bottomBar = 104.dp
            // Idle mic sits a little lower than centre, leaving air under the headline.
            val heroY = h * 0.45f
            val centerY = h * 0.50f
            val dockY = h - bottomBar / 2
            val micCenter = centerY + (dockY - centerY) * stage

            // Conversation layer
            if (stage > 0.01f) {
                Conversation(
                    state = state,
                    onStop = onStop,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 64.dp, bottom = bottomBar + if (typing) 64.dp else 0.dp)
                        .graphicsLayer { alpha = stage; translationY = (1f - stage) * 80f },
                )
            }

            // Idle hero text, above and below the mic slot
            if (stage < 0.99f) {
                val a = 1f - stage
                Column(
                    Modifier.fillMaxWidth().offset { IntOffset(0, (heroY - micBig / 2 - 160.dp).roundToPx()) }
                        .graphicsLayer { alpha = a; translationY = -stage * 120f; scaleX = 1f - stage * 0.08f; scaleY = 1f - stage * 0.08f },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("TV Pilot", style = MaterialTheme.typography.titleMediumEmphasized, color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    StatusPill(state.connection, state.onScreen)
                    Spacer(Modifier.height(28.dp))
                    AnimatedContent(
                        targetState = state.listening,
                        transitionSpec = { (fadeIn(tween(220)) + slideInVertically { it / 2 }) togetherWith (fadeOut(tween(150)) + slideOutVertically { -it / 2 }) },
                        label = "heroTitle",
                    ) { l ->
                        Text(
                            if (l) "Listening…" else "Speak to your TV",
                            style = MaterialTheme.typography.headlineLargeEmphasized,
                            fontWeight = FontWeight.SemiBold,
                            color = cs.onSurface,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                    }
                }
                val lowerAlpha by animateFloatAsState(if (imeOpen) 0f else 1f, tween(200), label = "lower")
                Column(
                    Modifier.fillMaxWidth()
                        .offset { IntOffset(0, (centerY + micBig / 2 + 32.dp).roundToPx()) }
                        .graphicsLayer { alpha = a * lowerAlpha; translationY = stage * 160f + (1f - lowerAlpha) * 40f },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (state.listening) "Tap to stop" else "Tap to talk",
                        style = MaterialTheme.typography.headlineSmallEmphasized,
                        color = cs.onSurface,
                    )
                    Text("or type a command", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
                    if (state.connection !is Connection.Connected) {
                        Spacer(Modifier.height(20.dp))
                        ConnectHint(state.connection, onConnect, onSettings)
                    }
                }
                // Suggestions ride just above the command field.
                if (state.connection is Connection.Connected) Box(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp)
                        .graphicsLayer { alpha = a * lowerAlpha; translationY = stage * 120f + (1f - lowerAlpha) * 40f },
                ) { Suggestions(onSend) }
            }

            // Top bar
            Row(
                Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f).padding(start = 12.dp).graphicsLayer { alpha = stage }) {
                    if (stage > 0.01f) Column {
                        Text("TV Pilot", style = MaterialTheme.typography.titleLargeEmphasized, fontWeight = FontWeight.Black, color = cs.onSurface)
                        StatusLine(state.connection, state.onScreen)
                    }
                }
                IconButton(onClick = onConnect) { Icon(Icons.Rounded.Refresh, "Reconnect", tint = cs.onSurfaceVariant) }
                AnimatedVisibility(state.lines.isNotEmpty(), enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                    IconButton(onClick = { onClear(); typing = false }) { Icon(Icons.Rounded.DeleteSweep, "Clear", tint = cs.onSurfaceVariant) }
                }
                IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Settings", tint = cs.onSurfaceVariant) }
            }

            // Bottom: idle pill field, or keyboard/remote buttons flanking the docked mic
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                // Docked side buttons
                Row(
                    Modifier.fillMaxWidth().height(bottomBar).padding(horizontal = 28.dp)
                        .graphicsLayer { alpha = stage; translationY = (1f - stage) * 60f },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SideButton(Icons.Rounded.Keyboard, "Type", active = typing, enabled = stage > 0.5f) { typing = !typing }
                    Spacer(Modifier.weight(1f))
                    SideButton(Icons.Rounded.SettingsRemote, "Remote", active = showSheet, enabled = stage > 0.5f) { showSheet = true }
                }
                // Idle: full-width pill at the bottom (mock 1)
                if (stage < 0.99f) {
                    CommandField(
                        text = text, onText = { text = it }, onSend = send,
                        leading = { IconButton(onClick = { showSheet = true }) { Icon(Icons.Rounded.SettingsRemote, "Remote", tint = cs.onSurfaceVariant) } },
                        autoFocus = false,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                            .graphicsLayer { alpha = 1f - stage; translationY = stage * 120f },
                    )
                }
            }

            // Conversation: field that slides up above the bar when the keyboard button is on
            AnimatedVisibility(
                visible = typing && !idle,
                enter = slideInVertically(spring(0.8f, 400f)) { it } + fadeIn(),
                exit = slideOutVertically(spring(0.9f, 500f)) { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomBar - 6.dp),
            ) {
                CommandField(
                    text = text, onText = { text = it }, onSend = send, autoFocus = true,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            // The mic itself, sized and placed by the stage spring
            BigMic(
                busy = state.thinking,
                listening = state.listening,
                level = state.micLevel,
                docked = stage,
                onClick = onMic,
                modifier = Modifier
                    .size(micSize + 80.dp) // room for ripples
                    .offset { IntOffset(((maxWidth - micSize - 80.dp) / 2).roundToPx(), (micCenter - (micSize + 80.dp) / 2).roundToPx()) },
                micSize = micSize,
            )
        }
    }

    if (showSheet) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheet,
            containerColor = cs.surfaceContainerLow,
            shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                if (state.apps.isNotEmpty()) {
                    Text("Your apps", style = MaterialTheme.typography.titleMediumEmphasized, color = cs.onSurface, modifier = Modifier.padding(start = 24.dp, bottom = 10.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        itemsIndexed(state.apps.take(16), key = { _, a -> a.packageName }) { i, app ->
                            AppTile(app, i, selected = app == state.onScreen) { onOpen(app) }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                }
                Remote(onKey)
            }
        }
    }
}

@Composable
private fun BigMic(
    busy: Boolean,
    listening: Boolean,
    level: Float,
    docked: Float,
    micSize: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val t = rememberInfiniteTransition(label = "bigmic")
    // Idle: a slow lazy turn and a soft breath. Busy: a faster spin with a morph pulse.
    val idleTurn by t.animateFloat(0f, 360f, infiniteRepeatable(tween(26000, easing = LinearEasing)), label = "idleTurn")
    val busyTurn by t.animateFloat(0f, 360f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "busyTurn")
    val breath by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val ripple by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "ripple")

    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.9f else 1f, spring(dampingRatio = 0.45f, stiffness = 700f), label = "press")
    val smoothLevel by animateFloatAsState(if (listening) level.coerceIn(0f, 1f) else 0f, spring(stiffness = 900f), label = "lvl")
    val morph by animateFloatAsState(
        when { busy -> pulse; listening -> smoothLevel * 0.9f; else -> 0f },
        spring(dampingRatio = 0.6f, stiffness = 500f), label = "morph",
    )
    val color by animateColorAsState(if (listening) cs.tertiary else cs.primary, tween(300), label = "micColor")
    val onColor by animateColorAsState(if (listening) cs.onTertiary else cs.onPrimary, tween(300), label = "micOn")
    // Keep turning smoothly between modes instead of snapping back to 0°.
    val rotation = if (busy) busyTurn else idleTurn * (1f - docked)
    val scale = press * (if (listening) 1f + smoothLevel * 0.18f else if (!busy) 1f + breath * 0.035f * (1f - docked) else 1f)

    Box(modifier, contentAlignment = Alignment.Center) {
        // Listening ripples: three rings flowing outward.
        if (listening) Canvas(Modifier.fillMaxSize()) {
            val base = micSize.toPx() / 2
            val span = size.minDimension / 2 - base
            repeat(3) { i ->
                val p = (ripple + i / 3f) % 1f
                drawCircle(color, radius = base + span * p, alpha = (1f - p) * 0.35f, style = Stroke(width = (3.dp.toPx() * (1f - p)) + 1f))
            }
        }
        Box(
            Modifier
                .size(micSize)
                .graphicsLayer { rotationZ = rotation; scaleX = scale; scaleY = scale }
                .clip(MorphShape(PilotShapes.micMorph, morph))
                .background(color)
                .clickable(interactionSource = src, indication = null, enabled = !busy, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(listening, animationSpec = tween(200), label = "micIcon") { l ->
                Icon(
                    if (l) Icons.Rounded.Stop else Icons.Rounded.Mic,
                    if (l) "Stop" else "Speak",
                    tint = onColor,
                    modifier = Modifier.size(micSize * 0.36f).graphicsLayer { rotationZ = -rotation },
                )
            }
        }
    }
}

@Composable
private fun SideButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val corner by animateDpAsState(if (pressed) 16.dp else if (active) 20.dp else 30.dp, spring(dampingRatio = 0.5f, stiffness = 700f), label = "side")
    val bg by animateColorAsState(if (active) cs.secondary else cs.surfaceContainerHigh, tween(220), label = "sideBg")
    val fg by animateColorAsState(if (active) cs.onSecondary else cs.onSurface, tween(220), label = "sideFg")
    Box(
        Modifier.size(60.dp).clip(RoundedCornerShape(corner)).background(bg)
            .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = fg) }
}

@Composable
private fun CommandField(
    text: String,
    onText: (String) -> Unit,
    onSend: () -> Unit,
    autoFocus: Boolean,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    LaunchedEffect(autoFocus) { if (autoFocus) runCatching { focus.requestFocus(); kb?.show() } }
    Row(
        modifier.fillMaxWidth().height(60.dp).clip(CircleShape).background(cs.surfaceContainerHigh)
            .border(1.dp, cs.outlineVariant.copy(alpha = 0.5f), CircleShape).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) leading() else Spacer(Modifier.width(14.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (text.isEmpty()) Text("Type a command…", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
            BasicTextField(
                value = text, onValueChange = onText, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
                cursorBrush = SolidColor(cs.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        val sendScale by animateFloatAsState(if (text.isNotBlank()) 1f else 0.6f, spring(0.5f, 600f), label = "send")
        IconButton(onClick = onSend, enabled = text.isNotBlank(), modifier = Modifier.graphicsLayer { scaleX = sendScale; scaleY = sendScale }) {
            Icon(Icons.AutoMirrored.Rounded.Send, "Send", tint = if (text.isNotBlank()) cs.primary else cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun Conversation(state: UiState, onStop: () -> Unit, modifier: Modifier) {
    val list = rememberLazyListState()
    LaunchedEffect(state.lines.size, state.thinking, state.steps.size) {
        val count = list.layoutInfo.totalItemsCount
        if (count > 0) list.animateScrollToItem(count - 1)
    }
    LazyColumn(
        state = list,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(state.lines, key = { it.id }) { line ->
            Box(Modifier.animateItem(fadeInSpec = tween(260), placementSpec = spring(0.85f, 380f), fadeOutSpec = tween(160))) {
                EnterPop(line.fromUser) { ChatBubble(line) }
            }
        }
        if (state.thinking) item(key = "agent") {
            Box(Modifier.animateItem(fadeInSpec = tween(300), placementSpec = spring(0.85f, 380f), fadeOutSpec = tween(200))) {
                AgentCard(state, onStop)
            }
        }
    }
}

/** New bubbles rise and settle with a little spring instead of just appearing. */
@Composable
private fun EnterPop(fromRight: Boolean, content: @Composable () -> Unit) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(Unit) { anim.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)) }
    Box(Modifier.graphicsLayer {
        val p = anim.value
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * 40f
        val s = 0.92f + 0.08f * p
        scaleX = s; scaleY = s
        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (fromRight) 1f else 0f, 1f)
    }) { content() }
}

@Composable
private fun AgentCard(state: UiState, onStop: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceContainerHigh,
        shape = RoundedCornerShape(32.dp),
        modifier = Modifier.fillMaxWidth().animateContentSizeSpring(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LoadingIndicator(Modifier.size(40.dp))
                Spacer(Modifier.width(10.dp))
                AnimatedContent(
                    targetState = state.status ?: "Thinking…",
                    transitionSpec = {
                        (fadeIn(tween(220, 60)) + slideInVertically(spring(0.85f, 420f)) { it / 2 }) togetherWith
                            (fadeOut(tween(120)) + slideOutVertically(spring(0.9f, 500f)) { -it / 2 })
                    },
                    modifier = Modifier.weight(1f),
                    label = "status",
                ) { s ->
                    Text(s, style = MaterialTheme.typography.titleMediumEmphasized, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            // Live TV view
            AnimatedVisibility(
                state.shot != null,
                enter = expandVertically(spring(0.85f, 300f)) + fadeIn(tween(300)),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Crossfade(state.shot, animationSpec = tween(350), label = "shot") { bmp ->
                    if (bmp != null) Image(
                        bmp.asImageBitmap(), "TV screen",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(20.dp)),
                    )
                }
            }
            if (state.steps.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.steps.forEachIndexed { i, step ->
                    androidx.compose.runtime.key(i, step) { StepRow(step) }
                }
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextButton(onClick = onStop) { Text("Stop", color = cs.error, style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
}

@Composable
private fun StepRow(step: String) {
    val cs = MaterialTheme.colorScheme
    val anim = remember { Animatable(0f) }
    LaunchedEffect(Unit) { anim.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 500f)) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.graphicsLayer { alpha = anim.value.coerceIn(0f, 1f); translationX = (1f - anim.value) * -30f },
    ) {
        Icon(
            Icons.Rounded.CheckCircle, null, tint = cs.primary,
            modifier = Modifier.size(20.dp).graphicsLayer { val s = anim.value; scaleX = s; scaleY = s },
        )
        Spacer(Modifier.width(10.dp))
        Text(step, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun Modifier.animateContentSizeSpring() = this.then(
    Modifier.animateContentSize(spring(dampingRatio = 0.85f, stiffness = 380f))
)

@Composable
private fun StatusPill(c: Connection, onScreen: TvApp?) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.widthIn(max = 300.dp).clip(CircleShape).background(cs.surfaceContainerHigh).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(c)
        Spacer(Modifier.width(8.dp))
        AnimatedContent(statusText(c, onScreen), transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) }, label = "pill") { t ->
            Text(t, style = MaterialTheme.typography.labelLarge, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun StatusLine(c: Connection, onScreen: TvApp?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(c, 8.dp)
        Spacer(Modifier.width(6.dp))
        Text(statusText(c, onScreen), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun statusText(c: Connection, onScreen: TvApp?) = when (c) {
    is Connection.Connected -> onScreen?.let { "${it.label} on screen" } ?: "Connected"
    Connection.Connecting -> "Connecting…"
    Connection.Disconnected -> "Not connected"
    is Connection.Failed -> "Offline"
}

@Composable
private fun StatusDot(c: Connection, size: androidx.compose.ui.unit.Dp = 10.dp) {
    val color = when (c) {
        is Connection.Connected -> Color(0xFF4ADE80)
        Connection.Connecting -> Color(0xFFFBBF24)
        else -> Color(0xFFF87171)
    }
    val animColor by animateColorAsState(color, tween(400), label = "dot")
    val t = rememberInfiniteTransition(label = "dot")
    val glow by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Restart), label = "glow")
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size * 2)) {
        if (c is Connection.Connected || c is Connection.Connecting) Box(
            Modifier.size(size).graphicsLayer { val s = 1f + glow * 1.2f; scaleX = s; scaleY = s; alpha = (1f - glow) * 0.5f }
                .clip(CircleShape).background(animColor),
        )
        Box(Modifier.size(size).clip(CircleShape).background(animColor))
    }
}

@Composable
private fun Suggestions(onSend: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ideas = listOf("Play Dhruv Rathee's latest video", "Resume Netflix", "Volume down", "Pause", "Go home")
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        itemsIndexed(ideas) { i, s ->
            val anim = remember { Animatable(0f) }
            LaunchedEffect(Unit) { kotlinx.coroutines.delay(120L + i * 70L); anim.animateTo(1f, spring(0.65f, 400f)) }
            Text(
                s,
                style = MaterialTheme.typography.labelLarge,
                color = cs.onSurface,
                maxLines = 1,
                modifier = Modifier
                    .graphicsLayer { alpha = anim.value.coerceIn(0f, 1f); translationY = (1f - anim.value) * 24f }
                    .clip(CircleShape)
                    .background(cs.surfaceContainer)
                    .border(1.dp, cs.outlineVariant, CircleShape)
                    .clickable { onSend(s) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun ConnectHint(c: Connection, onConnect: () -> Unit, onSettings: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            when (c) {
                Connection.Connecting -> "Tap Allow on your TV, and tick Always allow."
                is Connection.Failed -> c.reason
                else -> "Add your TV's IP address in Settings."
            },
            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp).padding(horizontal = 24.dp),
        )
        if (c !is Connection.Connecting) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onConnect) { Text("Connect", color = cs.primary) }
            TextButton(onClick = onSettings) { Text("Settings", color = cs.primary) }
        }
    }
}
