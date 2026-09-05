package io.github.bbzq.feats.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

object MediaMuxerUtil {

    private const val TAG = "BBZQ_Muxer"

    /**
     * 合并视频和音频流到一个 MP4 文件中。
     *
     * @param videoPath 视频文件路径 (m4s)
     * @param audioPath 音频文件路径 (m4s)
     * @param outPath   输出文件路径 (mp4)
     * @return 成功返回 true，失败返回 false
     */
    fun mux(videoPath: String, audioPath: String, outPath: String): Boolean {
        val videoFile = File(videoPath)
        val audioFile = File(audioPath)
        if (!videoFile.isFile || videoFile.length() <= 0L ||
            !audioFile.isFile || audioFile.length() <= 0L
        ) {
            Log.e(
                TAG,
                "mux: Input files missing or empty. " +
                    "video=${videoFile.isFile} (${videoFile.length()} bytes), " +
                    "audio=${audioFile.isFile} (${audioFile.length()} bytes)",
            )
            return false
        }

        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var temporaryOutput: File? = null

        try {
            videoExtractor = MediaExtractor().apply { setDataSource(videoPath) }
            var videoTrackIndex = -1
            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) {
                    videoExtractor.selectTrack(i)
                    videoTrackIndex = i
                    break
                }
            }

            audioExtractor = MediaExtractor().apply { setDataSource(audioPath) }
            var audioTrackIndex = -1
            for (i in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioExtractor.selectTrack(i)
                    audioTrackIndex = i
                    break
                }
            }

            if (videoTrackIndex == -1 || audioTrackIndex == -1) {
                Log.e(TAG, "mux: Video or audio track not found. videoTrack=$videoTrackIndex, audioTrack=$audioTrackIndex")
                return false
            }

            val videoFormat = videoExtractor.getTrackFormat(videoTrackIndex)
            val audioFormat = audioExtractor.getTrackFormat(audioTrackIndex)

            val outFile = File(outPath)
            temporaryOutput = DownloadFileOutput.createTemporary(outFile)
            if (!temporaryOutput!!.delete()) {
                throw IOException("Unable to prepare temporary mux output: ${temporaryOutput!!.absolutePath}")
            }

            muxer = MediaMuxer(temporaryOutput!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outVideoTrackIndex = muxer.addTrack(videoFormat)
            val outAudioTrackIndex = muxer.addTrack(audioFormat)

            muxer.start()
            muxerStarted = true

            val maxBufferSize = 2 * 1024 * 1024 // 2MB buffer
            val videoBuffer = ByteBuffer.allocateDirect(maxBufferSize)
            val audioBuffer = ByteBuffer.allocateDirect(maxBufferSize)
            val videoBufferInfo = MediaCodec.BufferInfo()
            val audioBufferInfo = MediaCodec.BufferInfo()

            var videoEos = false
            var audioEos = false

            var lastVideoPts = -1L
            var lastAudioPts = -1L
            var videoSamplesWritten = 0
            var audioSamplesWritten = 0

            while (!videoEos || !audioEos) {
                val videoPts = if (!videoEos) videoExtractor.sampleTime else Long.MAX_VALUE
                val audioPts = if (!audioEos) audioExtractor.sampleTime else Long.MAX_VALUE

                if (!videoEos && (audioEos || videoPts <= audioPts)) {
                    val sampleSize = videoExtractor.readSampleData(videoBuffer, 0)
                    if (sampleSize < 0) {
                        videoEos = true
                    } else {
                        val samplePts = videoExtractor.sampleTime
                        if (samplePts >= lastVideoPts) {
                            videoBufferInfo.offset = 0
                            videoBufferInfo.size = sampleSize
                            videoBufferInfo.presentationTimeUs = samplePts
                            videoBufferInfo.flags = videoExtractor.sampleFlags
                            muxer.writeSampleData(outVideoTrackIndex, videoBuffer, videoBufferInfo)
                            lastVideoPts = samplePts
                            videoSamplesWritten++
                        }
                        videoExtractor.advance()
                    }
                } else if (!audioEos) {
                    val sampleSize = audioExtractor.readSampleData(audioBuffer, 0)
                    if (sampleSize < 0) {
                        audioEos = true
                    } else {
                        val samplePts = audioExtractor.sampleTime
                        if (samplePts >= lastAudioPts) {
                            audioBufferInfo.offset = 0
                            audioBufferInfo.size = sampleSize
                            audioBufferInfo.presentationTimeUs = samplePts
                            audioBufferInfo.flags = audioExtractor.sampleFlags
                            muxer.writeSampleData(outAudioTrackIndex, audioBuffer, audioBufferInfo)
                            lastAudioPts = samplePts
                            audioSamplesWritten++
                        }
                        audioExtractor.advance()
                    }
                }
            }

            if (videoSamplesWritten == 0 || audioSamplesWritten == 0) {
                throw IOException(
                    "No samples written: video=$videoSamplesWritten audio=$audioSamplesWritten",
                )
            }

            // Stop and release before publishing the output. A stop failure must make
            // the operation fail and must never replace an existing successful file.
            muxer.stop()
            muxerStarted = false
            muxer.release()
            muxer = null

            DownloadFileOutput.commit(requireNotNull(temporaryOutput), outFile)
            temporaryOutput = null
            Log.i(TAG, "mux succeeded: $outPath")
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "mux failed with exception", e)
            return false
        } finally {
            runCatching { videoExtractor?.release() }
            runCatching { audioExtractor?.release() }
            if (muxerStarted) {
                runCatching { muxer?.stop() }
                muxerStarted = false
            }
            runCatching { muxer?.release() }
            runCatching { temporaryOutput?.let { if (it.exists()) it.delete() } }
        }
    }
}
