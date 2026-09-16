package fyi.amago.localchat

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import fyi.amago.localchat.voice.VoiceController
import fyi.amago.localchat.voice.VoiceModelsState
import fyi.amago.localchat.voice.VoiceState
import java.io.File

data class ChatMessage(
    val id: Long,
    val fromUser: Boolean,
    val text: String = "",
    val streaming: Boolean = false,
    val timeMs: Long = 0L,
)

data class ConversationSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int,
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
    val conversations: List<ConversationSummary> = emptyList(),
    val currentId: String? = null,
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ModelRepository(app)
    private val store = ChatStore(app)
    private val engine = LlmEngine()
    private val voice = VoiceController(app)

    /** Microphone/speaker state, kept out of [ChatUiState] so recording never recomposes the chat. */
    val voiceState: StateFlow<VoiceState> get() = voice.state
    val voiceModels: StateFlow<VoiceModelsState> get() = voice.models
    val voiceLang: StateFlow<String> get() = voice.lang
    val speakReplies: StateFlow<Boolean> get() = voice.speakReplies
    val handsFree: StateFlow<Boolean> get() = voice.handsFree

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var handsFreeJob: Job? = null
    private val SETTLE_MS = 600L
    private val conversations = mutableListOf<ChatStore.StoredConversation>()
    private var currentId: String? = null
    private var nextId = 0L

    init {
        val (stored, current) = store.load()
        conversations.addAll(stored)
        if (conversations.isEmpty()) {
            startConversation()
        } else {
            open(conversations.firstOrNull { it.id == current } ?: conversations.maxByOrNull { it.updatedAt }!!)
        }
        refreshModels()
        val default = repo.modelFile(ModelRepository.DEFAULT_MODEL_NAME)
        if (repo.isInstalled(default.name)) loadModel(default)
    }

    // ---------------------------------------------------------------- conversations

    private fun newId(): String = "c" + System.currentTimeMillis().toString(36) + "-" + (0..999).random()

    private fun open(conv: ChatStore.StoredConversation) {
        currentId = conv.id
        nextId = (conv.messages.maxOfOrNull { it.id } ?: -1L) + 1
        _state.update { it.copy(messages = conv.messages, currentId = conv.id, conversations = summaries()) }
    }

    private fun startConversation() {
        val now = System.currentTimeMillis()
        val conv = ChatStore.StoredConversation(newId(), "New chat", now, now, emptyList())
        conversations.add(0, conv)
        open(conv)
    }

    private fun summaries(): List<ConversationSummary> =
        conversations.sortedByDescending { it.updatedAt }
            .map { ConversationSummary(it.id, it.title, it.updatedAt, it.messageCount()) }

    private fun ChatStore.StoredConversation.messageCount(): Int = messages.count { !it.streaming }

    /** Folds the live message list back into the stored conversation. */
    private fun syncCurrent() {
        val id = currentId ?: return
        val idx = conversations.indexOfFirst { it.id == id }
        val msgs = _state.value.messages
        if (idx < 0) return
        val prev = conversations[idx]
        val title = if (prev.title == "New chat" || prev.title.isBlank()) titleFor(msgs) else prev.title
        conversations[idx] = prev.copy(
            title = title,
            updatedAt = System.currentTimeMillis(),
            messages = msgs,
        )
    }

    private fun titleFor(messages: List<ChatMessage>): String {
        val first = messages.firstOrNull { it.fromUser }?.text ?: return "New chat"
        val clean = first.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        return if (clean.length <= 42) clean else clean.take(42).trimEnd() + "…"
    }

    private fun historyOfCurrent(): List<Pair<Boolean, String>> =
        _state.value.messages.filter { it.text.isNotBlank() }.map { it.fromUser to it.text }

    private fun persist() {
        syncCurrent()
        val snapshot = conversations.toList()
        val current = currentId
        viewModelScope.launch(Dispatchers.IO) { store.save(snapshot, current) }
    }

    fun newChat() {
        engine.cancel()
        syncCurrent()
        startConversation()
        engine.resetConversation()
        _state.update { it.copy(messages = emptyList(), notice = null) }
        persist()
    }

    fun openConversation(id: String) {
        if (id == currentId) return
        val conv = conversations.firstOrNull { it.id == id } ?: return
        engine.cancel()
        syncCurrent()
        open(conv)
        // Give the model the thread's context back, otherwise it answers as a stranger.
        if (_state.value.model is ModelState.Ready && conv.messages.any { !it.fromUser }) {
            viewModelScope.launch(Dispatchers.IO) {
                engine.switchConversation(conv.messages.filter { it.text.isNotBlank() }.map { it.fromUser to it.text })
            }
        }
        persist()
    }

    fun deleteConversation(id: String) {
        val idx = conversations.indexOfFirst { it.id == id }
        if (idx < 0) return
        conversations.removeAt(idx)
        if (id == currentId) {
            engine.cancel()
            val next = conversations.maxByOrNull { it.updatedAt }
            if (next == null) startConversation() else open(next)
            engine.resetConversation()
        }
        _state.update { it.copy(conversations = summaries()) }
        persist()
    }

    fun renameCurrent(title: String) {
        val id = currentId ?: return
        val idx = conversations.indexOfFirst { it.id == id }
        if (idx < 0) return
        conversations[idx] = conversations[idx].copy(title = title.trim().ifBlank { "New chat" })
        _state.update { it.copy(conversations = summaries()) }
        persist()
    }

    // ---------------------------------------------------------------- models

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
                // Restore the open thread's context into the freshly loaded engine.
                val history = historyOfCurrent()
                if (history.any { !it.first }) withContext(Dispatchers.IO) { engine.switchConversation(history) }
                _state.update { it.copy(model = ModelState.Ready(backend, file.name)) }
            } catch (t: Throwable) {
                _state.update { it.copy(model = ModelState.Failed(t.message ?: t.javaClass.simpleName)) }
            }
        }
    }

    // ---------------------------------------------------------------- chat

    fun send(text: String) {
        val prompt = text.trim()
        val st = _state.value
        if (prompt.isEmpty() || st.generating || st.model !is ModelState.Ready) return
        voice.stopSpeaking() // talking to it interrupts it, exactly like a person

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
            var finalText = ""
            try {
                engine.send(prompt).collect { partial ->
                    finalText = partial
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
            // Speak the answer once it is complete — never mid-stream, or it stutters.
            // Wrapped on purpose: viewModelScope has no exception handler, so anything thrown here
            // would take the whole app down. The voice is optional; dying for it is not.
            if (voice.speakReplies.value && finalText.isNotBlank()) {
                try {
                    voice.speak(finalText)
                } catch (t: Throwable) {
                    voice.log("speak() threw: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
        }
    }

    fun stopGenerating() {
        engine.cancel()
    }

    // ---------------------------------------------------------------- voice

    /** Starts a take. False means the mic or the speech model is not ready — [voiceState] says why. */
    fun startVoice(): Boolean {
        voice.stopSpeaking()
        return voice.startListening()
    }

    /** Keeps the level meter alive while recording. */
    fun voiceTick() = voice.tick()

    fun cancelVoice() = voice.cancelListening()

    /**
     * Ends the take, transcribes on the phone, and sends what it heard. A silent take says so
     * instead of sending an empty message.
     */
    fun finishVoice() {
        viewModelScope.launch {
            val heard = voice.finishListening()
            when {
                heard.isBlank() -> _state.update { it.copy(notice = "Didn\u2019t catch that \u2014 hold on, tap again and speak") }
                _state.value.model !is ModelState.Ready -> _state.update { it.copy(notice = "Model not loaded yet") }
                else -> {
                    _state.update { it.copy(notice = null) }
                    send(heard)
                }
            }
        }
    }

    fun speak(text: String) {
        viewModelScope.launch { voice.speak(text) }
    }

    fun stopSpeaking() = voice.stopSpeaking()

    fun setVoiceLang(lang: String) = voice.setLang(lang)

    fun setSpeakReplies(on: Boolean) = voice.setSpeakReplies(on)

    /** Picks which voice speaks the current language. */
    fun setVoice(id: String) {
        voice.setVoice(id)
        // A voice that is not on the phone yet is worth fetching right away: that is what picking it means.
        if (!(voice.models.value.choices.firstOrNull { it.id == id }?.installed ?: false)) downloadVoiceModels()
    }

    /**
     * Hands-free conversation: while it is on, the microphone opens itself, silence closes the turn,
     * and whatever was said becomes a message. The loop waits whenever the app is busy — generating
     * or speaking — so the phone never listens to its own voice.
     */
    fun setHandsFree(on: Boolean) {
        voice.setHandsFree(on)
        handsFreeJob?.cancel()
        handsFreeJob = null
        if (!on) return
        handsFreeJob = viewModelScope.launch {
            var quietSince = 0L
            while (isActive && voice.handsFree.value) {
                val busy = _state.value.generating ||
                    voice.isSpeaking() ||
                    voice.state.value is VoiceState.Speaking ||
                    voice.state.value is VoiceState.Listening ||
                    voice.state.value is VoiceState.Transcribing
                if (busy) {
                    quietSince = 0L
                    delay(250)
                    continue
                }
                // A short quiet moment before opening the mic: without it the first syllable of
                // the answer would be heard as the next question and the phone would talk to itself.
                if (quietSince == 0L) {
                    quietSince = System.currentTimeMillis()
                    delay(200)
                    continue
                }
                if (System.currentTimeMillis() - quietSince < SETTLE_MS) {
                    delay(150)
                    continue
                }
                val heard = voice.listenOnce()
                quietSince = 0L
                if (!heard.isNullOrBlank()) {
                    _state.update { it.copy(notice = null) }
                    send(heard)
                }
            }
        }
    }

    fun downloadVoiceModels() {
        viewModelScope.launch { voice.downloadMissing() }
    }

    fun sttDescription(): String = voice.sttDescription()

    /** The sheet's self-check: files, engine, and a spoken test sentence, reported in words. */
    fun runVoiceCheck() {
        viewModelScope.launch { voice.runVoiceCheck() }
    }

    /** Re-reads what is on disk and the tail of the voice log. */
    fun refreshVoice() = voice.refresh()

    /** Hides the voice problem card. */
    fun clearVoiceError() = voice.clearVoiceError()

    fun clearNotice() = _state.update { it.copy(notice = null) }

    fun notice(message: String) = _state.update { it.copy(notice = message) }

    override fun onCleared() {
        handsFreeJob?.cancel()
        engine.close()
        voice.close()
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
