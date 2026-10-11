package io.github.yfishyon.doumcp.core

import java.lang.reflect.Modifier

/**
 * 宿主 Retrofit 工厂：Retrofit 服务入口 → 按主站域名建 Retrofit → 生成接口代理。
 *
 * 与宿主自身的请求同一条链路，公共参数与请求签名由宿主的拦截器补齐；
 * 只要接口类名未混淆、端点写在注解里，就能直接生成代理。
 */
object HostRetrofit {
    private const val RETROFIT_SERVICE = "com.ss.android.ugc.aweme.services.RetrofitService"
    private const val I_RETROFIT_SERVICE = "com.ss.android.ugc.aweme.framework.services.IRetrofitService"
    private const val I_RETROFIT = "com.ss.android.ugc.aweme.framework.services.IRetrofit"
    private const val COMMON_CONSTANTS = "com.ss.android.constants.CommonConstants"

    /** 生成指定接口的代理实例；失败返回 null。 */
    fun create(apiClass: Class<*>): Any? =
        runCatching {
            val service = service()
            val prefix =
                HostRuntime
                    .hostClass(COMMON_CONSTANTS)
                    .getField("API_URL_PREFIX_SI")
                    .get(null) as String
            val retrofit =
                service.javaClass
                    .getMethod("createNewRetrofit", String::class.java)
                    .invoke(service, prefix)
            HostRuntime
                .hostClass(I_RETROFIT)
                .methods
                .first { it.name == "create" && it.parameterCount == 1 }
                .invoke(retrofit, apiClass)
        }.getOrElse {
            ModLog.e("宿主 Retrofit 创建失败", it.cause ?: it)
            null
        }

    /** Retrofit 服务入口（宿主静态方法，返回服务接口实现）。 */
    private fun service(): Any {
        val entry =
            HostRuntime.hostClass(RETROFIT_SERVICE).declaredMethods.firstOrNull {
                Modifier.isStatic(it.modifiers) &&
                    it.parameterCount == 0 &&
                    it.returnType.name == I_RETROFIT_SERVICE
            } ?: throw IllegalStateException("Retrofit 服务入口未找到")
        return entry.invoke(null) ?: throw IllegalStateException("Retrofit 服务为空")
    }
}
