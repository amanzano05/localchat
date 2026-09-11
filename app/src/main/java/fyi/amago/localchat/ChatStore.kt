package fyi.amago.localchat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Chat history on disk: a list of conversations plus which one is open.
 * One JSON file in the app's private storage; writes are atomic (tmp + rename).
 * Reads the old flat-array format (0.2/0.3) and migrates it transparently.
 */
class ChatStore(context: Context) {

    data class StoredConversation(
        val id: String,
        val title: String,
        val createdAt: Long,
        val updatedAt: Long,
        val messages: List<ChatMessage>,
    )

    private val file = File(context.filesDir, "chat-history.json")

    fun load(): Pair<List<StoredConversation>, String?> {
        if (!file.isFile) return emptyList<StoredConversation>() to null
        val raw = runCatching { file.readText() }.getOrNull() ?: return emptyList<StoredConversation>() to null
        if (raw.isBlank()) return emptyList<StoredConversation>() to null

        // Legacy format: a bare JSON array of messages.
        if (raw.trimStart().startsWith("[")) {
            val msgs = parseMessages(runCatching { JSONArray(raw) }.getOrNull() ?: JSONArray())
            if (msgs.isEmpty()) return emptyList<StoredConversation>() to null
            val id = "migrated-${msgs.first().timeMs}"
            return listOf(
                StoredConversation(id, titleFor(msgs), msgs.first().timeMs, msgs.last().timeMs, msgs)
            ) to id
        }

        return runCatching {
            val root = JSONObject(raw)
            val arr = root.optJSONArray("conversations") ?: JSONArray()
            val convs = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val msgs = parseMessages(o.optJSONArray("messages") ?: JSONArray())
                if (msgs.isEmpty()) null else StoredConversation(
                    id = o.optString("id").ifBlank { "conv-${i}" },
                    title = o.optString("title").ifBlank { titleFor(msgs) },
                    createdAt = o.optLong("createdAt", msgs.first().timeMs),
                    updatedAt = o.optLong("updatedAt", msgs.last().timeMs),
                    messages = msgs,
                )
            }
            val current = root.optString("current").ifBlank { null }
            convs to current
        }.getOrDefault(emptyList<StoredConversation>() to null)
    }

    fun save(conversations: List<StoredConversation>, currentId: String?) {
        runCatching {
            val arr = JSONArray()
            conversations.forEach { c ->
                val msgs = JSONArray()
                c.messages.filter { it.text.isNotBlank() }.forEach { m ->
                    msgs.put(
                        JSONObject()
                            .put("id", m.id)
                            .put("fromUser", m.fromUser)
                            .put("text", m.text)
                            .put("timeMs", m.timeMs)
                    )
                }
                arr.put(
                    JSONObject()
                        .put("id", c.id)
                        .put("title", c.title)
                        .put("createdAt", c.createdAt)
                        .put("updatedAt", c.updatedAt)
                        .put("messages", msgs)
                )
            }
            val root = JSONObject().put("conversations", arr)
            if (currentId != null) root.put("current", currentId)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(root.toString())
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
    }

    private fun parseMessages(arr: JSONArray): List<ChatMessage> =
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

    private fun titleFor(messages: List<ChatMessage>): String {
        val first = messages.firstOrNull { it.fromUser }?.text ?: messages.first().text
        val clean = first.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        return if (clean.length <= 42) clean else clean.take(42).trimEnd() + "…"
    }
}
