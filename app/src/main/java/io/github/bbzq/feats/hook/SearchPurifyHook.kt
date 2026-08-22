package io.github.bbzq.feats.hook

import android.app.Activity
import android.content.res.Resources
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.hookAfterAllMethods
import java.lang.ref.WeakReference
import java.util.ArrayDeque
import java.util.WeakHashMap

/**
 * 搜索界面净化 Hook（三开关独立）
 *
 * 布局结构（从 uiautomator dump 确认）：
 *   content_container
 *     search_discover_list  → 热搜区（标题+榜单）
 *       rank_recycler       → 热搜列表（旧布局的 fallback）
 *     container             → 搜索历史区
 *       historyRv           → 历史列表
 *         tag_name          → 每条历史记录
 *       delete_all           → 删除全部
 *     tag_layout            → 搜索发现/推荐区（懒加载）
 */
class SearchPurifyHook(env: RoamingEnv) : BaseRoamingHook(env) {
    /** Values do not strongly retain either the Activity or its root view. */
    private val layoutRegistrations = WeakHashMap<Activity, LayoutRegistration>()

    override fun startHook() {
        if (env.processName != env.packageName) return

        val hideHot = ModuleSettings.isPurifySearchHotEnabled(prefs)
        val hideRecommend = ModuleSettings.isPurifySearchRecommendEnabled(prefs)
        val hideHistory = ModuleSettings.isPurifySearchHistoryEnabled(prefs)

        if (!hideHot && !hideRecommend && !hideHistory) {
            log("startHook: SearchPurify disabled (all switches off)")
            return
        }

        val searchClass = runCatching {
            classLoader.loadClass(SEARCH_ACTIVITY_CLASS)
        }.getOrNull()
        if (searchClass == null) {
            log("FAILED: class not found")
            return
        }

        env.hookAfterAllMethods(searchClass, "onCreate") { param ->
            val activity = param.thisObject as? Activity ?: return@hookAfterAllMethods
            runCatching {
                installLayoutListener(activity, hideHot, hideRecommend, hideHistory)
            }.onFailure { log("install layout listener failed", it) }
        }
        env.hookAfterAllMethods(searchClass, "onDestroy") { param ->
            val activity = param.thisObject as? Activity ?: return@hookAfterAllMethods
            removeLayoutListener(activity)
        }
        log("installed: hot=$hideHot recommend=$hideRecommend history=$hideHistory")
    }

    private fun installLayoutListener(
        activity: Activity,
        hideHot: Boolean,
        hideRecommend: Boolean,
        hideHistory: Boolean,
    ) {
        val root = activity.window?.decorView ?: return
        synchronized(layoutRegistrations) {
            val current = layoutRegistrations[activity]
            if (current?.isFor(root) == true) return
            current?.remove()

            val registration = LayoutRegistration(
                activity = activity,
                root = root,
                hideHot = hideHot,
                hideRecommend = hideRecommend,
                hideHistory = hideHistory,
            )
            if (registration.install()) {
                layoutRegistrations[activity] = registration
                runCatching {
                    sweep(activity, root, hideHot, hideRecommend, hideHistory)
                }.onFailure { log("initial search sweep failed", it) }
            }
        }
    }

    private fun removeLayoutListener(activity: Activity) {
        val registration = synchronized(layoutRegistrations) {
            layoutRegistrations.remove(activity)
        }
        registration?.remove()
    }

    private fun sweep(
        activity: Activity,
        root: View,
        hideHot: Boolean,
        hideRecommend: Boolean,
        hideHistory: Boolean,
    ) {
        val resources = activity.resources
        val viewsByName = findViewsByResourceName(root, resources)
        val hidden = ArrayList<String>(3)

        if (hideHot) {
            // The complete discover container includes the title and spacing;
            // old layouts without it retain rank_recycler as a safe fallback.
            val hotView = viewsByName[RES_DISCOVER_LIST]
                ?: viewsByName[RES_RANK]
            if (hideIfVisible(hotView)) {
                hidden += if (viewsByName[RES_DISCOVER_LIST] != null) {
                    "hot(search_discover_list)"
                } else {
                    "hot(rank_recycler)"
                }
            }
        }

        if (hideRecommend && hideIfVisible(viewsByName[RES_TAG_LAYOUT])) {
            hidden += "recommend(tag_layout)"
        }

        if (hideHistory) {
            val historyList = viewsByName[RES_HIST_RV]
            val historyContainer = historyList?.parent as? View ?: historyList
            if (hideIfVisible(historyContainer)) hidden += "history"
            if (hideIfVisible(viewsByName[RES_DEL_ALL])) hidden += "delete_all"
        }

        // Layout callbacks are frequent.  Only log actual visibility changes,
        // keeping repeated no-op sweeps quiet while lazy content settles.
        if (hidden.isNotEmpty()) {
            log("updated: ${hidden.joinToString(",")}")
        }
    }

    private fun findViewsByResourceName(root: View, resources: Resources): Map<String, View> {
        val result = HashMap<String, View>()
        val queue = ArrayDeque<View>()
        queue.addLast(root)
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_VIEW_SCAN_NODES) {
            val view = queue.removeFirst()
            visited += 1

            val id = view.id
            if (id != View.NO_ID) {
                runCatching { resources.getResourceEntryName(id) }
                    .getOrNull()
                    ?.let { result.putIfAbsent(it, view) }
            }

            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    queue.addLast(view.getChildAt(index))
                }
            }
        }
        return result
    }

    private fun hideIfVisible(view: View?): Boolean {
        if (view == null || view.visibility == View.GONE) return false
        view.visibility = View.GONE
        return true
    }

    private inner class LayoutRegistration(
        activity: Activity,
        root: View,
        private val hideHot: Boolean,
        private val hideRecommend: Boolean,
        private val hideHistory: Boolean,
    ) {
        private val activityReference = WeakReference(activity)
        private val rootReference = WeakReference(root)
        private var removed = false
        private var hasSwept = false
        private var lastSweepAt = 0L

        private val listener = ViewTreeObserver.OnGlobalLayoutListener {
            if (removed) return@OnGlobalLayoutListener
            val currentActivity = activityReference.get()
            val currentRoot = rootReference.get()
            if (
                currentActivity == null ||
                currentRoot == null ||
                currentActivity.isFinishing ||
                currentActivity.isDestroyed
            ) {
                remove()
                return@OnGlobalLayoutListener
            }

            val now = SystemClock.uptimeMillis()
            if (hasSwept && now >= lastSweepAt && now - lastSweepAt < GLOBAL_LAYOUT_THROTTLE_MS) {
                return@OnGlobalLayoutListener
            }
            hasSwept = true
            lastSweepAt = now
            runCatching {
                sweep(currentActivity, currentRoot, hideHot, hideRecommend, hideHistory)
            }.onFailure { log("layout search sweep failed", it) }
        }

        fun isFor(root: View): Boolean = rootReference.get() === root && !removed

        fun install(): Boolean {
            val observer = rootReference.get()?.viewTreeObserver ?: return false
            if (!observer.isAlive) return false
            observer.addOnGlobalLayoutListener(listener)
            return true
        }

        fun remove() {
            if (removed) return
            removed = true
            runCatching {
                val observer = rootReference.get()?.viewTreeObserver
                if (observer?.isAlive == true) {
                    observer.removeOnGlobalLayoutListener(listener)
                }
            }
        }
    }

    companion object {
        const val SEARCH_ACTIVITY_CLASS = "com.bilibili.search2.main.BiliMainSearchActivity"
        const val RES_DISCOVER_LIST = "search_discover_list"
        const val RES_RANK = "rank_recycler"
        const val RES_TAG_LAYOUT = "tag_layout"
        const val RES_HIST_RV = "historyRv"
        const val RES_DEL_ALL = "delete_all"
        private const val MAX_VIEW_SCAN_NODES = 1500
        private const val GLOBAL_LAYOUT_THROTTLE_MS = 120L
    }
}
