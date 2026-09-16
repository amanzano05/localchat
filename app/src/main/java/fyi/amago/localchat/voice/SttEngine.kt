package fyi.amago.localchat.voice

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig

/**
 * Speech to text, on the phone, offline.
 *
 * Whisper through sherpa-onnx: the multilingual tiny/base models take `language` as a parameter,
 * so both of the user's languages come from the *same* 99 MB encoder+decoder pair — no second
 * model, no language autodetect guesswork ("auto" is passed as an empty language, which is what
 * sherpa-onnx understands as "detect").
 *
 * Loads lazily and stays loaded: the first transcription pays ~1 s of model init, the rest are
 * pure inference.
 */
class SttEngine(private val repo: VoiceRepository) {

    private var recognizer: OfflineRecognizer? = null
    private var loadedSet: String? = null

    val isLoaded: Boolean get() = recognizer != null

    /** Loads (or reuses) the recognizer. Safe to call from a background thread; returns false if
     *  the model files are not on the phone yet. */
    fun load(): Boolean {
        val set = repo.installedStt() ?: return false
        if (recognizer != null && loadedSet == set.id) return true
        close()
        return runCatching {
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = repo.whisperEncoder(set).absolutePath,
                        decoder = repo.whisperDecoder(set).absolutePath,
                        language = "",          // empty = let Whisper decide (en/es both supported)
                        task = "transcribe",
                        tailPaddings = 1000,
                    ),
                    tokens = repo.whisperTokens(set).absolutePath,
                    modelType = "whisper",
                    numThreads = THREADS,
                    provider = "cpu",
                    debug = false,
                ),
            )
            recognizer = OfflineRecognizer(config = config)
            loadedSet = set.id
            Log.i(TAG, "Whisper loaded: ${set.id}")
            true
        }.getOrElse {
            Log.e(TAG, "Whisper failed to load", it)
            recognizer = null
            false
        }
    }

    /**
     * One utterance in, one line of text out. [samples] are mono float32 in [-1, 1] at
     * [SAMPLE_RATE]. Empty string means "nothing recognisable" — never a guess.
     */
    fun transcribe(samples: FloatArray): String {
        val r = recognizer ?: if (!load()) return "" else recognizer!!
        if (samples.isEmpty()) return ""
        return runCatching {
            val stream = r.createStream()
            try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                r.decode(stream)
                r.getResult(stream).text.trim()
            } finally {
                stream.release()
            }
        }.getOrElse {
            Log.e(TAG, "Transcription failed", it)
            ""
        }
    }

    fun close() {
        runCatching { recognizer?.release() }
        recognizer = null
        loadedSet = null
    }

    companion object {
        private const val TAG = "SttEngine"
        const val SAMPLE_RATE = 16_000
        private const val THREADS = 4
    }
}
