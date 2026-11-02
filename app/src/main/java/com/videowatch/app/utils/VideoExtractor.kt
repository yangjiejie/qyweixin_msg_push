package com.videowatch.app.utils

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * 视频片段截取工具。
 *
 * 为保证“视频的第一帧就是 A 点”（严谨的 A~B 帧间视频），视频轨道采用【重新编码】
 * （decode → Surface 编码）。
 * 时间戳方案：解码线程每渲染一帧就把 (原始 pts - startUs) 入队（FIFO）；
 * 编码线程每产出一帧就从队头取一个 PTS 写盘。
 * 这样【完全不依赖 Surface 编码器对输入时间戳的处理】（很多机型会忽略或重设基准，
 * 直接读 encInfo.presentationTimeUs 会算错起点导致偏移），又借由 Surface 编码
 * 内部做色彩转换，保证产出的 MP4 可正常播放、能被相册生成缩略图。
 * 音频轨道直接混流（无关键帧问题），保持无损。
 * 若重新编码失败或超时，自动退化为按原样混流（可能首帧非关键帧）。
 */
object VideoExtractor {

    interface Callback {
        /** @param note 附加说明 */
        fun onSuccess(file: File, note: String) {}
        fun onError(message: String) {}
    }

    private const val TAG = "VideoExtractor"
    private const val SAMPLE_BUFFER = 8 * 1024 * 1024 // 8MB，覆盖高码率大帧
    private const val TIMEOUT_US = 10000L
    private val EOS = MediaCodec.BUFFER_FLAG_END_OF_STREAM
    private val CODEC_CONFIG = MediaCodec.BUFFER_FLAG_CODEC_CONFIG

    private data class AudioSample(val data: ByteArray, val ptsUs: Long, val flags: Int)

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
        try {
            clipReencode(inputPath, startMs, endMs, outputFile, callback)
        } catch (e: Exception) {
            Log.w(TAG, "重新编码截取失败，退化为混流: ${e.message}", e)
            try { outputFile.delete() } catch (_: Exception) {}
            try {
                clipRemux(inputPath, startMs, endMs, outputFile, callback)
            } catch (e2: Exception) {
                Log.e(TAG, "clipRemux failed", e2)
                try { outputFile.delete() } catch (_: Exception) {}
                callback.onError("截取视频失败: ${e2.message}")
            }
        }
    }

    /**
     * 视频重编码（Surface 编码 + FIFO 时间戳）：
     * - 解码线程：只把 pts >= A 的帧渲染到编码器输入 Surface，并把 (pts - A) 入队。
     * - 编码线程：持续抽取编码输出，按 FIFO 出队得到精确 PTS 并写入 muxer（含音频）。
     * - 两线程并发，避免 Surface 缓冲队列填满导致死锁。
     * - 解码结束后调用 signalEndOfInputStream 通知编码器收尾。
     * - 带超时保护：超预算仍卡住则抛异常，由 clip() 退化为混流。
     */
    private fun clipReencode(
        inputPath: String,
        startMs: Long,
        endMs: Long,
        outputFile: File,
        callback: Callback
    ) {
        val extractor = MediaExtractor()
        extractor.setDataSource(inputPath)
        val startUs = startMs * 1000
        val endUs = endMs * 1000
        val segmentUs = endUs - startUs

        var videoIdx = -1
        var audioIdx = -1
        var videoFormat: MediaFormat? = null
        var audioFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("video/") && videoIdx < 0) {
                videoIdx = i; videoFormat = f
            } else if (mime.startsWith("audio/") && audioIdx < 0) {
                audioIdx = i; audioFormat = f
            }
        }
        if (videoIdx < 0 || videoFormat == null) throw RuntimeException("无视频轨道")

        // ---- 音频：提前读入内存（后续混流） ----
        val audioSamples = ArrayList<AudioSample>()
        if (audioIdx >= 0 && audioFormat != null) {
            extractor.selectTrack(audioIdx)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val buf = ByteBuffer.allocate(SAMPLE_BUFFER)
            while (true) {
                val size = extractor.readSampleData(buf, 0)
                if (size < 0) break
                val pts = extractor.sampleTime
                if (pts > endUs) break
                if (pts >= startUs) {
                    buf.position(0); buf.limit(size)
                    val data = ByteArray(size); buf.get(data)
                    audioSamples.add(AudioSample(data, pts - startUs, extractor.sampleFlags))
                }
                if (!extractor.advance()) break
            }
            extractor.unselectTrack(audioIdx)
        }

        // ---- 视频：Surface 重编码 ----
        val videoMime = videoFormat!!.getString(MediaFormat.KEY_MIME)!!
        val width = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
        val height = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
        val frameRate = if (videoFormat.containsKey(MediaFormat.KEY_FRAME_RATE))
            videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE) else 30

        // 分辨率封顶 1080p：超过则等比缩小（解码渲染到编码器输入 Surface 时自动缩放），
        // 保证输出在所有手机相册都能解码播放（4K 源在很多相册会提示“分辨率/帧率过高”拒播）。
        var encW = width
        var encH = height
        if (maxOf(encW, encH) > 1920) {
            val scale = 1920.0 / maxOf(encW, encH)
            encW = (encW * scale).toInt() / 2 * 2
            encH = (encH * scale).toInt() / 2 * 2
        }
        val encFps = minOf(frameRate, 60)
        val bitrate = if (videoFormat.containsKey(MediaFormat.KEY_BIT_RATE))
            videoFormat.getInteger(MediaFormat.KEY_BIT_RATE)
        else
            (encW * encH * encFps * 0.25).toInt().coerceIn(2_000_000, 30_000_000)

        fun buildEncFormat(strict: Boolean): MediaFormat =
            MediaFormat.createVideoFormat(videoMime, encW, encH).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, encFps)
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                )
                if (strict) {
                    // 关闭 B 帧：保证编码输出顺序 == 渲染入队顺序，FIFO 时间戳才能
                    // 一帧对一帧；顺序错乱会使时间戳非单调，产出损坏的 MP4
                    setInteger("max-bframes", 0)
                    if (videoMime == MediaFormat.MIMETYPE_VIDEO_AVC) {
                        setInteger(
                            MediaFormat.KEY_PROFILE,
                            MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                        )
                    }
                }
            }

        val encoder = MediaCodec.createEncoderByType(videoMime)
        try {
            encoder.configure(buildEncFormat(true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            // 个别编码器不接受 Baseline/无 B 帧配置：回退默认配置重试
            encoder.reset()
            encoder.configure(buildEncFormat(false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        val inputSurface = encoder.createInputSurface()
        encoder.start()

        val decoder = MediaCodec.createDecoderByType(videoMime)
        decoder.configure(videoFormat, inputSurface, null, 0)
        decoder.start()

        val muxer = MediaMuxer(
            outputFile.absolutePath,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )
        var videoMuxerTrack = -1
        var audioMuxerTrack = -1
        var muxerStarted = false
        val failed = AtomicReference<Throwable?>(null)

        extractor.selectTrack(videoIdx)
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        val decBuf = ByteBuffer.allocate(SAMPLE_BUFFER)
        val decInfo = MediaCodec.BufferInfo()
        val encInfo = MediaCodec.BufferInfo()
        val aInfo = MediaCodec.BufferInfo()

        // 超时预算：按段长放大，并设上下限，防止无限卡死
        val timeoutMs = ((endMs - startMs) * 4 + 15000L).coerceIn(20000L, 90000L)

        // 每渲染一帧，记录其输出 PTS（= 原始 pts - startUs），供编码线程按序取用
        val ptsQueue = ConcurrentLinkedQueue<Long>()
        // 视频写出统计与单调性保护（仅编码线程写入，get() 返回后读取安全）
        var lastVideoPts = -1L
        var videoSampleCount = 0

        val pool = Executors.newFixedThreadPool(2)

        // 解码线程：喂帧 + 仅渲染 >= A 的帧到编码器 Surface，并把 PTS 入队
        val decoderTask = pool.submit {
            try {
                var decEosInput = false
                while (true) {
                    if (!decEosInput) {
                        val inIdx = decoder.dequeueInputBuffer(TIMEOUT_US)
                        if (inIdx >= 0) {
                            val size = extractor.readSampleData(decBuf, 0)
                            if (size < 0 || extractor.sampleTime > endUs) {
                                decoder.queueInputBuffer(
                                    inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                decEosInput = true
                            } else {
                                val pts = extractor.sampleTime
                                val flags = extractor.sampleFlags
                                decBuf.position(0); decBuf.limit(size)
                                decoder.queueInputBuffer(inIdx, 0, size, pts, flags)
                                extractor.advance()
                            }
                        }
                    }
                    val outIdx = decoder.dequeueOutputBuffer(decInfo, TIMEOUT_US)
                    if (outIdx >= 0) {
                        val isEos = (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        if (isEos) {
                            // EOS 标记缓冲不是真实帧：绝不能渲染给编码器，
                            // 否则会注入时间戳无效的幽灵帧，破坏时间戳单调性导致文件损坏
                            decoder.releaseOutputBuffer(outIdx, false)
                            break
                        } else if (decInfo.presentationTimeUs >= startUs) {
                            // 入队该帧输出 PTS，再渲染到编码器输入 Surface
                            ptsQueue.add(decInfo.presentationTimeUs - startUs)
                            decoder.releaseOutputBuffer(outIdx, decInfo.presentationTimeUs * 1000)
                        } else {
                            decoder.releaseOutputBuffer(outIdx, false)
                        }
                    } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // Surface 解码无需处理
                    }
                }
                // 关键：Surface 输入型编码器必须显式 signalEndOfInputStream，
                // 否则它收不到流结束信号，永远不会吐出尾部/EOS，导致编码线程卡死。
                try { encoder.signalEndOfInputStream() } catch (_: Exception) {}
            } catch (e: Exception) {
                failed.set(e)
            }
        }

        // 编码线程：持续抽取编码输出并写入 muxer（含音频）
        val encoderTask = pool.submit {
            try {
                while (true) {
                    val encIdx = encoder.dequeueOutputBuffer(encInfo, 5000)
                    if (encIdx == MediaCodec.INFO_TRY_AGAIN_LATER) continue
                    if (encIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (videoMuxerTrack < 0) {
                            videoMuxerTrack = muxer.addTrack(encoder.outputFormat)
                            if (audioIdx >= 0 && audioFormat != null && audioSamples.isNotEmpty()) {
                                audioMuxerTrack = muxer.addTrack(audioFormat)
                            }
                            muxer.start()
                            muxerStarted = true
                            // 写音频（已全部读入内存）
                            if (audioMuxerTrack >= 0) {
                                for (s in audioSamples) {
                                    aInfo.set(0, s.data.size, s.ptsUs, s.flags)
                                    muxer.writeSampleData(
                                        audioMuxerTrack, ByteBuffer.wrap(s.data), aInfo
                                    )
                                }
                            }
                        }
                        continue
                    }
                    if (encIdx >= 0) {
                        val flags = encInfo.flags
                        if (muxerStarted && videoMuxerTrack >= 0 &&
                            (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            // 从 FIFO 取出该帧精确输出 PTS（不依赖编码器自身时间戳）
                            val outPts0 = ptsQueue.poll()
                            if (outPts0 != null && outPts0 in 0..segmentUs) {
                                // MediaMuxer 要求同轨时间戳严格递增，强制单调
                                val outPts = if (outPts0 <= lastVideoPts) lastVideoPts + 1 else outPts0
                                lastVideoPts = outPts
                                videoSampleCount++
                                val data = encoder.getOutputBuffer(encIdx)!!
                                data.position(0); data.limit(encInfo.size)
                                encInfo.set(0, encInfo.size, outPts, flags)
                                muxer.writeSampleData(videoMuxerTrack, data, encInfo)
                            }
                        }
                        encoder.releaseOutputBuffer(encIdx, false)
                        if ((flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                }
            } catch (e: Exception) {
                failed.set(e)
            }
        }

        var muxerOk = false
        try {
            decoderTask.get(timeoutMs, TimeUnit.MILLISECONDS)
            encoderTask.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            throw RuntimeException("重新编码超时（已回退混流）", e)
        } finally {
            try { pool.shutdownNow() } catch (_: Exception) {}
            // 收尾：必须 muxer.stop() 才会把 moov 写盘，文件才完整可播。
            // stop 失败说明轨道/时间戳数据异常，必须按失败处理，绝不能报成功。
            muxerOk = try {
                if (muxerStarted) { muxer.stop(); true } else false
            } catch (_: Exception) { false }
            try { muxer.release() } catch (_: Exception) {}
            try { decoder.stop(); decoder.release() } catch (_: Exception) {}
            try { encoder.stop(); encoder.release() } catch (_: Exception) {}
            try { inputSurface.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }

        if (failed.get() != null) throw failed.get()!!
        if (!muxerOk || videoSampleCount == 0) {
            try { outputFile.delete() } catch (_: Exception) {}
            throw RuntimeException("重编码输出异常（转用混流兜底）")
        }

        callback.onSuccess(
            outputFile,
            if (encW != width || encH != height)
                "已精确截取 A~B 帧间视频（重编码并降至 ${encW}x${encH}，首帧精确为 A 点）"
            else
                "已精确截取 A~B 帧间视频（视频重编码，首帧精确为 A 点）"
        )
    }

    /** 原样混流（无重编码）。首帧可能不是关键帧。作为重编码失败的兜底。 */
    private fun clipRemux(
        inputPath: String,
        startMs: Long,
        endMs: Long,
        outputFile: File,
        callback: Callback
    ) {
        val extractor = MediaExtractor()
        extractor.setDataSource(inputPath)
        val startUs = startMs * 1000
        val endUs = endMs * 1000
        val trackIndices = ArrayList<Int>()
        val formats = ArrayList<MediaFormat>()
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                trackIndices.add(i); formats.add(f)
            }
        }
        if (trackIndices.isEmpty()) {
            callback.onError("该视频没有可视/音频轨道，无法截取")
            return
        }
        val muxer = MediaMuxer(
            outputFile.absolutePath,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )
        val muxerTrack = IntArray(trackIndices.size)
        for (i in trackIndices.indices) muxerTrack[i] = muxer.addTrack(formats[i])
        muxer.start()
        val buffer = ByteBuffer.allocate(SAMPLE_BUFFER)
        val info = MediaCodec.BufferInfo()
        val trackShift = LongArray(trackIndices.size) { -1L }
        try {
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
                        if (first) { trackShift[t] = pts; first = false }
                        buffer.position(0); buffer.limit(size)
                        val outPts = if (trackShift[t] >= 0) pts - trackShift[t] else pts
                        info.set(0, size, outPts, extractor.sampleFlags)
                        muxer.writeSampleData(muxerTrack[t], buffer, info)
                    }
                    if (!extractor.advance()) break
                }
                extractor.unselectTrack(idx)
            }
            callback.onSuccess(outputFile, "视频片段已保存（混流）")
        } finally {
            try { extractor.release() } catch (_: Exception) {}
            try { muxer.stop() } catch (_: Exception) {}
            try { muxer.release() } catch (_: Exception) {}
        }
    }
}
