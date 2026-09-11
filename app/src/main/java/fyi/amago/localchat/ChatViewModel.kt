package fyi.amago.localchat

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ChatMessage(
    val id: Long,
    val fromUser: Boolean,
    val text: String = "",
    val streaming: Boolean = false,
    val timeMs: Long = 0L,
)

sealed interface ModelState {
    data object Missing : ModelState
    data class Downloading(val bytes: Long, val total: Long) : ModelState
    data object Loading : ModelState
    data class Ready(val backend: String, val fileName: String) : ModelState
    data class Failed(val message: String) : ModelState
}

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val model: ModelState = ModelState.Missing,
    val generating: Boolean = false,
    val models: List<File> = emptyList(),
    val notice: String? = null,
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ModelRepository(app)
    private val store = ChatStore(app)
    private val engine = LlmEngine()

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var nextId = 0L

    init {
        // Restore the previous conversation before anything else, so the screen never flashes empty.
        val restored = store.load()
        nextId = (restored.maxOfOrNull { it.id } ?: -1L) + 1
        _state.update { it.copy(messages = restored) }

        refreshModels()
        val default = repo.modelFile(ModelRepository.DEFAULT_MODEL_NAME)
        if (repo.isInstalled(default.name)) loadModel(default)
    }

    private fun persist() {
        val snapshot = _state.value.messages
        viewModelScope.launch(Dispatchers.IO) { store.save(snapshot) }
    }

    private fun refreshModels() {
        _state.update { it.copy(models = repo.installedModels()) }
    }

    fun downloadDefaultModel() {
        if (_state.value.model is ModelState.Downloading) return
        viewModelScope.launch {
            _state.update { it.copy(model = ModelState.Downloading(0, -1), notice = null) }
            try {
                val file = withContext(Dispatchers.IO) {
                    repo.download(ModelRepository.DEFAULT_MODEL_URL, ModelRepository.DEFAULT_MODEL_NAME) { read, total ->
                        _state.update { st ->
                            if (st.model is ModelState.Downloading) st.copy(model = ModelState.Downloading(read, total)) else st
                        }
                    }
                }
                refreshModels()
                loadModel(file)
            } catch (t: Throwable) {
                _state.update { it.copy(model = ModelState.Failed(t.message ?: "Download failed")) }
            }
        }
    }

    fun importModel(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                val name = queryDisplayName(context, uri) ?: ModelRepository.DEFAULT_MODEL_NAME
                val safeName = if (name.endsWith(ModelRepository.MODEL_EXTENSION)) name else "$name${ModelRepository.MODEL_EXTENSION}"
                _state.update { it.copy(model = ModelState.Loading, notice = "Importing $safeName\u2026") }
                val file = withContext(Dispatchers.IO) { repo.importFromUri(uri, safeName) }
                refreshModels()
                _state.update { it.copy(notice = null) }
                loadModel(file)
            } catch (t: Throwable) {
                _state.update { it.copy(model = ModelState.Failed(t.message ?: "Import failed"), notice = null) }
            }
        }
    }

    fun loadModel(file: File) {
        viewModelScope.launch {
            _state.update { it.copy(model = ModelState.Loading, notice = null) }
            try {
                val backend = withContext(Dispatchers.IO) {
                    engine.load(file.absolutePath, getApplication<Application>().cacheDir.absolutePath)
                }
                _state.update { it.copy(model = ModelState.Ready(backend, file.name)) }
            } catch (t: Throwable) {
                _state.update { it.copy(model = ModelState.Failed(t.message ?: t.javaClass.simpleName)) }
            }
        }
    }

    fun send(text: String) {
        val prompt = text.trim()
        val st = _state.value
        if (prompt.isEmpty() || st.generating || st.model !is ModelState.Ready) return

        val now = System.currentTimeMillis()
        val botId = nextId++
        _state.update {
            it.copy(
                messages = it.messages + ChatMessage(nextId++, true, prompt, timeMs = now) +
                    ChatMessage(botId, false, "", streaming = true, timeMs = now),
                generating = true,
            )
        }
        persist()

        viewModelScope.launch {
            try {
                engine.send(prompt).collect { partial ->
                    _state.update { s ->
                        s.copy(messages = s.messages.map { if (it.id == botId) it.copy(text = partial) else it })
                    }
                }
            } catch (t: Throwable) {
                val note = "\u26A0\uFE0F ${t.message ?: t.javaClass.simpleName}"
                _state.update { s ->
                    s.copy(messages = s.messages.map { m ->
                        if (m.id != botId) m
                        else m.copy(text = if (m.text.isEmpty()) note else m.text + "\n\n" + note, streaming = false)
                    })
                }
            } finally {
                _state.update { s ->
                    s.copy(
                        messages = s.messages.map { if (it.id == botId) it.copy(streaming = false, timeMs = System.currentTimeMillis()) else it },
                        generating = false,
                    )
                }
                persist()
            }
        }
    }

    fun stopGenerating() {
        engine.cancel()
    }

    fun newChat() {
        engine.cancel()
        _state.update { it.copy(messages = emptyList(), notice = null) }
        viewModelScope.launch(Dispatchers.IO) { store.clear() }
    }

    fun clearNotice() = _state.update { it.copy(notice = null) }

    override fun onCleared() {
        engine.close()
        super.onCleared()
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull()
}
