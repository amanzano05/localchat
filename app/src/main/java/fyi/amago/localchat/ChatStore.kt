package fyi.amago.localchat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Dead-simple, dependency-free chat history: one JSON file in the app's private storage.
 * Writes are atomic (tmp file + rename) so a kill during save can't corrupt the history.
 */
class ChatStore(context: Context) {

    private val file = File(context.filesDir, "chat-history.json")

    fun load(): List<ChatMessage> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val text = o.optString("text")
                if (text.isBlank()) null
                else ChatMessage(
                    id = o.optLong("id", i.toLong()),
                    fromUser = o.optBoolean("fromUser", false),
                    text = text,
                    timeMs = o.optLong("timeMs", 0L),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(messages: List<ChatMessage>) {
        runCatching {
            val arr = JSONArray()
            messages.filter { it.text.isNotBlank() }.forEach { m ->
                arr.put(
                    JSONObject()
                        .put("id", m.id)
                        .put("fromUser", m.fromUser)
                        .put("text", m.text)
                        .put("timeMs", m.timeMs)
                )
            }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(arr.toString())
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
    }

    fun clear() {
        runCatching { file.delete() }
    }
}
