package io.github.yfishyon.doumcp.features.interact.tool

import io.github.yfishyon.doumcp.core.addDouMcpTool
import io.github.yfishyon.doumcp.core.errorResult
import io.github.yfishyon.doumcp.core.stringArg
import io.github.yfishyon.doumcp.core.toolCall
import io.github.yfishyon.doumcp.features.interact.bridge.InteractBridge
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 互动域 MCP 工具注册：点赞作品、收藏作品。 */
internal fun Server.registerInteractTools() {
    addDouMcpTool(
        name = "diggAweme",
        description =
            "点赞或取消点赞一个作品。awemeId 取自作品详情或作品列表；" +
                "action 取 like（点赞）或 unlike（取消）",
    ) { request ->
        val awemeId = request.arguments.stringArg("awemeId") ?: return@addDouMcpTool errorResult("缺少 awemeId 参数")
        val action = request.arguments.stringArg("action") ?: return@addDouMcpTool errorResult("缺少 action 参数")
        val like =
            when (action) {
                "like" -> true
                "unlike" -> false
                else -> return@addDouMcpTool errorResult("action 只能是 like 或 unlike")
            }
        withContext(Dispatchers.IO) {
            toolCall { InteractBridge.diggAwemeJson(awemeId, like) }
        }
    }

    addDouMcpTool(
        name = "collectAweme",
        description =
            "收藏或取消收藏一个作品。awemeId 取自作品详情或作品列表；" +
                "action 取 collect（收藏）或 uncollect（取消）",
    ) { request ->
        val awemeId = request.arguments.stringArg("awemeId") ?: return@addDouMcpTool errorResult("缺少 awemeId 参数")
        val action = request.arguments.stringArg("action") ?: return@addDouMcpTool errorResult("缺少 action 参数")
        val collect =
            when (action) {
                "collect" -> true
                "uncollect" -> false
                else -> return@addDouMcpTool errorResult("action 只能是 collect 或 uncollect")
            }
        withContext(Dispatchers.IO) {
            toolCall { InteractBridge.collectAwemeJson(awemeId, collect) }
        }
    }
}
