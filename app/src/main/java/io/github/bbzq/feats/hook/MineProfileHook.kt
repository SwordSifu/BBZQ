package io.github.bbzq.feats.hook

import android.view.View
import dalvik.system.BaseDexClassLoader
import dalvik.system.DexFile
import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.from
import io.github.bbzq.feats.getObjectField
import io.github.bbzq.feats.hookBefore
import io.github.bbzq.feats.setIntField
import io.github.bbzq.feats.setObjectField
import java.lang.reflect.Constructor

class MineProfileHook(env: RoamingEnv) : BaseRoamingHook(env) {
    override fun startHook() {
        if (env.processName != env.packageName) return

        var count = 0
        count += hookMoreServiceMenu()
        count += hookVipEntrance()
        log("startHook: MineProfile, methods=$count")
    }

    private fun hookMoreServiceMenu(): Int {
        val itemClass = MENU_GROUP_ITEM_CLASSES.firstNotNullOfOrNull { it.from(classLoader) } ?: return 0
        val itemCtor = itemClass.declaredConstructors
            .firstOrNull { it.parameterCount == 0 }
            ?.apply { isAccessible = true }
            ?: return 0
        val fragmentClass = resolveHomeUserCenterFragmentClass() ?: return 0
        val buildMethod = fragmentClass.declaredMethods.firstOrNull { method ->
            method.parameterTypes.any { it == android.content.Context::class.java } &&
                method.parameterTypes.any { java.util.List::class.java.isAssignableFrom(it) }
        } ?: return 0

        env.hookBefore(buildMethod) { param ->
            runCatching {
                val addSearch = ModuleSettings.isMineAddSearchEnabled(prefs)
                val addMessages = ModuleSettings.isMineAddMessagesEnabled(prefs)
                if (!addSearch && !addMessages) return@runCatching

                val listIndex = param.args.indexOfFirst { it is MutableList<*> || it is List<*> }
                if (listIndex < 0) return@runCatching

                @Suppress("UNCHECKED_CAST")
                val groups = param.args[listIndex] as? List<Any?> ?: return@runCatching
                groups.forEach { group ->
                    if (group == null) return@forEach
                    val style = group.getObjectField("style") as? Int ?: return@forEach
                    if (style != 2) return@forEach

                    @Suppress("UNCHECKED_CAST")
                    val items = group.getObjectField("itemList") as? MutableList<Any?> ?: return@forEach
                    if (addSearch && items.none { itemHasUri(it, SEARCH_URI) }) {
                        createItem(itemCtor, "鎼滅储", SEARCH_URI)?.let { items.add(0, it) }
                    }
                    if (addMessages && items.none { itemHasUri(it, IM_URI) }) {
                        createItem(itemCtor, "娑堟伅", IM_URI)?.let { items.add(0, it) }
                    }
                }
            }.onFailure {
                log("MineProfile more service hook failed at ${buildMethod.declaringClass.name}.${buildMethod.name}", it)
            }
        }
        return 1
    }

    private fun hookVipEntrance(): Int {
        val fragmentClass = resolveHomeUserCenterFragmentClass() ?: return 0
        val vipViewClass = resolveMineVipViewClass() ?: return 0
        val vipField = fragmentClass.declaredFields.firstOrNull { vipViewClass.isAssignableFrom(it.type) }
            ?.apply { isAccessible = true }
            ?: return 0
        val onResume = fragmentClass.declaredMethods.firstOrNull { it.name == "onResume" && it.parameterCount == 0 } ?: return 0

        env.hookBefore(onResume) { param ->
            runCatching {
                if (!ModuleSettings.isMineRemoveVipEnabled(prefs)) return@runCatching
                val fragment = param.thisObject ?: return@runCatching
                val vipView = vipField.get(fragment) as? View ?: return@runCatching
                vipView.visibility = if (ModuleSettings.isMineKeepVipSpaceEnabled(prefs)) View.INVISIBLE else View.GONE
            }.onFailure {
                log("MineProfile vip hook failed at ${onResume.declaringClass.name}.${onResume.name}", it)
            }
        }
        return 1
    }

    private fun resolveHomeUserCenterFragmentClass(): Class<*>? =
        HOME_USER_CENTER_FRAGMENT_CLASSES.firstNotNullOfOrNull { it.from(classLoader) }
            ?: discoverClassesBySimpleName("HomeUserCenterFragment")
                .firstOrNull { candidate ->
                    candidate.declaredMethods.any { method ->
                        method.parameterTypes.any { it == android.content.Context::class.java } &&
                            method.parameterTypes.any { java.util.List::class.java.isAssignableFrom(it) }
                    }
                }

    private fun resolveMineVipViewClass(): Class<*>? =
        MINE_VIP_VIEW_CLASSES.firstNotNullOfOrNull { it.from(classLoader) }
            ?: discoverClassesBySimpleName("MineVipEntranceView").firstOrNull()
            ?: discoverClassesBySimpleName("VipEntranceView").firstOrNull()

    private fun createItem(
        ctor: Constructor<*>,
        title: String,
        uri: String,
    ): Any? {
        return runCatching {
            ctor.newInstance().apply {
                setObjectField("title", title)
                setObjectField("uri", uri)
                setObjectField("f272728uri", uri)
                setIntField("visible", 1)
                setIntField("needLogin", 0)
            }
        }.getOrNull()
    }

    private fun itemHasUri(item: Any?, targetUri: String): Boolean {
        if (item == null) return false
        return item.getObjectField("f272728uri") == targetUri || item.getObjectField("uri") == targetUri
    }

    private fun discoverClassesBySimpleName(simpleName: String): Sequence<Class<*>> {
        val baseLoader = classLoader as? BaseDexClassLoader ?: return emptySequence()
        val pathList = runCatching {
            BaseDexClassLoader::class.java.getDeclaredField("pathList").apply { isAccessible = true }.get(baseLoader)
        }.getOrNull() ?: return emptySequence()
        val dexElements = runCatching {
            pathList.javaClass.getDeclaredField("dexElements").apply { isAccessible = true }.get(pathList) as? Array<*>
        }.getOrNull() ?: return emptySequence()

        return dexElements.asSequence()
            .mapNotNull { element ->
                val dexFile = runCatching {
                    element?.javaClass?.getDeclaredField("dexFile")?.apply { isAccessible = true }?.get(element)
                }.getOrNull() as? DexFile
                dexFile?.entries()?.asSequence()
            }
            .flatten()
            .distinct()
            .filter { name ->
                name.substringAfterLast('.') == simpleName || name.endsWith(".$simpleName")
            }
            .mapNotNull { name -> runCatching { Class.forName(name, false, classLoader) }.getOrNull() }
    }

    private companion object {
        private const val SEARCH_URI = "bilibili://search"
        private const val IM_URI = "activity://link/im-home"

        private val MENU_GROUP_ITEM_CLASSES = arrayOf(
            "com.bilibili.lib.homepage.mine.MenuGroup\$Item",
            "com.bilibili.p5336lib.homepage.p5468mine.MenuGroup\$C87225Item",
        )

        private val HOME_USER_CENTER_FRAGMENT_CLASSES = arrayOf(
            "tv.danmaku.bili.ui.main2.mine.HomeUserCenterFragment",
            "tv.danmaku.p9138bili.p9228ui.main2.p9247mine.HomeUserCenterFragment",
        )

        private val MINE_VIP_VIEW_CLASSES = arrayOf(
            "tv.danmaku.bili.ui.main2.mine.widgets.MineVipEntranceView",
            "tv.danmaku.p9138bili.p9228ui.main2.p9247mine.widgets.MineVipEntranceView",
            "tv.danmaku.p9138bili.p9228ui.main2.p9247mine.modularvip.VipEntranceView",
        )
    }
}

private fun java.util.Enumeration<String>.asSequence(): Sequence<String> = sequence {
    while (hasMoreElements()) {
        yield(nextElement())
    }
}
