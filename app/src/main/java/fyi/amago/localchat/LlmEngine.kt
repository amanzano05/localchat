package fyi.amago.localchat

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * Thin wrapper around a LiteRT-LM [Engine] + [Conversation].
 * Everything runs on-device; nothing leaves the phone.
 */
class LlmEngine {

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    val isLoaded: Boolean get() = conversation != null

    /** Loads the model. Tries GPU first (falls back to CPU) and returns the backend actually used. */
    suspend fun load(modelPath: String, cacheDir: String, preferGpu: Boolean = true): String =
        withContext(Dispatchers.IO) {
            close()
            Engine.setNativeMinLogSeverity(LogSeverity.ERROR)

            val attempts = if (preferGpu) listOf(true, false) else listOf(false)
            var lastError: Throwable? = null

            for (useGpu in attempts) {
                try {
                    val cfg = EngineConfig(
                        modelPath = modelPath,
                        backend = if (useGpu) Backend.GPU() else Backend.CPU(),
                        cacheDir = cacheDir,
                    )
                    val e = Engine(cfg)
                    e.initialize()
                    val c = e.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of(SYSTEM_PROMPT),
                            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 1.0, seed = 0),
                        )
                    )
                    engine = e
                    conversation = c
                    return@withContext if (useGpu) "GPU" else "CPU"
                } catch (t: Throwable) {
                    lastError = t
                    runCatching { close() }
                }
            }
            throw (lastError ?: IllegalStateException("The model could not be loaded."))
        }

    /** Streams the whole answer; each emission is the full text so far. */
    fun send(text: String): Flow<String> = flow {
        val c = conversation ?: throw IllegalStateException("No model loaded")
        var acc = ""
        c.sendMessageAsync(text).collect { msg ->
            val chunk = msg.contents.contents
                .filterIsInstance<Content.Text>()
                .joinToString("") { it.text }
            if (chunk.isEmpty()) return@collect
            // Works whether the backend streams deltas or cumulative text.
            acc = if (chunk.length >= acc.length && chunk.startsWith(acc)) chunk else acc + chunk
            emit(acc)
        }
    }

    fun cancel() {
        runCatching { conversation?.cancelProcess() }
    }

    fun close() {
        runCatching { conversation?.close() }
        runCatching { engine?.close() }
        conversation = null
        engine = null
    }

    companion object {
        const val SYSTEM_PROMPT =
            "You are a concise, helpful assistant running entirely on the user's phone. " +
                "Answer directly and keep answers short unless asked for detail."
    }
}
