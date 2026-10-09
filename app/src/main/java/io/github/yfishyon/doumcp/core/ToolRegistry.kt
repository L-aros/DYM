package io.github.yfishyon.doumcp.core

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 工具注册表：MCP 端点与 REST 端点共用同一批工具处理逻辑。
 *
 * 各功能包通过 [addDouMcpTool] 注册，工具同时进入 MCP 与 REST 两条调用路径。
 */
object ToolRegistry {
    private val handlers = ConcurrentHashMap<String, suspend (CallToolRequest) -> CallToolResult>()

    fun register(
        name: String,
        handler: suspend (CallToolRequest) -> CallToolResult,
    ) {
        handlers[name] = handler
    }

    /** 已注册工具名（字典序）。 */
    fun names(): List<String> = handlers.keys.sorted()

    /** 按名调用工具；未注册返回 null。 */
    suspend fun call(
        name: String,
        arguments: JsonObject?,
    ): CallToolResult? =
        handlers[name]?.invoke(
            CallToolRequest(CallToolRequestParams(name = name, arguments = arguments)),
        )
}

/** 注册工具：挂到 MCP 端点，同时进入 REST 可调用表。schema 声明参数类型（见 inputSchema）。 */
internal fun Server.addDouMcpTool(
    name: String,
    description: String,
    schema: ToolSchema? = null,
    handler: suspend (CallToolRequest) -> CallToolResult,
) {
    ToolRegistry.register(name, handler)
    if (schema != null) {
        addTool(name, description, schema) { request -> handler(request) }
    } else {
        addTool(name, description) { request -> handler(request) }
    }
}
