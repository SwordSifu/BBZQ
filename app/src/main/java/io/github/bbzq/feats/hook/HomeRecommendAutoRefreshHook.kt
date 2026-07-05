package io.github.bbzq.feats.hook

import io.github.bbzq.ModuleSettings
import io.github.bbzq.ModuleSettingsBridge
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.hookBefore

class HomeRecommendAutoRefreshHook(env: RoamingEnv) : BaseRoamingHook(env) {
    private var blockedCount = 0

    override fun startHook() {
        if (env.processName != env.packageName) return
        val enabled = ModuleSettings.isBlockHomeRecommendAutoRefreshEnabled(prefs)
        if (!enabled) {
            log("startHook: HomeRecommendAutoRefresh disabled, settings=${ModuleSettingsBridge.lastStatus}")
            return
        }

        val symbols = env.symbols?.homeRecommendAutoRefresh?.restore(classLoader)
        if (symbols == null) {
            log("startHook: HomeRecommendAutoRefresh skipped because symbols are unavailable")
            return
        }

        env.hookBefore(symbols.autoRefreshMethod) { param ->
            val flushName = (param.args.firstOrNull() as? Enum<*>)?.name ?: return@hookBefore
            if (flushName !in BLOCKED_FLUSHES) return@hookBefore
            param.result = null
            logBlocked(flushName)
        }
        log(
            "startHook: HomeRecommendAutoRefresh at " +
                    "${symbols.autoRefreshMethod.declaringClass.name}.${symbols.autoRefreshMethod.name}",
        )
    }

    private fun logBlocked(flushName: String) {
        val count = ++blockedCount
        if (count <= 20 || count % 20 == 0) {
            log("HomeRecommendAutoRefresh blocked $flushName count=$count")
        }
    }

    private companion object {
        private val BLOCKED_FLUSHES = setOf(
            "AUTO_BACK_FROM_BACKGROUND",
            "AUTO_BACK_FROM_BEHAVIOR",
            "AUTO_BACK_FROM_OTHER_PAGE",
        )
    }
}

