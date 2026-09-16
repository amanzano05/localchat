package fyi.amago.localchat.voice

import android.content.Context
import android.content.res.AssetManager
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

// Kokoro ships one model for every language it speaks: the model and the voices are shared, and the
// language is chosen by speaker id inside voices.bin. The Spanish id is not documented for this
// build — it was found by synthesising with all 103 voices and transcribing each one with Whisper,
// which is the only honest way to know which one actually speaks Spanish.
private const val KOKORO_BASE = "https://huggingface.co/csukuangfj/kokoro-int8-multi-lang-v1_1/resolve/main"
private const val KOKORO_LEXICON = "lexicon-us-en.txt"
private const val KOKORO_EN_SPEAKER = 1      // af_bella, verified by ear on the phone
private const val KOKORO_ES_SPEAKER = 28     // ef_dora, from the official sid table (29 = em_alex, male)

/**
 * What has to be on the phone for voice, where it comes from, and how it gets there.
 *
 * Everything is Apache-2.0 / MIT (sherpa-onnx, Whisper, Piper/VITS, Silero VAD, espeak-ng) and
 * everything is a *plain file* download — no archives to unpack, so the app needs no decompression
 * code and no new dependency beyond the sherpa-onnx AAR itself.
 *
 * Layout, under the app's external files dir so `adb push` works too:
 *
 * ```
 * voice/
 *   stt/  tiny-encoder.int8.onnx  tiny-decoder.int8.onnx  tiny-tokens.txt
 *   tts/<voiceId>/  <model>.onnx  tokens.txt        one folder per voice you can pick
 *   vad/  silero_vad.onnx
 *   espeak-ng-data/                                  (copied out of assets on first use)
 * ```
 */
class VoiceRepository(private val context: Context) {

    val root: File get() = File(context.getExternalFilesDir(null), "voice").apply { mkdirs() }
    val sttDir: File get() = File(root, "stt").apply { mkdirs() }
    val vadDir: File get() = File(root, "vad").apply { mkdirs() }
    fun ttsDir(voiceId: String): File = File(root, "tts/$voiceId").apply { mkdirs() }
    val espeakDir: File get() = File(root, "espeak-ng-data")

    // --- catalog ---------------------------------------------------------------------------

    data class RemoteFile(val name: String, val url: String)

    interface ModelSet {
        val id: String
        val label: String
        val files: List<RemoteFile>
    }

    /**
     * A voice you can pick. `id` doubles as the folder name under `tts/`; the first two ids are the
     * historical "en" and "es" folders, so an existing install keeps working without a re-download.
     */
    data class VoiceOption(
        val id: String,
        val lang: String,
        val label: String,
        val note: String,
        val modelFile: String,
        val urlBase: String,
        val megabytes: Int,
        /** "vits" (Piper) or "kokoro": which loader the speech process builds. */
        val kind: String = "vits",
        /** Kokoro picks a voice by number inside voices.bin; Piper ignores it. */
        val speakerId: Int = 0,
        /** Folder under tts/. Two options can share one, so a shared model downloads once. */
        val dir: String = id,
        /** What a fresh install hears, and what the sheet preselects. */
        val isDefault: Boolean = false,
    ) {
        val files: List<RemoteFile>
            get() = when (kind) {
                "kokoro" -> listOf(
                    RemoteFile(modelFile, "$urlBase/$modelFile"),
                    RemoteFile("voices.bin", "$urlBase/voices.bin"),
                    RemoteFile("tokens.txt", "$urlBase/tokens.txt"),
                    RemoteFile(KOKORO_LEXICON, "$urlBase/$KOKORO_LEXICON"),
                )
                else -> listOf(
                    RemoteFile(modelFile, "$urlBase/$modelFile"),
                    RemoteFile("tokens.txt", "$urlBase/tokens.txt"),
                )
            }
    }

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

    private val hf = "https://huggingface.co/csukuangfj/"

    /**
     * Piper voices. Tiers are named for quality, not size: `low` is the fastest and thinnest,
     * `medium` is clear, `high` is the most natural and the biggest.
     */
    val voices: List<VoiceOption> = listOf(
        VoiceOption("en", "en", "Amy · rápida", "la de siempre, la más liviana",
            "en_US-amy-low.onnx", hf + "vits-piper-en_US-amy-low/resolve/main", 60),
        VoiceOption("en-amy-med", "en", "Amy · media", "más clara, mismo peso",
            "en_US-amy-medium.onnx", hf + "vits-piper-en_US-amy-medium/resolve/main", 64),
        VoiceOption("en-lessac-hi", "en", "Lessac · alta", "la más natural en inglés",
            "en_US-lessac-high.onnx", hf + "vits-piper-en_US-lessac-high/resolve/main", 110),
        VoiceOption("es", "es", "Sharvard · media", "la de siempre, castellano",
            "es_ES-sharvard-medium.onnx", hf + "vits-piper-es_ES-sharvard-medium/resolve/main", 77),
        VoiceOption("es-mx-claude", "es", "Claude · alta (México)", "español mexicano · el elegido",
            "es_MX-claude-high.onnx", hf + "vits-piper-es_MX-claude-high/resolve/main", 64, isDefault = true),
        VoiceOption("es-davefx", "es", "Davefx · media", "voz masculina, castellano",
            "es_ES-davefx-medium.onnx", hf + "vits-piper-es_ES-davefx-medium/resolve/main", 64),
        VoiceOption("kokoro-en", "en", "Kokoro · natural", "la más humana · el elegido en inglés",
            "model.int8.onnx", KOKORO_BASE, 166, kind = "kokoro", speakerId = KOKORO_EN_SPEAKER,
            dir = "kokoro", isDefault = true),
        VoiceOption("kokoro-es", "es", "Kokoro · natural (latino)", "la más humana en español",
            "model.int8.onnx", KOKORO_BASE, 166, kind = "kokoro", speakerId = KOKORO_ES_SPEAKER, dir = "kokoro"),
    )

    fun voicesFor(lang: String): List<VoiceOption> = voices.filter { it.lang == lang }

    /** Chooses the entry for a language when two options share an id's model but not its voice. */
    fun voice(id: String, lang: String? = null): VoiceOption? =
        voices.firstOrNull { it.id == id && (lang == null || it.lang == lang) }
            ?: voices.firstOrNull { it.id == id }

    /** The voice a language starts on: the chosen default, or whatever is first in the list. */
    fun defaultVoice(lang: String): VoiceOption =
        voicesFor(lang).firstOrNull { it.isDefault } ?: voicesFor(lang).first()

    /** Silero VAD, ~2 MB: the thing that hears you stop talking. */
    val vadFile = RemoteFile("silero_vad.onnx",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx")

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

    fun voiceInstalled(option: VoiceOption): Boolean =
        option.files.all { File(ttsDir(option.dir), it.name).isFile }

    fun voiceModelFile(option: VoiceOption): File = File(ttsDir(option.dir), option.modelFile)

    fun voiceTokensFile(option: VoiceOption): File = File(ttsDir(option.dir), "tokens.txt")

    fun voiceVoicesFile(option: VoiceOption): File = File(ttsDir(option.dir), "voices.bin")

    fun voiceLexiconFile(option: VoiceOption): File = File(ttsDir(option.dir), KOKORO_LEXICON)

    fun anyVoiceInstalled(lang: String): Boolean = voicesFor(lang).any { voiceInstalled(it) }

    fun vadInstalled(): Boolean = File(vadDir, vadFile.name).isFile

    fun vadModel(): File = File(vadDir, vadFile.name)

    /** espeak-ng phoneme data for Piper, copied out of assets exactly once. */
    fun ensureEspeakData(assets: AssetManager): File {
        val marker = File(root, ".espeak-ok")
        if (marker.isFile && espeakDir.isDirectory) return espeakDir
        copyAssetDir(assets, "espeak-ng-data", espeakDir)
        marker.writeText("ok")
        return espeakDir
    }

    /**
     * Repairs an install made by 0.8.x–0.9.1: Kokoro was downloaded into `tts/kokoro-en` while the
     * app looked for it in the shared `tts/kokoro`, so the files were there and the app still said
     * "not downloaded". Moving them costs a second; re-downloading costs the user 166 MB.
     */
    fun migrateVoiceFolders() {
        for (strayName in listOf("kokoro-en", "kokoro-es")) {
            val stray = File(root, "tts/$strayName")
            if (!stray.isDirectory) continue
            val target = ttsDir("kokoro")
            stray.listFiles()?.forEach { file ->
                val dest = File(target, file.name)
                if (dest.exists()) {
                    file.delete()
                } else if (!file.renameTo(dest)) {
                    file.copyTo(dest, overwrite = true)
                    file.delete()
                }
            }
            if (stray.listFiles().isNullOrEmpty()) stray.delete()
        }
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
