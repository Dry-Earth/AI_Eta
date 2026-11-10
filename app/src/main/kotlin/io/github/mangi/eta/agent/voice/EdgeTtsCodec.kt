package io.github.mangi.eta.agent.voice

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONObject

/**
 * 微软 Edge「大声朗读」的报文编解码。
 *
 * 这里的函数全部是纯函数，便于用单元测试固定 SSML 转义、DRM 令牌与二进制分帧的行为，
 * 避免协议细节散落在网络流程里。
 */
internal object EdgeTtsCodec {
    /** Edge 朗读公开使用的客户端令牌，不是用户凭据。 */
    const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    const val OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3"

    /** 令牌格式与版本头必须成对使用，v1 令牌会被服务端以 403 拒绝。 */
    const val DRM_VERSION = EdgeDrm.VERSION

    private val VOICE_PATTERN = Regex("[A-Za-z0-9_\\-()]{2,64}")
    private val CONTROL_CHARS = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")

    /** 音色名会进入 SSML 属性，只允许官方命名的字符集，避免注入。 */
    fun requireVoice(value: String): String {
        val voice = value.trim()
        if (!VOICE_PATTERN.matches(voice)) {
            throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "Edge 音色只能包含字母、数字、短横线、下划线和括号")
        }
        return voice
    }

    fun escapeText(value: String): String {
        val cleaned = CONTROL_CHARS.replace(value, "")
        if (cleaned.isBlank()) throw SpeechFailure(SpeechErrorCode.NO_SPEECH, "这条消息没有可朗读的正文")
        return cleaned
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    fun ssml(voice: String, rate: Int, pitch: Int, text: String): String {
        val voiceName = requireVoice(voice)
        val rateValue = if (rate >= 0) "+$rate%" else "$rate%"
        val pitchValue = if (pitch >= 0) "+${pitch}Hz" else "${pitch}Hz"
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
            "<voice name='$voiceName'><prosody pitch='$pitchValue' rate='$rateValue' volume='+0%'>" +
            escapeText(text) +
            "</prosody></voice></speak>"
    }

    fun configMessage(): String = JSONObject().put(
        "context",
        JSONObject().put(
            "synthesis",
            JSONObject().put(
                "audio",
                JSONObject()
                    .put("metadataoptions", JSONObject()
                        .put("sentenceBoundaryEnabled", "false")
                        .put("wordBoundaryEnabled", "false"))
                    .put("outputFormat", OUTPUT_FORMAT),
            ),
        ),
    ).toString()

    /**
     * 命令请求同样带 `Path:speech.config` 头，与 edge-tts SDK 的报文一致；
     * 服务端按 Path 分派消息，裸 JSON 会被当作无效帧。
     */
    fun configMessage(timestamp: String): String =
        "X-Timestamp:$timestamp\r\n" +
            "Content-Type:application/json; charset=utf-8\r\n" +
            "Path:speech.config\r\n\r\n" +
            configMessage() +
            "\r\n"

    /** 文本帧由若干行头和一个空行组成，正文是 SSML；`Path:ssml` 是服务端分派依据。 */
    fun speechMessage(requestId: String, timestamp: String, ssml: String): String =
        "X-RequestId:$requestId\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            "X-Timestamp:${timestamp}Z\r\n" +
            "Path:ssml\r\n\r\n" +
            ssml

    fun requestId(): String = java.util.UUID.randomUUID().toString().replace("-", "")

    fun connectionId(): String = requestId()

    /** 服务端只校验格式，但 edge-tts SDK 用的是 JS `Date` 风格字符串，这里保持一致。 */
    fun timestamp(epochMillis: Long): String =
        SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(epochMillis))

    /**
     * Edge 的 `Sec-MS-GEC` 令牌，格式为 `<CS64>-<SHA256>`，每 300 秒换一次。
     * 旧版纯 SHA-256 令牌已经被服务端废弃，这里统一走 [EdgeDrm] 的 v2 实现。
     */
    fun drmToken(epochMillis: Long): String = EdgeDrm.secMsGecV2(epochMillis)

    /**
     * 二进制帧：前两字节为大端头长度，随后是 JSON 头，再往后是音频。
     * 头里 `Path` 不是 `audio` 时该帧不含音频。
     */
    fun parseBinaryFrame(bytes: ByteArray): EdgeFrame? {
        if (bytes.size < 2) return null
        val headerLength = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        if (headerLength <= 0 || headerLength + 2 > bytes.size) return null
        val header = String(bytes, 2, headerLength, Charsets.UTF_8)
        val path = runCatching { JSONObject(header).optString("Path") }.getOrDefault("")
        if (path != "audio") return EdgeFrame.Metadata(path)
        val start = headerLength + 2
        val audio = bytes.copyOfRange(start, bytes.size)
        return if (audio.isEmpty()) EdgeFrame.Metadata(path) else EdgeFrame.Audio(audio)
    }
}

internal sealed interface EdgeFrame {
    class Audio(val bytes: ByteArray) : EdgeFrame
    class Metadata(val path: String) : EdgeFrame
}
