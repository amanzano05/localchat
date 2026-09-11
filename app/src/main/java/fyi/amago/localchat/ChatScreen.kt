package fyi.amago.localchat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var showModels by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importModel(context, uri)
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Only auto-scroll when the user is parked at the tail, so scrolling up while an answer
    // streams no longer fights the user. Non-animated while streaming (one call per chunk).
    val atTail by remember { derivedStateOf { !listState.canScrollForward } }

    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text) {
        if (state.messages.isNotEmpty() && atTail) listState.scrollToItem(state.messages.lastIndex)
    }

    val submit: () -> Unit = {
        val t = input
        if (t.isNotBlank() && !state.generating && state.model is ModelState.Ready) {
            input = ""
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
                    TextButton(onClick = { showModels = !showModels }) { Text("Model") }
                    TextButton(onClick = { vm.newChat() }) { Text("New") }
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
                    if (!atTail) {
                        TextButton(
                            onClick = { scope.launch { listState.animateScrollToItem(state.messages.lastIndex) } },
                            modifier = Modifier.align(Alignment.BottomCenter),
                        ) {
                            Text("↓ Jump to latest", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

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

private fun mb(bytes: Long): String = if (bytes <= 0) "0 MB" else String.format("%.1f MB", bytes / 1048576.0)

private fun pct(read: Long, total: Long): String = if (total <= 0) "" else "${(read * 100 / total)}%"
