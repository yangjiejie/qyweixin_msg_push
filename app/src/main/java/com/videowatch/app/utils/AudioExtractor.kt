package com.videowatch.app.utils

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * 音频截取工具：把视频中 A~B 区间的一段声音保存为 OGG / WAV / MP3。
 *
 * OGG ：源为 Opus/Vorbis/FLAC 时直接混流（无损）；否则解码后重编码为 Opus 再封装 OGG。
 * WAV ：解码为 PCM 后写入标准 WAV 容器（16bit / 32bit float 自动适配），无损但体积大。
 * MP3 ：源本身是 MP3 时直接抽取帧（无损）；否则用设备 MP3 编码器重编码；
 *       设备没有 MP3 编码器时（多数 Android 都如此）退化为 AAC 并保存为 M4A，并明确提示。
 * 若设备连 OGG 都不可用，则按源编码退化为 m4a / mp3 / webm。
 */
object AudioExtractor {

    /** 输出格式 */
    enum class OutFormat { OGG, WAV, MP3 }

    /** 容器类型：OGG=Muxer(OGG)，MPEG4=Muxer(MP4)，RAW=裸码流 */
    private enum class Container { OGG, MPEG4, RAW }

    interface Callback {
        /** @param note 附加说明，例如“MP3（原音轨直接抽取）” */
        fun onSuccess(file: File, note: String) {}
        fun onError(message: String) {}
    }

    private const val TAG = "AudioExtractor"
    private const val TIMEOUT_US = 10_000L
    private const val BIT_RATE = 128_000

    /** OGG 容器可直接封装的音频编码 */
    private val OGG_DIRECT_CODECS = setOf("audio/opus", "audio/vorbis", "audio/flac")

    /** OGG 重编码候选编码器（按优先级） */
    private val OGG_ENCODER_CANDIDATES = listOf("audio/opus", "audio/flac", "audio/vorbis")

    private const val MIME_MP3 = "audio/mpeg"
    private const val MIME_AAC = MediaFormat.MIMETYPE_AUDIO_AAC

    /** PCM 数据块（来自解码器输出） */
    private class PcmChunk(val data: ByteArray)

    /** 一段解码后的 PCM 数据 */
    private class PcmResult(
        val chunks: List<PcmChunk>,
        val sampleRate: Int,
        val channelCount: Int,
        val bytesPerSample: Int
    )

    /**
     * 截取音频。
     * @param outputFile 输出文件（降级时扩展名会按实际格式调整）
     */
    fun extract(
        inputPath: String,
        startMs: Long,
        endMs: Long,
        outputFile: File,
        format: OutFormat,
        callback: Callback
    ) {
        var extractor: MediaExtractor? = null
        try {
            if (endMs <= startMs) {
                callback.onError("结束时间必须大于开始时间")
                return
            }
            extractor = MediaExtractor()
            extractor.setDataSource(inputPath)

            val trackIndex = (0 until extractor.trackCount).firstOrNull { t ->
                extractor.getTrackFormat(t)
                    .getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: run {
                callback.onError("该视频没有音频轨，无法截取")
                return
            }

            val srcFormat = extractor.getTrackFormat(trackIndex)
            val srcMime = srcFormat.getString(MediaFormat.KEY_MIME) ?: ""
            extractor.selectTrack(trackIndex)

            val startUs = startMs * 1000
            val endUs = endMs * 1000
            val canOgg = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

            when (format) {
                OutFormat.WAV -> {
                    val pcm = decodeToPcm(extractor, srcFormat, startUs, endUs)
                    if (pcm == null) {
                        outputFile.delete()
                        callback.onError("截取区间内没有音频数据")
                        return
                    }
                    writeWav(pcm, outputFile)
                    callback.onSuccess(outputFile, "WAV 无损音频")
                }

                OutFormat.MP3 -> {
                    when {
                        // 源音轨本身就是 MP3：直接抽取 MP3 帧，不重编码
                        isMp3Mime(srcMime) -> {
                            writeRawFrames(extractor, startUs, endUs, outputFile, callback,
                                "MP3（原音轨直接抽取，无损）")
                        }
                        // 设备带 MP3 编码器：解码后重编码为 MP3 裸码流
                        findEncoder(MIME_MP3) != null -> {
                            val pcm = decodeToPcm(extractor, srcFormat, startUs, endUs)
                            if (pcm == null) {
                                outputFile.delete()
                                callback.onError("截取区间内没有音频数据")
                                return
                            }
                            encodePcm(pcm, MIME_MP3, outputFile, Container.RAW,
                                "MP3（已重编码）", callback)
                        }
                        // 无 MP3 编码器：退化为 AAC(M4A)，明确告知
                        findEncoder(MIME_AAC) != null -> {
                            val pcm = decodeToPcm(extractor, srcFormat, startUs, endUs)
                            if (pcm == null) {
                                outputFile.delete()
                                callback.onError("截取区间内没有音频数据")
                                return
                            }
                            val target = File(
                                outputFile.parentFile,
                                outputFile.nameWithoutExtension + ".m4a"
                            )
                            target.delete()
                            encodePcm(pcm, MIME_AAC, target, Container.MPEG4,
                                "当前设备不支持 MP3 编码，已保存为 M4A", callback)
                        }
                        else -> callback.onError("当前设备不支持 MP3 编码，也没有可用的 AAC 编码器")
                    }
                }

                OutFormat.OGG -> when {
                    canOgg && srcMime in OGG_DIRECT_CODECS ->
                        remuxToOgg(extractor, srcFormat, startUs, endUs, outputFile, callback)

                    else -> {
                        val encoderMime = if (canOgg) findOggEncoder() else null
                        if (encoderMime != null) {
                            transcodeToOgg(extractor, srcFormat, startUs, endUs, outputFile, encoderMime, callback)
                        } else {
                            fallbackSave(extractor, srcMime, startUs, endUs, outputFile, callback)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "extract failed", e)
            outputFile.delete()
            callback.onError("音频截取失败: ${e.message}")
        } finally {
            try { extractor?.release() } catch (_: Exception) {}
        }
    }

    private fun isMp3Mime(mime: String): Boolean =
        mime == MIME_MP3 || mime.equals("audio/mp3", true) ||
                (mime.startsWith("audio/mpeg") && !mime.contains("4"))

    // ==================== 解码为 PCM ====================

    /** 将 [startUs, endUs] 区间解码为 PCM；区间内无数据返回 null */
    private fun decodeToPcm(
        extractor: MediaExtractor,
        srcFormat: MediaFormat,
        startUs: Long,
        endUs: Long
    ): PcmResult? {
        val srcMime = srcFormat.getString(MediaFormat.KEY_MIME)
            ?: throw IllegalArgumentException("无法识别音频编码")

        val decoder = MediaCodec.createDecoderByType(srcMime)
        var sampleRate = 44100
        var channelCount = 2
        var bytesPerSample = 2
        try {
            decoder.configure(srcFormat, null, null, 0)
            decoder.start()
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val chunks = ArrayList<PcmChunk>()
            var inputDone = false
            var sawOutputEos = false
            var tryAgainCount = 0
            val info = MediaCodec.BufferInfo()

            while (!sawOutputEos) {
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuf = decoder.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0 || extractor.sampleTime > endUs) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex >= 0 -> {
                        tryAgainCount = 0
                        val outBuf = decoder.getOutputBuffer(outIndex)
                        if (outBuf != null && info.size > 0 &&
                            info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        ) {
                            val pts = info.presentationTimeUs
                            if (pts >= startUs && pts <= endUs) {
                                val data = ByteArray(info.size)
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                outBuf.get(data)
                                chunks.add(PcmChunk(data))
                            }
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                        decoder.releaseOutputBuffer(outIndex, false)
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = decoder.outputFormat
                        if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channelCount = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            bytesPerSample = if (
                                f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                            ) 4 else 2
                        }
                    }
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone -> {
                        if (++tryAgainCount > 100) sawOutputEos = true
                    }
                }
            }

            if (chunks.isEmpty()) return null
            return PcmResult(chunks, sampleRate, channelCount, bytesPerSample)
        } finally {
            try { decoder.stop() } catch (_: Exception) {}
            try { decoder.release() } catch (_: Exception) {}
        }
    }

    // ==================== PCM 编码输出 ====================

    /**
     * 把 PCM 编码为指定编码并输出：
     * - Container.OGG   : MediaMuxer(OGG)，Opus 时补 OpusHead
     * - Container.MPEG4 : MediaMuxer(MP4)，直接用编码器输出格式（含 AAC csd）
     * - Container.RAW   : 裸码流直接写文件（MP3 不需要容器）
     */
    private fun encodePcm(
        pcm: PcmResult,
        encoderMime: String,
        outputFile: File,
        container: Container,
        note: String,
        callback: Callback
    ) {
        val encoder = MediaCodec.createEncoderByType(encoderMime)
        var muxer: MediaMuxer? = null
        var rawOut: OutputStream? = null
        try {
            val sampleRate = pcm.sampleRate
            val channelCount = pcm.channelCount
            val bytesPerSample = pcm.bytesPerSample
            val pcmEncoding = if (bytesPerSample == 4) {
                AudioFormat.ENCODING_PCM_FLOAT
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }
            val encFormat = MediaFormat.createAudioFormat(encoderMime, sampleRate, channelCount)
            encFormat.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            encFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, pcmEncoding)
            if (encoderMime == MIME_AAC) {
                encFormat.setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC
                )
            }

            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            if (container == Container.OGG) {
                muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
            } else if (container == Container.MPEG4) {
                muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            } else {
                rawOut = FileOutputStream(outputFile)
            }

            var muxerStarted = false
            var trackIndex = -1
            var chunkIdx = 0
            var chunkOffset = 0
            var inputEos = false
            var encodeDone = false
            var written = false
            var tryAgainCount = 0
            val frameBytes = bytesPerSample * channelCount
            var totalSamples = 0L
            val info = MediaCodec.BufferInfo()

            fun ensureTrack() {
                if (muxerStarted || muxer == null) return
                trackIndex = if (container == Container.OGG) {
                    muxer.addTrack(buildOpusTrackFormat(encoderMime, sampleRate, channelCount))
                } else {
                    muxer.addTrack(encoder.outputFormat)
                }
                muxer.start()
                muxerStarted = true
            }

            while (!encodeDone) {
                if (!inputEos) {
                    val inIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuf = encoder.getInputBuffer(inIndex)!!
                        inBuf.clear()
                        if (chunkIdx < pcm.chunks.size) {
                            val chunk = pcm.chunks[chunkIdx]
                            val remaining = chunk.data.size - chunkOffset
                            val capacity = inBuf.capacity() / frameBytes * frameBytes
                            val n = minOf(remaining, capacity)
                            val ptsUs = totalSamples * 1_000_000L / sampleRate
                            inBuf.put(chunk.data, chunkOffset, n)
                            totalSamples += n / frameBytes
                            chunkOffset += n
                            if (chunkOffset >= chunk.data.size) {
                                chunkIdx++
                                chunkOffset = 0
                            }
                            encoder.queueInputBuffer(inIndex, 0, n, ptsUs, 0)
                        } else {
                            encoder.queueInputBuffer(
                                inIndex, 0, 0, totalSamples * 1_000_000L / sampleRate,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEos = true
                        }
                    }
                }

                val outIndex = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex >= 0 -> {
                        tryAgainCount = 0
                        val isCodecConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        ensureTrack()
                        if (!isCodecConfig && info.size > 0) {
                            val outBuf = encoder.getOutputBuffer(outIndex)!!
                            if (container == Container.RAW) {
                                // MP3 裸码流：直接顺序写入即为可播放的 .mp3
                                val data = ByteArray(info.size)
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                outBuf.get(data)
                                rawOut?.write(data)
                            } else {
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                muxer?.writeSampleData(trackIndex, outBuf, info)
                            }
                            written = true
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encodeDone = true
                        encoder.releaseOutputBuffer(outIndex, false)
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> ensureTrack()
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputEos -> {
                        if (++tryAgainCount > 500) encodeDone = true
                    }
                }
            }

            if (!written) {
                outputFile.delete()
                callback.onError("音频编码失败：编码器未产出数据")
                return
            }
            callback.onSuccess(outputFile, note)
        } catch (e: Exception) {
            Log.e(TAG, "encodePcm failed", e)
            outputFile.delete()
            callback.onError("音频截取失败: ${e.message}")
        } finally {
            try { rawOut?.flush() } catch (_: Exception) {}
            try { rawOut?.close() } catch (_: Exception) {}
            try { encoder.stop() } catch (_: Exception) {}
            try { encoder.release() } catch (_: Exception) {}
            try { muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    /** Opus 需要 csd-0(OpusHead)，否则部分设备的 OGG 封装会失败 */
    private fun buildOpusTrackFormat(
        encoderMime: String,
        sampleRate: Int,
        channelCount: Int
    ): MediaFormat {
        val format = MediaFormat()
        format.setString(MediaFormat.KEY_MIME, encoderMime)
        format.setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
        format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, channelCount)
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
        if (encoderMime == "audio/opus") {
            val head = ByteBuffer.allocate(19)
            head.put("OpusHead".toByteArray(Charsets.US_ASCII))
            head.put(1)                              // version
            head.put(channelCount.toByte())          // channel count
            head.putShort(0)                         // pre-skip
            head.putInt(sampleRate)                  // input sample rate
            head.putShort(0)                         // output gain
            head.put(0)                              // channel mapping family
            head.flip()
            format.setByteBuffer("csd-0", head)
        }
        return format
    }

    // ==================== WAV 输出 ====================

    /** 将 PCM 写入标准 WAV 文件（44 字节头 + 数据） */
    private fun writeWav(pcm: PcmResult, outputFile: File) {
        val bitsPerSample = pcm.bytesPerSample * 8
        val blockAlign = pcm.channelCount * pcm.bytesPerSample
        val byteRate = pcm.sampleRate * blockAlign
        val dataSize = pcm.chunks.sumOf { it.data.size.toLong() }

        FileOutputStream(outputFile).use { out ->
            out.write("RIFF".toByteArray(Charsets.US_ASCII))
            out.writeLe32(36 + dataSize)
            out.write("WAVE".toByteArray(Charsets.US_ASCII))
            out.write("fmt ".toByteArray(Charsets.US_ASCII))
            out.writeLe32(16)                                    // fmt chunk size
            out.writeLe16(if (pcm.bytesPerSample == 4) 3 else 1) // 1=PCM, 3=IEEE float
            out.writeLe16(pcm.channelCount)
            out.writeLe32(pcm.sampleRate.toLong())
            out.writeLe32(byteRate.toLong())
            out.writeLe16(blockAlign)
            out.writeLe16(bitsPerSample)
            out.write("data".toByteArray(Charsets.US_ASCII))
            out.writeLe32(dataSize)
            for (chunk in pcm.chunks) {
                out.write(chunk.data)
            }
        }
    }

    private fun OutputStream.writeLe16(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }

    private fun OutputStream.writeLe32(value: Long) {
        write((value and 0xFF).toInt())
        write(((value shr 8) and 0xFF).toInt())
        write(((value shr 16) and 0xFF).toInt())
        write(((value shr 24) and 0xFF).toInt())
    }

    // ==================== 直接混流 / 裸码流（不重编码） ====================

    private fun remuxToOgg(
        extractor: MediaExtractor,
        srcFormat: MediaFormat,
        startUs: Long,
        endUs: Long,
        outputFile: File,
        callback: Callback
    ) {
        var muxer: MediaMuxer? = null
        try {
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
            val track = muxer.addTrack(srcFormat)
            muxer.start()

            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val buffer = ByteBuffer.allocateDirect(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var wrote = false

            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val ptsUs = extractor.sampleTime
                if (ptsUs > endUs) break
                if (ptsUs >= startUs) {
                    buffer.position(0)
                    buffer.limit(size)
                    info.set(
                        0, size, ptsUs - startUs,
                        if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                            MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    )
                    muxer.writeSampleData(track, buffer, info)
                    wrote = true
                }
                if (!extractor.advance()) break
            }

            if (!wrote) {
                outputFile.delete()
                callback.onError("截取区间内没有音频数据")
                return
            }
            callback.onSuccess(outputFile, "OGG（已直接混流，保留原始音质）")
        } catch (e: Exception) {
            Log.e(TAG, "remuxToOgg failed", e)
            outputFile.delete()
            callback.onError("音频截取失败: ${e.message}")
        } finally {
            try { muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    /** 把区间内的音频帧按裸码流顺序写入文件（MP3 等无需容器的编码） */
    private fun writeRawFrames(
        extractor: MediaExtractor,
        startUs: Long,
        endUs: Long,
        outputFile: File,
        callback: Callback,
        note: String
    ) {
        try {
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val buffer = ByteBuffer.allocateDirect(1024 * 1024)
            val bytes = ByteArray(1024 * 1024)
            var wrote = false
            FileOutputStream(outputFile).use { out ->
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    val ptsUs = extractor.sampleTime
                    if (ptsUs > endUs) break
                    if (ptsUs >= startUs) {
                        buffer.position(0)
                        buffer.limit(size)
                        buffer.get(bytes, 0, size)
                        out.write(bytes, 0, size)
                        wrote = true
                    }
                    if (!extractor.advance()) break
                }
            }
            if (!wrote) {
                outputFile.delete()
                callback.onError("截取区间内没有音频数据")
                return
            }
            callback.onSuccess(outputFile, note)
        } catch (e: Exception) {
            Log.e(TAG, "writeRawFrames failed", e)
            outputFile.delete()
            callback.onError("音频截取失败: ${e.message}")
        }
    }

    private fun transcodeToOgg(
        extractor: MediaExtractor,
        srcFormat: MediaFormat,
        startUs: Long,
        endUs: Long,
        outputFile: File,
        encoderMime: String,
        callback: Callback
    ) {
        val pcm = decodeToPcm(extractor, srcFormat, startUs, endUs)
        if (pcm == null) {
            outputFile.delete()
            callback.onError("截取区间内没有音频数据")
            return
        }
        val note = if (encoderMime == "audio/opus") "OGG（已重编码为 Opus）" else "OGG（已重编码为 $encoderMime）"
        encodePcm(pcm, encoderMime, outputFile, Container.OGG, note, callback)
    }

    // ==================== 降级方案（仅 OGG 不可用时） ====================

    private fun fallbackSave(
        extractor: MediaExtractor,
        srcMime: String,
        startUs: Long,
        endUs: Long,
        outputFile: File,
        callback: Callback
    ) {
        val ext = when {
            srcMime.contains("mp4a") -> "m4a"
            isMp3Mime(srcMime) -> "mp3"
            srcMime == "audio/opus" || srcMime == "audio/vorbis" -> "webm"
            else -> null
        }
        if (ext == null) {
            callback.onError("当前设备不支持截取为 OGG（需要 Android 10+ 且支持 Opus 编码），可改选 WAV/MP3")
            return
        }
        val target = File(outputFile.parentFile, outputFile.nameWithoutExtension + ".$ext")
        target.delete()

        if (ext == "m4a" || ext == "webm") {
            val trackIndex = (0 until extractor.trackCount).first { t ->
                extractor.getTrackFormat(t).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            val srcFormat = extractor.getTrackFormat(trackIndex)
            var muxer: MediaMuxer? = null
            try {
                val outFmt = if (ext == "m4a") {
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                } else {
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                }
                muxer = MediaMuxer(target.absolutePath, outFmt)
                val track = muxer.addTrack(srcFormat)
                muxer.start()
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val buffer = ByteBuffer.allocateDirect(1024 * 1024)
                val info = MediaCodec.BufferInfo()
                var wrote = false
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    val ptsUs = extractor.sampleTime
                    if (ptsUs > endUs) break
                    if (ptsUs >= startUs) {
                        buffer.position(0)
                        buffer.limit(size)
                        info.set(0, size, ptsUs - startUs, 0)
                        muxer.writeSampleData(track, buffer, info)
                        wrote = true
                    }
                    if (!extractor.advance()) break
                }
                if (!wrote) {
                    target.delete()
                    callback.onError("截取区间内没有音频数据")
                    return
                }
                callback.onSuccess(target, "当前设备不支持 OGG，已保存为 $ext")
            } catch (e: Exception) {
                target.delete()
                callback.onError("音频截取失败: ${e.message}")
            } finally {
                try { muxer?.stop() } catch (_: Exception) {}
                try { muxer?.release() } catch (_: Exception) {}
            }
        } else {
            writeRawFrames(extractor, startUs, endUs, target, callback,
                "当前设备不支持 OGG，已保存为 mp3")
        }
    }

    // ==================== 工具 ====================

    /** 查找 OGG 可用的音频编码器 */
    private fun findOggEncoder(): String? =
        OGG_ENCODER_CANDIDATES.firstOrNull { findEncoder(it) != null }

    /** 指定编码是否有可用编码器 */
    private fun findEncoder(mime: String): String? {
        val format = MediaFormat.createAudioFormat(mime, 48000, 2).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            if (mime == MIME_AAC) {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
        }
        return try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)
        } catch (e: Exception) {
            Log.w(TAG, "findEncoder 失败 mime=$mime | ${e.message}")
            null
        }
    }
}
