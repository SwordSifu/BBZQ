package io.github.bbzq

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class SettingsContentFactory(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val page: String,
    private val openPage: (String) -> Unit,
) {
    private val tagCheckBoxes = mutableMapOf<String, CheckBox>()
    private val bottomBarItemCheckBoxes = mutableMapOf<String, CheckBox>()
    private val sponsorBlockCategoryCheckBoxes = mutableMapOf<String, CheckBox>()
    private lateinit var disableLongPressCopySwitch: Switch
    private lateinit var enhanceLongPressCopySwitch: Switch
    private lateinit var bottomBarSwitch: Switch
    private lateinit var storyVideoAdSwitch: Switch
    private lateinit var blockedCountView: TextView
    private var lastVersionTapAt = 0L
    private var refreshing = false

    fun createScrollView(): ScrollView {
        val pageRoot = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(PAGE_BACKGROUND)
            setPadding(dp(12), dp(12), dp(12), dp(24))
        }

        when (page) {
            SettingsActivity.PAGE_SKIP_VIDEO_AD_SWITCH -> {
                pageRoot.addView(createSectionLabel("空降助手"))
                pageRoot.addView(createSectionCard(skipVideoAdOverviewRows()))
                pageRoot.addView(createSectionLabel("鸣谢"))
                pageRoot.addView(createSectionCard(skipVideoAdCreditRows()))
            }

            SettingsActivity.PAGE_SKIP_VIDEO_AD_CATEGORY -> {
                pageRoot.addView(createSectionLabel("分类过滤"))
                pageRoot.addView(createSectionCard(skipVideoAdCategoryRows()))
                pageRoot.addView(createSectionLabel("鸣谢"))
                pageRoot.addView(createSectionCard(skipVideoAdCreditRows()))
            }

            else -> {
                pageRoot.addView(createSectionLabel("分享与链接"))
                pageRoot.addView(createSectionCard(shareRows()))

                pageRoot.addView(createSectionLabel("复制增强"))
                pageRoot.addView(createSectionCard(copyRows()))

                pageRoot.addView(createSectionLabel("启动净化"))
                pageRoot.addView(createSectionCard(startupRows()))

                pageRoot.addView(createSectionLabel("首页推荐净化"))
                pageRoot.addView(createSectionCard(homeRecommendRows()))

                pageRoot.addView(createSectionLabel("界面定制"))
                pageRoot.addView(createSectionCard(bottomBarRows()))

                pageRoot.addView(createSectionLabel("播放净化"))
                pageRoot.addView(createSectionCard(playbackRows()))

                pageRoot.addView(createSectionLabel("竖屏视频净化"))
                pageRoot.addView(createSectionCard(storyRows()))

                pageRoot.addView(createSectionLabel("关于"))
                pageRoot.addView(createSectionCard(aboutRows()))
            }
        }

        return ScrollView(context).apply {
            setBackgroundColor(PAGE_BACKGROUND)
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(
                pageRoot,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }.also { refresh() }
    }

    private fun shareRows(): List<View> {
        return listOf(
            createSwitchRow(
                "净化分享",
                "将 b23.tv / bili2233.cn 短链还原为普通链接，并保留必要定位参数。",
                ModuleSettings.KEY_PURIFY_SHARE_ENABLED,
                false,
            ),
            createSwitchRow(
                "普通链接分享",
                "不再以小程序方式分享到 QQ 或微信，同时复制分享链接时尽量转换为 av 号。",
                ModuleSettings.KEY_MINI_PROGRAM_ENABLED,
                false,
            ),
        )
    }

    private fun copyRows(): List<View> {
        return listOf(
            createSwitchRow(
                "去除长按复制",
                "禁用应用内各场景里长按后直接复制到剪贴板的行为，减少误触。",
                ModuleSettings.KEY_DISABLE_LONG_PRESS_COPY_ENABLED,
                false,
            ) {
                disableLongPressCopySwitch = it
            },
            createSwitchRow(
                "长按自由复制",
                "需先开启“去除长按复制”，拦截到复制动作时弹出可自由选择文本的窗口。",
                ModuleSettings.KEY_ENHANCE_LONG_PRESS_COPY_ENABLED,
                false,
            ) {
                enhanceLongPressCopySwitch = it
            },
        )
    }

    private fun startupRows(): List<View> {
        return listOf(
            createSwitchRow(
                "跳过开屏广告",
                "清理启动时的开屏广告响应，减少进入 BBZQ 作用目标时的等待。",
                ModuleSettings.KEY_SKIP_SPLASH_AD_ENABLED,
                true,
            ),
        )
    }

    private fun homeRecommendRows(): List<View> {
        return listOf(
            createSwitchRow(
                "移除首页推荐宽屏广告",
                "过滤首页推荐流中的大横幅和宽屏广告卡片。",
                ModuleSettings.KEY_PURIFY_HOME_RECOMMEND_AD_ENABLED,
                false,
            ),
            createSwitchRow(
                "阻止首页推荐自动刷新",
                "阻止冷启动、长时间后台回到前台或从其他页面返回时自动刷新推荐流，保留手动刷新。",
                ModuleSettings.KEY_BLOCK_HOME_RECOMMEND_AUTO_REFRESH_ENABLED,
                false,
            ),
        )
    }

    private fun bottomBarRows(): List<View> {
        val rows = mutableListOf<View>()
        rows += createSwitchRow(
            "自定义底栏",
            "隐藏不需要的底栏入口；首次使用需重启B站并打开首页后加载底栏数据。",
            ModuleSettings.KEY_CUSTOM_BOTTOM_BAR_ENABLED,
            false,
        ) {
            bottomBarSwitch = it
        }

        val items = bottomBarItems()
        if (items.isEmpty()) {
            rows += createInfoRow(
                "底栏项目",
                "尚未读取到底栏数据。开启后重启B站并打开首页，再回到 BBZQ 设置中选择需要隐藏的项目。",
            )
        } else {
            rows += createInfoRow("底栏项目", "勾选代表保留在底栏；取消勾选后会被隐藏。")
            rows += createBottomBarItemGroup(items)
        }
        return rows
    }

    private fun playbackRows(): List<View> {
        return listOf(
            createInfoRow(
                "空降助手",
                if (ModuleSettings.isSkipVideoAdSettingsVisible(prefs)) {
                    "入口已显示，请到“关于”分组进入空降助手功能开关和分类设定页。"
                } else {
                    "双击“关于”里的版本后，才会显示空降助手的隐藏设定入口。"
                },
            ),
            createSwitchRow(
                "屏蔽视频下方横幅广告",
                "阻止视频详情页播放器下方横幅广告创建。",
                ModuleSettings.KEY_BLOCK_VIDEO_DETAIL_BANNER_AD_ENABLED,
                false,
            ),
            createSwitchRow(
                "跳过视频激励广告",
                "参考 BBZQ 的奖励广告处理逻辑，自动尝试完成视频激励页。",
                ModuleSettings.KEY_SKIP_REWARD_AD_ENABLED,
                false,
            ),
        )
    }

    private fun skipVideoAdOverviewRows(): List<View> {
        return listOf(
            createInfoRow(
                "功能说明",
                "空降助手会依据社区提交的 SponsorBlock 片段，在播放时自动跳过已启用分类对应的片段；功能默认关闭。",
            ),
            createSwitchRow(
                "启用空降助手",
                "默认关闭。开启后进入视频会按已勾选分类加载片段，并在命中时自动跳过。",
                ModuleSettings.KEY_SKIP_VIDEO_AD_ENABLED,
                false,
            ),
            createClickableInfoRow(
                "进入分类设定",
                "选择需要参与自动跳过的 SponsorBlock 分类。",
            ) {
                openPage(SettingsActivity.PAGE_SKIP_VIDEO_AD_CATEGORY)
            },
        )
    }

    private fun skipVideoAdCategoryRows(): List<View> {
        val rows = mutableListOf<View>()
        rows += createInfoRow(
            "分类说明",
            "仅会跳过你勾选的分类；如果主开关关闭，这里的分类会保留，但不会生效。",
        )
        rows += createInfoRow(
            "当前状态",
            if (ModuleSettings.isSkipVideoAdEnabled(prefs)) {
                "空降助手已开启，可按下方分类过滤片段。"
            } else {
                "空降助手当前关闭。你可以先勾好分类，再回到上一页开启主开关。"
            },
        )
        rows += createSponsorBlockCategoryGroup()
        rows += createClickableInfoRow(
            "返回功能开关",
            "回到上一页调整主开关和基础说明。",
        ) {
            openPage(SettingsActivity.PAGE_SKIP_VIDEO_AD_SWITCH)
        }
        return rows
    }

    private fun storyRows(): List<View> {
        val rows = mutableListOf<View>()
        rows += createSwitchRow(
            "净化竖屏视频广告",
            "按标签过滤竖屏视频流中的广告、购物和推广内容。",
            ModuleSettings.KEY_PURIFY_STORY_VIDEO_AD_ENABLED,
            false,
        ) {
            storyVideoAdSwitch = it
        }
        rows += createInfoRow("已选标签", "勾选后会一起参与过滤。")
        rows += createTagGroup()
        rows += createBlockedCountRow()
        return rows
    }

    private fun aboutRows(): List<View> {
        val rows = mutableListOf<View>()
        rows += createClickableInfoRow(
            "版本",
            RuntimeEnvironmentInfo.versionSummary(context, prefs),
        ) {
            handleVersionRowClick()
        }
        rows += createInfoRow(
            "隐藏入口",
            if (ModuleSettings.isSkipVideoAdSettingsVisible(prefs)) {
                "空降助手隐藏入口已显示。"
            } else {
                "双击上方“版本”即可显示空降助手的隐藏设定入口。"
            },
        )
        if (ModuleSettings.isSkipVideoAdSettingsVisible(prefs)) {
            rows += createClickableInfoRow(
                "空降助手功能开关",
                "进入空降助手的主开关与基础说明页。",
            ) {
                openPage(SettingsActivity.PAGE_SKIP_VIDEO_AD_SWITCH)
            }
            rows += createClickableInfoRow(
                "空降助手分类设定",
                "进入 SponsorBlock 分类选择页。",
            ) {
                openPage(SettingsActivity.PAGE_SKIP_VIDEO_AD_CATEGORY)
            }
        }
        return rows
    }

    private fun skipVideoAdCreditRows(): List<View> {
        return listOf(
            createClickableInfoRow(
                "项目鸣谢",
                "空降助手的 API 结构、分类模型与实现思路参考了 hanydd/BilibiliSponsorBlock。点此打开项目主页。",
            ) {
                openUrl("https://github.com/hanydd/BilibiliSponsorBlock")
            },
            createClickableInfoRow(
                "API 文档",
                "点此查看官方 API 说明页。",
            ) {
                openUrl("https://github.com/hanydd/BilibiliSponsorBlock/wiki/API")
            },
        )
    }

    private fun handleVersionRowClick() {
        val now = SystemClock.elapsedRealtime()
        val settingsVisible = ModuleSettings.isSkipVideoAdSettingsVisible(prefs)
        if (!settingsVisible && now - lastVersionTapAt <= DOUBLE_TAP_WINDOW_MS) {
            prefs.edit().putBoolean(ModuleSettings.KEY_SKIP_VIDEO_AD_SETTINGS_VISIBLE, true).apply()
            Toast.makeText(context, "已显示空降助手隐藏入口", Toast.LENGTH_SHORT).show()
            openPage(SettingsActivity.PAGE_ROOT)
            return
        }
        lastVersionTapAt = now
        if (settingsVisible) {
            showRuntimeEnvironmentDialog()
        }
    }

    private fun createSectionLabel(text: String): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#8C8C91"))
            setPadding(dp(4), dp(14), dp(4), dp(8))
        }
    }

    private fun createSectionCard(rows: List<View>): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.WHITE)
            }
            clipToOutline = true
            rows.forEachIndexed { index, row ->
                addView(row)
                if (index != rows.lastIndex) {
                    addView(createDivider())
                }
            }
        }
    }

    private fun createDivider(): View {
        return View(context).apply {
            setBackgroundColor(Color.parseColor("#F1F2F3"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1),
            ).apply {
                marginStart = dp(16)
            }
        }
    }

    private fun createInfoRow(title: String, summary: String): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(TextView(context).apply {
                text = title
                textSize = 15f
                setTextColor(TITLE_COLOR)
            })
            addView(TextView(context).apply {
                text = summary
                textSize = 12f
                setTextColor(SUMMARY_COLOR)
                setPadding(0, dp(4), 0, 0)
            })
        }
    }

    private fun createClickableInfoRow(title: String, summary: String, onClick: () -> Unit): View {
        return createInfoRow(title, summary).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun createBlockedCountRow(): View {
        blockedCountView = TextView(context).apply {
            textSize = 12f
            setTextColor(SUMMARY_COLOR)
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(TextView(context).apply {
                text = "拦截统计"
                textSize = 15f
                setTextColor(TITLE_COLOR)
            })
            addView(blockedCountView.apply {
                setPadding(0, dp(4), 0, 0)
            })
        }
    }

    private fun createTagGroup(): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            ModuleSettings.storyVideoAdTags.forEach { tag ->
                addView(CheckBox(context).apply {
                    text = tag.label
                    textSize = 14f
                    setTextColor(TITLE_COLOR)
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                    setOnCheckedChangeListener { _, _ ->
                        if (!refreshing) saveSelectedTags()
                    }
                    tagCheckBoxes[tag.key] = this
                })
            }
        }
    }

    private fun createSponsorBlockCategoryGroup(): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            ModuleSettings.skipVideoAdCategories.forEach { category ->
                addView(CheckBox(context).apply {
                    text = "${category.label}\n${category.summary}"
                    textSize = 14f
                    setTextColor(TITLE_COLOR)
                    setPadding(dp(6), dp(4), dp(6), dp(4))
                    setOnCheckedChangeListener { _, _ ->
                        if (!refreshing) saveSkipVideoAdCategories()
                    }
                    sponsorBlockCategoryCheckBoxes[category.key] = this
                })
            }
        }
    }

    private fun createBottomBarItemGroup(items: List<BottomBarItem>): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            items.forEach { item ->
                addView(CheckBox(context).apply {
                    text = if (item.uri.isBlank()) item.name else "${item.name}\n${item.uri}"
                    textSize = 14f
                    setTextColor(TITLE_COLOR)
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                    setOnCheckedChangeListener { _, _ ->
                        if (!refreshing) saveHiddenBottomBarItems()
                    }
                    bottomBarItemCheckBoxes[item.id] = this
                })
            }
        }
    }

    private fun createSwitchRow(
        title: String,
        summary: String,
        key: String,
        defaultValue: Boolean,
        onSwitchReady: ((Switch) -> Unit)? = null,
    ): View {
        val switchView = Switch(context).apply {
            isChecked = prefs.getBoolean(key, defaultValue)
            setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
                if (!refreshing) {
                    prefs.edit().putBoolean(key, isChecked).apply()
                    if (key == ModuleSettings.KEY_PURIFY_STORY_VIDEO_AD_ENABLED ||
                        key == ModuleSettings.KEY_DISABLE_LONG_PRESS_COPY_ENABLED ||
                        key == ModuleSettings.KEY_CUSTOM_BOTTOM_BAR_ENABLED ||
                        key == ModuleSettings.KEY_SKIP_VIDEO_AD_ENABLED
                    ) {
                        refresh()
                    }
                }
            }
        }
        onSwitchReady?.invoke(switchView)

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(createTextColumn(title, summary), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(switchView)
        }
    }

    private fun createTextColumn(title: String, summary: String): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(14), 0)
            addView(TextView(context).apply {
                text = title
                textSize = 15f
                setTextColor(TITLE_COLOR)
            })
            addView(TextView(context).apply {
                text = summary
                textSize = 12f
                setTextColor(SUMMARY_COLOR)
                setPadding(0, dp(4), 0, 0)
            })
        }
    }

    private fun refresh() {
        refreshing = true

        val storyEnabled = ModuleSettings.isPurifyStoryVideoAdEnabled(prefs)
        val selectedTags = ModuleSettings.getPurifyStoryVideoAdTags(prefs)
        val copyBaseEnabled = ModuleSettings.isDisableLongPressCopyEnabled(prefs)
        val copyEnhanceEnabled = copyBaseEnabled && ModuleSettings.isEnhanceLongPressCopyEnabled(prefs)
        val bottomBarEnabled = ModuleSettings.isCustomBottomBarEnabled(prefs)
        val hiddenBottomBarItems = ModuleSettings.getHiddenBottomBarItems(prefs)
        val sponsorBlockEnabled = ModuleSettings.isSkipVideoAdEnabled(prefs)
        val sponsorBlockCategories = ModuleSettings.getSkipVideoAdCategories(prefs)

        if (!copyBaseEnabled && prefs.getBoolean(ModuleSettings.KEY_ENHANCE_LONG_PRESS_COPY_ENABLED, false)) {
            prefs.edit().putBoolean(ModuleSettings.KEY_ENHANCE_LONG_PRESS_COPY_ENABLED, false).apply()
        }

        if (::disableLongPressCopySwitch.isInitialized) {
            disableLongPressCopySwitch.isChecked = copyBaseEnabled
        }
        if (::enhanceLongPressCopySwitch.isInitialized) {
            enhanceLongPressCopySwitch.isEnabled = copyBaseEnabled
            enhanceLongPressCopySwitch.isChecked = copyEnhanceEnabled
        }
        if (::bottomBarSwitch.isInitialized) {
            bottomBarSwitch.isChecked = bottomBarEnabled
        }
        bottomBarItemCheckBoxes.forEach { (id, checkBox) ->
            checkBox.isEnabled = bottomBarEnabled
            checkBox.isChecked = id !in hiddenBottomBarItems
        }

        if (::storyVideoAdSwitch.isInitialized) {
            storyVideoAdSwitch.isChecked = storyEnabled
        }
        tagCheckBoxes.forEach { (key, checkBox) ->
            checkBox.isEnabled = storyEnabled
            checkBox.isChecked = key in selectedTags
        }
        sponsorBlockCategoryCheckBoxes.forEach { (key, checkBox) ->
            checkBox.isEnabled = sponsorBlockEnabled
            checkBox.isChecked = key in sponsorBlockCategories
        }

        if (::blockedCountView.isInitialized) {
            blockedCountView.text =
                "累计拦截 ${prefs.getInt(ModuleSettings.KEY_PURIFY_STORY_VIDEO_AD_BLOCKED_COUNT, 0)} 条内容"
        }
        refreshing = false
    }

    private fun saveSelectedTags() {
        prefs.edit()
            .putStringSet(ModuleSettings.KEY_PURIFY_STORY_VIDEO_AD_TAGS, selectedTagKeys().toMutableSet())
            .apply()
    }

    private fun saveHiddenBottomBarItems() {
        prefs.edit()
            .putStringSet(ModuleSettings.KEY_HIDDEN_BOTTOM_BAR_ITEMS, hiddenBottomBarItemIds().toMutableSet())
            .apply()
    }

    private fun saveSkipVideoAdCategories() {
        prefs.edit()
            .putStringSet(ModuleSettings.KEY_SKIP_VIDEO_AD_CATEGORIES, selectedSkipVideoAdCategories().toMutableSet())
            .apply()
    }

    private fun selectedTagKeys(): Set<String> =
        tagCheckBoxes.filterValues { it.isChecked }.keys.toSet()

    private fun hiddenBottomBarItemIds(): Set<String> =
        bottomBarItemCheckBoxes.filterValues { !it.isChecked }.keys.toSet()

    private fun selectedSkipVideoAdCategories(): Set<String> =
        sponsorBlockCategoryCheckBoxes.filterValues { it.isChecked }.keys.toSet()

    private fun showRuntimeEnvironmentDialog() {
        val content = TextView(context).apply {
            text = RuntimeEnvironmentInfo.runtimeEnvironmentJson(context, prefs)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(TITLE_COLOR)
            setTextIsSelectable(true)
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        val scroll = ScrollView(context).apply {
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        AlertDialog.Builder(context)
            .setTitle("runtimeEnvironment")
            .setView(scroll)
            .setPositiveButton("确定", null)
            .show()
    }

    private fun bottomBarItems(): List<BottomBarItem> =
        ModuleSettings.getKnownBottomBarItems(prefs)
            .mapNotNull(::parseBottomBarItem)
            .distinctBy(BottomBarItem::id)
            .sortedBy(BottomBarItem::order)

    private fun parseBottomBarItem(raw: String): BottomBarItem? {
        val parts = raw.split('\t', limit = 4)
        if (parts.size == 4) {
            val order = parts[0].toIntOrNull() ?: return null
            return BottomBarItem(order, parts[1], parts[2], parts[3])
        }
        if (parts.size == 3) {
            return BottomBarItem(Int.MAX_VALUE, parts[0], parts[1], parts[2])
        }
        return null
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure {
                Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show()
            }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private data class BottomBarItem(
        val order: Int,
        val id: String,
        val name: String,
        val uri: String,
    )

    private companion object {
        private val PAGE_BACKGROUND = Color.parseColor("#F6F7F8")
        private val TITLE_COLOR = Color.parseColor("#18191C")
        private val SUMMARY_COLOR = Color.parseColor("#9499A0")
        private const val DOUBLE_TAP_WINDOW_MS = 400L
    }
}
