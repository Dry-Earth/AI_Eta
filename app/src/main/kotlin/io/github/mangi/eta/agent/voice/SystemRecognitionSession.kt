package io.github.mangi.eta.agent.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * 调用系统内置语音识别（RecognitionService）。
 *
 * 与云端链路不同，这里不自己录音：识别引擎会自行打开麦克风，因此只能通过
 * start/stopListening 控制会话。LineageOS 等系统默认不带识别引擎，用户安装的
 * 离线引擎（例如 Marmalade STT）会以外部 RecognitionService 的形式被发现。
 */
internal class SystemRecognitionSession(
    context: Context,
    private val onReady: () -> Unit,
    private val onPreview: (String) -> Unit,
    private val onResult: (String) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private var stopped = false
    private var delivered = false

    fun start(language: String) {
        val created = runCatching { SystemSpeechRecognizer.create(appContext) }.getOrNull()
        if (created == null) {
            onFailure(SpeechFailure(SpeechErrorCode.CONFIGURATION, "系统未安装可用的语音识别服务，请先安装识别引擎"))
            return
        }
        recognizer = created
        created.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
        language.trim().takeIf { it.isNotEmpty() }?.let { intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
        runCatching { created.startListening(intent) }.onFailure {
            onFailure(SpeechFailure(SpeechErrorCode.SERVER, "无法启动系统语音识别"))
        }
    }

    /** 用户点「完成」：停止收音，等引擎返回最终结果。 */
    fun finish() {
        stopped = true
        runCatching { recognizer?.stopListening() }
    }

    fun cancel() {
        stopped = true
        runCatching {
            recognizer?.cancel()
            recognizer?.destroy()
        }
        recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = onReady()
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            if (delivered) return
            firstResult(partialResults)?.takeIf { it.isNotBlank() }?.let(onPreview)
        }

        override fun onResults(results: Bundle?) {
            if (delivered) return
            val text = firstResult(results).orEmpty().trim()
            if (text.isEmpty()) {
                onFailure(SpeechFailure(SpeechErrorCode.NO_SPEECH, "没有识别到语音，请重试"))
                return
            }
            delivered = true
            onResult(text)
        }

        override fun onError(error: Int) {
            // stopListening 之后引擎常以 NO_MATCH 收尾，此时仍当作「没听到」处理。
            if (delivered) return
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                delivered = true
                onFailure(SpeechFailure(SpeechErrorCode.NO_SPEECH, "没有识别到语音，请重试"))
                return
            }
            if (error == SpeechRecognizer.ERROR_CLIENT && stopped) {
                delivered = true
                onFailure(SpeechFailure(SpeechErrorCode.NO_SPEECH, "没有识别到语音，请重试"))
                return
            }
            delivered = true
            onFailure(SpeechFailure(codeOf(error), messageOf(error)))
        }
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun codeOf(error: Int): SpeechErrorCode = when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechErrorCode.PERMISSION
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> SpeechErrorCode.NETWORK
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> SpeechErrorCode.CONFIGURATION
        SpeechRecognizer.ERROR_SERVER -> SpeechErrorCode.SERVER
        else -> SpeechErrorCode.PROTOCOL
    }

    private fun messageOf(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "请允许 Eta 使用麦克风"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "系统识别引擎网络异常"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "系统识别引擎忙，请稍后重试"
        SpeechRecognizer.ERROR_SERVER -> "系统识别引擎返回错误"
        SpeechRecognizer.ERROR_AUDIO -> "录音失败，请重试"
        else -> "系统语音识别失败（$error）"
    }
}
