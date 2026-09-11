package fyi.amago.localchat

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Model files live in the app's external files dir, so adb push also works without root. */
class ModelRepository(private val context: Context) {

    val modelsDir: File
        get() = File(context.getExternalFilesDir(null), "models").apply { mkdirs() }

    fun installedModels(): List<File> =
        modelsDir.listFiles { f -> f.isFile && f.name.endsWith(MODEL_EXTENSION) }
            ?.sortedBy { it.name }
            ?.toList()
            ?: emptyList()

    fun modelFile(name: String): File = File(modelsDir, name)

    fun isInstalled(name: String): Boolean = modelFile(name).let { it.isFile && it.length() > MIN_VALID_SIZE }

    /**
     * Downloads [url] into the models dir. Reports (bytesRead, totalBytes) as it goes.
     * Failures leave the partial file as *.part so a retry starts clean.
     */
    fun download(
        url: String,
        fileName: String,
        onProgress: (Long, Long) -> Unit,
    ): File {
        val target = modelFile(fileName)
        val part = File(modelsDir, "$fileName.part")
        if (part.exists()) part.delete()

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "LocalChat/1.0 (Android)")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("Server returned HTTP $code")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var read: Long = 0
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        read += n
                        onProgress(read, total)
                    }
                    out.flush()
                }
            }
        } catch (t: Throwable) {
            conn.disconnect()
            throw t
        } finally {
            conn.disconnect()
        }
        if (part.length() < MIN_VALID_SIZE) throw IllegalStateException("Downloaded file looks incomplete")
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) throw IllegalStateException("Could not finalize the download")
        return target
    }

    /** Copies a user-picked .litertlm (e.g. exported from Google AI Edge Gallery) into the models dir. */
    fun importFromUri(uri: Uri, fileName: String): File {
        val target = modelFile(fileName)
        val part = File(modelsDir, "$fileName.part")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Could not open the selected file" }
            FileOutputStream(part).use { out -> input.copyTo(out) }
        }
        if (part.length() < MIN_VALID_SIZE) {
            part.delete()
            throw IllegalStateException("That file does not look like a .litertlm model")
        }
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) throw IllegalStateException("Could not import the file")
        return target
    }

    companion object {
        const val MODEL_EXTENSION = ".litertlm"
        const val DEFAULT_MODEL_NAME = "gemma-4-E2B-it-gpu.litertlm"
        const val DEFAULT_MODEL_URL =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it-gpu.litertlm"
        const val DEFAULT_MODEL_SIZE_HINT = "1.9 GB"
        private const val MIN_VALID_SIZE = 50L * 1024 * 1024
    }
}
