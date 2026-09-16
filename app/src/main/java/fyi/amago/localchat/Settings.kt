package fyi.amago.localchat

import android.content.Context
import java.io.File

/**
 * The settings that are not about voice: today, the instructions the model gets on every
 * conversation — persona, intent, tone, language, rules.
 *
 * Stored as a file rather than a preference string because it is meant to be written like prose
 * (several paragraphs if the user wants), and because a file can be pushed with adb while a
 * preference cannot.
 */
class Settings(private val context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val promptFile: File get() = File(context.filesDir, "system_prompt.txt")

    /** What the user wrote, or "" when they have not written anything (or cleared it). */
    fun systemPrompt(): String =
        runCatching { if (promptFile.isFile) promptFile.readText().trim() else "" }.getOrDefault("")

    /** An empty prompt means "forget mine", not "send an empty instruction". */
    fun setSystemPrompt(text: String) {
        runCatching {
            if (text.isBlank()) promptFile.delete() else promptFile.writeText(text.trim())
        }
    }

    fun hasCustomPrompt(): Boolean = systemPrompt().isNotBlank()

    /** Last model file the user opened, so the app can come back to it. */
    var lastModel: String
        get() = prefs.getString(KEY_MODEL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    companion object {
        private const val KEY_MODEL = "last_model"

        /** Shown as the placeholder: it is what the app sends when the box is empty. */
        const val DEFAULT_PROMPT =
            "You are a concise, helpful assistant running entirely on the user's phone. " +
                "Answer directly and keep answers short unless asked for detail."

        /** Starting points, one tap away. Editable like any other text once inserted. */
        val EXAMPLES = listOf(
            "Responde siempre en español, breve y directo. Si no sabes algo, dilo.",
            "Eres un tutor paciente para un niño de 10 años: explica con ejemplos y sin tecnicismos.",
            "Actúa como revisor de código: señala primero los errores, luego las mejoras, y al final un ejemplo corregido.",
            "Responde en español de México, tono cálido y breve: sin listas ni formato.",
        )
    }
}
