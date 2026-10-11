package io.github.yfishyon.doumcp.features.interact.resolver

import io.github.yfishyon.doumcp.core.DexKitSupport
import io.github.yfishyon.doumcp.core.HostRetrofit
import io.github.yfishyon.doumcp.core.HostRuntime
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Method

/**
 * 互动域定位器：点赞、收藏。
 *
 * 点赞走宿主自有的跨平台网络封装（类名混淆，靠端点字符串全等定位）；
 * 收藏接口类名、方法名、端点均未混淆，接口方法直接反射、实例由宿主 Retrofit 工厂生成。
 */
object InteractResolver {
    private const val DIGG_PATH = "https://aweme.snssdk.com/aweme/v1/commit/item/digg/"
    private const val COLLECT_API = "com.ss.android.ugc.aweme.platform.collect.imp.aweme.AwemeCollectRequester\$IApi"
    private const val COLLECT_METHOD = "collectAwemeByAwemeType"

    @Volatile
    private var collectApi: Any? = null

    /** 点赞方法：(作品 ID, 动作) → 挂起结果。 */
    fun resolveDiggMethod(): Method? =
        DexKitSupport.resolveCached("interact_digg") { dexKit ->
            dexKit
                .findClass {
                    matcher { usingStrings(listOf(DIGG_PATH), StringMatchType.Equals) }
                }.findMethod {
                    matcher {
                        paramTypes("java.lang.String", "int", "kotlin.coroutines.Continuation")
                        returnType = "java.lang.Object"
                    }
                }.firstOrNull()
                ?.getMethodInstance(HostRuntime.requireClassLoader())
        }

    /** 收藏方法：(作品 ID, 作品类型, 动作, …) → Single（动作位：1 收藏 / 0 取消）。 */
    fun resolveCollectMethod(): Method = HostRuntime.hostClass(COLLECT_API).methods.first { it.name == COLLECT_METHOD }

    /** 收藏接口实例（进程内复用）。 */
    fun collectApi(): Any? {
        collectApi?.let { return it }
        synchronized(this) {
            collectApi?.let { return it }
            val instance = HostRetrofit.create(HostRuntime.hostClass(COLLECT_API)) ?: return null
            collectApi = instance
            return instance
        }
    }
}
