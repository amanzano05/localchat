package fyi.amago.localchat.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File

/** What the app is doing with the microphone and the speaker right now. */
sealed interface VoiceState {
    data object Idle : VoiceState
    data class Listening(val level: Float, val seconds: Float) : VoiceState
    data object Transcribing : VoiceState
    data class Speaking(val since: Long) : VoiceState
    data class Failed(val message: String) : VoiceState
}

/** Voice models: what is on the phone and how far a download has got. */
data class VoiceModelsState(
    val sttInstalled: Boolean = false,
    val ttsEnInstalled: Boolean = false,
    val ttsEsInstalled: Boolean = false,
    val downloading: Boolean = false,
    val progress: Float = 0f,
    val label: String = "",
    /** Result of the last "Test voice" run, shown verbatim in the sheet. */
    val lastCheck: String? = null,
    /** Last failure from the speech engines, if any. */
    val voiceError: String? = null,
    /** Tail of the on-device voice log: survives a crash, which is the point of it. */
    val voiceLog: String = "",
) {
    /** Voice is usable once speech recognition and at least one voice are present. */
    val ready: Boolean get() = sttInstalled && (ttsEnInstalled || ttsEsInstalled)
}

/**
 * The conductor: microphone → Whisper → (the chat model, elsewhere) → Piper → speaker.
 *
 * Speech *recognition* runs here, in the app process: it is stable and its output is needed on this
 * side. Speech *synthesis* runs in [TtsService], in its own process, because that native stack was
 * killing the app — see [TtsClient]. The screen asks this class to listen, gets text back, and asks
 * it to speak; the ViewModel decides what that text means.
 */
class VoiceController(private val context: Context) {

    private val repo = VoiceRepository(context)
    private val recorder = VoiceRecorder()
    private val stt = SttEngine(repo)
    private val ttsClient = TtsClient(context)
    private val prefs = context.getSharedPreferences("voice", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val _models = MutableStateFlow(VoiceModelsState())
    val models: StateFlow<VoiceModelsState> = _models.asStateFlow()

    /** "en" or "es" — drives both the Whisper language hint and which Piper voice speaks. */
    private val _lang = MutableStateFlow(prefs.getString(KEY_LANG, "en") ?: "en")
    val lang: StateFlow<String> = _lang.asStateFlow()

    /** Read every answer out loud. Off by default: the first turn should not surprise anyone. */
    private val _speakReplies = MutableStateFlow(prefs.getBoolean(KEY_SPEAK, false))
    val speakReplies: StateFlow<Boolean> = _speakReplies.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _models.update {
            it.copy(
                sttInstalled = repo.sttInstalled(),
                ttsEnInstalled = repo.ttsInstalled("en"),
                ttsEsInstalled = repo.ttsInstalled("es"),
                voiceLog = logTail(8),
                voiceError = ttsClient.lastError,
            )
        }
    }

    fun setLang(lang: String) {
        _lang.value = if (lang == "es") "es" else "en"
        prefs.edit().putString(KEY_LANG, _lang.value).apply()
        // A different language is a different voice; the speech process reloads it on demand.
        ttsClient.stop()
    }

    fun setSpeakReplies(on: Boolean) {
        _speakReplies.value = on
        prefs.edit().putBoolean(KEY_SPEAK, on).apply()
        if (!on) ttsClient.stop()
    }

    // --- microphone ------------------------------------------------------------------------

    /** Starts capturing. Returns false (and sets [VoiceState.Failed]) if capture cannot start. */
    fun startListening(): Boolean {
        ttsClient.stop() // barge-in: talking over the assistant is how you interrupt it
        if (!repo.sttInstalled()) {
            _state.value = VoiceState.Failed("Speech model not downloaded yet")
            return false
        }
        if (!recorder.start()) {
            _state.value = VoiceState.Failed("Microphone unavailable")
            return false
        }
        _state.value = VoiceState.Listening(0f, 0f)
        return true
    }

    /** Called by the UI while recording so the meter and the timer move. */
    fun tick() {
        if (recorder.isRecording) {
            _state.value = VoiceState.Listening(recorder.level, recorder.seconds())
        }
    }

    fun cancelListening() {
        if (recorder.isRecording) recorder.stop()
        _state.value = VoiceState.Idle
    }

    /**
     * Ends the take and transcribes it. Returns the recognised text, or "" when nothing was
     * intelligible — in which case the UI says so rather than inventing a sentence.
     */
    suspend fun finishListening(): String {
        val samples = recorder.stop()
        val seconds = samples.size / SttEngine.SAMPLE_RATE.toFloat()
        if (seconds < MIN_SECONDS) {
            _state.value = VoiceState.Idle
            return ""
        }
        _state.value = VoiceState.Transcribing
        val started = System.currentTimeMillis()
        val text = withContext(Dispatchers.IO) {
            if (!stt.load()) "" else stt.transcribe(samples)
        }
        log("transcribed ${"%.1f".format(seconds)}s of audio in ${System.currentTimeMillis() - started} ms")
        _state.value = VoiceState.Idle
        return text
    }

    // --- speaker (in the speech process) ---------------------------------------------------

    /** Speaks [text] and suspends until the last word is out (or it is stopped). */
    suspend fun speak(text: String) {
        val clean = speakable(text)
        if (clean.isBlank() || !_speakReplies.value) return
        val espeak = withContext(Dispatchers.IO) {
            runCatching { repo.ensureEspeakData(context.assets).absolutePath }.getOrDefault("")
        }
        if (espeak.isEmpty()) {
            fail("Voice data missing")
            return
        }
        _state.value = VoiceState.Speaking(System.currentTimeMillis())
        log("asking the voice process to speak ${clean.length} chars")
        val ok = ttsClient.speak(clean, _lang.value, espeak)
        if (ok) {
            _state.value = VoiceState.Idle
            _models.update { it.copy(voiceError = null) }
        } else {
            fail(ttsClient.lastError ?: "the voice engine failed")
        }
    }

    private fun fail(message: String) {
        _state.value = VoiceState.Failed(message)
        _models.update { it.copy(voiceError = message) }
        log("FAILED $message")
    }

    fun stopSpeaking() {
        ttsClient.stop()
        if (_state.value is VoiceState.Speaking) _state.value = VoiceState.Idle
    }

    fun isSpeaking(): Boolean = ttsClient.isSpeaking

    /**
     * The self-check the sheet offers: files, engine, and a spoken test sentence, run *inside the
     * speech process* and reported in plain sentences.
     */
    suspend fun runVoiceCheck(): String {
        val report = ttsClient.testVoice(_lang.value)
        _models.update { it.copy(lastCheck = report, voiceError = ttsClient.lastError, voiceLog = logTail(8)) }
        return report
    }

    fun logTail(lines: Int = 10): String = runCatching {
        File(repo.root, "voice.log").readLines().takeLast(lines).joinToString("\n")
    }.getOrDefault("")

    /** App-side lines go into the same file as the speech process's, tagged so they can be told apart. */
    fun log(line: String) {
        val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        runCatching { File(repo.root, "voice.log").appendText("$stamp  [app] $line\n") }
        Log.i(TAG, line)
    }

    // --- downloads -------------------------------------------------------------------------

    /**
     * Fetches whichever half of the voice stack is missing: Whisper if speech recognition is not
     * installed, then the voice for the current language if that one is not either.
     */
    suspend fun downloadMissing() {
        if (_models.value.downloading) return
        val needStt = !repo.sttInstalled()
        val needTts = !repo.ttsInstalled(_lang.value)
        if (!needStt && !needTts) return

        _models.update { it.copy(downloading = true, progress = 0f, label = "Starting…") }
        try {
            if (needStt) {
                val set = VoiceRepository.WhisperTiny
                withContext(Dispatchers.IO) {
                    repo.download(set.files, repo.sttDir) { done, total, name ->
                        _models.update {
                            it.copy(
                                progress = if (total > 0) done.toFloat() / total else 0f,
                                label = "Speech model · $name · ${mb(done)}",
                            )
                        }
                    }
                }
            }
            if (needTts) {
                val lang = _lang.value
                val files = repo.ttsFiles(lang)
                withContext(Dispatchers.IO) {
                    repo.download(files, repo.ttsDir(lang)) { done, total, name ->
                        _models.update {
                            it.copy(
                                progress = if (total > 0) done.toFloat() / total else 0f,
                                label = (if (lang == "es") "Voz · " else "Voice · ") + "$name · ${mb(done)}",
                            )
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "voice download failed", t)
            _models.update { it.copy(label = "Download failed: ${t.message ?: "network error"}") }
        } finally {
            _models.update { it.copy(downloading = false, progress = 0f, label = "") }
            refresh()
        }
    }

    /** Free space check before a 100–160 MB download, so it fails with a sentence, not a stack. */
    fun hasRoomFor(bytes: Long): Boolean = runCatching { repo.root.usableSpace > bytes }.getOrDefault(true)

    fun modelTarget(): File = repo.root

    fun sttDescription(): String = repo.installedStt()?.label ?: "not installed"

    fun close() {
        if (recorder.isRecording) recorder.stop()
        stt.close()
        ttsClient.close()
    }

    private fun mb(bytes: Long): String = "%.0f MB".format(bytes / 1_048_576.0)

    companion object {
        private const val TAG = "VoiceController"
        private const val KEY_LANG = "lang"
        private const val KEY_SPEAK = "speak_replies"
        private const val MIN_SECONDS = 0.25f

        /**
         * Turns model output into something worth hearing: no markdown noise, no code blocks, no
         * URLs read out one character at a time.
         */
        fun speakable(text: String): String {
            var t = text
            t = t.replace(Regex("```[\\s\\S]*?```"), " ")
            t = t.replace(Regex("`([^`]*)`"), "$1")
            t = t.replace(Regex("!?\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
            t = t.replace(Regex("https?://\\S+"), " ")
            t = t.replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
            t = t.replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
            t = t.replace(Regex("(?m)^\\s*\\d+[.)]\\s+"), "")
            t = t.replace(Regex("\\*\\*|__|~~"), "")
            t = t.replace(Regex("[\\u{1F300}-\\u{1FAFF}\\u{2600}-\\u{27BF}\\u{FE0F}\\u{200D}]"), "")
            t = t.replace(Regex("\\n{2,}"), ". ")
            t = t.replace('\n', ' ')
            t = t.replace(Regex("\\s{2,}"), " ")
            return t.trim()
        }
    }
}
