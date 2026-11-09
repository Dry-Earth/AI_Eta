package io.github.mangi.eta.core

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 自建服务地址策略。
 *
 * 云端服务只接受 HTTPS。为了让本机与局域网内的模型可用，明文 HTTP 只放行环回、
 * 私有网段、链路本地、CGNAT 以及 mDNS/家庭网络保留域名，公网地址仍然必须使用 HTTPS，
 * 避免凭据和图片内容以明文发往互联网。
 */
internal object LocalEndpointPolicy {
    /** 放行明文 HTTP 的保留域名后缀；它们只可能解析到本机或局域网。 */
    private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home.arpa", ".localhost")

    fun requireServiceBase(value: String, allowPrivateHttp: Boolean): HttpUrl {
        val url = value.trim().toHttpUrlOrNull()
        if (url == null || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.query != null || url.fragment != null
        ) {
            throw endpointFailure("服务地址须为不含认证信息和查询参数的地址")
        }
        if (url.isHttps) return url
        if (allowPrivateHttp && isLocalHost(url.host)) return url
        throw endpointFailure("公网服务地址必须使用 HTTPS；明文 HTTP 只允许本机或局域网地址")
    }

    /** 服务路径必须是站内绝对路径，禁止查询串、片段与上级目录跳转。 */
    fun requireServicePath(value: String): String {
        val path = value.trim()
        val valid = path.startsWith("/") && path.length <= 512 &&
            !path.contains('?') && !path.contains('#') && !path.contains(' ') &&
            path.split('/').none { it == ".." || it == "." }
        if (!valid) throw endpointFailure("服务路径须以 / 开头，且不能包含查询参数或上级目录")
        return path.trimEnd('/').ifEmpty { "/" }
    }

    fun isLocalHost(host: String): Boolean {
        val normalized = host.trim().trim('[', ']').lowercase()
        if (normalized.isEmpty()) return false
        if (normalized == "localhost" || normalized == "::1" || normalized == "0:0:0:0:0:0:0:1") return true
        if (normalized.startsWith("fe80:") || normalized.startsWith("fc") || normalized.startsWith("fd")) {
            // fe80::/10 链路本地与 fc00::/7 唯一本地地址
            return true
        }
        if (LOCAL_SUFFIXES.any { normalized.endsWith(it) }) return true
        return isPrivateIpv4(normalized)
    }

    private fun isPrivateIpv4(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        val (a, b) = octets
        return when {
            a == 10 -> true
            a == 127 -> true
            a == 172 && b in 16..31 -> true
            a == 192 && b == 168 -> true
            a == 169 && b == 254 -> true
            a == 100 && b in 64..127 -> true
            else -> false
        }
    }

    private fun endpointFailure(message: String): IllegalArgumentException = IllegalArgumentException(message)
}

/** 便于在语音与识图两侧统一错误文案。 */
internal fun endpointErrorMessage(error: IllegalArgumentException): String =
    error.message ?: "服务地址无效"
