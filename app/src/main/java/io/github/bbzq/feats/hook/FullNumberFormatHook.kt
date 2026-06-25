package io.github.bbzq.feats.hook

import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.hookBefore

class FullNumberFormatHook(env: RoamingEnv) : BaseRoamingHook(env) {
    override fun startHook() {
        if (env.processName != env.packageName) return

        val methods = env.symbols?.fullNumberFormat?.restore(classLoader)?.formatterMethods.orEmpty()
        if (methods.isEmpty()) {
            log("startHook: FullNumberFormat skipped because symbols are unavailable")
            return
        }

        methods.forEach { method ->
            env.hookBefore(method) { param ->
                if (!ModuleSettings.isFullNumberFormatEnabled(prefs)) return@hookBefore

                val rawNumber = when (val value = param.args.firstOrNull()) {
                    is Long -> value
                    is Int -> value.toLong()
                    else -> return@hookBefore
                }

                if (rawNumber >= 0) {
                    param.result = rawNumber.toString()
                }
            }
        }

        log("startHook: FullNumberFormat, methods=${methods.size}")
    }
}
