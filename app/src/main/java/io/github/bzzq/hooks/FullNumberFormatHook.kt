package io.github.bzzq.hooks

import android.view.View
import android.widget.TextView
import io.github.bzzq.ModuleSettings
import io.github.libxposed.api.XposedInterface

/**
 */
class FullNumberFormatHook(
    targetPackageName: String,
) : BaseHook(targetPackageName) {
    override fun startHook() {
        val classLoader = context.classLoader
        val prefs = context.prefs

        hookMineHeader(context.xposed, classLoader, prefs, context.log)
        hookSpaceHeader(context.xposed, classLoader, prefs, context.log)
    }

    private fun hookMineHeader(
        xposed: XposedInterface,
        classLoader: ClassLoader,
        prefs: android.content.SharedPreferences,
        log: (String, Throwable?) -> Unit,
    ) {
        val accountMineClass = runCatching {
            Class.forName(ACCOUNT_MINE_CLASS_NAME, false, classLoader)
        }.getOrElse {
            log("AccountMine class not found for full-number hook", it)
            return
        }

        MINE_FRAGMENT_CLASS_NAMES.forEach { className ->
            runCatching {
                val fragmentClass = Class.forName(className, false, classLoader)
                val methods = fragmentClass.declaredMethods.filter { method ->
                    method.returnType == Void.TYPE &&
                        method.parameterTypes.size == 2 &&
                        method.parameterTypes[0] == accountMineClass &&
                        method.parameterTypes[1] == Boolean::class.javaPrimitiveType
                }
                methods.forEach { method ->
                    method.isAccessible = true
                    xposed.hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept { chain ->
                            val result = chain.proceed()
                            if (!ModuleSettings.isFullNumberFormatEnabled(prefs)) {
                                return@intercept result
                            }

                            val accountMine = chain.getArg(0) ?: return@intercept result
                            runCatching {
                                applyMineCounts(
                                    fragment = chain.thisObject,
                                    dynamic = getLongValue(accountMine, "dynamic", "getDynamic"),
                                    following = getLongValue(accountMine, "following", "getFollowing"),
                                    follower = getLongValue(accountMine, "follower", "getFollower"),
                                )
                            }.onFailure { log("Failed to apply full-number mine header", it) }
                            result
                        }
                }
                if (methods.isNotEmpty()) {
                    log("Installed full-number mine hook for $className", null)
                }
            }.onFailure {
                log("Failed to install full-number mine hook for $className", it)
            }
        }
    }

    private fun hookSpaceHeader(
        xposed: XposedInterface,
        classLoader: ClassLoader,
        prefs: android.content.SharedPreferences,
        log: (String, Throwable?) -> Unit,
    ) {
        runCatching {
            val fragmentClass = Class.forName(SPACE_HEADER_FRAGMENT_CLASS_NAME, false, classLoader)
            val memberCardClass = Class.forName(BILI_MEMBER_CARD_CLASS_NAME, false, classLoader)
            val methods = fragmentClass.declaredMethods.filter { method ->
                method.returnType == Void.TYPE && method.parameterTypes.contentEquals(arrayOf(memberCardClass))
            }
            methods.forEach { method ->
                method.isAccessible = true
                xposed.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept { chain ->
                        val result = chain.proceed()
                        if (!ModuleSettings.isFullNumberFormatEnabled(prefs)) {
                            return@intercept result
                        }

                        val memberCard = chain.getArg(0) ?: return@intercept result
                        runCatching {
                            applySpaceCounts(
                                fragment = chain.thisObject,
                                followers = getLongValue(memberCard, "mFollowers", "getMFollowers"),
                                followings = getLongValue(memberCard, "mFollowings", "getMFollowings"),
                                likes = getNestedLongValue(memberCard, "likes", "likeNum"),
                            )
                        }.onFailure { log("Failed to apply full-number space header", it) }
                        result
                    }
            }
            if (methods.isNotEmpty()) {
                log("Installed full-number space hook", null)
            }
        }.onFailure {
            log("Failed to install full-number space hook", it)
        }
    }

    private fun applyMineCounts(fragment: Any?, dynamic: Long?, following: Long?, follower: Long?) {
        val rootView = resolveFragmentView(fragment) ?: return
        setText(rootView, "following_count", dynamic)
        setText(rootView, "attention_count", following)
        setText(rootView, "fans_count", follower)
    }

    private fun applySpaceCounts(fragment: Any?, followers: Long?, followings: Long?, likes: Long?) {
        val rootView = resolveFragmentView(fragment) ?: return
        setText(rootView, "fans", followers)
        setText(rootView, "attentions", followings)
        setText(rootView, "likes", likes)
    }

    private fun resolveFragmentView(fragment: Any?): View? {
        if (fragment == null) return null

        invokeNoArg(fragment, "getView")?.let { return it as? View }
        getFieldValue(fragment, "mView")?.let { return it as? View }
        getFieldValue(fragment, "view")?.let { return it as? View }
        getFieldValue(fragment, "rootView")?.let { return it as? View }
        return null
    }

    private fun setText(rootView: View, idName: String, value: Long?) {
        if (value == null) return
        val context = rootView.context ?: return
        val viewId = context.resources.getIdentifier(idName, "id", context.packageName)
        if (viewId == 0) return
        val textView = rootView.findViewById<View>(viewId) as? TextView ?: return
        textView.text = value.toString()
    }

    private fun getLongValue(target: Any, fieldName: String, getterName: String): Long? {
        val methodValue = invokeNoArg(target, getterName)
        if (methodValue is Number) return methodValue.toLong()
        if (methodValue is String) return methodValue.toLongOrNull()

        val fieldValue = getFieldValue(target, fieldName)
        return when (fieldValue) {
            is Number -> fieldValue.toLong()
            is String -> fieldValue.toLongOrNull()
            else -> null
        }
    }

    private fun getNestedLongValue(target: Any, fieldName: String, nestedFieldName: String): Long? {
        val nested = getFieldValue(target, fieldName) ?: invokeNoArg(target, "get${fieldName.replaceFirstChar { it.uppercaseChar() }}")
        val nestedObject = nested ?: return null
        return getLongValue(nestedObject, nestedFieldName, "get${nestedFieldName.replaceFirstChar { it.uppercaseChar() }}")
    }

    private fun invokeNoArg(target: Any, methodName: String): Any? {
        return runCatching {
            target.javaClass.methods.firstOrNull {
                it.name == methodName && it.parameterCount == 0
            }?.invoke(target)
        }.getOrNull()
    }

    private fun getFieldValue(target: Any, fieldName: String): Any? {
        var currentClass: Class<*>? = target.javaClass
        while (currentClass != null) {
            try {
                val field = currentClass.getDeclaredField(fieldName)
                field.isAccessible = true
                return field.get(target)
            } catch (_: NoSuchFieldException) {
                currentClass = currentClass.superclass
            }
        }
        return null
    }

    private companion object {
        private const val ACCOUNT_MINE_CLASS_NAME = "tv.danmaku.bili.ui.main2.api.AccountMine"
        private const val BILI_MEMBER_CARD_CLASS_NAME = "com.bilibili.app.authorspace.api.BiliMemberCard"
        private const val SPACE_HEADER_FRAGMENT_CLASS_NAME = "com.bilibili.app.authorspace.ui.SpaceHeaderFragment2"

        private val MINE_FRAGMENT_CLASS_NAMES = listOf(
            "tv.danmaku.bili.ui.main2.mine.HomeUserCenterFragment",
            "tv.danmaku.bilibilihd.ui.main.mine.HdHomeUserCenterFragment",
        )
    }
}
