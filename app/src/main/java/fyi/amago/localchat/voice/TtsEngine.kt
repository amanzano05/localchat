package fyi.amago.localchat.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Text to speech, on the phone, offline: Piper (VITS) voices through sherpa-onnx, one voice per
 * language, espeak-ng for phonemes.
 *
 * Speech starts as soon as the first chunk is synthesised (`generateWithCallback` → `AudioTrack`
 * in streaming mode), so a long answer does not wait for its last word to be audible. Barge-in is
 * one call: [stop] flushes the track and tells the synthesiser to stop.
 */
class TtsEngine(private val repo: VoiceRepository) {

    private var tts: OfflineTts? = null
    private var loadedLang: String? = null
    private var track: AudioTrack? = null
    private val speaking = AtomicBoolean(false)

    val isReady: Boolean get() = tts != null
    val isSpeaking: Boolean get() = speaking.get()

    /** Loads the voice for [lang] ("en" / "es"). Returns false when that voice is not installed. */
    fun load(lang: String, espeakDir: String): Boolean {
        if (tts != null && loadedLang == lang) return true
        close()
        if (!repo.ttsInstalled(lang)) return false
        return runCatching {
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = repo.ttsModelFile(lang).absolutePath,
                        tokens = repo.ttsTokensFile(lang).absolutePath,
                        dataDir = espeakDir,
                    ),
                    numThreads = THREADS,
                    provider = "cpu",
                    debug = false,
                ),
                maxNumSentences = 1,
                silenceScale = 0.2f,
            )
            tts = OfflineTts(config = config)
            loadedLang = lang
            Log.i(TAG, "Piper voice loaded: $lang @ ${tts?.sampleRate()} Hz")
            true
        }.getOrElse {
            Log.e(TAG, "TTS failed to load for $lang", it)
            tts = null
            false
        }
    }

    /**
     * Speaks [text] from start to finish, streaming. Blocking on purpose: the controller calls it
     * from a coroutine on Dispatchers.IO, and returning is what marks the end of the turn.
     */
    fun speak(text: String, lang: String, espeakDir: String, speed: Float = 1.0f) {
        val engine = tts ?: if (load(lang, espeakDir)) tts!! else return
        val clean = text.trim()
        if (clean.isEmpty()) return

        val sampleRate = engine.sampleRate()
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val out = AudioTrack.Builder()
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
            .setBufferSizeInBytes(max(minBuf, sampleRate * 2 / 5)) // ~200 ms of slack
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        track = out
        speaking.set(true)
        out.play()
        try {
            engine.generateWithCallback(text = clean, sid = 0, speed = speed) { samples ->
                // Returning 0 asks the synthesiser to stop early — that is the barge-in path.
                if (!speaking.get()) return@generateWithCallback 0
                val pcm = ShortArray(samples.size)
                for (i in samples.indices) {
                    pcm[i] = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                }
                out.write(pcm, 0, pcm.size)
                1
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Speech synthesis failed", t)
        } finally {
            speaking.set(false)
            runCatching {
                out.stop()
                out.release()
            }
            if (track === out) track = null
        }
    }

    /** Barge-in: stop mid-word. Safe to call from anywhere. */
    fun stop() {
        speaking.set(false)
        runCatching { track?.pause(); track?.flush() }
    }

    fun close() {
        stop()
        runCatching { tts?.release() }
        tts = null
        loadedLang = null
    }

    companion object {
        private const val TAG = "TtsEngine"
        private const val THREADS = 2
        const val AUDIO_MANAGER_STREAM = AudioManager.STREAM_MUSIC
    }
}
