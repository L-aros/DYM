package io.github.yfishyon.doumcp

import android.content.Context
import io.github.yfishyon.doumcp.core.DouToastHelper
import io.github.yfishyon.doumcp.core.ModLog
import io.github.yfishyon.doumcp.core.ToolRegistry
import io.github.yfishyon.doumcp.core.addDouMcpTool
import io.github.yfishyon.doumcp.features.account.tool.registerAccountTools
import io.github.yfishyon.doumcp.features.comment.tool.registerCommentPublishTools
import io.github.yfishyon.doumcp.features.comment.tool.registerCommentTools
import io.github.yfishyon.doumcp.features.database.bridge.DatabaseBridge
import io.github.yfishyon.doumcp.features.database.tool.registerDatabaseTools
import io.github.yfishyon.doumcp.features.devtool.tool.registerDevtoolRuntimeTools
import io.github.yfishyon.doumcp.features.devtool.tool.registerDevtoolSearchTools
import io.github.yfishyon.doumcp.features.devtool.tool.registerDevtoolWatchTools
import io.github.yfishyon.doumcp.features.im.tool.registerImTools
import io.github.yfishyon.doumcp.features.interact.tool.registerInteractTools
import io.github.yfishyon.doumcp.features.notice.tool.registerNoticeTools
import io.github.yfishyon.doumcp.features.publish.tool.registerPublishTools
import io.github.yfishyon.doumcp.features.search.tool.registerSearchTools
import io.github.yfishyon.doumcp.features.system.tool.registerSystemTools
import io.github.yfishyon.doumcp.features.video.tool.registerVideoTools
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * MCP 服务宿主：Ktor Streamable HTTP（仅监听 127.0.0.1）+ Bearer 鉴权，注册全部功能包工具。
 * 服务启动整体在自有后台线程执行，不阻塞宿主主线程。
 */
object McpServerHost {
    /** 健康自检周期（连续失败时按退避放大） */
    private const val PROBE_INTERVAL_MS = 20_000L

    /** 探针连接超时：loopback 为亚毫秒级，1 秒给足余量 */
    private const val PROBE_CONNECT_TIMEOUT_MS = 1_000

    /** 探针读超时：GET /api 正常在毫秒级，读满即判定服务卡死 */
    private const val PROBE_READ_TIMEOUT_MS = 2_000

    /** 重建时旧引擎的排空时间与停止总超时 */
    private const val ENGINE_STOP_GRACE_MS = 500L
    private const val ENGINE_STOP_TIMEOUT_MS = 3_000L

    /** 停服务时等待守护线程退出的上限 */
    private const val WATCHDOG_JOIN_MS = 2_000L

    /** 连续失败时自检间隔的封顶 */
    private const val MAX_BACKOFF_MS = 300_000L

    @Volatile
    private var started = false

    /** 启动序号：stop() 递增，使已通过端口预检但尚未落地的启动任务自我作废 */
    private var launchSeq = 0L

    private var engine: io.ktor.server.engine.EmbeddedServer<*, *>? = null

    /** 启动任务队列（daemon，热重载时随 stop 一并关停） */
    private var launcher: java.util.concurrent.ExecutorService? = null

    /** 健康守护线程；以引用相等作为存活判据，stop() 置 null 并 interrupt */
    @Volatile
    private var watchdog: Thread? = null

    /** 服务实际使用的鉴权密钥（探针按它构造请求；改设置后需重启才变） */
    @Volatile
    private var activeToken = ""

    /**
     * 启动服务（幂等）。启动流程投递到自有后台线程执行：
     * 调用点在宿主主线程的 hook 回调里，端口预检的重试等待不能发生在主线程。
     */
    fun start(port: Int) {
        synchronized(this) {
            if (started) return
            val exec =
                launcher?.takeIf { !it.isShutdown }
                    ?: java.util.concurrent.Executors
                        .newSingleThreadExecutor { runnable ->
                            Thread(runnable).apply { isDaemon = true }
                        }.also { launcher = it }
            val seq = ++launchSeq
            exec.execute { startBlocking(port, seq, notify = true) }
        }
        // 服务被要求启动即进入自检：即使首次启动失败（端口被占），守护线程也会持续重试
        ensureWatchdog(port)
    }

    /** 停止服务并给排空时间（热重载前调用）；未执行的启动任务与守护线程一并关停 */
    fun stop() {
        val (watch, exec, server) =
            synchronized(this) {
                launchSeq++
                val w = watchdog
                watchdog = null
                val l = launcher
                launcher = null
                val e = engine
                engine = null
                started = false
                Triple(w, l, e)
            }
        // 阻塞操作留在锁外：守护线程可能正持锁执行 startBlocking，锁内等待会互等
        watch?.interrupt()
        runCatching { watch?.join(WATCHDOG_JOIN_MS) }
        exec?.shutdownNow()
        runCatching { server?.stop(1_000, 5_000) }
    }

    /** 确保健康守护线程在跑（幂等）；随服务启动派生，随 stop() 关停 */
    private fun ensureWatchdog(port: Int) {
        synchronized(this) {
            if (watchdog?.isAlive == true) return
            val thread = Thread({ watchdogLoop(port) }, "doumcp-watchdog")
            thread.isDaemon = true
            // 先赋值再启动：循环首行以 watchdog === 当前线程 判定自身是否仍有效
            watchdog = thread
            thread.start()
        }
    }

    /**
     * 健康自检循环：探针不响即重建服务。
     *
     * 连续失败时按 20s×2^(n-1) 放大间隔（封顶 300s），避免长期不可恢复时反复重建；恢复即复位。
     */
    private fun watchdogLoop(port: Int) {
        val self = Thread.currentThread()
        var failStreak = 0
        var delayMs = PROBE_INTERVAL_MS
        while (watchdog === self && !self.isInterrupted) {
            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
                return
            }
            if (watchdog !== self) return
            if (probe(port)) {
                failStreak = 0
                delayMs = PROBE_INTERVAL_MS
                continue
            }
            failStreak++
            ModLog.w("MCP：健康检查失败（连续 $failStreak 次），重建服务")
            restart(port)
            // 重建后立即确认：成功则马上回到常规间隔，失败才进入退避
            if (probe(port)) {
                failStreak = 0
                delayMs = PROBE_INTERVAL_MS
                continue
            }
            delayMs = backoffMs(failStreak)
        }
    }

    /** 连续失败 n 次时的自检间隔：20 / 40 / 80 / 160 / 300 秒 */
    private fun backoffMs(streak: Int): Long {
        val shift = (streak - 1).coerceIn(0, 4)
        return (PROBE_INTERVAL_MS shl shift).coerceAtMost(MAX_BACKOFF_MS)
    }

    /**
     * 探针：裸 Socket 发一个最小 GET /api，读首行判 200。
     *
     * 走完整 HTTP 链路（accept → 鉴权 → 路由 → 响应），因此能测出「端口在听但处理链卡死」：
     * 连接失败或读超时都判为不健康。用裸 Socket 是为绕开明文流量策略并精确控制读超时。
     */
    private fun probe(port: Int): Boolean =
        runCatching {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress("127.0.0.1", port), PROBE_CONNECT_TIMEOUT_MS)
                socket.soTimeout = PROBE_READ_TIMEOUT_MS
                val token = activeToken
                val request =
                    buildString {
                        append("GET /api HTTP/1.1\r\n")
                        append("Host: 127.0.0.1\r\n")
                        if (token.isNotEmpty()) append("Authorization: Bearer $token\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                socket.getOutputStream().apply {
                    write(request.toByteArray(Charsets.US_ASCII))
                    flush()
                }
                val statusLine = socket.getInputStream().bufferedReader().readLine()
                statusLine != null && statusLine.contains(" 200")
            }
        }.getOrDefault(false)

    /**
     * 重建服务：拆旧引擎后复用启动流程。
     *
     * 不调用 stop()——它会关停任务队列并 interrupt 调用线程，从看门狗里调会自杀。
     * 靠 launchSeq 与守护线程引用作废并发期间的 stop()（热重载），避免复活已停的服务。
     */
    private fun restart(port: Int) {
        val (old, seq) =
            synchronized(this) {
                val e = engine
                engine = null
                started = false
                e to ++launchSeq
            }
        runCatching { old?.stop(ENGINE_STOP_GRACE_MS, ENGINE_STOP_TIMEOUT_MS) }
            .onFailure { ModLog.w("MCP：旧引擎停止异常", it) }
        if (watchdog !== Thread.currentThread()) return
        startBlocking(port, seq, notify = false)
    }

    private fun startBlocking(
        port: Int,
        seq: Long,
        notify: Boolean,
    ) {
        // 端口预检含重试等待，放在锁外，避免阻塞并发的 start()/stop()
        var portFree = false
        for (attempt in 1..3) {
            portFree =
                runCatching {
                    java.net.ServerSocket(port, 1, java.net.InetAddress.getByName("127.0.0.1")).use { }
                }.isSuccess
            if (portFree) break
            try {
                Thread.sleep(500)
            } catch (_: InterruptedException) {
                return
            }
        }
        if (!portFree) {
            ModLog.e("MCP：端口 $port 持续被占用，放弃启动")
            return
        }

        synchronized(this) {
            if (started || seq != launchSeq) return
            try {
                startInternal(port, notify)
                started = true
            } catch (t: Throwable) {
                ModLog.e("MCP：服务启动失败", t)
            }
        }
    }

    private fun startInternal(
        port: Int,
        notify: Boolean,
    ) {
        val context =
            io.github.yfishyon.doumcp.core.HostRuntime
                .requireContext()
        val token = ModulePrefs.getToken(context)

        val server =
            Server(
                serverInfo = Implementation(name = "doumcp", version = "1.1"),
                options =
                    ServerOptions(
                        capabilities =
                            ServerCapabilities(
                                tools = ServerCapabilities.Tools(listChanged = false),
                            ),
                    ),
            )

        registerTools(server)

        val serverEngine =
            embeddedServer(CIO, host = "127.0.0.1", port = port) {
                // 仅本机
                if (token.isNotEmpty()) {
                    intercept(ApplicationCallPipeline.Plugins) {
                        if (call.request.header("Authorization") != "Bearer $token") {
                            call.respondText("unauthorized", status = HttpStatusCode.Unauthorized)
                            finish()
                        }
                    }
                }
                routing {
                    // GET /api：列出全部工具名
                    get("/api") {
                        call.respondText(
                            org.json
                                .JSONObject()
                                .put("ok", true)
                                .put("tools", org.json.JSONArray(ToolRegistry.names()))
                                .toString(),
                            ContentType.Application.Json,
                        )
                    }
                    // POST /api/{工具名}：请求体即参数 JSON，返回工具结果
                    post("/api/{name}") {
                        val name = call.parameters["name"].orEmpty()
                        val body = call.receiveText()
                        val arguments =
                            runCatching {
                                if (body.isBlank()) null else Json.parseToJsonElement(body).jsonObject
                            }.getOrNull()
                        val result = ToolRegistry.call(name, arguments)
                        if (result == null) {
                            call.respondText(
                                org.json
                                    .JSONObject()
                                    .put("ok", false)
                                    .put("error", "未知工具: $name")
                                    .toString(),
                                ContentType.Application.Json,
                                HttpStatusCode.NotFound,
                            )
                        } else {
                            val text = result.content.filterIsInstance<TextContent>().joinToString("") { it.text }
                            call.respondText(text, ContentType.Application.Json)
                        }
                    }
                }
                mcpStreamableHttp { server }
            }
        serverEngine.start(wait = false)
        engine = serverEngine
        activeToken = token
        // 自愈重建不弹提示（避免故障期反复弹窗），只走日志
        if (notify) DouToastHelper.show(context, "抖M：服务已启动（端口 $port）")
        ModLog.i("MCP：服务已启动，端口 $port")
    }

    /** 注册工具：ping 为连通性检查（返回裸 pong，不走统一 JSON 结构），其余按功能包挂载 */
    private fun registerTools(server: Server) {
        server.addDouMcpTool(
            name = "ping",
            description = "测试抖M服务是否存活，返回 pong",
        ) { _ ->
            CallToolResult(content = listOf(TextContent(text = "pong")))
        }

        val registrations =
            mutableListOf<Pair<String, Server.() -> Unit>>(
                "account" to { registerAccountTools() },
                "video" to { registerVideoTools() },
                "im" to { registerImTools() },
                "interact" to { registerInteractTools() },
                "comment" to { registerCommentTools() },
                "comment_publish" to { registerCommentPublishTools() },
                "publish" to { registerPublishTools() },
                "search" to { registerSearchTools() },
                "notice" to { registerNoticeTools() },
                "system" to { registerSystemTools() },
                "database" to { registerDatabaseTools() },
            )
        // 逆向调试工具只在 debug 包启用
        if (BuildConfig.DEBUG) {
            registrations += "devtool_search" to { registerDevtoolSearchTools() }
            registrations += "devtool_runtime" to { registerDevtoolRuntimeTools() }
            registrations += "devtool_watch" to { registerDevtoolWatchTools() }
        }
        for ((name, register) in registrations) {
            runCatching { register(server) }
                .onFailure { ModLog.e("MCP：$name 工具注册失败", it) }
        }
    }
}
