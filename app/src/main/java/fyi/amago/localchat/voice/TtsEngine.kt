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
    private var track: AudioTrack? = null
    private val speaking = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)

    @Volatile var lastError: String? = null
        private set

    val isReady: Boolean get() = tts != null
    val isSpeaking: Boolean get() = speaking.get()

    val logFile: File get() = File(repo.root, "voice.log")

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
    fun load(lang: String, espeakDir: String): Boolean {
        if (tts != null && loadedLang == lang) return true
        close()
        if (!repo.ttsInstalled(lang)) {
            lastError = "Voice for $lang is not downloaded"
            return false
        }
        val model = repo.ttsModelFile(lang)
        val tokens = repo.ttsTokensFile(lang)
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
        log("loading voice $lang: model=${model.length() / 1_048_576}MB dataDir=$espeakDir")
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
            loadedLang = lang
            lastError = null
            log("LOADED voice $lang @ ${rate}Hz (${model.name}, ${model.length() / 1_048_576}MB)")
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
     */
    fun speak(text: String, lang: String, espeakDir: String, speed: Float = 1.0f) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (!load(lang, espeakDir)) return
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
                // Caught *here*, off the native callback, so it can never unwind through JNI.
                writerError.set("${t.javaClass.simpleName}: ${t.message}")
            }
            log("speak: wrote $written samples (${written / sampleRate.toFloat()}s of audio)")
        }, "tts-writer").apply { isDaemon = true; start() }

        try {
            log("generating ${clean.length} chars")
            engine.generateWithCallback(text = clean, sid = 0, speed = speed) { samples ->
                // Inside JNI: no I/O, no throwing, no heavy work. Copy and hand off, nothing else.
                try {
                    if (stopping.get()) return@generateWithCallback 0
                    val copy = FloatArray(samples.size)
                    System.arraycopy(samples, 0, copy, 0, samples.size)
                    queue.offer(copy)
                    if (stopping.get()) 0 else 1
                } catch (t: Throwable) {
                    log("callback swallowed ${t.javaClass.simpleName}")
                    0
                }
            }
        } catch (t: Throwable) {
            lastError = "Speech failed: ${t.message ?: t.javaClass.simpleName}"
            log("GENERATE THREW: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            stopping.set(true)
            speaking.set(false)
            finished.set(true)
            // Drain the queue so the writer can finish, then let it exit before the track dies.
            queue.offer(FloatArray(0))
            runCatching { writer.join(2_000) }
            closeTrack(out)
            writerError.get()?.let {
                lastError = "Audio output failed: $it"
                log("WRITER ERROR: $it")
            }
        }
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
        if (track === out) track = null
    }

    /** Barge-in: stop mid-word. Safe from any thread; the writer notices and unwinds. */
    fun stop() {
        stopping.set(true)
        speaking.set(false)
    }

    fun close() {
        stop()
        runCatching { track?.pause() }
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
