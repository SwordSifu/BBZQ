package io.github.bbzq.feats.hook

import android.content.Context
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.findClassOrNull
import io.github.bbzq.feats.getObjectField
import io.github.bbzq.feats.hookAfter
import io.github.bbzq.feats.hookAfterAllConstructors
import io.github.bbzq.feats.hookBeforeAllMethods
import io.github.bbzq.feats.methodOrNull
import io.github.bbzq.feats.setBooleanField
import io.github.bbzq.feats.setIntField
import io.github.bbzq.feats.setObjectField

class WoMicHook(env: RoamingEnv) : BaseRoamingHook(env) {

    override fun startHook() {
        if (env.processName != env.packageName) {
            log("WoMicHook: skip non-main process (${env.processName})")
            return
        }
        log("WoMicHook: starting for ${env.packageName}...")

        var count = 0
        count += hookSubscription()  // 伪造订阅付费状态
        count += hookVolumeSeekbar()  // 拖拽音量条时强制解锁高级音量
        count += hookAds()  // 拦截AdMob广告加载展示

        log("WoMicHook: installed $count hook(s)")
    }

    // 订阅伪造
    private fun hookSubscription(): Int {
        var count = 0
        val cls = classLoader.findClassOrNull(SUBSCRIPTION_CLASS) ?: return logSkip("O2.a missing")

        // 实例构造时直接覆写付费字段
        count += env.hookAfterAllConstructors(cls) { param ->
            patchSubscriptionFields(param.thisObject)
            logOnce("sub_ctor", "purchaseState → $SUBSCRIBED")
        }

        // 拦截静态获取单例方法，每次取出实例强制重写订阅字段
        val getter = cls.methodOrNull("a", Context::class.java)
        getter?.let {
            env.hookAfter(it) { param ->
                patchSubscriptionFields(param.result)
            }
            count++
            logOnce("sub_getter", "Singleton getter patched")
        } ?: log("sub_getter: static a(Context) not found")

        log("Subscription: $count hook(s)")
        return count
    }

    // 统一修改订阅管理类付费相关字段
    private fun patchSubscriptionFields(instance: Any?) {
        instance ?: return
        instance.setIntField(PURCHASE_STATE_FIELD, SUBSCRIBED)
        instance.setObjectField(PRODUCT_FIELD, FAKE_PRODUCT_ID)
    }

    // 拖拽结束前强制解锁付费标记，规避ViewModel加载时序问题
    private fun hookVolumeSeekbar(): Int {
        val listenerCls = classLoader.findClassOrNull(SEEKBAR_LISTENER)
            ?: return logSkip("Z2.d missing")

        val count = env.hookBeforeAllMethods(listenerCls, "onStopTrackingTouch") { param ->
            val self = param.thisObject ?: return@hookBeforeAllMethods
            val selector = self.getObjectField("a") as? Int
            // 仅处理音量调节滑块
            if (selector != VOLUME_SELECTOR) return@hookBeforeAllMethods

            val mainFragment = self.getObjectField("b") ?: return@hookBeforeAllMethods
            // 强制开启高级音量权限标记
            mainFragment.setBooleanField("r0", true)
            logOnce("volume", "r0=true on onStopTrackingTouch")
        }

        log("Volume seekbar: $count hook(s)")
        return count
    }

    // 广告拦截
    private fun hookAds(): Int {
        var count = 0
        count += hookAdMethods("com.google.android.gms.ads.AdView", "loadAd")
        count += hookAdMethods("com.google.android.gms.ads.interstitial.InterstitialAd", "load", "show")
        count += hookAdMethods("com.google.android.gms.ads.rewarded.RewardedAd", "load", "show")
        log("Ads: $count hook(s)")
        return count
    }

    private fun hookAdMethods(className: String, vararg methodNames: String): Int {
        val cls = classLoader.findClassOrNull(className) ?: return 0
        return methodNames.sumOf { name ->
            env.hookBeforeAllMethods(cls, name) { param ->
                param.result = null
                logOnce("ad_${className.substringAfterLast('.')}_$name", "Blocked $name")
            }
        }
    }

    private val logged = hashSetOf<String>()
    private fun logOnce(key: String, msg: String) {
        if (logged.add(key)) log(msg)
    }

    private fun logSkip(reason: String): Int {
        log("skip: $reason")
        return 0
    }

    private companion object {
        // O2.a 订阅状态
        private const val SUBSCRIPTION_CLASS = "O2.a"
        private const val PURCHASE_STATE_FIELD = "f"
        private const val PRODUCT_FIELD = "g" // string 商品ID
        private const val SUBSCRIBED = 1
        private const val FAKE_PRODUCT_ID = "bbzq_unlocked"
        // Z2.d 音量滑块
        private const val SEEKBAR_LISTENER = "Z2.d"
        private const val VOLUME_SELECTOR = 0
    }
}