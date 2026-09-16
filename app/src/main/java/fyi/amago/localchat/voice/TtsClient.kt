package fyi.amago.localchat.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * The chat's side of the speech process: bind, ask it to speak, stop it, and — the reason all of
 * this exists — notice when it dies instead of dying with it.
 *
 * A dead speech process is recoverable: the next request rebinds and Android starts it again. All
 * the chat has to do is say so.
 */
class TtsClient(private val context: Context) {

    sealed interface Status {
        data object Idle : Status
        data object Speaking : Status
        /** The speech process died; [message] explains, and the next request will try again. */
        data class Crashed(val message: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    @Volatile var lastError: String? = null
        private set

    private var messenger: Messenger? = null
    @Volatile private var bound = false
    private var nextId = 1
    private var pending: CompletableDeferred<Pair<Boolean, String?>>? = null
    private var connect: CompletableDeferred<Boolean>? = null

    private val replies = Messenger(Handler(Looper.getMainLooper()) { msg -> onReply(msg); true })

    private val deathRecipient = IBinder.DeathRecipient {
        // Fires even when the process is killed by a native abort, which is exactly our case.
        bound = false
        messenger = null
        crashed("the voice engine stopped unexpectedly (it restarts on the next answer)")
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            messenger = service?.let { Messenger(it) }
            bound = messenger != null
            if (bound) runCatching { service?.linkToDeath(deathRecipient, 0) }
            log("speech process connected")
            connect?.complete(bound)
            connect = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            messenger = null
            crashed("the voice engine stopped unexpectedly")
        }
    }

    private fun crashed(message: String) {
        lastError = message
        _status.value = Status.Crashed(message)
        pending?.complete(false to message)
        pending = null
        connect?.complete(false)
        connect = null
        log("speech process gone: $message")
    }

    private fun onReply(msg: Message) {
        when (msg.what) {
            TtsService.MSG_STATE -> {
                val speaking = msg.data.getInt(TtsService.KEY_STATE) == TtsService.STATE_SPEAKING
                _status.value = if (speaking) Status.Speaking else Status.Idle
            }

            TtsService.MSG_DONE -> {
                val ok = msg.data.getBoolean(TtsService.KEY_OK, false)
                val note = msg.data.getString(TtsService.KEY_NOTE)
                if (!ok) lastError = note ?: "voice engine failed"
                pending?.complete(ok to note)
                pending = null
            }
        }
    }

    /** Binds (or rebinds after a crash), waiting up to [timeoutMs] for the process to answer. */
    private suspend fun ensureConnected(timeoutMs: Long = 2_500): Boolean {
        if (bound && messenger != null) return true
        val waiter = CompletableDeferred<Boolean>()
        connect = waiter
        val intent = Intent(context, TtsService::class.java)
        val started = runCatching { context.bindService(intent, connection, Context.BIND_AUTO_CREATE) }
            .getOrDefault(false)
        if (!started) {
            lastError = "could not start the voice engine"
            return false
        }
        return withTimeoutOrNull(timeoutMs) { waiter.await() } ?: run {
            lastError = "the voice engine did not answer"
            false
        }
    }

    /**
     * Asks the speech process to say [text] and suspends until it has finished, or until the
     * process dies — whichever comes first. Returns false with [lastError] set on any failure.
     */
    suspend fun speak(text: String, lang: String, espeakDir: String, speed: Float = 1.0f): Boolean {
        if (!ensureConnected()) return false
        val target = messenger ?: return false
        val id = nextId++
        val waiter = CompletableDeferred<Pair<Boolean, String?>>()
        pending = waiter
        val ok = runCatching {
            target.send(
                Message.obtain(null, TtsService.MSG_SPEAK).apply {
                    data = Bundle().apply {
                        putInt(TtsService.KEY_ID, id)
                        putString(TtsService.KEY_TEXT, text)
                        putString(TtsService.KEY_LANG, lang)
                        putString(TtsService.KEY_ESPEAK, espeakDir)
                        putFloat(TtsService.KEY_SPEED, speed)
                    }
                    replyTo = replies
                }
            )
            true
        }.getOrElse {
            lastError = "could not reach the voice engine: ${it.message}"
            false
        }
        if (!ok) {
            pending = null
            return false
        }
        val result = withTimeoutOrNull(SPEAK_TIMEOUT_MS) { waiter.await() }
        pending = null
        if (result == null) {
            lastError = "the voice engine did not finish"
            return false
        }
        lastError = if (result.first) null else result.second
        return result.first
    }

    /** Runs the service's self-test and returns its report. Never throws. */
    suspend fun testVoice(lang: String): String {
        if (!ensureConnected()) return lastError ?: "voice engine unavailable"
        val target = messenger ?: return "voice engine unavailable"
        val id = nextId++
        val waiter = CompletableDeferred<Pair<Boolean, String?>>()
        pending = waiter
        val sent = runCatching {
            target.send(
                Message.obtain(null, TtsService.MSG_TEST).apply {
                    data = Bundle().apply {
                        putInt(TtsService.KEY_ID, id)
                        putString(TtsService.KEY_LANG, lang)
                    }
                    replyTo = replies
                }
            )
            true
        }.getOrDefault(false)
        if (!sent) {
            pending = null
            return "could not reach the voice engine"
        }
        val result = withTimeoutOrNull(TEST_TIMEOUT_MS) { waiter.await() }
        pending = null
        return result?.second ?: "the voice engine did not answer"
    }

    fun stop() {
        runCatching {
            messenger?.send(Message.obtain(null, TtsService.MSG_STOP))
        }
        _status.value = Status.Idle
    }

    val isSpeaking: Boolean get() = _status.value is Status.Speaking

    fun close() {
        runCatching { context.unbindService(connection) }
        bound = false
        messenger = null
    }

    /** The log lives in the app's files dir, so both processes append to one file. */
    private fun log(line: String) {
        runCatching {
            val root = File(context.getExternalFilesDir(null), "voice").apply { mkdirs() }
            val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            File(root, "voice.log").appendText("$stamp  [app] $line\n")
        }
    }

    companion object {
        private const val SPEAK_TIMEOUT_MS = 180_000L
        private const val TEST_TIMEOUT_MS = 120_000L
    }
}
