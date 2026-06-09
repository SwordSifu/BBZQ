package io.github.bzzq.hooks

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

class MiniGameRewardAdHook(
    override val targetPackageName: String,
) : AppHook {
    override fun install(xposed: XposedInterface, packageReady: PackageReadyParam, log: (String, Throwable?) -> Unit) {
        val classLoader = packageReady.getClassLoader()
        val rewardActivityClass = findClass(classLoader, REWARD_ACTIVITY_CLASS_NAME)
        val rewardHeaderViewClass = findClass(classLoader, REWARD_HEADER_VIEW_CLASS_NAME)
        val countDownTextViewClass = findClass(classLoader, COUNT_DOWN_TEXT_VIEW_CLASS_NAME)
        val jumpClockField = findJumpClockField(classLoader)

        hookRewardActivity(xposed, rewardActivityClass, jumpClockField, log)
        hookRewardHeaderView(xposed, rewardHeaderViewClass, log)
        hookCountDownTextView(xposed, countDownTextViewClass, log)
    }

    private fun hookRewardActivity(
        xposed: XposedInterface,
        activityClass: Class<*>?,
        jumpClockField: Field?,
        log: (String, Throwable?) -> Unit,
    ) {
        if (activityClass == null) {
            log("Mini game reward activity class not found", null)
            return
        }

        runCatching {
            hookActivityMethod(xposed, activityClass.getDeclaredMethod("onCreate", Bundle::class.java)) { chain ->
                val result = chain.proceed()
                scheduleRewardButtonSweep(chain.getThisObject())
                result
            }

            hookActivityMethod(xposed, activityClass.getDeclaredMethod("onResume")) { chain ->
                backdateJumpClock(jumpClockField)
                val result = chain.proceed()
                scheduleRewardButtonSweep(chain.getThisObject())
                result
            }

            hookActivityMethod(xposed, activityClass.getDeclaredMethod("onStop")) { chain ->
                val result = chain.proceed()
                backdateJumpClock(jumpClockField)
                result
            }
            log("Installed mini game reward activity hook for ${activityClass.name}", null)
        }.onFailure {
            log("Failed to install mini game reward activity hook", it)
        }
    }

    private fun hookActivityMethod(
        xposed: XposedInterface,
        method: Method,
        hooker: (XposedInterface.Chain) -> Any?,
    ) {
        method.isAccessible = true
        xposed.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain -> hooker(chain) }
    }

    private fun hookRewardHeaderView(
        xposed: XposedInterface,
        headerClass: Class<*>?,
        log: (String, Throwable?) -> Unit,
    ) {
        if (headerClass == null) {
            log("Mini game reward header view class not found", null)
            return
        }

        runCatching {
            hookSetTotalTime(xposed, headerClass)
            hookSetElapsedTime(xposed, headerClass)
            hookStartTimer(xposed, headerClass)
            log("Installed mini game reward header timer hook for ${headerClass.name}", null)
        }.onFailure {
            log("Failed to install mini game reward header timer hook", it)
        }
    }

    private fun hookCountDownTextView(
        xposed: XposedInterface,
        textClass: Class<*>?,
        log: (String, Throwable?) -> Unit,
    ) {
        if (textClass == null) {
            log("Mini game countdown text view class not found", null)
            return
        }

        runCatching {
            hookSetTotalTime(xposed, textClass)
            log("Installed mini game countdown text hook for ${textClass.name}", null)
        }.onFailure {
            log("Failed to install mini game countdown text hook", it)
        }
    }

    private fun hookSetTotalTime(xposed: XposedInterface, targetClass: Class<*>) {
        val method = targetClass.getDeclaredMethod("setTotalTime", Integer.TYPE)
        method.isAccessible = true
        xposed.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                val total = (chain.getArg(0) as? Number)?.toInt() ?: 0
                if (total > 1) {
                    chain.proceed(arrayOf<Any>(1))
                } else {
                    chain.proceed()
                }
            }
    }

    private fun hookSetElapsedTime(xposed: XposedInterface, targetClass: Class<*>) {
        val method = targetClass.getDeclaredMethod("setElapsedTime", java.lang.Long.TYPE)
        method.isAccessible = true
        xposed.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                val elapsed = (chain.getArg(0) as? Number)?.toLong() ?: 0L
                if (elapsed < REWARD_FAST_FORWARD_MS) {
                    chain.proceed(arrayOf<Any>(REWARD_FAST_FORWARD_MS))
                } else {
                    chain.proceed()
                }
            }
    }

    private fun hookStartTimer(xposed: XposedInterface, targetClass: Class<*>) {
        val method = targetClass.getDeclaredMethod("startTimer")
        method.isAccessible = true
        xposed.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                invokeSetElapsedTime(chain.getThisObject(), REWARD_FAST_FORWARD_MS)
                chain.proceed()
            }
    }

    private fun backdateJumpClock(jumpClockField: Field?) {
        runCatching {
            jumpClockField?.set(null, System.currentTimeMillis() - JUMP_FAST_FORWARD_MS)
        }
    }

    private fun invokeSetElapsedTime(target: Any?, elapsedMs: Long) {
        if (target == null) return

        runCatching {
            val method = target.javaClass.getMethod("setElapsedTime", java.lang.Long.TYPE)
            method.invoke(target, elapsedMs)
        }
    }

    private fun scheduleRewardButtonSweep(target: Any?) {
        val activity = target as? Activity ?: return
        val decor = activity.window?.decorView ?: return
        for (delay in SWEEP_DELAYS_MS) {
            decor.postDelayed({ clickFirstCandidate(activity, decor, intArrayOf(0)) }, delay)
        }
    }

    private fun clickFirstCandidate(activity: Activity, view: View?, count: IntArray): Boolean {
        if (view == null || count[0]++ > MAX_VIEW_SCAN_NODES || !view.isShown) return false

        if (isClickCandidate(activity, view) && performClick(view)) return true

        val viewGroup = view as? ViewGroup ?: return false
        for (i in viewGroup.childCount - 1 downTo 0) {
            if (clickFirstCandidate(activity, viewGroup.getChildAt(i), count)) return true
        }
        return false
    }

    private fun isClickCandidate(activity: Activity, view: View): Boolean {
        val text = if (view is TextView) view.text else null
        return shouldClickText(activity, text) || shouldClickText(activity, view.contentDescription)
    }

    private fun shouldClickText(activity: Activity, rawText: CharSequence?): Boolean {
        val compact = rawText?.toString()
            ?.trim()
            ?.replace(" ", "")
            ?.replace("\n", "")
            ?: return false
        if (compact.isEmpty()) return false

        return compact.contains("跳过") ||
            compact.contains("领取奖励") ||
            compact.contains("立即领取") ||
            compact.contains("已获得奖励") ||
            (!isRewardActivity(activity) && compact.contains("关闭广告"))
    }

    private fun performClick(view: View): Boolean {
        if (!view.isEnabled) return false
        if (view.isClickable || view.hasOnClickListeners()) return view.performClick()

        val parent = view.parent as? View
        return parent != null &&
            parent.isShown &&
            parent.isEnabled &&
            (parent.isClickable || parent.hasOnClickListeners()) &&
            parent.performClick()
    }

    private fun isRewardActivity(activity: Activity): Boolean {
        return activity.javaClass.name.startsWith("com.bilibili.ad.reward.")
    }

    private fun findClass(classLoader: ClassLoader, className: String): Class<*>? {
        return runCatching { Class.forName(className, false, classLoader) }.getOrNull()
    }

    private fun findJumpClockField(classLoader: ClassLoader): Field? {
        val clazz = findClass(classLoader, JUMP_CLOCK_CLASS_NAME) ?: return null
        val field = clazz.declaredFields.singleOrNull { field ->
            Modifier.isStatic(field.modifiers) && field.type == Long::class.javaObjectType
        } ?: return null
        field.isAccessible = true
        return field
    }

    private companion object {
        private const val REWARD_ACTIVITY_CLASS_NAME = "com.bilibili.ad.reward.activity.BaseRewardAdActivity"
        private const val REWARD_HEADER_VIEW_CLASS_NAME = "com.bilibili.ad.reward.view.header.RewardAdHeaderView"
        private const val COUNT_DOWN_TEXT_VIEW_CLASS_NAME = "com.bilibili.ad.reward.view.header.CountDownTextView"
        private const val JUMP_CLOCK_CLASS_NAME = "Pe.k"

        private const val REWARD_FAST_FORWARD_MS = 60_000L
        private const val JUMP_FAST_FORWARD_MS = 15_000L
        private const val MAX_VIEW_SCAN_NODES = 320
        private val SWEEP_DELAYS_MS = longArrayOf(0L, 250L, 800L, 1500L)
    }
}
