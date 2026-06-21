package io.github.bbzq.feats.hook

import android.app.Activity
import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.from
import io.github.bbzq.feats.hookAfterMethod

class TeenagersModeHook(env: RoamingEnv) : BaseRoamingHook(env) {
    override fun startHook() {
        if (!ModuleSettings.isBlockTeenagersModeDialogEnabled(prefs)) return

        val count = TARGET_ACTIVITIES.sumOf { name ->
            val activityClass = name.from(classLoader) ?: return@sumOf 0
            env.hookAfterMethod(activityClass, "onCreate", android.os.Bundle::class.java) { param ->
                val activity = param.thisObject as? Activity ?: return@hookAfterMethod
                activity.finish()
                log("Teenagers mode dialog has been closed: ${activity.javaClass.name}")
            }
        }
        if (count > 0) {
            log("TeenagersModeHook installed, methods=$count")
        } else {
            log("TeenagersModeHook: Activity not found")
        }
    }

    private companion object {
        private val TARGET_ACTIVITIES = arrayOf(
            "com.bilibili.app.preferences.TeenagersModeDialogActivity",
            "com.bilibili.p4439app.preferences.TeenagersModeDialogActivity",
            "tv.danmaku.bili.ui.teenagersmode.TeenagersModeDialogActivity",
            "com.bilibili.teenagersmode.p6010ui.TeenagersModeActivity",
            "com.bilibili.teenagersmode.osteen.OSTeensParentControlRedirectActivity",
        )
    }
}

