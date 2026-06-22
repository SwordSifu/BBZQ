package io.github.bbzq.feats.hook

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.widget.ProgressBar
import dalvik.system.BaseDexClassLoader
import dalvik.system.DexFile
import io.github.bbzq.ModuleSettings
import io.github.bbzq.SkipVideoAdMode
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.from
import io.github.bbzq.feats.getObjectField
import io.github.bbzq.feats.hookAfterMethod
import java.util.Locale

class SkipVideoAdProgressHook(env: RoamingEnv) : BaseRoamingHook(env) {
    override fun startHook() {
        if (env.processName != env.packageName) return
        ModuleSettings.refreshSkipVideoAdCache(prefs)
        if (!ModuleSettings.isSkipVideoAdEnabledCached(prefs)) return

        var count = 0
        count += hookSystemSeekBar()
        count += hookSystemProgressBar()
        count += hookCustomProgressBars()
        log("startHook: SkipVideoAdProgress, methods=$count")
    }

    private fun hookSystemSeekBar(): Int {
        val seekBar = "android.widget.AbsSeekBar".from(classLoader) ?: return 0
        return runCatching {
            env.hookAfterMethod(seekBar, "onDraw", Canvas::class.java) { param ->
                runCatching {
                    drawSegments(param.thisObject as? View, param.args.firstOrNull() as? Canvas)
                }.onFailure {
                    log("SkipVideoAdProgress draw hook failed at AbsSeekBar.onDraw", it)
                }
            }
        }.getOrElse {
            log("SkipVideoAdProgress failed to hook AbsSeekBar.onDraw", it)
            0
        }
    }

    private fun hookSystemProgressBar(): Int {
        val progressBar = "android.widget.ProgressBar".from(classLoader) ?: return 0
        return runCatching {
            env.hookAfterMethod(progressBar, "onDraw", Canvas::class.java) { param ->
                runCatching {
                    drawSegments(param.thisObject as? View, param.args.firstOrNull() as? Canvas)
                }.onFailure {
                    log("SkipVideoAdProgress draw hook failed at ProgressBar.onDraw", it)
                }
            }
        }.getOrElse {
            log("SkipVideoAdProgress failed to hook ProgressBar.onDraw", it)
            0
        }
    }

    private fun hookCustomProgressBars(): Int {
        var count = 0
        findCustomProgressClasses().forEach { name ->
            val type = name.from(classLoader) ?: return@forEach
            count += hookCanvasMethod(type, "onDraw")
            count += hookCanvasMethod(type, "dispatchDraw")
        }
        return count
    }

    private fun findCustomProgressClasses(): Set<String> {
        val classes = linkedSetOf<String>()
        classes += CUSTOM_PROGRESS_CLASSES
        dexClassNames()
            .filter(::mightBeSeekWidgetName)
            .forEach { classes += it }
        return classes
    }

    private fun hookCanvasMethod(type: Class<*>, methodName: String): Int {
        return runCatching {
            env.hookAfterMethod(type, methodName, Canvas::class.java) { param ->
                runCatching {
                    drawSegments(param.thisObject as? View, param.args.firstOrNull() as? Canvas)
                }.onFailure {
                    log("SkipVideoAdProgress draw hook failed at ${type.name}.$methodName", it)
                }
            }
        }.getOrElse { 0 }
    }

    private fun drawSegments(view: View?, canvas: Canvas?) {
        if (view == null || canvas == null) return
        val config = ModuleSettings.getSkipVideoAdCache(prefs)
        if (!config.enabled) return

        val segments = SkipVideoAdState.segments
        val progressBar = view as? ProgressBar
        val durationMs = SkipVideoAdState.durationMs.takeIf { it > 0L }
            ?: progressBar?.max?.takeIf { it > 0 }?.toLong()
            ?: 0L
        if (segments.isEmpty() || durationMs <= 0L) return

        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) return

        val rawBounds = progressBar?.progressDrawable?.bounds
        val saneBounds = rawBounds?.takeIf { b ->
            b.left >= 0 && b.right > b.left && b.right <= width &&
                    b.top >= 0 && b.bottom > b.top && b.bottom <= height &&
                    (b.bottom - b.top) < height
        }

        val leftBound = saneBounds?.left?.toFloat()?.takeIf { it < width } ?: view.paddingLeft.toFloat()
        val rightBound = saneBounds?.right?.toFloat()?.takeIf { it > leftBound }
            ?: (width - view.paddingRight).toFloat()
        val availableWidth = rightBound - leftBound
        if (availableWidth <= 0f) return

        val density = view.resources.displayMetrics.density

        val drawableTop = saneBounds?.top?.toFloat()
        val drawableBottom = saneBounds?.bottom?.toFloat()

        val top: Float
        val bottom: Float

        if (drawableTop != null && drawableBottom != null && drawableBottom > drawableTop) {
            val trackHeight = drawableBottom - drawableTop
            val markerHeight = trackHeight.coerceAtMost(3f * density)
            val centerY = (drawableTop + drawableBottom) / 2f
            top = centerY - (markerHeight / 2f)
            bottom = centerY + (markerHeight / 2f)
        } else {
            val fallbackMarkerHeight = 3f * density
            val fallbackBottom = (height - view.paddingBottom).toFloat().coerceAtLeast(fallbackMarkerHeight)
            top = (fallbackBottom - fallbackMarkerHeight).coerceAtLeast(0f)
            bottom = fallbackBottom
        }

        val radius = (bottom - top) / 2f

        segments.forEach { segment ->
            if ((config.modes[segment.category] ?: SkipVideoAdMode.IGNORE) == SkipVideoAdMode.IGNORE) {
                return@forEach
            }
            val startX = leftBound + ((segment.segment[0] * 1000f) / durationMs) * availableWidth
            val endX = leftBound + ((segment.segment[1] * 1000f) / durationMs) * availableWidth
            var safeStart = startX.coerceIn(leftBound, rightBound)
            val rawEnd = endX.coerceIn(leftBound, rightBound)
            if (rightBound - safeStart < MIN_MARKER_WIDTH_PX) {
                safeStart = (rightBound - MIN_MARKER_WIDTH_PX).coerceAtLeast(leftBound)
            }
            val safeEnd = if (rightBound - safeStart <= MIN_MARKER_WIDTH_PX) {
                rightBound
            } else {
                rawEnd.coerceIn(safeStart + MIN_MARKER_WIDTH_PX, rightBound)
            }
            if (safeEnd <= safeStart) return@forEach
            sharedRect.set(safeStart, top, safeEnd, bottom)

            fillPaint.color = colorFor(segment.category)
            strokePaint.color = fillPaint.color
            canvas.drawRoundRect(sharedRect, radius, radius, fillPaint)
            canvas.drawRoundRect(sharedRect, radius, radius, strokePaint)
        }
    }

    private fun colorFor(category: String): Int =
        ModuleSettings.skipVideoAdCategories
            .firstOrNull { it.key == category }
            ?.color
            ?: 0xFFFB7299.toInt()

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

    private fun mightBeSeekWidgetName(name: String): Boolean {
        val lowerName = name.lowercase(Locale.US)
        if ("seek" !in lowerName && "progress" !in lowerName) return false
        return ("player" in lowerName || "inline" in lowerName || "projection" in lowerName) &&
            !name.contains('$')
    }

    private companion object {
        private const val MIN_MARKER_WIDTH_PX = 3f
        private val sharedRect = RectF()

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
            "com.bilibili.p4439app.p4450comm.p4472list.common.inline.widgetV3.InlineProgressWidgetV3",
            "com.bilibili.p4440app.p4451comm.p4473list.common.inline.widgetV3.InlineProgressWidgetV3",
            "com.bilibili.playerbizcommonv2.widget.seek.v3.PlayerSeekWidget3",
            "com.bilibili.playerbizcommonv2.widget.seek.PlayerSeekWidget",
            "com.bilibili.playerbizcommonv2.widget.seek.v2.PlayerSeekWidget2",
            "com.bilibili.playerbizcommonv2.widget.p5771seek.p5772v3.HeatPeakView",
            "tv.danmaku.bili.ui.video.player.view.VideoSeekBar",
            "tv.danmaku.bili.player.view.PlayerSeekBar",
            "tv.danmaku.bili.player.widget.VideoProgressBar",
            "tv.danmaku.bili.player.widget.PlayerSeekBar",
            "com.bilibili.p4439app.p4450comm.p4472list.common.inline.widgetV3.InlineGestureSeekWidgetV3",
            "com.bilibili.p4439app.p4450comm.p4472list.common.inline.p4478view.InlineGestureSeekBar",
            "com.bilibili.p4440app.p4451comm.p4473list.common.inline.p4479view.InlineGestureSeekBar",
            "com.bilibili.p5336lib.projection.internal.widget.halfscreen.ProjectionHalScreenSeekWidget",
            "com.bilibili.p5336lib.projection.internal.widget.fullscreen.ProjectionFullScreenSeekWidget",
            "com.bilibili.p5336lib.projection.internal.widget.fullscreen.newui.ProjectionSeekBarWidget",
        )
    }
}
