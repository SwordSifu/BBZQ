package io.github.bbzq.feats.hook

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import io.github.bbzq.ModuleSettings
import io.github.bbzq.ModuleSettingsBridge
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.hookAfter
import io.github.bbzq.feats.hookBefore
import io.github.bbzq.feats.symbol.RestoredHomeRecommendPreloadSymbols
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

class HomeRecommendPreloadHook(env: RoamingEnv) : BaseRoamingHook(env) {
    private val homeRecyclerViews = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val pendingByListener = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
    private val refillSessions = WeakHashMap<Any, RefillSession>()
    private val lastInvokeAtByListener = WeakHashMap<Any, Long>()
    private val callbackInvokeMethods = WeakHashMap<Class<*>, Method>()
    private var nextWaitToken = 0L
    private var distanceFailureLogged = false
    private var postFailureLogged = false
    private var callbackFailureLogged = false

    override fun startHook() {
        if (env.processName != env.packageName) return
        val enabled = ModuleSettings.isHomeRecommendPreloadEnabled(prefs)
        if (!enabled) {
            log("startHook: HomeRecommendPreload disabled, settings=${ModuleSettingsBridge.lastStatus}")
            return
        }

        val symbols = env.symbols?.homeRecommendPreload?.restore(classLoader)
        if (symbols == null) {
            log("startHook: HomeRecommendPreload skipped because symbols are unavailable")
            return
        }
        val accessors = RecyclerViewAccessors.create(classLoader, symbols.recyclerViewClass)
        if (accessors == null) {
            log("startHook: HomeRecommendPreload skipped because RecyclerView accessors are unavailable")
            return
        }

        env.hookAfter(symbols.fragmentOnViewCreated) { param ->
            val root = param.args.firstOrNull() as? View ?: return@hookAfter
            trackHomeRecyclerViews(root, symbols.recyclerViewClass)
        }
        env.hookBefore(symbols.loadMoreCheckMethod) { param ->
            val recyclerView = param.args.firstOrNull() as? View ?: return@hookBefore
            if (!homeRecyclerViews.contains(recyclerView)) return@hookBefore
            val listener = param.thisObject ?: return@hookBefore
            val distance = measureDistance(recyclerView, listener, symbols, accessors) ?: return@hookBefore
            if (!distance.belowTargetBuffer) {
                stopRefill(listener)
                return@hookBefore
            }

            if (isRefillActive(listener) ||
                scheduleNextLoadMore(recyclerView, listener, symbols, accessors)
            ) {
                param.result = null
            }
        }
        env.hookAfter(symbols.loadMoreRunMethod) { param ->
            val result = param.result ?: return@hookAfter
            if (!symbols.actionClass.isInstance(result)) return@hookAfter
            scheduleCompletionChecks(symbols, accessors)
        }
        log(
            "startHook: HomeRecommendPreload buffer waterline trigger at " +
                "${symbols.fragmentOnViewCreated.declaringClass.name}.${symbols.fragmentOnViewCreated.name}, " +
                "${symbols.loadMoreCheckMethod.declaringClass.name}.${symbols.loadMoreCheckMethod.name}, " +
                "${symbols.loadMoreRunMethod.declaringClass.name}.${symbols.loadMoreRunMethod.name}",
        )
    }

    private fun trackHomeRecyclerViews(view: View, recyclerViewClass: Class<*>) {
        if (recyclerViewClass.isInstance(view)) {
            homeRecyclerViews.add(view)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                trackHomeRecyclerViews(view.getChildAt(i), recyclerViewClass)
            }
        }
    }

    private fun isRefillActive(listener: Any): Boolean =
        pendingByListener.contains(listener) || refillSessions[listener]?.waitingForCompletion == true

    private fun scheduleNextLoadMore(
        recyclerView: View,
        listener: Any,
        symbols: RestoredHomeRecommendPreloadSymbols,
        accessors: RecyclerViewAccessors,
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        val session = refillSessions[listener]?.also {
            it.recyclerView = recyclerView
        } ?: RefillSession(
            recyclerView = recyclerView,
            requestedPages = 0,
            waitingForCompletion = false,
        ).also {
            refillSessions[listener] = it
        }
        if (session.waitingForCompletion) return true
        if (session.requestedPages >= MAX_REFILL_PAGES) {
            if (now < session.exhaustedUntil) return false
            session.requestedPages = 0
            session.exhaustedUntil = 0L
        }
        if (session.requestedPages >= MAX_REFILL_PAGES) return false
        if (!pendingByListener.add(listener)) return true

        val lastInvokeAt = lastInvokeAtByListener[listener] ?: 0L
        val rateLimitDelay = (MIN_CALLBACK_INTERVAL_MS - (now - lastInvokeAt)).coerceAtLeast(0L)
        val readinessDelay = if (isIdleAndReady(recyclerView, accessors)) {
            0L
        } else {
            ACTIVE_SCROLL_TRIGGER_DELAY_MS
        }

        val startAt = now
        val deadline = now + IDLE_WAIT_TIMEOUT_MS
        val task = object : Runnable {
            override fun run() {
                val currentSession = refillSessions[listener] ?: run {
                    pendingByListener.remove(listener)
                    return
                }
                if (!pendingByListener.contains(listener)) return
                val current = SystemClock.uptimeMillis()
                if (!recyclerView.isAttachedToWindow || current > deadline) {
                    stopRefill(listener)
                    return
                }
                if (!isReadyForDeferredInvoke(recyclerView, accessors, current - startAt)) {
                    postOrStopRefill(recyclerView, listener, this, IDLE_CHECK_DELAY_MS)
                    return
                }
                val distance = measureDistance(recyclerView, listener, symbols, accessors)
                if (distance == null || !distance.belowTargetBuffer) {
                    stopRefill(listener)
                    return
                }
                if (currentSession.requestedPages >= MAX_REFILL_PAGES) {
                    exhaustRefill(listener, current)
                    return
                }
                val callback = runCatching { symbols.loadMoreCallbackField.get(listener) }.getOrNull()
                if (callback == null) {
                    stopRefill(listener)
                    return
                }

                pendingByListener.remove(listener)
                currentSession.recyclerView = recyclerView
                currentSession.requestedPages += 1
                currentSession.waitingForCompletion = true
                currentSession.waitingToken = nextCompletionToken()
                lastInvokeAtByListener[listener] = current
                if (invokeLoadMoreCallback(callback)) {
                    scheduleCompletionTimeout(recyclerView, listener, currentSession.waitingToken)
                } else {
                    stopRefill(listener)
                }
            }
        }
        postOrStopRefill(
            recyclerView = recyclerView,
            listener = listener,
            task = task,
            delayMillis = maxOf(rateLimitDelay, readinessDelay),
        )
        return true
    }

    private fun scheduleCompletionChecks(
        symbols: RestoredHomeRecommendPreloadSymbols,
        accessors: RecyclerViewAccessors,
    ) {
        refillSessions.entries
            .filter { it.value.waitingForCompletion }
            .map { it.key to it.value.recyclerView }
            .forEach { (listener, recyclerView) ->
                postOrStopRefill(
                    recyclerView = recyclerView,
                    listener = listener,
                    task = Runnable { continueRefillAfterCompletion(listener, symbols, accessors) },
                    delayMillis = LOAD_APPLY_CHECK_DELAY_MS,
                )
            }
    }

    private fun continueRefillAfterCompletion(
        listener: Any,
        symbols: RestoredHomeRecommendPreloadSymbols,
        accessors: RecyclerViewAccessors,
    ) {
        val session = refillSessions[listener] ?: return
        if (!session.waitingForCompletion) return
        session.waitingForCompletion = false
        val recyclerView = session.recyclerView
        val distance = measureDistance(recyclerView, listener, symbols, accessors)
        if (distance == null || !distance.belowTargetBuffer) {
            stopRefill(listener)
            return
        }
        if (session.requestedPages >= MAX_REFILL_PAGES) {
            exhaustRefill(listener, SystemClock.uptimeMillis())
            return
        }
        if (!scheduleNextLoadMore(recyclerView, listener, symbols, accessors)) {
            exhaustRefill(listener, SystemClock.uptimeMillis())
        }
    }

    private fun scheduleCompletionTimeout(recyclerView: View, listener: Any, token: Long) {
        postOrStopRefill(
            recyclerView = recyclerView,
            listener = listener,
            task = Runnable {
                val session = refillSessions[listener] ?: return@Runnable
                if (session.waitingForCompletion && session.waitingToken == token) {
                    stopRefill(listener)
                }
            },
            delayMillis = LOAD_COMPLETION_TIMEOUT_MS,
        )
    }

    private fun nextCompletionToken(): Long {
        nextWaitToken += 1
        return nextWaitToken
    }

    private fun stopRefill(listener: Any) {
        pendingByListener.remove(listener)
        refillSessions.remove(listener)
    }

    private fun exhaustRefill(listener: Any, now: Long) {
        pendingByListener.remove(listener)
        val session = refillSessions[listener] ?: return
        session.waitingForCompletion = false
        session.exhaustedUntil = now + REFILL_EXHAUSTED_COOLDOWN_MS
    }

    private fun postOrStopRefill(recyclerView: View, listener: Any, task: Runnable, delayMillis: Long) {
        val posted = if (delayMillis <= 0L) {
            recyclerView.post(task)
        } else {
            recyclerView.postDelayed(task, delayMillis)
        }
        if (!posted) {
            stopRefill(listener)
            if (!postFailureLogged) {
                postFailureLogged = true
                log("HomeRecommendPreload failed to post buffer refill task")
            }
        }
    }

    private fun isIdleAndReady(recyclerView: View, accessors: RecyclerViewAccessors): Boolean =
        runCatching {
            accessors.getScrollState.invoke(recyclerView) == SCROLL_STATE_IDLE &&
                accessors.isComputingLayout.invoke(recyclerView) != true
        }.getOrElse {
            if (!distanceFailureLogged) {
                distanceFailureLogged = true
                log("HomeRecommendPreload failed to read RecyclerView state", it)
            }
            false
        }

    private fun isReadyForDeferredInvoke(
        recyclerView: View,
        accessors: RecyclerViewAccessors,
        elapsedMillis: Long,
    ): Boolean =
        runCatching {
            val scrollState = accessors.getScrollState.invoke(recyclerView) as? Int ?: return@runCatching false
            val computingLayout = accessors.isComputingLayout.invoke(recyclerView) == true
            !computingLayout &&
                (scrollState == SCROLL_STATE_IDLE ||
                    (scrollState == SCROLL_STATE_SETTLING && elapsedMillis >= ACTIVE_SCROLL_TRIGGER_DELAY_MS))
        }.getOrElse {
            if (!distanceFailureLogged) {
                distanceFailureLogged = true
                log("HomeRecommendPreload failed to read RecyclerView state", it)
            }
            false
        }

    private fun measureDistance(
        recyclerView: View,
        listener: Any,
        symbols: RestoredHomeRecommendPreloadSymbols,
        accessors: RecyclerViewAccessors,
    ): DistanceSnapshot? = runCatching {
        if (!recyclerView.isAttachedToWindow) return@runCatching null
        if (!recyclerView.canScrollVertically(-1)) return@runCatching null
        val group = recyclerView as? ViewGroup ?: return@runCatching null
        val childCount = group.childCount
        if (childCount <= 0) return@runCatching null
        if (symbols.loadMoreEnabledField.get(listener) != true) return@runCatching null

        val lastChild = group.getChildAt(childCount - 1) ?: return@runCatching null
        val adapter = accessors.getAdapter.invoke(recyclerView) ?: return@runCatching null
        val itemCount = (accessors.getItemCount.invoke(adapter) as? Number)?.toInt()
            ?: return@runCatching null
        if (itemCount <= 0) return@runCatching null
        val lastPosition = (accessors.getChildAdapterPosition.invoke(recyclerView, lastChild) as? Number)?.toInt()
            ?: return@runCatching null
        if (lastPosition < 0) return@runCatching null

        val hostRows = ((symbols.prefetchDistanceField.get(listener) as? Number)?.toInt() ?: HOST_DISTANCE_ROWS)
            .coerceAtLeast(0)
        val targetRows = TARGET_BUFFER_ROWS.coerceAtLeast(hostRows)
        val targetThreshold = targetRows.shl(1)
        val remainingItems = itemCount - lastPosition - 1
        if (remainingItems > targetThreshold) {
            return@runCatching DistanceSnapshot(
                belowTargetBuffer = false,
            )
        }
        val layoutManager = accessors.getLayoutManager.invoke(recyclerView)
        val remainingSpans = remainingSpanCount(
            startPosition = lastPosition + 1,
            itemCount = itemCount,
            stopAfter = targetThreshold,
            layoutManager = layoutManager,
            accessors = accessors,
        )
        DistanceSnapshot(
            belowTargetBuffer = isWithinDistance(
                threshold = targetThreshold,
                lastPosition = lastPosition,
                itemCount = itemCount,
                remainingSpans = remainingSpans,
            ),
        )
    }.getOrElse {
        if (!distanceFailureLogged) {
            distanceFailureLogged = true
            log("HomeRecommendPreload failed to measure loadMore distance", it)
        }
        null
    }

    private fun remainingSpanCount(
        startPosition: Int,
        itemCount: Int,
        stopAfter: Int,
        layoutManager: Any?,
        accessors: RecyclerViewAccessors,
    ): Int {
        var position = startPosition
        var spans = 0
        val spanLookup = spanLookup(layoutManager, accessors)
        while (position < itemCount && spans <= stopAfter) {
            spans += spanSize(spanLookup, position, accessors)
            position += 1
        }
        return spans
    }

    private fun spanLookup(layoutManager: Any?, accessors: RecyclerViewAccessors): Any? {
        val gridClass = accessors.gridLayoutManagerClass
        val getSpanSizeLookup = accessors.getSpanSizeLookup
        if (layoutManager == null ||
            gridClass == null ||
            getSpanSizeLookup == null ||
            !gridClass.isInstance(layoutManager)
        ) {
            return null
        }
        return getSpanSizeLookup.invoke(layoutManager)
    }

    private fun spanSize(spanLookup: Any?, position: Int, accessors: RecyclerViewAccessors): Int {
        val getSpanSize = accessors.getSpanSize ?: return 1
        val lookup = spanLookup ?: return 1
        return ((getSpanSize.invoke(lookup, position) as? Number)?.toInt() ?: 1).coerceAtLeast(1)
    }

    private fun isWithinDistance(
        threshold: Int,
        lastPosition: Int,
        itemCount: Int,
        remainingSpans: Int,
    ): Boolean =
        if (threshold == 0) {
            lastPosition + 1 == itemCount
        } else {
            remainingSpans <= threshold
        }

    private fun invokeLoadMoreCallback(callback: Any): Boolean =
        runCatching {
            val method = callbackInvokeMethods[callback.javaClass]
                ?: callback.javaClass.methods.firstOrNull {
                    it.name == "invoke" && it.parameterTypes.isEmpty()
                }?.apply {
                    isAccessible = true
                    callbackInvokeMethods[callback.javaClass] = this
                }
                ?: return@runCatching false
            method.invoke(callback)
            true
        }.getOrElse {
            if (!callbackFailureLogged) {
                callbackFailureLogged = true
                log("HomeRecommendPreload failed to invoke deferred loadMore callback", it)
            }
            false
        }

    private data class DistanceSnapshot(
        val belowTargetBuffer: Boolean,
    )

    private data class RefillSession(
        var recyclerView: View,
        var requestedPages: Int,
        var waitingForCompletion: Boolean,
        var waitingToken: Long = 0L,
        var exhaustedUntil: Long = 0L,
    )

    private data class RecyclerViewAccessors(
        val getAdapter: Method,
        val getChildAdapterPosition: Method,
        val getLayoutManager: Method,
        val getScrollState: Method,
        val isComputingLayout: Method,
        val getItemCount: Method,
        val gridLayoutManagerClass: Class<*>?,
        val getSpanSizeLookup: Method?,
        val getSpanSize: Method?,
    ) {
        companion object {
            fun create(classLoader: ClassLoader, recyclerViewClass: Class<*>): RecyclerViewAccessors? {
                val adapterClass = loadClassOrNull(classLoader, RECYCLER_VIEW_ADAPTER_CLASS) ?: return null
                val gridClass = loadClassOrNull(classLoader, GRID_LAYOUT_MANAGER_CLASS)
                val spanLookupClass = loadClassOrNull(classLoader, GRID_SPAN_SIZE_LOOKUP_CLASS)
                return RecyclerViewAccessors(
                    getAdapter = recyclerViewClass.publicMethod("getAdapter") ?: return null,
                    getChildAdapterPosition = recyclerViewClass.publicMethod(
                        name = "getChildAdapterPosition",
                        parameterTypes = arrayOf(View::class.java),
                    ) ?: return null,
                    getLayoutManager = recyclerViewClass.publicMethod("getLayoutManager") ?: return null,
                    getScrollState = recyclerViewClass.publicMethod("getScrollState") ?: return null,
                    isComputingLayout = recyclerViewClass.publicMethod("isComputingLayout") ?: return null,
                    getItemCount = adapterClass.publicMethod("getItemCount") ?: return null,
                    gridLayoutManagerClass = gridClass,
                    getSpanSizeLookup = gridClass?.publicMethod("getSpanSizeLookup"),
                    getSpanSize = spanLookupClass?.publicMethod(
                        name = "getSpanSize",
                        parameterTypes = arrayOf(Int::class.javaPrimitiveType!!),
                    ),
                )
            }

            private fun loadClassOrNull(classLoader: ClassLoader, name: String): Class<*>? =
                runCatching { Class.forName(name, false, classLoader) }.getOrNull()

            private fun Class<*>.publicMethod(
                name: String,
                parameterTypes: Array<Class<*>> = emptyArray(),
            ): Method? = methods.firstOrNull { method ->
                method.name == name && method.parameterTypes.contentEquals(parameterTypes)
            }?.apply { isAccessible = true }
        }
    }

    private companion object {
        private const val TARGET_BUFFER_ROWS = 8
        private const val MAX_REFILL_PAGES = 1
        private const val HOST_DISTANCE_ROWS = 1
        private const val SCROLL_STATE_IDLE = 0
        private const val SCROLL_STATE_SETTLING = 2
        private const val IDLE_CHECK_DELAY_MS = 80L
        private const val ACTIVE_SCROLL_TRIGGER_DELAY_MS = 120L
        private const val IDLE_WAIT_TIMEOUT_MS = 5_000L
        private const val MIN_CALLBACK_INTERVAL_MS = 1_200L
        private const val LOAD_APPLY_CHECK_DELAY_MS = 300L
        private const val LOAD_COMPLETION_TIMEOUT_MS = 15_000L
        private const val REFILL_EXHAUSTED_COOLDOWN_MS = 10_000L
        private const val RECYCLER_VIEW_ADAPTER_CLASS = "androidx.recyclerview.widget.RecyclerView\$Adapter"
        private const val GRID_LAYOUT_MANAGER_CLASS = "androidx.recyclerview.widget.GridLayoutManager"
        private const val GRID_SPAN_SIZE_LOOKUP_CLASS = "androidx.recyclerview.widget.GridLayoutManager\$SpanSizeLookup"
    }
}
