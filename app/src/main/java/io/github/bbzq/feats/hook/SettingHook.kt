package io.github.bbzq.feats.hook

import android.app.Activity
import dalvik.system.BaseDexClassLoader
import dalvik.system.DexFile
import io.github.bbzq.ModuleSettingsNavigator
import io.github.bbzq.RuntimeEnvironmentInfo
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.allMethods
import io.github.bbzq.feats.callMethod
import io.github.bbzq.feats.from
import io.github.bbzq.feats.getObjectField
import io.github.bbzq.feats.hookAfter
import io.github.bbzq.feats.methodsNamed
import io.github.bbzq.feats.newInstanceOrNull
import java.lang.reflect.Proxy

class SettingHook(env: RoamingEnv) : BaseRoamingHook(env) {
    override fun startHook() {
        val count = findPreferenceFragments().sumOf(::hookPreferenceFragment)
        log("startHook: Setting, entries=$count")
    }

    private fun findPreferenceFragments(): List<Class<*>> {
        val classes = linkedSetOf<Class<*>>()
        DIRECT_FRAGMENT_NAMES.mapNotNullTo(classes) { it.from(classLoader) }

        val wideBaseClass = WIDE_BASE_FRAGMENT.from(classLoader)
        dexClassNames()
            .filter(::mightBePreferenceFragmentName)
            .mapNotNull { name -> runCatching { Class.forName(name, false, classLoader) }.getOrNull() }
            .filter { type ->
                type.allMethods().any { method ->
                    method.name == "onCreatePreferences" && method.parameterCount == 2
                } && (
                    wideBaseClass?.isAssignableFrom(type) == true ||
                        "BiliPreferencesActivity\$BiliPreferencesFragment" in type.name ||
                        ".preferences.fragment." in type.name
                    )
            }
            .forEach { classes += it }

        return classes.toList()
    }

    private fun hookPreferenceFragment(fragmentClass: Class<*>): Int {
        val method = fragmentClass.methodsNamed("onCreatePreferences")
            .firstOrNull { it.parameterCount == 2 }
            ?: return 0
        env.hookAfter(method) { param ->
            param.thisObject?.let { fragment ->
                runCatching { injectEntry(fragment) }
                    .onFailure { log("Failed to inject BBZQ settings entry", it) }
            }
        }
        return 1
    }

    private fun injectEntry(fragment: Any) {
        if (fragment.callMethod("findPreference", ENTRY_KEY) != null) return
        val activity = fragment.callMethod("getActivity") as? Activity ?: return
        val group = TARGET_GROUP_KEYS.firstNotNullOfOrNull { key ->
            fragment.callMethod("findPreference", key)
        } ?: fragment.callMethod("getPreferenceScreen") ?: return

        val entry = createPreference(fragment, activity) ?: return
        group.callMethod("addPreference", entry)
        log("Injected BBZQ settings entry into ${fragment.javaClass.name}")
    }

    private fun createPreference(fragment: Any, activity: Activity): Any? {
        val preferenceClass = PREFERENCE_CLASSES.firstNotNullOfOrNull { it.from(classLoader) }
            ?: return null
        val preference = preferenceClass.newInstanceOrNull(activity) ?: return null
        preference.callMethod("setKey", ENTRY_KEY)
        preference.callMethod("setTitle", ENTRY_TITLE)
        preference.callMethod("setSummary", ENTRY_SUMMARY)
        preference.callMethod("setPersistent", false)
        preference.callMethod("setSelectable", true)
        resolveAnchorOrder(fragment)?.let { preference.callMethod("setOrder", it + 1) }

        val setter = preference.javaClass.methodsNamed("setOnPreferenceClickListener")
            .firstOrNull { it.parameterCount == 1 && it.parameterTypes[0].isInterface }
            ?: return preference
        val listenerType = setter.parameterTypes[0]
        val listener = Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { _, method, _ ->
            if (method.name == "onPreferenceClick") {
                ModuleSettingsNavigator.open(activity, runtimeSnapshot())
                true
            } else {
                null
            }
        }
        setter.invoke(preference, listener)
        return preference
    }

    private fun runtimeSnapshot() =
        RuntimeEnvironmentInfo.runtimeSnapshotBundle(
            hostContext = env.hostContext,
            processName = env.processName,
            xposedApiVersion = runCatching { xposed.apiVersion.toString() }.getOrDefault("unknown"),
            xposedFrameworkName = runCatching { xposed.frameworkName }.getOrDefault("unknown"),
            xposedFrameworkVersion = runCatching { xposed.frameworkVersion }.getOrDefault("unknown"),
            xposedFrameworkVersionCode = runCatching { xposed.frameworkVersionCode.toString() }.getOrDefault("unknown"),
            xposedFrameworkProperties = runCatching { xposed.frameworkProperties.toString() }.getOrDefault("unknown"),
        )

    private fun resolveAnchorOrder(fragment: Any): Int? {
        return ANCHOR_KEYS.firstNotNullOfOrNull { key ->
            val preference = fragment.callMethod("findPreference", key) ?: return@firstNotNullOfOrNull null
            preference.callMethod("getOrder") as? Int
        }
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

    private fun mightBePreferenceFragmentName(name: String): Boolean {
        return "preferences" in name &&
            "fragment" in name &&
            !name.contains('$')
    }

    private companion object {
        private const val ENTRY_KEY = "bbzq_settings"
        private const val ENTRY_TITLE = "高级设置"
        private const val ENTRY_SUMMARY = "BBZQ 设置"
        private const val WIDE_BASE_FRAGMENT = "com.bilibili.p4439app.preferences.fragment.BaseWidePreferenceFragment"

        private val TARGET_GROUP_KEYS = arrayOf(
            "pref_key_tools_setting",
            "categoryAdvanced",
        )
        private val ANCHOR_KEYS = arrayOf(
            "pref_key_side_center",
            "pref_clear_storage",
        )
        private val PREFERENCE_CLASSES = arrayOf(
            "com.bilibili.p4439app.preferences.settingWide.CornerPreference",
            "com.bilibili.app.preferences.settingWide.CornerPreference",
            "tv.danmaku.p9138bili.widget.preference.BLPreference",
            "androidx.preference.Preference",
        )
        private val DIRECT_FRAGMENT_NAMES = arrayOf(
            "com.bilibili.p4439app.preferences.BiliPreferencesActivity\$BiliPreferencesFragment",
            "com.bilibili.app.preferences.BiliPreferencesActivity\$BiliPreferencesFragment",
            "com.bilibili.p4439app.preferences.fragment.WideBiliPreferencesFragment",
            "com.bilibili.app.preferences.fragment.WideBiliPreferencesFragment",
            "com.bilibili.p4439app.preferences.fragment.BaseWidePreferenceFragment",
        )
    }
}
