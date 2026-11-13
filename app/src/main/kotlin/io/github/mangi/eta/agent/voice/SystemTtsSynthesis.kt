package io.github.mangi.eta.agent.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 系统内置语音合成（TextToSpeech）的两种用法：
 *
 * - [synthesizeSystemSpeech]：`synthesizeToFile` 生成 WAV，转成 24 kHz 单声道 PCM16，
 *   交回 Eta 的播放链路，保留分段/淡入淡出/前台服务/音频焦点等控制；
 * - [speakWithSystemEngine]：直接把文字交给引擎自己播（直出），延迟最低、兼容性最好，
 *   但 Eta 只能整体停止，无法做分段控制。
 *
 * LineageOS 默认不带合成引擎，需用户自行安装（例如 CloneTTS Engine）。
 */
internal suspend fun synthesizeSystemSpeech(
    context: Context,
    text: String,
    onAudio: (ByteArray) -> Unit,
) {
    if (text.isBlank()) throw SpeechFailure(SpeechErrorCode.NO_SPEECH, "这条消息没有可朗读的正文")
    val engine = createSystemEngine(context)
    val file = runCatching { File.createTempFile("eta-system-tts-", ".wav", context.cacheDir) }.getOrElse {
        runCatching { engine.shutdown() }
        throw SpeechFailure(SpeechErrorCode.STORAGE, "无法创建临时音频文件")
    }
    try {
        ensureLanguage(engine)
        engine.setSpeechRate(1.0f)
        synthesizeToFile(engine, text, file)
        val pcm = toPcm24kMono(file.readBytes())
        if (pcm.isEmpty()) throw SpeechFailure(SpeechErrorCode.PROTOCOL, "系统语音合成没有返回音频")
        var offset = 0
        while (offset < pcm.size) {
            val count = min(32 * 1024, pcm.size - offset)
            onAudio(pcm.copyOfRange(offset, offset + count))
            offset += count
        }
    } finally {
        runCatching { engine.shutdown() }
        file.delete()
    }
}

private var directEngine: TextToSpeech? = null

/** 直出模式：引擎自己播放，挂起直到说完或出错。 */
internal suspend fun speakWithSystemEngine(context: Context, text: String) {
    if (text.isBlank()) throw SpeechFailure(SpeechErrorCode.NO_SPEECH, "这条消息没有可朗读的正文")
    val engine = createSystemEngine(context)
    directEngine = engine
    try {
        ensureLanguage(engine)
        engine.setSpeechRate(1.0f)
        awaitSpeak(engine, text)
    } finally {
        if (directEngine === engine) directEngine = null
        runCatching { engine.shutdown() }
    }
}

internal fun stopSystemSpeech() {
    runCatching { directEngine?.stop() }
}

private fun ensureLanguage(engine: TextToSpeech) {
    val result = engine.setLanguage(Locale.getDefault())
    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
        throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "系统合成引擎不支持当前语言")
    }
}

private suspend fun createSystemEngine(context: Context): TextToSpeech =
    suspendCancellableCoroutine { continuation ->
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context.applicationContext) { status ->
            val created = engine
            if (!continuation.isActive) {
                runCatching { created?.shutdown() }
                return@TextToSpeech
            }
            if (status == TextToSpeech.SUCCESS && created != null) {
                continuation.resume(created)
            } else {
                runCatching { created?.shutdown() }
                continuation.resumeWithException(
                    SpeechFailure(SpeechErrorCode.CONFIGURATION, "系统未安装可用的语音合成引擎，请先安装 TTS 引擎"),
                )
            }
        }
        continuation.invokeOnCancellation { runCatching { engine?.shutdown() } }
    }

private fun progressListener(
    onDone: () -> Unit,
    onError: (Int) -> Unit,
): UtteranceProgressListener = object : UtteranceProgressListener() {
    override fun onStart(id: String?) = Unit
    override fun onDone(id: String?) = onDone()

    @Deprecated("Deprecated in Java")
    override fun onError(id: String?) = onError(-1)

    override fun onError(id: String?, errorCode: Int) = onError(errorCode)
}

private suspend fun synthesizeToFile(engine: TextToSpeech, text: String, file: File) =
    suspendCancellableCoroutine { continuation ->
        val utteranceId = UUID.randomUUID().toString()
        engine.setOnUtteranceProgressListener(progressListener(
            onDone = { if (continuation.isActive) continuation.resume(Unit) },
            onError = { code ->
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        SpeechFailure(SpeechErrorCode.SERVER, "系统语音合成失败（$code）"),
                    )
                }
            },
        ))
        val result = engine.synthesizeToFile(text, Bundle(), file, utteranceId)
        if (result != TextToSpeech.SUCCESS && continuation.isActive) {
            continuation.resumeWithException(SpeechFailure(SpeechErrorCode.SERVER, "系统语音合成启动失败"))
        }
        continuation.invokeOnCancellation { runCatching { engine.stop() } }
    }

private suspend fun awaitSpeak(engine: TextToSpeech, text: String) =
    suspendCancellableCoroutine { continuation ->
        val utteranceId = UUID.randomUUID().toString()
        engine.setOnUtteranceProgressListener(progressListener(
            onDone = { if (continuation.isActive) continuation.resume(Unit) },
            onError = { code ->
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        SpeechFailure(SpeechErrorCode.SERVER, "系统语音合成失败（$code）"),
                    )
                }
            },
        ))
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result != TextToSpeech.SUCCESS && continuation.isActive) {
            continuation.resumeWithException(SpeechFailure(SpeechErrorCode.SERVER, "系统语音合成启动失败"))
        }
        continuation.invokeOnCancellation { runCatching { engine.stop() } }
    }

/**
 * 解析 WAV 并转成 24 kHz 单声道 PCM16。系统引擎常见的 16/22.05/24 kHz、双声道都能处理；
 * 采样率不同时做线性插值重采样。
 */
private fun toPcm24kMono(wav: ByteArray): ByteArray {
    if (wav.size < 44) return ByteArray(0)
    if (String(wav, 0, 4, Charsets.US_ASCII) != "RIFF") return ByteArray(0)
    var channels = 1
    var sampleRate = 24_000
    var bits = 16
    var dataStart = -1
    var dataSize = 0
    var offset = 12
    while (offset + 8 <= wav.size) {
        val id = String(wav, offset, 4, Charsets.US_ASCII)
        val size = leInt(wav, offset + 4)
        if (size < 0) break
        when (id) {
            "fmt " -> {
                if (offset + 8 + 16 > wav.size) return ByteArray(0)
                channels = (wav[offset + 10].toInt() and 0xFF) or ((wav[offset + 11].toInt() and 0xFF) shl 8)
                sampleRate = leInt(wav, offset + 12)
                bits = (wav[offset + 22].toInt() and 0xFF) or ((wav[offset + 23].toInt() and 0xFF) shl 8)
            }
            "data" -> {
                dataStart = offset + 8
                dataSize = min(size, wav.size - dataStart)
            }
        }
        offset += 8 + size + (size and 1)
    }
    if (dataStart < 0 || dataSize < 2 || channels < 1 || bits != 16) return ByteArray(0)
    val frames = dataSize / (2 * channels)
    if (frames <= 0) return ByteArray(0)

    fun sample(frame: Int, channel: Int): Int {
        val position = dataStart + (frame * channels + channel) * 2
        return ((wav[position].toInt() and 0xFF) or (wav[position + 1].toInt() shl 8)).toShort().toInt()
    }

    fun mix(frame: Int): Float {
        if (channels == 1) return sample(frame, 0).toFloat()
        var total = 0f
        for (channel in 0 until channels) total += sample(frame, channel)
        return total / channels
    }

    val target = 24_000
    if (sampleRate == target) {
        val output = ByteArray(frames * 2)
        for (frame in 0 until frames) {
            val value = mix(frame).roundToInt().coerceIn(-32768, 32767)
            output[frame * 2] = (value and 0xFF).toByte()
            output[frame * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return output
    }

    val ratio = sampleRate.toDouble() / target
    val outFrames = (frames / ratio).toInt().coerceAtLeast(1)
    val output = ByteArray(outFrames * 2)
    for (index in 0 until outFrames) {
        val position = index * ratio
        val first = position.toInt().coerceIn(0, frames - 1)
        val second = min(first + 1, frames - 1)
        val fraction = position - first
        val value = (mix(first) * (1 - fraction) + mix(second) * fraction)
            .roundToInt().coerceIn(-32768, 32767)
        output[index * 2] = (value and 0xFF).toByte()
        output[index * 2 + 1] = ((value shr 8) and 0xFF).toByte()
    }
    return output
}

private fun leInt(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 3].toInt() and 0xFF) shl 24)
