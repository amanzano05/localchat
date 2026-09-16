package fyi.amago.localchat.voice

import android.content.Context
import android.content.res.AssetManager
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * What has to be on the phone for voice, where it comes from, and how it gets there.
 *
 * Everything is Apache-2.0 / MIT (sherpa-onnx, Whisper, Piper/VITS, espeak-ng) and everything is a
 * *plain file* download — no archives to unpack, so the app needs no decompression code and no new
 * dependency beyond the sherpa-onnx AAR itself.
 *
 * Layout, under the app's external files dir so `adb push` works too:
 *
 * ```
 * voice/
 *   stt/  tiny-encoder.int8.onnx  tiny-decoder.int8.onnx  tiny-tokens.txt
 *   tts/en/  en_US-amy-low.onnx  tokens.txt
 *   tts/es/  es_ES-sharvard-medium.onnx  tokens.txt
 *   espeak-ng-data/            (copied out of assets on first use)
 * ```
 */
class VoiceRepository(private val context: Context) {

    val root: File get() = File(context.getExternalFilesDir(null), "voice").apply { mkdirs() }
    val sttDir: File get() = File(root, "stt").apply { mkdirs() }
    fun ttsDir(lang: String): File = File(root, "tts/$lang").apply { mkdirs() }
    val espeakDir: File get() = File(root, "espeak-ng-data")

    // --- catalog ---------------------------------------------------------------------------

    /** Multilingual Whisper tiny, int8: ~99 MB and good enough to know what you said. */
    object WhisperTiny : ModelSet {
        override val id = "whisper-tiny"
        override val label = "Whisper tiny (99 MB)"
        override val files = listOf(
            RemoteFile("tiny-encoder.int8.onnx",
                "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-encoder.int8.onnx"),
            RemoteFile("tiny-decoder.int8.onnx",
                "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-decoder.int8.onnx"),
            RemoteFile("tiny-tokens.txt",
                "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-tokens.txt"),
        )
    }

    /** Whisper base, int8: ~153 MB, noticeably better with names and accents. */
    object WhisperBase : ModelSet {
        override val id = "whisper-base"
        override val label = "Whisper base (153 MB)"
        override val files = listOf(
            RemoteFile("base-encoder.int8.onnx",
                "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/main/base-encoder.int8.onnx"),
            RemoteFile("base-decoder.int8.onnx",
                "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/main/base-decoder.int8.onnx"),
            RemoteFile("base-tokens.txt",
                "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/main/base-tokens.txt"),
        )
    }

    /** Piper voices, one per language. Apache-2.0 models, MIT tooling. */
    object VoiceEn : ModelSet {
        override val id = "tts-en"
        override val label = "English voice (60 MB)"
        override val files = listOf(
            RemoteFile("en_US-amy-low.onnx",
                "https://huggingface.co/csukuangfj/vits-piper-en_US-amy-low/resolve/main/en_US-amy-low.onnx"),
            RemoteFile("tokens.txt",
                "https://huggingface.co/csukuangfj/vits-piper-en_US-amy-low/resolve/main/tokens.txt"),
        )
    }

    object VoiceEs : ModelSet {
        override val id = "tts-es"
        override val label = "Voz en español (73 MB)"
        override val files = listOf(
            RemoteFile("es_ES-sharvard-medium.onnx",
                "https://huggingface.co/csukuangfj/vits-piper-es_ES-sharvard-medium/resolve/main/es_ES-sharvard-medium.onnx"),
            RemoteFile("tokens.txt",
                "https://huggingface.co/csukuangfj/vits-piper-es_ES-sharvard-medium/resolve/main/tokens.txt"),
        )
    }

    interface ModelSet {
        val id: String
        val label: String
        val files: List<RemoteFile>
        val bytes: Long get() = 0L
    }

    data class RemoteFile(val name: String, val url: String)

    // --- state -----------------------------------------------------------------------------

    /** The Whisper set that is actually on disk, or null. */
    fun installedStt(): ModelSet? =
        listOf(WhisperTiny, WhisperBase).firstOrNull { set ->
            set.files.all { File(sttDir, it.name).isFile }
        }

    fun sttInstalled(): Boolean = installedStt() != null

    fun whisperEncoder(set: ModelSet): File =
        File(sttDir, set.files.first { it.name.contains("encoder") }.name)

    fun whisperDecoder(set: ModelSet): File =
        File(sttDir, set.files.first { it.name.contains("decoder") }.name)

    fun whisperTokens(set: ModelSet): File =
        File(sttDir, set.files.first { it.name.endsWith(".txt") }.name)

    fun ttsFiles(lang: String): List<RemoteFile> = if (lang == "es") VoiceEs.files else VoiceEn.files

    fun ttsInstalled(lang: String): Boolean = ttsFiles(lang).all { File(ttsDir(lang), it.name).isFile }

    fun ttsModelFile(lang: String): File =
        File(ttsDir(lang), ttsFiles(lang).first { it.name.endsWith(".onnx") }.name)

    fun ttsTokensFile(lang: String): File = File(ttsDir(lang), "tokens.txt")

    /** espeak-ng phoneme data for Piper, copied out of assets exactly once. */
    fun ensureEspeakData(assets: AssetManager): File {
        val marker = File(root, ".espeak-ok")
        if (marker.isFile && espeakDir.isDirectory) return espeakDir
        copyAssetDir(assets, "espeak-ng-data", espeakDir)
        marker.writeText("ok")
        return espeakDir
    }

    // --- download --------------------------------------------------------------------------

    /**
     * Downloads [files] into [dest]. Sequential on purpose: one connection, honest progress, and a
     * phone on LTE does not benefit from four parallel 60 MB streams.
     */
    fun download(
        files: List<RemoteFile>,
        dest: File,
        onProgress: (done: Long, total: Long, label: String) -> Unit,
    ) {
        dest.mkdirs()
        var doneTotal = 0L
        for (f in files) {
            val target = File(dest, f.name)
            if (target.isFile && target.length() > 0) {
                doneTotal += target.length()
                onProgress(doneTotal, 0, f.name)
                continue
            }
            val part = File(dest, f.name + ".part")
            part.delete()
            val conn = (URL(f.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "LocalChat/1.0 (Android)")
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) throw IllegalStateException("Server returned HTTP $code for ${f.name}")
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    FileOutputStream(part).use { out ->
                        val buf = ByteArray(1 shl 16)
                        var readInFile = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            readInFile += n
                            onProgress(doneTotal + readInFile, (total.takeIf { it > 0 } ?: 0L), f.name)
                        }
                        out.flush()
                    }
                }
            } finally {
                conn.disconnect()
            }
            if (part.length() <= 0) throw IllegalStateException("${f.name} came back empty")
            target.delete()
            if (!part.renameTo(target)) throw IllegalStateException("Could not finalize ${f.name}")
            doneTotal += target.length()
            onProgress(doneTotal, 0L, f.name)
        }
    }

    private fun copyAssetDir(assets: AssetManager, from: String, to: File) {
        val children = assets.list(from) ?: return
        if (children.isEmpty()) {
            // a file, not a directory
            to.parentFile?.mkdirs()
            assets.open(from).use { input -> FileOutputStream(to).use { input.copyTo(it) } }
            return
        }
        to.mkdirs()
        for (child in children) copyAssetDir(assets, "$from/$child", File(to, child))
    }
}
