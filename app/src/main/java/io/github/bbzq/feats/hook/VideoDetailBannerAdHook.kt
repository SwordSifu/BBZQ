package io.github.bbzq.feats.hook

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Space
import io.github.bbzq.ModuleSettings
import io.github.bbzq.ModuleSettingsBridge
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.from
import io.github.bbzq.feats.hookAfter
import io.github.bbzq.feats.hookBefore
import io.github.bbzq.feats.methodsNamed
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.IdentityHashMap

class VideoDetailBannerAdHook(env: RoamingEnv) : BaseRoamingHook(env) {
    private val videoDetailProxies = IdentityHashMap<Any, Any>()
    private val underPlayerProxies = IdentityHashMap<Any, Any>()
    private val relateProxies = IdentityHashMap<Any, Any>()
    private val merchandiseProxies = IdentityHashMap<Any, Any>()
    private var blockedCount = 0

    override fun startHook() {
        if (env.processName != env.packageName) return
        val enabled = ModuleSettings.isBlockVideoDetailBannerAdEnabled(prefs)
        if (!enabled) {
            log("startHook: VideoDetailBannerAd disabled, provider=${ModuleSettingsBridge.lastProviderStatus}")
            return
        }

        var installed = 0
        if (installGAdVideoDetailProxy()) installed++
        installed += installRelateGameComponentBlock()
        if (installed == 0) {
            log("startHook: VideoDetailBannerAd no hook point found")
        }
    }

    private fun installGAdVideoDetailProxy(): Boolean {
        val bizKt = G_AD_BIZ_KT.from(classLoader)
        val videoDetailType = G_AD_VIDEO_DETAIL.from(classLoader)
        val underPlayerType = I_AD_UNDER_PLAYER.from(classLoader)
        val relateType = I_AD_VIDEO_RELATE.from(classLoader)
        val merchandiseType = I_AD_MERCHANDISE.from(classLoader)
        if (bizKt == null || videoDetailType == null || underPlayerType == null) {
            log(
                "startHook: VideoDetailBannerAd missing " +
                    "bizKt=$bizKt videoDetail=$videoDetailType underPlayer=$underPlayerType",
            )
            return false
        }

        val getVideoDetail = bizKt.methodsNamed("getGAdVideoDetail")
            .firstOrNull {
                it.parameterCount == 0 &&
                    Modifier.isStatic(it.modifiers) &&
                    videoDetailType.isAssignableFrom(it.returnType)
            }
        if (getVideoDetail == null) {
            log("startHook: VideoDetailBannerAd no hook point found")
            return false
        }

        env.hookAfter(getVideoDetail) { param ->
            runCatching {
                val original = param.result ?: return@runCatching
                if (!videoDetailType.isInstance(original)) return@runCatching
                param.result = videoDetailProxy(
                    original = original,
                    videoDetailType = videoDetailType,
                    underPlayerType = underPlayerType,
                    relateType = relateType,
                    merchandiseType = merchandiseType,
                )
            }.onFailure {
                log("VideoDetailBannerAd hook failed at ${getVideoDetail.declaringClass.name}.${getVideoDetail.name}", it)
            }
        }
        log(
            "startHook: VideoDetailBannerAd at ${getVideoDetail.declaringClass.name}.${getVideoDetail.name}, " +
                "relate=${relateType != null} merchandise=${merchandiseType != null}",
        )
        return true
    }

    private fun installRelateGameComponentBlock(): Int {
        val baseComponent = GEMINI_BINDING_COMPONENT.from(classLoader)
        val simpleViewEntry = GEMINI_SIMPLE_VIEW_ENTRY.from(classLoader)
        val unit = KOTLIN_UNIT.from(classLoader)
            ?.getDeclaredField("INSTANCE")
            ?.apply { isAccessible = true }
            ?.get(null)
        if (baseComponent == null || simpleViewEntry == null || unit == null) {
            log(
                "startHook: VideoDetailBannerAd relate game missing " +
                    "component=$baseComponent viewEntry=$simpleViewEntry unit=$unit",
            )
            return 0
        }
        val simpleViewEntryConstructor = runCatching {
            simpleViewEntry.getDeclaredConstructor(View::class.java)
                .apply { isAccessible = true }
        }.getOrElse {
            log("startHook: VideoDetailBannerAd relate game view entry constructor missing: ${it.message}")
            return 0
        }

        val createViewEntry = baseComponent.methodsNamed("createViewEntry")
            .firstOrNull {
                it.parameterCount == 2 &&
                    it.parameterTypes[0] == Context::class.java &&
                    it.parameterTypes[1] == ViewGroup::class.java
            }
        val bindToView = baseComponent.methodsNamed("bindToView")
            .firstOrNull {
                it.parameterCount == 2 &&
                    it.parameterTypes[1].name == "kotlin.coroutines.Continuation"
            }
        if (createViewEntry == null || bindToView == null) {
            log(
                "startHook: VideoDetailBannerAd relate game no hook point " +
                    "create=$createViewEntry bind=$bindToView",
            )
            return 0
        }

        env.hookBefore(createViewEntry) { param ->
            runCatching {
                if (!isRelateGameComponent(param.thisObject)) return@runCatching
                val context = param.args.getOrNull(0) as? Context ?: return@runCatching
                val emptyEntry = createEmptyViewEntry(simpleViewEntryConstructor, context) ?: return@runCatching
                logBlocked("getRelateGameView")
                param.result = emptyEntry
            }.onFailure {
                log("VideoDetailBannerAd relate createViewEntry failed", it)
            }
        }
        env.hookBefore(bindToView) { param ->
            runCatching {
                if (!isRelateGameComponent(param.thisObject)) return@runCatching
                param.result = unit
            }.onFailure {
                log("VideoDetailBannerAd relate bindToView failed", it)
            }
        }
        log("startHook: VideoDetailBannerAd relate game at ${baseComponent.name}.createViewEntry/bindToView")
        return 2
    }

    private fun videoDetailProxy(
        original: Any,
        videoDetailType: Class<*>,
        underPlayerType: Class<*>,
        relateType: Class<*>?,
        merchandiseType: Class<*>?,
    ): Any = synchronized(videoDetailProxies) {
        videoDetailProxies.getOrPut(original) {
            Proxy.newProxyInstance(
                original.javaClass.classLoader ?: classLoader,
                collectProxyInterfaces(original, videoDetailType),
                InvocationHandler { proxy, method, args ->
                    runCatching {
                        when {
                            method.isObjectMethod("toString", 0) ->
                                "BBZQVideoDetailProxy(${original.javaClass.name})"
                            method.isObjectMethod("hashCode", 0) ->
                                System.identityHashCode(proxy)
                            method.isObjectMethod("equals", 1) ->
                                proxy === args?.firstOrNull()
                            method.name == "getUnderPlayer" && method.parameterCount == 0 -> {
                                val underPlayer = invokeOriginal(original, method, args) ?: return@runCatching null
                                underPlayerProxy(underPlayer, underPlayerType)
                            }
                            method.name == "getRelate" && method.parameterCount == 0 && relateType != null -> {
                                val relate = invokeOriginal(original, method, args) ?: return@runCatching null
                                if (relateType.isInstance(relate)) relateProxy(relate, relateType) else relate
                            }
                            method.name == "getMerchandise" &&
                                method.parameterCount == 0 &&
                                merchandiseType != null -> {
                                val merchandise = invokeOriginal(original, method, args) ?: return@runCatching null
                                if (merchandiseType.isInstance(merchandise)) {
                                    merchandiseProxy(merchandise, merchandiseType)
                                } else {
                                    merchandise
                                }
                            }
                            else ->
                                invokeOriginal(original, method, args)
                        }
                    }.getOrElse {
                        log("VideoDetailBannerAd videoDetail proxy failed at ${method.declaringClass.name}.${method.name}", it)
                        invokeOriginal(original, method, args)
                    }
                },
            )
        }
    }

    private fun underPlayerProxy(original: Any, underPlayerType: Class<*>): Any =
        synchronized(underPlayerProxies) {
            underPlayerProxies.getOrPut(original) {
                Proxy.newProxyInstance(
                    original.javaClass.classLoader ?: classLoader,
                    collectProxyInterfaces(original, underPlayerType),
                    InvocationHandler { proxy, method, args ->
                        runCatching {
                            when {
                                method.isObjectMethod("toString", 0) ->
                                    "BBZQUnderPlayerProxy(${original.javaClass.name})"
                                method.isObjectMethod("hashCode", 0) ->
                                    System.identityHashCode(proxy)
                                method.isObjectMethod("equals", 1) ->
                                    proxy === args?.firstOrNull()
                                method.name in BLOCKED_METHODS -> {
                                    logBlocked(method.name)
                                    null
                                }
                                else ->
                                    invokeOriginal(original, method, args)
                            }
                        }.getOrElse {
                            log("VideoDetailBannerAd underPlayer proxy failed at ${method.declaringClass.name}.${method.name}", it)
                            invokeOriginal(original, method, args)
                        }
                    },
                )
            }
        }

    private fun relateProxy(original: Any, relateType: Class<*>): Any =
        synchronized(relateProxies) {
            relateProxies.getOrPut(original) {
                Proxy.newProxyInstance(
                    original.javaClass.classLoader ?: classLoader,
                    collectProxyInterfaces(original, relateType),
                    InvocationHandler { proxy, method, args ->
                        runCatching {
                            when {
                                method.isObjectMethod("toString", 0) ->
                                    "BBZQRelateProxy(${original.javaClass.name})"
                                method.isObjectMethod("hashCode", 0) ->
                                    System.identityHashCode(proxy)
                                method.isObjectMethod("equals", 1) ->
                                    proxy === args?.firstOrNull()
                                method.name == "getAdRelateView" -> {
                                    logBlocked(method.name)
                                    null
                                }
                                else ->
                                    invokeOriginal(original, method, args)
                            }
                        }.getOrElse {
                            log("VideoDetailBannerAd relate proxy failed at ${method.declaringClass.name}.${method.name}", it)
                            invokeOriginal(original, method, args)
                        }
                    },
                )
            }
        }

    private fun merchandiseProxy(original: Any, merchandiseType: Class<*>): Any =
        synchronized(merchandiseProxies) {
            merchandiseProxies.getOrPut(original) {
                Proxy.newProxyInstance(
                    original.javaClass.classLoader ?: classLoader,
                    collectProxyInterfaces(original, merchandiseType),
                    InvocationHandler { proxy, method, args ->
                        runCatching {
                            when {
                                method.isObjectMethod("toString", 0) ->
                                    "BBZQMerchandiseProxy(${original.javaClass.name})"
                                method.isObjectMethod("hashCode", 0) ->
                                    System.identityHashCode(proxy)
                                method.isObjectMethod("equals", 1) ->
                                    proxy === args?.firstOrNull()
                                method.name == "getAdMerchandiseView" -> {
                                    logBlocked(method.name)
                                    null
                                }
                                else ->
                                    invokeOriginal(original, method, args)
                            }
                        }.getOrElse {
                            log("VideoDetailBannerAd merchandise proxy failed at ${method.declaringClass.name}.${method.name}", it)
                            invokeOriginal(original, method, args)
                        }
                    },
                )
            }
        }

    private fun invokeOriginal(target: Any, method: Method, args: Array<Any?>?): Any? =
        try {
            if (args == null) method.invoke(target) else method.invoke(target, *args)
        } catch (throwable: InvocationTargetException) {
            throw throwable.targetException ?: throwable
        }

    private fun Method.isObjectMethod(name: String, parameterCount: Int): Boolean =
        declaringClass == Any::class.java && this.name == name && this.parameterCount == parameterCount

    private fun collectProxyInterfaces(original: Any, primaryType: Class<*>): Array<Class<*>> =
        buildSet {
            add(primaryType)
            original.javaClass.interfaces.forEach(::add)
            original.javaClass.takeIf { it.isInterface }?.let(::add)
        }.toTypedArray()

    private fun isRelateGameComponent(value: Any?): Boolean =
        value?.javaClass?.name == RELATE_GAME_COMPONENT

    private fun createEmptyViewEntry(entryConstructor: Constructor<*>, context: Context): Any? {
        val view = Space(context).apply {
            visibility = View.GONE
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)
        }
        return runCatching {
            entryConstructor.newInstance(view)
        }.getOrNull()
    }

    private fun logBlocked(methodName: String) {
        val count = ++blockedCount
        if (count <= 20 || count % 20 == 0) {
            log("VideoDetailBannerAd blocked $methodName count=$count")
        }
    }

    private companion object {
        private const val G_AD_BIZ_KT = "com.bilibili.gripper.api.ad.biz.GAdBizKt"
        private const val G_AD_VIDEO_DETAIL = "com.bilibili.gripper.api.ad.biz.GAdVideoDetail"
        private const val I_AD_UNDER_PLAYER =
            "com.bilibili.gripper.api.ad.biz.videodetail.underplayer.IAdUnderPlayer"
        private const val I_AD_VIDEO_RELATE =
            "com.bilibili.gripper.api.ad.biz.videodetail.relate.IAdVideoRelate"
        private const val I_AD_MERCHANDISE =
            "com.bilibili.gripper.api.ad.biz.videodetail.merchandise.IAdMerchandise"
        private const val GEMINI_BINDING_COMPONENT = "com.bilibili.app.gemini.ui.m"
        private const val GEMINI_SIMPLE_VIEW_ENTRY = "com.bilibili.app.gemini.ui.UIComponent\$b"
        private const val KOTLIN_UNIT = "kotlin.Unit"
        private const val RELATE_GAME_COMPONENT =
            "com.bilibili.ship.theseus.united.page.intro.module.relate.game.g"
        private val BLOCKED_METHODS = setOf("getUpperAdView", "getUpperHDView", "getUpperNestView")
    }
}

