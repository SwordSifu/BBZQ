package io.github.bbzq.feats.hook

import android.content.SharedPreferences
import io.github.bbzq.ModuleSettings
import io.github.bbzq.ModuleSettingsBridge
import io.github.bbzq.StoryVideoAdTag
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.MethodHookParam
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.getObjectField
import io.github.bbzq.feats.hookAfter
import io.github.bbzq.feats.hookBefore
import io.github.bbzq.feats.replace
import java.lang.reflect.Method

class StoryPlayerAdHook(env: RoamingEnv) : BaseRoamingHook(env) {
    override fun startHook() {
        if (env.processName != env.packageName) return
        val enabled = ModuleSettings.isPurifyStoryVideoAdEnabled(prefs)
        if (!enabled) {
            log("startHook: StoryPlayerAd disabled, provider=${ModuleSettingsBridge.lastProviderStatus}")
            return
        }

        val symbols = env.symbols?.storyPlayerAd?.restore(classLoader)
        if (symbols == null) {
            log("startHook: StoryPlayerAd skipped because symbols are unavailable")
            return
        }

        var hookCount = 0
        symbols.feedGetItems?.let { hookCount += installStoryFeedResponseHook(it) }
        hookCount += installStoryPagerPlayerHook(symbols.pagerListMethods)
        if (symbols.rerankInvokeSuspend != null && symbols.kotlinUnit != null) {
            hookCount += installStoryAdRerankHook(symbols.rerankInvokeSuspend, symbols.kotlinUnit)
        }
        if (hookCount == 0) {
            log("startHook: StoryPlayerAd no hook point found")
        }
    }

    private fun installStoryFeedResponseHook(getItems: Method): Int {
        env.hookAfter(getItems) { param ->
            val result = filterReturnList(param)
            if (result != null) {
                log(
                    "StoryPlayerAd removed ${result.removed} item(s) " +
                        "reasons=${result.reasonSummary()} " +
                        "from ${getItems.declaringClass.name}.${getItems.name}",
                )
            }
        }
        log("startHook: StoryPlayerAd at ${getItems.declaringClass.name}.${getItems.name}")
        return 1
    }

    private fun installStoryPagerPlayerHook(methods: List<Method>): Int {
        methods.forEach { method ->
            env.hookBefore(method) { param ->
                val result = filterArgumentList(param, 0)
                if (result != null) {
                    log(
                        "StoryPlayerAd removed ${result.removed} item(s) " +
                            "reasons=${result.reasonSummary()} " +
                            "from ${method.declaringClass.name}.${method.name}",
                    )
                }
            }
        }
        if (methods.isNotEmpty()) {
            log(
                "startHook: StoryPlayerAd at ${methods.first().declaringClass.name}, " +
                    "methods=${methods.joinToString(",") { it.name }}",
            )
        }
        return methods.size
    }

    private fun installStoryAdRerankHook(invokeSuspend: Method, unit: Any): Int {
        env.replace(invokeSuspend) {
            log("StoryPlayerAd disabled story ad rerank request")
            unit
        }
        log("startHook: StoryPlayerAd at ${invokeSuspend.declaringClass.name}.${invokeSuspend.name}")
        return 1
    }

    private fun filterReturnList(param: MethodHookParam): FilterResult? {
        val items = (param.result as? List<*>)?.map { it } ?: return null
        val result = filteredStoryList(items)
        if (result.removed == 0) return null
        param.result = result.items
        incrementBlockedCount(prefs, result.removed)
        return result
    }

    private fun filterArgumentList(param: MethodHookParam, index: Int): FilterResult? {
        val items = (param.args.getOrNull(index) as? List<*>)?.map { it } ?: return null
        val result = filteredStoryList(items)
        if (result.removed == 0) return null
        param.args[index] = result.items
        incrementBlockedCount(prefs, result.removed)
        return result
    }

    private fun filteredStoryList(items: List<Any?>): FilterResult {
        val selectedTags = ModuleSettings.getPurifyStoryVideoAdTags(prefs)
        if (selectedTags.isEmpty()) return FilterResult(items, 0)

        val tags = ModuleSettings.storyVideoAdTags.associateBy { it.key }
        val filtered = ArrayList<Any?>(items.size)
        val reasons = linkedMapOf<String, Int>()
        var removed = 0
        items.forEach { item ->
            val reason = removeReason(item, selectedTags, tags)
            if (reason != null) {
                removed += 1
                reasons[reason] = (reasons[reason] ?: 0) + 1
            } else {
                filtered += item
            }
        }
        return FilterResult(filtered, removed, reasons)
    }

    private fun removeReason(
        item: Any?,
        selectedTags: Set<String>,
        tags: Map<String, StoryVideoAdTag>,
    ): String? {
        if (item == null) return null
        if (item.javaClass.name != STORY_DETAIL) return null
        if ("ad" in selectedTags && isStoryAd(item)) return "ad"
        if ("live" in selectedTags && isStoryLive(item)) return "live"
        val entryText = readCartEntryText(item) ?: return null
        return selectedTags.firstOrNull { key -> tags[key]?.cartText == entryText }
            ?.let { "tag:$it" }
    }

    private fun isStoryAd(item: Any): Boolean =
        runCatching {
            item.javaClass.getDeclaredMethod("isAd").apply { isAccessible = true }.invoke(item) as? Boolean
        }.getOrNull()
            ?: (item.getObjectField("ad") as? Boolean)
            ?: false

    private fun isStoryLive(item: Any): Boolean {
        val playerArgs = callPlayerArgs(item) ?: return false
        val roomId = fieldValue(playerArgs, "roomId").asIntOrNull() ?: 0
        val isLive = fieldValue(playerArgs, "isLive").asIntOrNull() ?: 0
        val videoType = fieldValue(playerArgs, "videoType")?.toString()
        val uri = runCatching { item.javaClass.getMethod("getUri").invoke(item)?.toString() }.getOrNull()
        return roomId > 0 ||
            isLive == 1 ||
            videoType == LIVE_GOTO ||
            uri?.contains(LIVE_URI_PART) == true
    }

    private fun callPlayerArgs(item: Any): Any? =
        runCatching {
            item.javaClass.getDeclaredMethod("getPlayerArgs").apply { isAccessible = true }.invoke(item)
        }.getOrNull() ?: item.getObjectField("playerArgs")

    private fun fieldValue(target: Any?, name: String): Any? =
        if (target == null) {
            null
        } else {
            runCatching {
                target.javaClass.getField(name).get(target)
            }.getOrNull()
        }

    private fun Any?.asIntOrNull(): Int? =
        when (this) {
            is Number -> toInt()
            is String -> toIntOrNull()
            else -> null
        }

    private fun readCartEntryText(item: Any): String? {
        val cart = runCatching {
            item.javaClass.getDeclaredMethod("getCartIconInfo").apply { isAccessible = true }.invoke(item)
        }.getOrNull() ?: item.getObjectField("cartIconInfo")
        return cart?.let {
            runCatching {
                it.javaClass.getDeclaredMethod("getEntryText").apply { isAccessible = true }.invoke(it) as? String
            }.getOrNull() ?: it.getObjectField("entryText")?.toString()
        }
    }

    private fun incrementBlockedCount(prefs: SharedPreferences, count: Int) {
        val oldValue = prefs.getInt(ModuleSettings.KEY_PURIFY_STORY_VIDEO_AD_BLOCKED_COUNT, 0)
        prefs.edit().putInt(ModuleSettings.KEY_PURIFY_STORY_VIDEO_AD_BLOCKED_COUNT, oldValue + count).apply()
    }

    private data class FilterResult(
        val items: List<Any?>,
        val removed: Int,
        val reasons: Map<String, Int> = emptyMap(),
    ) {
        fun reasonSummary(): String =
            reasons.entries.joinToString(",") { (reason, count) -> "$reason:$count" }
    }

    private companion object {
        private const val STORY_DETAIL = "com.bilibili.video.story.StoryDetail"
        private const val LIVE_GOTO = "live"
        private const val LIVE_URI_PART = "live.bilibili.com/"
    }
}

