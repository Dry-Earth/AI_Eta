package io.github.mangi.eta.agent.voice

import android.content.Context
import io.github.mangi.eta.data.model.LocalWhisperConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * whisper.cpp 的 JNI 桥。库在构建期由 CMake 从固定标签的 whisper.cpp 源码编译，
 * 只提供加载、转写、释放三个入口，避免把线程与生命周期管理散到 C++ 里。
 */
internal object WhisperBridge {
    init {
        System.loadLibrary("eta_whisper")
    }

    external fun nativeInit(modelPath: String, threads: Int): Long

    external fun nativeTranscribe(handle: Long, pcm: ByteArray, language: String): String

    external fun nativeRelease(handle: Long)
}

/**
 * 本地语音识别：模型默认随 APK 内置（`assets/whisper/ggml-small-q5_1.bin`），
 * 也可以在设置里填自己的 ggml 模型路径替换。音频不出设备。
 *
 * 模型只在首次使用时从 assets 复制到私有目录（whisper 需要真实文件路径），
 * 之后按文件大小判断是否已就绪；自定义模型直接读原路径，不复制。
 */
internal object LocalWhisperRecognizer {
    const val ASSET_MODEL = "whisper/ggml-small-q5_1.bin"
    private const val MODEL_DIR = "whisper"
    private const val MODEL_NAME = "ggml-small-q5_1.bin"
    private const val MIN_MODEL_BYTES = 1_000_000L

    private val mutex = Mutex()
    private var handle = 0L
    private var loadedPath = ""

    suspend fun transcribe(
        context: Context,
        config: LocalWhisperConfig,
        wav: ByteArray,
        language: String,
        onProgress: (String) -> Unit,
    ): String = mutex.withLock {
        withContext(Dispatchers.Default) {
            onProgress("正在准备本地模型")
            val model = resolveModel(context, config)
            if (handle == 0L || loadedPath != model.absolutePath) {
                if (handle != 0L) WhisperBridge.nativeRelease(handle)
                handle = WhisperBridge.nativeInit(model.absolutePath, threads(config))
                loadedPath = model.absolutePath
                if (handle == 0L) {
                    throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "本地 Whisper 模型加载失败，请检查模型文件")
                }
            }
            onProgress("正在本地识别")
            val pcm = extractPcm(wav)
            if (pcm.size < 3_200) throw SpeechFailure(SpeechErrorCode.NO_SPEECH, "录音太短，请重试")
            try {
                WhisperBridge.nativeTranscribe(handle, pcm, normalizeLanguage(language)).trim()
            } catch (error: UnsatisfiedLinkError) {
                throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "本地识别库不可用，请安装包含本地识别的版本")
            }
        }
    }

    /** 自定义模型优先；否则使用随包内置的模型，首次使用时复制到私有目录。 */
    private fun resolveModel(context: Context, config: LocalWhisperConfig): File {
        val custom = config.modelPath.trim()
        if (custom.isNotEmpty()) {
            val file = File(custom.removePrefix("file://"))
            if (!file.isFile || !file.canRead()) {
                throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "自定义模型文件不存在或不可读")
            }
            if (file.length() < MIN_MODEL_BYTES) {
                throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "自定义模型文件不完整")
            }
            return file
        }
        val target = File(File(context.filesDir, MODEL_DIR), MODEL_NAME)
        val assetLength = runCatching {
            context.assets.openFd(ASSET_MODEL).use { it.length }
        }.getOrNull() ?: throw SpeechFailure(
            SpeechErrorCode.CONFIGURATION,
            "当前安装包未内置本地模型，请在设置中填写 ggml 模型文件路径",
        )
        if (target.isFile && target.length() == assetLength && assetLength >= MIN_MODEL_BYTES) return target
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "$MODEL_NAME.tmp")
        runCatching {
            context.assets.open(ASSET_MODEL).use { input ->
                temporary.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
            }
            if (temporary.length() != assetLength) error("模型复制不完整")
            if (target.exists()) target.delete()
            if (!temporary.renameTo(target)) error("模型文件替换失败")
        }.onFailure {
            temporary.delete()
            throw SpeechFailure(SpeechErrorCode.STORAGE, "无法准备本地模型，请检查存储空间")
        }
        return target
    }

    private fun threads(config: LocalWhisperConfig): Int =
        config.threads.coerceIn(1, 8).let { configured ->
            if (config.threads > 0) configured
            else Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        }

    /** 只保留 whisper 认识的两位/三位语言代码，其余交给自动识别。 */
    private fun normalizeLanguage(value: String): String {
        val tag = value.trim().lowercase().substringBefore('-').substringBefore('_')
        return if (Regex("[a-z]{2,3}").matches(tag)) tag else ""
    }

    /** 从 WAV 中取出 PCM16；已是裸 PCM 时原样返回。 */
    private fun extractPcm(wav: ByteArray): ByteArray {
        if (wav.size <= 44) return ByteArray(0)
        if (String(wav, 0, 4, Charsets.US_ASCII) != "RIFF") return wav
        var offset = 12
        while (offset + 8 <= wav.size) {
            val id = String(wav, offset, 4, Charsets.US_ASCII)
            val size = (wav[offset + 4].toInt() and 0xFF) or
                ((wav[offset + 5].toInt() and 0xFF) shl 8) or
                ((wav[offset + 6].toInt() and 0xFF) shl 16) or
                ((wav[offset + 7].toInt() and 0xFF) shl 24)
            val start = offset + 8
            if (id == "data") {
                if (size <= 0 || start >= wav.size) break
                return wav.copyOfRange(start, minOf(wav.size, start + size))
            }
            if (size < 0) break
            offset = start + size + (size and 1)
        }
        return wav.copyOfRange(44, wav.size)
    }
}
