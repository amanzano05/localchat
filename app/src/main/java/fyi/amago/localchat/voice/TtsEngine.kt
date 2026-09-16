package fyi.amago.localchat.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

/**
 * Text to speech, on the phone, offline: Piper (VITS) voices through sherpa-onnx, one voice per
 * language, espeak-ng for phonemes.
 *
 * Two rules shape this class, both learned the hard way:
 *
 * 1. **Never do audio work inside the synthesiser's callback.** That callback is entered from native
 *    code; anything that throws there unwinds through JNI and takes the process down with no Java
 *    exception to catch. The callback only copies samples into a queue — a writer thread owns the
 *    `AudioTrack`.
 * 2. **Never touch the engine or the track without knowing they are valid.** Loading is verified
 *    (`sampleRate()` inside a guard) before a single call is made into it, and every failure is
 *    written to [logFile] and reported through [lastError] instead of being swallowed.
 */
class TtsEngine(private val repo: VoiceRepository) {

    private var tts: OfflineTts? = null
    private var loadedLang: String? = null
    private val speaking = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)

    /**
     * Serialises everything that touches the native engine. Two things were racing here and both
     * end in the same place — a freed ONNX session in use:
     *
     * - `load()` used to `close()` the old engine while a synthesis was still running on the IO
     *   thread (press "Test voice" mid-answer, or just reply quickly).
     * - `close()` released the engine from the main thread while `speak()` was mid-generation.
     *
     * A lock is the whole fix; the release that has to wait is deferred to the end of the utterance.
     */
    private val engineLock = Any()

    @Volatile private var pendingClose = false

    @Volatile var lastError: String? = null
        private set

    val isReady: Boolean get() = tts != null
    val isSpeaking: Boolean get() = speaking.get()

    val logFile: File get() = File(repo.root, "voice.log")

    /**
     * Records how much memory the process is holding before a synthesis run. Native heap is the
     * number that matters — the models live there, and the Java heap limit says nothing about it.
     */
    fun logMemory(where: String) {
        val runtime = Runtime.getRuntime()
        val javaUsed = (runtime.totalMemory() - runtime.freeMemory()) / 1_048_576
        val javaMax = runtime.maxMemory() / 1_048_576
        val nativeUsed = android.os.Debug.getNativeHeapAllocatedSize() / 1_048_576
        val nativeSize = android.os.Debug.getNativeHeapSize() / 1_048_576
        log("memory at $where: java ${javaUsed}MB/${javaMax}MB, native ${nativeUsed}MB/${nativeSize}MB")
    }

    /** Appends one line to the on-device voice log, so a field problem can be read back later. */
    fun log(line: String) {
        val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        runCatching { logFile.appendText("$stamp  $line\n") }
        Log.i(TAG, line)
    }

    /** Keeps the log short enough to show in a sheet: last 120 lines. */
    fun logTail(lines: Int = 12): String = runCatching {
        logFile.readLines().takeLast(lines).joinToString("\n")
    }.getOrDefault("")

    /**
     * Loads the voice for [lang]. Returns false (with [lastError] set) when the voice is not
     * installed or the engine refuses to come up.
     */
    fun load(voiceId: String, espeakDir: String): Boolean = synchronized(engineLock) { loadLocked(voiceId, espeakDir) }

    private fun loadLocked(voiceId: String, espeakDir: String): Boolean {
        if (tts != null && loadedLang == voiceId) return true
        releaseEngine()
        val option = repo.voice(voiceId) ?: run {
            lastError = "Unknown voice $voiceId"
            return false
        }
        if (!repo.voiceInstalled(option)) {
            lastError = "La voz ${option.label} todavía no está descargada"
            return false
        }
        val model = repo.voiceModelFile(option)
        val tokens = repo.voiceTokensFile(option)
        if (!looksLikeOnnx(model) || !tokens.isFile) {
            lastError = "Voice file is damaged — download the voice again"
            log("REFUSING load: ${model.name}=${model.length()}B tokens=${tokens.length()}B")
            return false
        }
        val espeak = File(espeakDir)
        if (!espeak.isDirectory) {
            lastError = "Phoneme data is missing"
            log("REFUSING load: espeak dir absent: $espeakDir")
            return false
        }
        // Logged *before* the native call: if the process dies here, the last line names the step.
        log("loading voice $voiceId: model=${model.length() / 1_048_576}MB dataDir=$espeakDir")
        return runCatching {
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = model.absolutePath,
                        tokens = tokens.absolutePath,
                        dataDir = espeak.absolutePath,
                    ),
                    numThreads = THREADS,
                    provider = "cpu",
                    debug = false,
                ),
                maxNumSentences = 1,
                silenceScale = 0.2f,
            )
            val engine = OfflineTts(config = config)
            // Prove the engine answers before trusting it: a null native handle returns 0 here
            // instead of segfaulting later, mid-sentence.
            val rate = engine.sampleRate()
            if (rate <= 0) {
                lastError = "Voice engine did not start"
                log("LOAD FAILED: sampleRate=$rate")
                runCatching { engine.release() }
                return false
            }
            tts = engine
            loadedLang = voiceId
            lastError = null
            log("LOADED voice $voiceId @ ${rate}Hz (${model.name}, ${model.length() / 1_048_576}MB)")
            logMemory("after load")
            true
        }.getOrElse {
            lastError = "Voice engine error: ${it.message ?: it.javaClass.simpleName}"
            log("LOAD THREW: ${it.javaClass.simpleName}: ${it.message}")
            tts = null
            false
        }
    }

    /**
     * Speaks [text] from start to finish. Blocking on purpose: the caller runs it on
     * Dispatchers.IO and returning is what marks the end of the turn.
     *
     * **`generate()`, never `generateWithCallback()`.** The callback variant makes the native
     * synthesiser call back up into the JVM once per chunk, and on this phone that call is what
     * killed the process (the log always ended at "generating", one line past a healthy load). The
     * plain call returns the audio instead, so synthesis stays entirely inside native code and the
     * only cross-language traffic is the return value. Responsiveness is kept by splitting the
     * answer into sentence-sized chunks and generating the next one while the current one plays.
     */
    fun speak(text: String, voiceId: String, espeakDir: String, speed: Float = 1.0f) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        synchronized(engineLock) {
            if (pendingClose) {
                releaseEngine()
                pendingClose = false
            }
            speakLocked(clean, voiceId, espeakDir, speed)
        }
    }

    private fun speakLocked(clean: String, voiceId: String, espeakDir: String, speed: Float) {
        if (!load(voiceId, espeakDir)) return
        val engine = tts ?: return

        val sampleRate = runCatching { engine.sampleRate() }.getOrDefault(0)
        if (sampleRate <= 0) {
            lastError = "Voice engine is not answering"
            log("speak: sampleRate=$sampleRate, aborting")
            return
        }

        val queue = ArrayBlockingQueue<FloatArray>(64)
        val finished = AtomicBoolean(false)
        val writerError = AtomicReference<String?>(null)
        val out = openTrack(sampleRate) ?: return

        speaking.set(true)
        stopping.set(false)

        // The writer owns the track for the whole utterance; nothing else touches it until join().
        val writer = Thread({
            var written = 0L
            try {
                out.play()
                while (true) {
                    val chunk = queue.poll(250, TimeUnit.MILLISECONDS) ?: if (finished.get()) break else continue
                    if (chunk.isEmpty()) continue
                    val pcm = ShortArray(chunk.size)
                    for (i in chunk.indices) {
                        pcm[i] = (chunk[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    }
                    val n = out.write(pcm, 0, pcm.size)
                    if (n < 0) {
                        writerError.set("AudioTrack.write returned $n")
                        break
                    }
                    written += n
                }
            } catch (t: Throwable) {
                writerError.set("${t.javaClass.simpleName}: ${t.message}")
            }
            log("speak: wrote $written samples (${written / sampleRate.toFloat()}s of audio)")
        }, "tts-writer").apply { isDaemon = true; start() }

        // Produce here, on the caller's IO thread: sentence by sentence, so the first words start
        // playing while the rest is still being synthesised.
        val chunks = sentenceChunks(clean)
        try {
            logMemory("speak")
            log("generating ${chunks.size} chunk(s), ${clean.length} chars")
            for ((index, chunk) in chunks.withIndex()) {
                if (stopping.get()) break
                // Logged before the call, with the text: if the engine dies here, this line names
                // the exact string that killed it, and the same string can be replayed on a laptop.
                log("  chunk ${index + 1}/${chunks.size} (${chunk.length} chars): ${chunk.take(80)}")
                val audio = engine.generate(chunk, 0, speed)
                if (stopping.get()) break
                log("    -> ${audio.samples.size} samples")
                queue.offer(audio.samples)
            }
        } catch (t: Throwable) {
            lastError = "Speech failed: ${t.message ?: t.javaClass.simpleName}"
            log("GENERATE THREW: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            stopping.set(true)
            speaking.set(false)
            finished.set(true)
            queue.offer(FloatArray(0))
            runCatching { writer.join(5_000) }
            if (writer.isAlive) {
                // Freeing a track another thread may still be writing to is a native crash.
                // Leaking one AudioTrack is cheap; killing the app is not.
                log("writer still busy after 5s: not releasing the track")
            } else {
                closeTrack(out)
            }
            if (pendingClose) {
                releaseEngine()
                pendingClose = false
            }
            writerError.get()?.let {
                lastError = "Audio output failed: $it"
                log("WRITER ERROR: $it")
            }
        }
    }

    /**
     * Splits an answer into chunks small enough to start speaking quickly, without ever cutting a
     * word in half. Long sentences are broken at commas first, then at spaces.
     */
    private fun sentenceChunks(text: String, limit: Int = 180): List<String> {
        val sentences = ArrayList<String>()
        val current = StringBuilder()
        for (ch in text) {
            current.append(ch)
            if (ch == '.' || ch == '!' || ch == '?' || ch == ';' || ch == ':' || ch == '\u2026') {
                if (current.toString().trim().isNotEmpty()) sentences.add(current.toString().trim())
                current.setLength(0)
            }
        }
        if (current.toString().trim().isNotEmpty()) sentences.add(current.toString().trim())

        // merge tiny fragments ("Ok." + "Sure.") so it does not speak in staccato
        val merged = ArrayList<String>()
        for (s in sentences) {
            val last = merged.lastOrNull()
            if (last != null && (last.length + s.length + 1) <= limit) {
                merged[merged.lastIndex] = "$last $s"
            } else {
                merged.add(s)
            }
        }
        return merged.flatMap { piece ->
            if (piece.length <= limit) listOf(piece)
            else piece.split(", ").flatMap { part ->
                if (part.length <= limit) listOf(part)
                else part.chunked(limit).map { it.trim() }.filter { it.isNotEmpty() }
            }
        }.filter { it.isNotBlank() }
    }

    private fun openTrack(sampleRate: Int): AudioTrack? = runCatching {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(max(if (minBuf > 0) minBuf else 4_096, sampleRate / 5 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track
    }.getOrElse {
        lastError = "Could not open the speaker: ${it.message}"
        log("AUDIOTRACK FAILED: ${it.javaClass.simpleName}: ${it.message}")
        null
    }

    private fun closeTrack(out: AudioTrack) {
        runCatching { if (out.playState != AudioTrack.PLAYSTATE_STOPPED) out.stop() }
        runCatching { out.flush() }
        runCatching { out.release() }
    }

    /** Barge-in: stop mid-word. Safe from any thread; the writer notices and unwinds. */
    fun stop() {
        stopping.set(true)
        speaking.set(false)
    }

    fun close() {
        stop()
        if (speaking.get()) {
            // Do not take the lock here: close() is called from the main thread, and waiting on a
            // half-minute of synthesis would freeze the UI. Hand the job to the running speak().
            pendingClose = true
            log("close deferred until the current utterance ends")
            return
        }
        synchronized(engineLock) { releaseEngine() }
    }

    /** Caller must hold [engineLock]. */
    private fun releaseEngine() {
        runCatching { tts?.release() }
        tts = null
        loadedLang = null
    }

    /** ONNX models are protobuf; an HTML error page from the CDN is what a bad download looks like. */
    private fun looksLikeOnnx(f: File): Boolean {
        if (!f.isFile || f.length() < 1_000_000) return false
        return runCatching {
            f.inputStream().use { input ->
                val head = ByteArray(8)
                val n = input.read(head)
                n > 0 && head[0] != '<'.code.toByte() && head[0] != 0x0A.toByte()
            }
        }.getOrDefault(false)
    }

    companion object {
        private const val TAG = "TtsEngine"
        private const val THREADS = 2
    }
}
