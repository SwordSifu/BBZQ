package io.github.bbzq.feats.symbol

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import io.github.bbzq.BuildConfig
import io.github.bbzq.ModuleSettings
import io.github.bbzq.ModuleSettingsBridge
import io.github.bbzq.feats.allFields
import io.github.bbzq.feats.allMethods
import io.github.bbzq.feats.symbol.dexkit.DexKitBridgeProvider
import io.github.bbzq.feats.symbol.dexkit.scanMessage
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.coroutines.Continuation

object BiliSymbolResolver {
    private const val PREFS_NAME = "bbzq_symbol_cache"
    private const val KEY_FINGERPRINT = "fingerprint"
    private const val KEY_SYMBOLS = "symbols"

    private const val HP_ACCOUNT_ACCESS_KEY = "AccessKeyHook.BiliAccounts"
    private const val HP_SETTINGS_FRAGMENT = "SettingHook.PreferenceFragments"
    private const val HP_BLOCK_UPDATE = "BlockUpdateHook.UpdateCheck"
    private const val HP_MINE_VIP = "MineProfileHook.VipEntrance"
    private const val HP_DOWNLOAD_THREAD_LISTENER = "DownloadThreadHook.Listener"
    private const val HP_DOWNLOAD_THREAD_REPORT = "DownloadThreadHook.ReportMethod"
    private const val HP_HOME_RECOMMEND_AUTO_REFRESH = "HomeRecommendAutoRefreshHook.AutoRefresh"
    private const val HP_STORY_PLAYER_AD = "StoryPlayerAdHook.InstallPoints"
    private const val HP_VIDEO_DETAIL_BANNER_AD = "VideoDetailBannerAdHook.InstallPoints"
    private const val HP_COMMENT_PICTURE = "CommentPictureHook.InitView"
    private const val HP_HOME_TOP_BAR = "HomeTopBarPurifyHook.InstallPoints"
    private const val HP_BOTTOM_BAR = "BottomBarHook.InstallPoints"
    private const val HP_HOME_RECOMMEND_FEED = "HomeRecommendFeed.Pegasus"
    private const val HP_HOME_COMPONENT_HIDE = "HomeComponentHideHook.Components"
    private const val HP_VIDEO_COMMENT = "VideoCommentHook.InstallPoints"
    private const val HP_SKIP_VIDEO_AD = "SkipVideoAdHook.InstallPoints"
    private const val HP_SKIP_VIDEO_AD_PROGRESS = "SkipVideoAdProgressHook.InstallPoints"
    private const val HP_SKIP_VIDEO_AD_AUTO_LIKE = "SkipVideoAdAutoLike.InstallPoints"
    private const val HP_CHRONOS_PROMOTION = "ChronosPromotionHook.InstallPoints"

    @Volatile
    private var memorySymbols: BiliHookSymbols? = null

    fun resolve(
        hostContext: Context,
        moduleContext: Context?,
        classLoader: ClassLoader,
        log: (String, Throwable?) -> Unit,
    ): BiliHookSymbols {
        val appContext = hostContext.applicationContext ?: hostContext
        val fingerprint = buildFingerprint(appContext)
        memorySymbols?.takeIf { it.isUsableWith(fingerprint) }?.let {
            log("BiliSymbolResolver cache hit: memory fp=$fingerprint", null)
            it.formatStatusLines().forEach { line -> log(line, null) }
            publishStatus(it, "memory", log)
            return it
        }

        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val diskSymbols = BiliHookSymbols.fromJson(prefs.getString(KEY_SYMBOLS, null))
        if (diskSymbols?.isUsableWith(fingerprint) == true) {
            memorySymbols = diskSymbols
            log("BiliSymbolResolver cache hit: disk fp=$fingerprint", null)
            diskSymbols.formatStatusLines().forEach { line -> log(line, null) }
            publishStatus(diskSymbols, "disk", log)
            return diskSymbols
        }

        log("BiliSymbolResolver scan begin fp=$fingerprint", null)
        val scanned = scan(
            hostContext = appContext,
            moduleContext = moduleContext,
            classLoader = classLoader,
            fingerprint = fingerprint,
            log = log,
        )
        writeCache(prefs, fingerprint, scanned, log)
        memorySymbols = scanned
        log("BiliSymbolResolver scan done fp=$fingerprint", null)
        scanned.formatStatusLines().forEach { line -> log(line, null) }
        publishStatus(scanned, "scan", log)
        return scanned
    }

    fun forceRefresh(
        hostContext: Context,
        moduleContext: Context?,
        classLoader: ClassLoader,
        log: (String, Throwable?) -> Unit,
    ): BiliHookSymbols {
        val appContext = hostContext.applicationContext ?: hostContext
        val fingerprint = buildFingerprint(appContext)
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        memorySymbols = null
        runCatching {
            prefs.edit().clear().commit()
        }.onFailure { log("BiliSymbolResolver cache clear failed", it) }

        log("BiliSymbolResolver force scan begin fp=$fingerprint", null)
        val scanned = scan(
            hostContext = appContext,
            moduleContext = moduleContext,
            classLoader = classLoader,
            fingerprint = fingerprint,
            log = log,
        )
        writeCache(prefs, fingerprint, scanned, log)
        memorySymbols = scanned
        log("BiliSymbolResolver force scan done fp=$fingerprint", null)
        scanned.formatStatusLines().forEach { line -> log(line, null) }
        publishStatus(scanned, "force-scan", log)
        return scanned
    }

    private fun scan(
        hostContext: Context,
        moduleContext: Context?,
        classLoader: ClassLoader,
        fingerprint: String,
        log: (String, Throwable?) -> Unit,
    ): BiliHookSymbols {
        val sourcePaths = sourcePaths(hostContext)
        val scanErrors = ArrayList<String>()
        var bridge: DexKitBridge? = null

        fun recordError(detail: String) {
            val sanitized = detail.replace('\n', ' ').take(420)
            scanErrors += sanitized
            log("BiliSymbolResolver scan issue: $sanitized", null)
        }

        fun bridge(): DexKitBridge? {
            bridge?.let { return it }
            val opened = DexKitBridgeProvider.openFirstAvailable(
                hostContext = hostContext,
                moduleContext = moduleContext,
                sourcePaths = sourcePaths,
                recordError = ::recordError,
                log = { log(it, null) },
            ) ?: return null
            bridge = opened.bridge
            return opened.bridge
        }

        val hookPoints = ArrayList<HookPointStatus>()
        val account = scanHookPoint(HP_ACCOUNT_ACCESS_KEY, hookPoints, scanErrors, log) {
            scanAccount(classLoader, ::bridge)
        }
        val settings = scanHookPoint(HP_SETTINGS_FRAGMENT, hookPoints, scanErrors, log) {
            scanSettings(classLoader, ::bridge)
        }
        val blockUpdate = scanHookPoint(HP_BLOCK_UPDATE, hookPoints, scanErrors, log) {
            scanBlockUpdate(classLoader, ::bridge)
        }
        val mineProfile = scanHookPoint(HP_MINE_VIP, hookPoints, scanErrors, log) {
            scanMineProfile(classLoader, ::bridge)
        }
        val downloadThreadListeners = scanHookPoint(HP_DOWNLOAD_THREAD_LISTENER, hookPoints, scanErrors, log) {
            scanDownloadThreadListeners(classLoader, ::bridge)
        }.orEmpty()
        val downloadThreadReport = scanOptionalHookPoint(HP_DOWNLOAD_THREAD_REPORT, hookPoints, scanErrors, log) {
            scanDownloadThreadReportMethod(classLoader, ::bridge)
        }
        val downloadThread = if (downloadThreadListeners.isNotEmpty() || downloadThreadReport != null) {
            DownloadThreadSymbols(
                listeners = downloadThreadListeners,
                reportMethod = downloadThreadReport,
                evidence = "listeners=${downloadThreadListeners.size},report=${downloadThreadReport != null}",
            )
        } else {
            null
        }
        val homeRecommendAutoRefresh = scanHookPoint(HP_HOME_RECOMMEND_AUTO_REFRESH, hookPoints, scanErrors, log) {
            scanHomeRecommendAutoRefresh(classLoader)
        }
        val storyPlayerAd = scanHookPoint(HP_STORY_PLAYER_AD, hookPoints, scanErrors, log) {
            scanStoryPlayerAd(classLoader)
        }
        val videoDetailBannerAd = scanHookPoint(HP_VIDEO_DETAIL_BANNER_AD, hookPoints, scanErrors, log) {
            scanVideoDetailBannerAd(classLoader)
        }
        val commentPicture = scanHookPoint(HP_COMMENT_PICTURE, hookPoints, scanErrors, log) {
            scanCommentPicture(classLoader)
        }
        val homeTopBar = scanHookPoint(HP_HOME_TOP_BAR, hookPoints, scanErrors, log) {
            scanHomeTopBar(classLoader)
        }
        val bottomBar = scanHookPoint(HP_BOTTOM_BAR, hookPoints, scanErrors, log) {
            scanBottomBar(classLoader)
        }
        val homeRecommendFeed = scanHookPoint(HP_HOME_RECOMMEND_FEED, hookPoints, scanErrors, log) {
            scanHomeRecommendFeed(classLoader)
        }
        val homeComponentHide = scanHookPoint(HP_HOME_COMPONENT_HIDE, hookPoints, scanErrors, log) {
            scanHomeComponentHide(classLoader)
        }
        val videoComment = scanHookPoint(HP_VIDEO_COMMENT, hookPoints, scanErrors, log) {
            scanVideoComment(classLoader)
        }
        val skipVideoAd = scanHookPoint(HP_SKIP_VIDEO_AD, hookPoints, scanErrors, log) {
            scanSkipVideoAd(classLoader)
        }
        val skipVideoAdProgress = scanHookPoint(HP_SKIP_VIDEO_AD_PROGRESS, hookPoints, scanErrors, log) {
            scanSkipVideoAdProgress(classLoader)
        }
        val skipVideoAdAutoLike = scanHookPoint(HP_SKIP_VIDEO_AD_AUTO_LIKE, hookPoints, scanErrors, log) {
            scanSkipVideoAdAutoLike(classLoader)
        }
        val chronosPromotion = scanHookPoint(HP_CHRONOS_PROMOTION, hookPoints, scanErrors, log) {
            scanChronosPromotion(classLoader)
        }

        runCatching { bridge?.close() }
            .onFailure { recordError("DexKitBridge close failed: ${it.scanMessage()}") }

        return BiliHookSymbols(
            fingerprint = fingerprint,
            hookPoints = hookPoints,
            scanErrors = scanErrors.distinct(),
            account = account,
            settings = settings,
            blockUpdate = blockUpdate,
            mineProfile = mineProfile,
            downloadThread = downloadThread,
            homeRecommendAutoRefresh = homeRecommendAutoRefresh,
            storyPlayerAd = storyPlayerAd,
            videoDetailBannerAd = videoDetailBannerAd,
            commentPicture = commentPicture,
            homeTopBar = homeTopBar,
            bottomBar = bottomBar,
            homeRecommendFeed = homeRecommendFeed,
            homeComponentHide = homeComponentHide,
            videoComment = videoComment,
            skipVideoAd = skipVideoAd,
            skipVideoAdProgress = skipVideoAdProgress,
            skipVideoAdAutoLike = skipVideoAdAutoLike,
            chronosPromotion = chronosPromotion,
        )
    }

    private inline fun <T : Any> scanHookPoint(
        id: String,
        hookPoints: MutableList<HookPointStatus>,
        scanErrors: MutableList<String>,
        log: (String, Throwable?) -> Unit,
        block: () -> SymbolScanResult<T>,
    ): T? {
        return try {
            when (val result = block()) {
                is SymbolScanResult.Found -> {
                    hookPoints += HookPointStatus.found(id, result.target, result.evidence)
                    result.value
                }
                is SymbolScanResult.Missing -> {
                    hookPoints += HookPointStatus.missing(id, result.reason)
                    null
                }
            }
        } catch (t: Throwable) {
            val reason = t.scanMessage()
            scanErrors += "$id :: $reason"
            hookPoints += HookPointStatus.error(id, reason)
            log("BiliSymbolResolver $id scan failed", t)
            null
        }
    }

    private inline fun <T : Any> scanOptionalHookPoint(
        id: String,
        hookPoints: MutableList<HookPointStatus>,
        scanErrors: MutableList<String>,
        log: (String, Throwable?) -> Unit,
        block: () -> SymbolScanResult<T>,
    ): T? {
        return try {
            when (val result = block()) {
                is SymbolScanResult.Found -> {
                    hookPoints += HookPointStatus.found(id, result.target, result.evidence)
                    result.value
                }
                is SymbolScanResult.Missing -> {
                    hookPoints += HookPointStatus.optional(id, result.reason)
                    null
                }
            }
        } catch (t: Throwable) {
            val reason = t.scanMessage()
            scanErrors += "$id :: $reason"
            hookPoints += HookPointStatus.error(id, reason)
            log("BiliSymbolResolver $id scan failed", t)
            null
        }
    }

    private fun scanAccount(
        classLoader: ClassLoader,
        bridge: () -> DexKitBridge?,
    ): SymbolScanResult<AccountSymbols> {
        val candidates = (
            ACCOUNT_CLASS_NAMES.asSequence() +
                findClassNamesBySimpleName(bridge, "BiliAccounts").asSequence()
            )
            .distinct()
            .mapNotNull { classLoader.loadClassOrNull(it) }
            .distinctBy { it.name }
            .toList()

        for (type in candidates) {
            val getMethod = type.declaredMethods
                .filter { method ->
                    Modifier.isStatic(method.modifiers) &&
                        method.returnType == type &&
                        (
                            method.parameterCount == 0 ||
                                method.hasParameterTypes("android.content.Context")
                            )
                }
                .sortedWith(compareBy<Method> { if (it.name == "get") 0 else 1 }.thenBy { it.parameterCount })
                .firstOrNull()
                ?.apply { isAccessible = true }
                ?: continue
            val accessKeyMethod = type.declaredMethods
                .filter { method ->
                    !Modifier.isStatic(method.modifiers) &&
                        method.parameterCount == 0 &&
                        method.returnType == String::class.java
                }
                .sortedWith(
                    compareBy<Method> {
                        when (it.name) {
                            "getAccessKey" -> 0
                            "accessKey" -> 1
                            else -> 2
                        }
                    }.thenBy { it.name },
                )
                .firstOrNull { method -> method.name.contains("access", ignoreCase = true) || method.name in SHORT_ACCESS_METHODS }
                ?.apply { isAccessible = true }
                ?: continue

            val symbols = AccountSymbols(
                accountClassName = type.name,
                getMethod = MethodDescriptor.of(getMethod),
                accessKeyMethod = MethodDescriptor.of(accessKeyMethod),
                evidence = if (type.name in ACCOUNT_CLASS_NAMES) "stableClass" else "dexkitSimpleName",
            )
            return SymbolScanResult.Found(symbols, "${type.name}.${accessKeyMethod.name}", symbols.evidence)
        }
        return SymbolScanResult.Missing("account class or getAccessKey method not verified")
    }

    private fun scanSettings(
        classLoader: ClassLoader,
        bridge: () -> DexKitBridge?,
    ): SymbolScanResult<SettingsSymbols> {
        val fragmentNames = (
            SETTINGS_FRAGMENT_NAMES.asSequence() +
                findClassNamesBySimpleName(bridge, "BiliPreferencesFragment").asSequence() +
                findClassNamesBySimpleName(bridge, "WideBiliPreferencesFragment").asSequence()
            ).distinct()

        val methods = fragmentNames
            .mapNotNull { classLoader.loadClassOrNull(it) }
            .mapNotNull { type ->
                type.declaredMethods.firstOrNull { method ->
                    method.name == "onCreatePreferences" && method.parameterCount == 2
                }?.apply { isAccessible = true }
            }
            .distinctBy { it.declaringClass.name + "#" + it.name + it.parameterTypes.joinToString { type -> type.name } }
            .toList()

        val preferenceClass = PREFERENCE_CLASS_NAMES.firstNotNullOfOrNull { classLoader.loadClassOrNull(it) }
            ?: return SymbolScanResult.Missing("preference class not found")
        if (methods.isEmpty()) {
            return SymbolScanResult.Missing("onCreatePreferences fragment method not found")
        }
        val symbols = SettingsSymbols(
            fragmentMethods = methods.map(MethodDescriptor::of),
            preferenceClassName = preferenceClass.name,
            evidence = "fragments=${methods.size},preference=${preferenceClass.name}",
        )
        return SymbolScanResult.Found(symbols, methods.joinToString("|") { it.declaringClass.name }, symbols.evidence)
    }

    private fun scanBlockUpdate(
        classLoader: ClassLoader,
        bridge: () -> DexKitBridge?,
    ): SymbolScanResult<BlockUpdateSymbols> {
        val currentBridge = bridge() ?: return SymbolScanResult.Missing("DexKitBridge unavailable")
        val methods = runCatching {
            currentBridge.findMethod(
                FindMethod.create()
                    .searchPackages("com.bilibili", "tv.danmaku")
                    .matcher(
                        MethodMatcher.create()
                            .name("check")
                            .paramTypes(Context::class.java)
                            .usingStrings("Do sync http request."),
                    ),
            )
        }.getOrElse { throwable ->
            return SymbolScanResult.Missing("update check method search failed: ${throwable.scanMessage()}")
        }
        val methodData = methods.firstOrNull()
            ?: return SymbolScanResult.Missing("update check method not found")
        val method = runCatching { methodData.getMethodInstance(classLoader) }
            .getOrNull()
            ?: return SymbolScanResult.Missing("update check method restore failed")
        val symbols = BlockUpdateSymbols(
            checkMethod = MethodDescriptor.of(method),
            evidence = "${method.declaringClass.name}.${method.name}",
        )
        return SymbolScanResult.Found(symbols, symbols.evidence, symbols.evidence)
    }

    private fun scanMineProfile(
        classLoader: ClassLoader,
        bridge: () -> DexKitBridge?,
    ): SymbolScanResult<MineProfileSymbols> {
        val fragmentClass = (
            MINE_FRAGMENT_CLASS_NAMES.asSequence() +
                findClassNamesBySimpleName(bridge, "HomeUserCenterFragment").asSequence()
            )
            .distinct()
            .mapNotNull { classLoader.loadClassOrNull(it) }
            .firstOrNull { type ->
                type.declaredMethods.any { method -> method.name == "onResume" && method.parameterCount == 0 }
            }
            ?: return SymbolScanResult.Missing("HomeUserCenterFragment not verified")

        val vipViewClass = (
            MINE_VIP_VIEW_CLASS_NAMES.asSequence() +
                findClassNamesBySimpleName(bridge, "MineVipEntranceView").asSequence() +
                findClassNamesBySimpleName(bridge, "VipEntranceView").asSequence()
            )
            .distinct()
            .mapNotNull { classLoader.loadClassOrNull(it) }
            .firstOrNull()

        val onResume = fragmentClass.declaredMethods.firstOrNull { method ->
            method.name == "onResume" && method.parameterCount == 0
        }?.apply { isAccessible = true }
            ?: return SymbolScanResult.Missing("onResume not found")

        if (vipViewClass != null) {
            val vipField = fragmentClass.declaredFields.firstOrNull { field ->
                vipViewClass.isAssignableFrom(field.type)
            }?.apply { isAccessible = true }
                ?: return SymbolScanResult.Missing("vip view field not found")

            val symbols = MineProfileSymbols(
                fragmentClassName = fragmentClass.name,
                vipViewClassName = vipViewClass.name,
                vipField = FieldDescriptor.of(vipField),
                onResume = MethodDescriptor.of(onResume),
                evidence = "fragment=${fragmentClass.name},vip=${vipViewClass.name},field=${vipField.name}",
            )
            return SymbolScanResult.Found(symbols, "${fragmentClass.name}.${onResume.name}", symbols.evidence)
        }

        val managerClass = MINE_VIP_MANAGER_CLASS_NAMES.firstNotNullOfOrNull { classLoader.loadClassOrNull(it) }
            ?: return SymbolScanResult.Missing("mine vip view or manager class not found")
        val viewBindingClass = classLoader.loadClassOrNull("androidx.viewbinding.ViewBinding")
            ?: return SymbolScanResult.Missing("ViewBinding class not found")
        val managerField = fragmentClass.declaredFields.firstOrNull { field ->
            managerClass.isAssignableFrom(field.type)
        }?.apply { isAccessible = true }
            ?: return SymbolScanResult.Missing("mine vip manager field not found")
        val bindingField = managerClass.declaredFields.firstOrNull { field ->
            viewBindingClass.isAssignableFrom(field.type)
        }?.apply { isAccessible = true }
            ?: return SymbolScanResult.Missing("mine vip manager binding field not found")
        val rootField = bindingField.type.declaredFields.firstOrNull { field ->
            View::class.java.isAssignableFrom(field.type)
        }?.apply { isAccessible = true }
            ?: return SymbolScanResult.Missing("mine vip binding root view field not found")

        val symbols = MineProfileSymbols(
            fragmentClassName = fragmentClass.name,
            vipViewClassName = managerClass.name,
            vipField = FieldDescriptor.of(managerField),
            managerBindingField = FieldDescriptor.of(bindingField),
            bindingRootField = FieldDescriptor.of(rootField),
            onResume = MethodDescriptor.of(onResume),
            evidence = "fragment=${fragmentClass.name},manager=${managerClass.name},field=${managerField.name},root=${rootField.name}",
        )
        return SymbolScanResult.Found(symbols, "${fragmentClass.name}.${onResume.name}", symbols.evidence)
    }

    private fun scanDownloadThreadListeners(
        classLoader: ClassLoader,
        bridge: () -> DexKitBridge?,
    ): SymbolScanResult<List<DownloadThreadListenerSymbols>> {
        val classNames = findClassNamesByNameContains(
            bridge = bridge,
            terms = DOWNLOAD_THREAD_LISTENER_CLASS_TERMS,
        )
        val listeners = classNames
            .asSequence()
            .mapNotNull { classLoader.loadClassOrNull(it) }
            .distinctBy { it.name }
            .mapNotNull { type -> type.toDownloadThreadListenerSymbols() }
            .distinctBy { it.className }
            .toList()

        if (listeners.isEmpty()) {
            return SymbolScanResult.Missing("download thread listener class not verified")
        }
        return SymbolScanResult.Found(
            listeners,
            listeners.joinToString("|") { it.className },
            "dexkitClassName+TextViewCtor+onClick+TextViewField",
        )
    }

    private fun scanDownloadThreadReportMethod(
        classLoader: ClassLoader,
        bridge: () -> DexKitBridge?,
    ): SymbolScanResult<MethodDescriptor> {
        val classNames = findClassNamesByNameContains(
            bridge = bridge,
            terms = DOWNLOAD_THREAD_REPORT_CLASS_TERMS,
        )
        val methods = classNames
            .asSequence()
            .mapNotNull { classLoader.loadClassOrNull(it) }
            .distinctBy { it.name }
            .flatMap { type -> type.allMethods() }
            .filter { method ->
                method.name == "reportDownloadThread" &&
                    method.parameterCount == 2 &&
                    method.parameterTypes[0].name == "android.content.Context" &&
                    method.parameterTypes[1] == Int::class.javaPrimitiveType &&
                    !method.returnType.isPrimitive &&
                    CharSequence::class.java.isAssignableFrom(method.returnType)
            }
            .distinctBy { it.declaringClass.name + "#" + it.name + it.parameterTypes.joinToString { type -> type.name } }
            .toList()

        if (methods.size != 1) {
            return SymbolScanResult.Missing("reportDownloadThread method candidates=${methods.size}")
        }
        return SymbolScanResult.Found(
            MethodDescriptor.of(methods.single()),
            methods.single().toGenericString(),
            "dexkitClassName+exactSignature",
        )
    }

    private fun Class<*>.toDownloadThreadListenerSymbols(): DownloadThreadListenerSymbols? {
        if (isInterface || Modifier.isAbstract(modifiers)) return null
        val constructor = declaredConstructors.firstOrNull { ctor ->
            ctor.parameterTypes.any { TextView::class.java.isAssignableFrom(it) }
        }?.apply { isAccessible = true } ?: return null

        val onClick = allMethods().firstOrNull { method ->
            method.name == "onClick" &&
                method.parameterCount == 1 &&
                View::class.java.isAssignableFrom(method.parameterTypes[0])
        } ?: return null

        val textViewField = allFields().firstOrNull { field ->
            TextView::class.java.isAssignableFrom(field.type)
        } ?: return null

        return DownloadThreadListenerSymbols(
            className = name,
            constructor = ConstructorDescriptor.of(constructor),
            onClick = MethodDescriptor.of(onClick),
            textViewField = FieldDescriptor.of(textViewField),
        )
    }

    private fun scanHomeRecommendAutoRefresh(
        classLoader: ClassLoader,
    ): SymbolScanResult<HomeRecommendAutoRefreshSymbols> {
        val componentClass = classLoader.loadClassOrNull(HOME_AUTO_REFRESH_COMPONENT)
            ?: return SymbolScanResult.Missing("component class not found")
        val requestManagerClass = classLoader.loadClassOrNull(HOME_PEGASUS_REQUEST_MANAGER)
            ?: return SymbolScanResult.Missing("request manager class not found")
        val flushClass = classLoader.loadClassOrNull(HOME_PEGASUS_FLUSH)
            ?: return SymbolScanResult.Missing("flush class not found")
        val resourceClass = classLoader.loadClassOrNull(HOME_RESOURCE)
            ?: return SymbolScanResult.Missing("resource class not found")

        val autoRefreshMethod = componentClass.declaredMethods
            .firstOrNull {
                it.parameterCount == 1 &&
                    it.parameterTypes[0] == flushClass &&
                    it.returnType == Void.TYPE &&
                    !Modifier.isStatic(it.modifiers) &&
                    !Modifier.isAbstract(it.modifiers)
            }?.apply { isAccessible = true }
            ?: return SymbolScanResult.Missing("auto refresh method not found")

        val requestMethodCandidates = requestManagerClass.allMethods()
            .filter { it.isHomeRecommendRequestCandidate(flushClass) }
            .distinctBy { it.toGenericString() }
            .toList()
        val requestMethods = requestMethodCandidates
        val requestParamClass = requestMethods.map { it.parameterTypes[0] }.distinct().singleOrNull()
            ?: return SymbolScanResult.Missing(
                "request param class candidates=${requestMethods.map { it.parameterTypes[0].name }.distinct().size} " +
                    "methods=${requestMethodCandidates.size} " +
                    "params=${requestMethodCandidates.map { it.parameterTypes[0].name }.distinct().joinToString(limit = 4)} " +
                    "fields=${requestMethodCandidates.map { describeHomeRequestParamCandidate(it.parameterTypes[0], flushClass) }.distinct().joinToString(limit = 4)}",
            )
        val requestSymbols = homeRequestParamSymbols(requestParamClass, flushClass)
            ?: return SymbolScanResult.Missing("request param fields not found")
        val resourceError = resourceClass.declaredMethods.firstOrNull {
            Modifier.isStatic(it.modifiers) &&
                it.parameterCount == 1 &&
                Throwable::class.java.isAssignableFrom(it.parameterTypes[0]) &&
                resourceClass.isAssignableFrom(it.returnType)
        }?.apply { isAccessible = true }
            ?: return SymbolScanResult.Missing("resource error method not found")

        val symbols = HomeRecommendAutoRefreshSymbols(
            autoRefreshMethod = MethodDescriptor.of(autoRefreshMethod),
            requestMethods = requestMethods.map(MethodDescriptor::of),
            idxField = FieldDescriptor.of(requestSymbols.idxField),
            refreshField = FieldDescriptor.of(requestSymbols.refreshField),
            flushField = FieldDescriptor.of(requestSymbols.flushField),
            resourceErrorMethod = MethodDescriptor.of(resourceError),
            evidence = "requestMethods=${requestMethods.size},param=${requestParamClass.name},returns=${requestMethods.map { it.returnType.name }.distinct().joinToString(limit = 3)}",
        )
        return SymbolScanResult.Found(
            symbols,
            "${autoRefreshMethod.declaringClass.name}.${autoRefreshMethod.name}",
            symbols.evidence,
        )
    }

    private fun homeRequestParamSymbols(requestParamClass: Class<*>?, flushClass: Class<*>): HomeRequestParamSymbols? {
        if (requestParamClass == null) return null
        val fields = requestParamClass.allFields()
            .filter { !Modifier.isStatic(it.modifiers) }
            .toList()
        val idxField = fields.firstOrNull { it.isLongField() } ?: return null
        val refreshField = fields.singleOrNull { it.isBooleanField() } ?: return null
        val flushField = fields.singleOrNull { it.type == flushClass } ?: return null
        return HomeRequestParamSymbols(idxField, refreshField, flushField)
    }

    private fun Method.isHomeRecommendRequestCandidate(flushClass: Class<*>): Boolean =
        parameterCount in 3..4 &&
            returnType == Any::class.java &&
            Modifier.isStatic(modifiers) &&
            !Modifier.isAbstract(modifiers) &&
            !isSynthetic &&
            parameterTypes.last().isKotlinContinuationTypeName() &&
            homeRequestParamSymbols(parameterTypes[0], flushClass) != null

    private fun Class<*>.isKotlinContinuationTypeName(): Boolean =
        name == "kotlin.coroutines.Continuation" ||
            name == "kotlin.coroutines.jvm.internal.ContinuationImpl"

    private fun Field.isLongField(): Boolean =
        type == Long::class.javaPrimitiveType || type == Long::class.javaObjectType

    private fun Field.isBooleanField(): Boolean =
        type == Boolean::class.javaPrimitiveType || type == Boolean::class.javaObjectType

    private fun describeHomeRequestParamCandidate(requestParamClass: Class<*>?, flushClass: Class<*>): String {
        if (requestParamClass == null) return "-"
        val fields = requestParamClass.allFields()
            .filter { !Modifier.isStatic(it.modifiers) }
            .toList()
        val longCount = fields.count { it.isLongField() }
        val booleanCount = fields.count { it.isBooleanField() }
        val flushCount = fields.count { it.type == flushClass }
        return "${requestParamClass.name}:long=$longCount,bool=$booleanCount,flush=$flushCount"
    }

    private fun scanStoryPlayerAd(
        classLoader: ClassLoader,
    ): SymbolScanResult<StoryPlayerAdSymbols> {
        val storyFeedResponse = classLoader.loadClassOrNull(STORY_FEED_RESPONSE)
        val feedGetItems = storyFeedResponse?.declaredMethods?.firstOrNull {
            it.name == "getItems" &&
                it.parameterCount == 0 &&
                List::class.java.isAssignableFrom(it.returnType) &&
                !Modifier.isStatic(it.modifiers) &&
                !Modifier.isAbstract(it.modifiers)
        }?.apply { isAccessible = true }

        val storyPagerPlayer = classLoader.loadClassOrNull(STORY_PAGER_PLAYER)
        val pagerMethods = storyPagerPlayer?.declaredMethods
            ?.filter(::isStoryPagerListMethod)
            ?.distinctBy(Method::toGenericString)
            .orEmpty()

        val rerankTask = classLoader.loadClassOrNull(STORY_AD_RERANK_TASK)
        val rerankInvoke = rerankTask?.declaredMethods?.firstOrNull {
            it.name == "invokeSuspend" &&
                it.parameterCount == 1 &&
                it.parameterTypes[0] == Any::class.java &&
                it.returnType == Any::class.java &&
                !Modifier.isStatic(it.modifiers) &&
                !Modifier.isAbstract(it.modifiers)
        }?.apply { isAccessible = true }
        val unitField = classLoader.loadClassOrNull(KOTLIN_UNIT)
            ?.getDeclaredField("INSTANCE")
            ?.apply { isAccessible = true }

        if (feedGetItems == null && pagerMethods.isEmpty() && (rerankInvoke == null || unitField == null)) {
            return SymbolScanResult.Missing("story player ad hook points not found")
        }

        val symbols = StoryPlayerAdSymbols(
            feedGetItems = feedGetItems?.let(MethodDescriptor::of),
            pagerListMethods = pagerMethods.map(MethodDescriptor::of),
            rerankInvokeSuspend = rerankInvoke?.let(MethodDescriptor::of),
            kotlinUnitField = if (rerankInvoke != null) unitField?.let(FieldDescriptor::of) else null,
            evidence = "feed=${feedGetItems != null},pager=${pagerMethods.size},rerank=${rerankInvoke != null && unitField != null}",
        )
        return SymbolScanResult.Found(symbols, "StoryPlayerAd", symbols.evidence)
    }

    private fun isStoryPagerListMethod(method: Method): Boolean =
        method.returnType == Void.TYPE &&
            method.declaringClass.name == STORY_PAGER_PLAYER &&
            !Modifier.isStatic(method.modifiers) &&
            !Modifier.isAbstract(method.modifiers) &&
            !method.isSynthetic &&
            !method.isBridge &&
            method.parameterCount == 1 &&
            List::class.java.isAssignableFrom(method.parameterTypes[0]) &&
            isStoryDetailListParameter(method)

    private fun isStoryDetailListParameter(method: Method): Boolean {
        val parameterType = method.genericParameterTypes.firstOrNull()?.typeName ?: return true
        return parameterType == List::class.java.name || parameterType.contains(STORY_DETAIL)
    }

    private fun scanVideoDetailBannerAd(
        classLoader: ClassLoader,
    ): SymbolScanResult<VideoDetailBannerAdSymbols> {
        val bizKt = classLoader.loadClassOrNull(G_AD_BIZ_KT)
        val videoDetailType = classLoader.loadClassOrNull(G_AD_VIDEO_DETAIL)
        val underPlayerType = classLoader.loadClassOrNull(I_AD_UNDER_PLAYER)
        val relateType = classLoader.loadClassOrNull(I_AD_VIDEO_RELATE)
        val merchandiseType = classLoader.loadClassOrNull(I_AD_MERCHANDISE)
        val getVideoDetail = if (bizKt != null && videoDetailType != null) {
            bizKt.allMethods().firstOrNull {
                it.name == "getGAdVideoDetail" &&
                    it.parameterCount == 0 &&
                    Modifier.isStatic(it.modifiers) &&
                    videoDetailType.isAssignableFrom(it.returnType)
            }
        } else {
            null
        }

        val baseComponent = classLoader.loadClassOrNull(GEMINI_BINDING_COMPONENT)
        val simpleViewEntry = classLoader.loadClassOrNull(GEMINI_SIMPLE_VIEW_ENTRY)
        val simpleViewEntryConstructor = simpleViewEntry?.declaredConstructors?.firstOrNull {
            it.parameterTypes.contentEquals(arrayOf(View::class.java))
        }?.apply { isAccessible = true }
        val createViewEntry = baseComponent?.allMethods()?.firstOrNull {
            it.name == "createViewEntry" &&
                it.parameterCount == 2 &&
                it.parameterTypes[0] == Context::class.java &&
                it.parameterTypes[1] == ViewGroup::class.java
        }
        val bindToView = baseComponent?.allMethods()?.firstOrNull {
            it.name == "bindToView" &&
                it.parameterCount == 2 &&
                it.parameterTypes[1].name == "kotlin.coroutines.Continuation"
        }
        val unitField = runCatching {
            classLoader.loadClassOrNull(KOTLIN_UNIT)
                ?.getDeclaredField("INSTANCE")
                ?.apply { isAccessible = true }
        }.getOrNull()

        val hasProxyHook = getVideoDetail != null && videoDetailType != null && underPlayerType != null
        val hasRelateGameHook = simpleViewEntryConstructor != null &&
            createViewEntry != null &&
            bindToView != null &&
            unitField != null
        if (!hasProxyHook && !hasRelateGameHook) {
            return SymbolScanResult.Missing("video detail banner hook points not found")
        }

        val symbols = VideoDetailBannerAdSymbols(
            getVideoDetail = getVideoDetail?.let(MethodDescriptor::of),
            videoDetailTypeName = if (hasProxyHook) videoDetailType?.name else null,
            underPlayerTypeName = if (hasProxyHook) underPlayerType?.name else null,
            relateTypeName = relateType?.name,
            merchandiseTypeName = merchandiseType?.name,
            simpleViewEntryConstructor = simpleViewEntryConstructor?.let(ConstructorDescriptor::of),
            createViewEntry = createViewEntry?.let(MethodDescriptor::of),
            bindToView = bindToView?.let(MethodDescriptor::of),
            kotlinUnitField = if (hasRelateGameHook) unitField?.let(FieldDescriptor::of) else null,
            evidence = "proxy=$hasProxyHook,relateGame=$hasRelateGameHook",
        )
        return SymbolScanResult.Found(symbols, "VideoDetailBannerAd", symbols.evidence)
    }

    private fun scanCommentPicture(
        classLoader: ClassLoader,
    ): SymbolScanResult<CommentPictureSymbols> {
        val methods = COMMENT_IMAGE_VIEWER_CLASSES
            .asSequence()
            .mapNotNull { classLoader.loadClassOrNull(it) }
            .flatMap { type ->
                type.allMethods()
                    .filter { method ->
                        method.name == "initView" &&
                            method.parameterCount == 1 &&
                            View::class.java.isAssignableFrom(method.parameterTypes[0])
                    }
            }
            .distinctBy(Method::toGenericString)
            .toList()
        if (methods.isEmpty()) {
            return SymbolScanResult.Missing("comment image initView methods not found")
        }
        val symbols = CommentPictureSymbols(
            initViewMethods = methods.map(MethodDescriptor::of),
            evidence = "methods=${methods.size}",
        )
        return SymbolScanResult.Found(
            symbols,
            methods.joinToString("|") { "${it.declaringClass.name}.${it.name}" },
            symbols.evidence,
        )
    }

    private fun scanHomeTopBar(
        classLoader: ClassLoader,
    ): SymbolScanResult<HomeTopBarSymbols> {
        val menuItemClass = classLoader.loadClassOrNull(HOME_MENU_ITEM_CLASS)
        val gameMenuMethod = menuItemClass?.declaredMethods?.firstOrNull {
            it.name == "b" &&
                it.parameterTypes.contentEquals(arrayOf(Menu::class.java, MenuInflater::class.java))
        }?.apply { isAccessible = true }

        val baseFragmentClass = classLoader.loadClassOrNull(HOME_BASE_MAIN_FRAME_FRAGMENT)
        val baseOnViewCreated = baseFragmentClass?.declaredMethods?.firstOrNull {
            it.name == "onViewCreated" &&
                it.parameterTypes.contentEquals(arrayOf(View::class.java, Bundle::class.java))
        }?.apply { isAccessible = true }

        val mainFragmentClass = classLoader.loadClassOrNull(HOME_MAIN_FRAGMENT)
        val defaultWordClass = classLoader.loadClassOrNull(HOME_DEFAULT_SEARCH_WORD_CLASS)
        val defaultWordMethods = if (mainFragmentClass != null && defaultWordClass != null) {
            mainFragmentClass.allMethods()
                .filter { method ->
                    method.returnType == Void.TYPE &&
                        method.parameterCount == 1 &&
                        method.parameterTypes[0] == defaultWordClass &&
                        !Modifier.isStatic(method.modifiers) &&
                        !Modifier.isAbstract(method.modifiers)
                }
                .distinctBy(Method::toGenericString)
                .toList()
        } else {
            emptyList()
        }

        if (gameMenuMethod == null && baseOnViewCreated == null && defaultWordMethods.isEmpty()) {
            return SymbolScanResult.Missing("home top bar hook points not found")
        }
        val symbols = HomeTopBarSymbols(
            gameMenuMethod = gameMenuMethod?.let(MethodDescriptor::of),
            baseOnViewCreated = baseOnViewCreated?.let(MethodDescriptor::of),
            defaultWordMethods = defaultWordMethods.map(MethodDescriptor::of),
            evidence = "game=${gameMenuMethod != null},onViewCreated=${baseOnViewCreated != null},defaultWord=${defaultWordMethods.size}",
        )
        return SymbolScanResult.Found(symbols, "HomeTopBarPurify", symbols.evidence)
    }

    private fun scanBottomBar(
        classLoader: ClassLoader,
    ): SymbolScanResult<BottomBarSymbols> {
        val parserMethods = buildList {
            addParserMethod(classLoader, "com.alibaba.fastjson.JSON", "parseObject", "java.lang.String", "java.lang.reflect.Type", "int", "[Lcom.alibaba.fastjson.parser.Feature;")
            addParserMethod(classLoader, "com.alibaba.fastjson.JSON", "parseObject", "java.lang.String", "java.lang.Class", "int", "[Lcom.alibaba.fastjson.parser.Feature;")
            addParserMethod(classLoader, "com.alibaba.fastjson2.JSON", "parseObject", "java.lang.String", "java.lang.reflect.Type", "[Lcom.alibaba.fastjson2.JSONReader\$Feature;")
            addParserMethod(classLoader, "com.google.gson.Gson", "fromJson", "java.lang.String", "java.lang.reflect.Type")
            addParserMethod(classLoader, "com.google.gson.Gson", "fromJson", "java.io.Reader", "java.lang.reflect.Type")
        }.distinctBy(Method::toGenericString)

        val resourceMethods = BOTTOM_RESOURCE_MANAGER_CLASSES
            .asSequence()
            .mapNotNull { classLoader.loadClassOrNull(it) }
            .flatMap { type ->
                type.declaredMethods.asSequence()
                    .filter { method ->
                        method.name in BOTTOM_RESOURCE_MANAGER_METHOD_NAMES &&
                            List::class.java.isAssignableFrom(method.returnType) &&
                            method.parameterCount == 2 &&
                            method.parameterTypes.firstOrNull() == Int::class.javaPrimitiveType &&
                            List::class.java.isAssignableFrom(method.parameterTypes[1])
                    }
            }
            .distinctBy(Method::toGenericString)
            .toList()

        if (parserMethods.isEmpty() && resourceMethods.isEmpty()) {
            return SymbolScanResult.Missing("bottom bar hook points not found")
        }
        val symbols = BottomBarSymbols(
            parserMethods = parserMethods.map(MethodDescriptor::of),
            resourceMethods = resourceMethods.map(MethodDescriptor::of),
            evidence = "parsers=${parserMethods.size},resources=${resourceMethods.size}",
        )
        return SymbolScanResult.Found(symbols, "BottomBar", symbols.evidence)
    }

    private fun scanHomeRecommendFeed(
        classLoader: ClassLoader,
    ): SymbolScanResult<HomeRecommendFeedSymbols> {
        val holderDataClass = classLoader.loadClassOrNull(PEGASUS_HOLDER_DATA)
            ?: return SymbolScanResult.Missing("PegasusHolderData class not found")
        val responseClasses = PEGASUS_RESPONSE_CLASSES.mapNotNull { classLoader.loadClassOrNull(it) }
        if (responseClasses.isEmpty()) return SymbolScanResult.Missing("PegasusResponse class not found")

        val getHolderType = holderDataClass.allMethods().firstOrNull {
            it.name == "getHolderType" &&
                it.parameterCount == 0 &&
                it.returnType == String::class.java &&
                !Modifier.isStatic(it.modifiers)
        } ?: return SymbolScanResult.Missing("getHolderType not found")

        val responseGetItems = responseClasses.mapNotNull { responseClass ->
            val getItems = responseClass.allMethods().firstOrNull {
                it.name == "getItems" &&
                    it.parameterCount == 0 &&
                    List::class.java.isAssignableFrom(it.returnType) &&
                    !Modifier.isStatic(it.modifiers) &&
                    !Modifier.isAbstract(it.modifiers)
            } ?: return@mapNotNull null
            val itemsField = responseClass.allFields()
                .filter { List::class.java.isAssignableFrom(it.type) }
                .singleOrNull()
            PegasusResponseGetItemsSymbols(MethodDescriptor.of(getItems), itemsField?.let(FieldDescriptor::of))
        }.distinctBy { it.getItems.declaringClassName + "#" + it.getItems.name }
        if (responseGetItems.isEmpty()) return SymbolScanResult.Missing("PegasusResponse.getItems not found")

        val baseDataClass = BASE_PEGASUS_DATA_CLASSES.firstNotNullOfOrNull { classLoader.loadClassOrNull(it) }
        val holderStyleClass = classLoader.loadClassOrNull(PEGASUS_HOLDER_STYLE)
        val adInfoClass = classLoader.loadClassOrNull(AD_INFO)
        val symbols = HomeRecommendFeedSymbols(
            responseGetItems = responseGetItems,
            getHolderType = MethodDescriptor.of(getHolderType),
            getBizType = holderDataClass.noArgMethod("getBizType")?.let(MethodDescriptor::of),
            getHolderStyle = holderDataClass.noArgMethod("getHolderStyle")?.let(MethodDescriptor::of),
            isSmallCard = holderStyleClass?.allMethods()?.firstOrNull {
                it.name == "isSmallCard" &&
                    it.parameterCount == 0 &&
                    it.returnType == Boolean::class.javaPrimitiveType
            }?.let(MethodDescriptor::of),
            getAdInfo = baseDataClass?.noArgMethod("getAdInfo")?.let(MethodDescriptor::of),
            getCardType = baseDataClass?.noArgMethod("getCardType")?.let(MethodDescriptor::of),
            getCardGoto = baseDataClass?.noArgMethod("getCardGoto")?.let(MethodDescriptor::of),
            getGoTo = baseDataClass?.noArgMethod("getGoTo")?.let(MethodDescriptor::of),
            getUri = baseDataClass?.noArgMethod("getUri")?.let(MethodDescriptor::of),
            adInfoClassName = adInfoClass?.name,
            evidence = "responses=${responseGetItems.size},holder=${holderDataClass.name},base=${baseDataClass?.name}",
        )
        return SymbolScanResult.Found(symbols, responseGetItems.joinToString("|") { it.getItems.declaringClassName }, symbols.evidence)
    }

    private fun scanHomeComponentHide(
        classLoader: ClassLoader,
    ): SymbolScanResult<HomeComponentHideSymbols> {
        val methods = BASE_HOME_FRAGMENT_CLASSES
            .asSequence()
            .mapNotNull { classLoader.loadClassOrNull(it) }
            .flatMap { type ->
                HOME_COMPONENT_LIFECYCLE_METHODS.asSequence().flatMap { methodName ->
                    type.allMethods().asSequence().filter { it.name == methodName }
                }
            }
            .distinctBy(Method::toGenericString)
            .toList()
        if (methods.isEmpty()) return SymbolScanResult.Missing("base home fragment lifecycle methods not found")
        val symbols = HomeComponentHideSymbols(
            baseHomeFragmentMethods = methods.map(MethodDescriptor::of),
            evidence = "baseLifecycle=${methods.size}",
        )
        return SymbolScanResult.Found(symbols, methods.joinToString("|") { it.declaringClass.name }, symbols.evidence)
    }

    private fun scanVideoComment(
        classLoader: ClassLoader,
    ): SymbolScanResult<VideoCommentSymbols> {
        val disableCommentConstructors = THESEUS_TAB_PAGER_SERVICE
            .asSequence()
            .mapNotNull { classLoader.loadClassOrNull(it) }
            .flatMap { it.declaredConstructors.asSequence() }
            .onEach { it.isAccessible = true }
            .map(ConstructorDescriptor::of)
            .toList()

        val quickReplyViewModelMethods = COMMENT_VIEW_MODEL_CLASSES
            .asSequence()
            .mapNotNull { classLoader.loadClassOrNull(it) }
            .flatMap { type ->
                type.declaredMethods.asSequence().filter { method ->
                    method.returnType == Void.TYPE &&
                        method.parameterCount == 1 &&
                        !method.name.contains("lambda", ignoreCase = true) &&
                        method.name in QUICK_REPLY_METHOD_NAMES &&
                        method.parameterTypes.firstOrNull()?.isCommentActionType() == true
                }
            }
            .distinctBy(Method::toGenericString)
            .toList()

        val quickReplyDialogMethods = QUICK_REPLY_DIALOG_COLLECTOR_CLASSES
            .asSequence()
            .mapNotNull { classLoader.loadClassOrNull(it) }
            .flatMap { type ->
                type.declaredMethods.asSequence().filter { method ->
                    method.parameterCount == 2 &&
                        !method.name.contains("lambda", ignoreCase = true) &&
                        method.parameterTypes.firstOrNull()?.isQuickReplyDialogIntentType() == true &&
                        method.parameterTypes.getOrNull(1)?.let { Continuation::class.java.isAssignableFrom(it) } == true
                }
            }
            .distinctBy(Method::toGenericString)
            .toList()

        val voteWidgetMethods = (CMT_VOTE_WIDGET_CLASSES + CMT_MOUNT_WIDGET_CLASSES + COMMENT_VOTE_VIEW_CLASSES)
            .asSequence()
            .mapNotNull { classLoader.loadClassOrNull(it) }
            .flatMap { type ->
                type.declaredMethods.asSequence().filter { method ->
                    method.returnType == Void.TYPE &&
                        method.parameterCount == 1 &&
                        !method.name.contains("lambda", ignoreCase = true) &&
                        method.name in VOTE_WIDGET_METHOD_NAMES &&
                        method.parameterTypes.firstOrNull().isVoteWidgetPayload()
                }
            }
            .distinctBy(Method::toGenericString)
            .toList()

        val followWidgetMethods = COMMENT_FOLLOW_WIDGET_CLASSES
            .asSequence()
            .mapNotNull { classLoader.loadClassOrNull(it) }
            .flatMap { type ->
                type.declaredMethods.asSequence().filter { method ->
                    method.returnType == Void.TYPE &&
                        method.parameterCount == 1 &&
                        !method.name.contains("lambda", ignoreCase = true) &&
                        method.name in FOLLOW_WIDGET_METHOD_NAMES &&
                        method.parameterTypes.firstOrNull().isFollowWidgetPayload()
                }
            }
            .distinctBy(Method::toGenericString)
            .toList()

        val headerDecorativeMethods = COMMENT_HEADER_DECORATIVE_VIEW_CLASSES
            .asSequence()
            .mapNotNull { classLoader.loadClassOrNull(it) }
            .flatMap { type ->
                type.declaredMethods.asSequence().filter { method ->
                    method.returnType == Void.TYPE &&
                        method.parameterCount == 1 &&
                        !method.name.contains("lambda", ignoreCase = true) &&
                        method.name in HEADER_DECORATIVE_METHOD_NAMES &&
                        List::class.java.isAssignableFrom(method.parameterTypes[0])
                }
            }
            .distinctBy(Method::toGenericString)
            .toList()

        val searchUrlsMethod = COMMENT_CONTENT_CLASSES
            .asSequence()
            .mapNotNull { classLoader.loadClassOrNull(it) }
            .mapNotNull { it.allMethods().firstOrNull { method -> method.name == "internalGetUrls" && method.parameterCount == 0 } }
            .firstOrNull()

        val emptyPageHooks = scanVideoCommentEmptyPageHooks(classLoader)
        val mainListOnNextMethods = COMMENT_MAIN_LIST_OBSERVERS
            .asSequence()
            .mapNotNull { classLoader.loadClassOrNull(it) }
            .mapNotNull { it.allMethods().firstOrNull { method -> method.name == "onNext" && method.parameterCount == 1 } }
            .distinctBy(Method::toGenericString)
            .toList()

        val total = disableCommentConstructors.size + quickReplyViewModelMethods.size + quickReplyDialogMethods.size +
            voteWidgetMethods.size + followWidgetMethods.size + headerDecorativeMethods.size +
            (if (searchUrlsMethod != null) 1 else 0) + emptyPageHooks.size + mainListOnNextMethods.size
        if (total == 0) return SymbolScanResult.Missing("video comment hook points not found")

        val symbols = VideoCommentSymbols(
            disableCommentConstructors = disableCommentConstructors,
            quickReplyViewModelMethods = quickReplyViewModelMethods.map(MethodDescriptor::of),
            quickReplyDialogMethods = quickReplyDialogMethods.map(MethodDescriptor::of),
            voteWidgetMethods = voteWidgetMethods.map(MethodDescriptor::of),
            followWidgetMethods = followWidgetMethods.map(MethodDescriptor::of),
            headerDecorativeMethods = headerDecorativeMethods.map(MethodDescriptor::of),
            searchUrlsMethod = searchUrlsMethod?.let(MethodDescriptor::of),
            emptyPageHooks = emptyPageHooks,
            mainListOnNextMethods = mainListOnNextMethods.map(MethodDescriptor::of),
            evidence = "constructors=${disableCommentConstructors.size},quick=${quickReplyViewModelMethods.size + quickReplyDialogMethods.size},widgets=${voteWidgetMethods.size + followWidgetMethods.size + headerDecorativeMethods.size},empty=${emptyPageHooks.size},main=${mainListOnNextMethods.size}",
        )
        return SymbolScanResult.Found(symbols, "VideoComment", symbols.evidence)
    }

    private fun scanVideoCommentEmptyPageHooks(classLoader: ClassLoader): List<VideoCommentEmptyPageSymbols> {
        val subjectControl = COMMENT_SUBJECT_CONTROL_CLASSES.mapNotNull { classLoader.loadClassOrNull(it) }
            .mapNotNull { type ->
                val emptyPageClass = classLoader.loadClassOrNull(type.name.replace("SubjectControl", "EmptyPage"))
                    ?: return@mapNotNull null
                val defaultInstance = emptyPageClass.declaredFields.firstOrNull { it.name == "DEFAULT_INSTANCE" }
                    ?.apply { isAccessible = true } ?: return@mapNotNull null
                val method = type.allMethods().firstOrNull { it.name == "getEmptyPage" && it.parameterCount == 0 }
                    ?: return@mapNotNull null
                VideoCommentEmptyPageSymbols(MethodDescriptor.of(method), FieldDescriptor.of(defaultInstance))
            }
        val subjectDesc = COMMENT_SUBJECT_DESC_CLASSES.mapNotNull { classLoader.loadClassOrNull(it) }
            .mapNotNull { type ->
                val emptyPageClass = classLoader.loadClassOrNull(type.name.replace("SubjectDescriptionReply", "EmptyPage"))
                    ?: return@mapNotNull null
                val defaultInstance = emptyPageClass.declaredFields.firstOrNull { it.name == "DEFAULT_INSTANCE" }
                    ?.apply { isAccessible = true } ?: return@mapNotNull null
                val method = type.allMethods().firstOrNull { it.name == "getEmptyPage" && it.parameterCount == 0 }
                    ?: return@mapNotNull null
                VideoCommentEmptyPageSymbols(MethodDescriptor.of(method), FieldDescriptor.of(defaultInstance))
            }
        return (subjectControl + subjectDesc).distinctBy { it.getEmptyPage.declaringClassName }
    }

    private fun scanSkipVideoAd(
        classLoader: ClassLoader,
    ): SymbolScanResult<SkipVideoAdSymbols> {
        val playViewMethods = PLAYER_MOSS_CANDIDATES
            .asSequence()
            .mapNotNull { classLoader.loadClassOrNull(it) }
            .flatMap { type ->
                type.allMethods().asSequence().filter { method ->
                    method.isConcreteInstanceHookMethod() &&
                        method.name in PLAY_VIEW_METHOD_NAMES &&
                        method.parameterCount >= 1 &&
                        method.parameterTypes.firstOrNull()?.isPlayViewRequestType() == true
                }
            }
            .distinctBy(Method::toGenericString)
            .toList()
        val playerCoreClasses = (listOf(PLAYER_CORE_SERVICE_INTERFACE) + PLAYER_CORE_SERVICE_CANDIDATES)
            .mapNotNull { classLoader.loadClassOrNull(it) }
            .distinctBy { it.name }
        val cardClasses = (listOf(CARD_PLAYER_CONTEXT_INTERFACE) + CARD_PLAYER_CONTEXT_CANDIDATES)
            .mapNotNull { classLoader.loadClassOrNull(it) }
            .distinctBy { it.name }
        val playerCoreCurrent = playerCoreClasses.flatMap { it.currentPositionMethods() }.distinctBy(Method::toGenericString)
        val playerCoreState = playerCoreClasses.flatMap { it.stateMethods(STATE_METHOD_NAMES) }.distinctBy(Method::toGenericString)
        val cardCurrent = cardClasses.flatMap { it.currentPositionMethods() }.distinctBy(Method::toGenericString)
        val cardState = cardClasses.flatMap { it.stateMethods(CARD_STATE_METHOD_NAMES) }.distinctBy(Method::toGenericString)
        val total = playViewMethods.size + playerCoreCurrent.size + playerCoreState.size + cardCurrent.size + cardState.size
        if (total == 0) return SymbolScanResult.Missing("skip video ad hook points not found")
        val symbols = SkipVideoAdSymbols(
            playViewMethods = playViewMethods.map(MethodDescriptor::of),
            playerCoreCurrentPositionMethods = playerCoreCurrent.map(MethodDescriptor::of),
            playerCoreStateMethods = playerCoreState.map(MethodDescriptor::of),
            cardCurrentPositionMethods = cardCurrent.map(MethodDescriptor::of),
            cardStateMethods = cardState.map(MethodDescriptor::of),
            evidence = "play=${playViewMethods.size},core=${playerCoreCurrent.size}/${playerCoreState.size},card=${cardCurrent.size}/${cardState.size}",
        )
        return SymbolScanResult.Found(symbols, "SkipVideoAd", symbols.evidence)
    }

    private fun scanSkipVideoAdProgress(
        classLoader: ClassLoader,
    ): SymbolScanResult<SkipVideoAdProgressSymbols> {
        val progressOnDraw = classLoader.loadClassOrNull(PROGRESS_BAR_CLASS)
            ?.allMethods()
            ?.firstOrNull { it.name == "onDraw" && it.parameterTypes.contentEquals(arrayOf(Canvas::class.java)) }
        val storyOnStart = classLoader.loadClassOrNull(STORY_SEEK_BAR_CLASS)
            ?.allMethods()
            ?.filter {
                it.name == "onStart" &&
                    it.parameterCount == 1 &&
                    it.parameterTypes.firstOrNull()?.isNumericType() == true
            }
            ?.distinctBy(Method::toGenericString)
            ?.toList()
            .orEmpty()
        val inlineUpdate = INLINE_PROGRESS_CLASSES
            .asSequence()
            .mapNotNull { classLoader.loadClassOrNull(it) }
            .flatMap { type ->
                type.allMethods().asSequence().filter { it.name == "updateProgress" && it.parameterCount == 0 }
            }
            .distinctBy(Method::toGenericString)
            .toList()
        val panelWidget = classLoader.loadClassOrNull(PANEL_WIDGET_KT_CLASS)
        if (progressOnDraw == null && storyOnStart.isEmpty() && inlineUpdate.isEmpty()) {
            return SymbolScanResult.Missing("skip video ad progress hook points not found")
        }
        val symbols = SkipVideoAdProgressSymbols(
            progressOnDraw = progressOnDraw?.let(MethodDescriptor::of),
            storyOnStartMethods = storyOnStart.map(MethodDescriptor::of),
            inlineUpdateMethods = inlineUpdate.map(MethodDescriptor::of),
            panelWidgetKtClassName = panelWidget?.name,
            evidence = "draw=${progressOnDraw != null},story=${storyOnStart.size},inline=${inlineUpdate.size},panel=${panelWidget != null}",
        )
        return SymbolScanResult.Found(symbols, "SkipVideoAdProgress", symbols.evidence)
    }

    private fun scanSkipVideoAdAutoLike(
        classLoader: ClassLoader,
    ): SymbolScanResult<SkipVideoAdAutoLikeSymbols> {
        val detailLikeInflate = classLoader.loadClassOrNull(DETAIL_LIKE_COMPONENT)
            ?.allMethods()
            ?.firstOrNull { it.isDetailLikeInflateMethod() }
        val detailLikeStateOwner = classLoader.loadClassOrNull(DETAIL_LIKE_STATE_OWNER)
        val storyActionOwner = classLoader.loadClassOrNull(STORY_ACTION_OWNER)
        val storyWidgetClasses = listOf(STORY_LIKE_WIDGET, STORY_LANDSCAPE_LIKE_WIDGET)
            .mapNotNull { classLoader.loadClassOrNull(it) }
            .distinctBy { it.name }
        val storyBindMethods = storyWidgetClasses
            .flatMap { type -> type.allMethods().filter { it.isStoryBindMethod() } }
            .distinctBy(Method::toGenericString)
        val geminiLikeWidget = classLoader.loadClassOrNull(GEMINI_PLAYER_LIKE_WIDGET)
        if (detailLikeInflate == null && storyWidgetClasses.isEmpty() && geminiLikeWidget == null) {
            return SymbolScanResult.Missing("skip video ad auto-like hook points not found")
        }
        val symbols = SkipVideoAdAutoLikeSymbols(
            detailLikeInflateMethod = detailLikeInflate?.let(MethodDescriptor::of),
            detailLikeStateOwnerClassName = detailLikeStateOwner?.name,
            storyWidgetClassNames = storyWidgetClasses.map { it.name },
            storyActionOwnerClassName = storyActionOwner?.name,
            storyBindMethods = storyBindMethods.map(MethodDescriptor::of),
            geminiLikeWidgetClassName = geminiLikeWidget?.name,
            evidence = "detail=${detailLikeInflate != null},detailOwner=${detailLikeStateOwner != null},storyClasses=${storyWidgetClasses.size},storyOwner=${storyActionOwner != null},storyBind=${storyBindMethods.size},gemini=${geminiLikeWidget != null}",
        )
        return SymbolScanResult.Found(symbols, "SkipVideoAdAutoLike", symbols.evidence)
    }

    private fun scanChronosPromotion(
        classLoader: ClassLoader,
    ): SymbolScanResult<ChronosPromotionSymbols> {
        val classes = ArrayList<NamedClassSymbol>()
        val methodGroups = ArrayList<NamedMethodGroup>()

        fun addClass(id: String, className: String): Class<*>? {
            val type = classLoader.loadClassOrNull(className) ?: return null
            classes += NamedClassSymbol(id, type.name)
            return type
        }

        fun addMethods(id: String, methods: List<Method>) {
            if (methods.isNotEmpty()) {
                methodGroups += NamedMethodGroup(id, methods.distinctBy(Method::toGenericString).map(MethodDescriptor::of))
            }
        }

        fun addMethods(id: String, methods: Sequence<Method>) {
            addMethods(id, methods.toList())
        }

        val rpcHandler = addClass(CHRONOS_ID_RPC_HANDLER, CHRONOS_RPC_HANDLER)
        val remoteHandler = addClass(CHRONOS_ID_REMOTE_HANDLER, REMOTE_SERVICE_HANDLER)
        val senderType = addClass(CHRONOS_ID_MESSAGE_SENDER, CHRONOS_MESSAGE_SENDER)
        val function2Type = addClass(CHRONOS_ID_FUNCTION2, KOTLIN_FUNCTION2)

        val updateDetailStateRequest = addClass(CHRONOS_ID_UPDATE_DETAIL_STATE_REQUEST, UPDATE_VIDEO_DETAIL_STATE_REQUEST)
        val openUrlRequest = addClass(CHRONOS_ID_OPEN_URL_REQUEST, OPEN_URL_SCHEME_REQUEST)
        val adDanmakuEventRequest = addClass(CHRONOS_ID_AD_DANMAKU_EVENT_REQUEST, AD_DANMAKU_EVENT_REQUEST)
        val notifyCommercialEventRequest = addClass(CHRONOS_ID_NOTIFY_COMMERCIAL_EVENT_REQUEST, NOTIFY_COMMERCIAL_EVENT_REQUEST)
        val getViewProgressRequest = addClass(CHRONOS_ID_GET_VIEW_PROGRESS_REQUEST, GET_VIEW_PROGRESS_REQUEST)
        val getDmViewRequest = addClass(CHRONOS_ID_GET_DM_VIEW_REQUEST, GET_DM_VIEW_REQUEST)
        val viewProgressReply = addClass(CHRONOS_ID_VIEW_PROGRESS_REPLY, VIEW_PROGRESS_REPLY)
        val uniteViewProgressReply = addClass(CHRONOS_ID_UNITE_VIEW_PROGRESS_REPLY, UNITE_VIEW_PROGRESS_REPLY)
        val dmViewReply = addClass(CHRONOS_ID_DM_VIEW_REPLY, DM_VIEW_REPLY)
        val addCustomRequest = addClass(CHRONOS_ID_ADD_CUSTOM_DANMAKU_REQUEST, ADD_CUSTOM_DANMAKU_REQUEST)
        val dmViewChangeRequest = addClass(CHRONOS_ID_DM_VIEW_CHANGE_REQUEST, DM_VIEW_CHANGE_REQUEST)
        val commandDanmakuSentRequest = addClass(CHRONOS_ID_COMMAND_DANMAKU_SENT_REQUEST, COMMAND_DANMAKU_SENT_REQUEST)
        val commandDmListRequest = addClass(CHRONOS_ID_COMMAND_DM_LIST_REQUEST, COMMAND_DM_LIST_REQUEST)
        val commandDmListResponse = addClass(CHRONOS_ID_COMMAND_DM_LIST_RESPONSE, COMMAND_DM_LIST_RESPONSE)
        val videoDetailStateChangeRequest = addClass(CHRONOS_ID_VIDEO_DETAIL_STATE_CHANGE_REQUEST, VIDEO_DETAIL_STATE_CHANGE_REQUEST)
        val viewProgressDetail = addClass(CHRONOS_ID_VIEW_PROGRESS_DETAIL, VIEW_PROGRESS_DETAIL)

        if (rpcHandler != null) {
            val invokeMethods = rpcHandler.allMethods()
                .filter { it.name == "invoke" && it.parameterCount == 6 }
                .distinctBy(Method::toGenericString)
                .toList()
            if (
                (updateDetailStateRequest != null && hasDetailStateGetterMethods(updateDetailStateRequest, DETAIL_STATE_GETTERS)) ||
                openUrlRequest != null ||
                adDanmakuEventRequest != null ||
                notifyCommercialEventRequest != null
            ) {
                addMethods(CHRONOS_METHOD_RPC_RECEIVE, invokeMethods)
            }
            if (getViewProgressRequest != null && function2Type != null && (viewProgressReply != null || uniteViewProgressReply != null)) {
                addMethods(CHRONOS_METHOD_LOCAL_VIEW_PROGRESS, invokeMethods)
            }
            if (getDmViewRequest != null && dmViewReply != null && function2Type != null) {
                addMethods(CHRONOS_METHOD_LOCAL_DM_VIEW, invokeMethods)
            }
        }

        if (remoteHandler != null) {
            if (videoDetailStateChangeRequest != null &&
                hasDetailStateGetterMethods(videoDetailStateChangeRequest, VIDEO_DETAIL_STATE_CHANGE_GETTERS)
            ) {
                addMethods(
                    CHRONOS_METHOD_REMOTE_VIDEO_DETAIL_STATE,
                    remoteHandler.allMethods().filter {
                        it.name == "onVideoDetailStateChanged" &&
                            it.parameterCount == 1 &&
                            it.parameterTypes[0] == videoDetailStateChangeRequest
                    },
                )
            }
            if (viewProgressDetail != null) {
                addMethods(
                    CHRONOS_METHOD_REMOTE_VIEW_PROGRESS,
                    remoteHandler.allMethods().filter {
                        it.name == "configChronos" &&
                            it.parameterTypes.contentEquals(
                                arrayOf(viewProgressDetail, java.lang.Long.TYPE, java.lang.Long.TYPE),
                            )
                    },
                )
            }
            addMethods(
                CHRONOS_METHOD_REMOTE_COMMAND_DANMAKU,
                remoteHandler.allMethods().filter {
                    it.name == "setCommandDanmakus" &&
                        it.parameterCount == 1 &&
                        List::class.java.isAssignableFrom(it.parameterTypes[0])
                },
            )
            addMethods(
                CHRONOS_METHOD_REMOTE_ADD_DANMAKU,
                remoteHandler.allMethods().filter {
                    it.name == "addDanmaku" &&
                        it.parameterCount == 4 &&
                        it.parameterTypes[0] == String::class.java &&
                        it.parameterTypes[1] == java.lang.Integer.TYPE &&
                        Map::class.java.isAssignableFrom(it.parameterTypes[3])
                },
            )
            addMethods(
                CHRONOS_METHOD_REMOTE_AD_FLOAT_EXPOSURE,
                remoteHandler.allMethods().filter {
                    it.name == "adDanmakuExposureRequest" &&
                        it.parameterTypes.contentEquals(arrayOf(List::class.java, java.lang.Long.TYPE, java.lang.Long.TYPE))
                },
            )
        }

        if (senderType != null) {
            if (addCustomRequest != null || commandDanmakuSentRequest != null || (dmViewChangeRequest != null && dmViewReply != null)) {
                addMethods(
                    CHRONOS_METHOD_MESSAGE_SEND,
                    senderType.allMethods().filter {
                        it.name == "e" &&
                            it.parameterCount == 2 &&
                            Map::class.java.isAssignableFrom(it.parameterTypes[1])
                    },
                )
            }
            if (commandDmListRequest != null && commandDmListResponse != null && function2Type != null) {
                addMethods(
                    CHRONOS_METHOD_COMMAND_DM_LIST,
                    senderType.allMethods().filter {
                        it.name == "a" &&
                            it.parameterCount == 5 &&
                            Map::class.java.isAssignableFrom(it.parameterTypes[1]) &&
                            it.parameterTypes[2] == Class::class.java &&
                            it.parameterTypes[3].name == KOTLIN_FUNCTION2
                    },
                )
            }
        }

        addClass(CHRONOS_ID_AD_DANMAKU_DELEGATE, AD_DANMAKU_DELEGATE)?.let { delegate ->
            addMethods(
                CHRONOS_METHOD_AD_DANMAKU_FEED,
                delegate.allMethods().filter {
                    it.name == "feedData2Chronos" &&
                        it.parameterTypes.contentEquals(arrayOf(List::class.java, java.lang.Long.TYPE, java.lang.Long.TYPE))
                },
            )
        }
        addClass(CHRONOS_ID_INTERACT_LAYER_SERVICE, INTERACT_LAYER_SERVICE)?.let { service ->
            if (viewProgressDetail != null) {
                addMethods(
                    CHRONOS_METHOD_INTERACT_LAYER_VIEW_PROGRESS,
                    service.allMethods().filter {
                        it.name == "getViewProgressDetail" &&
                            it.parameterCount == 0 &&
                            viewProgressDetail.isAssignableFrom(it.returnType)
                    },
                )
            }
        }
        addClass(CHRONOS_ID_GEMINI_OPERATION_WIDGET, GEMINI_OPERATION_WIDGET)?.let { widget ->
            addMethods(
                CHRONOS_METHOD_GEMINI_OPERATION_RENDER,
                widget.allMethods().filter {
                    it.name == "i" &&
                        it.parameterCount == 0 &&
                        it.returnType == java.lang.Void.TYPE
                },
            )
        }
        addClass(CHRONOS_ID_GEMINI_OPERATION_OBSERVER, GEMINI_OPERATION_OBSERVER)?.let { observer ->
            if (viewProgressDetail != null) {
                addMethods(
                    CHRONOS_METHOD_GEMINI_OPERATION_UPDATE,
                    observer.allMethods().filter {
                        it.name == "onUpdate" &&
                            it.parameterCount == 1 &&
                            viewProgressDetail.isAssignableFrom(it.parameterTypes[0])
                    },
                )
            }
        }

        val totalMethods = methodGroups.sumOf { it.methods.size }
        if (totalMethods == 0) {
            return SymbolScanResult.Missing("chronos promotion hook points not found")
        }
        val symbols = ChronosPromotionSymbols(
            classSymbols = classes.distinctBy { it.id },
            methodGroups = methodGroups.distinctBy { it.id },
            evidence = "classes=${classes.distinctBy { it.id }.size},methodGroups=${methodGroups.size},methods=$totalMethods",
        )
        return SymbolScanResult.Found(symbols, "ChronosPromotion", symbols.evidence)
    }

    private fun hasDetailStateGetterMethods(requestType: Class<*>, getters: List<String>): Boolean =
        getters.any { getter -> requestType.allMethods().any { it.name == getter && it.parameterCount == 0 } }

    private fun MutableList<Method>.addParserMethod(
        classLoader: ClassLoader,
        className: String,
        methodName: String,
        vararg parameterTypeNames: String,
    ) {
        val type = classLoader.loadClassOrNull(className) ?: return
        val parameterTypes = parameterTypeNames.map { resolveParameterType(classLoader, it) }
            .takeIf { it.none { resolved -> resolved == null } }
            ?.filterNotNull()
            ?.toTypedArray()
            ?: return
        val method = type.allMethods().firstOrNull {
            it.name == methodName && it.parameterTypes.contentEquals(parameterTypes)
        } ?: return
        add(method)
    }

    private fun resolveParameterType(classLoader: ClassLoader, typeName: String): Class<*>? = when (typeName) {
        "int" -> Int::class.javaPrimitiveType
        else -> classLoader.loadClassOrNull(typeName)
    }

    private fun Class<*>.noArgMethod(name: String): Method? =
        allMethods().firstOrNull {
            it.name == name &&
                it.parameterCount == 0 &&
                !Modifier.isStatic(it.modifiers)
        }?.apply { isAccessible = true }

    private fun Class<*>.isCommentActionType(): Boolean {
        val simpleName = simpleName
        val className = name
        return className.contains(".comment3.", ignoreCase = true) &&
            (simpleName.contains("Action", ignoreCase = true) ||
                simpleName.contains("Intent", ignoreCase = true))
    }

    private fun Class<*>.isQuickReplyDialogIntentType(): Boolean {
        val className = name
        return className.endsWith("PublishDialogIntent", ignoreCase = true) ||
            className.contains("PublishDialogIntent", ignoreCase = true)
    }

    private fun Class<*>?.isVoteWidgetPayload(): Boolean {
        val type = this ?: return false
        val simpleName = type.simpleName
        val className = type.name
        return simpleName.isNotBlank() && (
            simpleName.contains("Vote", ignoreCase = true) ||
                simpleName.contains("Reply", ignoreCase = true) ||
                simpleName.contains("Item", ignoreCase = true) ||
                simpleName.contains("Data", ignoreCase = true) ||
                className.contains("vote", ignoreCase = true) ||
                className.contains("reply", ignoreCase = true)
            )
    }

    private fun Class<*>?.isFollowWidgetPayload(): Boolean {
        val type = this ?: return false
        val simpleName = type.simpleName
        val className = type.name
        return simpleName.isNotBlank() && (
            simpleName.contains("Follow", ignoreCase = true) ||
                simpleName.contains("User", ignoreCase = true) ||
                simpleName.contains("Item", ignoreCase = true) ||
                simpleName.contains("Data", ignoreCase = true) ||
                className.contains("follow", ignoreCase = true)
            )
    }

    private fun Class<*>.isPlayViewRequestType(): Boolean {
        val methods = allMethods().toList()
        return methods.any { it.name == "getBvid" && it.parameterCount == 0 } &&
            methods.any { it.name == "getVod" && it.parameterCount == 0 }
    }

    private fun Class<*>.currentPositionMethods(): List<Method> =
        allMethods()
            .filter {
                it.isConcreteInstanceHookMethod() &&
                    it.name == "getCurrentPosition" &&
                    it.parameterCount == 0 &&
                    it.returnType.isNumericType()
            }
            .onEach { it.isAccessible = true }
            .toList()

    private fun Class<*>.stateMethods(stateMethodNames: Set<String>): List<Method> =
        allMethods()
            .filter {
                it.isConcreteInstanceHookMethod() &&
                    it.name in stateMethodNames &&
                    it.parameterCount == 0 &&
                    it.returnType.isNumericType()
            }
            .onEach { it.isAccessible = true }
            .toList()

    private fun Method.isConcreteInstanceHookMethod(): Boolean =
        !Modifier.isStatic(modifiers) &&
            !Modifier.isAbstract(modifiers) &&
            !declaringClass.isInterface

    private fun Class<*>.isNumericType(): Boolean =
        this == Int::class.javaPrimitiveType ||
            this == Int::class.javaObjectType ||
            this == Long::class.javaPrimitiveType ||
            this == Long::class.javaObjectType

    private fun Method.isDetailLikeInflateMethod(): Boolean {
        if (name != "b" || parameterCount != 3) return false
        val params = parameterTypes
        return Context::class.java.isAssignableFrom(params[0]) &&
            LayoutInflater::class.java.isAssignableFrom(params[1]) &&
            ViewGroup::class.java.isAssignableFrom(params[2])
    }

    private fun Method.isStoryBindMethod(): Boolean =
        parameterCount == 1 &&
            returnType == Void.TYPE &&
            parameterTypes[0].isStoryActionOwnerType()

    private fun Class<*>.isStoryActionOwnerType(): Boolean {
        if (name == STORY_ACTION_OWNER) return true
        val methods = allMethods().toList()
        return methods.any {
            it.name == "getData" && it.parameterCount == 0
        } && methods.any {
            it.name == "isActive" &&
                it.parameterCount == 0 &&
                (it.returnType == Boolean::class.javaPrimitiveType || it.returnType == Boolean::class.javaObjectType)
        }
    }

    private fun findClassNamesBySimpleName(
        bridge: () -> DexKitBridge?,
        simpleName: String,
    ): List<String> {
        val currentBridge = bridge() ?: return emptyList()
        return try {
            currentBridge.findClass(
                FindClass.create()
                    .searchPackages("com.bilibili", "tv.danmaku")
                    .matcher(ClassMatcher.create().className(simpleName, StringMatchType.Contains)),
            ).map { it.name }.toList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun findClassNamesByNameContains(
        bridge: () -> DexKitBridge?,
        terms: List<String>,
    ): List<String> {
        val currentBridge = bridge() ?: return emptyList()
        return terms.flatMap { term ->
            try {
                currentBridge.findClass(
                    FindClass.create()
                        .matcher(ClassMatcher.create().className(term, StringMatchType.Contains)),
                ).map { it.name }.toList()
            } catch (t: Throwable) {
                throw IllegalStateException("DexKit class name search failed term=$term: ${t.scanMessage()}", t)
            }
        }.distinct()
    }

    private fun writeCache(
        prefs: SharedPreferences,
        fingerprint: String,
        symbols: BiliHookSymbols,
        log: (String, Throwable?) -> Unit,
    ) {
        runCatching {
            prefs.edit()
                .putString(KEY_FINGERPRINT, fingerprint)
                .putString(KEY_SYMBOLS, symbols.toJson().toString())
                .apply()
        }.onFailure { log("BiliSymbolResolver cache write failed", it) }
    }

    private fun publishStatus(
        symbols: BiliHookSymbols,
        source: String,
        log: (String, Throwable?) -> Unit,
    ) {
        runCatching {
            val issues = symbols.hookPoints.filter { it.state == HookPointState.MISSING || it.state == HookPointState.ERROR }
            val optional = symbols.hookPoints.filter { it.state == HookPointState.OPTIONAL }
            val summary = when {
                issues.isEmpty() && symbols.scanErrors.isEmpty() ->
                    "当前扫描结果：未发现缺失方法（$source）"
                symbols.scanErrors.isEmpty() ->
                    "当前扫描结果：${issues.size} 个 HookPoint 缺失或异常（$source）"
                else ->
                    "当前扫描结果：${issues.size} 个 HookPoint 缺失或异常，${symbols.scanErrors.size} 个扫描错误（$source）"
            }
            val report = buildString {
                appendLine("来源：$source")
                appendLine("缓存指纹：${symbols.fingerprint}")
                appendLine("schema=${symbols.cacheSchemaVersion}, rule=${symbols.dexKitRuleVersion}")
                if (issues.isEmpty()) {
                    appendLine("未发现 MISSING/ERROR HookPoint。")
                } else {
                    appendLine("未找到或异常的 HookPoint：")
                    issues.forEach { status ->
                        appendLine("- ${status.id}: ${status.state.name}")
                        appendLine("  missing=${status.missing}")
                        if (status.target != "-") appendLine("  target=${status.target}")
                        if (status.evidence != "-") appendLine("  evidence=${status.evidence}")
                    }
                }
                if (optional.isNotEmpty()) {
                    appendLine("可选未安装的 HookPoint：")
                    optional.forEach { status ->
                        appendLine("- ${status.id}: ${status.state.name}")
                        appendLine("  reason=${status.missing}")
                    }
                }
                if (symbols.scanErrors.isNotEmpty()) {
                    appendLine("Scan Errors:")
                    symbols.scanErrors.forEach { appendLine("- $it") }
                }
            }.trim()

            val editor = ModuleSettingsBridge.instance.edit()
                .putString(ModuleSettings.KEY_SYMBOL_SCAN_STATUS_SUMMARY, summary)
                .putString(ModuleSettings.KEY_SYMBOL_SCAN_STATUS_REPORT, report)
            if (source == "scan" || source == "force-scan") {
                editor.putString(ModuleSettings.KEY_SYMBOL_SCAN_STATUS_UPDATED_AT, System.currentTimeMillis().toString())
            }
            editor.apply()
        }.onFailure {
            log("BiliSymbolResolver publish status failed", it)
        }
    }

    private fun buildFingerprint(context: Context): String {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val sourceDir = info.applicationInfo?.sourceDir.orEmpty()
            val source = File(sourceDir)
            val versionCode = context.packageVersionCodeLong()
            val versionName = info.versionName.orEmpty()
            listOf(
                context.packageName,
                versionCode.toString(),
                versionName,
                source.length().toString(),
                source.lastModified().toString(),
                BuildConfig.VERSION_CODE.toString(),
                BiliHookSymbols.CACHE_SCHEMA_VERSION.toString(),
                DexKitRuleVersions.CURRENT.toString(),
            ).joinToString("|")
        } catch (_: Throwable) {
            "unknown|${BuildConfig.VERSION_CODE}|${BiliHookSymbols.CACHE_SCHEMA_VERSION}|${DexKitRuleVersions.CURRENT}"
        }
    }

    @Suppress("DEPRECATION")
    private fun sourcePaths(context: Context): List<String> {
        val info = context.applicationInfo
        return buildList {
            info.sourceDir?.takeIf { it.isNotBlank() }?.let(::add)
            info.splitSourceDirs?.filterTo(this) { it.isNotBlank() }
        }.distinct()
    }

    private val ACCOUNT_CLASS_NAMES = listOf(
        "com.bilibili.lib.accounts.BiliAccounts",
        "com.bilibili.app.accounts.BiliAccounts",
        "com.bilibili.p4439app.accounts.BiliAccounts",
    )

    private val SHORT_ACCESS_METHODS = setOf("a", "b")

    private val SETTINGS_FRAGMENT_NAMES = listOf(
        "com.bilibili.p4439app.preferences.BiliPreferencesActivity\$BiliPreferencesFragment",
        "com.bilibili.app.preferences.BiliPreferencesActivity\$BiliPreferencesFragment",
        "com.bilibili.p4439app.preferences.fragment.WideBiliPreferencesFragment",
        "com.bilibili.app.preferences.fragment.WideBiliPreferencesFragment",
        "com.bilibili.p4439app.preferences.fragment.BaseWidePreferenceFragment",
        "com.bilibili.app.preferences.fragment.BaseWidePreferenceFragment",
    )

    private val PREFERENCE_CLASS_NAMES = listOf(
        "com.bilibili.p4439app.preferences.settingWide.CornerPreference",
        "com.bilibili.app.preferences.settingWide.CornerPreference",
        "tv.danmaku.p9138bili.widget.preference.BLPreference",
        "androidx.preference.Preference",
    )

    private val MINE_FRAGMENT_CLASS_NAMES = listOf(
        "tv.danmaku.bili.ui.main2.mine.HomeUserCenterFragment",
        "tv.danmaku.p9138bili.p9228ui.main2.p9247mine.HomeUserCenterFragment",
    )

    private val MINE_VIP_VIEW_CLASS_NAMES = listOf(
        "tv.danmaku.bili.ui.main2.mine.widgets.MineVipEntranceView",
        "tv.danmaku.p9138bili.p9228ui.main2.p9247mine.widgets.MineVipEntranceView",
        "tv.danmaku.p9138bili.p9228ui.main2.p9247mine.modularvip.VipEntranceView",
    )

    private val MINE_VIP_MANAGER_CLASS_NAMES = listOf(
        "tv.danmaku.bili.ui.main2.mine.modularvip.MineVipModuleManager",
    )

    private val DOWNLOAD_THREAD_LISTENER_CLASS_TERMS = listOf(
        "Download",
        "download",
        "Thread",
        "thread",
        "Listener",
        "listener",
        "Cache",
        "cache",
        "Offline",
        "offline",
    )

    private val DOWNLOAD_THREAD_REPORT_CLASS_TERMS = listOf(
        "Download",
        "download",
        "Thread",
        "thread",
        "Report",
        "report",
        "Cache",
        "cache",
        "Offline",
        "offline",
    )

    private const val HOME_AUTO_REFRESH_COMPONENT = "com.bilibili.pegasus.components.AutoRefreshComponent"
    private const val HOME_PEGASUS_REQUEST_MANAGER = "com.bilibili.pegasus.request.b"
    private const val HOME_PEGASUS_FLUSH = "com.bilibili.pegasus.data.request.PegasusFlush"
    private const val HOME_RESOURCE = "com.bilibili.lib.arch.lifecycle.Resource"
    private const val STORY_PAGER_PLAYER = "com.bilibili.video.story.player.StoryPagerPlayer"
    private const val STORY_FEED_RESPONSE = "com.bilibili.video.story.api.StoryFeedResponse"
    private const val STORY_AD_RERANK_TASK = "com.bilibili.video.story.action.service.StoryAdReRankService\$2"
    private const val STORY_DETAIL = "com.bilibili.video.story.StoryDetail"
    private const val KOTLIN_UNIT = "kotlin.Unit"
    private const val G_AD_BIZ_KT = "com.bilibili.gripper.api.ad.biz.GAdBizKt"
    private const val G_AD_VIDEO_DETAIL = "com.bilibili.gripper.api.ad.biz.GAdVideoDetail"
    private const val I_AD_UNDER_PLAYER = "com.bilibili.gripper.api.ad.biz.videodetail.underplayer.IAdUnderPlayer"
    private const val I_AD_VIDEO_RELATE = "com.bilibili.gripper.api.ad.biz.videodetail.relate.IAdVideoRelate"
    private const val I_AD_MERCHANDISE = "com.bilibili.gripper.api.ad.biz.videodetail.merchandise.IAdMerchandise"
    private const val GEMINI_BINDING_COMPONENT = "com.bilibili.app.gemini.ui.m"
    private const val GEMINI_SIMPLE_VIEW_ENTRY = "com.bilibili.app.gemini.ui.UIComponent\$b"
    private val COMMENT_IMAGE_VIEWER_CLASSES = listOf(
        "com.bilibili.app.comment3.ui.widget.imagecardviewer.CommentImageCardViewerDialogFragment",
        "com.bilibili.p4439app.comment3.p4518ui.widget.imagecardviewer.CommentImageCardViewerDialogFragment",
        "com.bilibili.app.comment.ui.image.CommentImageCardViewerDialogFragment",
    )
    private const val HOME_MENU_ITEM_CLASS = "com.bilibili.lib.homepage.startdust.menu.a"
    private const val HOME_BASE_MAIN_FRAME_FRAGMENT = "tv.danmaku.bili.ui.main2.basic.BaseMainFrameFragment"
    private const val HOME_MAIN_FRAGMENT = "tv.danmaku.bili.ui.main2.MainFragment"
    private const val HOME_DEFAULT_SEARCH_WORD_CLASS = "com.bilibili.app.comm.list.common.api.b"
    private val BOTTOM_RESOURCE_MANAGER_CLASSES = setOf(
        "tv.danmaku.bili.ui.main2.resource.MainResourceManager",
        "tv.danmaku.p9138bili.p9228ui.main2.resource.MainResourceManager",
    )
    private val BOTTOM_RESOURCE_MANAGER_METHOD_NAMES = setOf("m171910c", "m171911d")
    private val PEGASUS_RESPONSE_CLASSES = arrayOf(
        "com.bilibili.pegasus.data.base.PegasusResponse",
        "com.bilibili.pegasus.p5730data.p5731base.PegasusResponse",
        "com.bilibili.pegasus.p5730data.request.PegasusResponseWrapper",
    )
    private const val PEGASUS_HOLDER_DATA = "com.bilibili.pegasus.PegasusHolderData"
    private val BASE_PEGASUS_DATA_CLASSES = arrayOf(
        "com.bilibili.pegasus.data.base.BasePegasusData",
        "com.bilibili.pegasus.p5730data.p5731base.BasePegasusData",
    )
    private const val PEGASUS_HOLDER_STYLE = "com.bilibili.pegasus.HolderStyle"
    private const val AD_INFO = "com.bilibili.adcommon.data.IAdInfo"
    private val BASE_HOME_FRAGMENT_CLASSES = arrayOf(
        "tv.danmaku.bili.home.tab.page.BaseHomeFragment",
        "tv.danmaku.p9138bili.p9170home.p9173tab.p9174page.BaseHomeFragment",
    )
    private val HOME_COMPONENT_LIFECYCLE_METHODS = listOf(
        "onAttach",
        "onCreate",
        "onCreateView",
        "onViewCreated",
        "onResume",
    )
    private val THESEUS_TAB_PAGER_SERVICE = arrayOf(
        "com.bilibili.ship.theseus.united.page.tab.TheseusTabPagerService",
        "com.bilibili.p5797ship.theseus.united.p5850page.p5861tab.TheseusTabPagerService",
    )
    private val COMMENT_VIEW_MODEL_CLASSES = arrayOf(
        "com.bilibili.app.comment3.viewmodel.CommentViewModel",
        "com.bilibili.p4439app.comment3.viewmodel.CommentViewModel",
    )
    private val QUICK_REPLY_DIALOG_COLLECTOR_CLASSES = arrayOf(
        "com.bilibili.app.comment3.ui.CommentContainerImpl\$attachRepository\$5",
        "com.bilibili.p4439app.comment3.p4518ui.CommentContainerImpl\$attachRepository\$5",
        "com.bilibili.app.comment3.ui.CommentContainerImpl\$attachRepository\$5\$C636262",
        "com.bilibili.p4439app.comment3.p4518ui.CommentContainerImpl\$attachRepository\$5\$C636262",
    )
    private val CMT_VOTE_WIDGET_CLASSES = arrayOf(
        "com.bilibili.app.comment.ext.widgets.CmtVoteWidget",
        "com.bilibili.p4439app.comment.p4511ext.widgets.CmtVoteWidget",
    )
    private val CMT_MOUNT_WIDGET_CLASSES = arrayOf(
        "com.bilibili.app.comment.ext.widgets.CmtMountWidget",
        "com.bilibili.p4439app.comment.p4511ext.widgets.CmtMountWidget",
    )
    private val COMMENT_VOTE_VIEW_CLASSES = arrayOf(
        "com.bilibili.app.comment3.ui.widget.CommentVoteView",
        "com.bilibili.p4439app.comment3.p4518ui.widget.CommentVoteView",
    )
    private val COMMENT_FOLLOW_WIDGET_CLASSES = arrayOf(
        "com.bilibili.app.comm.comment2.phoenix.view.CommentFollowWidget",
        "com.bilibili.p4439app.p4450comm.comment2.phoenix.p4467view.CommentFollowWidget",
    )
    private val COMMENT_HEADER_DECORATIVE_VIEW_CLASSES = arrayOf(
        "com.bilibili.app.comment3.ui.widget.CommentHeaderDecorativeView",
        "com.bilibili.p4439app.comment3.p4518ui.widget.CommentHeaderDecorativeView",
    )
    private val COMMENT_CONTENT_CLASSES = arrayOf(
        "com.bapis.bilibili.main.community.reply.v1.Content",
        "com.bapis.bilibili.p4311main.community.reply.p4312v1.Content",
    )
    private val COMMENT_SUBJECT_CONTROL_CLASSES = arrayOf(
        "com.bapis.bilibili.main.community.reply.v1.SubjectControl",
        "com.bapis.bilibili.p4311main.community.reply.p4312v1.SubjectControl",
    )
    private val COMMENT_SUBJECT_DESC_CLASSES = arrayOf(
        "com.bapis.bilibili.main.community.reply.v2.SubjectDescriptionReply",
        "com.bapis.bilibili.p4311main.community.reply.p4313v2.SubjectDescriptionReply",
    )
    private val COMMENT_MAIN_LIST_OBSERVERS = arrayOf(
        "com.bapis.bilibili.main.community.reply.v1.ReplyMossKtxKt\$suspendMainList\$\$inlined\$suspendCall\$1",
        "com.bapis.bilibili.main.community.reply.v1.KReplyMoss\$mainList\$\$inlined\$suspendCall\$2",
        "com.bapis.bilibili.p4311main.community.reply.p4312v1.ReplyMossKtxKt\$suspendMainList\$\$inlined\$suspendCall\$1",
        "com.bapis.bilibili.p4311main.community.reply.p4312v1.KReplyMoss\$mainList\$\$inlined\$suspendCall\$2",
    )
    private val QUICK_REPLY_METHOD_NAMES = setOf("dispatchAction", "onAction", "submitAction")
    private val VOTE_WIDGET_METHOD_NAMES = setOf("setData", "bindData", "update", "refresh", "bind", "onBind")
    private val FOLLOW_WIDGET_METHOD_NAMES = setOf("setData", "bindData", "update", "refresh", "bind", "onBind")
    private val HEADER_DECORATIVE_METHOD_NAMES = setOf("setData", "bindData", "update", "refresh", "submitList")
    private const val PLAYER_CORE_SERVICE_INTERFACE = "tv.danmaku.biliplayerv2.service.IPlayerCoreService"
    private const val CARD_PLAYER_CONTEXT_INTERFACE = "tv.danmaku.video.bilicardplayer.ICardPlayerContext"
    private val PLAY_VIEW_METHOD_NAMES = setOf("executePlayViewUnite", "playViewUnite")
    private val STATE_METHOD_NAMES = setOf("getState")
    private val CARD_STATE_METHOD_NAMES = setOf("getPlayerState", "getState")
    private val PLAYER_CORE_SERVICE_CANDIDATES = arrayOf(
        "tv.danmaku.biliplayerv2.service.PlayerCoreService",
        "tv.danmaku.biliplayerimpl.core.PlayerCoreService",
        "com.bilibili.playerbizcommon.service.PlayerCoreService",
    )
    private val CARD_PLAYER_CONTEXT_CANDIDATES = arrayOf(
        "tv.danmaku.video.bilicardplayer.CardPlayerContext",
    )
    private val PLAYER_MOSS_CANDIDATES = arrayOf(
        "com.bapis.bilibili.app.playerunite.v1.PlayerMoss",
        "com.bapis.bilibili.p4218app.playerunite.p4240v1.PlayerMoss",
        "com.bapis.bilibili.p4218app.playerunite.p4240v1.KPlayerMoss",
    )
    private const val PROGRESS_BAR_CLASS = "android.widget.ProgressBar"
    private const val PANEL_WIDGET_KT_CLASS = "com.bilibili.inline.panel.PanelWidgetKt"
    private const val STORY_SEEK_BAR_CLASS = "com.bilibili.video.story.view.StorySeekBar"
    private val INLINE_PROGRESS_CLASSES = setOf(
        "com.bilibili.app.comm.list.common.inline.widgetV3.InlineProgressWidgetV3",
        "com.bilibili.p4439app.p4450comm.p4472list.common.inline.widgetV3.InlineProgressWidgetV3",
        "com.bilibili.p4440app.p4451comm.p4473list.common.inline.widgetV3.InlineProgressWidgetV3",
    )
    private const val DETAIL_LIKE_COMPONENT =
        "com.bilibili.ship.theseus.united.page.intro.module.kingposition.KingPositionComponent2\$LikeComponent"
    private const val DETAIL_LIKE_STATE_OWNER =
        "com.bilibili.ship.theseus.united.page.intro.module.kingposition.KingPositionService\$d"
    private const val STORY_ACTION_OWNER = "com.bilibili.video.story.action.InterfaceC24213g"
    private const val STORY_LIKE_WIDGET = "com.bilibili.video.story.action.widget.StoryLikeWidget"
    private const val STORY_LANDSCAPE_LIKE_WIDGET = "com.bilibili.video.story.action.widget.StoryLandscapeLikeWidget"
    private const val GEMINI_PLAYER_LIKE_WIDGET = "com.bilibili.app.gemini.player.widget.like.GeminiPlayerLikeWidget"
    private const val CHRONOS_ID_RPC_HANDLER = "rpcHandler"
    private const val CHRONOS_ID_REMOTE_HANDLER = "remoteHandler"
    private const val CHRONOS_ID_MESSAGE_SENDER = "messageSender"
    private const val CHRONOS_ID_ADD_CUSTOM_DANMAKU_REQUEST = "addCustomDanmakuRequest"
    private const val CHRONOS_ID_DM_VIEW_CHANGE_REQUEST = "dmViewChangeRequest"
    private const val CHRONOS_ID_COMMAND_DANMAKU_SENT_REQUEST = "commandDanmakuSentRequest"
    private const val CHRONOS_ID_COMMAND_DM_LIST_REQUEST = "commandDmListRequest"
    private const val CHRONOS_ID_COMMAND_DM_LIST_RESPONSE = "commandDmListResponse"
    private const val CHRONOS_ID_AD_DANMAKU_DELEGATE = "adDanmakuDelegate"
    private const val CHRONOS_ID_INTERACT_LAYER_SERVICE = "interactLayerService"
    private const val CHRONOS_ID_GEMINI_OPERATION_WIDGET = "geminiOperationWidget"
    private const val CHRONOS_ID_GEMINI_OPERATION_OBSERVER = "geminiOperationObserver"
    private const val CHRONOS_ID_VIEW_PROGRESS_DETAIL = "viewProgressDetail"
    private const val CHRONOS_ID_VIEW_PROGRESS_REPLY = "viewProgressReply"
    private const val CHRONOS_ID_UNITE_VIEW_PROGRESS_REPLY = "uniteViewProgressReply"
    private const val CHRONOS_ID_DM_VIEW_REPLY = "dmViewReply"
    private const val CHRONOS_ID_GET_DM_VIEW_REQUEST = "getDmViewRequest"
    private const val CHRONOS_ID_GET_VIEW_PROGRESS_REQUEST = "getViewProgressRequest"
    private const val CHRONOS_ID_UPDATE_DETAIL_STATE_REQUEST = "updateDetailStateRequest"
    private const val CHRONOS_ID_VIDEO_DETAIL_STATE_CHANGE_REQUEST = "videoDetailStateChangeRequest"
    private const val CHRONOS_ID_OPEN_URL_REQUEST = "openUrlRequest"
    private const val CHRONOS_ID_AD_DANMAKU_EVENT_REQUEST = "adDanmakuEventRequest"
    private const val CHRONOS_ID_NOTIFY_COMMERCIAL_EVENT_REQUEST = "notifyCommercialEventRequest"
    private const val CHRONOS_ID_FUNCTION2 = "function2"
    private const val CHRONOS_METHOD_RPC_RECEIVE = "rpcReceive"
    private const val CHRONOS_METHOD_LOCAL_VIEW_PROGRESS = "localViewProgress"
    private const val CHRONOS_METHOD_LOCAL_DM_VIEW = "localDmView"
    private const val CHRONOS_METHOD_REMOTE_VIDEO_DETAIL_STATE = "remoteVideoDetailState"
    private const val CHRONOS_METHOD_REMOTE_VIEW_PROGRESS = "remoteViewProgress"
    private const val CHRONOS_METHOD_REMOTE_COMMAND_DANMAKU = "remoteCommandDanmaku"
    private const val CHRONOS_METHOD_REMOTE_ADD_DANMAKU = "remoteAddDanmaku"
    private const val CHRONOS_METHOD_REMOTE_AD_FLOAT_EXPOSURE = "remoteAdFloatExposure"
    private const val CHRONOS_METHOD_MESSAGE_SEND = "messageSend"
    private const val CHRONOS_METHOD_COMMAND_DM_LIST = "commandDmList"
    private const val CHRONOS_METHOD_AD_DANMAKU_FEED = "adDanmakuFeed"
    private const val CHRONOS_METHOD_INTERACT_LAYER_VIEW_PROGRESS = "interactLayerViewProgress"
    private const val CHRONOS_METHOD_GEMINI_OPERATION_RENDER = "geminiOperationRender"
    private const val CHRONOS_METHOD_GEMINI_OPERATION_UPDATE = "geminiOperationUpdate"
    private const val CHRONOS_RPC_HANDLER =
        "tv.danmaku.biliplayerv2.service.interact.biz.chronos.chronosrpc.a"
    private const val REMOTE_SERVICE_HANDLER =
        "tv.danmaku.biliplayerv2.service.interact.biz.chronos.chronosrpc.remote.RemoteServiceHandler"
    private const val CHRONOS_MESSAGE_SENDER = "Hh1.d"
    private const val ADD_CUSTOM_DANMAKU_REQUEST =
        "tv.danmaku.biliplayerv2.service.interact.biz.chronos.chronosrpc.methods.send.AddCustomDanmaku\$Request"
    private const val DM_VIEW_CHANGE_REQUEST =
        "tv.danmaku.biliplayerv2.service.interact.biz.chronos.chronosrpc.methods.send.DmViewChange\$Request"
    private const val COMMAND_DANMAKU_SENT_REQUEST =
        "tv.danmaku.biliplayerv2.service.interact.biz.chronos.chronosrpc.methods.send.CommandDanmakuSent\$Request"
    private const val COMMAND_DM_LIST_REQUEST =
        "tv.danmaku.biliplayerv2.service.interact.biz.chronos.chronosrpc.methods.send.CommandDmListRequest\$Request"
    private const val COMMAND_DM_LIST_RESPONSE =
        "tv.danmaku.biliplayerv2.service.interact.biz.chronos.chronosrpc.methods.send.CommandDmListRequest\$Response"
    private const val AD_DANMAKU_DELEGATE =
        "tv.danmaku.biliplayerv2.service.interact.biz.chronos.AdDanmakuDelegate"
    private const val INTERACT_LAYER_SERVICE =
        "tv.danmaku.biliplayerv2.service.interact.biz.InteractLayerService"
    private const val GEMINI_OPERATION_WIDGET =
        "com.bilibili.app.gemini.player.widget.operation.a"
    private const val GEMINI_OPERATION_OBSERVER =
        "com.bilibili.app.gemini.player.widget.operation.a\$d"
    private const val VIEW_PROGRESS_DETAIL =
        "tv.danmaku.biliplayerv2.service.interact.biz.model.viewprogress.uniteviewprogress.ViewProgressDetail"
    private const val VIEW_PROGRESS_REPLY =
        "com.bapis.bilibili.app.view.v1.ViewProgressReply"
    private const val UNITE_VIEW_PROGRESS_REPLY =
        "com.bapis.bilibili.app.viewunite.v1.ViewProgressReply"
    private const val DM_VIEW_REPLY =
        "com.bapis.bilibili.community.service.dm.v1.DmViewReply"
    private const val GET_DM_VIEW_REQUEST =
        "tv.danmaku.biliplayerv2.service.interact.biz.chronos.chronosrpc.methods.receive.GetDmView\$Request"
    private const val GET_VIEW_PROGRESS_REQUEST =
        "tv.danmaku.biliplayerv2.service.interact.biz.chronos.chronosrpc.methods.receive.GetViewProgress\$Request"
    private const val UPDATE_VIDEO_DETAIL_STATE_REQUEST =
        "tv.danmaku.biliplayerv2.service.interact.biz.chronos.chronosrpc.methods.receive.UpdateVideoDetailState\$Request"
    private const val VIDEO_DETAIL_STATE_CHANGE_REQUEST =
        "tv.danmaku.biliplayerv2.service.interact.biz.chronos.chronosrpc.methods.send.VideoDetailStateChange\$Request"
    private const val OPEN_URL_SCHEME_REQUEST =
        "tv.danmaku.biliplayerv2.service.interact.biz.chronos.chronosrpc.methods.receive.OpenUrlScheme\$Request"
    private const val AD_DANMAKU_EVENT_REQUEST =
        "tv.danmaku.biliplayerv2.service.interact.biz.chronos.chronosrpc.methods.receive.AdDanmakuEvent\$Request"
    private const val NOTIFY_COMMERCIAL_EVENT_REQUEST =
        "tv.danmaku.biliplayerv2.service.interact.biz.chronos.chronosrpc.methods.receive.NotifyCommercialEvent\$Request"
    private const val KOTLIN_FUNCTION2 = "kotlin.jvm.functions.Function2"
    private val DETAIL_STATE_GETTERS = listOf(
        "getFollowStates",
        "getReserveState",
        "getClockInState",
        "getVoteState",
    )
    private val VIDEO_DETAIL_STATE_CHANGE_GETTERS = listOf(
        "getFollowStates",
        "getReserveState",
        "getClockInState",
        "getVoteState",
        "getActivityState",
    )
}

private data class HomeRequestParamSymbols(
    val idxField: java.lang.reflect.Field,
    val refreshField: java.lang.reflect.Field,
    val flushField: java.lang.reflect.Field,
)

private sealed class SymbolScanResult<out T> {
    data class Found<T : Any>(
        val value: T,
        val target: String,
        val evidence: String,
    ) : SymbolScanResult<T>()

    data class Missing(
        val reason: String,
    ) : SymbolScanResult<Nothing>()
}
