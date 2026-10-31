package com.videowatch.app.utils

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * 视频片段截取工具：把视频中 A~B 区间的一段（含音轨）直接混流为 MP4，不重新编码，速度快。
 * 视频从 A 点之前最近的同步帧开始，以保证截取后的文件能正常解码播放。
 */
object VideoExtractor {

    interface Callback {
        /** @param note 附加说明 */
        fun onSuccess(file: File, note: String) {}
        fun onError(message: String) {}
    }

    private const val TAG = "VideoExtractor"
    private const val SAMPLE_BUFFER = 8 * 1024 * 1024 // 8MB，覆盖高码率大帧

    /**
     * 截取 [startMs, endMs] 区间的视频片段。
     * @param outputFile 输出的 MP4 文件
     */
    fun clip(
        inputPath: String,
        startMs: Long,
        endMs: Long,
        outputFile: File,
        callback: Callback
    ) {
        if (endMs <= startMs) {
            callback.onError("结束时间必须大于开始时间")
            return
        }
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(inputPath)

            val trackCount = extractor.trackCount
            val trackIndices = ArrayList<Int>()
            val formats = ArrayList<MediaFormat>()
            for (i in 0 until trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    trackIndices.add(i)
                    formats.add(f)
                }
            }
            if (trackIndices.isEmpty()) {
                callback.onError("该视频没有可视/音频轨道，无法截取")
                return
            }

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrack = IntArray(trackIndices.size)
            for (i in trackIndices.indices) {
                muxerTrack[i] = muxer.addTrack(formats[i])
            }
            muxer.start()

            val startUs = startMs * 1000
            val endUs = endMs * 1000
            val buffer = ByteBuffer.allocate(SAMPLE_BUFFER)
            val info = MediaCodec.BufferInfo()
            val trackShift = LongArray(trackIndices.size) { -1L }

            for (t in trackIndices.indices) {
                val idx = trackIndices[t]
                extractor.selectTrack(idx)
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                var first = true
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    val pts = extractor.sampleTime
                    if (pts > endUs) break
                    if (pts >= startUs) {
                        if (first) {
                            trackShift[t] = pts
                            first = false
                        }
                        buffer.position(0)
                        buffer.limit(size)
                        val outPts = if (trackShift[t] >= 0) pts - trackShift[t] else pts
                        info.set(0, size, outPts, extractor.sampleFlags)
                        muxer.writeSampleData(muxerTrack[t], buffer, info)
                    }
                    if (!extractor.advance()) break
                }
                extractor.unselectTrack(idx)
            }

            callback.onSuccess(outputFile, "视频片段已保存")
        } catch (e: Exception) {
            Log.e(TAG, "clip failed", e)
            try { outputFile.delete() } catch (_: Exception) {}
            callback.onError("截取视频失败: ${e.message}")
        } finally {
            try { extractor?.release() } catch (_: Exception) {}
            try { muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }
}
