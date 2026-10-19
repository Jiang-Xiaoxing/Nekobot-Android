package com.nekobot.app.data.local.ai

/** 内置浏览器与 Agent 浏览器共用的明文 HTTP 主机白名单。 */
internal object LocalBrowserHttpPolicy {
    /** 提供 HTTP 页面访问的题库站点；网络安全配置也只为该主机开放明文流量。 */
    const val ALLOWED_HTTP_IP = "39.106.221.130"

    /** 地址栏补全协议或校验 HTTP 地址时，主机是否允许使用明文连接。 */
    fun isAllowedHttpHost(value: String): Boolean {
        val address = value.trim()
        val authority = address.substringAfter("://", address)
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('@')
        if (authority.isBlank()) return false

        val host = if (authority.startsWith("[")) {
            authority.substringAfter('[').substringBefore(']')
        } else {
            authority.substringBefore(':')
        }.lowercase()

        return host == "localhost" ||
            host == "::1" ||
            host == "0:0:0:0:0:0:0:1" ||
            host.startsWith("127.") ||
            host == ALLOWED_HTTP_IP
    }
}
