package io.github.bbzq.feats.hook

import android.view.View
import io.github.bbzq.feats.BilibiliSponsorBlock
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object SkipVideoAdState {
    @Volatile private var activeVideoKey = ""

    private val markerStates = ConcurrentHashMap<String, TimelineMarkerState>()
    private val loadingSegmentRequests = ConcurrentHashMap.newKeySet<String>()
    private val loadedSegmentRequests = ConcurrentHashMap.newKeySet<String>()
    private val failedSegmentRequests = ConcurrentHashMap<String, Long>()

    private val controllerVideoKeys = Collections.synchronizedMap(WeakHashMap<Any, String>())
    private val viewVideoKeys = Collections.synchronizedMap(WeakHashMap<View, String>())
    private val controllerObservers = CopyOnWriteArrayList<(Any) -> Unit>()

    fun resolveVideoIdentity(bvid: String?, cid: Any?, aid: Any? = null): VideoIdentity? {
        val normalizedCid = cid.asLong()?.takeIf { it > 0L }?.toString() ?: return null
        val normalizedBvid = bvid
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: aid.asLong()?.takeIf { it > 0L }?.let(::bvidFromAid)
            ?: return null
        return VideoIdentity(normalizedBvid, normalizedCid)
    }

    fun bvidFromAid(aid: Long): String {
        val result = CharArray(12) { if (it < 3) "BV1"[it] else '0' }
        var value = ((1L shl 51) or aid) xor 23442827791579L
        var index = 11
        while (value > 0) {
            result[index--] = BV_TABLE[(value % 58).toInt()]
            value /= 58
        }
        result[3] = result[9].also { result[9] = result[3] }
        result[4] = result[7].also { result[7] = result[4] }
        return String(result)
    }

    fun activateVideo(identity: VideoIdentity): String {
        activeVideoKey = identity.key
        return identity.key
    }

    fun activeStateForDuration(durationMs: Long): TimelineMarkerState? {
        val state = activeVideoKey
            .takeIf { it.isNotBlank() }
            ?.let(markerStates::get)
            ?: return null
        if (durationMs <= 0L || state.durationMs <= 0L) return state

        val toleranceMs = maxOf(ACTIVE_DURATION_TOLERANCE_MS, state.durationMs / 100L)
        return state.takeIf {
            kotlin.math.abs(state.durationMs - durationMs) <= toleranceMs
        }
    }

    fun bindController(controller: Any?, key: String) {
        if (controller == null || key.isBlank()) return
        synchronized(controllerVideoKeys) {
            controllerVideoKeys[controller] = key
        }
        controllerObservers.forEach { observer ->
            observer(controller)
        }
    }

    fun observeControllers(observer: (Any) -> Unit) {
        controllerObservers.add(observer)
    }

    fun bindView(view: View?, key: String) {
        if (view == null || key.isBlank()) return
        synchronized(viewVideoKeys) {
            viewVideoKeys[view] = key
        }
    }

    fun keyForController(controller: Any?): String? {
        if (controller == null) return null
        return synchronized(controllerVideoKeys) {
            controllerVideoKeys[controller]
        }
    }

    fun stateForView(view: View?): TimelineMarkerState? {
        if (view == null) return null
        return synchronized(viewVideoKeys) {
            viewVideoKeys[view]
        }?.let(markerStates::get)
    }

    fun stateForKey(key: String): TimelineMarkerState? =
        key.takeIf { it.isNotBlank() }?.let(markerStates::get)

    fun updateDuration(key: String, nextDurationMs: Long): TimelineMarkerState? {
        if (key.isBlank() || nextDurationMs <= 0L) return markerStates[key]
        return updateState(key, durationMs = nextDurationMs, segments = null)
    }

    fun updateSegments(key: String, nextSegments: List<BilibiliSponsorBlock.Segment>): TimelineMarkerState {
        return updateState(key, durationMs = null, segments = nextSegments)
    }

    fun requestSegmentsIfMissing(
        identity: VideoIdentity,
        enabledCategories: Set<String>,
        log: (String, Throwable?) -> Unit,
    ) {
        if (enabledCategories.isEmpty()) return

        val requestKey = buildRequestKey(identity, enabledCategories)
        if (requestKey in loadedSegmentRequests) return

        val failedAt = failedSegmentRequests[requestKey]
        if (failedAt != null && System.currentTimeMillis() - failedAt < FAILED_RETRY_DELAY_MS) return
        if (!loadingSegmentRequests.add(requestKey)) return

        Thread {
            try {
                var result = BilibiliSponsorBlock.FetchResult(
                    status = BilibiliSponsorBlock.FetchStatus.FAILED,
                    segments = emptyList(),
                )
                for (attempt in 0 until FETCH_RETRY_COUNT) {
                    result = BilibiliSponsorBlock(identity.bvid, identity.cid, enabledCategories).getSegments()
                    if (result.status != BilibiliSponsorBlock.FetchStatus.FAILED) break
                    if (attempt < FETCH_RETRY_COUNT - 1) Thread.sleep(FETCH_RETRY_DELAY_MS)
                }

                if (result.status == BilibiliSponsorBlock.FetchStatus.FAILED) {
                    failedSegmentRequests[requestKey] = System.currentTimeMillis()
                    log("SkipVideoAd marker segment fetch failed for ${identity.key}", null)
                } else {
                    failedSegmentRequests.remove(requestKey)
                    loadedSegmentRequests.add(requestKey)
                    updateSegments(identity.key, result.segments)
                    if (result.segments.isNotEmpty()) {
                        log("SkipVideoAd marker loaded ${result.segments.size} segment(s) for ${identity.key}", null)
                    }
                }
            } catch (throwable: Throwable) {
                failedSegmentRequests[requestKey] = System.currentTimeMillis()
                log("SkipVideoAd marker segment fetch crashed for ${identity.key}", throwable)
            } finally {
                loadingSegmentRequests.remove(requestKey)
            }
        }.apply {
            name = "BBZQ-SkipVideoAdMarker"
            isDaemon = true
            start()
        }
    }

    private fun updateState(
        key: String,
        durationMs: Long?,
        segments: List<BilibiliSponsorBlock.Segment>?,
    ): TimelineMarkerState {
        val current = markerStates[key]
        val next = TimelineMarkerState(
            key = key,
            durationMs = durationMs?.takeIf { it > 0L } ?: current?.durationMs ?: 0L,
            segments = segments ?: current?.segments ?: emptyList(),
        )
        markerStates[key] = next
        return next
    }

    private fun buildRequestKey(identity: VideoIdentity, enabledCategories: Set<String>): String =
        identity.key + "|" + enabledCategories.sorted().joinToString(",")

    private fun Any?.asLong(): Long? = when (this) {
        is Number -> toLong()
        is String -> toLongOrNull()
        else -> null
    }

    data class VideoIdentity(
        val bvid: String,
        val cid: String,
    ) {
        val key: String = "cid:$cid"
    }

    data class TimelineMarkerState(
        val key: String,
        val durationMs: Long,
        val segments: List<BilibiliSponsorBlock.Segment>,
    )

    private const val FETCH_RETRY_COUNT = 3
    private const val FETCH_RETRY_DELAY_MS = 1000L
    private const val FAILED_RETRY_DELAY_MS = 60_000L
    private const val ACTIVE_DURATION_TOLERANCE_MS = 1000L
    private const val BV_TABLE = "FcwAPNKTMug3GV5Lj7EJnHpWsx4tb8haYeviqBz6rkCy12mUSDQX9RdoZf"
}
