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

/** One selectable voice, as the sheet needs to draw it. */
data class VoiceChoice(
    val id: String,
    val label: String,
    val note: String,
    val megabytes: Int,
    val installed: Boolean,
    val selected: Boolean,
)

/** Voice models: what is on the phone and how far a download has got. */
data class VoiceModelsState(
    val sttInstalled: Boolean = false,
    val ttsEnInstalled: Boolean = false,
    val ttsEsInstalled: Boolean = false,
    /** The voices available for the current language, with what is installed and what is in use. */
    val choices: List<VoiceChoice> = emptyList(),
    val vadInstalled: Boolean = false,
    val handsFree: Boolean = false,
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

    /** Hands-free needs everything voice needs, plus the detector. */
    val handsFreeReady: Boolean get() = ready && vadInstalled
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
    private val listener = VadListener(repo)
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

    /**
     * Hands-free: the microphone opens by itself and silence closes the turn, so the phone behaves
     * like a conversation instead of a walkie-talkie.
     */
    private val _handsFree = MutableStateFlow(prefs.getBoolean(KEY_HANDS_FREE, false))
    val handsFree: StateFlow<Boolean> = _handsFree.asStateFlow()

    init {
        // If the marker from a previous attempt is still set, that attempt never returned: the
        // engine died mid-sentence. Say so on launch, in the chat, where it cannot be missed.
        if (prefs.getBoolean(KEY_INFLIGHT, false)) {
            prefs.edit().putBoolean(KEY_INFLIGHT, false).apply()
            _models.update {
                it.copy(
                    voiceError = "La voz se quedó a medias la última vez y el motor se cayó",
                    voiceLog = logTail(10),
                )
            }
        }
        refresh()
    }

    fun refresh() {
        _models.update {
            it.copy(
                sttInstalled = repo.sttInstalled(),
                ttsEnInstalled = repo.anyVoiceInstalled("en"),
                ttsEsInstalled = repo.anyVoiceInstalled("es"),
                choices = choices(),
                vadInstalled = repo.vadInstalled(),
                handsFree = _handsFree.value,
                voiceLog = logTail(8),
                voiceError = ttsClient.lastError,
            )
        }
    }

    private fun choices(): List<VoiceChoice> {
        val selected = voiceId(_lang.value)
        return repo.voicesFor(_lang.value).map { option ->
            VoiceChoice(
                id = option.id,
                label = option.label,
                note = option.note,
                megabytes = option.megabytes,
                installed = repo.voiceInstalled(option),
                selected = option.id == selected,
            )
        }
    }

    fun setLang(lang: String) {
        _lang.value = if (lang == "es") "es" else "en"
        prefs.edit().putString(KEY_LANG, _lang.value).apply()
        // A different language is a different voice; the speech process reloads it on demand.
        ttsClient.stop()
        refresh()
    }

    /** Which voice speaks for [lang]: the one that was picked, or the language default. */
    fun voiceId(lang: String): String {
        val stored = prefs.getString(KEY_VOICE_PREFIX + lang, null)
        val option = stored?.let { repo.voice(it) }
        return if (option != null && option.lang == lang) option.id else repo.defaultVoice(lang).id
    }

    fun selectedVoice(): VoiceRepository.VoiceOption =
        repo.voice(voiceId(_lang.value)) ?: repo.defaultVoice(_lang.value)

    /** Picks a voice. If it is not on the phone yet, the sheet downloads it. */
    fun setVoice(id: String) {
        val option = repo.voice(id) ?: return
        prefs.edit().putString(KEY_VOICE_PREFIX + option.lang, option.id).apply()
        ttsClient.stop()
        log("voice selected: ${option.id}")
        refresh()
    }

    fun setHandsFree(on: Boolean) {
        _handsFree.value = on
        prefs.edit().putBoolean(KEY_HANDS_FREE, on).apply()
        if (!on) listener.cancel()
        log("hands-free " + if (on) "on" else "off")
        refresh()
    }

    /**
     * One hands-free turn: waits for the person to speak and stop, transcribes on the phone, and
     * returns what was said — or null when nothing happened. Blocking, so callers run it off the
     * main thread.
     */
    suspend fun listenOnce(): String? {
        if (!repo.vadInstalled() || !repo.sttInstalled()) return null
        _state.value = VoiceState.Listening(0f, 0f)
        val samples = withContext(Dispatchers.IO) {
            listener.listenForOneUtterance { !_handsFree.value }
        }
        if (samples == null) {
            _state.value = VoiceState.Idle
            return null
        }
        _state.value = VoiceState.Transcribing
        val text = withContext(Dispatchers.IO) { if (stt.load()) stt.transcribe(samples) else "" }
        _state.value = VoiceState.Idle
        return text.ifBlank { null }
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
        listener.cancel() // and the hands-free listener must let go of the microphone
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
        log("speak() called with ${text.length} chars")
        runCatching { speakInner(text) }.onFailure {
            fail("Voice failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private suspend fun speakInner(text: String) {
        // Logged before anything else in this method: if the app dies and the log ends here, the
        // crash is in this call at all — not in the engine, not in the text processing below.
        val clean = speakable(text)
        log("speakable() -> ${clean.length} chars: ${clean.take(80)}")
        if (clean.isBlank() || !_speakReplies.value) return
        val espeak = withContext(Dispatchers.IO) {
            runCatching { repo.ensureEspeakData(context.assets).absolutePath }.getOrDefault("")
        }
        log("phoneme data ready: ${espeak.ifEmpty { "MISSING" }}")
        if (espeak.isEmpty()) {
            fail("Voice data missing")
            return
        }
        _state.value = VoiceState.Speaking(System.currentTimeMillis())
        val voice = selectedVoice()
        log("asking the voice process to speak ${clean.length} chars with ${voice.id}")
        prefs.edit().putBoolean(KEY_INFLIGHT, true).apply()
        val ok = ttsClient.speak(clean, voice.id, espeak)
        prefs.edit().putBoolean(KEY_INFLIGHT, false).apply()
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
        val report = ttsClient.testVoice(selectedVoice().id)
        _models.update { it.copy(lastCheck = report, voiceError = ttsClient.lastError, voiceLog = logTail(8)) }
        return report
    }

    fun clearVoiceError() {
        _models.update { it.copy(voiceError = null) }
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
        val voice = selectedVoice()
        val needStt = !repo.sttInstalled()
        val needTts = !repo.voiceInstalled(voice)
        val needVad = !repo.vadInstalled()
        if (!needStt && !needTts && !needVad) return

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
                withContext(Dispatchers.IO) {
                    repo.download(voice.files, repo.ttsDir(voice.id)) { done, total, name ->
                        _models.update {
                            it.copy(
                                progress = if (total > 0) done.toFloat() / total else 0f,
                                label = "Voz · $name · ${mb(done)}",
                            )
                        }
                    }
                }
            }
            if (needVad) {
                withContext(Dispatchers.IO) {
                    repo.download(listOf(repo.vadFile), repo.vadDir) { done, total, name ->
                        _models.update {
                            it.copy(
                                progress = if (total > 0) done.toFloat() / total else 0f,
                                label = "Detector de voz · ${mb(done)}",
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
        private const val KEY_VOICE_PREFIX = "voice_"
        private const val KEY_HANDS_FREE = "hands_free"
        private const val KEY_SPEAK = "speak_replies"
        private const val KEY_INFLIGHT = "speech_inflight"
        private const val MIN_SECONDS = 0.25f

        /**
         * Turns model output into something worth hearing: no markdown noise, no code blocks, no
         * URLs read out one character at a time.
         */
        fun speakable(text: String): String =
            runCatching { speakableUnsafe(text) }.getOrElse { text.take(600) }

        private fun speakableUnsafe(text: String): String {
            var t = text
            // Typographic punctuation first: these are the characters a model emits and a
            // phonemiser has never seen. Straight ASCII equivalents read the same aloud.
            val normalise = mapOf(
                "\u2018" to "'", "\u2019" to "'", "\u201A" to ",",
                "\u201C" to "\"", "\u201D" to "\"", "\u201E" to "\"",
                "\u2013" to "-", "\u2014" to "-", "\u2212" to "-",
                "\u2026" to ".", "\u00A0" to " ", "\u202F" to " ",
                "\u200B" to " ", "\u200C" to " ", "\u200D" to " ",
                "\u2022" to ".", "\u00B7" to ".",
                "\u2192" to " to ", "\u2265" to " at least ", "\u2264" to " at most ", "\u00D7" to " by ",
            )
            for ((from, to) in normalise) t = t.replace(from, to)
            t = t.replace(Regex("```[\\s\\S]*?```"), " ")
            t = t.replace(Regex("`([^`]*)`"), "$1")
            t = t.replace(Regex("!?\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
            t = t.replace(Regex("https?://\\S+"), " ")
            // Markers become a beat instead of nothing, or a list reads as one long sentence.
            t = t.replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), ". ")
            t = t.replace(Regex("(?m)^\\s*[-*+]\\s+"), ". ")
            t = t.replace(Regex("(?m)^\\s*\\d+[.)]\\s+"), ". ")
            t = t.replace(Regex("\\*\\*|__|~~"), "")
            // Emoji, arrows, maths and symbol planes, plus variation selectors: characters no voice
            // can pronounce. Done in plain code on purpose — `\\u{1F000}` is *not* a valid escape in
            // Java's regex engine, and that single wrong pattern used to take the whole app down
            // (PatternSyntaxException inside the answer coroutine, with no exception handler above it).
            t = buildString(t.length) {
                for (ch in t) {
                    val c = ch.code
                    val drop = c >= 0x1F000 ||
                        c in 0x2190..0x2BFF ||
                        c in 0xFE00..0xFE0F ||
                        c in 0x2000..0x206F
                    if (!drop) append(ch)
                }
            }
            t = t.replace(Regex("[^\\p{L}\\p{N} .,;:!?'\"()\\-/%]"), " ")
            // A run of punctuation with no speech in it (---, ***, |...|) is noise.
            t = t.replace(Regex("\\s*([.,;:!?%\\-])\\1+\\s*"), " ")
            t = t.replace(Regex("\\s*[|^~`<>\\[\\]{}()]+\\s*"), " ")
            t = t.replace(Regex("\\n{2,}"), ". ")
            t = t.replace('\n', ' ')
            t = t.replace(Regex("\\s{2,}"), " ")
            return t.trim()
        }
    }
}
