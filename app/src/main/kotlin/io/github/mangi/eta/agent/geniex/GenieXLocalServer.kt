package io.github.mangi.eta.agent.geniex

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Base64
import android.util.Log
import com.geniex.sdk.VlmWrapper
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.VlmChatMessage
import com.geniex.sdk.bean.VlmContent
import com.geniex.sdk.bean.VlmCreateInput
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * 把设备内 GenieX 包装成一个只监听 127.0.0.1 的 OpenAI 兼容服务。
 *
 * 这样角色对话可以完全复用现有的 OpenAI 兼容 provider 链路（流式、取消、工具开关、
 * 历史管理都不需要改），而模型常驻/释放由本服务的生命周期决定。
 *
 * 只绑定回环地址，外部网络无法访问。
 */
internal class GenieXLocalServer : Service() {
    private var socket: ServerSocket? = null
    private var worker: Thread? = null
    private var vlm: VlmWrapper? = null
    private var loadedKey = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (socket != null) return START_STICKY
        startServer()
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { socket?.close() }
        socket = null
        runCatching { vlm?.close() }
        vlm = null
        loadedKey = ""
        running = false
        super.onDestroy()
    }

    private fun startServer() {
        val server = runCatching { ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1")) }
            .getOrElse {
                Log.e(TAG, "无法监听端口 $PORT", it)
                stopSelf()
                return
            }
        socket = server
        running = true
        worker = Thread({
            while (running) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                runBlocking { runCatching { handle(client) } }.onFailure { Log.w(TAG, "请求处理失败", it) }
            }
        }, "geniex-local-server").apply { isDaemon = true; start() }
        Log.i(TAG, "GenieX 本地服务已启动：http://127.0.0.1:$PORT/v1")
    }

    private suspend fun handle(client: Socket) = client.use { connection ->
        connection.soTimeout = 120_000
        val reader = BufferedReader(InputStreamReader(connection.getInputStream(), Charsets.UTF_8))
        val requestLine = reader.readLine().orEmpty()
        if (!requestLine.startsWith("POST")) {
            writeJson(connection.getOutputStream(), 404, """{"error":{"message":"not found"}}""")
            return@use
        }
        var contentLength = 0
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }
        val body = CharArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = reader.read(body, read, contentLength - read)
            if (n <= 0) break
            read += n
        }
        val payload = runCatching { JSONObject(String(body, 0, read)) }.getOrElse {
            writeJson(connection.getOutputStream(), 400, """{"error":{"message":"invalid json"}}""")
            return@use
        }
        val messages = payload.optJSONArray("messages") ?: JSONArray()
        val stream = payload.optBoolean("stream", true)
        val model = payload.optString("model").trim()
        val chatMessages = toVlmMessages(messages)
        if (chatMessages.isEmpty()) {
            writeJson(connection.getOutputStream(), 400, """{"error":{"message":"no messages"}}""")
            return@use
        }
        val wrapper = ensureLoaded(model) ?: run {
            writeJson(connection.getOutputStream(), 503, """{"error":{"message":"本地模型未就绪"}}""")
            return@use
        }
        if (stream) streamCompletion(connection.getOutputStream(), wrapper, chatMessages)
        else writeCompletion(connection.getOutputStream(), wrapper, chatMessages)
    }

    private suspend fun ensureLoaded(modelName: String): VlmWrapper? {
        val key = modelName.ifBlank { "default" }
        vlm?.takeIf { loadedKey == key }?.let { return it }
        runCatching { vlm?.close() }
        vlm = null
        val paths = runBlocking { GenieXModelManager.paths(key) } ?: return null
        val built = runCatching {
            VlmWrapper.builder()
                .vlmCreateInput(
                    VlmCreateInput(
                        model_path = paths.model_path,
                        mmproj_path = paths.mmproj_path,
                        config = ModelConfig(nCtx = 4096),
                        runtime_id = paths.runtime_id.ifBlank { "llama_cpp" },
                        compute_unit = null,
                    ),
                )
                .build()
                .getOrThrow()
        }.onFailure { Log.e(TAG, "模型加载失败", it) }.getOrNull() ?: return null
        vlm = built
        loadedKey = key
        return built
    }

    private suspend fun generate(wrapper: VlmWrapper, messages: List<VlmChatMessage>, onToken: (String) -> Unit) {
        val generation = wrapper.injectMediaPathsToConfig(messages.toTypedArray(), GenerationConfig(maxTokens = 2048))
        val templated = wrapper.applyChatTemplate(messages.toTypedArray(), null, false).getOrThrow()
        runBlocking {
            wrapper.generateStreamFlow(templated.formattedText, generation).collect { result ->
                when (result) {
                    is com.geniex.sdk.bean.LlmStreamResult.Token -> onToken(result.text)
                    is com.geniex.sdk.bean.LlmStreamResult.Error -> throw result.throwable
                    else -> Unit
                }
            }
        }
    }

    private suspend fun streamCompletion(out: OutputStream, wrapper: VlmWrapper, messages: List<VlmChatMessage>) {
        writeRaw(
            out,
            "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\n" +
                "Cache-Control: no-cache\r\nConnection: close\r\n\r\n",
        )
        fun send(delta: JSONObject) {
            val chunk = JSONObject()
                .put("object", "chat.completion.chunk")
                .put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", delta)))
            writeRaw(out, "data: $chunk\n\n")
            out.flush()
        }
        val text = StringBuilder()
        runCatching {
            generate(wrapper, messages) { token ->
                text.append(token)
                send(JSONObject().put("content", token))
            }
        }.onFailure { error ->
            send(JSONObject().put("content", "[本地模型错误] " + (error.message ?: "未知错误")))
        }
        send(JSONObject())
        writeRaw(out, "data: [DONE]\n\n")
        out.flush()
    }

    private suspend fun writeCompletion(out: OutputStream, wrapper: VlmWrapper, messages: List<VlmChatMessage>) {
        val text = StringBuilder()
        runCatching { generate(wrapper, messages) { text.append(it) } }
        val response = JSONObject()
            .put("object", "chat.completion")
            .put(
                "choices",
                JSONArray().put(
                    JSONObject()
                        .put("index", 0)
                        .put("finish_reason", "stop")
                        .put("message", JSONObject().put("role", "assistant").put("content", text.toString())),
                ),
            )
        writeJson(out, 200, response.toString())
    }

    /** OpenAI 消息 → VlmChatMessage；图片 data URL 落成临时文件（VlmWrapper 只接受路径）。 */
    private fun toVlmMessages(messages: JSONArray): List<VlmChatMessage> {
        val result = ArrayList<VlmChatMessage>(messages.length())
        for (i in 0 until messages.length()) {
            val message = messages.optJSONObject(i) ?: continue
            val role = message.optString("role").ifBlank { "user" }
            val contents = ArrayList<VlmContent>()
            when (val content = message.opt("content")) {
                is String -> if (content.isNotBlank()) contents.add(VlmContent("text", content))
                is JSONArray -> for (j in 0 until content.length()) {
                    val part = content.optJSONObject(j) ?: continue
                    when (part.optString("type")) {
                        "text" -> part.optString("text").takeIf { it.isNotBlank() }?.let { contents.add(VlmContent("text", it)) }
                        "image_url" -> imagePath(part)?.let { contents.add(VlmContent("image", it)) }
                    }
                }
            }
            if (contents.isNotEmpty()) result.add(VlmChatMessage(role = role, contents = contents))
        }
        return result
    }

    private fun imagePath(part: JSONObject): String? {
        val url = part.optJSONObject("image_url")?.optString("url")?.takeIf { it.isNotBlank() }
            ?: part.optString("image_url").takeIf { it.isNotBlank() }
            ?: return null
        if (!url.startsWith("data:")) return url
        return runCatching {
            val comma = url.indexOf(',')
            val meta = url.substring(5, comma)
            val bytes = Base64.decode(url.substring(comma + 1), Base64.DEFAULT)
            val ext = when {
                meta.contains("png") -> "png"
                meta.contains("webp") -> "webp"
                else -> "jpg"
            }
            val file = File.createTempFile("geniex-chat-", ".$ext", cacheDir)
            file.writeBytes(bytes)
            file.absolutePath
        }.getOrNull()
    }

    private fun writeJson(out: OutputStream, code: Int, body: String) {
        val reason = if (code == 200) "OK" else "Error"
        writeRaw(
            out,
            "HTTP/1.1 $code $reason\r\nContent-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body",
        )
    }

    companion object {
        private const val TAG = "GenieXLocalServer"
        const val PORT = 8790
        const val ACTION_STOP = "io.github.mangi.eta.GENIEX_LOCAL_STOP"

        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, GenieXLocalServer::class.java)
            context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GenieXLocalServer::class.java))
        }
    }
}

private fun writeRaw(out: OutputStream, text: String) {
    out.write(text.toByteArray())
    out.flush()
}