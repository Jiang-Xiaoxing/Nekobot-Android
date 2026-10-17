package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.LocalLogger
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Protocol
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

/** 仅记录匿名标签、阶段耗时和计数，不保存地址、头、请求或响应正文。 */
internal class RelationshipHttpDiagnostics(private val tag: String?) : EventListener() {
    private val started = System.nanoTime()
    private var attempts = 0
    private fun record(phase: String, detail: String = "") {
        LocalLogger.i("RelationshipHttp", "tag=$tag，阶段=$phase，耗时毫秒=${(System.nanoTime() - started) / 1_000_000}$detail")
    }
    override fun callStart(call: Call) = record("排队开始")
    override fun dnsStart(call: Call, domainName: String) = record("DNS开始")
    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) = record("DNS结束")
    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) = record("连接开始")
    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) = record("连接结束")
    override fun connectionAcquired(call: Call, connection: Connection) = record("获得连接")
    override fun requestHeadersStart(call: Call) { attempts++; record("发送请求头", "，网络尝试=$attempts") }
    override fun requestBodyEnd(call: Call, byteCount: Long) = record("请求正文完成", "，字节=$byteCount")
    override fun responseHeadersStart(call: Call) = record("响应头开始")
    override fun responseBodyStart(call: Call) = record("响应正文开始")
    override fun responseBodyEnd(call: Call, byteCount: Long) = record("响应正文结束", "，字节=$byteCount")
    override fun callEnd(call: Call) = record("完成", "，网络尝试=$attempts")
    override fun callFailed(call: Call, ioe: IOException) = record("失败", "，异常=${ioe.javaClass.simpleName}，网络尝试=$attempts")
}
