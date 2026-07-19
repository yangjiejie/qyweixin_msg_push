package com.videowatch.app.adapter

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.videowatch.app.R
import com.videowatch.app.crypto.VideoEncryptor
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * 视频文件列表适配器，支持缩略图加载
 */
class VideoFileAdapter(
    private var files: List<File>,
    private val onItemClick: (File) -> Unit,
    private val onSelectionChanged: (Int) -> Unit
) : RecyclerView.Adapter<VideoFileAdapter.ViewHolder>() {

    private var isSelectionMode = false
    private val selectedPositions = HashSet<Int>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private var ignoreCheckChange = false

    // 缩略图缓存和线程池
    private val thumbnailCache = HashMap<String, Bitmap?>()
    private val thumbnailExecutor = Executors.newFixedThreadPool(2)
    private val pendingFutures = HashMap<String, Future<*>?>()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val checkbox: CheckBox = view.findViewById(R.id.checkbox)
        val ivThumbnail: ImageView = view.findViewById(R.id.ivThumbnail)
        val ivPlayOverlay: ImageView = view.findViewById(R.id.ivPlayOverlay)
        val tvFileName: TextView = view.findViewById(R.id.tvFileName)
        val tvFileInfo: TextView = view.findViewById(R.id.tvFileInfo)
        val ivLock: ImageView = view.findViewById(R.id.ivLock)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_video_file, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = files[position]
        val context = holder.itemView.context

        // 文件名 - 最多显示20个字符，超出截断
        val rawName = if (VideoEncryptor.isEncrypted(file)) {
            VideoEncryptor.getDecryptedFileName(file) ?: file.name
        } else {
            file.name
        }
        val displayName = if (rawName.length > 20) {
            rawName.take(17) + "..."
        } else {
            rawName
        }
        holder.tvFileName.text = displayName

        // 文件信息
        val sizeStr = formatFileSize(file.length())
        val dateStr = dateFormat.format(Date(file.lastModified()))
        val statusStr = if (VideoEncryptor.isEncrypted(file)) "已加密" else "已解密"
        holder.tvFileInfo.text = "$sizeStr  •  $dateStr  •  $statusStr"

        // 加密状态图标
        if (VideoEncryptor.isEncrypted(file)) {
            holder.ivLock.visibility = View.VISIBLE
            holder.ivLock.setImageResource(R.drawable.ic_lock)
        } else {
            holder.ivLock.visibility = View.GONE
        }

        // 加载缩略图
        loadThumbnail(file, holder)

        // 选择模式
        holder.checkbox.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
        ignoreCheckChange = true
        holder.checkbox.isChecked = selectedPositions.contains(position)
        ignoreCheckChange = false

        // 点击处理
        holder.itemView.setOnClickListener {
            if (isSelectionMode) {
                toggleSelection(position, holder.checkbox)
            } else {
                onItemClick(file)
            }
        }

        holder.itemView.setOnLongClickListener {
            if (!isSelectionMode) {
                enterSelectionMode()
                toggleSelection(position, holder.checkbox)
            }
            true
        }

        holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
            if (ignoreCheckChange) return@setOnCheckedChangeListener
            if (isChecked) selectedPositions.add(position)
            else selectedPositions.remove(position)
            onSelectionChanged(selectedPositions.size)
        }
    }

    private fun loadThumbnail(file: File, holder: ViewHolder) {
        val filePath = file.absolutePath

        // 已加密文件不生成缩略图
        if (VideoEncryptor.isEncrypted(file)) {
            holder.ivThumbnail.setImageResource(0)
            holder.ivThumbnail.setBackgroundColor(0xFF2A2A3E.toInt())
            return
        }

        // 先检查缓存
        if (thumbnailCache.containsKey(filePath)) {
            val cached = thumbnailCache[filePath]
            if (cached != null) {
                holder.ivThumbnail.setImageBitmap(cached)
                holder.ivPlayOverlay.visibility = View.VISIBLE
            }
            return
        }

        // 设置默认背景，避免闪烁
        holder.ivThumbnail.setImageResource(0)
        holder.ivThumbnail.setBackgroundColor(0xFF2A2A3E.toInt())

        // 取消同一文件的旧任务
        pendingFutures[filePath]?.let { future ->
            if (!future.isDone) future.cancel(true)
        }

        // 异步加载缩略图
        val future = thumbnailExecutor.submit {
            try {
                if (Thread.currentThread().isInterrupted) return@submit
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(filePath)
                // 提取1秒处的帧作为缩略图
                val bitmap = retriever.getFrameAtTime(
                    1_000_000, // 1秒
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
                retriever.release()

                // 缩放到目标尺寸
                val thumbnail = if (bitmap != null) {
                    val targetW = 144 // 对应72dp的xhdpi
                    val targetH = 96
                    val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
                    if (scaled !== bitmap) bitmap.recycle()
                    scaled
                } else null

                if (!Thread.currentThread().isInterrupted) {
                    thumbnailCache[filePath] = thumbnail
                    // 在主线程更新UI
                    holder.itemView.post {
                        val currentFile = if (holder.bindingAdapterPosition in 0 until files.size) {
                            files[holder.bindingAdapterPosition]
                        } else null
                        if (currentFile?.absolutePath == filePath) {
                            if (thumbnail != null) {
                                holder.ivThumbnail.setImageBitmap(thumbnail)
                                holder.ivThumbnail.setBackgroundColor(0xFF2A2A3E.toInt())
                                holder.ivPlayOverlay.visibility = View.VISIBLE
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                thumbnailCache[filePath] = null
            }
        }
        pendingFutures[filePath] = future
    }

    override fun getItemCount(): Int = files.size

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        // 清理正在加载的缩略图引用
        holder.ivThumbnail.setImageBitmap(null)
    }

    fun updateFiles(newFiles: List<File>) {
        files = newFiles
        selectedPositions.clear()
        // 清理不再需要的缩略图缓存
        val newPaths = newFiles.map { it.absolutePath }.toSet()
        thumbnailCache.keys.removeAll { !newPaths.contains(it) }
        pendingFutures.keys.removeAll { !newPaths.contains(it) }
        notifyDataSetChanged()
    }

    fun enterSelectionMode() {
        isSelectionMode = true
        selectedPositions.clear()
        notifyDataSetChanged()
    }

    fun exitSelectionMode() {
        isSelectionMode = false
        selectedPositions.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    fun cancelSelection() = exitSelectionMode()

    fun isInSelectionMode(): Boolean = isSelectionMode

    /** 根据File对象切换选中状态 */
    fun toggleSelection(file: File) {
        val pos = files.indexOfFirst { it.absolutePath == file.absolutePath }
        if (pos < 0) return
        if (isSelectionMode) {
            if (selectedPositions.contains(pos)) {
                selectedPositions.remove(pos)
            } else {
                selectedPositions.add(pos)
            }
            notifyItemChanged(pos)
            onSelectionChanged(selectedPositions.size)
            if (selectedPositions.isEmpty()) exitSelectionMode()
        }
    }

    fun getSelectedFiles(): List<File> {
        return selectedPositions.mapNotNull { idx ->
            if (idx < files.size) files[idx] else null
        }
    }

    fun getSelectedCount(): Int = selectedPositions.size

    fun cleanup() {
        thumbnailExecutor.shutdownNow()
        thumbnailCache.clear()
        pendingFutures.clear()
    }

    private fun toggleSelection(position: Int, checkbox: CheckBox) {
        ignoreCheckChange = true
        if (selectedPositions.contains(position)) {
            selectedPositions.remove(position)
            checkbox.isChecked = false
        } else {
            selectedPositions.add(position)
            checkbox.isChecked = true
        }
        ignoreCheckChange = false
        onSelectionChanged(selectedPositions.size)

        if (selectedPositions.isEmpty()) {
            exitSelectionMode()
        }
    }

    private fun formatFileSize(size: Long): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            size < 1024 * 1024 * 1024 -> "${"%.1f".format(size.toDouble() / 1024 / 1024)} MB"
            else -> "${"%.2f".format(size.toDouble() / 1024 / 1024 / 1024)} GB"
        }
    }
}
