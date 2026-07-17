package io.github.bbzq.feats.hook

import android.app.Activity
import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.hookAfterAllMethods

/**
 * 搜索界面净化 Hook（三开关独立）
 *
 * 布局结构（从 uiautomator dump 确认）：
 *   content_container
 *     search_discover_list  → 热搜区（标题+榜单）
 *       rank_recycler       → 热搜列表
 *     container             → 搜索历史区
 *       historyRv           → 历史列表
 *         tag_name          → 每条历史记录
 *       delete_all          → 删除全部
 *     tag_layout            → 搜索发现/推荐区（懒加载）
 */
class SearchPurifyHook(env: RoamingEnv) : BaseRoamingHook(env) {

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
            val act = param.thisObject as? Activity ?: return@hookAfterAllMethods
            runCatching { schedule(act, hideHot, hideRecommend, hideHistory) }
                .onFailure { log("schedule failed", it) }
        }
        log("installed: hot=$hideHot recommend=$hideRecommend history=$hideHistory")
    }

    private fun schedule(act: Activity, h: Boolean, r: Boolean, y: Boolean) {
        for (d in longArrayOf(500, 1200, 2500, 4000)) {
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching { walk(act, h, r, y) }
            }, d)
        }
    }

    private fun walk(act: Activity, hot: Boolean, rec: Boolean, hist: Boolean) {
        val root = act.window?.decorView ?: return
        val res = act.resources

        var hotV: View? = null
        var histRv: View? = null
        var delAll: View? = null
        var tagLayout: View? = null

        iter(root, res) { v, en ->
            when (en) {
                RES_RANK -> { if (hotV == null) hotV = v }
                RES_TAG_LAYOUT -> { if (tagLayout == null) tagLayout = v }
                RES_HIST_RV -> { if (histRv == null) histRv = v }
                RES_DEL_ALL -> { if (delAll == null) delAll = v }
            }
        }

        val hidden = mutableListOf<String>()

        if (hot && hotV != null) {
            hotV!!.visibility = View.GONE
            hidden.add("hot")
        }

        if (rec && tagLayout != null) {
            tagLayout!!.visibility = View.GONE
            hidden.add("recommend(tag_layout)")
        }

        if (hist && histRv != null) {
            (histRv!!.parent as? View)?.visibility = View.GONE
            delAll?.visibility = View.GONE
            hidden.add("history")
        }

        log("walk: found=[hot=${hotV!=null} tagLayout=${tagLayout!=null} histRv=${histRv!=null}] " +
            "hidden=[${hidden.joinToString(",")}] sw=[h=$hot r=$rec y=$hist]")
    }

    private fun iter(v: View, r: Resources, cb: (View, String) -> Unit) {
        val id = v.id
        if (id != View.NO_ID) {
            try { cb(v, r.getResourceEntryName(id)) } catch (_: Exception) {}
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) iter(v.getChildAt(i), r, cb)
        }
    }

    companion object {
        const val SEARCH_ACTIVITY_CLASS = "com.bilibili.search2.main.BiliMainSearchActivity"
        const val RES_RANK = "rank_recycler"
        const val RES_TAG_LAYOUT = "tag_layout"
        const val RES_HIST_RV = "historyRv"
        const val RES_DEL_ALL = "delete_all"
    }
}