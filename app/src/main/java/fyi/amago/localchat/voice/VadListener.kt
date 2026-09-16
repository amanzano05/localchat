package fyi.amago.localchat.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * The part that means you do not have to press anything.
 *
 * The microphone stays open and Silero VAD — 2 MB, running on this phone — decides when speech
 * started and, more importantly, when it stopped. Every finished utterance is handed over as
 * float32 samples, so the rest of the pipeline is identical to the button path: same Whisper, same
 * answers. Turning speech into turns is exactly what a button used to do, only now it is silence
 * doing the deciding instead of a thumb.
 *
 * Two rules keep it from talking to itself:
 *
 * - The caller must not listen while the assistant is speaking (the controller checks), and the
 *   listener gives up after [MAX_SILENCE_MS] so an idle room is not a microphone left on.
 * - Chunks that never become an utterance are discarded; only complete segments are returned.
 */
class VadListener(private val repo: VoiceRepository) {

    private var record: AudioRecord? = null

    /**
     * Blocks until one utterance has been heard and finished, then returns its samples (mono
     * float32 at 16 kHz). Returns null when the model is missing, the mic fails, [shouldStop]
     * turns true, or nothing was said within [MAX_SILENCE_MS].
     */
    fun listenForOneUtterance(shouldStop: () -> Boolean): FloatArray? {
        val vad = openVad() ?: return null
        val mic = openMic() ?: run { runCatching { vad.release() }; return null }
        try {
            mic.startRecording()
            val chunk = ShortArray(WINDOW)
            val started = System.currentTimeMillis()
            var spokeAtLeastOnce = false
            Log.i(TAG, "listening (hands-free)")
            while (true) {
                if (shouldStop()) return null
                if (System.currentTimeMillis() - started > MAX_SILENCE_MS && !spokeAtLeastOnce) return null

                val n = mic.read(chunk, 0, chunk.size)
                if (n <= 0) continue
                val samples = FloatArray(n)
                for (i in 0 until n) samples[i] = chunk[i] / 32768f
                vad.acceptWaveform(samples)
                if (vad.isSpeechDetected()) spokeAtLeastOnce = true

                while (!vad.empty()) {
                    val segment = vad.front()
                    vad.pop()
                    val utterance = segment.samples
                    // A cough, a door, or this phone's own voice is not a turn.
                    if (utterance.size >= MIN_UTTERANCE_SAMPLES) {
                        Log.i(TAG, "utterance: ${utterance.size / 16_000f}s")
                        return utterance
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "hands-free listening failed", t)
            return null
        } finally {
            runCatching { mic.stop() }
            runCatching { mic.release() }
            record = null
            runCatching { vad.release() }
        }
    }

    /** Stops a listen in progress from another thread. */
    fun cancel() {
        runCatching { record?.stop() }
    }

    private fun openVad(): Vad? {
        if (!repo.vadInstalled()) {
            Log.i(TAG, "VAD model not downloaded")
            return null
        }
        return runCatching {
            val config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = repo.vadModel().absolutePath,
                    threshold = 0.5f,
                    minSilenceDuration = 0.4f,   // a breath is not the end of a sentence
                    minSpeechDuration = 0.25f,
                    windowSize = WINDOW,
                    maxSpeechDuration = 20.0f,
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu",
                debug = false,
            )
            Vad(config = config)
        }.getOrElse {
            Log.e(TAG, "VAD failed to load", it)
            null
        }
    }

    @SuppressLint("MissingPermission") // the caller checks RECORD_AUDIO
    private fun openMic(): AudioRecord? = runCatching {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf <= 0) return null
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, CHANNEL, ENCODING,
            minBuf * 2,
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            null
        } else {
            record = rec
            rec
        }
    }.getOrElse {
        Log.e(TAG, "mic failed to open for hands-free", it)
        null
    }

    companion object {
        private const val TAG = "VadListener"
        const val SAMPLE_RATE = 16_000
        private const val WINDOW = 512
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val MIN_UTTERANCE_SAMPLES = 4_800   // 0.3 s
        private const val MAX_SILENCE_MS = 25_000L
    }
}
