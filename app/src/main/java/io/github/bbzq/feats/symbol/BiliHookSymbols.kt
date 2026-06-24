package io.github.bbzq.feats.symbol

import android.content.Context
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

data class BiliHookSymbols(
    val cacheSchemaVersion: Int = CACHE_SCHEMA_VERSION,
    val dexKitRuleVersion: Int = DexKitRuleVersions.CURRENT,
    val fingerprint: String,
    val hookPoints: List<HookPointStatus>,
    val scanErrors: List<String>,
    val account: AccountSymbols? = null,
    val settings: SettingsSymbols? = null,
    val blockUpdate: BlockUpdateSymbols? = null,
    val mineProfile: MineProfileSymbols? = null,
    val downloadThread: DownloadThreadSymbols? = null,
    val homeRecommendAutoRefresh: HomeRecommendAutoRefreshSymbols? = null,
    val storyPlayerAd: StoryPlayerAdSymbols? = null,
    val videoDetailBannerAd: VideoDetailBannerAdSymbols? = null,
    val commentPicture: CommentPictureSymbols? = null,
    val homeTopBar: HomeTopBarSymbols? = null,
    val bottomBar: BottomBarSymbols? = null,
    val homeRecommendFeed: HomeRecommendFeedSymbols? = null,
    val homeComponentHide: HomeComponentHideSymbols? = null,
    val videoComment: VideoCommentSymbols? = null,
    val skipVideoAd: SkipVideoAdSymbols? = null,
    val skipVideoAdProgress: SkipVideoAdProgressSymbols? = null,
    val skipVideoAdAutoLike: SkipVideoAdAutoLikeSymbols? = null,
    val chronosPromotion: ChronosPromotionSymbols? = null,
) {
    fun isUsableWith(expectedFingerprint: String): Boolean =
        cacheSchemaVersion == CACHE_SCHEMA_VERSION &&
            dexKitRuleVersion == DexKitRuleVersions.CURRENT &&
            fingerprint == expectedFingerprint

    fun toJson(): JSONObject = JSONObject()
        .put("cacheSchemaVersion", cacheSchemaVersion)
        .put("dexKitRuleVersion", dexKitRuleVersion)
        .put("fingerprint", fingerprint)
        .put("hookPoints", hookPoints.toJsonArray { it.toJson() })
        .put("scanErrors", scanErrors.toJsonArray())
        .putOpt("account", account?.toJson())
        .putOpt("settings", settings?.toJson())
        .putOpt("blockUpdate", blockUpdate?.toJson())
        .putOpt("mineProfile", mineProfile?.toJson())
        .putOpt("downloadThread", downloadThread?.toJson())
        .putOpt("homeRecommendAutoRefresh", homeRecommendAutoRefresh?.toJson())
        .putOpt("storyPlayerAd", storyPlayerAd?.toJson())
        .putOpt("videoDetailBannerAd", videoDetailBannerAd?.toJson())
        .putOpt("commentPicture", commentPicture?.toJson())
        .putOpt("homeTopBar", homeTopBar?.toJson())
        .putOpt("bottomBar", bottomBar?.toJson())
        .putOpt("homeRecommendFeed", homeRecommendFeed?.toJson())
        .putOpt("homeComponentHide", homeComponentHide?.toJson())
        .putOpt("videoComment", videoComment?.toJson())
        .putOpt("skipVideoAd", skipVideoAd?.toJson())
        .putOpt("skipVideoAdProgress", skipVideoAdProgress?.toJson())
        .putOpt("skipVideoAdAutoLike", skipVideoAdAutoLike?.toJson())
        .putOpt("chronosPromotion", chronosPromotion?.toJson())

    companion object {
        const val CACHE_SCHEMA_VERSION = 5

        fun fromJson(raw: String?): BiliHookSymbols? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val obj = JSONObject(raw)
                BiliHookSymbols(
                    cacheSchemaVersion = obj.optInt("cacheSchemaVersion", 0),
                    dexKitRuleVersion = obj.optInt("dexKitRuleVersion", 0),
                    fingerprint = obj.optString("fingerprint"),
                    hookPoints = obj.optJSONArray("hookPoints").toList { HookPointStatus.fromJson(it) },
                    scanErrors = obj.optJSONArray("scanErrors").toStringList(),
                    account = obj.optJSONObject("account")?.let(AccountSymbols::fromJson),
                    settings = obj.optJSONObject("settings")?.let(SettingsSymbols::fromJson),
                    blockUpdate = obj.optJSONObject("blockUpdate")?.let(BlockUpdateSymbols::fromJson),
                    mineProfile = obj.optJSONObject("mineProfile")?.let(MineProfileSymbols::fromJson),
                    downloadThread = obj.optJSONObject("downloadThread")?.let(DownloadThreadSymbols::fromJson),
                    homeRecommendAutoRefresh = obj.optJSONObject("homeRecommendAutoRefresh")
                        ?.let(HomeRecommendAutoRefreshSymbols::fromJson),
                    storyPlayerAd = obj.optJSONObject("storyPlayerAd")?.let(StoryPlayerAdSymbols::fromJson),
                    videoDetailBannerAd = obj.optJSONObject("videoDetailBannerAd")
                        ?.let(VideoDetailBannerAdSymbols::fromJson),
                    commentPicture = obj.optJSONObject("commentPicture")?.let(CommentPictureSymbols::fromJson),
                    homeTopBar = obj.optJSONObject("homeTopBar")?.let(HomeTopBarSymbols::fromJson),
                    bottomBar = obj.optJSONObject("bottomBar")?.let(BottomBarSymbols::fromJson),
                    homeRecommendFeed = obj.optJSONObject("homeRecommendFeed")?.let(HomeRecommendFeedSymbols::fromJson),
                    homeComponentHide = obj.optJSONObject("homeComponentHide")?.let(HomeComponentHideSymbols::fromJson),
                    videoComment = obj.optJSONObject("videoComment")?.let(VideoCommentSymbols::fromJson),
                    skipVideoAd = obj.optJSONObject("skipVideoAd")?.let(SkipVideoAdSymbols::fromJson),
                    skipVideoAdProgress = obj.optJSONObject("skipVideoAdProgress")
                        ?.let(SkipVideoAdProgressSymbols::fromJson),
                    skipVideoAdAutoLike = obj.optJSONObject("skipVideoAdAutoLike")
                        ?.let(SkipVideoAdAutoLikeSymbols::fromJson),
                    chronosPromotion = obj.optJSONObject("chronosPromotion")?.let(ChronosPromotionSymbols::fromJson),
                )
            }.getOrNull()
        }
    }
}

object DexKitRuleVersions {
    const val CURRENT = 10
}

data class HookPointStatus(
    val id: String,
    val state: HookPointState,
    val missing: String = "-",
    val target: String = "-",
    val evidence: String = "-",
) {
    fun toLine(): String =
        "HookPoint[$id] state=${state.name} missing=$missing target=$target evidence=$evidence"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("state", state.name)
        .put("missing", missing)
        .put("target", target)
        .put("evidence", evidence)

    companion object {
        fun found(id: String, target: String, evidence: String): HookPointStatus =
            HookPointStatus(id, HookPointState.FOUND, target = target, evidence = evidence)

        fun missing(id: String, reason: String): HookPointStatus =
            HookPointStatus(id, HookPointState.MISSING, missing = reason)

        fun optional(id: String, reason: String): HookPointStatus =
            HookPointStatus(id, HookPointState.OPTIONAL, missing = reason)

        fun error(id: String, reason: String): HookPointStatus =
            HookPointStatus(id, HookPointState.ERROR, missing = reason)

        fun fromJson(obj: JSONObject): HookPointStatus = HookPointStatus(
            id = obj.optString("id"),
            state = HookPointState.from(obj.optString("state")),
            missing = obj.optString("missing", "-"),
            target = obj.optString("target", "-"),
            evidence = obj.optString("evidence", "-"),
        )
    }
}

enum class HookPointState {
    FOUND,
    OPTIONAL,
    MISSING,
    ERROR;

    companion object {
        fun from(raw: String?): HookPointState =
            entries.firstOrNull { it.name == raw } ?: MISSING
    }
}

data class AccountSymbols(
    val accountClassName: String,
    val getMethod: MethodDescriptor,
    val accessKeyMethod: MethodDescriptor,
    val evidence: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("accountClassName", accountClassName)
        .put("getMethod", getMethod.toJson())
        .put("accessKeyMethod", accessKeyMethod.toJson())
        .put("evidence", evidence)

    fun restore(classLoader: ClassLoader): RestoredAccountSymbols? {
        val accountClass = classLoader.loadClassOrNull(accountClassName) ?: return null
        val get = getMethod.restore(accountClass) ?: return null
        val access = accessKeyMethod.restore(accountClass) ?: return null
        if (!Modifier.isStatic(get.modifiers)) return null
        if (get.returnType != accountClass) return null
        if (access.returnType != String::class.java || Modifier.isStatic(access.modifiers)) return null
        return RestoredAccountSymbols(accountClass, get, access)
    }

    companion object {
        fun fromJson(obj: JSONObject): AccountSymbols = AccountSymbols(
            accountClassName = obj.optString("accountClassName"),
            getMethod = MethodDescriptor.fromJson(obj.getJSONObject("getMethod")),
            accessKeyMethod = MethodDescriptor.fromJson(obj.getJSONObject("accessKeyMethod")),
            evidence = obj.optString("evidence", "-"),
        )
    }
}

data class RestoredAccountSymbols(
    val accountClass: Class<*>,
    val getMethod: Method,
    val accessKeyMethod: Method,
)

data class SettingsSymbols(
    val fragmentMethods: List<MethodDescriptor>,
    val preferenceClassName: String,
    val evidence: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("fragmentMethods", fragmentMethods.toJsonArray { it.toJson() })
        .put("preferenceClassName", preferenceClassName)
        .put("evidence", evidence)

    fun restoreFragmentMethods(classLoader: ClassLoader): List<Method> =
        fragmentMethods.mapNotNull { descriptor ->
            val owner = classLoader.loadClassOrNull(descriptor.declaringClassName) ?: return@mapNotNull null
            descriptor.restore(owner)
        }

    fun restorePreferenceClass(classLoader: ClassLoader): Class<*>? =
        classLoader.loadClassOrNull(preferenceClassName)

    companion object {
        fun fromJson(obj: JSONObject): SettingsSymbols = SettingsSymbols(
            fragmentMethods = obj.optJSONArray("fragmentMethods").toList { MethodDescriptor.fromJson(it) },
            preferenceClassName = obj.optString("preferenceClassName"),
            evidence = obj.optString("evidence", "-"),
        )
    }
}

data class BlockUpdateSymbols(
    val checkMethod: MethodDescriptor,
    val evidence: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("checkMethod", checkMethod.toJson())
        .put("evidence", evidence)

    fun restore(classLoader: ClassLoader): RestoredBlockUpdateSymbols? {
        val owner = classLoader.loadClassOrNull(checkMethod.declaringClassName) ?: return null
        val method = checkMethod.restore(owner) ?: return null
        return RestoredBlockUpdateSymbols(method)
    }

    companion object {
        fun fromJson(obj: JSONObject): BlockUpdateSymbols = BlockUpdateSymbols(
            checkMethod = MethodDescriptor.fromJson(obj.getJSONObject("checkMethod")),
            evidence = obj.optString("evidence", "-"),
        )
    }
}

data class RestoredBlockUpdateSymbols(
    val checkMethod: Method,
)

data class MineProfileSymbols(
    val fragmentClassName: String,
    val vipViewClassName: String,
    val vipField: FieldDescriptor,
    val managerBindingField: FieldDescriptor? = null,
    val bindingRootField: FieldDescriptor? = null,
    val onResume: MethodDescriptor,
    val evidence: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("fragmentClassName", fragmentClassName)
        .put("vipViewClassName", vipViewClassName)
        .put("vipField", vipField.toJson())
        .putOpt("managerBindingField", managerBindingField?.toJson())
        .putOpt("bindingRootField", bindingRootField?.toJson())
        .put("onResume", onResume.toJson())
        .put("evidence", evidence)

    fun restore(classLoader: ClassLoader): RestoredMineProfileSymbols? {
        val fragmentClass = classLoader.loadClassOrNull(fragmentClassName) ?: return null
        val vipViewClass = classLoader.loadClassOrNull(vipViewClassName) ?: return null
        val field = vipField.restore(fragmentClass) ?: return null
        val method = onResume.restore(fragmentClass) ?: return null
        if (!vipViewClass.isAssignableFrom(field.type)) return null
        val bindingField = managerBindingField?.restore(vipViewClass)
        val rootField = bindingRootField?.let { descriptor ->
            val owner = bindingField?.type ?: return null
            descriptor.restore(owner)
        }
        return RestoredMineProfileSymbols(fragmentClass, vipViewClass, field, bindingField, rootField, method)
    }

    companion object {
        fun fromJson(obj: JSONObject): MineProfileSymbols = MineProfileSymbols(
            fragmentClassName = obj.optString("fragmentClassName"),
            vipViewClassName = obj.optString("vipViewClassName"),
            vipField = FieldDescriptor.fromJson(obj.getJSONObject("vipField")),
            managerBindingField = obj.optJSONObject("managerBindingField")?.let(FieldDescriptor::fromJson),
            bindingRootField = obj.optJSONObject("bindingRootField")?.let(FieldDescriptor::fromJson),
            onResume = MethodDescriptor.fromJson(obj.getJSONObject("onResume")),
            evidence = obj.optString("evidence", "-"),
        )
    }
}

data class RestoredMineProfileSymbols(
    val fragmentClass: Class<*>,
    val vipViewClass: Class<*>,
    val vipField: Field,
    val managerBindingField: Field?,
    val bindingRootField: Field?,
    val onResume: Method,
) {
    fun resolveVipView(fragment: Any): View? {
        val holder = vipField.get(fragment) ?: return null
        if (holder is View) return holder
        val binding = managerBindingField?.get(holder) ?: return null
        return bindingRootField?.get(binding) as? View
    }
}

data class DownloadThreadSymbols(
    val listeners: List<DownloadThreadListenerSymbols>,
    val reportMethod: MethodDescriptor?,
    val evidence: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("listeners", listeners.toJsonArray { it.toJson() })
        .putOpt("reportMethod", reportMethod?.toJson())
        .put("evidence", evidence)

    fun restore(classLoader: ClassLoader): RestoredDownloadThreadSymbols {
        val restoredListeners = listeners.mapNotNull { it.restore(classLoader) }
        val restoredReport = reportMethod?.let { descriptor ->
            val owner = classLoader.loadClassOrNull(descriptor.declaringClassName) ?: return@let null
            descriptor.restore(owner)
        }
        return RestoredDownloadThreadSymbols(restoredListeners, restoredReport)
    }

    companion object {
        fun fromJson(obj: JSONObject): DownloadThreadSymbols = DownloadThreadSymbols(
            listeners = obj.optJSONArray("listeners").toList { DownloadThreadListenerSymbols.fromJson(it) },
            reportMethod = obj.optJSONObject("reportMethod")?.let(MethodDescriptor::fromJson),
            evidence = obj.optString("evidence", "-"),
        )
    }
}

data class DownloadThreadListenerSymbols(
    val className: String,
    val constructor: ConstructorDescriptor,
    val onClick: MethodDescriptor,
    val textViewField: FieldDescriptor,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("className", className)
        .put("constructor", constructor.toJson())
        .put("onClick", onClick.toJson())
        .put("textViewField", textViewField.toJson())

    fun restore(classLoader: ClassLoader): RestoredDownloadThreadListenerSymbols? {
        val type = classLoader.loadClassOrNull(className) ?: return null
        val ctor = constructor.restore(type) ?: return null
        val click = onClick.restore(type) ?: return null
        val fieldOwner = classLoader.loadClassOrNull(textViewField.declaringClassName) ?: return null
        val field = textViewField.restore(fieldOwner) ?: return null
        return RestoredDownloadThreadListenerSymbols(type, ctor, click, field)
    }

    companion object {
        fun fromJson(obj: JSONObject): DownloadThreadListenerSymbols = DownloadThreadListenerSymbols(
            className = obj.optString("className"),
            constructor = ConstructorDescriptor.fromJson(obj.getJSONObject("constructor")),
            onClick = MethodDescriptor.fromJson(obj.getJSONObject("onClick")),
            textViewField = FieldDescriptor.fromJson(obj.getJSONObject("textViewField")),
        )
    }
}

data class RestoredDownloadThreadSymbols(
    val listeners: List<RestoredDownloadThreadListenerSymbols>,
    val reportMethod: Method?,
)

data class RestoredDownloadThreadListenerSymbols(
    val listenerClass: Class<*>,
    val constructor: Constructor<*>,
    val onClick: Method,
    val textViewField: Field,
)

data class HomeRecommendAutoRefreshSymbols(
    val autoRefreshMethod: MethodDescriptor,
    val requestMethods: List<MethodDescriptor>,
    val idxField: FieldDescriptor,
    val refreshField: FieldDescriptor,
    val flushField: FieldDescriptor,
    val resourceErrorMethod: MethodDescriptor,
    val evidence: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("autoRefreshMethod", autoRefreshMethod.toJson())
        .put("requestMethods", requestMethods.toJsonArray { it.toJson() })
        .put("idxField", idxField.toJson())
        .put("refreshField", refreshField.toJson())
        .put("flushField", flushField.toJson())
        .put("resourceErrorMethod", resourceErrorMethod.toJson())
        .put("evidence", evidence)

    fun restore(classLoader: ClassLoader): RestoredHomeRecommendAutoRefreshSymbols? {
        val autoRefreshOwner = classLoader.loadClassOrNull(autoRefreshMethod.declaringClassName) ?: return null
        val autoRefresh = autoRefreshMethod.restore(autoRefreshOwner) ?: return null
        val restoredRequestMethods = requestMethods.mapNotNull { descriptor ->
            val owner = classLoader.loadClassOrNull(descriptor.declaringClassName) ?: return@mapNotNull null
            descriptor.restore(owner)
        }
        if (restoredRequestMethods.size != requestMethods.size) return null

        val idxOwner = classLoader.loadClassOrNull(idxField.declaringClassName) ?: return null
        val refreshOwner = classLoader.loadClassOrNull(refreshField.declaringClassName) ?: return null
        val flushOwner = classLoader.loadClassOrNull(flushField.declaringClassName) ?: return null
        val errorOwner = classLoader.loadClassOrNull(resourceErrorMethod.declaringClassName) ?: return null
        return RestoredHomeRecommendAutoRefreshSymbols(
            autoRefreshMethod = autoRefresh,
            requestMethods = restoredRequestMethods,
            idxField = idxField.restore(idxOwner) ?: return null,
            refreshField = refreshField.restore(refreshOwner) ?: return null,
            flushField = flushField.restore(flushOwner) ?: return null,
            resourceErrorMethod = resourceErrorMethod.restore(errorOwner) ?: return null,
        )
    }

    companion object {
        fun fromJson(obj: JSONObject): HomeRecommendAutoRefreshSymbols = HomeRecommendAutoRefreshSymbols(
            autoRefreshMethod = MethodDescriptor.fromJson(obj.getJSONObject("autoRefreshMethod")),
            requestMethods = obj.optJSONArray("requestMethods").toList { MethodDescriptor.fromJson(it) },
            idxField = FieldDescriptor.fromJson(obj.getJSONObject("idxField")),
            refreshField = FieldDescriptor.fromJson(obj.getJSONObject("refreshField")),
            flushField = FieldDescriptor.fromJson(obj.getJSONObject("flushField")),
            resourceErrorMethod = MethodDescriptor.fromJson(obj.getJSONObject("resourceErrorMethod")),
            evidence = obj.optString("evidence", "-"),
        )
    }
}

data class RestoredHomeRecommendAutoRefreshSymbols(
    val autoRefreshMethod: Method,
    val requestMethods: List<Method>,
    val idxField: Field,
    val refreshField: Field,
    val flushField: Field,
    val resourceErrorMethod: Method,
) {
    fun isColdStartNormalRefresh(requestParam: Any, normalFlushName: String): Boolean {
        val idx = runCatching { idxField.getLong(requestParam) }.getOrNull() ?: return false
        val refresh = runCatching { refreshField.getBoolean(requestParam) }.getOrNull() ?: return false
        val flushName = (runCatching { flushField.get(requestParam) }.getOrNull() as? Enum<*>)?.name
            ?: return false
        return idx == 0L && refresh && flushName == normalFlushName
    }
}

data class StoryPlayerAdSymbols(
    val feedGetItems: MethodDescriptor?,
    val pagerListMethods: List<MethodDescriptor>,
    val rerankInvokeSuspend: MethodDescriptor?,
    val kotlinUnitField: FieldDescriptor?,
    val evidence: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .putOpt("feedGetItems", feedGetItems?.toJson())
        .put("pagerListMethods", pagerListMethods.toJsonArray { it.toJson() })
        .putOpt("rerankInvokeSuspend", rerankInvokeSuspend?.toJson())
        .putOpt("kotlinUnitField", kotlinUnitField?.toJson())
        .put("evidence", evidence)

    fun restore(classLoader: ClassLoader): RestoredStoryPlayerAdSymbols? {
        val feed = feedGetItems?.let { descriptor ->
            val owner = classLoader.loadClassOrNull(descriptor.declaringClassName) ?: return@let null
            descriptor.restore(owner)
        }
        val pager = pagerListMethods.mapNotNull { descriptor ->
            val owner = classLoader.loadClassOrNull(descriptor.declaringClassName) ?: return@mapNotNull null
            descriptor.restore(owner)
        }
        if (pager.size != pagerListMethods.size) return null
        val rerank = rerankInvokeSuspend?.let { descriptor ->
            val owner = classLoader.loadClassOrNull(descriptor.declaringClassName) ?: return@let null
            descriptor.restore(owner)
        }
        val unit = kotlinUnitField?.let { descriptor ->
            val owner = classLoader.loadClassOrNull(descriptor.declaringClassName) ?: return@let null
            descriptor.restore(owner)?.get(null)
        }
        if (rerankInvokeSuspend != null && rerank == null) return null
        if (kotlinUnitField != null && unit == null) return null
        return RestoredStoryPlayerAdSymbols(feed, pager, rerank, unit)
    }

    companion object {
        fun fromJson(obj: JSONObject): StoryPlayerAdSymbols = StoryPlayerAdSymbols(
            feedGetItems = obj.optJSONObject("feedGetItems")?.let(MethodDescriptor::fromJson),
            pagerListMethods = obj.optJSONArray("pagerListMethods").toList { MethodDescriptor.fromJson(it) },
            rerankInvokeSuspend = obj.optJSONObject("rerankInvokeSuspend")?.let(MethodDescriptor::fromJson),
            kotlinUnitField = obj.optJSONObject("kotlinUnitField")?.let(FieldDescriptor::fromJson),
            evidence = obj.optString("evidence", "-"),
        )
    }
}

data class RestoredStoryPlayerAdSymbols(
    val feedGetItems: Method?,
    val pagerListMethods: List<Method>,
    val rerankInvokeSuspend: Method?,
    val kotlinUnit: Any?,
)

data class VideoDetailBannerAdSymbols(
    val getVideoDetail: MethodDescriptor?,
    val videoDetailTypeName: String?,
    val underPlayerTypeName: String?,
    val relateTypeName: String?,
    val merchandiseTypeName: String?,
    val simpleViewEntryConstructor: ConstructorDescriptor?,
    val createViewEntry: MethodDescriptor?,
    val bindToView: MethodDescriptor?,
    val kotlinUnitField: FieldDescriptor?,
    val evidence: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .putOpt("getVideoDetail", getVideoDetail?.toJson())
        .putOpt("videoDetailTypeName", videoDetailTypeName)
        .putOpt("underPlayerTypeName", underPlayerTypeName)
        .putOpt("relateTypeName", relateTypeName)
        .putOpt("merchandiseTypeName", merchandiseTypeName)
        .putOpt("simpleViewEntryConstructor", simpleViewEntryConstructor?.toJson())
        .putOpt("createViewEntry", createViewEntry?.toJson())
        .putOpt("bindToView", bindToView?.toJson())
        .putOpt("kotlinUnitField", kotlinUnitField?.toJson())
        .put("evidence", evidence)

    fun restore(classLoader: ClassLoader): RestoredVideoDetailBannerAdSymbols? {
        val getVideoDetailMethod = getVideoDetail?.let { descriptor ->
            val owner = classLoader.loadClassOrNull(descriptor.declaringClassName) ?: return@let null
            descriptor.restore(owner)
        }
        val videoDetailType = videoDetailTypeName?.let(classLoader::loadClassOrNull)
        val underPlayerType = underPlayerTypeName?.let(classLoader::loadClassOrNull)
        val relateType = relateTypeName?.let(classLoader::loadClassOrNull)
        val merchandiseType = merchandiseTypeName?.let(classLoader::loadClassOrNull)
        if (getVideoDetail != null && (getVideoDetailMethod == null || videoDetailType == null || underPlayerType == null)) {
            return null
        }

        val constructor = simpleViewEntryConstructor?.let { descriptor ->
            val owner = classLoader.loadClassOrNull(descriptor.declaringClassName) ?: return@let null
            descriptor.restore(owner)
        }
        val create = createViewEntry?.let { descriptor ->
            val owner = classLoader.loadClassOrNull(descriptor.declaringClassName) ?: return@let null
            descriptor.restore(owner)
        }
        val bind = bindToView?.let { descriptor ->
            val owner = classLoader.loadClassOrNull(descriptor.declaringClassName) ?: return@let null
            descriptor.restore(owner)
        }
        val unit = kotlinUnitField?.let { descriptor ->
            val owner = classLoader.loadClassOrNull(descriptor.declaringClassName) ?: return@let null
            descriptor.restore(owner)?.get(null)
        }
        if (createViewEntry != null && (constructor == null || create == null || bind == null || unit == null)) {
            return null
        }

        return RestoredVideoDetailBannerAdSymbols(
            getVideoDetail = getVideoDetailMethod,
            videoDetailType = videoDetailType,
            underPlayerType = underPlayerType,
            relateType = relateType,
            merchandiseType = merchandiseType,
            simpleViewEntryConstructor = constructor,
            createViewEntry = create,
            bindToView = bind,
            kotlinUnit = unit,
        )
    }

    companion object {
        fun fromJson(obj: JSONObject): VideoDetailBannerAdSymbols = VideoDetailBannerAdSymbols(
            getVideoDetail = obj.optJSONObject("getVideoDetail")?.let(MethodDescriptor::fromJson),
            videoDetailTypeName = obj.optString("videoDetailTypeName").takeIf { it.isNotBlank() },
            underPlayerTypeName = obj.optString("underPlayerTypeName").takeIf { it.isNotBlank() },
            relateTypeName = obj.optString("relateTypeName").takeIf { it.isNotBlank() },
            merchandiseTypeName = obj.optString("merchandiseTypeName").takeIf { it.isNotBlank() },
            simpleViewEntryConstructor = obj.optJSONObject("simpleViewEntryConstructor")?.let(ConstructorDescriptor::fromJson),
            createViewEntry = obj.optJSONObject("createViewEntry")?.let(MethodDescriptor::fromJson),
            bindToView = obj.optJSONObject("bindToView")?.let(MethodDescriptor::fromJson),
            kotlinUnitField = obj.optJSONObject("kotlinUnitField")?.let(FieldDescriptor::fromJson),
            evidence = obj.optString("evidence", "-"),
        )
    }
}

data class RestoredVideoDetailBannerAdSymbols(
    val getVideoDetail: Method?,
    val videoDetailType: Class<*>?,
    val underPlayerType: Class<*>?,
    val relateType: Class<*>?,
    val merchandiseType: Class<*>?,
    val simpleViewEntryConstructor: Constructor<*>?,
    val createViewEntry: Method?,
    val bindToView: Method?,
    val kotlinUnit: Any?,
)

data class CommentPictureSymbols(
    val initViewMethods: List<MethodDescriptor>,
    val evidence: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("initViewMethods", initViewMethods.toJsonArray { it.toJson() })
        .put("evidence", evidence)

    fun restore(classLoader: ClassLoader): RestoredCommentPictureSymbols? {
        val methods = initViewMethods.mapNotNull { descriptor ->
            val owner = classLoader.loadClassOrNull(descriptor.declaringClassName) ?: return@mapNotNull null
            descriptor.restore(owner)
        }
        if (methods.size != initViewMethods.size) return null
        return RestoredCommentPictureSymbols(methods)
    }

    companion object {
        fun fromJson(obj: JSONObject): CommentPictureSymbols = CommentPictureSymbols(
            initViewMethods = obj.optJSONArray("initViewMethods").toList { MethodDescriptor.fromJson(it) },
            evidence = obj.optString("evidence", "-"),
        )
    }
}

data class RestoredCommentPictureSymbols(
    val initViewMethods: List<Method>,
)

data class HomeTopBarSymbols(
    val gameMenuMethod: MethodDescriptor?,
    val baseOnViewCreated: MethodDescriptor?,
    val defaultWordMethods: List<MethodDescriptor>,
    val evidence: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .putOpt("gameMenuMethod", gameMenuMethod?.toJson())
        .putOpt("baseOnViewCreated", baseOnViewCreated?.toJson())
        .put("defaultWordMethods", defaultWordMethods.toJsonArray { it.toJson() })
        .put("evidence", evidence)

    fun restore(classLoader: ClassLoader): RestoredHomeTopBarSymbols? {
        val gameMenu = gameMenuMethod?.let { descriptor ->
            val owner = classLoader.loadClassOrNull(descriptor.declaringClassName) ?: return@let null
            descriptor.restore(owner)
        }
        val onViewCreated = baseOnViewCreated?.let { descriptor ->
            val owner = classLoader.loadClassOrNull(descriptor.declaringClassName) ?: return@let null
            descriptor.restore(owner)
        }
        val defaultWord = defaultWordMethods.mapNotNull { descriptor ->
            val owner = classLoader.loadClassOrNull(descriptor.declaringClassName) ?: return@mapNotNull null
            descriptor.restore(owner)
        }
        if (defaultWord.size != defaultWordMethods.size) return null
        return RestoredHomeTopBarSymbols(gameMenu, onViewCreated, defaultWord)
    }

    companion object {
        fun fromJson(obj: JSONObject): HomeTopBarSymbols = HomeTopBarSymbols(
            gameMenuMethod = obj.optJSONObject("gameMenuMethod")?.let(MethodDescriptor::fromJson),
            baseOnViewCreated = obj.optJSONObject("baseOnViewCreated")?.let(MethodDescriptor::fromJson),
            defaultWordMethods = obj.optJSONArray("defaultWordMethods").toList { MethodDescriptor.fromJson(it) },
            evidence = obj.optString("evidence", "-"),
        )
    }
}

data class RestoredHomeTopBarSymbols(
    val gameMenuMethod: Method?,
    val baseOnViewCreated: Method?,
    val defaultWordMethods: List<Method>,
)

data class BottomBarSymbols(
    val parserMethods: List<MethodDescriptor>,
    val resourceMethods: List<MethodDescriptor>,
    val evidence: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("parserMethods", parserMethods.toJsonArray { it.toJson() })
        .put("resourceMethods", resourceMethods.toJsonArray { it.toJson() })
        .put("evidence", evidence)

    fun restore(classLoader: ClassLoader): RestoredBottomBarSymbols? {
        val parsers = parserMethods.mapNotNull { descriptor ->
            val owner = classLoader.loadClassOrNull(descriptor.declaringClassName) ?: return@mapNotNull null
            descriptor.restore(owner)
        }
        val resources = resourceMethods.mapNotNull { descriptor ->
            val owner = classLoader.loadClassOrNull(descriptor.declaringClassName) ?: return@mapNotNull null
            descriptor.restore(owner)
        }
        if (parsers.size != parserMethods.size || resources.size != resourceMethods.size) return null
        return RestoredBottomBarSymbols(parsers, resources)
    }

    companion object {
        fun fromJson(obj: JSONObject): BottomBarSymbols = BottomBarSymbols(
            parserMethods = obj.optJSONArray("parserMethods").toList { MethodDescriptor.fromJson(it) },
            resourceMethods = obj.optJSONArray("resourceMethods").toList { MethodDescriptor.fromJson(it) },
            evidence = obj.optString("evidence", "-"),
        )
    }
}

data class RestoredBottomBarSymbols(
    val parserMethods: List<Method>,
    val resourceMethods: List<Method>,
)

data class HomeRecommendFeedSymbols(
    val responseGetItems: List<PegasusResponseGetItemsSymbols>,
    val getHolderType: MethodDescriptor,
    val getBizType: MethodDescriptor?,
    val getHolderStyle: MethodDescriptor?,
    val isSmallCard: MethodDescriptor?,
    val getAdInfo: MethodDescriptor?,
    val getCardType: MethodDescriptor?,
    val getCardGoto: MethodDescriptor?,
    val getGoTo: MethodDescriptor?,
    val getUri: MethodDescriptor?,
    val adInfoClassName: String?,
    val evidence: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("responseGetItems", responseGetItems.toJsonArray { it.toJson() })
        .put("getHolderType", getHolderType.toJson())
        .putOpt("getBizType", getBizType?.toJson())
        .putOpt("getHolderStyle", getHolderStyle?.toJson())
        .putOpt("isSmallCard", isSmallCard?.toJson())
        .putOpt("getAdInfo", getAdInfo?.toJson())
        .putOpt("getCardType", getCardType?.toJson())
        .putOpt("getCardGoto", getCardGoto?.toJson())
        .putOpt("getGoTo", getGoTo?.toJson())
        .putOpt("getUri", getUri?.toJson())
        .putOpt("adInfoClassName", adInfoClassName)
        .put("evidence", evidence)

    fun restore(classLoader: ClassLoader): RestoredHomeRecommendFeedSymbols? {
        val responses = responseGetItems.mapNotNull { it.restore(classLoader) }
        if (responses.size != responseGetItems.size) return null
        val holderOwner = classLoader.loadClassOrNull(getHolderType.declaringClassName) ?: return null
        return RestoredHomeRecommendFeedSymbols(
            responseGetItems = responses,
            getHolderType = getHolderType.restore(holderOwner) ?: return null,
            getBizType = getBizType.restoreOptional(classLoader),
            getHolderStyle = getHolderStyle.restoreOptional(classLoader),
            isSmallCard = isSmallCard.restoreOptional(classLoader),
            getAdInfo = getAdInfo.restoreOptional(classLoader),
            getCardType = getCardType.restoreOptional(classLoader),
            getCardGoto = getCardGoto.restoreOptional(classLoader),
            getGoTo = getGoTo.restoreOptional(classLoader),
            getUri = getUri.restoreOptional(classLoader),
            adInfoClass = adInfoClassName?.let(classLoader::loadClassOrNull),
        )
    }

    companion object {
        fun fromJson(obj: JSONObject): HomeRecommendFeedSymbols = HomeRecommendFeedSymbols(
            responseGetItems = obj.optJSONArray("responseGetItems").toList {
                PegasusResponseGetItemsSymbols.fromJson(it)
            },
            getHolderType = MethodDescriptor.fromJson(obj.getJSONObject("getHolderType")),
            getBizType = obj.optJSONObject("getBizType")?.let(MethodDescriptor::fromJson),
            getHolderStyle = obj.optJSONObject("getHolderStyle")?.let(MethodDescriptor::fromJson),
            isSmallCard = obj.optJSONObject("isSmallCard")?.let(MethodDescriptor::fromJson),
            getAdInfo = obj.optJSONObject("getAdInfo")?.let(MethodDescriptor::fromJson),
            getCardType = obj.optJSONObject("getCardType")?.let(MethodDescriptor::fromJson),
            getCardGoto = obj.optJSONObject("getCardGoto")?.let(MethodDescriptor::fromJson),
            getGoTo = obj.optJSONObject("getGoTo")?.let(MethodDescriptor::fromJson),
            getUri = obj.optJSONObject("getUri")?.let(MethodDescriptor::fromJson),
            adInfoClassName = obj.optString("adInfoClassName").takeIf { it.isNotBlank() },
            evidence = obj.optString("evidence", "-"),
        )
    }
}

data class PegasusResponseGetItemsSymbols(
    val getItems: MethodDescriptor,
    val itemsField: FieldDescriptor?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("getItems", getItems.toJson())
        .putOpt("itemsField", itemsField?.toJson())

    fun restore(classLoader: ClassLoader): RestoredPegasusResponseGetItemsSymbols? {
        val owner = classLoader.loadClassOrNull(getItems.declaringClassName) ?: return null
        return RestoredPegasusResponseGetItemsSymbols(
            getItems = getItems.restore(owner) ?: return null,
            itemsField = itemsField.restoreOptional(classLoader),
        )
    }

    companion object {
        fun fromJson(obj: JSONObject): PegasusResponseGetItemsSymbols = PegasusResponseGetItemsSymbols(
            getItems = MethodDescriptor.fromJson(obj.getJSONObject("getItems")),
            itemsField = obj.optJSONObject("itemsField")?.let(FieldDescriptor::fromJson),
        )
    }
}

data class RestoredHomeRecommendFeedSymbols(
    val responseGetItems: List<RestoredPegasusResponseGetItemsSymbols>,
    val getHolderType: Method,
    val getBizType: Method?,
    val getHolderStyle: Method?,
    val isSmallCard: Method?,
    val getAdInfo: Method?,
    val getCardType: Method?,
    val getCardGoto: Method?,
    val getGoTo: Method?,
    val getUri: Method?,
    val adInfoClass: Class<*>?,
)

data class RestoredPegasusResponseGetItemsSymbols(
    val getItems: Method,
    val itemsField: Field?,
)

data class HomeComponentHideSymbols(
    val baseHomeFragmentMethods: List<MethodDescriptor>,
    val evidence: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("baseHomeFragmentMethods", baseHomeFragmentMethods.toJsonArray { it.toJson() })
        .put("evidence", evidence)

    fun restore(classLoader: ClassLoader): RestoredHomeComponentHideSymbols? {
        val methods = baseHomeFragmentMethods.mapNotNull { it.restoreOptional(classLoader) }
        if (methods.size != baseHomeFragmentMethods.size) return null
        return RestoredHomeComponentHideSymbols(methods)
    }

    companion object {
        fun fromJson(obj: JSONObject): HomeComponentHideSymbols = HomeComponentHideSymbols(
            baseHomeFragmentMethods = obj.optJSONArray("baseHomeFragmentMethods").toList {
                MethodDescriptor.fromJson(it)
            },
            evidence = obj.optString("evidence", "-"),
        )
    }
}

data class RestoredHomeComponentHideSymbols(
    val baseHomeFragmentMethods: List<Method>,
)

data class VideoCommentSymbols(
    val disableCommentConstructors: List<ConstructorDescriptor>,
    val quickReplyViewModelMethods: List<MethodDescriptor>,
    val quickReplyDialogMethods: List<MethodDescriptor>,
    val voteWidgetMethods: List<MethodDescriptor>,
    val followWidgetMethods: List<MethodDescriptor>,
    val headerDecorativeMethods: List<MethodDescriptor>,
    val searchUrlsMethod: MethodDescriptor?,
    val emptyPageHooks: List<VideoCommentEmptyPageSymbols>,
    val mainListOnNextMethods: List<MethodDescriptor>,
    val evidence: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("disableCommentConstructors", disableCommentConstructors.toJsonArray { it.toJson() })
        .put("quickReplyViewModelMethods", quickReplyViewModelMethods.toJsonArray { it.toJson() })
        .put("quickReplyDialogMethods", quickReplyDialogMethods.toJsonArray { it.toJson() })
        .put("voteWidgetMethods", voteWidgetMethods.toJsonArray { it.toJson() })
        .put("followWidgetMethods", followWidgetMethods.toJsonArray { it.toJson() })
        .put("headerDecorativeMethods", headerDecorativeMethods.toJsonArray { it.toJson() })
        .putOpt("searchUrlsMethod", searchUrlsMethod?.toJson())
        .put("emptyPageHooks", emptyPageHooks.toJsonArray { it.toJson() })
        .put("mainListOnNextMethods", mainListOnNextMethods.toJsonArray { it.toJson() })
        .put("evidence", evidence)

    fun restore(classLoader: ClassLoader): RestoredVideoCommentSymbols? {
        val constructors = disableCommentConstructors.mapNotNull { descriptor ->
            val owner = classLoader.loadClassOrNull(descriptor.declaringClassName) ?: return@mapNotNull null
            descriptor.restore(owner)
        }
        if (constructors.size != disableCommentConstructors.size) return null
        val emptyPages = emptyPageHooks.mapNotNull { it.restore(classLoader) }
        if (emptyPages.size != emptyPageHooks.size) return null
        return RestoredVideoCommentSymbols(
            disableCommentConstructors = constructors,
            quickReplyViewModelMethods = quickReplyViewModelMethods.restoreAll(classLoader) ?: return null,
            quickReplyDialogMethods = quickReplyDialogMethods.restoreAll(classLoader) ?: return null,
            voteWidgetMethods = voteWidgetMethods.restoreAll(classLoader) ?: return null,
            followWidgetMethods = followWidgetMethods.restoreAll(classLoader) ?: return null,
            headerDecorativeMethods = headerDecorativeMethods.restoreAll(classLoader) ?: return null,
            searchUrlsMethod = searchUrlsMethod.restoreOptional(classLoader),
            emptyPageHooks = emptyPages,
            mainListOnNextMethods = mainListOnNextMethods.restoreAll(classLoader) ?: return null,
        )
    }

    companion object {
        fun fromJson(obj: JSONObject): VideoCommentSymbols = VideoCommentSymbols(
            disableCommentConstructors = obj.optJSONArray("disableCommentConstructors").toList {
                ConstructorDescriptor.fromJson(it)
            },
            quickReplyViewModelMethods = obj.optJSONArray("quickReplyViewModelMethods").toList {
                MethodDescriptor.fromJson(it)
            },
            quickReplyDialogMethods = obj.optJSONArray("quickReplyDialogMethods").toList {
                MethodDescriptor.fromJson(it)
            },
            voteWidgetMethods = obj.optJSONArray("voteWidgetMethods").toList { MethodDescriptor.fromJson(it) },
            followWidgetMethods = obj.optJSONArray("followWidgetMethods").toList { MethodDescriptor.fromJson(it) },
            headerDecorativeMethods = obj.optJSONArray("headerDecorativeMethods").toList {
                MethodDescriptor.fromJson(it)
            },
            searchUrlsMethod = obj.optJSONObject("searchUrlsMethod")?.let(MethodDescriptor::fromJson),
            emptyPageHooks = obj.optJSONArray("emptyPageHooks").toList {
                VideoCommentEmptyPageSymbols.fromJson(it)
            },
            mainListOnNextMethods = obj.optJSONArray("mainListOnNextMethods").toList {
                MethodDescriptor.fromJson(it)
            },
            evidence = obj.optString("evidence", "-"),
        )
    }
}

data class VideoCommentEmptyPageSymbols(
    val getEmptyPage: MethodDescriptor,
    val defaultInstance: FieldDescriptor,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("getEmptyPage", getEmptyPage.toJson())
        .put("defaultInstance", defaultInstance.toJson())

    fun restore(classLoader: ClassLoader): RestoredVideoCommentEmptyPageSymbols? {
        val methodOwner = classLoader.loadClassOrNull(getEmptyPage.declaringClassName) ?: return null
        val fieldOwner = classLoader.loadClassOrNull(defaultInstance.declaringClassName) ?: return null
        val field = defaultInstance.restore(fieldOwner) ?: return null
        return RestoredVideoCommentEmptyPageSymbols(
            getEmptyPage = getEmptyPage.restore(methodOwner) ?: return null,
            defaultInstance = field.get(null) ?: return null,
        )
    }

    companion object {
        fun fromJson(obj: JSONObject): VideoCommentEmptyPageSymbols = VideoCommentEmptyPageSymbols(
            getEmptyPage = MethodDescriptor.fromJson(obj.getJSONObject("getEmptyPage")),
            defaultInstance = FieldDescriptor.fromJson(obj.getJSONObject("defaultInstance")),
        )
    }
}

data class RestoredVideoCommentSymbols(
    val disableCommentConstructors: List<Constructor<*>>,
    val quickReplyViewModelMethods: List<Method>,
    val quickReplyDialogMethods: List<Method>,
    val voteWidgetMethods: List<Method>,
    val followWidgetMethods: List<Method>,
    val headerDecorativeMethods: List<Method>,
    val searchUrlsMethod: Method?,
    val emptyPageHooks: List<RestoredVideoCommentEmptyPageSymbols>,
    val mainListOnNextMethods: List<Method>,
)

data class RestoredVideoCommentEmptyPageSymbols(
    val getEmptyPage: Method,
    val defaultInstance: Any,
)

data class SkipVideoAdSymbols(
    val playViewMethods: List<MethodDescriptor>,
    val playerCoreCurrentPositionMethods: List<MethodDescriptor>,
    val playerCoreStateMethods: List<MethodDescriptor>,
    val cardCurrentPositionMethods: List<MethodDescriptor>,
    val cardStateMethods: List<MethodDescriptor>,
    val evidence: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("playViewMethods", playViewMethods.toJsonArray { it.toJson() })
        .put("playerCoreCurrentPositionMethods", playerCoreCurrentPositionMethods.toJsonArray { it.toJson() })
        .put("playerCoreStateMethods", playerCoreStateMethods.toJsonArray { it.toJson() })
        .put("cardCurrentPositionMethods", cardCurrentPositionMethods.toJsonArray { it.toJson() })
        .put("cardStateMethods", cardStateMethods.toJsonArray { it.toJson() })
        .put("evidence", evidence)

    fun restore(classLoader: ClassLoader): RestoredSkipVideoAdSymbols? {
        return RestoredSkipVideoAdSymbols(
            playViewMethods = playViewMethods.restoreAll(classLoader) ?: return null,
            playerCoreCurrentPositionMethods = playerCoreCurrentPositionMethods.restoreAll(classLoader) ?: return null,
            playerCoreStateMethods = playerCoreStateMethods.restoreAll(classLoader) ?: return null,
            cardCurrentPositionMethods = cardCurrentPositionMethods.restoreAll(classLoader) ?: return null,
            cardStateMethods = cardStateMethods.restoreAll(classLoader) ?: return null,
        )
    }

    companion object {
        fun fromJson(obj: JSONObject): SkipVideoAdSymbols = SkipVideoAdSymbols(
            playViewMethods = obj.optJSONArray("playViewMethods").toList { MethodDescriptor.fromJson(it) },
            playerCoreCurrentPositionMethods = obj.optJSONArray("playerCoreCurrentPositionMethods").toList {
                MethodDescriptor.fromJson(it)
            },
            playerCoreStateMethods = obj.optJSONArray("playerCoreStateMethods").toList {
                MethodDescriptor.fromJson(it)
            },
            cardCurrentPositionMethods = obj.optJSONArray("cardCurrentPositionMethods").toList {
                MethodDescriptor.fromJson(it)
            },
            cardStateMethods = obj.optJSONArray("cardStateMethods").toList { MethodDescriptor.fromJson(it) },
            evidence = obj.optString("evidence", "-"),
        )
    }
}

data class RestoredSkipVideoAdSymbols(
    val playViewMethods: List<Method>,
    val playerCoreCurrentPositionMethods: List<Method>,
    val playerCoreStateMethods: List<Method>,
    val cardCurrentPositionMethods: List<Method>,
    val cardStateMethods: List<Method>,
)

data class SkipVideoAdProgressSymbols(
    val progressOnDraw: MethodDescriptor?,
    val storyOnStartMethods: List<MethodDescriptor>,
    val inlineUpdateMethods: List<MethodDescriptor>,
    val panelWidgetKtClassName: String?,
    val evidence: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .putOpt("progressOnDraw", progressOnDraw?.toJson())
        .put("storyOnStartMethods", storyOnStartMethods.toJsonArray { it.toJson() })
        .put("inlineUpdateMethods", inlineUpdateMethods.toJsonArray { it.toJson() })
        .putOpt("panelWidgetKtClassName", panelWidgetKtClassName)
        .put("evidence", evidence)

    fun restore(classLoader: ClassLoader): RestoredSkipVideoAdProgressSymbols? {
        return RestoredSkipVideoAdProgressSymbols(
            progressOnDraw = progressOnDraw.restoreOptional(classLoader),
            storyOnStartMethods = storyOnStartMethods.restoreAll(classLoader) ?: return null,
            inlineUpdateMethods = inlineUpdateMethods.restoreAll(classLoader) ?: return null,
            panelWidgetKtClass = panelWidgetKtClassName?.let(classLoader::loadClassOrNull),
        )
    }

    companion object {
        fun fromJson(obj: JSONObject): SkipVideoAdProgressSymbols = SkipVideoAdProgressSymbols(
            progressOnDraw = obj.optJSONObject("progressOnDraw")?.let(MethodDescriptor::fromJson),
            storyOnStartMethods = obj.optJSONArray("storyOnStartMethods").toList {
                MethodDescriptor.fromJson(it)
            },
            inlineUpdateMethods = obj.optJSONArray("inlineUpdateMethods").toList {
                MethodDescriptor.fromJson(it)
            },
            panelWidgetKtClassName = obj.optString("panelWidgetKtClassName").takeIf { it.isNotBlank() },
            evidence = obj.optString("evidence", "-"),
        )
    }
}

data class RestoredSkipVideoAdProgressSymbols(
    val progressOnDraw: Method?,
    val storyOnStartMethods: List<Method>,
    val inlineUpdateMethods: List<Method>,
    val panelWidgetKtClass: Class<*>?,
)

data class SkipVideoAdAutoLikeSymbols(
    val detailLikeInflateMethod: MethodDescriptor?,
    val detailLikeStateOwnerClassName: String?,
    val storyWidgetClassNames: List<String>,
    val storyActionOwnerClassName: String?,
    val storyBindMethods: List<MethodDescriptor>,
    val geminiLikeWidgetClassName: String?,
    val evidence: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .putOpt("detailLikeInflateMethod", detailLikeInflateMethod?.toJson())
        .putOpt("detailLikeStateOwnerClassName", detailLikeStateOwnerClassName)
        .put("storyWidgetClassNames", storyWidgetClassNames.toJsonArray())
        .putOpt("storyActionOwnerClassName", storyActionOwnerClassName)
        .put("storyBindMethods", storyBindMethods.toJsonArray { it.toJson() })
        .putOpt("geminiLikeWidgetClassName", geminiLikeWidgetClassName)
        .put("evidence", evidence)

    fun restore(classLoader: ClassLoader): RestoredSkipVideoAdAutoLikeSymbols? {
        return RestoredSkipVideoAdAutoLikeSymbols(
            detailLikeInflateMethod = detailLikeInflateMethod.restoreOptional(classLoader),
            detailLikeStateOwnerClass = detailLikeStateOwnerClassName?.let(classLoader::loadClassOrNull),
            storyWidgetClasses = storyWidgetClassNames.mapNotNull(classLoader::loadClassOrNull)
                .takeIf { it.size == storyWidgetClassNames.size } ?: return null,
            storyActionOwnerClass = storyActionOwnerClassName?.let(classLoader::loadClassOrNull),
            storyBindMethods = storyBindMethods.restoreAll(classLoader) ?: return null,
            geminiLikeWidgetClass = geminiLikeWidgetClassName?.let(classLoader::loadClassOrNull),
        )
    }

    companion object {
        fun fromJson(obj: JSONObject): SkipVideoAdAutoLikeSymbols = SkipVideoAdAutoLikeSymbols(
            detailLikeInflateMethod = obj.optJSONObject("detailLikeInflateMethod")?.let(MethodDescriptor::fromJson),
            detailLikeStateOwnerClassName = obj.optString("detailLikeStateOwnerClassName").takeIf { it.isNotBlank() },
            storyWidgetClassNames = obj.optJSONArray("storyWidgetClassNames").toStringList(),
            storyActionOwnerClassName = obj.optString("storyActionOwnerClassName").takeIf { it.isNotBlank() },
            storyBindMethods = obj.optJSONArray("storyBindMethods").toList { MethodDescriptor.fromJson(it) },
            geminiLikeWidgetClassName = obj.optString("geminiLikeWidgetClassName").takeIf { it.isNotBlank() },
            evidence = obj.optString("evidence", "-"),
        )
    }
}

data class RestoredSkipVideoAdAutoLikeSymbols(
    val detailLikeInflateMethod: Method?,
    val detailLikeStateOwnerClass: Class<*>?,
    val storyWidgetClasses: List<Class<*>>,
    val storyActionOwnerClass: Class<*>?,
    val storyBindMethods: List<Method>,
    val geminiLikeWidgetClass: Class<*>?,
)

data class ChronosPromotionSymbols(
    val classSymbols: List<NamedClassSymbol>,
    val methodGroups: List<NamedMethodGroup>,
    val evidence: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("classSymbols", classSymbols.toJsonArray { it.toJson() })
        .put("methodGroups", methodGroups.toJsonArray { it.toJson() })
        .put("evidence", evidence)

    fun restore(classLoader: ClassLoader): RestoredChronosPromotionSymbols? {
        val classes = classSymbols.mapNotNull { symbol ->
            classLoader.loadClassOrNull(symbol.className)?.let { symbol.id to it }
        }.toMap()
        if (classes.size != classSymbols.size) return null
        val methods = methodGroups.mapNotNull { group ->
            val restored = group.methods.restoreAll(classLoader) ?: return@mapNotNull null
            group.id to restored
        }.toMap()
        if (methods.size != methodGroups.size) return null
        return RestoredChronosPromotionSymbols(classes, methods)
    }

    companion object {
        fun fromJson(obj: JSONObject): ChronosPromotionSymbols = ChronosPromotionSymbols(
            classSymbols = obj.optJSONArray("classSymbols").toList { NamedClassSymbol.fromJson(it) },
            methodGroups = obj.optJSONArray("methodGroups").toList { NamedMethodGroup.fromJson(it) },
            evidence = obj.optString("evidence", "-"),
        )
    }
}

data class NamedClassSymbol(
    val id: String,
    val className: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("className", className)

    companion object {
        fun fromJson(obj: JSONObject): NamedClassSymbol = NamedClassSymbol(
            id = obj.optString("id"),
            className = obj.optString("className"),
        )
    }
}

data class NamedMethodGroup(
    val id: String,
    val methods: List<MethodDescriptor>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("methods", methods.toJsonArray { it.toJson() })

    companion object {
        fun fromJson(obj: JSONObject): NamedMethodGroup = NamedMethodGroup(
            id = obj.optString("id"),
            methods = obj.optJSONArray("methods").toList { MethodDescriptor.fromJson(it) },
        )
    }
}

data class RestoredChronosPromotionSymbols(
    val classes: Map<String, Class<*>>,
    val methods: Map<String, List<Method>>,
) {
    fun clazz(id: String): Class<*>? = classes[id]
    fun methods(id: String): List<Method> = methods[id].orEmpty()
    fun firstMethod(id: String): Method? = methods(id).firstOrNull()
}

data class ConstructorDescriptor(
    val declaringClassName: String,
    val parameterTypeNames: List<String>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("declaringClassName", declaringClassName)
        .put("parameterTypeNames", parameterTypeNames.toJsonArray())

    fun restore(owner: Class<*>): Constructor<*>? {
        if (owner.name != declaringClassName) return null
        return owner.declaredConstructors.firstOrNull { constructor ->
            constructor.parameterTypes.map { it.name } == parameterTypeNames
        }?.apply { isAccessible = true }
    }

    companion object {
        fun of(constructor: Constructor<*>): ConstructorDescriptor = ConstructorDescriptor(
            declaringClassName = constructor.declaringClass.name,
            parameterTypeNames = constructor.parameterTypes.map { it.name },
        )

        fun fromJson(obj: JSONObject): ConstructorDescriptor = ConstructorDescriptor(
            declaringClassName = obj.optString("declaringClassName"),
            parameterTypeNames = obj.optJSONArray("parameterTypeNames").toStringList(),
        )
    }
}

data class MethodDescriptor(
    val declaringClassName: String,
    val name: String,
    val returnTypeName: String,
    val parameterTypeNames: List<String>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("declaringClassName", declaringClassName)
        .put("name", name)
        .put("returnTypeName", returnTypeName)
        .put("parameterTypeNames", parameterTypeNames.toJsonArray())

    fun restore(owner: Class<*>): Method? {
        if (owner.name != declaringClassName) return null
        return owner.declaredMethods.firstOrNull { method ->
            method.name == name &&
                method.returnType.name == returnTypeName &&
                method.parameterTypes.map { it.name } == parameterTypeNames
        }?.apply { isAccessible = true }
    }

    companion object {
        fun of(method: Method): MethodDescriptor = MethodDescriptor(
            declaringClassName = method.declaringClass.name,
            name = method.name,
            returnTypeName = method.returnType.name,
            parameterTypeNames = method.parameterTypes.map { it.name },
        )

        fun fromJson(obj: JSONObject): MethodDescriptor = MethodDescriptor(
            declaringClassName = obj.optString("declaringClassName"),
            name = obj.optString("name"),
            returnTypeName = obj.optString("returnTypeName"),
            parameterTypeNames = obj.optJSONArray("parameterTypeNames").toStringList(),
        )
    }
}

data class FieldDescriptor(
    val declaringClassName: String,
    val name: String,
    val typeName: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("declaringClassName", declaringClassName)
        .put("name", name)
        .put("typeName", typeName)

    fun restore(owner: Class<*>): Field? {
        if (owner.name != declaringClassName) return null
        return owner.declaredFields.firstOrNull { field ->
            field.name == name && field.type.name == typeName
        }?.apply { isAccessible = true }
    }

    companion object {
        fun of(field: Field): FieldDescriptor = FieldDescriptor(
            declaringClassName = field.declaringClass.name,
            name = field.name,
            typeName = field.type.name,
        )

        fun fromJson(obj: JSONObject): FieldDescriptor = FieldDescriptor(
            declaringClassName = obj.optString("declaringClassName"),
            name = obj.optString("name"),
            typeName = obj.optString("typeName"),
        )
    }
}

fun BiliHookSymbols.formatStatusLines(): List<String> =
    hookPoints.map { it.toLine() } +
        if (scanErrors.isEmpty()) {
            listOf("Scan Errors: -")
        } else {
            listOf("Scan Errors:") + scanErrors.map { "  - $it" }
        }

internal fun ClassLoader.loadClassOrNull(name: String): Class<*>? =
    runCatching { Class.forName(name, false, this) }.getOrNull()

internal fun MethodDescriptor?.restoreOptional(classLoader: ClassLoader): Method? {
    val descriptor = this ?: return null
    val owner = classLoader.loadClassOrNull(descriptor.declaringClassName) ?: return null
    return descriptor.restore(owner)
}

internal fun FieldDescriptor?.restoreOptional(classLoader: ClassLoader): Field? {
    val descriptor = this ?: return null
    val owner = classLoader.loadClassOrNull(descriptor.declaringClassName) ?: return null
    return descriptor.restore(owner)
}

internal fun List<MethodDescriptor>.restoreAll(classLoader: ClassLoader): List<Method>? {
    val methods = mapNotNull { it.restoreOptional(classLoader) }
    return methods.takeIf { it.size == size }
}

internal fun Method.hasParameterTypes(vararg names: String): Boolean =
    parameterTypes.map { it.name } == names.toList()

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    val out = ArrayList<String>(length())
    for (i in 0 until length()) {
        optString(i).takeIf { it.isNotBlank() }?.let(out::add)
    }
    return out
}

private fun <T> JSONArray?.toList(mapper: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    val out = ArrayList<T>(length())
    for (i in 0 until length()) {
        optJSONObject(i)?.let { out.add(mapper(it)) }
    }
    return out
}

private fun Iterable<String>.toJsonArray(): JSONArray =
    JSONArray().also { array -> forEach(array::put) }

private fun <T> Iterable<T>.toJsonArray(mapper: (T) -> JSONObject): JSONArray =
    JSONArray().also { array -> forEach { array.put(mapper(it)) } }

internal fun Context.packageVersionCodeLong(): Long {
    val info = packageManager.getPackageInfo(packageName, 0)
    @Suppress("DEPRECATION")
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        info.versionCode.toLong()
    }
}
