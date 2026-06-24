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
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import io.github.bbzq.DesktopIconHelper
import io.github.bbzq.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsContentFactory(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val page: String,
    private val openPage: (String) -> Unit,
    private val onExportClick: () -> Unit,
    private val onImportClick: () -> Unit,
) {
    private val tagCheckBoxes = mutableMapOf<String, CheckBox>()
    private val bottomBarItemCheckBoxes = mutableMapOf<String, CheckBox>()
    private val homeRecommendItemCheckBoxes = mutableMapOf<String, CheckBox>()
    private val homeComponentCheckBoxes = mutableMapOf<String, CheckBox>()
    private val sponsorBlockCategoryButtons = mutableMapOf<String, Button>()
    private lateinit var disableLongPressCopySwitch: Switch
    private lateinit var enhanceLongPressCopySwitch: Switch
    private lateinit var downloadThreadSwitch: Switch
    private lateinit var downloadConcurrencyRow: View
    private lateinit var downloadConcurrencySummary: TextView
    private lateinit var bottomBarSwitch: Switch
    private lateinit var homeRecommendItemSwitch: Switch
    private lateinit var homeRecommendTitleKeywordSummaryView: TextView
    private lateinit var hideAllHomeComponentsSwitch: Switch
    private lateinit var customHomeComponentHideSwitch: Switch
    private lateinit var storyVideoAdSwitch: Switch
    private lateinit var skipVideoAdAutoLikeSwitch: Switch
    private lateinit var blockedCountView: TextView
    private lateinit var symbolScanStatusSummary: TextView
    private var versionTapCount = 0
    private var firstVersionTapAt = 0L
    private var refreshing = false

    fun createScrollView(): ScrollView {
        val pageRoot = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(PAGE_BACKGROUND)
            setPadding(dp(12), dp(12), dp(12), dp(24))
        }

        when (page) {
            SettingsActivity.PAGE_SKIP_VIDEO_AD_SWITCH -> {
                pageRoot.addView(createSectionLabel(context.getString(R.string.section_skip_video_ad)))
                pageRoot.addView(createSectionCard(skipVideoAdOverviewRows()))
                pageRoot.addView(createSectionLabel(context.getString(R.string.section_thanks)))
                pageRoot.addView(createSectionCard(skipVideoAdCreditRows()))
            }

            SettingsActivity.PAGE_SKIP_VIDEO_AD_CATEGORY -> {
                pageRoot.addView(createSectionLabel(context.getString(R.string.section_category_filter)))
                pageRoot.addView(createSectionCard(skipVideoAdCategoryRows()))
                pageRoot.addView(createSectionLabel(context.getString(R.string.section_thanks)))
                pageRoot.addView(createSectionCard(skipVideoAdCreditRows()))
            }

            SettingsActivity.PAGE_HIDDEN_FEATURES -> {
                pageRoot.addView(createSectionLabel(context.getString(R.string.about_hidden_features_title)))
                pageRoot.addView(createSectionCard(hiddenFeaturesRows()))
            }

            else -> {
                pageRoot.addView(createSectionLabel(context.getString(R.string.section_share_link)))
                pageRoot.addView(createSectionCard(shareRows()))

                pageRoot.addView(createSectionLabel(context.getString(R.string.section_copy_enhance)))
                pageRoot.addView(createSectionCard(copyRows()))

                pageRoot.addView(createSectionLabel(context.getString(R.string.section_startup_purify)))
                pageRoot.addView(createSectionCard(startupRows()))

                pageRoot.addView(createSectionLabel(context.getString(R.string.section_download_features)))
                pageRoot.addView(createSectionCard(downloadRows()))

                pageRoot.addView(createSectionLabel(context.getString(R.string.section_home_recommend_purify)))
                pageRoot.addView(createSectionCard(homeRecommendRows()))

                pageRoot.addView(createSectionLabel(context.getString(R.string.section_ui_customize)))
                pageRoot.addView(createSectionCard(bottomBarRows()))

                pageRoot.addView(createSectionLabel(context.getString(R.string.section_playback_purify)))
                pageRoot.addView(createSectionCard(playbackRows()))

                pageRoot.addView(createSectionLabel(context.getString(R.string.section_comment_purify)))
                pageRoot.addView(createSectionCard(commentRows()))

                pageRoot.addView(createSectionLabel(context.getString(R.string.section_mine_customize)))
                pageRoot.addView(createSectionCard(mineProfileRows()))

                pageRoot.addView(createSectionLabel(context.getString(R.string.section_story_purify)))
                pageRoot.addView(createSectionCard(storyRows()))

                if (hasHiddenFeatures()) {
                    pageRoot.addView(createSectionLabel(context.getString(R.string.about_hidden_features_title)))
                    pageRoot.addView(createSectionCard(hiddenFeaturesEntryRows()))
                }

                pageRoot.addView(createSectionLabel(context.getString(R.string.section_about)))
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
                context.getString(R.string.share_purify_title),
                context.getString(R.string.share_purify_summary),
                ModuleSettings.KEY_PURIFY_SHARE_ENABLED,
                false,
            ),
            createSwitchRow(
                context.getString(R.string.share_mini_program_title),
                context.getString(R.string.share_mini_program_summary),
                ModuleSettings.KEY_MINI_PROGRAM_ENABLED,
                false,
            ),
        )
    }

    private fun copyRows(): List<View> {
        return listOf(
            createSwitchRow(
                context.getString(R.string.copy_disable_title),
                context.getString(R.string.copy_disable_summary),
                ModuleSettings.KEY_DISABLE_LONG_PRESS_COPY_ENABLED,
                false,
            ) {
                disableLongPressCopySwitch = it
            },
            createSwitchRow(
                context.getString(R.string.copy_enhance_title),
                context.getString(R.string.copy_enhance_summary),
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
                context.getString(R.string.startup_skip_splash_title),
                context.getString(R.string.startup_skip_splash_summary),
                ModuleSettings.KEY_SKIP_SPLASH_AD_ENABLED,
                true,
            ),
            createSwitchRow(
                context.getString(R.string.startup_block_teenagers_title),
                context.getString(R.string.startup_block_teenagers_summary),
                ModuleSettings.KEY_BLOCK_TEENAGERS_MODE_DIALOG_ENABLED,
                false,
            ),
            createSwitchRow(
                context.getString(R.string.block_update_title),
                context.getString(R.string.block_update_summary),
                ModuleSettings.KEY_BLOCK_UPDATE_ENABLED,
                false,
            ),
        )
    }

    private fun downloadRows(): List<View> {
        val rows = mutableListOf<View>()
        rows += createSwitchRow(
                context.getString(R.string.custom_download_thread_title),
                context.getString(R.string.custom_download_thread_summary),
                ModuleSettings.KEY_CUSTOM_DOWNLOAD_THREAD_ENABLED,
                false,
            ) {
                downloadThreadSwitch = it
            }
        rows += createClickableInfoRow(
            context.getString(R.string.custom_download_concurrency_title),
            context.getString(
                R.string.custom_download_concurrency_summary,
                ModuleSettings.getCustomDownloadConcurrency(prefs),
            ),
        ) {
            showCustomDownloadConcurrencyDialog()
        }.also {
            downloadConcurrencyRow = it
            downloadConcurrencySummary = (it as ViewGroup).getChildAt(1) as TextView
        }
        return rows
    }

    private fun homeRecommendRows(): List<View> {
        val rows = mutableListOf<View>()
        rows += createSwitchRow(
            context.getString(R.string.home_recommend_ad_title),
            context.getString(R.string.home_recommend_ad_summary),
            ModuleSettings.KEY_PURIFY_HOME_RECOMMEND_AD_ENABLED,
            false,
        )
        rows += createSwitchRow(
            context.getString(R.string.home_recommend_picture_title),
            context.getString(R.string.home_recommend_picture_summary),
            ModuleSettings.KEY_PURIFY_HOME_RECOMMEND_PICTURE_ENABLED,
            false,
        )
        rows += createSwitchRow(
            context.getString(R.string.home_recommend_game_promo_title),
            context.getString(R.string.home_recommend_game_promo_summary),
            ModuleSettings.KEY_PURIFY_HOME_RECOMMEND_GAME_PROMO_ENABLED,
            false,
        )
        rows += createHomeRecommendTitleKeywordRow()
        rows += createSwitchRow(
            context.getString(R.string.home_recommend_vertical_av_detail_title),
            context.getString(R.string.home_recommend_vertical_av_detail_summary),
            ModuleSettings.KEY_HOME_RECOMMEND_VERTICAL_AV_DETAIL_ENABLED,
            false,
        )
        rows += createSwitchRow(
            context.getString(R.string.home_recommend_auto_refresh_title),
            context.getString(R.string.home_recommend_auto_refresh_summary),
            ModuleSettings.KEY_BLOCK_HOME_RECOMMEND_AUTO_REFRESH_ENABLED,
            false,
        )
        rows += createSwitchRow(
            context.getString(R.string.home_recommend_custom_filter_title),
            context.getString(R.string.home_recommend_custom_filter_summary),
            ModuleSettings.KEY_CUSTOM_HOME_RECOMMEND_FILTER_ENABLED,
            false,
        ) {
            homeRecommendItemSwitch = it
        }

        val recommendItems = homeRecommendItems()
        if (recommendItems.isEmpty()) {
            rows += createInfoRow(
                context.getString(R.string.home_recommend_custom_filter_item_title),
                context.getString(R.string.home_recommend_custom_filter_unavailable_summary),
            )
        } else {
            rows += createInfoRow(
                context.getString(R.string.home_recommend_custom_filter_item_title),
                context.getString(R.string.home_recommend_custom_filter_info_summary),
            )
            rows += createHomeRecommendItemGroup(recommendItems)
        }
        rows += createSwitchRow(
            context.getString(R.string.home_recommend_hide_all_title),
            context.getString(R.string.home_recommend_hide_all_summary),
            ModuleSettings.KEY_HIDE_ALL_HOME_COMPONENTS_ENABLED,
            false,
        ) {
            hideAllHomeComponentsSwitch = it
        }
        rows += createSwitchRow(
            context.getString(R.string.home_recommend_custom_hide_title),
            context.getString(R.string.home_recommend_custom_hide_summary),
            ModuleSettings.KEY_CUSTOM_HOME_COMPONENT_HIDE_ENABLED,
            false,
        ) {
            customHomeComponentHideSwitch = it
        }

        val components = homeComponentItems()
        if (components.isEmpty()) {
            rows += createInfoRow(
                context.getString(R.string.home_component_title),
                context.getString(R.string.home_component_unavailable_summary),
            )
        } else {
            rows += createInfoRow(
                context.getString(R.string.home_component_title),
                context.getString(R.string.home_component_info_summary),
            )
            rows += createHomeComponentGroup(components)
        }
        return rows
    }

    private fun bottomBarRows(): List<View> {
        val rows = mutableListOf<View>()
        rows += createSwitchRow(
            context.getString(R.string.bottom_bar_title),
            context.getString(R.string.bottom_bar_summary),
            ModuleSettings.KEY_CUSTOM_BOTTOM_BAR_ENABLED,
            false,
        ) {
            bottomBarSwitch = it
        }

        val items = bottomBarItems()
        if (items.isEmpty()) {
            rows += createInfoRow(
                context.getString(R.string.bottom_bar_item_title),
                context.getString(R.string.bottom_bar_unavailable_summary),
            )
        } else {
            rows += createInfoRow(
                context.getString(R.string.bottom_bar_item_title),
                context.getString(R.string.bottom_bar_info_summary),
            )
            rows += createBottomBarItemGroup(items)
        }
        rows += createSwitchRow(
            context.getString(R.string.home_top_bar_promotion_title),
            context.getString(R.string.home_top_bar_promotion_summary),
            ModuleSettings.KEY_HIDE_HOME_TOP_BAR_PROMOTION_ENABLED,
            false,
        )
        rows += createSwitchRow(
            context.getString(R.string.home_search_default_word_title),
            context.getString(R.string.home_search_default_word_summary),
            ModuleSettings.KEY_HIDE_HOME_SEARCH_DEFAULT_WORD_ENABLED,
            false,
        )
        return rows
    }

    private fun playbackRows(): List<View> {
        val rows = mutableListOf<View>()
        if (ModuleSettings.isSkipVideoAdSettingsVisible(prefs)) {
            rows += createInfoRow(
                context.getString(R.string.section_skip_video_ad),
                context.getString(R.string.skip_video_ad_entry_shown_summary),
            )
        }
        rows += createSwitchRow(
            context.getString(R.string.playback_hide_banner_title),
            context.getString(R.string.playback_hide_banner_summary),
            ModuleSettings.KEY_BLOCK_VIDEO_DETAIL_BANNER_AD_ENABLED,
            false,
        )
        rows += createSwitchRow(
            context.getString(R.string.playback_block_chronos_promotion_title),
            context.getString(R.string.playback_block_chronos_promotion_summary),
            ModuleSettings.KEY_BLOCK_CHRONOS_PROMOTION_ENABLED,
            false,
        )
        rows += createSwitchRow(
            context.getString(R.string.playback_skip_reward_title),
            context.getString(R.string.playback_skip_reward_summary),
            ModuleSettings.KEY_SKIP_REWARD_AD_ENABLED,
            false,
        )
        rows += createSwitchRow(
            context.getString(R.string.playback_auto_like_title),
            context.getString(R.string.playback_auto_like_summary),
            ModuleSettings.KEY_AUTO_LIKE_VIDEO_DETAIL_ENABLED,
            false,
        )
        return rows
    }

    private fun commentRows(): List<View> {
        return listOf(
            createSwitchRow(
                context.getString(R.string.comment_disable_title),
                context.getString(R.string.comment_disable_summary),
                ModuleSettings.KEY_COMMENT_DISABLE,
                false,
            ),
            createSwitchRow(
                context.getString(R.string.comment_no_quick_reply_title),
                context.getString(R.string.comment_no_quick_reply_summary),
                ModuleSettings.KEY_COMMENT_NO_QUICK_REPLY,
                false,
            ),
            createSwitchRow(
                context.getString(R.string.comment_no_vote_title),
                context.getString(R.string.comment_no_vote_summary),
                ModuleSettings.KEY_COMMENT_NO_VOTE,
                false,
            ),
            createSwitchRow(
                context.getString(R.string.comment_no_follow_title),
                context.getString(R.string.comment_no_follow_summary),
                ModuleSettings.KEY_COMMENT_NO_FOLLOW,
                false,
            ),
            createSwitchRow(
                context.getString(R.string.comment_no_search_title),
                context.getString(R.string.comment_no_search_summary),
                ModuleSettings.KEY_COMMENT_NO_SEARCH,
                false,
            ),
            createSwitchRow(
                context.getString(R.string.comment_no_empty_page_title),
                context.getString(R.string.comment_no_empty_page_summary),
                ModuleSettings.KEY_COMMENT_NO_EMPTY_PAGE,
                false,
            ),
            createSwitchRow(
                context.getString(R.string.comment_no_qoe_title),
                context.getString(R.string.comment_no_qoe_summary),
                ModuleSettings.KEY_COMMENT_NO_QOE,
                false,
            ),
            createSwitchRow(
                context.getString(R.string.comment_no_operation_title),
                context.getString(R.string.comment_no_operation_summary),
                ModuleSettings.KEY_COMMENT_NO_OPERATION,
                false,
            ),
            createSwitchRow(
                context.getString(R.string.comment_picture_view_title),
                context.getString(R.string.comment_picture_view_summary),
                ModuleSettings.KEY_COMMENT_PICTURE_VIEW,
                false,
            ),
        )
    }

    private fun mineProfileRows(): List<View> {
        return listOf(
            createSwitchRow(
                context.getString(R.string.mine_remove_vip_title),
                context.getString(R.string.mine_remove_vip_summary),
                ModuleSettings.KEY_MINE_REMOVE_VIP,
                false,
            ),
            createSwitchRow(
                context.getString(R.string.mine_keep_vip_space_title),
                context.getString(R.string.mine_keep_vip_space_summary),
                ModuleSettings.KEY_MINE_KEEP_VIP_SPACE,
                false,
            ),
            createSwitchRow(
                context.getString(R.string.full_number_format_title),
                context.getString(R.string.full_number_format_summary),
                ModuleSettings.KEY_FULL_NUMBER_FORMAT_ENABLED,
                false,
            ),
        )
    }

    private fun skipVideoAdOverviewRows(): List<View> {
        return listOf(
            createInfoRow(
                context.getString(R.string.skip_video_ad_function_title),
                context.getString(R.string.skip_video_ad_function_summary),
            ),
            createSwitchRow(
                context.getString(R.string.skip_video_ad_enable_title),
                context.getString(R.string.skip_video_ad_enable_summary),
                ModuleSettings.KEY_SKIP_VIDEO_AD_ENABLED,
                false,
            ),
            createSwitchRow(
                context.getString(R.string.skip_video_ad_auto_like_title),
                context.getString(R.string.skip_video_ad_auto_like_summary),
                ModuleSettings.KEY_SKIP_VIDEO_AD_AUTO_LIKE_ENABLED,
                false,
            ) {
                skipVideoAdAutoLikeSwitch = it
            },
            createClickableInfoRow(
                context.getString(R.string.skip_video_ad_category_entry_title),
                context.getString(R.string.skip_video_ad_category_entry_summary),
            ) {
                openPage(SettingsActivity.PAGE_SKIP_VIDEO_AD_CATEGORY)
            },
        )
    }

    private fun skipVideoAdCategoryRows(): List<View> {
        val rows = mutableListOf<View>()
        rows += createInfoRow(
            context.getString(R.string.skip_video_ad_category_description_title),
            context.getString(R.string.skip_video_ad_category_description_summary),
        )
        rows += createInfoRow(
            context.getString(R.string.skip_video_ad_category_state_title),
            if (ModuleSettings.isSkipVideoAdEnabled(prefs)) {
                context.getString(R.string.skip_video_ad_category_state_on)
            } else {
                context.getString(R.string.skip_video_ad_category_state_off)
            },
        )
        rows += createSponsorBlockCategoryGroup()
        rows += createClickableInfoRow(
            context.getString(R.string.skip_video_ad_category_back_title),
            context.getString(R.string.skip_video_ad_category_back_summary),
        ) {
            openPage(SettingsActivity.PAGE_SKIP_VIDEO_AD_SWITCH)
        }
        return rows
    }

    private fun storyRows(): List<View> {
        val rows = mutableListOf<View>()
        rows += createSwitchRow(
            context.getString(R.string.purify_story_video_ad_title),
            context.getString(R.string.purify_story_video_ad_summary),
            ModuleSettings.KEY_PURIFY_STORY_VIDEO_AD_ENABLED,
            false,
        ) {
            storyVideoAdSwitch = it
        }
        rows += createInfoRow(
            context.getString(R.string.story_filter_selected_tags_title),
            context.getString(R.string.story_filter_selected_tags_summary),
        )
        rows += createTagGroup()
        rows += createBlockedCountRow()
        return rows
    }

    private fun aboutRows(): List<View> {
        val rows = mutableListOf<View>()
        rows += createSwitchRow(
            context.getString(R.string.about_hide_desktop_icon_title),
            context.getString(R.string.about_hide_desktop_icon_summary),
            ModuleSettings.KEY_HIDE_DESKTOP_ICON,
            false,
        )
        rows += createClickableInfoRow(
            context.getString(R.string.about_version_title),
            RuntimeEnvironmentInfo.versionSummary(context, prefs),
        ) {
            handleVersionRowClick()
        }
        rows += createClickableInfoRow(
            context.getString(R.string.about_export_config_title),
            context.getString(R.string.about_export_config_summary),
        ) {
            onExportClick()
        }
        rows += createClickableInfoRow(
            context.getString(R.string.about_import_config_title),
            context.getString(R.string.about_import_config_summary),
        ) {
            onImportClick()
        }
        rows += createSymbolCacheRefreshRow()
        return rows
    }

    private fun hiddenFeaturesEntryRows(): List<View> {
        return listOf(
            createClickableInfoRow(
                context.getString(R.string.hidden_features_entry_title),
                "",
            ) {
                openPage(SettingsActivity.PAGE_HIDDEN_FEATURES)
            },
        )
    }

    private fun hiddenFeaturesRows(): List<View> {
        val rows = mutableListOf<View>()
        val skipVisible = ModuleSettings.isSkipVideoAdSettingsVisible(prefs)
        val accessKeyVisible = ModuleSettings.isAccessKeySettingsVisible(prefs)
        val tryFreeQualityVisible = ModuleSettings.isTryFreeQualitySettingsVisible(prefs)
        if (skipVisible) {
            rows += createClickableInfoRow(
                context.getString(R.string.about_skip_video_ad_switch_title),
                context.getString(R.string.about_skip_video_ad_switch_summary),
            ) {
                openPage(SettingsActivity.PAGE_SKIP_VIDEO_AD_SWITCH)
            }
            rows += createClickableInfoRow(
                context.getString(R.string.about_skip_video_ad_category_title),
                context.getString(R.string.about_skip_video_ad_category_summary),
            ) {
                openPage(SettingsActivity.PAGE_SKIP_VIDEO_AD_CATEGORY)
            }
        }
        if (accessKeyVisible) {
            rows += createClickableInfoRow(
                context.getString(R.string.about_access_key_title),
                context.getString(R.string.about_access_key_summary),
            ) {
                handleAccessKeyClick()
            }
        }
        if (tryFreeQualityVisible) {
            rows += createSwitchRow(
                context.getString(R.string.unlock_video_features_title),
                context.getString(R.string.unlock_video_features_summary),
                ModuleSettings.KEY_UNLOCK_VIDEO_FEATURES_ENABLED,
                false,
            )
        }
        return rows
    }

    private fun skipVideoAdCreditRows(): List<View> {
        return listOf(
            createClickableInfoRow(
                context.getString(R.string.credits_title),
                context.getString(R.string.credits_summary),
            ) {
                openUrl("https://github.com/hanydd/BilibiliSponsorBlock")
            },
            createClickableInfoRow(
                context.getString(R.string.api_docs_title),
                context.getString(R.string.api_docs_summary),
            ) {
                openUrl("https://github.com/hanydd/BilibiliSponsorBlock/wiki/API")
            },
        )
    }

    private fun handleVersionRowClick() {
        val now = SystemClock.elapsedRealtime()
        val skipVisible = ModuleSettings.isSkipVideoAdSettingsVisible(prefs)
        val accessKeyVisible = ModuleSettings.isAccessKeySettingsVisible(prefs)
        val tryFreeQualityVisible = ModuleSettings.isTryFreeQualitySettingsVisible(prefs)

        if (now - firstVersionTapAt > VERSION_TAP_WINDOW_MS) {
            versionTapCount = 0
            firstVersionTapAt = now
        }

        versionTapCount += 1

        if (!(skipVisible && accessKeyVisible && tryFreeQualityVisible) && versionTapCount >= 4) {
            prefs.edit().apply {
                if (!skipVisible) putBoolean(ModuleSettings.KEY_SKIP_VIDEO_AD_SETTINGS_VISIBLE, true)
                if (!accessKeyVisible) putBoolean(ModuleSettings.KEY_ACCESS_KEY_SETTINGS_VISIBLE, true)
                if (!tryFreeQualityVisible) putBoolean(ModuleSettings.KEY_TRY_FREE_QUALITY_SETTINGS_VISIBLE, true)
            }.apply()
            Toast.makeText(context, context.getString(R.string.version_hidden_entry_toast), Toast.LENGTH_SHORT).show()
            versionTapCount = 0
            firstVersionTapAt = 0L
            openPage(SettingsActivity.PAGE_ROOT)
            return
        }

        if (skipVisible || accessKeyVisible || tryFreeQualityVisible) {
            showRuntimeEnvironmentDialog()
        }
    }

    private fun hasHiddenFeatures(): Boolean =
        ModuleSettings.isSkipVideoAdSettingsVisible(prefs) ||
            ModuleSettings.isAccessKeySettingsVisible(prefs) ||
            ModuleSettings.isTryFreeQualitySettingsVisible(prefs)

    private fun handleAccessKeyClick() {
        val key = AccessKeyRepository.read(prefs)
        if (key == null) {
            AlertDialog.Builder(context)
                .setTitle(R.string.access_key_error_title)
                .setMessage(R.string.access_key_error_message)
                .setPositiveButton(R.string.runtime_environment_ok, null)
                .show()
            return
        }

        val content = TextView(context).apply {
            text = key
            textSize = 16f
            typeface = Typeface.MONOSPACE
            setTextColor(TITLE_COLOR)
            setTextIsSelectable(true)
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(24), dp(18), dp(24))
        }

        AlertDialog.Builder(context)
            .setTitle(R.string.access_key_title)
            .setView(content)
            .setPositiveButton(R.string.access_key_copy_button) { _, _ ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("AccessKey", key)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, context.getString(R.string.access_key_copied_toast), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.runtime_environment_ok, null)
            .show()
    }

    private fun createSymbolCacheRefreshRow(): View {
        symbolScanStatusSummary = TextView(context).apply {
            text = symbolScanSummary()
            textSize = 12f
            setTextColor(SUMMARY_COLOR)
            setPadding(0, dp(4), 0, 0)
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            isFocusable = true
            setOnClickListener { handleSymbolCacheRefreshClick() }
            addView(TextView(context).apply {
                text = context.getString(R.string.symbol_cache_refresh_title)
                textSize = 15f
                setTextColor(TITLE_COLOR)
            })
            addView(symbolScanStatusSummary)
        }
    }

    private fun handleSymbolCacheRefreshClick() {
        val report = symbolScanReport()
        AlertDialog.Builder(context)
            .setTitle(R.string.symbol_cache_refresh_dialog_title)
            .setMessage(context.getString(R.string.symbol_cache_refresh_dialog_message, report))
            .setNegativeButton(R.string.skip_mode_cancel, null)
            .setPositiveButton(R.string.symbol_cache_refresh_confirm) { _, _ ->
                startSymbolCacheRefresh()
            }
            .show()
    }

    private fun startSymbolCacheRefresh() {
        val progress = AlertDialog.Builder(context)
            .setTitle(R.string.symbol_cache_refresh_running_title)
            .setMessage(R.string.symbol_cache_refresh_running_message)
            .setCancelable(false)
            .show()
        ModuleRemotePreferences.requestSymbolCacheRefresh { result ->
            progress.dismiss()
            Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
            if (::symbolScanStatusSummary.isInitialized) {
                symbolScanStatusSummary.text = symbolScanSummary()
            }
            if (!result.success && result.targetResults.isNotEmpty()) {
                AlertDialog.Builder(context)
                    .setTitle(R.string.symbol_cache_refresh_failed_title)
                    .setMessage(result.targetResults.joinToString("\n") { "${it.processName}: ${it.detail}" })
                    .setPositiveButton(R.string.runtime_environment_ok, null)
                    .show()
            }
        }
    }

    private fun symbolScanSummary(): String {
        val summary = prefs.getString(ModuleSettings.KEY_SYMBOL_SCAN_STATUS_SUMMARY, null)
            ?: context.getString(R.string.symbol_cache_refresh_no_status)
        val updatedAt = prefs.getString(ModuleSettings.KEY_SYMBOL_SCAN_STATUS_UPDATED_AT, null)
            ?.toLongOrNull()
            ?.let(::formatTime)
        return if (updatedAt == null) {
            summary
        } else {
            context.getString(R.string.symbol_cache_refresh_summary_with_time, summary, updatedAt)
        }
    }

    private fun symbolScanReport(): String =
        prefs.getString(ModuleSettings.KEY_SYMBOL_SCAN_STATUS_REPORT, null)
            ?: context.getString(R.string.symbol_cache_refresh_no_report)

    private fun formatTime(value: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(value))

    private fun showCustomDownloadConcurrencyDialog() {
        val picker = NumberPicker(context).apply {
            minValue = 1
            maxValue = 12
            wrapSelectorWheel = false
            value = ModuleSettings.getCustomDownloadConcurrency(prefs)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.custom_download_concurrency_dialog_title)
            .setView(picker)
            .setNegativeButton(R.string.skip_mode_cancel, null)
            .setPositiveButton(R.string.skip_mode_confirm) { _, _ ->
                prefs.edit()
                    .putInt(ModuleSettings.KEY_CUSTOM_DOWNLOAD_CONCURRENCY, picker.value)
                    .apply()
                refresh()
            }
            .show()
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
            if (summary.isNotBlank()) {
                addView(TextView(context).apply {
                    text = summary
                    textSize = 12f
                    setTextColor(SUMMARY_COLOR)
                    setPadding(0, dp(4), 0, 0)
                })
            }
        }
    }

    private fun createClickableInfoRow(title: String, summary: String, onClick: () -> Unit): View {
        return createInfoRow(title, summary).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun createHomeRecommendTitleKeywordRow(): View {
        homeRecommendTitleKeywordSummaryView = TextView(context).apply {
            textSize = 12f
            setTextColor(SUMMARY_COLOR)
            setPadding(0, dp(4), 0, 0)
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            isFocusable = true
            setOnClickListener { showHomeRecommendTitleKeywordDialog() }
            addView(TextView(context).apply {
                text = context.getString(R.string.home_recommend_title_keyword_title)
                textSize = 15f
                setTextColor(TITLE_COLOR)
            })
            addView(homeRecommendTitleKeywordSummaryView)
        }
    }

    private fun showHomeRecommendTitleKeywordDialog() {
        val input = EditText(context).apply {
            setText(ModuleSettings.getHomeRecommendTitleKeywordsText(prefs))
            minLines = 4
            maxLines = 8
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(false)
            setSelectAllOnFocus(false)
            setHint(R.string.home_recommend_title_keyword_hint)
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.home_recommend_title_keyword_dialog_title)
            .setView(content)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                prefs.edit()
                    .putString(ModuleSettings.KEY_HOME_RECOMMEND_TITLE_KEYWORDS, input.text?.toString()?.trim().orEmpty())
                    .apply()
                refresh()
            }
            .show()
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
                text = context.getString(R.string.story_filter_blocked_count_title)
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
                addView(createSkipModeRow(category))
            }
        }
    }

    private fun createSkipModeRow(category: SponsorBlockCategory): View {
        val button = Button(context).apply {
            text = ModuleSettings.getSkipVideoAdMode(prefs, category.key).label
            textSize = 12f
            setOnClickListener { showSkipModeDialog(category) }
            sponsorBlockCategoryButtons[category.key] = this
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(
                createTextColumn(category.label, category.summary),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(createCategoryColorLegend(category))
            addView(button)
        }
    }

    private fun showSkipModeDialog(category: SponsorBlockCategory) {
        val currentMode = ModuleSettings.getSkipVideoAdMode(prefs, category.key)
        val modes = SkipVideoAdMode.entries.toTypedArray()
        val labels = modes.map { it.label }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.skip_mode_dialog_title, category.label))
            .setSingleChoiceItems(labels, currentMode.ordinal) { dialog, which ->
                val selectedMode = modes[which]
                prefs.edit()
                    .putInt(
                        "${ModuleSettings.KEY_SKIP_VIDEO_AD_MODE_PREFIX}${category.key}",
                        selectedMode.value,
                    )
                    .apply()
                refresh()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.skip_mode_cancel, null)
            .show()
    }

    private fun createCategoryColorLegend(category: SponsorBlockCategory): View {
        fun swatch(color: Int): View =
            View(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(4).toFloat()
                    setColor(color)
                }
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply {
                    marginEnd = dp(6)
                }
            }

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(10), 0)
            addView(swatch(category.color))
            addView(swatch(category.previewColor))
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

    private fun createHomeRecommendItemGroup(items: List<HomeRecommendItem>): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            items.forEach { item ->
                addView(CheckBox(context).apply {
                    text = if (item.bizType.isBlank()) {
                        "${item.key}\n${item.className}"
                    } else {
                        "${item.key}\n${item.bizType}\n${item.className}"
                    }
                    textSize = 14f
                    setTextColor(TITLE_COLOR)
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                    setOnCheckedChangeListener { _, _ ->
                        if (!refreshing) saveHiddenHomeRecommendItems()
                    }
                    homeRecommendItemCheckBoxes[item.key] = this
                })
            }
        }
    }

    private fun createHomeComponentGroup(items: List<HomeComponentItem>): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            items.forEach { item ->
                addView(CheckBox(context).apply {
                    text = "${item.name}\n${item.className}"
                    textSize = 14f
                    setTextColor(TITLE_COLOR)
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                    setOnCheckedChangeListener { _, _ ->
                        if (!refreshing) saveHiddenHomeComponents()
                    }
                    homeComponentCheckBoxes[item.className] = this
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
                    handleSwitchChanged(key, isChecked)
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

    private fun handleSwitchChanged(key: String, isChecked: Boolean) {
        val editor = prefs.edit().putBoolean(key, isChecked)
        if (key == ModuleSettings.KEY_SKIP_VIDEO_AD_ENABLED && isChecked) {
            editor.putBoolean(ModuleSettings.KEY_SKIP_VIDEO_AD_AUTO_LIKE_ENABLED, true)
        }
        editor.apply()

        if (isSkipVideoAdSwitchKey(key)) {
            ModuleSettings.refreshSkipVideoAdCache(prefs)
        }
        if (key == ModuleSettings.KEY_SKIP_VIDEO_AD_AUTO_LIKE_ENABLED && !isChecked) {
            showSkipVideoAdAutoLikeDisableDialog()
        }
        if (shouldRefreshAfterSwitchChanged(key)) {
            refresh()
        }
        if (key == ModuleSettings.KEY_HIDE_DESKTOP_ICON) {
            applyDesktopIconSetting(isChecked)
        }
    }

    private fun isSkipVideoAdSwitchKey(key: String): Boolean =
        key == ModuleSettings.KEY_SKIP_VIDEO_AD_ENABLED ||
            key == ModuleSettings.KEY_SKIP_VIDEO_AD_AUTO_LIKE_ENABLED

    private fun shouldRefreshAfterSwitchChanged(key: String): Boolean =
        key == ModuleSettings.KEY_PURIFY_STORY_VIDEO_AD_ENABLED ||
            key == ModuleSettings.KEY_DISABLE_LONG_PRESS_COPY_ENABLED ||
            key == ModuleSettings.KEY_CUSTOM_BOTTOM_BAR_ENABLED ||
            key == ModuleSettings.KEY_CUSTOM_HOME_RECOMMEND_FILTER_ENABLED ||
            key == ModuleSettings.KEY_SKIP_VIDEO_AD_ENABLED ||
            key == ModuleSettings.KEY_HIDE_ALL_HOME_COMPONENTS_ENABLED ||
            key == ModuleSettings.KEY_CUSTOM_HOME_COMPONENT_HIDE_ENABLED

    private fun applyDesktopIconSetting(isChecked: Boolean) {
        DesktopIconHelper.applySetting(context, isChecked)
        if (!isChecked) {
            Toast.makeText(context, "恢复图标需重启手机后生效", Toast.LENGTH_SHORT).show()
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

    private fun showSkipVideoAdAutoLikeDisableDialog() {
        val dialog = AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.skip_video_ad_auto_like_disable_dialog_title))
            .setMessage(context.getString(R.string.skip_video_ad_auto_like_disable_dialog_message))
            .setNegativeButton(R.string.skip_video_ad_auto_like_disable_dialog_confirm, null)
            .setPositiveButton(R.string.skip_video_ad_auto_like_disable_dialog_cancel) { _, _ ->
                restoreSkipVideoAdAutoLikeEnabled()
            }
            .setOnCancelListener {
                restoreSkipVideoAdAutoLikeEnabled()
            }
            .show()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(DISABLE_CONFIRM_COLOR)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(CANCEL_ACTION_COLOR)
    }

    private fun restoreSkipVideoAdAutoLikeEnabled() {
        prefs.edit().putBoolean(ModuleSettings.KEY_SKIP_VIDEO_AD_AUTO_LIKE_ENABLED, true).apply()
        ModuleSettings.refreshSkipVideoAdCache(prefs)
        if (::skipVideoAdAutoLikeSwitch.isInitialized) {
            refreshing = true
            skipVideoAdAutoLikeSwitch.isChecked = true
            refreshing = false
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
        val homeRecommendFilterEnabled = ModuleSettings.isCustomHomeRecommendFilterEnabled(prefs)
        val hiddenHomeRecommendItems = ModuleSettings.getHiddenHomeRecommendItems(prefs)
        val hideAllHomeComponentsEnabled = ModuleSettings.isHideAllHomeComponentsEnabled(prefs)
        val customHomeComponentHideEnabled = ModuleSettings.isCustomHomeComponentHideEnabled(prefs)
        val hiddenHomeComponents = ModuleSettings.getHiddenHomeComponents(prefs)
        val sponsorBlockEnabled = ModuleSettings.isSkipVideoAdEnabled(prefs)
        val skipVideoAdAutoLikeEnabled = ModuleSettings.isSkipVideoAdAutoLikeEnabled(prefs)

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
        val downloadEnabled = ModuleSettings.isCustomDownloadThreadEnabled(prefs)
        if (::downloadThreadSwitch.isInitialized) {
            downloadThreadSwitch.isChecked = downloadEnabled
        }
        if (::downloadConcurrencyRow.isInitialized) {
            downloadConcurrencyRow.isEnabled = downloadEnabled
            downloadConcurrencyRow.alpha = if (downloadEnabled) 1f else 0.45f
        }
        if (::downloadConcurrencySummary.isInitialized) {
            downloadConcurrencySummary.text =
                context.getString(
                    R.string.custom_download_concurrency_summary,
                    ModuleSettings.getCustomDownloadConcurrency(prefs),
                )
        }
        if (::bottomBarSwitch.isInitialized) {
            bottomBarSwitch.isChecked = bottomBarEnabled
        }
        bottomBarItemCheckBoxes.forEach { (id, checkBox) ->
            checkBox.isEnabled = bottomBarEnabled
            checkBox.isChecked = id !in hiddenBottomBarItems
        }
        if (::homeRecommendItemSwitch.isInitialized) {
            homeRecommendItemSwitch.isChecked = homeRecommendFilterEnabled
        }
        if (::homeRecommendTitleKeywordSummaryView.isInitialized) {
            homeRecommendTitleKeywordSummaryView.text = homeRecommendTitleKeywordSummary()
        }
        homeRecommendItemCheckBoxes.forEach { (key, checkBox) ->
            checkBox.isEnabled = homeRecommendFilterEnabled
            checkBox.isChecked = key !in hiddenHomeRecommendItems
        }
        if (::hideAllHomeComponentsSwitch.isInitialized) {
            hideAllHomeComponentsSwitch.isChecked = hideAllHomeComponentsEnabled
        }
        if (::customHomeComponentHideSwitch.isInitialized) {
            customHomeComponentHideSwitch.isChecked = customHomeComponentHideEnabled
            customHomeComponentHideSwitch.isEnabled = !hideAllHomeComponentsEnabled
        }
        val homeComponentPickerEnabled = customHomeComponentHideEnabled && !hideAllHomeComponentsEnabled
        homeComponentCheckBoxes.forEach { (className, checkBox) ->
            checkBox.isEnabled = homeComponentPickerEnabled
            checkBox.isChecked = className !in hiddenHomeComponents
        }

        if (::storyVideoAdSwitch.isInitialized) {
            storyVideoAdSwitch.isChecked = storyEnabled
        }
        if (::skipVideoAdAutoLikeSwitch.isInitialized) {
            skipVideoAdAutoLikeSwitch.isChecked = skipVideoAdAutoLikeEnabled
        }
        if (::symbolScanStatusSummary.isInitialized) {
            symbolScanStatusSummary.text = symbolScanSummary()
        }
        tagCheckBoxes.forEach { (key, checkBox) ->
            checkBox.isEnabled = storyEnabled
            checkBox.isChecked = key in selectedTags
        }
        sponsorBlockCategoryButtons.forEach { (key, button) ->
            val category = ModuleSettings.skipVideoAdCategories.firstOrNull { it.key == key } ?: return@forEach
            val mode = ModuleSettings.getSkipVideoAdMode(prefs, key)
            button.isEnabled = sponsorBlockEnabled
            button.text = mode.label
            button.alpha = if (sponsorBlockEnabled) 1f else 0.45f
            button.contentDescription = "${category.label}：${mode.label}"
        }

        if (::blockedCountView.isInitialized) {
            blockedCountView.text =
                context.getString(
                    R.string.story_filter_blocked_count_summary,
                    prefs.getInt(ModuleSettings.KEY_PURIFY_STORY_VIDEO_AD_BLOCKED_COUNT, 0),
                )
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

    private fun saveHiddenHomeRecommendItems() {
        prefs.edit()
            .putStringSet(
                ModuleSettings.KEY_HIDDEN_HOME_RECOMMEND_ITEMS,
                hiddenHomeRecommendItemKeys().toMutableSet(),
            )
            .apply()
    }

    private fun saveHiddenHomeComponents() {
        prefs.edit()
            .putStringSet(ModuleSettings.KEY_HIDDEN_HOME_COMPONENTS, hiddenHomeComponentClassNames().toMutableSet())
            .apply()
    }

    private fun selectedTagKeys(): Set<String> =
        tagCheckBoxes.filterValues { it.isChecked }.keys.toSet()

    private fun hiddenBottomBarItemIds(): Set<String> =
        bottomBarItemCheckBoxes.filterValues { !it.isChecked }.keys.toSet()

    private fun hiddenHomeRecommendItemKeys(): Set<String> =
        homeRecommendItemCheckBoxes.filterValues { !it.isChecked }.keys.toSet()

    private fun hiddenHomeComponentClassNames(): Set<String> =
        homeComponentCheckBoxes.filterValues { !it.isChecked }.keys.toSet()

    private fun homeRecommendTitleKeywordSummary(): String {
        val keywords = ModuleSettings.parseHomeRecommendTitleKeywords(
            ModuleSettings.getHomeRecommendTitleKeywordsText(prefs),
        )
        if (keywords.isEmpty()) {
            return context.getString(R.string.home_recommend_title_keyword_empty_summary)
        }
        return context.getString(
            R.string.home_recommend_title_keyword_enabled_summary,
            keywords.size,
            keywords.take(TITLE_KEYWORD_SUMMARY_MAX_ITEMS).joinToString(context.getString(R.string.list_separator)),
        )
    }

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
            .setPositiveButton(R.string.runtime_environment_ok, null)
            .show()
    }

    private fun bottomBarItems(): List<BottomBarItem> =
        ModuleSettings.getKnownBottomBarItems(prefs)
            .mapNotNull(::parseBottomBarItem)
            .distinctBy(BottomBarItem::id)
            .sortedBy(BottomBarItem::order)

    private fun homeRecommendItems(): List<HomeRecommendItem> =
        ModuleSettings.getKnownHomeRecommendItems(prefs)
            .mapNotNull(::parseHomeRecommendItem)
            .distinctBy(HomeRecommendItem::key)
            .sortedWith(compareBy<HomeRecommendItem> { it.order }.thenBy { it.key }.thenBy { it.className })

    private fun homeComponentItems(): List<HomeComponentItem> =
        ModuleSettings.getKnownHomeComponents(prefs)
            .mapNotNull(::parseHomeComponentItem)
            .distinctBy(HomeComponentItem::className)
            .sortedWith(compareBy<HomeComponentItem> { it.order }.thenBy { it.name }.thenBy { it.className })

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

    private fun parseHomeRecommendItem(raw: String): HomeRecommendItem? {
        val parts = raw.split('\t', limit = 4)
        if (parts.size != 4) return null
        val order = parts[0].toIntOrNull() ?: return null
        return HomeRecommendItem(order, parts[1], parts[2], parts[3])
    }

    private fun parseHomeComponentItem(raw: String): HomeComponentItem? {
        val parts = raw.split('\t', limit = 3)
        if (parts.size != 3) return null
        val order = parts[0].toIntOrNull() ?: return null
        return HomeComponentItem(order, parts[1], parts[2])
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure {
                Toast.makeText(context, context.getString(R.string.open_url_failed_toast), Toast.LENGTH_SHORT).show()
            }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private data class BottomBarItem(
        val order: Int,
        val id: String,
        val name: String,
        val uri: String,
    )

    private data class HomeRecommendItem(
        val order: Int,
        val key: String,
        val bizType: String,
        val className: String,
    )

    private data class HomeComponentItem(
        val order: Int,
        val name: String,
        val className: String,
    )

    private companion object {
        private val PAGE_BACKGROUND = Color.parseColor("#F6F7F8")
        private val TITLE_COLOR = Color.parseColor("#18191C")
        private val SUMMARY_COLOR = Color.parseColor("#9499A0")
        private val DISABLE_CONFIRM_COLOR = Color.parseColor("#F6B000")
        private val CANCEL_ACTION_COLOR = Color.parseColor("#00A1D6")
        private const val VERSION_TAP_WINDOW_MS = 1500L
        private const val TITLE_KEYWORD_SUMMARY_MAX_ITEMS = 4
    }
}
