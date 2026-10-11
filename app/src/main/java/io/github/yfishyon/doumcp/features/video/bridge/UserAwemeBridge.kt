package io.github.yfishyon.doumcp.features.video.bridge

import io.github.yfishyon.doumcp.core.ApiPrefix
import io.github.yfishyon.doumcp.core.HostRuntime
import io.github.yfishyon.doumcp.core.Reflect
import io.github.yfishyon.doumcp.features.account.bridge.AccountBridge
import org.json.JSONArray
import org.json.JSONObject

/**
 * 用户作品列表桥：uid/secUid → 该账号的作品 / 喜欢 / 收藏列表（分页）。
 *
 * 请求构造逆向自用户主页数据源（全等证据）：
 * `API_URL_PREFIX_SI + /aweme/v1/aweme/post/`（作品）、`/aweme/v1/aweme/favorite/`（喜欢）、
 * `/aweme/v1/aweme/listcollection/`（收藏）；参数 sec_user_id（或 user_id）、max_cursor、count 等。
 * 网络走抖音自己的同步请求入口（带完整签名），响应是作品列表模型，
 * 数据在 getItems()，翻页游标 getCursor()、是否还有 getHasMore()。
 */
object UserAwemeBridge {
    private const val API_CLASS = "com.ss.android.ugc.aweme.app.api.Api"
    private const val FEED_ITEM_LIST = "com.ss.android.ugc.aweme.feed.model.FeedItemList"

    private const val POST_PATH = "/aweme/v1/aweme/post/"
    private const val FAVORITE_PATH = "/aweme/v1/aweme/favorite/"
    private const val COLLECTION_PATH = "/aweme/v1/aweme/listcollection/"

    /**
     * 获取用户发布的作品列表（分页，阻塞走网络，调用方负责后台线程）。
     *
     * @param uidOrSecUid 用户 ID 或 secUid（自动识别）
     * @param cursor 翻页游标（首页传 0，翻页传上页返回的 nextCursor）
     * @param count 每页条数
     */
    fun getUserAwemesJson(
        uidOrSecUid: String,
        cursor: Long,
        count: Int,
        keyword: String = "",
    ): String {
        if (AccountBridge.getCurrentUserId().isNullOrBlank()) return errorJson("未登录")
        if (uidOrSecUid.isBlank()) return errorJson("参数为空")
        val url =
            buildListUrl(POST_PATH, uidOrSecUid, cursor, count) +
                "&publish_video_strategy_type=2&is_other=1&need_time_list=0&page_from=2&location_permission=0"
        return feedListJson(url, keyword)
    }

    /** 获取用户的「喜欢」列表（该账号点过赞的作品，分页）。 */
    fun getLikedAwemesJson(
        uidOrSecUid: String,
        cursor: Long,
        count: Int,
    ): String = listJson(FAVORITE_PATH, uidOrSecUid, cursor, count)

    /** 获取用户的「收藏」列表（分页）。 */
    fun getCollectedAwemesJson(
        uidOrSecUid: String,
        cursor: Long,
        count: Int,
    ): String = listJson(COLLECTION_PATH, uidOrSecUid, cursor, count)

    private fun listJson(
        path: String,
        uidOrSecUid: String,
        cursor: Long,
        count: Int,
    ): String {
        if (AccountBridge.getCurrentUserId().isNullOrBlank()) return errorJson("未登录")
        if (uidOrSecUid.isBlank()) return errorJson("参数为空")
        return feedListJson(buildListUrl(path, uidOrSecUid, cursor, count), "")
    }

    private fun buildListUrl(
        path: String,
        uidOrSecUid: String,
        cursor: Long,
        count: Int,
    ): String =
        buildString {
            append(profilePrefix())
            append(path)
            append("?")
            if (uidOrSecUid.startsWith("MS4wLj")) {
                append("sec_user_id=").append(uidOrSecUid)
            } else {
                append("user_id=").append(uidOrSecUid)
            }
            append("&max_cursor=").append(cursor)
            append("&count=").append(count)
            append("&source=0")
        }

    private fun feedListJson(
        url: String,
        keyword: String,
    ): String {
        // 同步请求入口：Api 类里静态方法，签名 (String url, Class, String, 回调容器)
        val apiClass = Class.forName(API_CLASS, false, HostRuntime.requireClassLoader())
        val requestMethod =
            apiClass.declaredMethods.firstOrNull { method ->
                java.lang.reflect.Modifier
                    .isStatic(method.modifiers) &&
                    method.parameterCount == 4 &&
                    method.parameterTypes[0] == String::class.java &&
                    method.parameterTypes[1] == Class::class.java &&
                    method.parameterTypes[2] == String::class.java &&
                    method.parameterTypes[3].isInterface.not() &&
                    method.parameterTypes[3].declaredConstructors.any { it.parameterCount == 0 }
            } ?: return errorJson("请求入口未定位")

        val callbackType = requestMethod.parameterTypes[3]
        val callback = callbackType.getDeclaredConstructor().newInstance()

        val feedItemList =
            requestMethod.invoke(null, url, HostRuntime.requireClassLoader().loadClass(FEED_ITEM_LIST), null, callback)
                ?: return errorJson("请求无结果")

        val items =
            Reflect.getter(feedItemList, "getItems") as? List<*>
                ?: return errorJson("响应无作品列表")
        val result = JSONArray()
        for (item in items) {
            item ?: continue
            val summary = extractSummary(item)
            if (keyword.isEmpty() || summary.optString("desc").contains(keyword, ignoreCase = true)) {
                result.put(summary)
            }
        }
        return JSONObject()
            .put("ok", true)
            .put("nextCursor", Reflect.getter(feedItemList, "getCursor")?.toString() ?: "0")
            .put("hasMore", Reflect.getter(feedItemList, "getHasMore") == true)
            .put("awemes", result)
            .toString()
    }

    private fun profilePrefix(): String = ApiPrefix.get()

    /** 作品摘要（字段是服务端协议名；desc 的 getter 被混淆，直接读字段）。 */
    private fun extractSummary(aweme: Any): JSONObject {
        val out = JSONObject()
        out.put("awemeId", Reflect.getter(aweme, "getAid")?.toString() ?: "")
        (Reflect.field(aweme, "desc") as? String)?.let { if (it.isNotEmpty()) out.put("desc", it) }
        (Reflect.field(aweme, "createTime") as? Number)?.let {
            out.put(
                "createTime",
                java.text
                    .SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date(it.toLong() * 1000)),
            )
        }
        (Reflect.field(aweme, "statistics"))?.let { stats ->
            val parsed = VideoBridge.parseStatistics(stats)
            if (parsed.length() > 0) out.put("statistics", parsed)
        }
        return out
    }

    private fun errorJson(message: String): String = JSONObject().put("ok", false).put("error", message).toString()
}
