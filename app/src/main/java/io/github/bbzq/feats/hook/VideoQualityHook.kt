package io.github.bbzq.feats.hook

import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.hookBefore

class VideoQualityHook(env: RoamingEnv) : BaseRoamingHook(env) {
    override fun startHook() {
        if (env.processName != env.packageName) return
        val halfScreenQuality = ModuleSettings.getHalfScreenQuality(prefs)
        val fullScreenQuality = ModuleSettings.getFullScreenQuality(prefs)

        if (halfScreenQuality == 0 && fullScreenQuality == 0) {
            log("startHook: VideoQualityHook disabled (both half and full screen are default)")
            return
        }

        val symbols = env.symbols?.videoQuality?.restore(classLoader) ?: run {
            log("startHook: VideoQualityHook skipped because symbols are unavailable")
            return
        }

        var installedHooks = 0

        // 1. Prevent half-screen preloading low quality streams
        if (halfScreenQuality != 0) {
            symbols.playerPreloadGetMethods.forEach { method ->
                runCatching {
                    env.hookBefore(method) { param ->
                        param.result = null
                    }
                    installedHooks++
                }.onFailure { log("Failed to hook preload method ${method.name}", it) }
            }

            symbols.playerQualityServiceMethods.forEach { method ->
                runCatching {
                    env.hookBefore(method) { param ->
                        param.result = halfScreenQuality
                    }
                    installedHooks++
                }.onFailure { log("Failed to hook quality service method ${method.name}", it) }
            }
        }

        // 2. Full screen setting helper
        if (fullScreenQuality != 0) {
            symbols.playerSettingHelperGetDefaultQnMethod?.let { method ->
                runCatching {
                    env.hookBefore(method) { param ->
                        param.result = fullScreenQuality
                    }
                    installedHooks++
                }.onFailure { log("Failed to hook setting helper getDefaultQn", it) }
            }
        }

        // 3. AutoSupremumQuality constructor overriding & strategy routing
        symbols.autoSupremumQualityConstructor?.let { ctor ->
            runCatching {
                env.hookBefore(ctor) { param ->
                    if (param.args.size >= 6) {
                        if (halfScreenQuality != 0) {
                            param.args[0] = halfScreenQuality // loginHalf
                            param.args[3] = halfScreenQuality // unloginHalf
                            param.args[4] = halfScreenQuality // unloginFull
                            param.args[5] = halfScreenQuality // unloginMobileFull
                        }
                        if (fullScreenQuality != 0) {
                            param.args[1] = fullScreenQuality // loginFull
                            param.args[2] = fullScreenQuality // loginMobileFull
                        }
                    }
                }
                installedHooks++
            }.onFailure { log("Failed to hook AutoSupremumQuality constructor", it) }
        }

        symbols.qualityStrategySelectMethod?.let { method ->
            runCatching {
                env.hookBefore(method) { param ->
                    if (param.args.size >= 3) {
                        // param.args[1]: isFullscreen, param.args[2]: isVideoPortrait
                        // Setting isVideoPortrait = true routes through the designated full/half tier
                        param.args[2] = true
                    }
                }
                installedHooks++
            }.onFailure { log("Failed to hook QualityStrategy selectQuality method", it) }
        }

        log("startHook: VideoQualityHook active halfQn=$halfScreenQuality fullQn=$fullScreenQuality hooks=$installedHooks")
    }
}
