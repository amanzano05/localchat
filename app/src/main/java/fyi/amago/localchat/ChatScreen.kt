package fyi.amago.localchat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    Scaffold(
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
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            if (showModels || state.model !is ModelState.Ready) {
                ModelPanel(vm = vm, state = state, onPickFile = { picker.launch(arrayOf("*/*")) })
            }

            state.notice?.let {
                Text(it, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.messages, key = { it.id }) { msg -> Bubble(msg) }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (state.model is ModelState.Ready) "Message the model…" else "Load a model first") },
                    enabled = state.model is ModelState.Ready && !state.generating,
                    keyboardActions = KeyboardActions(),
                    maxLines = 5,
                )
                if (state.generating) {
                    OutlinedButton(onClick = { vm.stopGenerating() }) { Text("Stop") }
                } else {
                    Button(
                        onClick = {
                            val t = input
                            input = ""
                            vm.send(t)
                        },
                        enabled = state.model is ModelState.Ready && input.isNotBlank(),
                    ) { Text("Send") }
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
                        TextButton(
                            onClick = { vm.loadModel(f) },
                            enabled = state.model !is ModelState.Loading,
                        ) { Text("Load") }
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
    val bg = if (msg.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = align) {
        Surface(color = bg, shape = RoundedCornerShape(14.dp), modifier = Modifier.widthIn(max = 320.dp)) {
            Text(
                text = if (msg.text.isEmpty() && msg.streaming) "…" else msg.text,
                modifier = Modifier.padding(10.dp),
                style = MaterialTheme.typography.bodyMedium,
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

private fun mb(bytes: Long): String = if (bytes <= 0) "0 MB" else String.format("%.1f MB", bytes / 1048576.0)

private fun pct(read: Long, total: Long): String = if (total <= 0) "" else "${(read * 100 / total)}%"
