package io.github.bzzq.hooks

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Field

/**
 * Hook to unlock video features such as trial quality and VIP-only streams.
 * Re-implemented based on the functionality of the TQSA project.
 */
class VideoFeatureUnlockHook(
    override val targetPackageName: String,
) : AppHook {

    override fun install(xposed: XposedInterface, packageReady: PackageReadyParam, log: (String, Throwable?) -> Unit) {
        val classLoader = packageReady.getClassLoader()

        // 1. Enable trial quality in SceneControl
        hookSetBoolean(xposed, classLoader, SCENE_CONTROL_CLASS, SET_IS_NEED_TRIAL, true, log)
        hookGetBoolean(xposed, classLoader, SCENE_CONTROL_CLASS, GET_IS_NEED_TRIAL, true, log)

        // 2. Enable trial quality in VideoVod
        hookSetBoolean(xposed, classLoader, VIDEO_VOD_CLASS, SET_IS_NEED_TRIAL, true, log)
        hookGetBoolean(xposed, classLoader, VIDEO_VOD_CLASS, GET_IS_NEED_TRIAL, true, log)

        // 3. Bypass VIP requirements in StreamInfo (various versions)
        STREAM_INFO_CLASSES.forEach { className ->
            hookRedirectFieldToMethod(xposed, classLoader, className, GET_VIP_FREE, NEED_VIP_FIELD, log)
            hookGetBoolean(xposed, classLoader, className, GET_NEED_VIP, false, log)
        }
    }

    private fun hookGetBoolean(
        xposed: XposedInterface,
        cl: ClassLoader,
        className: String,
        methodName: String,
        fixedValue: Boolean,
        log: (String, Throwable?) -> Unit
    ) {
        runCatching {
            val clazz = Class.forName(className, false, cl)
            val method = clazz.getDeclaredMethod(methodName)
            xposed.hook(method).intercept { fixedValue }
            log("Installed constant return hook: $className.$methodName() -> $fixedValue", null)
        }.onFailure {
            // Silently fail if class or method is not found
        }
    }

    private fun hookSetBoolean(
        xposed: XposedInterface,
        cl: ClassLoader,
        className: String,
        methodName: String,
        forcedValue: Boolean,
        log: (String, Throwable?) -> Unit
    ) {
        runCatching {
            val clazz = Class.forName(className, false, cl)
            val method = clazz.getDeclaredMethod(methodName, Boolean::class.javaPrimitiveType)
            xposed.hook(method).intercept { chain ->
                chain.proceed(arrayOf(forcedValue))
            }
            log("Installed argument force hook: $className.$methodName($forcedValue)", null)
        }.onFailure {
            // Silently fail
        }
    }

    private fun hookRedirectFieldToMethod(
        xposed: XposedInterface,
        cl: ClassLoader,
        className: String,
        methodName: String,
        fieldName: String,
        log: (String, Throwable?) -> Unit
    ) {
        runCatching {
            val clazz = Class.forName(className, false, cl)
            val method = clazz.getDeclaredMethod(methodName)
            val field = findField(clazz, fieldName)?.apply { isAccessible = true } ?: return@runCatching
            xposed.hook(method).intercept { chain ->
                val instance = chain.thisObject
                if (instance != null) {
                    field.get(instance)
                } else {
                    chain.proceed()
                }
            }
            log("Installed field redirection hook: $className.$methodName() -> field $fieldName", null)
        }.onFailure {
            // Silently fail
        }
    }

    private fun findField(clazz: Class<*>, fieldName: String): Field? {
        var current: Class<*>? = clazz
        while (current != null) {
            runCatching {
                return current.getDeclaredField(fieldName)
            }
            current = current.superclass
        }
        return null
    }

    private companion object {
        private const val SCENE_CONTROL_CLASS = "com.bapis.bilibili.pgc.gateway.player.v2.SceneControl"
        private const val VIDEO_VOD_CLASS = "com.bapis.bilibili.playershared.VideoVod"
        private val STREAM_INFO_CLASSES = listOf(
            "com.bapis.bilibili.app.playurl.v1.StreamInfo",
            "com.bapis.bilibili.playershared.StreamInfo"
        )

        private const val SET_IS_NEED_TRIAL = "setIsNeedTrial"
        private const val GET_IS_NEED_TRIAL = "getIsNeedTrial"
        private const val GET_VIP_FREE = "getVipFree"
        private const val GET_NEED_VIP = "getNeedVip"
        private const val NEED_VIP_FIELD = "needVip_"
    }
}
