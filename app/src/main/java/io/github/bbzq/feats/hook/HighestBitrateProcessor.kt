package io.github.bbzq.feats.hook

import io.github.bbzq.feats.allMethods
import io.github.bbzq.feats.callMethod
import java.util.concurrent.atomic.AtomicBoolean

/** Request/response policy used by the single PlayView hook pipeline. */
internal class HighestBitrateProcessor(
    private val reportFailure: (String, Throwable) -> Unit,
) {
    private val forceNextHighestRequest = AtomicBoolean(false)
    private val awaitingHighestResponse = AtomicBoolean(false)

    fun requestHighestQuality() {
        forceNextHighestRequest.set(true)
    }

    fun prepareRequest(request: Any?) {
        if (request == null) return
        runCatching {
            val forceHighest = forceNextHighestRequest.getAndSet(false)
            if (forceHighest) awaitingHighestResponse.set(true)
            enableAllVideoCapabilities(request, forceHighest)
            enableAllVideoCapabilities(request.callMethod("getVod"), forceHighest)
        }.onFailure { reportFailure("request preparation failed at ${request.javaClass.name}", it) }
    }

    fun preferHighestBitrate(response: Any?): VideoStreamStats? {
        if (response == null) return null
        return runCatching {
            val videoInfo = response.callMethod("getVideoInfo")
            val vodInfo = response.callMethod("getVodInfo")
            val selectHighest = awaitingHighestResponse.getAndSet(false)
            reorderStreams(videoInfo, selectHighest)
            reorderStreams(vodInfo, selectHighest)
            readSelectedStats(vodInfo) ?: readSelectedStats(videoInfo)
        }.onFailure {
            reportFailure("response selection failed at ${response.javaClass.name}", it)
        }.getOrNull()
    }

    private fun enableAllVideoCapabilities(target: Any?, forceHighest: Boolean) {
        if (target == null) return
        invokeNumber(target, "setFnval", MAX_FNVAL.toLong())
        invokeOneArg(target, "setFourk", true)
        if (forceHighest) invokeNumber(target, "setQn", HIGHEST_QN)
    }

    private fun reorderStreams(container: Any?, selectHighest: Boolean) {
        if (container == null) return
        val original = (container.callMethod("getStreamListList") as? Iterable<*>)
            ?.filterNotNull()
            .orEmpty()
        if (original.size < 2) return

        if (selectHighest) {
            original.asSequence()
                .filter { it.callMethod("getDashVideo") != null }
                .mapNotNull { stream -> stream.callMethod("getStreamInfo")?.let { number(it, "getQuality") } }
                .maxOrNull()
                ?.let { highestQuality -> invokeNumber(container, "setQuality", highestQuality) }
        }

        // Only compare equivalent representations. Comparing AVC and AV1 bandwidth directly,
        // for example, would prefer the less efficient codec rather than the better picture.
        val replacements = original
            .groupBy(::representationKey)
            .filterKeys { it != null }
            .mapValues { (_, streams) -> streams.sortedByDescending(::bandwidth).iterator() }
        if (replacements.values.none { iterator -> iterator.hasNext() }) return

        val reordered = original.map { stream ->
            replacements[representationKey(stream)]?.next() ?: stream
        }
        if (reordered.indices.all { reordered[it] === original[it] }) return
        replaceStreamList(container, original, reordered)
    }

    private fun readSelectedStats(container: Any?): VideoStreamStats? {
        if (container == null) return null
        val selectedQuality = number(container, "getQuality")
        val streams = (container.callMethod("getStreamListList") as? Iterable<*>)
            ?.filterNotNull()
            .orEmpty()
        val stream = streams.firstOrNull { candidate ->
            val info = candidate.callMethod("getStreamInfo") ?: return@firstOrNull false
            candidate.callMethod("getDashVideo") != null &&
                (selectedQuality == null || number(info, "getQuality") == selectedQuality)
        } ?: return null
        val info = stream.callMethod("getStreamInfo") ?: return null
        val video = stream.callMethod("getDashVideo") ?: return null
        val stats = VideoStreamStats(
            quality = number(info, "getQuality") ?: selectedQuality ?: 0,
            bandwidth = number(video, "getBandwidth") ?: 0,
            width = number(video, "getWidth")?.toInt() ?: 0,
            height = number(video, "getHeight")?.toInt() ?: 0,
            codecId = number(video, "getCodecid") ?: number(video, "getCodeId") ?: 0,
            frameRate = stringValue(video, "getFrameRate", "getFrameRateValue", "getFps"),
        )
        return readAudioStats(container, stream, stats)
    }

    private fun readAudioStats(container: Any, stream: Any, stats: VideoStreamStats): VideoStreamStats {
        // Bilibili has shipped several PlayView protobuf layouts. In the common layout
        // audio is a repeated `dash_audio` field on VideoInfo/VodInfo; some versions put
        // it on the selected stream or wrap the actual DashItem in another message.
        val audio = firstListItem(
            container,
            "getDashAudioList",
            "getAudioStreamListList",
            "getAudioDashVideoList",
            "getAudioList",
            "getAudioListList",
            "getAudiosList",
        ) ?: firstValue(
            stream,
            "getDashAudio",
            "getAudioDashVideo",
            "getAudio",
        ) ?: return stats

        val dashAudio = firstValue(
            audio,
            "getDashAudio",
            "getAudioDashVideo",
            "getDashItem",
            "getAudio",
        ) ?: audio
        return stats.copy(
            audioBandwidth = firstNumber(dashAudio, "getBandwidth", "getBandWidth", "getAudioBandwidth") ?: 0,
            audioCodecId = firstNumber(dashAudio, "getCodecid", "getCodeId", "getCodecId", "getAudioCodecid") ?: 0,
            audioSampleRate = firstNumber(
                dashAudio,
                "getSampleRate",
                "getAudioSampleRate",
                "getSamplingRate",
            )?.toInt() ?: 0,
            audioChannels = firstNumber(
                dashAudio,
                "getChannels",
                "getChannelCount",
                "getAudioChannels",
            )?.toInt() ?: 0,
        )
    }

    private fun firstListItem(target: Any, vararg getters: String): Any? =
        getters.asSequence()
            .mapNotNull { getter -> target.callMethod(getter) as? Iterable<*> }
            .mapNotNull { values -> values.firstOrNull { it != null } }
            .firstOrNull()

    private fun firstValue(target: Any, vararg getters: String): Any? =
        getters.firstNotNullOfOrNull { getter -> target.callMethod(getter) }

    private fun firstNumber(target: Any, vararg getters: String): Long? =
        getters.firstNotNullOfOrNull { getter -> number(target, getter) }

    private fun representationKey(stream: Any): RepresentationKey? {
        val info = stream.callMethod("getStreamInfo") ?: return null
        val video = stream.callMethod("getDashVideo") ?: return null
        return RepresentationKey(
            quality = number(info, "getQuality") ?: return null,
            codec = number(video, "getCodecid") ?: number(video, "getCodeId") ?: 0,
            width = number(video, "getWidth") ?: 0,
            height = number(video, "getHeight") ?: 0,
            frameRate = stringValue(video, "getFrameRate", "getFrameRateValue", "getFps"),
        )
    }

    private fun bandwidth(stream: Any): Long {
        val video = stream.callMethod("getDashVideo") ?: return Long.MIN_VALUE
        return number(video, "getBandwidth") ?: Long.MIN_VALUE
    }

    private fun replaceStreamList(container: Any, original: List<Any>, reordered: List<Any>) {
        val exposed = container.callMethod("getStreamListList")
        val replacedInPlace = runCatching {
            @Suppress("UNCHECKED_CAST")
            (exposed as MutableList<Any>).apply {
                clear()
                addAll(reordered)
            }
            true
        }.getOrDefault(false)
        if (replacedInPlace) return

        val cleared = invokeNoArg(container, "clearStreamList")
        if (!cleared || !invokeOneArg(container, "addAllStreamList", reordered)) {
            // Restore when a generated protobuf exposes clear but not addAll on this version.
            if (cleared) invokeOneArg(container, "addAllStreamList", original)
        }
    }

    private fun number(target: Any, getter: String): Long? =
        (target.callMethod(getter) as? Number)?.toLong()

    private fun stringValue(target: Any, vararg getters: String): String =
        getters.firstNotNullOfOrNull { getter -> target.callMethod(getter)?.toString() }.orEmpty()

    private fun invokeNoArg(target: Any, name: String): Boolean {
        val method = target.javaClass.allMethods().firstOrNull { it.name == name && it.parameterCount == 0 }
            ?: return false
        return runCatching { method.invoke(target); true }.getOrDefault(false)
    }

    private fun invokeOneArg(target: Any, name: String, value: Any): Boolean {
        val method = target.javaClass.allMethods().firstOrNull { method ->
            method.name == name && method.parameterCount == 1 &&
                method.parameterTypes[0].let { type ->
                    type.isInstance(value) ||
                        (type == Int::class.javaPrimitiveType && value is Int) ||
                        (type == Long::class.javaPrimitiveType && value is Long) ||
                        (type == Boolean::class.javaPrimitiveType && value is Boolean)
                }
        } ?: return false
        return runCatching { method.invoke(target, value); true }.getOrDefault(false)
    }

    private fun invokeNumber(target: Any, name: String, value: Long): Boolean {
        val method = target.javaClass.allMethods().firstOrNull { method ->
            method.name == name && method.parameterCount == 1 && method.parameterTypes[0].let { type ->
                type == Int::class.javaPrimitiveType || type == Int::class.javaObjectType ||
                    type == Long::class.javaPrimitiveType || type == Long::class.javaObjectType
            }
        } ?: return false
        val converted: Any = when (method.parameterTypes[0]) {
            Int::class.javaPrimitiveType, Int::class.javaObjectType -> value.toInt()
            else -> value
        }
        return runCatching { method.invoke(target, converted); true }.getOrDefault(false)
    }

    private data class RepresentationKey(
        val quality: Long,
        val codec: Long,
        val width: Long,
        val height: Long,
        val frameRate: String,
    )

    private companion object {
        // DASH, HDR, Dolby, 8K and AV1 capability bits used by current Bilibili clients.
        const val MAX_FNVAL = 16 or 64 or 128 or 256 or 512 or 1024 or 2048
        const val HIGHEST_QN = 127L
    }
}

internal data class VideoStreamStats(
    val quality: Long,
    val bandwidth: Long,
    val width: Int,
    val height: Int,
    val codecId: Long,
    val frameRate: String,
    val audioBandwidth: Long = 0,
    val audioCodecId: Long = 0,
    val audioSampleRate: Int = 0,
    val audioChannels: Int = 0,
)
