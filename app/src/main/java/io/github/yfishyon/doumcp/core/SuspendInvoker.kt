package io.github.yfishyon.doumcp.core

import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 阻塞调用宿主挂起方法（签名以 kotlin.coroutines.Continuation 收尾）。
 *
 * 宿主与模块各自打包 Kotlin 运行时，模块侧的 Continuation 过不了宿主方法的参数类型检查——
 * 必须动态代理**宿主 classloader** 的 Continuation，协程上下文取宿主侧的空上下文。
 * 挂起标记按枚举 name 判定（宿主侧被 R8 改名，不能用引用或类名比较）。
 */
object SuspendInvoker {
    /** 单次调用的等待上限（秒） */
    private const val TIMEOUT_SEC = 30L

    /**
     * 调用挂起方法并等待结果。
     *
     * @param method 目标挂起方法（最后一个参数类型为 Continuation）
     * @param instance 实例方法传宿主对象，静态方法传 null
     * @param args 业务参数（不含 Continuation，由本方法补）
     */
    fun invoke(
        method: Method,
        instance: Any?,
        args: List<Any?>,
    ): Any? {
        val classLoader = method.declaringClass.classLoader
        val continuationType = method.parameterTypes.last()

        val hostEmptyContext =
            runCatching {
                classLoader
                    ?.loadClass("kotlin.coroutines.EmptyCoroutineContext")
                    ?.getField("INSTANCE")
                    ?.get(null)
            }.getOrNull()

        var outcome: Any? = null
        val latch = CountDownLatch(1)

        val continuation =
            Proxy.newProxyInstance(
                continuationType.classLoader,
                arrayOf(continuationType),
            ) { _, m, a ->
                when (m.name) {
                    "resumeWith" -> {
                        outcome = a[0]
                        latch.countDown()
                        null
                    }

                    "getContext" -> {
                        hostEmptyContext
                    }

                    else -> {
                        null
                    }
                }
            }

        val direct =
            runCatching { method.invoke(instance, *args.toTypedArray(), continuation) }
                .getOrElse {
                    ModLog.e("挂起方法调用异常", it.cause ?: it)
                    return null
                }
        val suspended = direct is Enum<*> && direct.name == "COROUTINE_SUSPENDED"
        if (!suspended) return direct

        if (!latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)) {
            ModLog.e("挂起调用超时")
            return null
        }
        return outcome
    }
}
