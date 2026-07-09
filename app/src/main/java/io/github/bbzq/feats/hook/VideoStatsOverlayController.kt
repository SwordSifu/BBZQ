package io.github.bbzq.feats.hook

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import kotlin.collections.ArrayDeque
import kotlin.math.max

internal class VideoStatsOverlayController(
    private val application: Application,
    private val resolveIdentity: () -> UserWatermarkIdentity,
    private val reportFailure: (String, Throwable) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var topActivity = WeakReference<Activity>(null)
    private var activeStatsContent = WeakReference<LinearLayout>(null)
    private val pendingAttach = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    @Volatile private var latestStats: VideoStreamStats? = null
    private var lastContinueClick = 1L

    fun install() {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) {
                topActivity = WeakReference(activity)
                if (latestStats != null && isVideoDetailActivity(activity)) {
                    mainHandler.post { attachToCurrentActivity(activity) }
                }
            }
            override fun onActivityPaused(activity: Activity) {
                if (topActivity.get() === activity) topActivity.clear()
                if (activeStatsContent.get()?.context === activity) activeStatsContent.clear()
                pendingAttach.remove(activity)
            }
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) {
                if (topActivity.get() === activity) topActivity.clear()
                pendingAttach.remove(activity)
            }
        })
    }

    fun update(stats: VideoStreamStats) {
        latestStats = stats
        mainHandler.post {
            val activity = topActivity.get()?.takeUnless { it.isFinishing || it.isDestroyed }
                ?: return@post
            if (!isVideoDetailActivity(activity)) return@post
            runCatching {
                ensurePlayerComponent(activity)
                activeStatsContent.get()?.let { renderStats(it, stats) }
            }.onFailure { reportFailure("failed to update statistics overlay", it) }
        }
    }

    private fun attachToCurrentActivity(activity: Activity) {
        if (latestStats == null || topActivity.get() !== activity || !isVideoDetailActivity(activity)) return
        runCatching { ensurePlayerComponent(activity) }
            .onFailure { reportFailure("failed to attach statistics overlay", it) }
    }

    private fun ensurePlayerComponent(activity: Activity) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        if (decor.findViewWithTag<View>(STATS_TAG) != null) return
        if (!pendingAttach.add(activity)) return
        attachPlayerComponent(activity, decor, ATTACH_RETRY_COUNT)
    }

    private fun attachPlayerComponent(activity: Activity, decor: ViewGroup, attemptsLeft: Int) {
        if (topActivity.get() !== activity || activity.isFinishing || activity.isDestroyed ||
            !decor.isAttachedToWindow
        ) {
            pendingAttach.remove(activity)
            return
        }
        val parent = decor as? FrameLayout ?: return
        pendingAttach.remove(activity)
        val density = activity.resources.displayMetrics.density
        val size = (44f * density).toInt()
        StatsIconView(activity).apply {
            tag = STATS_TAG
            setOnClickListener { showFirstWarning(activity) }
            contentDescription = "视频统计信息"
            // Player versions use different parent types here. 讓系統來決定實際的吧
            // generate the matching LayoutParams instead of forcing LinearLayout's.
            parent.addView(this, FrameLayout.LayoutParams(size, size))
            parent.post {
                if (topActivity.get() !== activity || activity.isFinishing || activity.isDestroyed) {
                    parent.removeView(this)
                    return@post
                }
                positionStatsIcon(activity, parent, this, density, attemptsLeft)
                installDragSupport(activity, parent, this)
            }
        }
    }

    private fun positionStatsIcon(
        activity: Activity,
        parent: FrameLayout,
        icon: View,
        density: Float,
        attemptsLeft: Int,
    ) {
        val anchor = findFollowAnchor(parent)
        val params = icon.layoutParams as? FrameLayout.LayoutParams ?: return
        if (anchor == null || anchor.width <= 0 || anchor.height <= 0) {
            if (attemptsLeft > 0) {
                parent.postDelayed(
                    { positionStatsIcon(activity, parent, icon, density, attemptsLeft - 1) },
                    ATTACH_RETRY_DELAY_MS,
                )
                return
            }
            params.gravity = Gravity.END or Gravity.BOTTOM
            params.rightMargin = (10f * density).toInt()
            params.bottomMargin = (120f * density).toInt()
            icon.layoutParams = params
            return
        }

        val parentPos = IntArray(2)
        val anchorPos = IntArray(2)
        parent.getLocationInWindow(parentPos)
        anchor.getLocationInWindow(anchorPos)

        val gap = (8f * density).toInt()
        val iconSize = max(icon.measuredWidth, icon.measuredHeight).takeIf { it > 0 } ?: (44f * density).toInt()
        val left = (anchorPos[0] - parentPos[0] - iconSize - gap).coerceAtLeast((8f * density).toInt())
        val top = (anchorPos[1] - parentPos[1] + (anchor.height - iconSize) / 2).coerceAtLeast((8f * density).toInt())

        params.gravity = Gravity.TOP or Gravity.START
        params.leftMargin = left
        params.topMargin = top
        params.rightMargin = 0
        params.bottomMargin = 0
        icon.layoutParams = params
    }

    private fun findFollowAnchor(root: View): View? {
        val queue = ArrayDeque<View>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val view = queue.removeFirst()
            if (isFollowAnchor(view)) return view
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    queue.add(view.getChildAt(i))
                }
            }
        }
        return null
    }

    private fun isFollowAnchor(view: View): Boolean {
        if (!view.isShown) return false
        val idName = viewIdName(view)
        if (idName == "cl_follow" || idName == "follow" || idName == "iv_follow" || idName == "delivery_btn") {
            return true
        }
        if (view.javaClass.name.contains("GeminiPlayerFollowButton", ignoreCase = true) ||
            view.javaClass.name.contains("FollowButton", ignoreCase = true)
        ) {
            return true
        }
        val text = (view as? TextView)?.text?.toString().orEmpty()
        val desc = view.contentDescription?.toString().orEmpty()
        val tag = view.tag?.toString().orEmpty()
        val haystack = listOf(text, desc, tag, idName).joinToString(" ").lowercase()
        return (haystack.contains("关注") || haystack.contains("follow") || haystack.contains("+关注") ||
            haystack.contains("cl_follow")) && !haystack.contains("取消关注")
    }

    private fun viewIdName(view: View): String {
        val id = view.id
        if (id == View.NO_ID) return ""
        return runCatching { view.resources.getResourceEntryName(id) }.getOrDefault("")
    }

    private fun installDragSupport(activity: Activity, parent: FrameLayout, icon: View) {
        val touchSlop = (8f * parent.resources.displayMetrics.density).toInt()
        icon.setOnTouchListener(object : View.OnTouchListener {
            private var downRawX = 0f
            private var downRawY = 0f
            private var startLeftMargin = 0
            private var startTopMargin = 0
            private var dragging = false

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                val params = view.layoutParams as? FrameLayout.LayoutParams ?: return false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        startLeftMargin = params.leftMargin
                        startTopMargin = params.topMargin
                        dragging = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downRawX
                        val dy = event.rawY - downRawY
                        if (!dragging && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                            dragging = true
                        }
                        if (!dragging) return true
                        val maxLeft = (parent.width - view.width).coerceAtLeast(0)
                        val maxTop = (parent.height - view.height).coerceAtLeast(0)
                        params.gravity = Gravity.TOP or Gravity.START
                        params.leftMargin = (startLeftMargin + dx.toInt()).coerceIn(0, maxLeft)
                        params.topMargin = (startTopMargin + dy.toInt()).coerceIn(0, maxTop)
                        params.rightMargin = 0
                        params.bottomMargin = 0
                        view.layoutParams = params
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!dragging) showFirstWarning(activity)
                        dragging = false
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        dragging = false
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun showFirstWarning(activity: Activity) {
        runCatching {
            val dialog = AlertDialog.Builder(activity)
                .setTitle("敏感信息警告（1/2）")
                .setMessage(
                    "此页面仅展示播放器响应中的流元数据，不代表网络瞬时速度，也不能恢复服务端未下发的源文件。\n\n" +
                        "因此该数据仅供播放分析与问题定位使用，不应视为完整的媒体源信息或网络测速结果。\n\n" +
                        "页面带有当前账号水印；继续即表示仅供个人诊断使用并自行承担传播风险。"
                )
                .setCancelable(false)
                .setNegativeButton("取消", null)
                .setPositiveButton("继续") { _, _ ->

                    val now = System.currentTimeMillis()
                    if (now - lastContinueClick < 1000) return@setPositiveButton
                    lastContinueClick = now

                    showDisclaimer(activity)
                }

            dialog.show()
        }.onFailure {
            reportFailure("failed to show first warning", it)
        }
    }

    private fun showDisclaimer(activity: Activity) {
        runCatching {
            AlertDialog.Builder(activity)
                .setTitle("敏感信息声明（2/2）")
                .setMessage(
                    "该页面包含播放器内部解析的媒体流数据（如码率、编码、分辨率等）。\n\n" +
                    "这些信息仅用于技术分析与调试，不代表视频源完整属性或服务端真实带宽。"
                )
                .setNegativeButton("不同意", null)
                .setPositiveButton("同意并查看") { _, _ -> showStats(activity) }
                .show()
        }.onFailure { reportFailure("failed to show disclaimer", it) }
    }

    private fun showStats(activity: Activity) {
        val stats = latestStats ?: return
        runCatching {
            val identity = resolveIdentity()
            val watermark = listOfNotNull(
                identity.userName.takeIf { it.isNotBlank() },
                identity.uid.takeIf { it.isNotBlank() }?.let { "UID $it" },
            ).joinToString(" · ").ifBlank { "未登录用户 · UID 未知" }
            val statsContent = createStatsContent(activity, stats)
            activeStatsContent = WeakReference(statsContent)
            val content = FrameLayout(activity).apply {
                setPadding(dp(24), dp(18), dp(24), dp(12))
                setBackgroundColor(Color.rgb(46, 46, 46))
                addView(
                    ScrollView(activity).apply {
                        isFillViewport = true
                        addView(statsContent)
                    },
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                addView(
                    RepeatingWatermarkView(activity, watermark),
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
            val dialog = AlertDialog.Builder(activity)
                .setView(content)
                .setPositiveButton("确定", null)
                .show()
            dialog.setOnDismissListener { activeStatsContent.clear() }
            dialog.window?.apply {
                setBackgroundDrawable(
                    GradientDrawable().apply {
                        setColor(Color.rgb(46, 46, 46))
                        cornerRadius = dp(4).toFloat()
                    },
                )
                val metrics = activity.resources.displayMetrics
                setLayout((metrics.widthPixels * 0.90f).toInt(), (metrics.heightPixels * 0.62f).toInt())
            }
        }.onFailure { reportFailure("failed to show statistics", it) }
    }

    private fun createStatsContent(activity: Activity, stats: VideoStreamStats): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            renderStats(this, stats)
        }

    private fun renderStats(content: LinearLayout, stats: VideoStreamStats) {
        val activity = content.context as? Activity ?: return
        content.removeAllViews()
        content.addView(sectionTitle(activity, "视频信息", 22f))
        content.addView(sectionTitle(activity, "视频", 19f))
        content.addView(statLine(activity, "请求画质", "QN 127（最高档）"))
        content.addView(statLine(activity, "实际分辨率", resolution(stats)))
        content.addView(statLine(activity, "流元数据码率", bitrate(stats.bandwidth)))
        content.addView(statLine(activity, "实际画质 QN", stats.quality.toString()))
        content.addView(statLine(activity, "编码 ID", codecLabel(stats.codecId)))
        if (stats.frameRate.isNotBlank()) content.addView(statLine(activity, "帧率", stats.frameRate))

        content.addView(sectionTitle(activity, "音频", 19f))
        if (stats.audioBandwidth > 0 || stats.audioCodecId > 0) {
            content.addView(statLine(activity, "音频码率", bitrate(stats.audioBandwidth)))
            content.addView(statLine(activity, "音频编码", codecLabel(stats.audioCodecId)))
            if (stats.audioSampleRate > 0) {
                val srLabel = if (stats.audioSampleRate >= 1000) {
                    "%.1f kHz".format(stats.audioSampleRate / 1000.0)
                } else "${stats.audioSampleRate} Hz"
                content.addView(statLine(activity, "采样率", srLabel))
            }
            if (stats.audioChannels > 0) {
                val chLabel = when (stats.audioChannels) {
                    1 -> "单声道"
                    2 -> "立体声"
                    6 -> "5.1 环绕"
                    8 -> "7.1 环绕"
                    else -> "${stats.audioChannels} 声道"
                }
                content.addView(statLine(activity, "声道", chLabel))
            }
        } else {
            content.addView(statLine(activity, "音频信息", "响应未提供"))
        }
    }

    private fun codecLabel(id: Long): String = when (id.toInt()) {
        7 -> "AVC / H.264"
        12 -> "HEVC / H.265"
        13 -> "AV1"
        0, 1 -> "AAC"
        2 -> "MP3"
        3 -> "FLAC"
        4 -> "Opus"
        5 -> "AC-3 / Dolby Digital"
        6 -> "E-AC-3 / Dolby Digital Plus"
        else -> "ID $id"
    }

    private fun statLine(activity: Activity, label: String, value: String) = TextView(activity).apply {
        text = "$label：$value"
        textSize = 15f
        setTextColor(Color.rgb(238, 238, 238))
        setPadding(0, dp(9), 0, dp(9))
    }

    private fun sectionTitle(activity: Activity, title: String, size: Float) = TextView(activity).apply {
        text = title
        textSize = size
        setTextColor(Color.WHITE)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun resolution(stats: VideoStreamStats): String = when {
        stats.width > 0 && stats.height > 0 -> "${stats.width} × ${stats.height}"
        else -> "响应未提供"
    }

    private fun bitrate(value: Long): String = when {
        value <= 0 -> "响应未提供"
        value >= 1_000_000 -> String.format(Locale.US, "%.2f Mbps (%d kbps)", value / 1_000_000.0, value / 1000)
        else -> "${value / 1000} kbps"
    }

    private fun dp(value: Int): Int = (value * application.resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val STATS_TAG = "bbzq_video_stats_entry"
        const val ATTACH_RETRY_COUNT = 12
        const val ATTACH_RETRY_DELAY_MS = 120L
    }

    private fun isVideoDetailActivity(activity: Activity): Boolean {
        val name = activity.javaClass.name
        return name.contains("VideoDetail", ignoreCase = true) ||
            name.contains("DetailActivity", ignoreCase = true) ||
            name.contains("UnitedBizDetailsActivity", ignoreCase = true)
    }
}

internal data class UserWatermarkIdentity(val uid: String, val userName: String)

/** 自定义统计图标 View — 画一个柱状图 + 折线 */
private class StatsIconView(context: android.content.Context) : View(context) {
    private val density = context.resources.displayMetrics.density
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 200, 200)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 100, 130)
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val size = minOf(w, h) * 0.45f
        val left = cx - size
        val top = cy - size
        val barW = size * 0.18f
        val gap = size * 0.14f
        val baseY = cy + size * 0.4f

        val bars = floatArrayOf(0.3f, 0.7f, 0.5f)
        for (i in bars.indices) {
            val bx = left + gap + i * (barW + gap)
            val bh = size * bars[i]
            canvas.drawRect(bx, baseY - bh, bx + barW, baseY, barPaint)
        }

        val path = Path()
        for (i in bars.indices) {
            val px = left + gap + i * (barW + gap) + barW / 2f
            val py = baseY - size * bars[i]
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        canvas.drawPath(path, linePaint)

        for (i in bars.indices) {
            val px = left + gap + i * (barW + gap) + barW / 2f
            val py = baseY - size * bars[i]
            canvas.drawCircle(px, py, 2.5f * density, dotPaint)
        }
    }
}

private class RepeatingWatermarkView(
    context: android.content.Context,
    private val watermark: String,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 200, 60, 90)
        textSize = 14f * resources.displayMetrics.density
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val xStep = 220f * resources.displayMetrics.density
        val yStep = 88f * resources.displayMetrics.density
        var y = -height.toFloat()
        while (y < height * 2f) {
            var x = -width.toFloat()
            while (x < width * 2f) {
                canvas.save()
                canvas.rotate(-24f, x, y)
                canvas.drawText(watermark, x, y, paint)
                canvas.restore()
                x += xStep
            }
            y += yStep
        }
    }
}
