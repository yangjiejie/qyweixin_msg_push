package com.videowatch.app.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 视频缩略图工具（明文视频）。
 *
 * 优先用 MediaStore content Uri 取帧（Android 11+ 下按文件路径取帧可能失败，
 * 尤其是经 MediaStore 写入 Movies/VideoWatch 的视频）；Uri 取帧失败时回退到文件路径。
 * 取到的帧以 JPEG 缓存在 cacheDir/video_thumbs，之后直接读缓存，避免重复抽帧。
 */
object VideoThumbs {

    private const val TAG = "VideoThumbs"

    /** 与列表视图一致的缩略图目标尺寸（144x96，对应 72x48dp） */
    private const val THUMB_W = 144
    private const val THUMB_H = 96

    fun thumbDir(context: Context): File =
        File(context.cacheDir, "video_thumbs").apply { mkdirs() }

    /**
     * 缓存 key：文件路径 + 大小。内容变化后大小不同，会自动改用新缓存。
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
     * 普通（明文）视频缩略图：先查磁盘缓存；没有则抽帧并缓存。
     * @param uri 若为 MediaStore content Uri 则优先用它取帧（更可靠）。
     */
    fun thumbForVideo(context: Context, videoFile: File, uri: Uri? = null): Bitmap? {
        val key = thumbKey(videoFile)
        cachedThumb(context, videoFile)?.let { return it }
        val retriever = MediaMetadataRetriever()
        return try {
            if (uri != null) {
                try {
                    retriever.setDataSource(context, uri)
                } catch (_: Exception) {
                    retriever.setDataSource(videoFile.absolutePath)
                }
            } else {
                retriever.setDataSource(videoFile.absolutePath)
            }
            val frame = retriever.getFrameAtTime(
                1_000_000, // 1秒处
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            )
            val bitmap = if (frame != null && frame.width > 0) {
                ThumbnailUtils.extractThumbnail(
                    frame, THUMB_W, THUMB_H, ThumbnailUtils.OPTIONS_RECYCLE_INPUT
                )
            } else null
            if (bitmap != null) saveThumb(context, key, bitmap)
            bitmap
        } catch (e: Exception) {
            Log.w(TAG, "抽帧失败: ${videoFile.name} | ${e.message}")
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun saveThumb(context: Context, key: String, bitmap: Bitmap) {
        try {
            val dir = thumbDir(context)
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
