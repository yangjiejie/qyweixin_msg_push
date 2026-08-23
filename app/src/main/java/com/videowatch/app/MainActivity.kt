package com.videowatch.app

import android.Manifest
import android.app.AlertDialog
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.os.Environment
import android.view.Menu
import android.widget.PopupMenu
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.videowatch.app.adapter.VideoFileAdapter
import com.videowatch.app.crypto.VideoEncryptor
import com.videowatch.app.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import java.io.File

class MainActivity : AppCompatActivity() {

    private val TAG = "VideoWatch"

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: VideoFileAdapter

    // 两个独立列表
    private val albumVideos = mutableListOf<File>()       // 相册视频（MediaStore）
    private val encryptedVideos = mutableListOf<File>()   // 加密视频（私有目录）
    // 当前展示的列表
    private var currentTab = 0 // 0=相册视频, 1=加密视频

    /**
     * 应用工作目录（App 私有外部存储，无需任何特殊权限即可读写）。
     * 注意：卸载 App 时该目录会被系统清除。
     * 为了让加密视频在卸载/更新后仍保留，已加密的 .enc 文件会通过
     * mirrorEncryptedToBackup() 镜像到用户用 SAF 选择的备份目录，
     * 并在启动时由 restoreEncryptedFromBackup() 自动恢复。
     */
    private val appVideoDir: File by lazy {
        File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "videos").also { it.mkdirs() }
    }
    private var loadJob: Job? = null
    private var isMultiSelect = false

    /** SAF 备份目录相关 */
    private val prefs by lazy { getSharedPreferences("videowatch_prefs", MODE_PRIVATE) }
    private var backupPromptPending = false

    // 权限请求
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            loadAllVideos()
        } else {
            Toast.makeText(this, "需要存储权限才能访问视频文件", Toast.LENGTH_LONG).show()
            // 引导去设置
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 沉浸式全屏 - 隐藏状态栏
        setupFullscreen()

        setupViews()
        setupListeners()
        checkPermissionAndLoad()
        // 确保工作目录有 .nomedia
        ensureNomedia()
        // 引导用户选择"加密视频备份目录"（卸载/更新后仍能保留）
        ensureBackupFolderOrPrompt()
    }

    // ==================== 全屏沉浸 ====================

    private fun setupFullscreen() {
        // 隐藏状态栏，全屏显示
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.statusBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // 让 topBar 不被状态栏区域遮挡（给它多一点 padding）
        binding.topBar.setPadding(
            binding.topBar.paddingLeft,
            getStatusBarHeight(),
            binding.topBar.paddingRight,
            binding.topBar.paddingBottom
        )
    }

    private fun getStatusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0
    }

    // ==================== 初始化 ====================

    private fun setupViews() {
        adapter = VideoFileAdapter(
            files = emptyList(),
            onItemClick = { file -> playVideo(file) },
            onSelectionChanged = { count ->
                if (count == 0) {
                    // 选择清空：直接退出多选 UI。
                    // 注意：这里不能回调 cancelMultiSelect()，否则会与
                    // adapter.exitSelectionMode() 形成 cancelMultiSelect <-> exitSelectionMode 的无限递归（栈溢出崩溃）。
                    isMultiSelect = false
                    binding.bottomBar.visibility = View.GONE
                } else {
                    isMultiSelect = true
                    binding.bottomBar.visibility = View.VISIBLE
                    updateBatchMigrateButton()
                    binding.tvSelectedCount.text = "已选 $count 个"
                }
            },
            onItemDeleteClick = { file -> showItemMenu(file) },
            onItemMigrateClick = { file -> showItemMenu(file) }
        )
        binding.recyclerView.adapter = adapter

        // TabLayout 初始化
        binding.tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                currentTab = tab?.position ?: 0
                switchTab()
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })
    }

    private fun setupListeners() {
        // 导入按钮 - 顶栏，大触控区域
        binding.btnImport.setOnClickListener { importVideos() }
        binding.btnImportBig.setOnClickListener { importVideos() }
        binding.btnSettings.setOnClickListener { showSettingsDialog() }
        binding.btnImportFile.setOnClickListener { importFromFile() }

        // 多选按钮
        binding.btnMultiSelect.setOnClickListener {
            if (!adapter.isInSelectionMode()) {
                adapter.enterSelectionMode()
                isMultiSelect = true
                binding.bottomBar.visibility = View.VISIBLE
                Toast.makeText(this, "长按视频可多选", Toast.LENGTH_SHORT).show()
            } else {
                cancelMultiSelect()
            }
        }

        // 底部批量操作
        binding.btnBatchEncrypt.setOnClickListener { batchEncrypt() }
        binding.btnBatchDecrypt.setOnClickListener { batchDecrypt() }
        binding.btnBatchDelete.setOnClickListener { batchDelete() }
        binding.btnBatchMoveToAlbum.setOnClickListener { batchMoveToAlbum() }
        binding.btnBatchMoveToEncrypt.setOnClickListener { batchMoveToEncrypt() }
        binding.btnSelectAll.setOnClickListener { adapter.selectAll() }
    }

    /** 根据当前页签决定显示“批量回退相册”还是“批量迁移加密” */
    private fun updateBatchMigrateButton() {
        if (currentTab == 1) {
            binding.btnBatchMoveToAlbum.visibility = View.VISIBLE
            binding.btnBatchMoveToEncrypt.visibility = View.GONE
        } else {
            binding.btnBatchMoveToAlbum.visibility = View.GONE
            binding.btnBatchMoveToEncrypt.visibility = View.VISIBLE
        }
    }

    // ==================== 权限检查 ====================

    private fun checkPermissionAndLoad() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // API 29 以下不需要存储权限也能读取
            loadAllVideos()
        } else {
            permissionLauncher.launch(arrayOf(permission))
        }
    }

    // ==================== 加载视频列表 ====================

    private fun loadAllVideos() {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch(Dispatchers.IO) {
            binding.progressBar.post { binding.progressBar.visibility = View.VISIBLE }

            // 0. 若已配置了 SAF 备份目录，则先从该目录恢复上次保留的加密视频
            val backupUri = backupTreeUri
            if (backupUri != null) {
                restoreEncryptedFromBackup(backupUri)
            }

            // 1. 加载加密视频（私有目录全部视频文件）
            val encList = mutableListOf<File>()
            if (appVideoDir.exists()) {
                appVideoDir.listFiles()?.forEach { file ->
                    if (!file.isDirectory && !file.name.startsWith(".") && isVideoOrEncFile(file)) {
                        encList.add(file)
                    }
                }
            }

            // 2. 从MediaStore加载所有设备视频
            val albumList = queryMediaStoreVideos()

            withContext(Dispatchers.Main) {
                albumVideos.clear()
                albumVideos.addAll(albumList)
                encryptedVideos.clear()
                encryptedVideos.addAll(encList)

                binding.progressBar.visibility = View.GONE
                switchTab()
            }
        }
    }

    private fun queryMediaStoreVideos(): List<File> {
        val files = mutableListOf<File>()
        // 收集私有目录中所有视频文件的"原始文件名"
        // 1) xxx.mp4.enc → 对应原始文件 xxx.mp4
        // 2) xxx.mp4（直接导入的） → 对应原始文件 xxx.mp4
        val privateFileNames = mutableSetOf<String>()
        if (appVideoDir.exists()) {
            appVideoDir.listFiles()?.forEach { f ->
                if (f.name.endsWith(".enc")) {
                    privateFileNames.add(f.name.removeSuffix(".enc"))
                } else if (!f.isDirectory && !f.name.startsWith(".")) {
                    // 导入但未加密的视频副本，也需要在相册中隐藏原文件
                    privateFileNames.add(f.name)
                }
            }
        }
        try {
            val projection = arrayOf(
                MediaStore.Video.Media.DATA,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE
            )
            val cursor = contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection, null, null,
                MediaStore.Video.Media.DATE_MODIFIED + " DESC"
            )
            cursor?.use {
                val dataIndex = it.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                val nameIndex = it.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                while (it.moveToNext()) {
                    val path = it.getString(dataIndex)
                    val name = it.getString(nameIndex)
                    if (path != null) {
                        val file = File(path)
                        // 如果该视频在私有目录中已有副本（加密或直接导入），从相册中隐藏
                        if (name != null && privateFileNames.contains(name)) continue
                        if (file.exists() && file.isFile && isVideoOrEncFile(file)) {
                            files.add(file)
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return files
    }

    private fun isVideoOrEncFile(file: File): Boolean {
        val name = file.name.lowercase()
        if (name.endsWith(".mp4") || name.endsWith(".avi") || name.endsWith(".mkv") ||
            name.endsWith(".mov") || name.endsWith(".wmv") || name.endsWith(".flv") ||
            name.endsWith(".webm") || name.endsWith(".3gp") || name.endsWith(".m4v") ||
            name.endsWith(".ts")) return true
        if (name.endsWith(".enc")) return true
        return false
    }

    // ==================== Tab切换 ====================

    private fun switchTab() {
        val currentList = if (currentTab == 0) albumVideos else encryptedVideos

        // 退出多选
        if (isMultiSelect) cancelMultiSelect()

        adapter.setShowItemMenu(true) // 相册与加密视频页签都显示“更多”菜单
        adapter.updateFiles(currentList.toList())

        if (currentList.isEmpty()) {
            binding.recyclerView.visibility = View.GONE
            binding.emptyLayout.visibility = View.VISIBLE

            // 根据tab显示不同的空状态文案
            if (currentTab == 0) {
                binding.imgEmptyIcon.setImageResource(R.drawable.ic_import_big)
                binding.tvEmpty.text = "暂无相册视频"
                binding.tvEmptyHint.text = "请点击下方按钮从相册导入视频\n或确保设备存储中有视频文件"
                binding.btnImportBig.visibility = View.VISIBLE
                binding.btnImportFile.visibility = View.VISIBLE
            } else {
                binding.imgEmptyIcon.setImageResource(R.drawable.ic_lock)
                binding.tvEmpty.text = "暂无加密视频"
                binding.tvEmptyHint.text = "请先在「相册视频」中选择视频\n然后长按进入多选模式进行加密保护"
                binding.btnImportBig.visibility = View.GONE
                binding.btnImportFile.visibility = View.GONE
            }
        } else {
            binding.recyclerView.visibility = View.VISIBLE
            binding.emptyLayout.visibility = View.GONE
        }
    }

    // ==================== 导入视频 ====================

    private fun importVideos() {
        try {
            importLauncher.launch(arrayOf("video/*"))
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun importFromFile() {
        try {
            importLauncher.launch(arrayOf("video/*"))
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@registerForActivityResult
        importVideosFromUris(uris)
    }

    private val deleteIntentLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        Log.d(TAG, "deleteIntentLauncher 回调 resultCode=${result.resultCode}")
        if (result.resultCode == RESULT_OK) {
            Toast.makeText(this, "原视频已从系统相册删除", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "原视频仍保留在系统相册", Toast.LENGTH_SHORT).show()
        }
        loadAllVideos()
    }

    private fun importVideosFromUris(uris: List<Uri>) {
        lifecycleScope.launch(Dispatchers.IO) {
            var imported = 0
            // 记录成功导入的视频在 MediaStore 中的 ID（用于后续删除）
            val importedMediaIds = mutableListOf<Long>()
            // 无法解析出 MediaStore ID 时，退化为真实文件路径（需 MANAGE_EXTERNAL_STORAGE 才能删除）
            val importedPaths = mutableListOf<String>()
            for (uri in uris) {
                try {
                    val name = getFileName(uri) ?: "video_${System.currentTimeMillis()}.mp4"
                    val dest = File(appVideoDir, name)
                    contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    imported++
                    // 提取 MediaStore ID
                    val mediaId = extractMediaStoreId(uri)
                    Log.d(TAG, "import uri=$uri -> mediaId=$mediaId")
                    if (mediaId != null) {
                        importedMediaIds.add(mediaId)
                    } else {
                        val p = documentPathToRealPath(uri)
                        if (p != null) {
                            importedPaths.add(p)
                            Log.d(TAG, "import uri 无法解析ID，记录真实路径=$p")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "import 单个文件失败: $uri", e)
                    // 跳过失败的文件
                }
            }
            Log.d(TAG, "import 完成: imported=$imported, mediaIds=$importedMediaIds, paths=$importedPaths")
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "成功导入 $imported 个视频", Toast.LENGTH_SHORT).show()
                loadAllVideos()
                // 切换到加密视频tab
                binding.tabLayout.getTabAt(1)?.select()

                // 导入完成后询问是否删除原文件
                if (imported > 0 && (importedMediaIds.isNotEmpty() || importedPaths.isNotEmpty())) {
                    showDeleteOriginalDialog(importedMediaIds, importedPaths)
                }
            }
        }
    }

    /**
     * 从 SAF content URI 提取 MediaStore 视频 ID
     * 支持:
     *   - content://media/external/video/media/123
     *   - content://com.android.providers.media.documents/document/video%3A123
     *   - content://com.android.externalstorage.documents/document/primary%3ADCIM/Camera/xxx.mp4 (文件选择器)
     */
    private fun extractMediaStoreId(uri: Uri): Long? {
        return try {
            val lastSegment = uri.lastPathSegment ?: return null
            // 1) 直接是 MediaStore 数字 ID
            val directId = lastSegment.toLongOrNull()
            if (directId != null) return directId

            // 2) DocumentsProvider 格式
            val docId = try {
                DocumentsContract.getDocumentId(uri)
            } catch (_: Exception) { lastSegment }
            val decoded = Uri.decode(docId)

            // 2a) media documents: video:123
            val parts = decoded.split(":")
            if (parts.size == 2 && parts[0] == "video") {
                return parts[1].toLongOrNull()
            }

            // 2b) external storage documents:
            //     primary:DCIM/Camera/x.mp4        -> /storage/emulated/0/DCIM/Camera/x.mp4
            //     primary:/storage/emulated/0/...  -> 直接就是绝对路径（小米文件管理器等格式）
            //     通过真实文件路径反查 MediaStore ID
            val path = if (decoded.contains(":")) {
                val rest = decoded.substringAfter(":")
                if (rest.startsWith("/")) rest else "/storage/emulated/0/$rest"
            } else {
                null
            }
            if (path != null) {
                val idByPath = queryMediaStoreIdByPath(path)
                if (idByPath != null) {
                    Log.d(TAG, "extractMediaStoreId: 通过路径[$path]反查到 id=$idByPath")
                    return idByPath
                }
            }

            Log.w(TAG, "extractMediaStoreId: 无法解析 docId=$decoded (uri=$uri)")
            null
        } catch (e: Exception) {
            Log.e(TAG, "extractMediaStoreId 异常: $uri", e)
            null
        }
    }

    /**
     * 通过真实文件路径反查 MediaStore 中的视频 ID
     */
    private fun queryMediaStoreIdByPath(path: String): Long? {
        val projection = arrayOf(MediaStore.Video.Media._ID)
        val selection = "${MediaStore.Video.Media.DATA} = ?"
        return try {
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection, selection, arrayOf(path), null
            )?.use { c ->
                if (c.moveToFirst()) {
                    c.getLong(c.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "queryMediaStoreIdByPath 异常: $path", e)
            null
        }
    }

    /**
     * 提示用户是否删除已导入的原文件
     */
    private fun showDeleteOriginalDialog(mediaIds: List<Long>, paths: List<String>) {
        AlertDialog.Builder(this)
            .setTitle("删除原文件")
            .setMessage("视频已成功导入到加密目录，是否删除相册中的原文件以释放空间？")
            .setPositiveButton("删除") { _, _ ->
                Log.d(TAG, "用户点击[删除]，mediaIds=$mediaIds, paths=$paths")
                deleteOriginalFilesById(mediaIds, paths)
            }
            .setNegativeButton("保留", null)
            .show()
    }

    /**
     * 通过 MediaStore ID 删除原始视频文件
     * - mediaIds 对应的视频交给 requestDeleteUris（Android 11+ 会弹系统确认框真正删除物理文件）
     * - paths（无法解析出 MediaStore ID 时的真实路径）走 deleteByRealPath 兜底
     */
    private fun deleteOriginalFilesById(mediaIds: List<Long>, paths: List<String>) {
        if (mediaIds.isEmpty() && paths.isEmpty()) {
            Toast.makeText(this, "无法识别原文件，仍保留在系统相册", Toast.LENGTH_SHORT).show()
            return
        }
        val uris = mediaIds.map { id ->
            ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
        }
        if (uris.isNotEmpty()) {
            requestDeleteUris(uris)
        }
        // 兜底：直接按真实路径删除（需 MANAGE_EXTERNAL_STORAGE，否则会失败）
        paths.forEach { deleteByRealPath(it) }
    }

    /**
     * 按真实文件路径直接删除（兜底手段，依赖 MANAGE_EXTERNAL_STORAGE）
     * 删除后通知媒体库重新扫描，使系统图库不再显示该文件
     */
    private fun deleteByRealPath(path: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val f = File(path)
                val ok = if (f.exists()) f.delete() else false
                MediaScannerConnection.scanFile(
                    this@MainActivity, arrayOf(path), null, null
                )
                Log.d(TAG, "deleteByRealPath $path -> deleted=$ok")
            } catch (e: Exception) {
                Log.e(TAG, "deleteByRealPath 异常 $path", e)
            }
            withContext(Dispatchers.Main) { loadAllVideos() }
        }
    }

    /**
     * 从 SAF documents URI 解析出真实文件路径（用于无法直接拿到 MediaStore ID 时的兜底删除）
     */
    private fun documentPathToRealPath(uri: Uri): String? {
        return try {
            val docId = try {
                DocumentsContract.getDocumentId(uri)
            } catch (_: Exception) {
                return null
            }
            val decoded = Uri.decode(docId)
            if (decoded.contains(":")) {
                val rest = decoded.substringAfter(":")
                if (rest.startsWith("/")) rest else "/storage/emulated/0/$rest"
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun getFileName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getString(cursor.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME))
                    } else null
                }
        } catch (_: Exception) { null }
    }

    // ==================== 通过MediaStore真正删除原文件 ====================

    /**
     * 将本地文件解析为其在 MediaStore 中的 content URI
     * 用于后续通过 MediaStore 删除（分区存储下唯一可靠的删除方式）
     */
    private fun getMediaStoreUriForFile(file: File): Uri? {
        val projection = arrayOf(MediaStore.Video.Media._ID)
        val selection = "${MediaStore.Video.Media.DATA} = ?"
        val args = arrayOf(file.absolutePath)
        return try {
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection, selection, args, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
                    ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                } else {
                    null
                }
            }
        } catch (_: Exception) { null }
    }

    /**
     * 请求删除一组 MediaStore 视频（真实删除物理文件，系统图库将不再显示）
     * - Android 11+：调用 MediaStore.createDeleteRequest 弹出系统确认框，结果在 deleteIntentLauncher 回调
     * - Android 10 及以下：直接 contentResolver.delete 删除
     */
    private fun requestDeleteUris(uris: List<Uri>) {
        if (uris.isEmpty()) {
            Log.w(TAG, "requestDeleteUris: uris 为空，不执行删除")
            loadAllVideos()
            return
        }
        Log.d(TAG, "requestDeleteUris: 准备删除 ${uris.size} 个, SDK=${Build.VERSION.SDK_INT}")
        uris.forEachIndexed { i, u -> Log.d(TAG, "  uri[$i] = $u") }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 先尝试整批删除
            val pi = try {
                MediaStore.createDeleteRequest(contentResolver, uris)
            } catch (e: Exception) {
                Log.e(TAG, "createDeleteRequest 整批失败, 改逐个尝试", e)
                null
            }
            if (pi != null) {
                Log.d(TAG, "launch 系统删除确认框(整批, ${uris.size} 个)")
                deleteIntentLauncher.launch(
                    IntentSenderRequest.Builder(pi.intentSender).build()
                )
                return
            }
            // 整批失败：逐个校验，挑出可删除的再发起一次
            val valid = mutableListOf<Uri>()
            for (u in uris) {
                try {
                    MediaStore.createDeleteRequest(contentResolver, listOf(u))
                    valid.add(u)
                } catch (e: Exception) {
                    Log.e(TAG, "该 uri 无法删除，跳过: $u", e)
                }
            }
            if (valid.isNotEmpty()) {
                try {
                    val pi2 = MediaStore.createDeleteRequest(contentResolver, valid)
                    Log.d(TAG, "launch 系统删除确认框(逐个, ${valid.size} 个)")
                    deleteIntentLauncher.launch(
                        IntentSenderRequest.Builder(pi2.intentSender).build()
                    )
                    return
                } catch (e: Exception) {
                    Log.e(TAG, "逐个创建删除请求仍失败", e)
                }
            }
        }
        // 回退：Android 10 及以下，或 createDeleteRequest 完全不可用
        Log.d(TAG, "走回退删除路径(contentResolver.delete)")
        lifecycleScope.launch(Dispatchers.IO) {
            for (uri in uris) {
                try {
                    val c = contentResolver.delete(uri, null, null)
                    Log.d(TAG, "contentResolver.delete $uri -> $c")
                } catch (e: Exception) {
                    Log.e(TAG, "contentResolver.delete 异常 $uri", e)
                }
            }
            withContext(Dispatchers.Main) { loadAllVideos() }
        }
    }

    /** 删除相册视频（从系统相册删除原视频，真实删除） */
    private fun confirmDeleteAlbumVideo(file: File) {
        val uri = getMediaStoreUriForFile(file)
        Log.d(TAG, "confirmDeleteAlbumVideo: file=${file.name}, uri=$uri")
        AlertDialog.Builder(this)
            .setTitle(R.string.delete)
            .setMessage("确定从系统相册删除视频「${file.name}」？此操作不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                if (uri != null) {
                    requestDeleteUris(listOf(uri as Uri))
                } else {
                    // 退化删除
                    try {
                        if (file.delete()) {
                            Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "删除失败，可能需要文件管理权限", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this, "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                    loadAllVideos()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 播放视频 ====================

    private fun playVideo(file: File) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("video_path", file.absolutePath)
            putExtra("is_encrypted", VideoEncryptor.isEncrypted(file))
        }
        startActivity(intent)
    }

    // ==================== 批量加密/解密 ====================

    private fun batchEncrypt() {
        val selected = adapter.getSelectedFiles()
        if (selected.isEmpty()) {
            Toast.makeText(this, "请先选择要加密的视频", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val total = selected.size
            ensureActive()
            withContext(Dispatchers.Main) {
                binding.progressBar.visibility = View.VISIBLE
                Toast.makeText(this@MainActivity, "正在加密 $total 个文件…", Toast.LENGTH_SHORT).show()
            }

            var success = 0
            var mirrorOk = 0
            var mirrorFail = 0
            var mirrorErr = ""
            val originalsToDelete = mutableListOf<File>()
            for (file in selected) {
                ensureActive()
                try {
                    if (VideoEncryptor.isEncrypted(file)) continue
                    // 先将原文件拷贝到私有目录，再在私有目录中加密，避免在原路径写入权限问题
                    val tempFile = File(appVideoDir, file.name)
                    if (!tempFile.exists()) {
                        file.copyTo(tempFile, overwrite = true)
                    }
                    val encryptedPath = VideoEncryptor.encrypt(tempFile.absolutePath)
                    if (encryptedPath != null) {
                        // 加密成功，删除临时明文文件
                        tempFile.delete()
                        success++
                        // 将加密后的 .enc 文件镜像到用户选定的备份目录，保证卸载后仍保留
                        val encFile = File(encryptedPath)
                        val mirrored = mirrorEncryptedToBackup(encFile)
                        Log.d(TAG, "加密+镜像: ${file.name} -> mirrored=$mirrored | lastMirrorError=$lastMirrorError")
                        if (mirrored) {
                            mirrorOk++
                        } else {
                            mirrorFail++
                            // 记录首个失败的真实原因
                            if (mirrorErr.isEmpty() && lastMirrorError.isNotEmpty()) {
                                mirrorErr = lastMirrorError
                            }
                        }
                        // 标记待删除系统相册中的原文件
                        originalsToDelete.add(file)
                    } else {
                        // 加密失败，清理临时文件
                        tempFile.delete()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            ensureActive()
            withContext(Dispatchers.Main) {
                binding.progressBar.visibility = View.GONE
                cancelMultiSelect()
                val mirrorMsg = if (backupTreeUri == null) {
                    "（未设置备份目录，加密视频仅存于本机，卸载即丢）"
                } else if (mirrorFail > 0) {
                    if (mirrorErr.isNotEmpty()) "（备份失败：$mirrorErr）"
                    else "（备份失败 $mirrorFail 个，点设置→查看备份文件排查）"
                } else if (mirrorOk > 0) {
                    "（已备份 $mirrorOk 个到 ${describeTreeUri(backupTreeUri!!)}）"
                } else ""
                Toast.makeText(this@MainActivity, "加密完成：$success/$total$mirrorMsg", Toast.LENGTH_LONG).show()
                loadAllVideos()
                // 切换到加密tab
                binding.tabLayout.getTabAt(1)?.select()

                // 加密后，真正删除系统相册里的原视频（弹系统确认框）
                val uris = originalsToDelete.mapNotNull { getMediaStoreUriForFile(it) }
                Log.d(TAG, "batchEncrypt: 待删除原文件 ${uris.size} 个")
                uris.forEachIndexed { i, u -> Log.d(TAG, "  enc uri[$i] = $u") }
                if (uris.isNotEmpty()) {
                    requestDeleteUris(uris)
                }
            }
        }
    }

    private fun batchDecrypt() {
        val selected = adapter.getSelectedFiles()
        if (selected.isEmpty()) {
            Toast.makeText(this, "请先选择要解密的视频", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            ensureActive()
            withContext(Dispatchers.Main) {
                binding.progressBar.visibility = View.VISIBLE
                Toast.makeText(this@MainActivity, "正在解密 ${selected.size} 个文件…", Toast.LENGTH_SHORT).show()
            }

            var success = 0
            for (file in selected) {
                ensureActive()
                try {
                    if (!VideoEncryptor.isEncrypted(file)) continue
                    val origName = file.name.removeSuffix(".enc")
                    val movieDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                    movieDir.mkdirs()
                    val decryptedFile = File(movieDir, origName)
                    if (VideoEncryptor.decryptToFile(file.absolutePath, decryptedFile.absolutePath)) {
                        try { file.delete() } catch (_: Exception) {}
                        success++
                        // 通知MediaStore扫描新文件
                        MediaScannerConnection.scanFile(
                            this@MainActivity,
                            arrayOf(decryptedFile.absolutePath),
                            null, null
                        )
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            ensureActive()
            withContext(Dispatchers.Main) {
                binding.progressBar.visibility = View.GONE
                cancelMultiSelect()
                Toast.makeText(this@MainActivity, "解密完成：$success/${selected.size}", Toast.LENGTH_LONG).show()
                loadAllVideos()
                // 切回相册tab
                binding.tabLayout.getTabAt(0)?.select()
            }
        }
    }

    /**
     * 将选中的加密视频（.enc）自动解码并移动到系统相册（Movies）。
     * 不会破坏任何原有视频：仅在相册生成一份解密后的副本，随后删除私有目录的 .enc 及其备份副本。
     */
    private fun batchMoveToAlbum() {
        val selected = adapter.getSelectedFiles()
        if (selected.isEmpty()) {
            Toast.makeText(this, "请先选择要移动的视频", Toast.LENGTH_SHORT).show()
            return
        }
        val encFiles = selected.filter { VideoEncryptor.isEncrypted(it) }
        if (encFiles.isEmpty()) {
            Toast.makeText(this, "请选择加密视频（.enc）后移动到相册", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.migrate_to_album)
            .setMessage("将选中的 ${encFiles.size} 个加密视频解码后保存到系统相册（Movies），并删除 App 内的加密副本及备份。\n不会破坏任何原有视频。")
            .setPositiveButton("确定移动") { _, _ -> doMoveToAlbum(encFiles) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doMoveToAlbum(encFiles: List<File>) {
        lifecycleScope.launch(Dispatchers.IO) {
            ensureActive()
            withContext(Dispatchers.Main) {
                binding.progressBar.visibility = View.VISIBLE
                Toast.makeText(this@MainActivity, "正在移动到相册 ${encFiles.size} 个…", Toast.LENGTH_SHORT).show()
            }

            var success = 0
            for (file in encFiles) {
                ensureActive()
                try {
                    // 1) 先解码到临时文件
                    val tmp = File(cacheDir, file.name.removeSuffix(".enc"))
                    if (VideoEncryptor.decryptToFile(file.absolutePath, tmp.absolutePath) && tmp.exists()) {
                        // 2) 写入系统相册（MediaStore，落库后会显示在相册中且卸载不丢）
                        val uri = insertVideoToAlbum(tmp, tmp.name)
                        if (uri != null) {
                            success++
                            // 3) 删除 App 内加密副本
                            try { file.delete() } catch (_: Exception) {}
                            // 4) 删除备份目录中的同名副本
                            deleteBackupEncMirror(file.name)
                        } else {
                            Log.e(TAG, "移动到相册失败(写入MediaStore失败): ${file.name}")
                        }
                        try { tmp.delete() } catch (_: Exception) {}
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "移动到相册异常: ${file.name} | ${e.message}", e)
                }
            }

            ensureActive()
            withContext(Dispatchers.Main) {
                binding.progressBar.visibility = View.GONE
                cancelMultiSelect()
                Toast.makeText(
                    this@MainActivity,
                    "已移动到相册：$success/${encFiles.size}",
                    Toast.LENGTH_LONG
                ).show()
                loadAllVideos()
                binding.tabLayout.getTabAt(0)?.select()
            }
        }
    }

    /**
     * 每项（相册或加密视频）的“更多”菜单：删除 / 迁移到另一端。
     * - 加密视频：迁移=解码回退到相册；删除=删 .enc 及备份。
     * - 相册视频：迁移=加密后移入 App 私有目录；删除=从系统相册删除。
     */
    private fun showItemMenu(file: File) {
        val isEnc = VideoEncryptor.isEncrypted(file)
        val anchor = binding.recyclerView
        val popup = PopupMenu(this, anchor)
        val migrateTitle = if (isEnc) {
            getString(R.string.migrate_to_album)
        } else {
            getString(R.string.migrate_to_encrypt)
        }
        popup.menu.add(Menu.NONE, 1, 1, migrateTitle)
        popup.menu.add(Menu.NONE, 2, 2, getString(R.string.delete))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    if (isEnc) {
                        // 解码回退到相册，只处理这一个文件
                        AlertDialog.Builder(this)
                            .setTitle(R.string.migrate_to_album)
                            .setMessage("将该加密视频解码后保存到系统相册（Movies），并删除 App 内的加密副本及备份。\n不会破坏任何原有视频。")
                            .setPositiveButton("确定") { _, _ -> doMoveToAlbum(listOf(file)) }
                            .setNegativeButton("取消", null)
                            .show()
                    } else {
                        // 加密后移入 App 私有目录
                        AlertDialog.Builder(this)
                            .setTitle(R.string.migrate_to_encrypt)
                            .setMessage("将该相册视频加密后移入 App 私有目录（${appVideoDir.name}）。\n将在系统相册中删除原视频。")
                            .setPositiveButton("确定") { _, _ -> doMoveToEncrypt(listOf(file)) }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                    true
                }
                2 -> {
                    if (isEnc) {
                        AlertDialog.Builder(this)
                            .setTitle(R.string.delete)
                            .setMessage("确定删除加密视频「${file.name}」？\n（同时删除其备份副本）")
                            .setPositiveButton("删除") { _, _ ->
                                try {
                                    file.delete()
                                    deleteBackupEncMirror(file.name)
                                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(this, "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                                loadAllVideos()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    } else {
                        confirmDeleteAlbumVideo(file)
                    }
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    /**
     * 将选中的相册视频加密后移入 App 私有目录（encryptDir），并从系统相册删除原视频。
     */
    private fun batchMoveToEncrypt() {
        val selected = adapter.getSelectedFiles()
        if (selected.isEmpty()) {
            Toast.makeText(this, "请先选择要加密的视频", Toast.LENGTH_SHORT).show()
            return
        }
        val albumFiles = selected.filter { !VideoEncryptor.isEncrypted(it) }
        if (albumFiles.isEmpty()) {
            Toast.makeText(this, "请选择相册视频后迁移到加密", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.migrate_to_encrypt)
            .setMessage("将选中的 ${albumFiles.size} 个相册视频加密后移入 App 私有目录（${appVideoDir.name}），并在系统相册中删除原视频。")
            .setPositiveButton("确定迁移") { _, _ -> doMoveToEncrypt(albumFiles) }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 将相册视频逐个加密到私有目录，然后删除系统相册中的原视频。
     */
    private fun doMoveToEncrypt(albumFiles: List<File>) {
        lifecycleScope.launch(Dispatchers.IO) {
            ensureActive()
            withContext(Dispatchers.Main) {
                binding.progressBar.visibility = View.VISIBLE
                Toast.makeText(this@MainActivity, "正在迁移到加密 ${albumFiles.size} 个…", Toast.LENGTH_SHORT).show()
            }

            var success = 0
            val originalsToDelete = mutableListOf<Uri>()
            for (file in albumFiles) {
                ensureActive()
                try {
                    // 加密后默认生成 file.absolutePath + ".enc"
                    val encPath = VideoEncryptor.encrypt(file.absolutePath)
                    val encFile = if (encPath != null) File(encPath) else null
                    if (encFile != null && encFile.exists()) {
                        val dest = File(appVideoDir, file.name + ".enc")
                        encFile.copyTo(dest, overwrite = true)
                        encFile.delete()
                        // 立即镜像到备份目录，保证卸载后仍在
                        try { mirrorEncryptedToBackup(dest) } catch (_: Exception) {}
                        success++
                        val uri = getMediaStoreUriForFile(file)
                        if (uri != null) originalsToDelete.add(uri)
                        // 删除系统相册中的原视频（避免重复）
                        try { file.delete() } catch (_: Exception) {}
                    } else {
                        Log.e(TAG, "迁移到加密失败(加密失败): ${file.name}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "迁移到加密异常: ${file.name} | ${e.message}", e)
                }
            }

            ensureActive()
            withContext(Dispatchers.Main) {
                binding.progressBar.visibility = View.GONE
                if (originalsToDelete.isNotEmpty()) {
                    requestDeleteUris(originalsToDelete)
                } else {
                    cancelMultiSelect()
                    loadAllVideos()
                }
                Toast.makeText(
                    this@MainActivity,
                    "已迁移到加密：$success/${albumFiles.size}",
                    Toast.LENGTH_LONG
                ).show()
                binding.tabLayout.getTabAt(1)?.select()
            }
        }
    }

    /** 将解码后的视频通过 MediaStore 写入系统相册 Movies，返回其 Uri（已落库） */
    private fun insertVideoToAlbum(src: File, displayName: String): Uri? {
        val resolver = contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val ext = displayName.substringAfterLast('.', "mp4").lowercase()
        val mime = when (ext) {
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "avi" -> "video/avi"
            "3gp" -> "video/3gpp"
            else -> "video/*"
        }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VideoWatch")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(collection, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { os ->
                src.inputStream().use { it.copyTo(os) }
            } ?: return null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            return uri
        } catch (e: Exception) {
            // 写入失败清理空记录
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            return null
        }
    }

    private fun batchDelete() {
        val selected = adapter.getSelectedFiles()
        if (selected.isEmpty()) {
            Toast.makeText(this, "请先选择要删除的视频", Toast.LENGTH_SHORT).show()
            return
        }
        val encCount = selected.count { VideoEncryptor.isEncrypted(it) }
        val msg = if (currentTab == 1 || encCount == selected.size) {
            "确定删除选中的 ${selected.size} 个加密视频吗？\n（将同时删除备份目录中的副本）"
        } else {
            "确定删除选中的 ${selected.size} 个视频吗？\n（相册中的原文件也会一并删除）"
        }
        AlertDialog.Builder(this)
            .setTitle("删除视频")
            .setMessage(msg)
            .setPositiveButton("删除") { _, _ -> doBatchDelete(selected) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doBatchDelete(files: List<File>) {
        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                binding.progressBar.visibility = View.VISIBLE
            }
            var deleted = 0
            val originalsToDeleteUris = mutableListOf<Uri>()
            for (file in files) {
                try {
                    // 1) 删除私有工作目录中的文件（明文副本或 .enc）
                    if (file.exists()) file.delete()
                    // 2) 删除备份目录中的 .enc 镜像副本（若存在）
                    if (VideoEncryptor.isEncrypted(file)) {
                        deleteBackupEncMirror(file.name)
                    }
                    deleted++
                    // 3) 相册视频：同时删除系统相册中的原文件
                    if (!VideoEncryptor.isEncrypted(file)) {
                        getMediaStoreUriForFile(file)?.let { originalsToDeleteUris.add(it) }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "删除失败: ${file.name}", e)
                }
            }

            withContext(Dispatchers.Main) {
                binding.progressBar.visibility = View.GONE
                cancelMultiSelect()
                Toast.makeText(this@MainActivity, "已删除 $deleted/${files.size}", Toast.LENGTH_LONG).show()
                loadAllVideos()
                // 若删除了相册原文件，走系统删除确认框
                if (originalsToDeleteUris.isNotEmpty()) {
                    requestDeleteUris(originalsToDeleteUris)
                }
            }
        }
    }

    /** 删除 SAF 备份目录中同名 .enc 镜像副本 */
    private fun deleteBackupEncMirror(name: String) {
        val uri = backupTreeUri ?: return
        try {
            val tree = DocumentFile.fromTreeUri(this, uri) ?: return
            val doc = tree.findFile(name) ?: return
            try {
                DocumentsContract.deleteDocument(contentResolver, doc.uri)
            } catch (e: Exception) {
                Log.w(TAG, "删除备份副本失败: $name", e)
            }
        } catch (_: Exception) {}
    }

    // ==================== 多选管理 ====================

    private fun cancelMultiSelect() {
        // 防御性保护：避免与 adapter 的 onSelectionChanged(0) 回调形成递归
        if (isMultiSelect) {
            isMultiSelect = false
            binding.bottomBar.visibility = View.GONE
        }
        adapter.cancelSelection()
    }

    private fun updateSelectionUI() {
        val count = adapter.getSelectedCount()
        binding.tvSelectedCount.text = "已选 $count 项"

        // 选中项为0时退出多选
        if (count == 0 && isMultiSelect) {
            cancelMultiSelect()
        }
    }

    // ==================== 加密视频的备份目录（SAF，卸载不丢失） ====================

    private fun ensureNomedia() {
        // 私有工作目录本身就不会被相册扫描（在 Android/data/ 下），加 .nomedia 多做一层保障
        val nomedia = File(appVideoDir, ".nomedia")
        if (!nomedia.exists()) nomedia.createNewFile()
    }

    /**
     * 读取已保存的备份目录 tree Uri。
     * 注：部分 ROM（如小米/HyperOS）上 takePersistableUriPermission 会抛异常，
     * 导致 persistedUriPermissions 中查不到该 uri；但 ACTION_OPEN_DOCUMENT_TREE
     * 返回的那个 tree uri 在本次/后续会话通常仍可直接访问。
     * 因此这里直接信任已保存的 uri，由真实的 IO 操作判断可用性，而不依赖
     * persistedUriPermissions 列表（否则会误判为 null 而跳过所有镜像/恢复）。
     * 仅在"卸载重装"后（SharedPreferences 被清除）才返回 null，此时由
     * ensureBackupFolderOrPrompt() 引导用户重新选择同一目录即可找回文件。
     */
    private val backupTreeUri: Uri?
        get() {
            val s = prefs.getString("backup_tree_uri", null) ?: return null
            return Uri.parse(s)
        }

    /** 记录最近一次镜像写入失败的真实原因，供加密完成时的 Toast 直接显示 */
    private var lastMirrorError: String = ""

    /** 引导用户选择备份目录（首次运行 / 卸载重装后） */
    private fun ensureBackupFolderOrPrompt() {
        if (backupTreeUri != null) return
        if (backupPromptPending) return

        // 检测是否是重装（之前设置过但权限失效了）
        val hadBackupBefore = prefs.contains("backup_tree_uri")

        backupPromptPending = true
        val title = if (hadBackupBefore) "检测到您之前设置过备份目录" else "选择加密视频备份目录"
        val msg = if (hadBackupBefore)
            "App 更新或重装后需要重新授权备份目录。\n\n请选择与之前相同的文件夹（如 Download/VideoWatch），加密视频会自动恢复。"
        else
            "为了让加密视频在 App 更新或卸载重装后仍然保留，请选择一个保存目录（建议进入 Download，新建/选择 VideoWatch 文件夹）。\n\n该目录中的文件由你完全掌控，可随时用文件管理器手动删除。"
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("选择目录") { _, _ -> launchBackupPicker() }
            .setNegativeButton("暂时跳过") { _, _ ->
                backupPromptPending = false
                Toast.makeText(
                    this,
                    "未设置备份目录：加密视频仅保存在应用私有空间，卸载后将无法恢复",
                    Toast.LENGTH_LONG
                ).show()
            }
            .show()
    }

    private fun launchBackupPicker() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
            }
            backupFolderLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开目录选择器", Toast.LENGTH_SHORT).show()
            backupPromptPending = false
        }
    }

    private val backupFolderLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        backupPromptPending = false
        if (result.resultCode != RESULT_OK) {
            Toast.makeText(this, "未选择备份目录，加密视频将无法在卸载后保留", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        val uri = result.data?.data ?: run {
            Toast.makeText(this, "未选择备份目录", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            Log.d(TAG, "[备份] takePersistableUriPermission 成功: $uri")
        } catch (e: Exception) {
            Log.w(TAG, "[备份] takePersistableUriPermission 失败（将依赖会话内权限）：可能重启后失效", e)
        }
        prefs.edit().putString("backup_tree_uri", uri.toString()).apply()
        // 选择后立即恢复 + 把已有的加密文件镜像过去，并刷新列表
        lifecycleScope.launch(Dispatchers.IO) {
            restoreEncryptedFromBackup(uri)
            mirrorAllExistingEncrypted()
            withContext(Dispatchers.Main) { loadAllVideos() }
        }
    }

    /**
     * 将已加密的 .enc 文件镜像到用户选定的备份目录。
     * 该目录属于用户、不在 App 私有区，因此即使 App 被卸载，文件依然保留，
     * 重装后只需重新选择同一目录即可由 restoreEncryptedFromBackup() 自动恢复。
     *
     * @return true=镜像成功/已存在, false=失败
     */
    /**
     * 将已加密的 .enc 文件镜像到用户选定的备份目录。
     * 该目录属于用户、不在 App 私有区，因此即使 App 被卸载，文件依然保留，
     * 重装后只需重新选择同一目录即可由 restoreEncryptedFromBackup() 自动恢复。
     *
     * 采用多策略写入：依次尝试不同 MIME 的 createFile、直接 DocumentsContract.createDocument、
     * 以及真实文件路径兜底，并记录每一步的真实失败原因到 lastMirrorError。
     *
     * @return true=镜像成功/已存在, false=失败
     */
    private fun mirrorEncryptedToBackup(src: File): Boolean {
        lastMirrorError = ""
        val uri = backupTreeUri ?: run {
            lastMirrorError = "未配置备份目录"
            Log.w(TAG, "[镜像] 未配置备份目录，跳过: ${src.name} | prefs含key=${prefs.contains("backup_tree_uri")}")
            return false
        }
        Log.d(TAG, "[镜像] 开始: ${src.name} (存在=${src.exists()}, 加密=${VideoEncryptor.isEncrypted(src)}, 大小=${src.length()}) treeUri=$uri")
        if (!src.exists()) {
            lastMirrorError = "源文件不存在"
            Log.w(TAG, "[镜像] 源文件不存在: ${src.absolutePath}")
            return false
        }
        if (!VideoEncryptor.isEncrypted(src)) {
            lastMirrorError = "不是加密文件"
            Log.w(TAG, "[镜像] 不是加密文件，跳过: ${src.name}")
            return false
        }
        try {
            val tree = DocumentFile.fromTreeUri(this, uri)
                ?: run { lastMirrorError = "无法打开备份树(目录可能已被移动/删除)"; Log.e(TAG, "[镜像] 无法打开备份树 uri=$uri"); return false }
            Log.d(TAG, "[镜像] 打开树成功: exists=${tree.exists()}, canWrite=${tree.canWrite()}, uri=${tree.uri}")
            if (!tree.canWrite()) {
                lastMirrorError = "备份目录无写入权限(canWrite=false)，请重新选择目录并允许“允许访问”"
            }

            // 1) 已存在同名且大小一致 -> 跳过
            val existing = tree.findFile(src.name)
            if (existing != null && existing.exists() && existing.isFile) {
                Log.d(TAG, "[镜像] 已存在同名文件: ${src.name} (备份size=${existing.length()}, 源size=${src.length()})")
                if (existing.length() == src.length()) return true
                // 大小不一致，覆盖
                if (writeStream(existing.uri, src)) {
                    Log.d(TAG, "[镜像] 已覆盖大小不一致的文件: ${src.name}")
                    return true
                }
                Log.w(TAG, "[镜像] 覆盖失败，尝试重新创建: ${src.name}")
            }

            // 2) 多策略创建并写入
            if (createAndWrite(tree, uri, src)) {
                val targetDoc = tree.findFile(src.name)
                val writtenOk = targetDoc?.exists() == true && targetDoc.isFile && targetDoc.length() > 0
                Log.d(TAG, "[镜像] 完成: ${src.name} -> writtenOk=$writtenOk, size=${targetDoc?.length()}")
                return writtenOk
            }

            // 3) 兜底：直接按真实文件路径写入（部分 ROM 下可行）
            val fallback = writeViaRealPath(uri, src)
            Log.d(TAG, "[镜像] 真实路径兜底结果: $fallback (${src.name})")
            if (!fallback && lastMirrorError.isEmpty()) {
                lastMirrorError = "所有写入策略均失败(createFile/createDocument/真实路径)，请尝试更换备份目录或查看日志"
            }
            return fallback
        } catch (e: Exception) {
            lastMirrorError = "${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "[镜像] 异常: ${src.name} | ${e.javaClass.simpleName}: ${e.message}", e)
            return false
        }
    }

    /** 依次尝试多种 MIME 的 createFile 与直接 createDocument，任一成功即返回 true */
    private fun createAndWrite(tree: DocumentFile, treeUri: Uri, src: File): Boolean {
        for (mime in MIRROR_MIME_CANDIDATES) {
            try {
                val target = tree.createFile(mime, src.name)
                if (target != null) {
                    if (writeStream(target.uri, src)) {
                        Log.d(TAG, "[镜像] createFile 成功(mime=$mime): ${src.name}")
                        return true
                    } else {
                        Log.w(TAG, "[镜像] createFile 成功但写入失败(mime=$mime): ${src.name}")
                        if (lastMirrorError.isEmpty()) lastMirrorError = "createFile 成功但 openOutputStream 失败(mime=$mime)"
                    }
                } else {
                    Log.w(TAG, "[镜像] createFile 返回null(mime=$mime): ${src.name}")
                    if (lastMirrorError.isEmpty()) lastMirrorError = "createFile 返回null(mime=$mime)，目录可能禁止写入"
                }
            } catch (e: Exception) {
                Log.w(TAG, "[镜像] createFile 异常(mime=$mime): ${e.javaClass.simpleName}: ${e.message}")
                if (lastMirrorError.isEmpty()) lastMirrorError = "createFile 异常: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
        // 再尝试 DocumentsContract.createDocument 直接调用（不同 MIME）
        for (mime in MIRROR_MIME_CANDIDATES) {
            try {
                val parentDocId = DocumentsContract.getTreeDocumentId(treeUri)
                val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
                val newUri = DocumentsContract.createDocument(contentResolver, parentUri, mime, src.name)
                if (newUri != null && writeStream(newUri, src)) {
                    Log.d(TAG, "[镜像] createDocument 成功(mime=$mime): ${src.name}")
                    return true
                } else if (newUri == null && lastMirrorError.isEmpty()) {
                    lastMirrorError = "DocumentsContract.createDocument 返回null(mime=$mime)"
                }
            } catch (e: Exception) {
                Log.w(TAG, "[镜像] createDocument 异常(mime=$mime): ${e.javaClass.simpleName}: ${e.message}")
                if (lastMirrorError.isEmpty()) lastMirrorError = "createDocument 异常: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
        return false
    }

    private fun writeStream(targetUri: Uri, src: File): Boolean {
        return try {
            val os = contentResolver.openOutputStream(targetUri, "rwt") ?: return false
            os.use { outputStream -> src.inputStream().use { it.copyTo(outputStream) } }
            true
        } catch (e: Exception) {
            Log.w(TAG, "[镜像] writeStream 异常: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /** 兜底：把 tree document id 还原成真实文件路径后直接用 File 写入（需该路径可写） */
    private fun writeViaRealPath(treeUri: Uri, src: File): Boolean {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri) // 例: primary:Download/VideoWatch
            val path = docIdToRealPath(docId) ?: return false
            val dir = File(path)
            if (!dir.exists() && !dir.mkdirs()) return false
            if (!dir.canWrite()) {
                lastMirrorError = "真实路径不可写: $path"
                return false
            }
            src.copyTo(File(dir, src.name), overwrite = true)
            true
        } catch (e: Exception) {
            Log.w(TAG, "[镜像] 真实路径写入异常: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /** primary:Download/VideoWatch -> /storage/emulated/0/Download/VideoWatch */
    private fun docIdToRealPath(docId: String): String? {
        return try {
            val decoded = Uri.decode(docId)
            if (!decoded.contains(":")) return null
            val (volume, rest) = decoded.split(":", limit = 2)
            val base = when (volume) {
                "primary" -> Environment.getExternalStorageDirectory().absolutePath
                else -> "/storage/$volume"
            }
            if (rest.startsWith("/")) "$base$rest" else "$base/$rest"
        } catch (_: Exception) { null }
    }

    companion object {
        // 尝试多种 MIME，规避部分 ROM 对 application/octet-stream 的拒绝
        private val MIRROR_MIME_CANDIDATES = arrayOf(
            "application/octet-stream", "*/*", "video/mp4", "application/vnd.android.package-archive"
        )
    }

    /** 把当前私有目录中已有的所有 .enc 文件都镜像到备份目录（用于刚配置好备份目录时） */
    private fun mirrorAllExistingEncrypted() {
        try {
            appVideoDir.listFiles()?.forEach { f ->
                if (f.isFile && VideoEncryptor.isEncrypted(f)) mirrorEncryptedToBackup(f)
            }
        } catch (_: Exception) {}
    }

    /** 带返回值的版本，供设置对话框显示结果 */
    private fun mirrorAllExistingEncryptedWithResult(): String {
        var ok = 0
        var fail = 0
        var skip = 0
        val uri = backupTreeUri
        if (uri == null) return "未设置备份目录"
        try {
            appVideoDir.listFiles()?.forEach { f ->
                if (!f.isFile || !VideoEncryptor.isEncrypted(f)) return@forEach
                when {
                    mirrorEncryptedToBackup(f) -> ok++
                    else -> { fail++; Log.e(TAG, "[立即备份] 失败: ${f.name}") }
                }
            }
            if (ok + fail == 0) skip = 1 // 无加密文件可备
        } catch (e: Exception) {
            return "异常: ${e.message}"
        }
        return when {
            skip > 0 -> "没有需要备份的加密视频"
            fail > 0 && ok > 0 -> "成功 $ok 个，失败 $fail 个（查看日志排查）"
            fail > 0 -> "全部失败 $fail 个（查看日志或检查权限）"
            else -> "全部成功，共备份 $ok 个文件到 ${describeTreeUri(uri)}"
        }
    }

    /**
     * 从用户选定的备份目录恢复上次保留的加密视频到应用工作目录，
     * 之后即可在"加密视频"中正常显示与播放。
     */
    private fun restoreEncryptedFromBackup(treeUri: Uri): Int {
        var restored = 0
        var skipped = 0
        Log.d(TAG, "[恢复] 开始从备份目录恢复: ${describeTreeUri(treeUri)}")
        try {
            val tree = DocumentFile.fromTreeUri(this, treeUri)
            if (tree == null || !tree.exists()) {
                Log.e(TAG, "[恢复] 无法打开备份树 uri=$treeUri")
                return 0
            }
            val allFiles = tree.listFiles()
            if (allFiles.isEmpty()) {
                Log.w(TAG, "[恢复] 备份目录为空")
                return 0
            }
            for (doc in allFiles) {
                val name = doc.name ?: continue
                if (!name.endsWith(".enc")) { skipped++; continue }
                if (!doc.isFile) { skipped++; continue }

                val dest = File(appVideoDir, name)
                if (dest.exists()) {
                    Log.d(TAG, "[恢复] 跳过（已存在）: $name (${dest.length()}B)")
                    skipped++
                    continue
                }
                try {
                    contentResolver.openInputStream(doc.uri)?.use { ins ->
                        dest.outputStream().use { outs -> ins.copyTo(outs) }
                    }?.let {
                        restored++
                        Log.d(TAG, "[恢复] 成功: $name (${dest.length()}B)")
                    } ?: Log.e(TAG, "[恢复] openInputStream 返回 null: ${doc.uri}")
                } catch (e: Exception) {
                    Log.e(TAG, "[恢复] 失败: $name", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[恢复] 异常", e)
        }
        Log.d(TAG, "[恢复] 完成: 恢复=$restored, 跳过=$skipped")
        // 恢复完成后 Toast 通知主线程
        if (restored > 0) {
            binding.root.post {
                Toast.makeText(this, "已从备份目录恢复 $restored 个加密视频", Toast.LENGTH_LONG).show()
            }
        }
        return restored
    }

    /** 将 tree Uri 转成人类可读的相对路径，如 primary:Download/VideoWatch -> Download/VideoWatch */
    private fun describeTreeUri(uri: Uri): String {
        return try {
            DocumentsContract.getTreeDocumentId(uri).removePrefix("primary:")
        } catch (_: Exception) {
            "已设置"
        }
    }

    /** 设置对话框：查看/更改备份目录，查看备份文件，立即备份 */
    private fun showSettingsDialog() {
        val uri = backupTreeUri
        val prefsHasKey = prefs.contains("backup_tree_uri")
        val status = when {
            uri != null -> "当前备份目录：${describeTreeUri(uri)}"
            prefsHasKey -> "已记录目录但当前不可用（权限可能失效），请重新选择"
            else -> "尚未设置备份目录（加密视频将无法在卸载后保留）"
        }

        // 无论是否配置，都显示按钮，确保点击一定有反馈
        AlertDialog.Builder(this)
            .setTitle("设置")
            .setMessage("$status\n\n点击「查看备份文件」可确认备份是否生效；未设置时请先选目录。")
            .setPositiveButton("更改/选择备份目录") { _, _ -> launchBackupPicker() }
            .setNeutralButton("查看备份文件") { _, _ -> showBackupFilesInfo() }
            .setNegativeButton(if (uri != null) "立即备份现有视频" else "取消") { _, _ ->
                if (uri != null) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val result = mirrorAllExistingEncryptedWithResult()
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "备份完成：$result", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .show()
    }

    /** 查看备份目录中有哪些文件（内部读取 backupTreeUri，null 时直接提示） */
    private fun showBackupFilesInfo() {
        val uri = backupTreeUri
        if (uri == null) {
            Toast.makeText(this, "尚未选择备份目录，请先点「更改/选择备份目录」", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "正在读取备份目录…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val tree = DocumentFile.fromTreeUri(this@MainActivity, uri)
                val sb = StringBuilder()
                var totalSize = 0L
                if (tree == null || !tree.exists()) {
                    sb.appendLine("无法访问备份目录！\n请点「更改/选择备份目录」重新选择同一文件夹。")
                } else {
                    val files = tree.listFiles().filter { it.isFile && it.name?.endsWith(".enc") == true }
                    if (files.isEmpty()) {
                        sb.appendLine("备份目录为空（没有 .enc 文件）。\n\n可能原因：\n1. 镜像从未成功\n2. 文件被手动删除\n\n建议：点「立即备份现有视频」补一份，或先加密一个视频。")
                    } else {
                        files.forEach { f ->
                            val sizeMB = f.length() / 1024.0 / 1024.0
                            totalSize += f.length()
                            sb.appendLine("${f.name}  (${String.format("%.1f", sizeMB)} MB)")
                        }
                        sb.appendLine("\n共 ${files.size} 个文件，${String.format("%.1f", totalSize / 1024.0 / 1024.0)} MB")
                        sb.appendLine("\n卸载 App 后这些文件不会被清除，重装 App 后选择同一目录即可自动恢复。")
                    }
                }
                val text = sb.toString()
                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("备份文件列表 (${describeTreeUri(uri)})")
                        .setMessage(text)
                        .setPositiveButton("确定", null)
                        .show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("读取失败")
                        .setMessage("读取备份目录异常: ${e.message}")
                        .setPositiveButton("确定", null)
                        .show()
                }
            }
        }
    }

    // ==================== 生命周期 ====================

    override fun onResume() {
        super.onResume()
        // 每次回到主界面刷新列表（可能在其他地方新增或删除了视频）
        loadAllVideos()
    }

    override fun onDestroy() {
        loadJob?.cancel()
        super.onDestroy()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (isMultiSelect) {
            cancelMultiSelect()
        } else {
            super.onBackPressed()
        }
    }
}
