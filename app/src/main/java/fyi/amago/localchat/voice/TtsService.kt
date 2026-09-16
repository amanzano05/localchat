package fyi.amago.localchat.voice

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import java.io.File
import java.util.concurrent.Executors

/**
 * The speech engine, in a process of its own.
 *
 * Why this exists: the Piper/espeak/ONNX path is a third-party native stack, and it was taking the
 * whole app down when it ran in the same process as the language model — no Java exception, no
 * stack trace, just a dead app in the user's hand. A native library we do not control must not be
 * able to do that, so it lives in `:speech`: if it dies, Android kills *that* process, the chat
 * keeps working, and the user gets a sentence instead of a crash.
 *
 * It also gives the engine its own memory budget instead of sharing one with a 1.9 GB model.
 *
 * Protocol (Messenger, no AIDL): send [MSG_SPEAK] / [MSG_STOP] / [MSG_TEST] / [MSG_STATUS_QUERY],
 * receive [MSG_STATE] and [MSG_DONE] with a bundle. Everything is one short request at a time, one
 * worker thread, so the engine is never entered twice at once.
 */
class TtsService : Service() {

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "tts-worker") }
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { msg -> onMessage(msg); true })
    private lateinit var repo: VoiceRepository
    private lateinit var engine: TtsEngine

    override fun onCreate() {
        super.onCreate()
        repo = VoiceRepository(this)
        engine = TtsEngine(repo)
        engine.log("service started (process ${android.os.Process.myPid()})")
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun onMessage(msg: Message) {
        val reply = msg.replyTo
        when (msg.what) {
            MSG_SPEAK -> {
                val data = msg.data
                val text = data.getString(KEY_TEXT).orEmpty()
                val voice = data.getString(KEY_VOICE) ?: "en"
                val espeak = data.getString(KEY_ESPEAK).orEmpty()
                val speed = data.getFloat(KEY_SPEED, 1.0f)
                val id = data.getInt(KEY_ID, 0)
                worker.execute {
                    sendState(reply, STATE_SPEAKING, id)
                    val ok = runCatching {
                        engine.speak(text, voice, espeak, speed)
                        engine.lastError == null
                    }.getOrElse {
                        engine.log("SERVICE SPEAK THREW: ${it.javaClass.simpleName}: ${it.message}")
                        false
                    }
                    sendState(reply, STATE_IDLE, id)
                    sendDone(reply, id, ok, engine.lastError)
                }
            }

            MSG_STOP -> engine.stop()

            MSG_TEST -> {
                val voice = msg.data.getString(KEY_VOICE) ?: "en"
                val id = msg.data.getInt(KEY_ID, 0)
                worker.execute {
                    val report = runCatching { selfTest(voice) }
                        .getOrElse { "test threw: ${it.javaClass.simpleName}: ${it.message}" }
                    sendDone(reply, id, true, report)
                }
            }

            MSG_STATUS_QUERY -> sendState(
                reply,
                if (engine.isSpeaking) STATE_SPEAKING else STATE_IDLE,
                msg.data.getInt(KEY_ID, 0),
            )
        }
    }

    /** Files → engine → speech, reported in plain sentences for the Voice sheet. */
    private fun selfTest(voiceId: String): String {
        val report = StringBuilder()
        report.append("speech to text: ").append(repo.installedStt()?.label ?: "missing").append('\n')
        val espeak = runCatching { repo.ensureEspeakData(assets) }.getOrNull()
        if (espeak == null) {
            report.append("phoneme data: MISSING\n")
        } else {
            val count = espeak.walkTopDown().count { it.isFile }
            val core = listOf("phontab", "phondata", "phonindex", "intonations").all { File(espeak, it).isFile }
            report.append("phoneme data: ").append(count).append(" files, core complete=").append(core).append('\n')
        }
        val option = repo.voice(voiceId)
        if (option == null || !repo.voiceInstalled(option)) {
            report.append("voz (").append(voiceId).append("): sin descargar\n")
            return report.toString()
        }
        report.append("voz: ").append(option.label).append(" · ")
            .append(repo.voiceModelFile(option).length() / 1_048_576).append(" MB\n")
        if (espeak != null) {
            val loaded = engine.load(voiceId, espeak.absolutePath)
            report.append("engine: ").append(if (loaded) "loaded" else "FAILED — " + (engine.lastError ?: "unknown")).append('\n')
            if (loaded) {
                val started = System.currentTimeMillis()
                engine.speak("Voice test, one two three.", voiceId, espeak.absolutePath)
                val ms = System.currentTimeMillis() - started
                report.append("synthesis: ").append(if (engine.lastError == null) "ok in $ms ms" else "FAILED — ${engine.lastError}").append('\n')
            }
        }
        report.append("log:\n").append(engine.logTail(8))
        return report.toString()
    }

    private fun sendState(reply: Messenger?, state: Int, id: Int) {
        if (reply == null) return
        runCatching {
            reply.send(Message.obtain(null, MSG_STATE).apply {
                data = Bundle().apply {
                    putInt(KEY_STATE, state)
                    putInt(KEY_ID, id)
                }
            })
        }
    }

    private fun sendDone(reply: Messenger?, id: Int, ok: Boolean, note: String?) {
        if (reply == null) return
        runCatching {
            reply.send(Message.obtain(null, MSG_DONE).apply {
                data = Bundle().apply {
                    putInt(KEY_ID, id)
                    putBoolean(KEY_OK, ok)
                    putString(KEY_NOTE, note)
                }
            })
        }.onFailure { t: Throwable ->
            if (t is RemoteException) engine.log("client gone before reply")
        }
    }

    override fun onDestroy() {
        engine.log("service stopping")
        runCatching { engine.close() }
        worker.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val MSG_SPEAK = 1
        const val MSG_STOP = 2
        const val MSG_TEST = 3
        const val MSG_STATUS_QUERY = 4
        const val MSG_STATE = 10
        const val MSG_DONE = 11

        const val STATE_IDLE = 0
        const val STATE_SPEAKING = 1

        const val KEY_TEXT = "text"
        const val KEY_VOICE = "voice"
        const val KEY_ESPEAK = "espeak"
        const val KEY_SPEED = "speed"
        const val KEY_ID = "id"
        const val KEY_STATE = "state"
        const val KEY_OK = "ok"
        const val KEY_NOTE = "note"
    }
}
