package io.github.yfishyon.doumcp.core

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 工具入参 schema 声明：把参数类型显式告诉 MCP 客户端。
 *
 * 不声明时服务端下发空 schema（`{"type":"object"}`），客户端只能从描述文本自行猜参数类型，
 * 对象/数组参数会被压成 JSON 字符串导致收不到；显式声明后客户端按 schema 原样传 JSON。
 * 含对象/数组参数的工具都应声明；纯标量参数的工具声明与否不影响传参。
 */
internal fun inputSchema(
    properties: Map<String, JsonObject>,
    required: List<String> = emptyList(),
): ToolSchema = ToolSchema(null, JsonObject(properties), required.ifEmpty { null })

internal fun stringProp(): JsonObject = buildJsonObject { put("type", "string") }

internal fun intProp(): JsonObject = buildJsonObject { put("type", "integer") }

internal fun boolProp(): JsonObject = buildJsonObject { put("type", "boolean") }

/** 任意 JSON（表达式或标量）。 */
internal fun anyProp(): JsonObject = JsonObject(emptyMap())

/** 自由键的对象。 */
internal fun objectProp(): JsonObject = buildJsonObject { put("type", "object") }

/** 字符串数组；nullableItems=true 时元素可为 null（通配语义）。 */
internal fun stringArrayProp(nullableItems: Boolean = false): JsonObject =
    buildJsonObject {
        put("type", "array")
        put(
            "items",
            if (nullableItems) {
                buildJsonObject {
                    put(
                        "type",
                        buildJsonArray {
                            add(JsonPrimitive("string"))
                            add(JsonPrimitive("null"))
                        },
                    )
                }
            } else {
                buildJsonObject { put("type", "string") }
            },
        )
    }

/** 元素为任意 JSON 的数组（表达式参数表）。 */
internal fun anyArrayProp(): JsonObject =
    buildJsonObject {
        put("type", "array")
        put("items", JsonObject(emptyMap()))
    }

internal fun numberArrayProp(): JsonObject =
    buildJsonObject {
        put("type", "array")
        put("items", buildJsonObject { put("type", "number") })
    }
