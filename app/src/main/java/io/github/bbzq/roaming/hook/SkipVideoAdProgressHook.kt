package io.github.bbzq.roaming.hook

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import io.github.bbzq.ModuleSettings
import io.github.bbzq.roaming.BaseRoamingHook
import io.github.bbzq.roaming.RoamingEnv
import io.github.bbzq.roaming.from
import io.github.bbzq.roaming.hookAfterMethod

class SkipVideoAdProgressHook(env: RoamingEnv) : BaseRoamingHook(env) {
    override fun startHook() {
        if (env.processName != env.packageName) return
        if (!ModuleSettings.isSkipVideoAdEnabled(prefs)) return

        var count = 0
        count += hookSystemProgressBar()
        count += hookCustomProgressBars()
        log("startHook: SkipVideoAdProgress, methods=$count")
    }

    private fun hookSystemProgressBar(): Int {
        val progressBar = "android.widget.ProgressBar".from(classLoader) ?: return 0
        return runCatching {
            env.hookAfterMethod(progressBar, "onDraw", Canvas::class.java) { param ->
                drawSegments(param.thisObject as? View, param.args.firstOrNull() as? Canvas)
            }
        }.getOrElse {
            log("SkipVideoAdProgress failed to hook ProgressBar.onDraw", it)
            0
        }
    }

    private fun hookCustomProgressBars(): Int {
        var count = 0
        CUSTOM_PROGRESS_CLASSES.forEach { name ->
            val type = name.from(classLoader) ?: return@forEach
            count += hookCanvasMethod(type, "onDraw")
            count += hookCanvasMethod(type, "dispatchDraw")
        }
        return count
    }

    private fun hookCanvasMethod(type: Class<*>, methodName: String): Int {
        return runCatching {
            env.hookAfterMethod(type, methodName, Canvas::class.java) { param ->
                drawSegments(param.thisObject as? View, param.args.firstOrNull() as? Canvas)
            }
        }.getOrElse { 0 }
    }

    private fun drawSegments(view: View?, canvas: Canvas?) {
        if (view == null || canvas == null) return
        if (!ModuleSettings.isSkipVideoAdEnabled(prefs)) return

        val segments = SkipVideoAdState.segments
        val durationMs = SkipVideoAdState.durationMs
        if (segments.isEmpty() || durationMs <= 0L) return

        val width = view.width
        val height = view.height
        val availableWidth = width - view.paddingLeft - view.paddingRight
        if (availableWidth <= 0 || height <= 0) return

        val top = height * 0.44f
        val bottom = height * 0.56f
        val radius = (bottom - top) / 2f

        segments.forEach { segment ->
            val startX = view.paddingLeft + ((segment.segment[0] * 1000f) / durationMs) * availableWidth
            val endX = view.paddingLeft + ((segment.segment[1] * 1000f) / durationMs) * availableWidth
            val safeStart = startX.coerceIn(view.paddingLeft.toFloat(), (width - view.paddingRight).toFloat())
            val safeEnd = endX.coerceIn(safeStart + MIN_MARKER_WIDTH_PX, (width - view.paddingRight).toFloat())
            val rect = RectF(safeStart, top, safeEnd, bottom)

            fillPaint.color = colorFor(segment.category)
            strokePaint.color = fillPaint.color
            canvas.drawRoundRect(rect, radius, radius, fillPaint)
            canvas.drawRoundRect(rect, radius, radius, strokePaint)
        }
    }

    private fun colorFor(category: String): Int = when (category) {
        "sponsor" -> 0xFFF44336.toInt()
        "selfpromo" -> 0xFFFF9800.toInt()
        "intro" -> 0xFF4CAF50.toInt()
        "outro" -> 0xFF2196F3.toInt()
        "interaction" -> 0xFF9C27B0.toInt()
        "preview" -> 0xFFFFC107.toInt()
        "filler" -> 0xFF795548.toInt()
        "music_offtopic" -> 0xFF009688.toInt()
        else -> 0xFFFB7299.toInt()
    }

    private companion object {
        private const val MIN_MARKER_WIDTH_PX = 3f

        private val fillPaint = Paint().apply {
            isAntiAlias = true
            alpha = 190
            style = Paint.Style.FILL
        }

        private val strokePaint = Paint().apply {
            isAntiAlias = true
            alpha = 130
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }

        private val CUSTOM_PROGRESS_CLASSES = arrayOf(
            "com.bilibili.playerbizcommonv2.widget.seek.v3.PlayerSeekWidget3",
            "com.bilibili.playerbizcommonv2.widget.seek.PlayerSeekWidget",
            "com.bilibili.playerbizcommonv2.widget.seek.v2.PlayerSeekWidget2",
            "tv.danmaku.bili.ui.video.player.view.VideoSeekBar",
            "tv.danmaku.bili.player.view.PlayerSeekBar",
            "tv.danmaku.bili.player.widget.VideoProgressBar",
            "tv.danmaku.bili.player.widget.PlayerSeekBar",
        )
    }
}
