package io.github.yfishyon.doumcp.features.interact.bridge

import io.github.yfishyon.doumcp.core.SuspendInvoker
import io.github.yfishyon.doumcp.features.account.bridge.AccountBridge
import io.github.yfishyon.doumcp.features.interact.resolver.InteractResolver
import io.github.yfishyon.doumcp.features.video.resolver.VideoResolver
import org.json.JSONObject
import java.lang.reflect.InvocationTargetException

/**
 * 互动能力桥：点赞作品、收藏作品。
 *
 * 动作位沿用宿主取值：点赞 1 赞 / 0 取消；收藏 1 收藏 / 0 取消。
 * 收藏请求需要作品的真实类型，先从作品详情解析出作品对象。
 */
object InteractBridge {
    private const val DIGG_LIKE = 1
    private const val DIGG_CANCEL = 0
    private const val COLLECT_ADD = 1
    private const val COLLECT_REMOVE = 0

    /** 收藏请求的页面来源（推荐流为宿主页面枚举里的基准值） */
    private const val ENTER_FROM = "homepage_hot"

    /**
     * 点赞 / 取消点赞。
     *
     * @param awemeId 作品 ID
     * @param like true 点赞，false 取消
     */
    fun diggAwemeJson(
        awemeId: String,
        like: Boolean,
    ): String {
        if (AccountBridge.getCurrentUserId().isNullOrBlank()) return errorJson("未登录，无法点赞")
        val method = InteractResolver.resolveDiggMethod() ?: return errorJson("点赞方法定位失败（需更新特征）")
        return try {
            SuspendInvoker.invoke(method, null, listOf(awemeId, if (like) DIGG_LIKE else DIGG_CANCEL))
            JSONObject()
                .put("ok", true)
                .put("awemeId", awemeId)
                .put("digg", like)
                .toString()
        } catch (t: Throwable) {
            errorJson("点赞失败: ${causeOf(t)}")
        }
    }

    /**
     * 收藏 / 取消收藏。
     *
     * @param awemeId 作品 ID
     * @param collect true 收藏，false 取消
     */
    fun collectAwemeJson(
        awemeId: String,
        collect: Boolean,
    ): String {
        if (AccountBridge.getCurrentUserId().isNullOrBlank()) return errorJson("未登录，无法收藏")
        val detail = resolveAwemeDetail(awemeId) ?: return errorJson("作品详情获取失败")
        val api = InteractResolver.collectApi() ?: return errorJson("收藏接口创建失败")
        val method = InteractResolver.resolveCollectMethod()
        return try {
            val single =
                method.invoke(
                    api,
                    awemeId,
                    detail.awemeType,
                    if (collect) COLLECT_ADD else COLLECT_REMOVE,
                    true,
                    false,
                    0,
                    null,
                    0,
                    null,
                    false,
                    ENTER_FROM,
                    detail.authorUid,
                    0,
                    0L,
                    0L,
                    0L,
                    null,
                    null,
                    0,
                    null,
                    null,
                    null,
                )
            awaitSingle(single)
            JSONObject()
                .put("ok", true)
                .put("awemeId", awemeId)
                .put("collect", collect)
                .toString()
        } catch (t: Throwable) {
            errorJson("收藏失败: ${causeOf(t)}")
        }
    }

    /** 从作品详情取真实作品类型与作者 ID（收藏请求需要）。 */
    private fun resolveAwemeDetail(awemeId: String): AwemeDetail? {
        val method = VideoResolver.resolveDetailMethod() ?: return null
        val aweme = runCatching { method.invoke(null, awemeId, "") }.getOrNull() ?: return null
        val awemeType =
            runCatching { aweme.javaClass.getMethod("getAwemeType").invoke(aweme) as Int }.getOrNull()
                ?: return null
        val authorUid = runCatching { aweme.javaClass.getMethod("getAuthorUid").invoke(aweme) as? String }.getOrNull()
        return AwemeDetail(awemeType, authorUid)
    }

    private data class AwemeDetail(
        val awemeType: Int,
        val authorUid: String?,
    )

    /**
     * 阻塞等待 RxJava 单值结果。
     *
     * 宿主的 Single 实现类把订阅方法改了名，按参数类型（SingleObserver）定位订阅入口。
     */
    private fun awaitSingle(single: Any): Any? {
        val latch = java.util.concurrent.CountDownLatch(1)
        val result = arrayOfNulls<Any>(1)
        val failure = arrayOfNulls<Throwable>(1)
        val observerClass = Class.forName("io.reactivex.SingleObserver", false, single.javaClass.classLoader)
        val observer =
            java.lang.reflect.Proxy.newProxyInstance(observerClass.classLoader, arrayOf(observerClass)) { _, m, a ->
                when (m.name) {
                    "onSuccess" -> {
                        result[0] = a?.firstOrNull()
                        latch.countDown()
                    }

                    "onError" -> {
                        failure[0] = a?.firstOrNull() as? Throwable
                        latch.countDown()
                    }

                    else -> {
                        Unit
                    }
                }
                null
            }
        val subscribe =
            single.javaClass.methods.firstOrNull {
                it.parameterCount == 1 && it.parameterTypes[0].name == "io.reactivex.SingleObserver"
            } ?: throw IllegalStateException("Single 订阅方法未找到")
        subscribe.invoke(single, observer)
        if (!latch.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
            throw IllegalStateException("收藏请求超时")
        }
        failure[0]?.let { throw it }
        return result[0]
    }

    private fun causeOf(t: Throwable): Throwable = (t as? InvocationTargetException)?.targetException ?: t

    private fun errorJson(message: String): String = JSONObject().put("ok", false).put("error", message).toString()
}
