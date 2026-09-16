package fyi.amago.localchat.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.sqrt

/**
 * Microphone capture for speech recognition: 16 kHz mono PCM16, which is exactly what Whisper
 * wants, so there is no resampling anywhere in the path.
 *
 * [VOICE_RECOGNITION] as the source (not MIC) because it is the one Android tunes for ASR —
 * noise suppression and AGC that help a human listener but hurt a recogniser are skipped.
 *
 * Recording runs on its own thread and reports an RMS level so the UI can show that it is hearing
 * something; a hard ceiling of [MAX_MS] stops a stuck session from eating the battery.
 */
class VoiceRecorder {

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private val buffer = ArrayList<Short>(SAMPLE_RATE * 8)

    @Volatile var level: Float = 0f
        private set

    @Volatile var startedAt: Long = 0L
        private set

    val isRecording: Boolean get() = running

    @SuppressLint("MissingPermission") // caller checks RECORD_AUDIO before starting
    fun start(): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf <= 0) return false
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, CHANNEL, ENCODING,
                minBuf * 2,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "AudioRecord could not be created", t)
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        buffer.clear()
        level = 0f
        startedAt = System.currentTimeMillis()
        record = rec
        running = true
        rec.startRecording()

        thread = Thread({
            val chunk = ShortArray(minBuf.coerceAtLeast(1024))
            while (running) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n <= 0) continue
                synchronized(buffer) {
                    for (i in 0 until n) buffer.add(chunk[i])
                }
                var sum = 0.0
                for (i in 0 until n) {
                    val v = chunk[i] / 32768.0
                    sum += v * v
                }
                val rms = sqrt(sum / n).toFloat()
                // gentle smoothing so the meter does not flicker
                level = level * 0.6f + rms * 0.4f
                if (System.currentTimeMillis() - startedAt > MAX_MS) running = false
            }
        }, "voice-recorder").apply { isDaemon = true; start() }
        return true
    }

    /** Stops capture and hands back everything that was heard, as float32 in [-1, 1]. */
    fun stop(): FloatArray {
        running = false
        thread?.let { runCatching { it.join(1_000) } }
        thread = null
        val rec = record
        record = null
        runCatching {
            rec?.stop()
            rec?.release()
        }
        val samples: FloatArray
        synchronized(buffer) {
            samples = FloatArray(buffer.size)
            for (i in buffer.indices) samples[i] = buffer[i] / 32768f
            buffer.clear()
        }
        level = 0f
        return samples
    }

    /** Length of the current take, in seconds — what the UI shows next to the meter. */
    fun seconds(): Float =
        if (startedAt == 0L) 0f else (System.currentTimeMillis() - startedAt) / 1000f

    companion object {
        private const val TAG = "VoiceRecorder"
        const val SAMPLE_RATE = 16_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val MAX_MS = 60_000L
    }
}
