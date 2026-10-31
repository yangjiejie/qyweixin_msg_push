package com.videowatch.app.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.util.Log
import com.videowatch.app.crypto.VideoEncryptor
import java.io.File
import java.io.FileOutputStream

/**
 * 视频缩略图工具。
 *
 * 加密视频没有明文文件可直接抽帧，需要先解密到缓存再取帧；
 * 取到的帧以 JPEG 缓存在 cacheDir/video_thumbs，之后直接读缓存，不再重复解密。
 * 生成失败会写一个 24 小时有效的失败标记，避免每次列表绑定都重新解密整个文件。
 */
object VideoThumbs {

    private const val TAG = "VideoThumbs"

    /** 与列表视图一致的缩略图目标尺寸（144x96，对应 72x48dp） */
    private const val THUMB_W = 144
    private const val THUMB_H = 96

    /** 失败标记有效期：超过后允许重试（避免临时失败被永久记住） */
    private const val FAIL_RETRY_MS = 24 * 3600 * 1000L

    fun thumbDir(context: Context): File =
        File(context.cacheDir, "video_thumbs").apply { mkdirs() }

    /**
     * 缓存 key：文件路径 + 大小。
     * 重新加密/内容变化后大小不同，会自动改用新缓存。
     */
    fun thumbKey(file: File): String =
        (file.absolutePath + "|" + file.length()).hashCode().toUInt().toString(16)

    /** 读取磁盘缓存中的缩略图，没有返回 null */
    fun cachedThumb(context: Context, file: File): Bitmap? {
        val f = File(thumbDir(context), thumbKey(file) + ".jpg")
        if (!f.exists() || f.length() <= 0L) return null
        return try {
            BitmapFactory.decodeFile(f.absolutePath)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 从普通（明文）视频文件抽一帧，生成并缓存缩略图。
     * @param key 缓存 key（加密视频应传其 .enc 文件的 key）
     */
    fun generateFromPlain(context: Context, plainFile: File, key: String): Bitmap? {
        var bitmap: Bitmap? = null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(plainFile.absolutePath)
            val frame = retriever.getFrameAtTime(
                1_000_000, // 1秒处
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            )
            bitmap = if (frame != null && frame.width > 0) {
                ThumbnailUtils.extractThumbnail(
                    frame, THUMB_W, THUMB_H, ThumbnailUtils.OPTIONS_RECYCLE_INPUT
                )
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "抽帧失败: ${plainFile.name} | ${e.message}")
            bitmap = null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
        if (bitmap != null) {
            saveThumb(context, key, bitmap)
        }
        return bitmap
    }

    /**
     * 加密视频缩略图：先查磁盘缓存；没有则解密到缓存目录抽帧，完成后立即删除解密临时文件。
     */
    fun thumbForEncrypted(context: Context, encFile: File): Bitmap? {
        val dir = thumbDir(context)
        val key = thumbKey(encFile)

        // 最近失败过：直接返回，避免每次绑定都解密整个文件
        val failMarker = File(dir, "$key.fail")
        if (failMarker.exists() &&
            System.currentTimeMillis() - failMarker.lastModified() < FAIL_RETRY_MS
        ) {
            return null
        }

        cachedThumb(context, encFile)?.let { return it }

        val tmp = File(context.cacheDir, "thumb_dec_${System.currentTimeMillis()}_${encFile.name}")
        return try {
            if (!VideoEncryptor.decryptToFile(encFile.absolutePath, tmp.absolutePath) || !tmp.exists()) {
                markFailed(dir, key)
                return null
            }
            val bitmap = generateFromPlain(context, tmp, key)
            if (bitmap == null) markFailed(dir, key)
            bitmap
        } catch (e: Exception) {
            Log.w(TAG, "加密视频缩略图生成失败: ${encFile.name} | ${e.message}")
            markFailed(dir, key)
            null
        } finally {
            try { tmp.delete() } catch (_: Exception) {}
        }
    }

    private fun markFailed(dir: File, key: String) {
        try {
            File(dir, "$key.fail").createNewFile()
        } catch (_: Exception) {}
    }

    private fun saveThumb(context: Context, key: String, bitmap: Bitmap) {
        try {
            val dir = thumbDir(context)
            // 成功即清除失败标记
            File(dir, "$key.fail").delete()
            val target = File(dir, "$key.jpg")
            val tmp = File(dir, "$key.jpg.partial")
            FileOutputStream(tmp).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            if (target.exists()) target.delete()
            tmp.renameTo(target)
        } catch (e: Exception) {
            Log.w(TAG, "保存缩略图缓存失败: $key | ${e.message}")
        }
    }
}
