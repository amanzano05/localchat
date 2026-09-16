package fyi.amago.localchat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fyi.amago.localchat.voice.VoiceModelsState
import fyi.amago.localchat.voice.VoiceState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val voice by vm.voiceState.collectAsStateWithLifecycle()
    val voiceModels by vm.voiceModels.collectAsStateWithLifecycle()
    val voiceLang by vm.voiceLang.collectAsStateWithLifecycle()
    val speakReplies by vm.speakReplies.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var showModels by remember { mutableStateOf(false) }
    var showChats by remember { mutableStateOf(false) }
    var showVoice by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importModel(context, uri)
    }

    // The mic is asked for at the moment it is wanted, with the reason attached — not on launch.
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startVoice() else vm.notice("Microphone permission is off, so voice is unavailable")
    }

    // One ticker for the whole recording: moves the meter and the timer, and stops by itself.
    LaunchedEffect(voice) {
        while (voice is VoiceState.Listening) {
            vm.voiceTick()
            delay(120)
        }
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Autoscroll: follow the tail by default (and always right after you send). It only stops
    // when *you* drag the list yourself, so reading back never fights the streaming answer.
    var follow by remember { mutableStateOf(true) }
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragging) { if (dragging) follow = false }

    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text, state.currentId) {
        if (state.messages.isNotEmpty() && follow) listState.scrollToItem(state.messages.lastIndex)
    }
    LaunchedEffect(state.currentId) {
        if (state.messages.isNotEmpty()) {
            follow = true
            listState.scrollToItem(state.messages.lastIndex)
        }
    }

    val submit: () -> Unit = {
        val t = input
        if (t.isNotBlank() && !state.generating && state.model is ModelState.Ready) {
            input = ""
            follow = true
            vm.send(t)
        }
    }

    Scaffold(
        // The Scaffold must not consume window insets itself: each region below pads exactly
        // the sides it draws under. Otherwise the nav-bar inset stacks on top of the IME inset
        // (the IME inset already includes it) and the composer floats one nav bar too high.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Local Chat", style = MaterialTheme.typography.titleMedium)
                        Text(modelSubtitle(state.model), style = MaterialTheme.typography.labelSmall)
                    }
                },
                actions = {
                    TextButton(onClick = { showVoice = true }) { Text("Voice") }
                    TextButton(onClick = { showChats = true }) { Text("Chats") }
                    TextButton(onClick = { showModels = !showModels }) { Text("Model") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        ) {

            if (showModels || state.model !is ModelState.Ready) {
                ModelPanel(vm = vm, state = state, onPickFile = { picker.launch(arrayOf("*/*")) })
            }

            state.notice?.let {
                Text(it, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            }

            if (state.messages.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (state.model is ModelState.Ready)
                            "Ask anything.\nIt never leaves your phone."
                        else
                            "Load a model to start chatting.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(state.messages, key = { it.id }) { msg -> Bubble(msg) }
                    }
                    if (!follow) {
                        TextButton(
                            onClick = {
                                follow = true
                                scope.launch { listState.animateScrollToItem(state.messages.lastIndex) }
                            },
                            modifier = Modifier.align(Alignment.BottomCenter),
                        ) {
                            Text("↓ Jump to latest", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            VoiceStrip(
                voice = voice,
                onCancel = { vm.cancelVoice() },
                onStopSpeaking = { vm.stopSpeaking() },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    .padding(8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (state.model is ModelState.Ready) "Message the model…" else "Load a model first") },
                    // Typing stays possible while the model answers; only sending is gated.
                    enabled = state.model is ModelState.Ready,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    maxLines = 5,
                    shape = RoundedCornerShape(24.dp),
                )
                MicButton(
                    voice = voice,
                    voiceReady = voiceModels.ready,
                    onTap = {
                        when {
                            voice is VoiceState.Listening -> vm.finishVoice()
                            voice is VoiceState.Transcribing -> Unit
                            !voiceModels.ready -> showVoice = true
                            hasMic(context) -> if (!vm.startVoice()) showVoice = true
                            else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                )
                if (state.generating) {
                    OutlinedButton(onClick = { vm.stopGenerating() }) { Text("Stop") }
                } else {
                    Button(onClick = submit, enabled = state.model is ModelState.Ready && input.isNotBlank()) {
                        Text("Send")
                    }
                }
            }
        }
    }

    if (showVoice) {
        ModalBottomSheet(onDismissRequest = { showVoice = false }) {
            VoiceSheet(
                models = voiceModels,
                lang = voiceLang,
                speak = speakReplies,
                sttName = vm.sttDescription(),
                onLang = { vm.setVoiceLang(it) },
                onSpeak = { vm.setSpeakReplies(it) },
                onDownload = { vm.downloadVoiceModels() },
            )
        }
    }

    if (showChats) {
        ModalBottomSheet(onDismissRequest = { showChats = false }) {
            ChatsSheet(
                state = state,
                onNew = {
                    vm.newChat()
                    follow = true
                    showChats = false
                },
                onOpen = { id ->
                    vm.openConversation(id)
                    follow = true
                    showChats = false
                },
                onDelete = { id -> vm.deleteConversation(id) },
            )
        }
    }
}

@Composable
private fun ChatsSheet(
    state: ChatUiState,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Chats", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Button(onClick = onNew) { Text("New chat") }
        }
        Spacer(Modifier.height(8.dp))
        if (state.conversations.isEmpty()) {
            Text("No conversations yet.", style = MaterialTheme.typography.bodySmall)
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(state.conversations, key = { it.id }) { c ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(c.id) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = c.title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (c.id == state.currentId) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${c.messageCount} messages · ${whenLabel(c.updatedAt)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onDelete(c.id) }) { Text("Delete") }
                    }
                    HorizontalDivider()
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ModelPanel(vm: ChatViewModel, state: ChatUiState, onPickFile: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (val m = state.model) {
                is ModelState.Downloading -> {
                    Text("Downloading ${ModelRepository.DEFAULT_MODEL_SIZE_HINT} model…")
                    if (m.total > 0) {
                        LinearProgressIndicator(
                            progress = { m.bytes.toFloat() / m.total.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("${pct(m.bytes, m.total)} — ${mb(m.bytes)} / ${mb(m.total)}", style = MaterialTheme.typography.labelSmall)
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("${mb(m.bytes)} downloaded", style = MaterialTheme.typography.labelSmall)
                    }
                }

                ModelState.Loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Loading model into memory…")
                }

                is ModelState.Ready -> Text("Ready · ${m.backend} backend · ${m.fileName}", style = MaterialTheme.typography.bodySmall)

                is ModelState.Failed -> Text("Error: ${m.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)

                ModelState.Missing -> Text("No model on the device yet. Download Gemma 4 E2B (1.9 GB, Apache-2.0) or pick a .litertlm file you already have.")
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.downloadDefaultModel() },
                    enabled = state.model !is ModelState.Downloading && state.model !is ModelState.Loading,
                ) { Text("Download Gemma 4 E2B") }
                OutlinedButton(onClick = onPickFile) { Text("Load file…") }
            }

            if (state.models.isNotEmpty()) {
                Text("Installed:", style = MaterialTheme.typography.labelMedium)
                state.models.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(f.name + " (" + mb(f.length()) + ")", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.loadModel(f) }, enabled = state.model !is ModelState.Loading) { Text("Load") }
                    }
                }
            }

            Text(
                "Tip: adb push a model to /sdcard/Android/data/fyi.amago.localchat/files/models/",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun Bubble(msg: ChatMessage) {
    // Alignment.End/Start are Alignment.Horizontal; CenterEnd/CenterStart are plain Alignment here.
    val align: Alignment.Horizontal = if (msg.fromUser) Alignment.End else Alignment.Start
    val bg = if (msg.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
    val fg = if (msg.fromUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = align) {
        Surface(color = bg, shape = RoundedCornerShape(18.dp), modifier = Modifier.widthIn(max = 520.dp)) {
            SelectionContainer {
                val thinking = msg.text.isEmpty() && msg.streaming
                Text(
                    text = if (thinking) "Thinking…" else msg.text,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (thinking) MaterialTheme.colorScheme.onSurfaceVariant else fg,
                )
            }
        }
        val stamp = if (msg.streaming) "" else timeLabel(msg.timeMs)
        if (stamp.isNotEmpty()) {
            Text(
                stamp,
                modifier = Modifier.padding(top = 2.dp, start = 6.dp, end = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun modelSubtitle(m: ModelState): String = when (m) {
    ModelState.Missing -> "no model loaded"
    is ModelState.Downloading -> "downloading model…"
    ModelState.Loading -> "loading model…"
    is ModelState.Ready -> "on-device · ${m.backend}"
    is ModelState.Failed -> "error"
}

private fun timeLabel(ms: Long): String =
    if (ms <= 0L) "" else java.time.Instant.ofEpochMilli(ms)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))

private fun whenLabel(ms: Long): String {
    if (ms <= 0L) return ""
    val zone = java.time.ZoneId.systemDefault()
    val dt = java.time.Instant.ofEpochMilli(ms).atZone(zone)
    val today = java.time.LocalDate.now(zone)
    return if (dt.toLocalDate() == today) dt.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
    else dt.format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
}

private fun mb(bytes: Long): String = if (bytes <= 0) "0 MB" else String.format("%.1f MB", bytes / 1048576.0)

private fun pct(read: Long, total: Long): String = if (total <= 0) "" else "${(read * 100 / total)}%"


// ---------------------------------------------------------------------------- voice

/** The status half of voice: what the mic is doing, and the way out of it. */
@Composable
private fun VoiceStrip(voice: VoiceState, onCancel: () -> Unit, onStopSpeaking: () -> Unit) {
    when (voice) {
        VoiceState.Idle -> Unit

        is VoiceState.Listening -> Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LevelMeter(voice.level)
            Text(
                "Listening \u00b7 ${"%.1f".format(voice.seconds)}s",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancel) { Text("Cancel") }
        }

        VoiceState.Transcribing -> Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text("Transcribing on the phone\u2026", style = MaterialTheme.typography.labelMedium)
        }

        is VoiceState.Speaking -> Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Speaking", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onStopSpeaking) { Text("Stop") }
        }

        is VoiceState.Failed -> Text(
            voice.message,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** Eight bars that answer one question: is it hearing me? */
@Composable
private fun LevelMeter(level: Float) {
    val bars = 8
    val on = 0.15f + level.coerceIn(0f, 1f) * 1.6f
    Canvas(modifier = Modifier.size(width = 34.dp, height = 16.dp)) {
        val gap = 2.dp.toPx()
        val w = (size.width - gap * (bars - 1)) / bars
        for (i in 0 until bars) {
            val t = (i + 1f) / bars
            val lit = on >= t
            val h = size.height * (0.35f + 0.65f * t)
            drawRoundRect(
                color = if (lit) Color(0xFF9CC7FF) else Color(0xFF3A3F46),
                topLeft = Offset(i * (w + gap), size.height - h),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2f, w / 2f),
            )
        }
    }
}

/**
 * The microphone. Drawn by hand on purpose: Compose's core icon set has no mic, and pulling in the
 * extended icon pack for one glyph is not worth 1.5 MB of APK.
 */
@Composable
private fun MicButton(voice: VoiceState, voiceReady: Boolean, onTap: () -> Unit) {
    val listening = voice is VoiceState.Listening
    val speaking = voice is VoiceState.Speaking
    val busy = voice is VoiceState.Transcribing
    val bg = when {
        listening -> MaterialTheme.colorScheme.errorContainer
        speaking -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val tint = when {
        listening -> MaterialTheme.colorScheme.onErrorContainer
        speaking -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    IconButton(
        onClick = onTap,
        enabled = !busy,
        modifier = Modifier.size(48.dp).background(bg, CircleShape),
    ) {
        MicGlyph(tint = if (voiceReady || listening) tint else tint.copy(alpha = 0.45f), filled = listening)
    }
}

@Composable
private fun MicGlyph(tint: Color, filled: Boolean, size: androidx.compose.ui.unit.Dp = 22.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = w * 0.09f
        val bodyW = w * 0.34f
        val bodyH = h * 0.46f
        val bodyTop = h * 0.06f
        val style = if (filled) androidx.compose.ui.graphics.drawscope.Fill
        else Stroke(width = stroke, cap = StrokeCap.Round)
        drawRoundRect(
            color = tint,
            topLeft = Offset((w - bodyW) / 2f, bodyTop),
            size = Size(bodyW, bodyH),
            cornerRadius = CornerRadius(bodyW / 2f, bodyW / 2f),
            style = style,
        )
        drawArc(
            color = tint,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.18f, bodyTop + bodyH * 0.30f),
            size = Size(w * 0.64f, h * 0.46f),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawLine(
            color = tint,
            start = Offset(w / 2f, h * 0.80f),
            end = Offset(w / 2f, h * 0.94f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/** Voice settings, reachable without a model loaded: the models are a voice, not a chat, concern. */
@Composable
private fun VoiceSheet(
    models: VoiceModelsState,
    lang: String,
    speak: Boolean,
    sttName: String,
    onLang: (String) -> Unit,
    onSpeak: (Boolean) -> Unit,
    onDownload: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Text("Voice", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "Whisper listens, Piper speaks, both on this phone. Airplane mode is fine.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Language of the conversation", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { onLang("en") }, enabled = lang != "en") { Text(if (lang == "en") "English \u2713" else "English") }
            TextButton(onClick = { onLang("es") }, enabled = lang != "es") { Text(if (lang == "es") "Espa\u00f1ol \u2713" else "Espa\u00f1ol") }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Speak replies", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Reads each answer out loud when it finishes.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = speak, onCheckedChange = onSpeak)
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        StatusLine("Speech to text", sttName)
        StatusLine("Voice", voiceLabel(models, lang))
        StatusLine("Storage", "about 160 MB once everything is in")

        Spacer(Modifier.height(12.dp))
        if (models.downloading) {
            LinearProgressIndicator(progress = { models.progress }, modifier = Modifier.fillMaxWidth())
            Text(models.label.ifBlank { "Downloading\u2026" }, style = MaterialTheme.typography.labelSmall)
        } else {
            val missing = !models.sttInstalled || !(if (lang == "es") models.ttsEsInstalled else models.ttsEnInstalled)
            Button(onClick = onDownload, enabled = missing) {
                Text(if (missing) "Download voice models" else "Voice is ready")
            }
            if (missing) {
                Text(
                    "Whisper tiny (99 MB, both languages) plus the " + (if (lang == "es") "Spanish" else "English") + " voice.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun voiceLabel(models: VoiceModelsState, lang: String): String {
    val installed = if (lang == "es") models.ttsEsInstalled else models.ttsEnInstalled
    val name = if (lang == "es") "es_ES sharvard" else "en_US amy"
    return if (installed) "$name \u00b7 installed" else "$name \u00b7 not installed"
}

private fun hasMic(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
