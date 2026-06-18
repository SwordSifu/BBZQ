package io.github.bbzq.roaming.hook

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import dalvik.system.BaseDexClassLoader
import dalvik.system.DexFile
import io.github.bbzq.ModuleSettings
import io.github.bbzq.roaming.BaseRoamingHook
import io.github.bbzq.roaming.BilibiliSponsorBlock
import io.github.bbzq.roaming.RoamingEnv
import io.github.bbzq.roaming.allMethods
import io.github.bbzq.roaming.callMethod
import io.github.bbzq.roaming.from
import io.github.bbzq.roaming.getObjectField
import io.github.bbzq.roaming.hookAfter
import io.github.bbzq.roaming.hookBeforeMethod
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.Locale

class SkipVideoAdHook(env: RoamingEnv) : BaseRoamingHook(env) {
    @Volatile private var lastSeekTime = 0L
    @Volatile private var duration = -1L
    @Volatile private var segments: List<BilibiliSponsorBlock.Segment> = emptyList()
    @Volatile private var segmentsKey = ""
    @Volatile private var loadingSegments = false
    @Volatile private var bvid = ""
    @Volatile private var cid = ""

    private var waitTime = CHECK_INTERVAL_MS
    private var playerRef: WeakReference<Any>? = null
    private val player: Any?
        get() = playerRef?.get()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    override fun startHook() {
        val count = hookPlayViewUnite() + hookPlayerCoreService()
        log("startHook: SkipVideoAd, methods=$count")
        if (isEnabled() && count == 0 && env.processName == env.packageName) {
            toast("\u8df3\u8fc7\u89c6\u9891\u5e7f\u544a\u672a\u627e\u5230\u64ad\u653e\u5668\u63a5\u53e3")
        }
    }

    private fun hookPlayViewUnite(): Int {
        val playerMoss = PLAYER_MOSS.from(classLoader) ?: return 0
        val playViewUniteReq = PLAY_VIEW_UNITE_REQ.from(classLoader) ?: return 0
        val mossResponseHandler = MOSS_RESPONSE_HANDLER.from(classLoader)

        var count = runCatching {
            env.hookBeforeMethod(playerMoss, "executePlayViewUnite", playViewUniteReq) { param ->
                updateVideoIdentityFromRequest(param.args.firstOrNull())
            }
        }.getOrElse {
            log("SkipVideoAd failed to hook executePlayViewUnite", it)
            0
        }

        if (mossResponseHandler != null) {
            count += runCatching {
                env.hookBeforeMethod(playerMoss, "playViewUnite", playViewUniteReq, mossResponseHandler) { param ->
                    updateVideoIdentityFromRequest(param.args.firstOrNull())
                    val handler = param.args.getOrNull(1) ?: return@hookBeforeMethod
                    param.args[1] = wrapMossResponseHandler(handler, mossResponseHandler)
                }
            }.getOrElse {
                log("SkipVideoAd failed to hook playViewUnite", it)
                0
            }
        }

        return count
    }

    private fun wrapMossResponseHandler(handler: Any, handlerClass: Class<*>): Any {
        return Proxy.newProxyInstance(
            handler.javaClass.classLoader,
            arrayOf(handlerClass),
        ) { _, method, args ->
            if (method.name == "onNext") {
                updateVideoIdentityFromReply(args?.firstOrNull())
            }
            if (args == null) method.invoke(handler) else method.invoke(handler, *args)
        }
    }

    private fun updateVideoIdentityFromRequest(req: Any?) {
        if (!isEnabled()) return
        if (req == null) return
        var nextBvid = req.callMethod("getBvid") as? String ?: ""
        val vod = req.callMethod("getVod") ?: return
        if (nextBvid.isEmpty()) {
            val aid = vod.callMethod("getAid").asLong() ?: return
            if (aid == -1L) return
            nextBvid = av2bv(aid)
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
        updateVideoIdentity(av2bv(aid), nextCid)
    }

    private fun updateVideoIdentity(nextBvid: String, nextCid: String) {
        if (nextBvid.isBlank() || nextCid.isBlank()) return
        if (nextBvid == bvid && nextCid == cid) return

        bvid = nextBvid
        cid = nextCid
        duration = -1L
        segments = emptyList()
        SkipVideoAdState.durationMs = 0L
        SkipVideoAdState.segments = emptyList()
        segmentsKey = ""
        loadingSegments = false
        waitTime = CHECK_INTERVAL_MS
        fetchSegmentsIfNeeded()
    }

    private fun hookPlayerCoreService(): Int {
        var count = 0
        findPlayerCoreServiceClasses().forEach { type ->
            count += hookCurrentPosition(type)
            count += hookPlayerState(type)
        }
        return count
    }

    private fun hookCurrentPosition(type: Class<*>): Int {
        val methods = type.allMethods()
            .filter {
                it.name == "getCurrentPosition" &&
                    it.parameterCount == 0 &&
                    it.returnType.isNumericType()
            }
            .distinctBy(Method::toGenericString)
            .toList()

        var count = 0
        methods.forEach { method ->
            runCatching {
                env.hookAfter(method) { param ->
                    if (!isEnabled()) return@hookAfter
                    val service = param.thisObject ?: return@hookAfter
                    playerRef = WeakReference(service)
                    if (duration <= 0) {
                        duration = service.callMethod("getDuration").asLong() ?: -1L
                        if (duration > 0) {
                            SkipVideoAdState.durationMs = duration
                        }
                    }
                    fetchSegmentsIfNeeded()
                    val position = param.result.asLong() ?: return@hookAfter
                    val now = System.currentTimeMillis()
                    if (now - lastSeekTime > waitTime) {
                        lastSeekTime = now
                        waitTime = if (seekTo(position)) SKIP_COOLDOWN_MS else CHECK_INTERVAL_MS
                    }
                }
                count++
            }.onFailure {
                log("SkipVideoAd failed to hook ${method.declaringClass.name}.${method.name}", it)
            }
        }
        return count
    }

    private fun hookPlayerState(type: Class<*>): Int {
        val methods = type.allMethods()
            .filter {
                it.name == "G1" &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            }
            .distinctBy(Method::toGenericString)
            .toList()

        var count = 0
        methods.forEach { method ->
            runCatching {
                env.hookAfter(method) { param ->
                    if (!isEnabled()) return@hookAfter
                    val service = param.thisObject ?: return@hookAfter
                    playerRef = WeakReference(service)
                    val state = param.args.firstOrNull().asInt() ?: return@hookAfter
                    if (state in 3..5 && duration <= 0) {
                        duration = service.callMethod("getDuration").asLong() ?: -1L
                        if (duration > 0) {
                            SkipVideoAdState.durationMs = duration
                        }
                    }
                    if (state == 2) {
                        duration = -1L
                        segments = emptyList()
                        SkipVideoAdState.durationMs = 0L
                        SkipVideoAdState.segments = emptyList()
                        segmentsKey = ""
                        fetchSegmentsIfNeeded()
                    }
                }
                count++
            }.onFailure {
                log("SkipVideoAd failed to hook ${method.declaringClass.name}.${method.name}", it)
            }
        }
        return count
    }

    private fun findPlayerCoreServiceClasses(): List<Class<*>> {
        val serviceInterface = PLAYER_CORE_SERVICE_INTERFACE.from(classLoader)
        val candidates = linkedSetOf<Class<*>>()

        PLAYER_CORE_SERVICE_CANDIDATES.mapNotNullTo(candidates) { it.from(classLoader) }

        dexClassNames()
            .filter(::mightBePlayerCoreServiceName)
            .mapNotNull { name -> runCatching { Class.forName(name, false, classLoader) }.getOrNull() }
            .filter { it.isPlayerCoreService(serviceInterface) }
            .forEach { candidates += it }

        return candidates.distinctBy { it.name }
    }

    private fun dexClassNames(): Sequence<String> = sequence {
        val baseDexClassLoader = classLoader as? BaseDexClassLoader ?: return@sequence
        val pathList = baseDexClassLoader.getObjectField("pathList") ?: return@sequence
        val dexElements = pathList.getObjectField("dexElements") as? Array<*> ?: return@sequence
        dexElements.forEach { element ->
            val dexFile = element?.getObjectField("dexFile") as? DexFile ?: return@forEach
            val entries = dexFile.entries()
            while (entries.hasMoreElements()) {
                yield(entries.nextElement())
            }
        }
    }

    private fun mightBePlayerCoreServiceName(name: String): Boolean {
        val lowerName = name.lowercase(Locale.US)
        return "player" in lowerName &&
            "service" in lowerName &&
            ("core" in lowerName || "playerv2" in lowerName)
    }

    private fun Class<*>.isPlayerCoreService(serviceInterface: Class<*>?): Boolean {
        if (isInterface || Modifier.isAbstract(modifiers)) return false
        if (serviceInterface?.isAssignableFrom(this) == true) return true
        return hasNoArgNumericMethod("getCurrentPosition") &&
            hasNoArgNumericMethod("getDuration") &&
            allMethods().any { it.isSeekToMethod() }
    }

    private fun Class<*>.hasNoArgNumericMethod(name: String): Boolean =
        allMethods().any {
            it.name == name &&
                it.parameterCount == 0 &&
                it.returnType.isNumericType()
        }

    private fun fetchSegmentsIfNeeded() {
        if (!isEnabled()) return
        val currentBvid = bvid
        val currentCid = cid
        if (currentBvid.isBlank() || currentCid.isBlank()) return

        val key = "$currentBvid/$currentCid"
        if (loadingSegments || segmentsKey == key) return

        loadingSegments = true
        segmentsKey = key
        Thread {
            val enabledCategories = ModuleSettings.getSkipVideoAdCategories(prefs)
            var result = BilibiliSponsorBlock.FetchResult(
                status = BilibiliSponsorBlock.FetchStatus.FAILED,
                segments = emptyList(),
            )
            for (attempt in 0 until 3) {
                result = BilibiliSponsorBlock(currentBvid, currentCid, enabledCategories).getSegments()
                if (result.status != BilibiliSponsorBlock.FetchStatus.FAILED) break
                if (attempt < 2) Thread.sleep(1000)
            }

            if (key == videoKey()) {
                segments = result.segments
                SkipVideoAdState.segments = result.segments
                when (result.status) {
                    BilibiliSponsorBlock.FetchStatus.SUCCESS -> {
                        log("SkipVideoAd loaded ${result.segments.size} segment(s) for $key")
                        if (result.segments.isNotEmpty()) {
                            toast("\u5df2\u52a0\u8f7d ${result.segments.size} \u4e2a\u7a7a\u964d\u7247\u6bb5${loadedCategorySuffix(result.segments)}")
                        }
                    }
                    BilibiliSponsorBlock.FetchStatus.EMPTY,
                    BilibiliSponsorBlock.FetchStatus.NOT_FOUND -> {
                        log("SkipVideoAd found no skippable segments for $key")
                    }
                    BilibiliSponsorBlock.FetchStatus.FAILED -> {
                        toast("\u5e7f\u544a\u7247\u6bb5\u6570\u636e\u83b7\u53d6\u5931\u8d25")
                    }
                }
            }
            loadingSegments = false
        }.apply {
            name = "BBZQ-SkipVideoAd"
            isDaemon = true
            start()
        }
    }

    private fun videoKey(): String = "$bvid/$cid"

    private fun seekTo(position: Long): Boolean {
        if (!isEnabled()) return false
        val videoDuration = duration
        if (videoDuration > 0 && position > videoDuration) return false

        segments.forEach { segment ->
            val start = (segment.segment[0] * 1000).toLong()
            val end = (segment.segment[1] * 1000).toLong()
            if (position >= start - PRE_SKIP_THRESHOLD_MS && position < end) {
                return seekPlayerTo(end, segment)
            }
        }
        return false
    }

    private fun seekPlayerTo(position: Long, segment: BilibiliSponsorBlock.Segment): Boolean {
        val service = player ?: return false
        val method = service.javaClass.allMethods()
            .firstOrNull { it.isSeekToMethod() }
            ?: return false
        val args = when (method.parameterCount) {
            1 -> arrayOf(position.coerceToMethodType(method.parameterTypes[0]))
            2 -> arrayOf(position.coerceToMethodType(method.parameterTypes[0]), true)
            else -> return false
        }
        return runCatching {
            method.invoke(service, *args)
            log("SkipVideoAd skipped ${segment.category} to $position")
            toast(skipToastMessage(segment))
            true
        }.onFailure {
            log("SkipVideoAd seekTo failed", it)
        }.getOrDefault(false)
    }

    private fun Method.isSeekToMethod(): Boolean {
        if (name != "seekTo" || parameterCount !in 1..2) return false
        if (!parameterTypes[0].isNumericType()) return false
        return parameterCount == 1 || parameterTypes[1].isBooleanType()
    }

    private fun Class<*>.isNumericType(): Boolean =
        this == Int::class.javaPrimitiveType ||
            this == Int::class.javaObjectType ||
            this == Long::class.javaPrimitiveType ||
            this == Long::class.javaObjectType

    private fun Class<*>.isBooleanType(): Boolean =
        this == Boolean::class.javaPrimitiveType || this == Boolean::class.javaObjectType

    private fun toast(message: String) {
        mainHandler.post {
            Toast.makeText(env.hostContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun isEnabled(): Boolean =
        ModuleSettings.isSkipVideoAdEnabled(prefs)

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

    private fun loadedCategorySuffix(segments: List<BilibiliSponsorBlock.Segment>): String {
        val labels = segments
            .map(::categoryLabel)
            .distinct()
        if (labels.isEmpty()) return ""
        val preview = labels.take(3).joinToString("、")
        return if (labels.size > 3) "（$preview 等）" else "（$preview）"
    }

    private fun skipToastMessage(segment: BilibiliSponsorBlock.Segment): String =
        "\u5df2\u8df3\u8fc7${categoryLabel(segment)}"

    private fun categoryLabel(segment: BilibiliSponsorBlock.Segment): String =
        ModuleSettings.skipVideoAdCategories
            .firstOrNull { it.key == segment.category }
            ?.label
            ?: segment.category

    private fun av2bv(aid: Long): String {
        val result = CharArray(12) { if (it < 3) "BV1"[it] else '0' }
        var value = ((1L shl 51) or aid) xor 23442827791579L
        var index = 11
        while (value > 0) {
            result[index--] = BV_TABLE[(value % 58).toInt()]
            value /= 58
        }
        result[3] = result[9].also { result[9] = result[3] }
        result[4] = result[7].also { result[7] = result[4] }
        return String(result)
    }

    private companion object {
        private const val PLAYER_MOSS = "com.bapis.bilibili.app.playerunite.v1.PlayerMoss"
        private const val PLAY_VIEW_UNITE_REQ = "com.bapis.bilibili.app.playerunite.v1.PlayViewUniteReq"
        private const val MOSS_RESPONSE_HANDLER = "com.bilibili.lib.moss.api.MossResponseHandler"
        private const val PLAYER_CORE_SERVICE_INTERFACE = "tv.danmaku.biliplayerv2.service.IPlayerCoreService"
        private const val BV_TABLE = "FcwAPNKTMug3GV5Lj7EJnHpWsx4tb8haYeviqBz6rkCy12mUSDQX9RdoZf"
        private const val CHECK_INTERVAL_MS = 250L
        private const val PRE_SKIP_THRESHOLD_MS = 300L
        private const val SKIP_COOLDOWN_MS = 1000L

        private val PLAYER_CORE_SERVICE_CANDIDATES = arrayOf(
            "tv.danmaku.biliplayerv2.service.PlayerCoreService",
            "tv.danmaku.biliplayerimpl.core.PlayerCoreService",
            "com.bilibili.playerbizcommon.service.PlayerCoreService",
        )
    }
}
