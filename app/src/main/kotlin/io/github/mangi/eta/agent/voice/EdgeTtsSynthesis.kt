package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.data.model.EdgeTtsConfig
import java.io.ByteArrayOutputStream
import java.io.Closeable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * 微软 Edge「大声朗读」合成。
 *
 * 免账号、免 Key，单次连接内合成一段文本；返回的 MP3 由 [Mp3PcmDecoder] 解码成 24 kHz PCM16。
 * 任何一步失败都直接报错，不回落到其他语音服务，也不重试到其他域名。
 */
internal suspend fun synthesizeEdgeTts(
    config: EdgeTtsConfig,
    text: String,
    onAudio: (ByteArray) -> Unit,
) {
    val base = edgeSpeechBase(config.baseUrl)
    val token = EdgeTtsCodec.TRUSTED_CLIENT_TOKEN
    val url = "$base/consumer/speech/synthesize/readaloud/edge/v1" +
        "?TrustedClientToken=$token" +
        "&Sec-MS-GEC=${EdgeTtsCodec.drmToken(System.currentTimeMillis())}" +
        "&Sec-MS-GEC-Version=${EdgeTtsCodec.DRM_VERSION}"
    val request = Request.Builder()
        .url(url)
        .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
        .header("Pragma", "no-cache")
        .header("Cache-Control", "no-cache")
        .header("User-Agent", USER_AGENT)
        .build()
    val audio = ByteArrayOutputStream()
    EdgeSpeechSocket(request).use { socket ->
        socket.awaitReady()
        socket.text(EdgeTtsCodec.configMessage())
        socket.text(
            EdgeTtsCodec.speechMessage(
                requestId = EdgeTtsCodec.requestId(),
                timestamp = EdgeTtsCodec.timestamp(System.currentTimeMillis()),
                ssml = EdgeTtsCodec.ssml(config.voice, config.rate, config.pitch, text),
            ),
        )
        var finished = false
        while (!finished) {
            when (val frame = socket.next()) {
                is EdgeSocketFrame.Text -> if (frame.value.trim().startsWith(TURN_END)) finished = true
                is EdgeSocketFrame.Binary -> when (val parsed = EdgeTtsCodec.parseBinaryFrame(frame.value)) {
                    is EdgeFrame.Audio -> {
                        if (audio.size().toLong() + parsed.bytes.size > MAX_AUDIO_BYTES) {
                            throw SpeechFailure(SpeechErrorCode.PROTOCOL, "Edge 返回的音频过长，请分段朗读")
                        }
                        audio.write(parsed.bytes)
                    }
                    is EdgeFrame.Metadata -> Unit
                    null -> Unit
                }
            }
        }
    }
    if (audio.size() == 0) throw SpeechFailure(SpeechErrorCode.PROTOCOL, "Edge 没有返回可播放的音频")
    Mp3PcmDecoder.decode(audio.toByteArray(), onAudio)
}

/**
 * Edge 的文本帧与二进制帧含义不同：文本帧携带 `Path:...` 状态，二进制帧头部带 `Path` 与音频。
 * 通用 [SpeechSocket] 把两者压平成字节，这里单独保留类型。
 */
private class EdgeSpeechSocket(request: Request) : Closeable {
    private val ready = CompletableDeferred<Unit>()
    private val frames = Channel<EdgeSocketFrame>(64)
    private val socket = SpeechHttp.client.newWebSocket(request, object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            ready.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            deliver(webSocket, EdgeSocketFrame.Text(text))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            deliver(webSocket, EdgeSocketFrame.Binary(bytes.toByteArray()))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val failure = response?.let {
                runCatching { SpeechHttp.checkStatus(it.code) }.exceptionOrNull()
                    ?.let { error -> error as? SpeechFailure ?: error.speechFailure() }
            } ?: t.speechFailure()
            ready.completeExceptionally(failure)
            frames.close(failure)
            response?.close()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
            frames.close()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            frames.close()
        }
    })

    private fun deliver(webSocket: WebSocket, frame: EdgeSocketFrame) {
        if (!frames.trySend(frame).isSuccess) {
            frames.close(SpeechFailure(SpeechErrorCode.BACKPRESSURE, "语音数据处理不及时，请重试"))
            webSocket.cancel()
        }
    }

    suspend fun awaitReady() = withTimeout(15_000) { ready.await() }

    suspend fun next(): EdgeSocketFrame {
        val frame = frames.receiveCatching()
        return frame.getOrNull()
            ?: throw (frame.exceptionOrNull()?.speechFailure()
                ?: SpeechFailure(SpeechErrorCode.PROTOCOL, "Edge 语音合成连接提前结束"))
    }

    fun text(value: String) {
        if (!socket.send(value)) throw SpeechFailure(SpeechErrorCode.BACKPRESSURE, "网络上传不及时，请检查网络后重试")
        if (socket.queueSize() > 256 * 1024) {
            throw SpeechFailure(SpeechErrorCode.BACKPRESSURE, "网络上传不及时，请检查网络后重试")
        }
    }

    override fun close() {
        socket.cancel()
        ready.cancel()
        frames.cancel()
    }
}

private sealed interface EdgeSocketFrame {
    class Text(val value: String) : EdgeSocketFrame
    class Binary(val value: ByteArray) : EdgeSocketFrame
}

private const val TURN_END = "Path:turn.end"
private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0"
/** MP3 有压缩比，但单段文本仍有上限，避免异常响应把内存撑爆。 */
private const val MAX_AUDIO_BYTES = 8L * 1024 * 1024
