package io.github.bbzq.feats.hook

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.bbzq.ModuleSettings
import io.github.bbzq.SkipVideoAdMode
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.BilibiliSponsorBlock
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.allMethods
import io.github.bbzq.feats.callMethod
import io.github.bbzq.feats.hookAfter
import io.github.bbzq.feats.hookBefore
import io.github.bbzq.feats.symbol.RestoredSkipVideoAdSymbols
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class SkipVideoAdHook(env: RoamingEnv) : BaseRoamingHook(env) {
    @Volatile private var lastSeekTime = 0L
    @Volatile private var duration = -1L
    @Volatile private var bvid = ""
    @Volatile private var cid = ""
    @Volatile private var playbackKey = ""
    private val autoSkippedSegments = ConcurrentHashMap.newKeySet<String>()
    private val manualNotifiedSegments = ConcurrentHashMap.newKeySet<String>()
    private val pendingAutoLikeVideos = ConcurrentHashMap.newKeySet<String>()

    private var waitTime = CHECK_INTERVAL_MS
    private var playerCoreServiceRef: WeakReference<Any>? = null
    private var cardPlayerContextRef: WeakReference<Any>? = null
    private var storyPlayerRef: WeakReference<Any>? = null
    private val reflectionFailureLogs = ConcurrentHashMap.newKeySet<String>()
    private val noArgMethods = ConcurrentHashMap<String, Method>()
    private val missingNoArgMethods = ConcurrentHashMap.newKeySet<String>()
    private val seekMethodsByControllerClass = ConcurrentHashMap<Class<*>, List<Method>>()
    private val playerCoreService: Any?
        get() = playerCoreServiceRef?.get()
    private val cardPlayerContext: Any?
        get() = cardPlayerContextRef?.get()
    private val storyPlayer: Any?
        get() = storyPlayerRef?.get()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    override fun startHook() {
        val config = ModuleSettings.refreshSkipVideoAdCache(prefs)
        if (!config.enabled) return
        if (config.autoLikeEnabled) {
            SkipVideoAdAutoLike.install(env)
        }
        val symbols = env.symbols?.skipVideoAd?.restore(classLoader)
        if (symbols == null) {
            log("startHook: SkipVideoAd skipped because symbols are unavailable")
            if (env.processName == env.packageName) {
                toast("跳过视频广告未找到播放器接口")
            }
            return
        }
        ensureActivityTracking()
        cacheSeekMethods(symbols)
        val count = installHookGroup("playView") { hookPlayViewUnite(symbols) } +
            installHookGroup("playerCore") { hookPlayerCoreService(symbols) } +
            installHookGroup("cardPlayer") { hookCardPlayerContext(symbols) } +
            installHookGroup("storyPlayer") { hookStoryPlayer(symbols) }
        log("startHook: SkipVideoAd, methods=$count")
        if (count == 0 && env.processName == env.packageName) {
            toast("跳过视频广告未找到播放器接口")
        }
    }

    private fun installHookGroup(label: String, block: () -> Int): Int =
        runCatching(block).getOrElse {
            log("SkipVideoAd $label hook group failed", it)
            0
        }

    private fun ensureActivityTracking() {
        val application = env.hostContext as? Application ?: return
        if (!callbacksRegistered.compareAndSet(false, true)) return
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

                override fun onActivityStarted(activity: Activity) = Unit

                override fun onActivityResumed(activity: Activity) {
                    topActivity = WeakReference(activity)
                }

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivityStopped(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

                override fun onActivityDestroyed(activity: Activity) {
                    if (topActivity?.get() === activity) {
                        topActivity = null
                    }
                }
            },
        )
    }

    private fun hookPlayViewUnite(symbols: RestoredSkipVideoAdSymbols): Int {
        var count = 0
        symbols.playViewMethods.forEach { method ->
            count += runCatching {
                env.hookBefore(method) { param ->
                    runCatching {
                        updateVideoIdentityFromRequest(param.args.firstOrNull())
                        if (method.name != "playViewUnite") return@runCatching
                        val handler = param.args.getOrNull(1) ?: return@runCatching
                        val wrapped = wrapResponseHandlerIfNeeded(handler)
                        if (wrapped !== handler) {
                            param.args[1] = wrapped
                        }
                    }.onFailure {
                        log("SkipVideoAd play view hook failed at ${method.declaringClass.name}.${method.name}", it)
                    }
                }
                1
            }.getOrElse {
                log("SkipVideoAd failed to hook ${method.declaringClass.name}.${method.name}", it)
                0
            }
        }
        return count
    }

    private fun wrapResponseHandlerIfNeeded(handler: Any): Any {
        val handlerClass = handler.javaClass.interfaces.firstOrNull { type ->
            type.safeAllMethods("play view response").any { it.name == "onNext" && it.parameterCount == 1 }
        } ?: return handler
        return Proxy.newProxyInstance(
            handler.javaClass.classLoader ?: classLoader,
            collectProxyInterfaces(handler, handlerClass),
        ) { _, method, args ->
            runCatching {
                if (method.name == "onNext") {
                    updateVideoIdentityFromReply(args?.firstOrNull())
                }
            }.onFailure {
                log("SkipVideoAd response proxy failed at ${method.declaringClass.name}.${method.name}", it)
            }

            if (args == null) {
                method.invoke(handler)
            } else {
                method.invoke(handler, *args)
            }
        }
    }

    private fun updateVideoIdentityFromRequest(req: Any?) {
        if (!isEnabled() || req == null) return
        var nextBvid = req.callMethod("getBvid") as? String ?: ""
        val vod = req.callMethod("getVod") ?: return
        if (nextBvid.isEmpty()) {
            val aid = vod.callMethod("getAid").asLong() ?: return
            if (aid == -1L) return
            nextBvid = SkipVideoAdState.bvidFromAid(aid)
        }
        val nextCid = vod.callMethod("getCid").asLong()?.toString() ?: return
        updateVideoIdentity(nextBvid, nextCid)
    }

    private fun updateVideoIdentityFromReply(reply: Any?) {
        if (!isEnabled()) return
        val playArc = reply?.callMethod("getPlayArc") ?: return
        val aid = playArc.callMethod("getAid").asLong() ?: return
        if (aid == -1L) return
        val nextCid = playArc.callMethod("getCid").asLong()?.toString() ?: return
        updateVideoIdentity(SkipVideoAdState.bvidFromAid(aid), nextCid)
    }

    private fun updateVideoIdentity(nextBvid: String, nextCid: String, fetchSegments: Boolean = true) {
        val identity = SkipVideoAdState.resolveVideoIdentity(nextBvid, nextCid) ?: return
        if (identity.bvid == bvid && identity.cid == cid) return

        bvid = identity.bvid
        cid = identity.cid
        playbackKey = identity.key
        duration = -1L
        SkipVideoAdState.activateVideo(identity)
        playerCoreService?.let { SkipVideoAdState.bindController(it, identity.key) }
        cardPlayerContext?.let { SkipVideoAdState.bindController(it, identity.key) }
        storyPlayer?.let { SkipVideoAdState.bindController(it, identity.key) }
        autoSkippedSegments.clear()
        manualNotifiedSegments.clear()
        waitTime = CHECK_INTERVAL_MS
        if (fetchSegments) {
            fetchSegmentsIfNeeded()
        }
    }

    private fun hookPlayerCoreService(symbols: RestoredSkipVideoAdSymbols): Int {
        return hookCurrentPositionMethods(
            methods = symbols.playerCoreCurrentPositionMethods,
            stateMethodNames = STATE_METHOD_NAMES,
            controllerKind = ControllerKind.PLAYER_CORE,
        ) + hookPlayerStateMethods(
            methods = symbols.playerCoreStateMethods,
            stateMethodNames = STATE_METHOD_NAMES,
            controllerKind = ControllerKind.PLAYER_CORE,
        )
    }

    private fun hookCardPlayerContext(symbols: RestoredSkipVideoAdSymbols): Int {
        return hookCurrentPositionMethods(
            methods = symbols.cardCurrentPositionMethods,
            stateMethodNames = CARD_STATE_METHOD_NAMES,
            controllerKind = ControllerKind.CARD,
        ) + hookPlayerStateMethods(
            methods = symbols.cardStateMethods,
            stateMethodNames = CARD_STATE_METHOD_NAMES,
            controllerKind = ControllerKind.CARD,
        )
    }

    private fun hookStoryPlayer(symbols: RestoredSkipVideoAdSymbols): Int {
        return hookCurrentPositionMethods(
            methods = symbols.storyCurrentPositionMethods,
            stateMethodNames = STORY_STATE_METHOD_NAMES,
            controllerKind = ControllerKind.STORY,
        ) + hookPlayerStateMethods(
            methods = symbols.storyStateMethods,
            stateMethodNames = STORY_STATE_METHOD_NAMES,
            controllerKind = ControllerKind.STORY,
        )
    }

    private fun cacheSeekMethods(symbols: RestoredSkipVideoAdSymbols) {
        seekMethodsByControllerClass.clear()
        (symbols.playerCoreSeekMethods + symbols.cardSeekMethods + symbols.storySeekMethods)
            .groupBy { it.declaringClass }
            .forEach { (type, methods) ->
                seekMethodsByControllerClass[type] = methods.distinctBy(Method::toGenericString)
            }
    }

    private fun hookCurrentPositionMethods(
        methods: List<Method>,
        stateMethodNames: Set<String>,
        controllerKind: ControllerKind,
    ): Int {
        var count = 0
        methods.forEach { method ->
            runCatching {
                env.hookAfter(method) { param ->
                    runCatching {
                        if (!isEnabled()) return@runCatching
                        val controller = param.thisObject ?: return@runCatching
                        val key = rememberPlayerController(controller, controllerKind) ?: return@runCatching
                        if (duration <= 0) {
                            duration = resolveDuration(controller)
                            if (duration > 0) {
                                SkipVideoAdState.updateDuration(key, duration)
                            }
                        }
                        if (controllerKind != ControllerKind.STORY) {
                            fetchSegmentsIfNeeded()
                        }
                        val position = param.result.asLong() ?: return@runCatching
                        val state = resolveState(controller, stateMethodNames)
                        if (state in RESET_PLAYER_STATES) {
                            resetPlaybackState(fetchImmediately = false)
                            return@runCatching
                        }
                        val now = System.currentTimeMillis()
                        if (now - lastSeekTime > waitTime) {
                            lastSeekTime = now
                            waitTime = if (seekTo(position, key, controller, allowFallbackState = controllerKind != ControllerKind.STORY)) {
                                SKIP_COOLDOWN_MS
                            } else {
                                CHECK_INTERVAL_MS
                            }
                        }
                    }.onFailure {
                        log("SkipVideoAd currentPosition callback failed at ${method.declaringClass.name}.${method.name}", it)
                    }
                }
                count++
            }.onFailure {
                log("SkipVideoAd failed to hook ${method.declaringClass.name}.${method.name}", it)
            }
        }
        return count
    }

    private fun hookPlayerStateMethods(
        methods: List<Method>,
        stateMethodNames: Set<String>,
        controllerKind: ControllerKind,
    ): Int {
        var count = 0
        methods.forEach { method ->
            runCatching {
                env.hookAfter(method) { param ->
                    runCatching {
                        if (!isEnabled()) return@runCatching
                        val controller = param.thisObject ?: return@runCatching
                        val key = rememberPlayerController(controller, controllerKind) ?: return@runCatching
                        val state = param.result.asInt() ?: return@runCatching
                        if (state in 3..5 && duration <= 0) {
                            duration = resolveDuration(controller)
                            if (duration > 0) {
                                SkipVideoAdState.updateDuration(key, duration)
                            }
                        }
                        if (state in RESET_PLAYER_STATES) {
                            resetPlaybackState(fetchImmediately = true)
                        }
                    }.onFailure {
                        log("SkipVideoAd playerState callback failed at ${method.declaringClass.name}.${method.name}", it)
                    }
                }
                count++
            }.onFailure {
                log("SkipVideoAd failed to hook ${method.declaringClass.name}.${method.name}", it)
            }
        }
        return count
    }

    private fun resolveDuration(controller: Any): Long {
        return controller.callMethod("getDuration").asLong()
            ?: controller.callMethod("getRealDuration").asLong()
            ?: -1L
    }

    private fun rememberPlayerController(controller: Any, controllerKind: ControllerKind): String? {
        if (controllerKind == ControllerKind.STORY) {
            val identity = resolveIdentityFromStoryController(controller)
            if (identity == null) {
                storyPlayerRef = null
                return null
            }
            storyPlayerRef = WeakReference(controller)
            val key = bindResolvedVideoIdentity(identity, controller, fetchSegments = false)
            updateDurationFromController(key, controller)
            return key
        }

        when (controllerKind) {
            ControllerKind.PLAYER_CORE -> playerCoreServiceRef = WeakReference(controller)
            ControllerKind.CARD -> cardPlayerContextRef = WeakReference(controller)
            ControllerKind.STORY -> Unit
        }
        val key = SkipVideoAdState.keyForController(controller) ?: videoKey()
        updatePlaybackKey(key)
        SkipVideoAdState.bindController(controller, key)
        return key
    }

    private fun bindResolvedVideoIdentity(
        identity: SkipVideoAdState.VideoIdentity,
        controller: Any,
        fetchSegments: Boolean,
    ): String {
        if (identity.bvid != bvid || identity.cid != cid) {
            updateVideoIdentity(identity.bvid, identity.cid, fetchSegments)
        } else {
            SkipVideoAdState.activateVideo(identity)
        }
        updatePlaybackKey(identity.key)
        SkipVideoAdState.bindController(controller, identity.key)
        return identity.key
    }

    private fun updateDurationFromController(key: String, controller: Any) {
        val nextDuration = resolveDuration(controller)
        if (nextDuration > 0) {
            duration = nextDuration
            SkipVideoAdState.updateDuration(key, nextDuration)
        }
    }

    private fun updatePlaybackKey(key: String) {
        if (key.isBlank() || key == playbackKey) return
        playbackKey = key
        duration = -1L
        autoSkippedSegments.clear()
        manualNotifiedSegments.clear()
        waitTime = CHECK_INTERVAL_MS
    }

    private fun resolveState(controller: Any, methodNames: Set<String>): Int? {
        methodNames.forEach { name ->
            controller.callMethod(name).asInt()?.let { return it }
        }
        return null
    }

    private fun resetPlaybackState(fetchImmediately: Boolean) {
        duration = -1L
        autoSkippedSegments.clear()
        manualNotifiedSegments.clear()
        playerCoreServiceRef = null
        cardPlayerContextRef = null
        storyPlayerRef = null
        if (fetchImmediately) {
            fetchSegmentsIfNeeded()
        }
    }

    private fun fetchSegmentsIfNeeded() {
        val config = ModuleSettings.refreshSkipVideoAdCache(prefs)
        if (!config.enabled) return
        val currentBvid = bvid
        val currentCid = cid
        if (currentBvid.isBlank() || currentCid.isBlank()) return

        val identity = SkipVideoAdState.resolveVideoIdentity(currentBvid, currentCid) ?: return
        SkipVideoAdState.requestSegmentsIfMissing(identity, config.enabledCategories) { message, throwable ->
            log(message, throwable)
        }
    }

    private fun videoKey(): String =
        SkipVideoAdState.resolveVideoIdentity(bvid, cid)?.key ?: ""

    private fun seekTo(
        position: Long,
        key: String,
        controller: Any,
        allowFallbackState: Boolean,
    ): Boolean {
        val config = ModuleSettings.getSkipVideoAdCache(prefs)
        if (!config.enabled) return false
        val state = timelineStateFor(key, allowFallbackState)
        val videoDuration = state?.durationMs?.takeIf { it > 0L } ?: duration
        if (videoDuration > 0 && position > videoDuration) return false

        state?.segments.orEmpty().forEach { segment ->
            val mode = config.modes[segment.category] ?: SkipVideoAdMode.IGNORE
            if (mode == SkipVideoAdMode.IGNORE) return@forEach
            val start = (segment.segment[0] * 1000).toLong()
            val end = (segment.segment[1] * 1000).toLong()
            val segmentKey = playbackSegmentKey(state?.key ?: key, segment)
            if (position < start - AUTO_SKIP_REARM_THRESHOLD_MS) {
                autoSkippedSegments.remove("$segmentKey:auto")
                manualNotifiedSegments.remove("$segmentKey:manual")
                return@forEach
            }
            if (position >= start - PRE_SKIP_THRESHOLD_MS && position < end) {
                val seekTarget = skipTargetAfter(end, videoDuration)
                return when (mode) {
                    SkipVideoAdMode.AUTO_SKIP -> {
                        val autoKey = "$segmentKey:auto"
                        if (!autoSkippedSegments.add(autoKey)) return false
                        seekPlayerTo(seekTarget, segment, controller).also { skipped ->
                            if (skipped) {
                                requestAutoLikeAfterSkip(state?.key ?: key)
                            } else {
                                autoSkippedSegments.remove(autoKey)
                            }
                        }
                    }
                    SkipVideoAdMode.MANUAL_SKIP -> {
                        val manualKey = "$segmentKey:manual"
                        if (manualNotifiedSegments.add(manualKey)) {
                            showManualSkipPrompt(seekTarget, segment, controller)
                        }
                        false
                    }
                    SkipVideoAdMode.SHOW_IN_BAR,
                    SkipVideoAdMode.IGNORE -> false
                }
            }
        }
        return false
    }

    private fun playbackSegmentKey(key: String, segment: BilibiliSponsorBlock.Segment): String =
        key + ":" + segmentStateKey(segment)

    private fun skipTargetAfter(end: Long, videoDuration: Long): Long {
        val target = end + POST_SKIP_PADDING_MS
        return if (videoDuration > 0) target.coerceAtMost(videoDuration) else target
    }

    private fun timelineStateFor(key: String, allowFallbackState: Boolean): SkipVideoAdState.TimelineMarkerState? {
        SkipVideoAdState.stateForKey(key)?.let { return it }
        if (!allowFallbackState) return null
        val fallbackKey = videoKey()
        if (fallbackKey != key) {
            SkipVideoAdState.stateForKey(fallbackKey)?.let { return it }
        }
        return SkipVideoAdState.activeStateForDuration(duration)
    }

    private fun seekPlayerTo(
        position: Long,
        segment: BilibiliSponsorBlock.Segment,
        preferredController: Any? = null,
    ): Boolean {
        val controllers = buildList {
            preferredController?.let(::add)
            storyPlayer?.let(::add)
            cardPlayerContext?.let(::add)
            playerCoreService?.let(::add)
        }.distinctBy { System.identityHashCode(it) }
        if (controllers.isEmpty()) return false

        controllers.forEach { controller ->
            if (invokeSeek(controller, position)) {
                log("SkipVideoAd skipped ${segment.category} to $position via ${controller.javaClass.name}")
                toast(skipToastMessage(segment))
                return true
            }
        }
        return false
    }

    private fun invokeSeek(controller: Any, position: Long): Boolean {
        seekMethodsFor(controller).forEach { method ->
            val args = when (method.parameterCount) {
                1 -> arrayOf(position.coerceToMethodType(method.parameterTypes[0]))
                2 -> arrayOf(position.coerceToMethodType(method.parameterTypes[0]), true)
                else -> return@forEach
            }
            val invoked = runCatching {
                method.invoke(controller, *args)
                true
            }.onFailure {
                log("SkipVideoAd seekTo failed via ${controller.javaClass.name}", it)
            }.getOrDefault(false)
            if (invoked) return true
        }
        return false
    }

    private fun seekMethodsFor(controller: Any): List<Method> {
        val controllerType = controller.javaClass
        seekMethodsByControllerClass[controllerType]?.let { return it }
        return seekMethodsByControllerClass.entries
            .firstOrNull { (type, _) -> type.isAssignableFrom(controllerType) }
            ?.value
            .orEmpty()
    }

    private fun resolveIdentityFromStoryController(controller: Any): SkipVideoAdState.VideoIdentity? =
        resolveIdentityFromPlayableParams(controller.callNoArg("getCurrentPlayableParam"))
            ?: resolveIdentityFromPlayableParams(controller.callNoArg("getCurrentPlayableParams"))

    private fun resolveIdentityFromPlayableParams(params: Any?): SkipVideoAdState.VideoIdentity? {
        if (params == null) return null
        val directIdentity = SkipVideoAdState.resolveVideoIdentity(
            bvid = params.callNoArg("getBvid") as? String,
            cid = params.callNoArg("getCid"),
            aid = params.callNoArg("getAvid") ?: params.callNoArg("getAid"),
        )
        if (directIdentity != null) return directIdentity

        val displayParams = params.callNoArg("getDisplayParams")
        val displayIdentity = SkipVideoAdState.resolveVideoIdentity(
            bvid = displayParams?.callNoArg("getBvid") as? String,
            cid = displayParams?.callNoArg("getCid"),
            aid = displayParams?.callNoArg("getAvid") ?: displayParams?.callNoArg("getAid"),
        )
        if (displayIdentity != null) return displayIdentity

        val danmakuParams = params.callNoArg("getDanmakuResolveParams")
        return SkipVideoAdState.resolveVideoIdentity(
            bvid = danmakuParams?.callNoArg("getBvid") as? String,
            cid = danmakuParams?.callNoArg("getCid"),
            aid = danmakuParams?.callNoArg("getAvid") ?: danmakuParams?.callNoArg("getAid"),
        )
    }

    private fun Any.callNoArg(name: String): Any? {
        val type = javaClass
        val cacheKey = type.name + "#" + name
        noArgMethods[cacheKey]?.let { method ->
            return runCatching { method.invoke(this) }.getOrNull()
        }
        if (cacheKey in missingNoArgMethods) return null

        val method = type.findNoArgMethod(name)
        if (method == null) {
            missingNoArgMethods.add(cacheKey)
            return null
        }
        noArgMethods[cacheKey] = method
        return runCatching { method.invoke(this) }.getOrNull()
    }

    private fun Class<*>.findNoArgMethod(name: String): Method? =
        safeAllMethods("method $name").firstOrNull { method ->
            method.name == name && method.parameterCount == 0
        } ?: runCatching {
            methods.firstOrNull { method ->
                method.name == name && method.parameterCount == 0
            }?.apply { isAccessible = true }
        }.getOrNull()

    private fun Class<*>.safeAllMethods(reason: String): List<Method> =
        runCatching { allMethods().toList() }
            .getOrElse {
                logReflectionFailure(reason, name, it)
                emptyList()
            }

    private fun logReflectionFailure(reason: String, typeName: String, throwable: Throwable) {
        if (reflectionFailureLogs.add("$reason#$typeName")) {
            log("SkipVideoAd failed to inspect $typeName for $reason", throwable)
        }
    }

    private fun toast(message: String) {
        mainHandler.post {
            Toast.makeText(env.hostContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showManualSkipPrompt(
        position: Long,
        segment: BilibiliSponsorBlock.Segment,
        controller: Any?,
    ) {
        val activity = topActivity?.get()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            toast(manualSkipToastMessage(segment))
            return
        }

        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread

            val root = activity.findViewById<ViewGroup>(android.R.id.content)
                ?: activity.window?.decorView as? ViewGroup
                ?: return@runOnUiThread
            root.findViewWithTag<View>(MANUAL_PROMPT_TAG)?.let(root::removeView)

            val prompt = LinearLayout(activity).apply {
                tag = MANUAL_PROMPT_TAG
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                elevation = dp(12).toFloat()
                setPadding(dp(12), dp(8), dp(8), dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = dp(22).toFloat()
                    setColor(MANUAL_PROMPT_BACKGROUND)
                }
                setOnClickListener {
                    removeFromParent(this)
                    skipFromManualPrompt(position, segment, controller)
                }
            }

            val textColumn = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    TextView(activity).apply {
                        text = manualPromptTitle(segment)
                        textSize = 13f
                        maxWidth = dp(190)
                        maxLines = 1
                        setTextColor(Color.WHITE)
                    },
                )
                addView(
                    TextView(activity).apply {
                        text = manualPromptTimeRange(segment)
                        textSize = 11f
                        maxWidth = dp(190)
                        maxLines = 1
                        setTextColor(MANUAL_PROMPT_SECONDARY_TEXT)
                    },
                )
            }

            val actionView = TextView(activity).apply {
                text = "跳过"
                textSize = 13f
                gravity = Gravity.CENTER
                minWidth = dp(54)
                minHeight = dp(32)
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(MANUAL_PROMPT_ACTION_BACKGROUND)
                }
                setOnClickListener {
                    removeFromParent(prompt)
                    skipFromManualPrompt(position, segment, controller)
                }
            }

            prompt.addView(
                textColumn,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(12)
                },
            )
            prompt.addView(actionView)

            val params = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply {
                bottomMargin = dp(manualPromptBottomMarginDp(controller))
                marginStart = dp(16)
                marginEnd = dp(16)
            }
            root.addView(prompt, params)
            mainHandler.postDelayed({
                removeFromParent(prompt)
            }, MANUAL_PROMPT_DURATION_MS)
        }
    }

    private fun skipFromManualPrompt(
        position: Long,
        segment: BilibiliSponsorBlock.Segment,
        controller: Any?,
    ) {
        if (seekPlayerTo(position, segment, controller)) {
            val key = SkipVideoAdState.keyForController(controller) ?: videoKey()
            requestAutoLikeAfterSkip(key)
        }
    }

    private fun requestAutoLikeAfterSkip(key: String) {
        if (!ModuleSettings.getSkipVideoAdCache(prefs).autoLikeEnabled) return
        val videoLikeKey = autoLikeKey(key) ?: return
        if (!pendingAutoLikeVideos.add(videoLikeKey)) return

        mainHandler.postDelayed({
            attemptAutoLike(videoLikeKey, 1)
        }, AUTO_LIKE_DELAY_MS)
    }

    private fun attemptAutoLike(videoLikeKey: String, attempt: Int) {
        if (!ModuleSettings.getSkipVideoAdCache(prefs).autoLikeEnabled) {
            pendingAutoLikeVideos.remove(videoLikeKey)
            return
        }

        when (SkipVideoAdAutoLike.likeCurrentVideo(::log)) {
            SkipVideoAdAutoLike.AutoLikeResult.PERFORMED -> {
                log("SkipVideoAd auto-liked current video")
                pendingAutoLikeVideos.remove(videoLikeKey)
            }
            SkipVideoAdAutoLike.AutoLikeResult.ALREADY_LIKED -> {
                pendingAutoLikeVideos.remove(videoLikeKey)
            }
            SkipVideoAdAutoLike.AutoLikeResult.NO_CANDIDATE -> {
                if (attempt >= AUTO_LIKE_MAX_ATTEMPTS) {
                    log("SkipVideoAd auto-like found no ready like action")
                    pendingAutoLikeVideos.remove(videoLikeKey)
                } else {
                    mainHandler.postDelayed({
                        attemptAutoLike(videoLikeKey, attempt + 1)
                    }, AUTO_LIKE_RETRY_DELAY_MS)
                }
            }
        }
    }

    private fun autoLikeKey(key: String): String? =
        key.ifBlank { videoKey() }
            .ifBlank {
                listOf(bvid, cid)
                    .filter { it.isNotBlank() }
                    .joinToString(":")
            }
            .ifBlank { null }

    private fun removeFromParent(view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
    }

    private fun dp(value: Int): Int =
        (value * env.hostContext.resources.displayMetrics.density + 0.5f).toInt()

    private fun manualPromptBottomMarginDp(controller: Any?): Int {
        val controllerName = controller?.javaClass?.name.orEmpty()
        return if (".video.story." in controllerName) {
            STORY_MANUAL_PROMPT_BOTTOM_MARGIN_DP
        } else {
            MANUAL_PROMPT_BOTTOM_MARGIN_DP
        }
    }

    private fun isEnabled(): Boolean =
        ModuleSettings.isSkipVideoAdEnabledCached(prefs)

    private fun Any?.asInt(): Int? = when (this) {
        is Number -> toInt()
        is String -> toIntOrNull()
        else -> null
    }

    private fun Any?.asLong(): Long? = when (this) {
        is Number -> toLong()
        is String -> toLongOrNull()
        else -> null
    }

    private fun Long.coerceToMethodType(type: Class<*>): Any =
        when (type) {
            Int::class.javaPrimitiveType,
            Int::class.javaObjectType,
            Short::class.javaPrimitiveType,
            Short::class.javaObjectType -> toInt()
            else -> this
        }

    private fun collectProxyInterfaces(original: Any, primaryType: Class<*>): Array<Class<*>> =
        buildSet {
            add(primaryType)
            original.javaClass.interfaces.forEach(::add)
            original.javaClass.takeIf { it.isInterface }?.let(::add)
        }.toTypedArray()

    private fun skipToastMessage(segment: BilibiliSponsorBlock.Segment): String =
        "已跳过${categoryLabel(segment)}"

    private fun manualSkipToastMessage(segment: BilibiliSponsorBlock.Segment): String =
        "检测到${categoryLabel(segment)}片段"

    private fun manualPromptTitle(segment: BilibiliSponsorBlock.Segment): String =
        "检测到${categoryLabel(segment)}片段"

    private fun manualPromptTimeRange(segment: BilibiliSponsorBlock.Segment): String =
        "${formatSeconds(segment.segment[0])} - ${formatSeconds(segment.segment[1])}"

    private fun categoryLabel(segment: BilibiliSponsorBlock.Segment): String =
        ModuleSettings.skipVideoAdCategories
            .firstOrNull { it.key == segment.category }
            ?.label
            ?: segment.category

    private fun segmentStateKey(segment: BilibiliSponsorBlock.Segment): String {
        val uuid = segment.uuid.trim()
        if (uuid.isNotEmpty()) return uuid
        return buildString {
            append(segment.category)
            append(':')
            append(segment.segment[0])
            append(':')
            append(segment.segment[1])
        }
    }

    private fun formatSeconds(value: Float): String = String.format(Locale.US, "%.1fs", value)

    private companion object {
        private const val CHECK_INTERVAL_MS = 250L
        private const val PRE_SKIP_THRESHOLD_MS = 300L
        private const val POST_SKIP_PADDING_MS = 500L
        private const val AUTO_SKIP_REARM_THRESHOLD_MS = 1200L
        private const val SKIP_COOLDOWN_MS = 1000L
        private const val MANUAL_PROMPT_DURATION_MS = 7000L
        private const val AUTO_LIKE_DELAY_MS = 400L
        private const val AUTO_LIKE_RETRY_DELAY_MS = 350L
        private const val AUTO_LIKE_MAX_ATTEMPTS = 5
        private const val MANUAL_PROMPT_BOTTOM_MARGIN_DP = 92
        private const val STORY_MANUAL_PROMPT_BOTTOM_MARGIN_DP = 216
        private const val MANUAL_PROMPT_TAG = "bbzq_skip_video_ad_manual_prompt"
        private val STATE_METHOD_NAMES = setOf("getState")
        private val CARD_STATE_METHOD_NAMES = setOf("getPlayerState", "getState")
        private val STORY_STATE_METHOD_NAMES = setOf("getState")
        private val RESET_PLAYER_STATES = setOf(2)
        private val MANUAL_PROMPT_BACKGROUND = Color.argb(230, 18, 18, 18)
        private val MANUAL_PROMPT_ACTION_BACKGROUND = Color.rgb(251, 114, 153)
        private val MANUAL_PROMPT_SECONDARY_TEXT = Color.argb(190, 255, 255, 255)

        private val callbacksRegistered = java.util.concurrent.atomic.AtomicBoolean(false)

        @Volatile
        private var topActivity: WeakReference<Activity>? = null
    }

    private enum class ControllerKind {
        PLAYER_CORE,
        CARD,
        STORY,
    }
}
