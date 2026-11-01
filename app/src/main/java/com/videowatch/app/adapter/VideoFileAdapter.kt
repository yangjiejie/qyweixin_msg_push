package com.videowatch.app.adapter

import android.graphics.Bitmap
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.videowatch.app.R
import com.videowatch.app.utils.VideoThumbs
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
    private val onSelectionChanged: (Int) -> Unit,
    private val onItemMenuClick: ((File, View) -> Unit)? = null
) : RecyclerView.Adapter<VideoFileAdapter.ViewHolder>() {

    /** 是否显示每项的“更多”菜单按钮（批量/迁移模式下显示） */
    private var showItemMenu = false
    fun setShowItemMenu(show: Boolean) {
        if (showItemMenu != show) {
            showItemMenu = show
            notifyDataSetChanged()
        }
    }

    private var isSelectionMode = false
    private val selectedPositions = HashSet<Int>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private var ignoreCheckChange = false

    // 缩略图缓存和线程池
    private val thumbnailCache = HashMap<String, Bitmap?>()
    private val thumbnailExecutor = Executors.newFixedThreadPool(2)
    private val pendingFutures = HashMap<String, Future<*>?>()

    /** 相册视频 path -> MediaStore content Uri 映射（缩略图按 Uri 取帧更可靠） */
    private var albumUriMap: Map<String, Uri> = emptyMap()
    fun setAlbumUriMap(map: Map<String, Uri>) {
        albumUriMap = map
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val checkbox: CheckBox = view.findViewById(R.id.checkbox)
        val ivThumbnail: ImageView = view.findViewById(R.id.ivThumbnail)
        val ivPlayOverlay: ImageView = view.findViewById(R.id.ivPlayOverlay)
        val tvFileName: TextView = view.findViewById(R.id.tvFileName)
        val tvFileInfo: TextView = view.findViewById(R.id.tvFileInfo)
        val ivLock: ImageView = view.findViewById(R.id.ivLock)
        val btnMore: ImageButton = view.findViewById(R.id.btnMore)
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
        val rawName = file.name
        val displayName = if (rawName.length > 20) {
            rawName.take(17) + "..."
        } else {
            rawName
        }
        holder.tvFileName.text = displayName

        // 文件信息
        val sizeStr = formatFileSize(file.length())
        val dateStr = dateFormat.format(Date(file.lastModified()))
        holder.tvFileInfo.text = "$sizeStr  •  $dateStr"

        // 隐私视频标识（位于 App 私有目录的视频标记为“隐私”）
        if (file.absolutePath.contains("/Android/data/")) {
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
            } else {
                // 已在批量选择状态：长按退出批量模式
                exitSelectionMode()
            }
            true
        }

        holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
            if (ignoreCheckChange) return@setOnCheckedChangeListener
            if (isChecked) selectedPositions.add(position)
            else selectedPositions.remove(position)
            onSelectionChanged(selectedPositions.size)
        }

        // 批量/迁移模式下显示“更多”菜单按钮（相册与隐私视频都有该功能）
        if (showItemMenu) {
            holder.btnMore.visibility = View.VISIBLE
            holder.btnMore.setOnClickListener {
                onItemMenuClick?.invoke(file, holder.btnMore)
            }
        } else {
            holder.btnMore.visibility = View.GONE
            holder.btnMore.setOnClickListener(null)
        }
    }

    private fun loadThumbnail(file: File, holder: ViewHolder) {
        val filePath = file.absolutePath

        // 内存缓存
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

        // 异步加载缩略图（优先用 MediaStore content Uri，普通视频直接按路径）
        val future = thumbnailExecutor.submit {
            try {
                if (Thread.currentThread().isInterrupted) return@submit
                val bitmap = VideoThumbs.thumbForVideo(
                    holder.itemView.context, file, albumUriMap[filePath]
                )
                if (!Thread.currentThread().isInterrupted) {
                    thumbnailCache[filePath] = bitmap
                    holder.itemView.post {
                        val currentFile = if (holder.bindingAdapterPosition in 0 until files.size) {
                            files[holder.bindingAdapterPosition]
                        } else null
                        if (currentFile?.absolutePath == filePath) {
                            if (bitmap != null) {
                                holder.ivThumbnail.setImageBitmap(bitmap)
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

    /** 全选当前列表所有项 */
    fun selectAll() {
        if (!isSelectionMode) {
            isSelectionMode = true
        }
        selectedPositions.clear()
        for (i in files.indices) selectedPositions.add(i)
        notifyDataSetChanged()
        onSelectionChanged(selectedPositions.size)
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
