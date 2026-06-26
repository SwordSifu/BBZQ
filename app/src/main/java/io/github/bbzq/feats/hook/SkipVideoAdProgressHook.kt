package io.github.bbzq.feats.hook

import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ProgressBar
import io.github.bbzq.ModuleSettings
import io.github.bbzq.SkipVideoAdMode
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.allFields
import io.github.bbzq.feats.allMethods
import io.github.bbzq.feats.callStaticMethod
import io.github.bbzq.feats.hookAfter
import io.github.bbzq.feats.symbol.RestoredSkipVideoAdProgressSymbols
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

class SkipVideoAdProgressHook(env: RoamingEnv) : BaseRoamingHook(env) {
    private val noArgMethods = ConcurrentHashMap<String, Method>()
    private val missingNoArgMethods = ConcurrentHashMap.newKeySet<String>()
    private val storyControllerFields = ConcurrentHashMap<Class<*>, Field>()
    private val missingStoryControllerFields = ConcurrentHashMap.newKeySet<Class<*>>()
    private val playerContainerFields = ConcurrentHashMap<Class<*>, Field>()
    private val missingPlayerContainerFields = ConcurrentHashMap.newKeySet<Class<*>>()
    private val hookedProgressDrawMethods = ConcurrentHashMap.newKeySet<String>()
    private val pendingStorySegmentRequests = ConcurrentHashMap.newKeySet<String>()
    private val reflectionFailureLogs = ConcurrentHashMap.newKeySet<String>()

    private var restoredSymbols: RestoredSkipVideoAdProgressSymbols? = null
    private val panelWidgetKtClass: Class<*>?
        get() = restoredSymbols?.panelWidgetKtClass
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    override fun startHook() {
        if (env.processName != env.packageName) return
        ModuleSettings.refreshSkipVideoAdCache(prefs)
        if (!ModuleSettings.isSkipVideoAdEnabledCached(prefs)) return
        val symbols = env.symbols?.skipVideoAdProgress?.restore(classLoader)
        if (symbols == null) {
            log("startHook: SkipVideoAdProgress skipped because symbols are unavailable")
            return
        }
        restoredSymbols = symbols

        val count = hookProgressTrackDraw(symbols) +
            hookStorySeekBarLifecycle(symbols) +
            hookInlineProgressUpdates(symbols)
        log("startHook: SkipVideoAdProgress, methods=$count")
    }

    private fun hookProgressTrackDraw(symbols: RestoredSkipVideoAdProgressSymbols): Int {
        val method = symbols.progressOnDraw ?: return 0
        if (!hookedProgressDrawMethods.add(method.toGenericString())) return 0

        return runCatching {
            env.hookAfter(method) { param ->
                runCatching {
                    val progressBar = param.thisObject as? ProgressBar ?: return@runCatching
                    if (!isSupportedProgressView(progressBar)) return@runCatching
                    drawSegments(progressBar, param.args.firstOrNull() as? Canvas)
                }.onFailure {
                    log("SkipVideoAdProgress draw hook failed at ${method.declaringClass.name}.${method.name}", it)
                }
            }
        }.fold(
            onSuccess = { 1 },
            onFailure = {
                log("SkipVideoAdProgress failed to hook ${method.declaringClass.name}.${method.name}", it)
                0
            },
        )
    }

    private fun hookStorySeekBarLifecycle(symbols: RestoredSkipVideoAdProgressSymbols): Int {
        var count = 0
        symbols.storyOnStartMethods.forEach { method ->
            count += runCatching {
                env.hookAfter(method) { param ->
                    runCatching {
                        bindStoryView(param.thisObject as? ProgressBar, requestSegments = true)
                    }.onFailure {
                        log("SkipVideoAdProgress story bind failed at ${method.name}", it)
                    }
                }
                1
            }.getOrElse {
                log("SkipVideoAdProgress failed to hook ${method.declaringClass.name}.${method.name}", it)
                0
            }
        }

        return count
    }

    private fun hookInlineProgressUpdates(symbols: RestoredSkipVideoAdProgressSymbols): Int {
        var count = 0
        symbols.inlineUpdateMethods.forEach { method ->
            count += runCatching {
                env.hookAfter(method) { param ->
                    runCatching {
                        bindInlineProgressView(param.thisObject as? ProgressBar, requestSegments = true)
                    }.onFailure {
                        log("SkipVideoAdProgress inline bind failed at ${method.declaringClass.name}.${method.name}", it)
                    }
                }
                1
            }.getOrElse {
                log("SkipVideoAdProgress failed to hook ${method.declaringClass.name}.${method.name}", it)
                0
            }
        }
        return count
    }

    private fun drawSegments(progressBar: ProgressBar, canvas: Canvas?) {
        if (canvas == null) return
        val config = ModuleSettings.getSkipVideoAdCache(prefs)
        if (!config.enabled) return

        val state = resolveMarkerState(progressBar) ?: return
        val durationMs = durationForDrawing(progressBar, state) ?: return
        val segments = state.segments.filter { segment ->
            (config.modes[segment.category] ?: SkipVideoAdMode.IGNORE) != SkipVideoAdMode.IGNORE
        }
        if (segments.isEmpty()) return

        SkipVideoAdMarkerRenderer.draw(
            progressBar = progressBar,
            canvas = canvas,
            durationMs = durationMs,
            segments = segments,
            colorForCategory = ::colorFor,
        )
    }

    private fun resolveMarkerState(progressBar: ProgressBar): SkipVideoAdState.TimelineMarkerState? {
        return when {
            isStorySeekBar(progressBar) -> bindStoryView(progressBar, requestSegments = true)
            isInlineProgressView(progressBar) -> bindInlineProgressView(progressBar, requestSegments = false)
            isPlayerSeekView(progressBar) -> bindPlayerSeekView(progressBar, requestSegments = true)
            else -> stateForViewOrActive(progressBar)
        }
    }

    private fun bindPlayerSeekView(
        progressBar: ProgressBar?,
        requestSegments: Boolean,
    ): SkipVideoAdState.TimelineMarkerState? {
        if (progressBar == null) return null
        val controller = progressBar.callNoArg("getPlayerCoreService")
        val identity = resolveIdentityFromPlayerSeekView(progressBar)
        val key = identity?.let(SkipVideoAdState::activateVideo)
            ?: SkipVideoAdState.keyForController(controller)
            ?: return stateForViewOrActive(progressBar)

        SkipVideoAdState.bindView(progressBar, key)
        SkipVideoAdState.bindController(controller, key)
        if (controller != null) {
            updateDurationFromController(key, controller)
        } else {
            progressBar.playerSeekDurationMs()?.let { durationMs ->
                SkipVideoAdState.updateDuration(key, durationMs)
            }
        }
        if (requestSegments && identity != null) {
            requestSegmentsIfMissing(identity)
        }
        return stateForViewOrActive(progressBar)
    }

    private fun bindInlineProgressView(
        progressBar: ProgressBar?,
        requestSegments: Boolean,
    ): SkipVideoAdState.TimelineMarkerState? {
        if (progressBar == null) return null
        val controller = resolveInlinePlayerContext(progressBar) ?: return stateForViewOrActive(progressBar)
        val identity = resolveIdentityFromPlayableParams(controller.callNoArg("getCurrentPlayableParams"))
        val key = identity?.let(SkipVideoAdState::activateVideo)
            ?: SkipVideoAdState.keyForController(controller)
            ?: return stateForViewOrActive(progressBar)

        SkipVideoAdState.bindController(controller, key)
        SkipVideoAdState.bindView(progressBar, key)
        updateDurationFromController(key, controller)
        if (requestSegments && identity != null) {
            requestSegmentsIfMissing(identity)
        }
        return stateForViewOrActive(progressBar)
    }

    private fun bindStoryView(
        progressBar: ProgressBar?,
        requestSegments: Boolean,
    ): SkipVideoAdState.TimelineMarkerState? {
        if (progressBar == null) return null
        val controller = resolveStoryController(progressBar) ?: return stateForViewOrActive(progressBar)
        val player = controller.callNoArg("getPlayer")
        val detail = controller.callNoArg("getData")
        val identity = resolveIdentityFromStoryPlayer(player)
            ?: detail?.let(::resolveIdentityFromDetail)
            ?: return stateForViewOrActive(progressBar)
        val key = SkipVideoAdState.activateVideo(identity)

        SkipVideoAdState.bindController(controller, key)
        if (player != null) {
            SkipVideoAdState.bindController(player, key)
        }
        SkipVideoAdState.bindView(progressBar, key)

        val durationMs = resolveStoryDurationMs(player, progressBar, detail, key)
        SkipVideoAdState.updateDuration(key, durationMs)
        if (requestSegments) {
            requestStorySegmentsIfMissing(identity)
        }
        return stateForViewOrActive(progressBar)
    }

    private fun stateForViewOrActive(progressBar: ProgressBar): SkipVideoAdState.TimelineMarkerState? {
        SkipVideoAdState.stateForView(progressBar)?.let { return it }
        val durationMs = progressBar.max.takeIf { it > 0 }?.toLong() ?: 0L
        val state = SkipVideoAdState.activeStateForDuration(durationMs) ?: return null
        SkipVideoAdState.bindView(progressBar, state.key)
        return state
    }

    private fun requestSegmentsIfMissing(identity: SkipVideoAdState.VideoIdentity) {
        val config = ModuleSettings.getSkipVideoAdCache(prefs)
        if (!config.enabled) return
        SkipVideoAdState.requestSegmentsIfMissing(identity, config.enabledCategories) { message, throwable ->
            log(message, throwable)
        }
    }

    private fun requestStorySegmentsIfMissing(identity: SkipVideoAdState.VideoIdentity) {
        val config = ModuleSettings.getSkipVideoAdCache(prefs)
        if (!config.enabled) return
        if (!SkipVideoAdState.shouldRequestSegments(identity, config.enabledCategories)) return
        if (!pendingStorySegmentRequests.add(identity.key)) return

        mainHandler.postDelayed(
            {
                pendingStorySegmentRequests.remove(identity.key)
                requestSegmentsIfMissing(identity)
            },
            STORY_SEGMENT_REQUEST_DELAY_MS,
        )
    }

    private fun updateDurationFromController(key: String, controller: Any) {
        val duration = controller.callNoArg("getDuration").asLong()
            ?: controller.callNoArg("getRealDuration").asLong()
            ?: return
        SkipVideoAdState.updateDuration(key, duration)
    }

    private fun durationForDrawing(
        progressBar: ProgressBar,
        state: SkipVideoAdState.TimelineMarkerState,
    ): Long? {
        val stateDuration = state.durationMs.takeIf { it > 0L }
        if (isPlayerSeekView(progressBar)) {
            return progressBar.playerSeekDurationMs() ?: stateDuration
        }
        return stateDuration ?: progressBar.maxDurationMs()
    }

    private fun resolveStoryDurationMs(
        player: Any?,
        progressBar: ProgressBar,
        detail: Any?,
        key: String,
    ): Long {
        player?.callNoArg("getDuration").asLong()?.takeIf { it > 0L }?.let { return it }
        player?.callNoArg("getRealDuration").asLong()?.takeIf { it > 0L }?.let { return it }
        progressBar.max.takeIf { it > 0 }?.toLong()?.let { return it }

        val detailDuration = detail?.callNoArg("getDuration").asLong()?.takeIf { it > 0L } ?: return 0L
        val existingDuration = SkipVideoAdState.stateForKey(key)?.durationMs ?: 0L
        val detailDurationMs = detailDuration * STORY_DETAIL_DURATION_SCALE
        if (existingDuration <= 0L) return detailDurationMs

        return if (kotlin.math.abs(existingDuration - detailDurationMs) <
            kotlin.math.abs(existingDuration - detailDuration)
        ) {
            detailDurationMs
        } else {
            detailDuration
        }
    }

    private fun resolveInlinePlayerContext(progressBar: ProgressBar): Any? =
        panelWidgetKtClass?.callStaticMethod("getPlayerContext", progressBar)

    private fun resolveIdentityFromStoryPlayer(player: Any?): SkipVideoAdState.VideoIdentity? =
        resolveIdentityFromPlayableParams(player?.callNoArg("getCurrentPlayableParam"))
            ?: resolveIdentityFromPlayableParams(player?.callNoArg("getCurrentPlayableParams"))

    private fun resolveIdentityFromPlayerSeekView(progressBar: ProgressBar): SkipVideoAdState.VideoIdentity? {
        val container = resolvePlayerContainer(progressBar) ?: return null
        currentDirectors(container).forEach { director ->
            resolveIdentityFromPlayableParams(director.callNoArg("getCurrentPlayableParams"))?.let { return it }
        }
        return null
    }

    private fun currentDirectors(container: Any): List<Any> {
        val v1Director = container.callNoArg("getVideoPlayDirectorService")
        val v3Director = container.callNoArg("getPlayDirectorServiceV3")
        val directorVersion = container.callNoArg("getPlayerParams")
            ?.callNoArg("getConfig")
            ?.callNoArg("getDirectorVersion")
            ?.toString()

        val preferred = when (directorVersion) {
            "V1" -> listOf(v1Director, v3Director)
            "V3" -> listOf(v3Director, v1Director)
            else -> listOf(v3Director, v1Director)
        }
        return preferred.filterNotNull().distinctBy { System.identityHashCode(it) }
    }

    private fun resolvePlayerContainer(view: View): Any? {
        val type = view.javaClass
        playerContainerFields[type]?.let { field ->
            return runCatching { field.get(view) }.getOrNull()
        }
        if (type in missingPlayerContainerFields) return null

        val field = type.safeAllFields("player container").firstOrNull { candidate ->
            runCatching {
                candidate.type.name == PLAYER_CONTAINER_CLASS ||
                    (
                        candidate.type.hasNoArgMethod("getPlayDirectorServiceV3") &&
                            candidate.type.hasNoArgMethod("getVideoPlayDirectorService")
                        )
            }.getOrDefault(false)
        }
        if (field == null) {
            missingPlayerContainerFields.add(type)
            return null
        }

        playerContainerFields[type] = field
        return runCatching { field.get(view) }.getOrNull()
    }

    private fun resolveStoryController(view: View): Any? {
        val type = view.javaClass
        storyControllerFields[type]?.let { field ->
            return runCatching { field.get(view) }.getOrNull()
        }
        if (type in missingStoryControllerFields) return null

        val field = type.safeAllFields("story controller").firstOrNull { candidate ->
            runCatching {
                candidate.type.hasNoArgMethod("getData") &&
                    candidate.type.hasNoArgMethod("getPlayer")
            }.getOrDefault(false)
        }
        if (field == null) {
            missingStoryControllerFields.add(type)
            return null
        }

        storyControllerFields[type] = field
        return runCatching { field.get(view) }.getOrNull()
    }

    private fun resolveIdentityFromDetail(detail: Any): SkipVideoAdState.VideoIdentity? =
        SkipVideoAdState.resolveVideoIdentity(
            bvid = detail.callNoArg("getBvid") as? String,
            cid = detail.callNoArg("getCid"),
            aid = detail.callNoArg("getAid"),
        )

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

    private fun Class<*>.hasNoArgMethod(name: String): Boolean =
        findNoArgMethod(name) != null

    private fun Class<*>.findNoArgMethod(name: String): Method? =
        safeAllMethods("method $name").firstOrNull { method ->
            method.name == name && method.parameterCount == 0
        } ?: runCatching {
            methods.firstOrNull { method ->
                method.name == name && method.parameterCount == 0
            }?.apply { isAccessible = true }
        }.getOrNull()

    private fun Class<*>.findNoArgCanvasMethod(name: String): Method? =
        safeAllMethods("canvas method $name").firstOrNull { method ->
            method.name == name &&
                method.parameterCount == 1 &&
                method.parameterTypes.firstOrNull() == Canvas::class.java
        }

    private fun Class<*>.safeAllMethods(reason: String): List<Method> =
        runCatching { allMethods().toList() }
            .getOrElse {
                logReflectionFailure(reason, name, it)
                emptyList()
            }

    private fun Class<*>.safeAllFields(reason: String): List<Field> =
        runCatching { allFields().toList() }
            .getOrElse {
                logReflectionFailure(reason, name, it)
                emptyList()
            }

    private fun logReflectionFailure(reason: String, typeName: String, throwable: Throwable) {
        if (reflectionFailureLogs.add("$reason#$typeName")) {
            log("SkipVideoAdProgress failed to inspect $typeName for $reason", throwable)
        }
    }

    private fun isPlayerSeekView(view: ProgressBar): Boolean {
        val name = view.javaClass.name
        return name == PLAYER_SEEK_WIDGET_CLASS ||
            (name.endsWith(".PlayerSeekWidget3") && "playerbizcommon" in name)
    }

    private fun isInlineProgressView(view: ProgressBar): Boolean {
        val name = view.javaClass.name
        return name in INLINE_PROGRESS_CLASSES ||
            (name.endsWith(".InlineProgressWidgetV3") && ".inline." in name)
    }

    private fun isStorySeekBar(view: ProgressBar): Boolean {
        val name = view.javaClass.name
        return name == STORY_SEEK_BAR_CLASS ||
            (name.endsWith(".StorySeekBar") && ".video.story." in name)
    }

    private fun isSupportedProgressView(view: ProgressBar): Boolean =
        isPlayerSeekView(view) || isStorySeekBar(view) || isInlineProgressView(view)

    private fun colorFor(category: String): Int =
        ModuleSettings.skipVideoAdCategories
            .firstOrNull { it.key == category }
            ?.color
            ?: 0xFFFB7299.toInt()

    private fun Class<*>.isNumericType(): Boolean =
        this == Int::class.javaPrimitiveType ||
            this == Int::class.javaObjectType ||
            this == Long::class.javaPrimitiveType ||
            this == Long::class.javaObjectType

    private fun Any?.asLong(): Long? = when (this) {
        is Number -> toLong()
        is String -> toLongOrNull()
        else -> null
    }

    private fun ProgressBar.maxDurationMs(): Long? =
        max.takeIf { it > 0 }?.toLong()

    private fun ProgressBar.playerSeekDurationMs(): Long? =
        maxDurationMs()?.takeIf { it >= MIN_PLAYER_SEEK_DURATION_MS }

    private companion object {
        private const val PLAYER_CONTAINER_CLASS = "tv.danmaku.biliplayerv2.PlayerContainer"
        private const val PLAYER_SEEK_WIDGET_CLASS = "com.bilibili.playerbizcommonv2.widget.seek.v3.PlayerSeekWidget3"
        private const val STORY_SEEK_BAR_CLASS = "com.bilibili.video.story.view.StorySeekBar"
        private const val STORY_DETAIL_DURATION_SCALE = 1000L
        private const val STORY_SEGMENT_REQUEST_DELAY_MS = 3000L
        private const val MIN_PLAYER_SEEK_DURATION_MS = 1000L

        private val INLINE_PROGRESS_CLASSES = setOf(
            "com.bilibili.app.comm.list.common.inline.widgetV3.InlineProgressWidgetV3",
            "com.bilibili.p4439app.p4450comm.p4472list.common.inline.widgetV3.InlineProgressWidgetV3",
            "com.bilibili.p4440app.p4451comm.p4473list.common.inline.widgetV3.InlineProgressWidgetV3",
        )
    }
}
