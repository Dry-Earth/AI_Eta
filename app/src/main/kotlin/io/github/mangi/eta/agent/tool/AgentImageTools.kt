package io.github.mangi.eta.agent.tool

import android.content.Context
import android.net.Uri
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.device.BoundedFileCopy
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.media.MAX_AGENT_IMAGE_BYTES
import io.github.mangi.eta.agent.media.hasSupportedImageMagic
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.vision.LocalVisionClient
import io.github.mangi.eta.agent.vision.VisionFailure
import io.github.mangi.eta.data.repository.ImageRecognitionRepository
import io.github.mangi.eta.data.repository.SpeechCredentialsUnavailable
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** 读取用户已明确指定的单张图片。 */
internal class AgentImageTools(
    private val context: Context,
    private val root: BoundedRootCommandExecutor,
    private val rootAvailable: () -> Boolean = { RootAccess.isGranted },
) {
    fun readImage(args: JSONObject): AgentModelClient.ToolResult {
        val source = args.getString("path").removePrefix("file://")
        val sourceKind = when {
            source.startsWith("content://") -> ImageSourceKind.ContentUri
            source.startsWith("/") && !source.contains('\u0000') -> ImageSourceKind.File
            else -> return sensitive(error("IMAGE_PATH_DENIED", "图片路径必须是绝对路径、file URI 或已授权的 content URI"))
        }
        val temporaryFile = runCatching {
            File.createTempFile("eta-read-image-", ".img", imageCacheDirectory())
        }.getOrElse {
            return sensitive(error("IMAGE_TEMPORARY_FILE_FAILED", "无法创建图片临时文件"))
        }
        return try {
            val staged = copyAsApp(source, sourceKind, temporaryFile)
            if (!staged) {
                if (!rootAvailable()) {
                    return sensitive(error("IMAGE_ACCESS_DENIED", "Eta 无法读取此图片；请先通过文件选择器导入或授予读取权限"))
                }
                val copyResult = root.execute(
                    imageCopyCommand(source, sourceKind, temporaryFile),
                    timeoutMillis = READ_TIMEOUT_MS,
                    maxOutputBytes = 8 * 1024,
                )
                if (!copyResult.ok) return sensitive(copyFailure(copyResult))
            }
            describeLocally(source, temporaryFile)?.let { return it }
            val image = AgentImageCodec.fromToolFile(
                file = temporaryFile,
                source = "tool_read_image",
            ) ?: return sensitive(error("IMAGE_UNSUPPORTED", "文件不是可识别的图片"))
            sensitive(
                content = JSONObject()
                    .put("ok", true)
                    .put("tool", "read_image")
                    .put("path", source)
                    .put("image_attached", true)
                    .toString(),
                images = listOf(image),
            )
        } catch (_: BoundedFileCopy.TooLargeException) {
            sensitive(error("IMAGE_TOO_LARGE", "图片超过大小限制"))
        } finally {
            temporaryFile.delete()
        }
    }

    /**
     * 启用本地识图后，图片只发给配置的本机/局域网服务，模型只拿到文字描述。
     *
     * 这里刻意不做任何回退：配置读不出、凭据解不开、服务连不上都直接返回错误，
     * 绝不会把图片交给对话模型，因此图片不会因为识图失败而“逃逸”到云端。
     */
    private fun describeLocally(source: String, file: File): AgentModelClient.ToolResult? {
        val settings = try {
            runBlocking { ImageRecognitionRepository.settings() }
        } catch (_: Exception) {
            return sensitive(error("VISION_CONFIG_UNAVAILABLE", "无法读取本地图片识别配置，本次识图已取消"))
        }
        if (!settings.enabled) return null
        if (settings.model.isBlank()) {
            return sensitive(error("VISION_NOT_CONFIGURED", "请在图片识别设置中填写本地识图模型"))
        }
        val key = try {
            runBlocking { ImageRecognitionRepository.apiKey(context) }
        } catch (_: SpeechCredentialsUnavailable) {
            return sensitive(error("VISION_CREDENTIALS_UNAVAILABLE", "图片识别凭据无法解密，请重新填写并保存"))
        }
        val bytes = runCatching { file.readBytes() }.getOrElse {
            return sensitive(error("IMAGE_UNAVAILABLE", "图片读取失败"))
        }
        if (!bytes.hasSupportedImageMagic()) {
            return sensitive(error("IMAGE_UNSUPPORTED", "文件不是可识别的图片"))
        }
        val description = try {
            LocalVisionClient.describe(settings, key, bytes, mimeType(bytes))
        } catch (failure: VisionFailure) {
            return sensitive(error("VISION_FAILED", failure.message ?: "本地识图失败"))
        } catch (failure: Exception) {
            return sensitive(error("VISION_UNAVAILABLE", "无法连接本地识图服务：${failure.message ?: "未知错误"}"))
        }
        return sensitive(
            JSONObject()
                .put("ok", true)
                .put("tool", "read_image")
                .put("path", source)
                .put("image_attached", false)
                .put("local_vision", true)
                .put("vision_model", settings.model.trim())
                .put("description", description)
                .toString(),
        )
    }

    private fun mimeType(bytes: ByteArray): String = when {
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "image/png"
        bytes.size >= 6 && bytes[0] == 'G'.code.toByte() -> "image/gif"
        bytes.size >= 12 && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        else -> "image/jpeg"
    }

    private fun copyAsApp(source: String, sourceKind: ImageSourceKind, destination: File): Boolean = try {
        val input = when (sourceKind) {
            ImageSourceKind.File -> File(source).takeIf { it.isFile && it.canRead() }?.inputStream()
            ImageSourceKind.ContentUri -> context.contentResolver.openInputStream(Uri.parse(source))
        }
        if (input == null) {
            false
        } else {
            input.use { sourceStream ->
                destination.outputStream().use { target ->
                    BoundedFileCopy.copy(sourceStream, target, MAX_IMAGE_FILE_BYTES)
                }
            }
            true
        }
    } catch (tooLarge: BoundedFileCopy.TooLargeException) {
        throw tooLarge
    } catch (interrupted: InterruptedIOException) {
        throw interrupted
    } catch (_: SecurityException) {
        false
    } catch (_: IOException) {
        false
    }

    private fun imageCopyCommand(
        source: String,
        sourceKind: ImageSourceKind,
        destination: File,
    ): String = when (sourceKind) {
        ImageSourceKind.File -> "[ -f ${shellQuote(source)} ] || exit 21; " +
            "[ \"$(stat -c %s ${shellQuote(source)})\" -le $MAX_IMAGE_FILE_BYTES ] || exit 22; " +
            "cp ${shellQuote(source)} ${shellQuote(destination.absolutePath)} || exit 23"
        ImageSourceKind.ContentUri -> "content read --uri ${shellQuote(source)} 2>/dev/null | " +
            "head -c ${MAX_IMAGE_FILE_BYTES + 1L} > ${shellQuote(destination.absolutePath)} && " +
            "[ \"$(stat -c %s ${shellQuote(destination.absolutePath)})\" -le $MAX_IMAGE_FILE_BYTES ]"
    }

    private fun imageCacheDirectory(): File =
        context.externalCacheDir
            ?.takeIf { it.isDirectory || it.mkdirs() }
            ?: context.cacheDir

    private fun copyFailure(result: BoundedRootCommandExecutor.Result): String = when (result.exitCode) {
        21 -> error("IMAGE_SOURCE_UNAVAILABLE", "图片源文件不存在或当前不可读")
        22 -> error("IMAGE_TOO_LARGE", "图片超过大小限制")
        23 -> error("IMAGE_STAGE_FAILED", "Root 无法将图片复制到 Eta 临时缓存")
        else -> error("IMAGE_UNAVAILABLE", "图片读取失败")
    }

    private fun error(code: String, message: String): String =
        JSONObject().put("ok", false).put("code", code).put("message", message).toString()

    private fun sensitive(
        content: String,
        images: List<AgentModelClient.ModelImage> = emptyList(),
    ) = AgentModelClient.ToolResult(content = content, images = images, sensitive = true)

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private enum class ImageSourceKind {
        File,
        ContentUri,
    }

    private companion object {
        const val READ_TIMEOUT_MS = 15_000L
        const val MAX_IMAGE_FILE_BYTES = MAX_AGENT_IMAGE_BYTES.toLong()
    }
}
