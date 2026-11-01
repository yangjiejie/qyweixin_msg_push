package com.videowatch.app

import android.Manifest
import android.app.AlertDialog
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.videowatch.app.adapter.VideoFileAdapter
import com.videowatch.app.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import java.io.File

class MainActivity : AppCompatActivity() {

    private val TAG = "VideoWatch"

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: VideoFileAdapter

    // 两个独立列表
    private val albumVideos = mutableListOf<File>()       // 相册视频（MediaStore）
    /** 相册视频 path -> MediaStore content Uri 映射，用于缩略图按 Uri 取帧（Android 11+ 下按路径取帧易失败） */
    private val albumUriMap = mutableMapOf<String, Uri>()
    private val privacyVideos = mutableListOf<File>()   // 隐私视频（可配置的公共文件夹中的普通视频，默认 Download/VideoWatch）
    // 当前展示的列表
    private var currentTab = 0 // 0=相册视频, 1=隐私视频

    private val prefs by lazy { getSharedPreferences("video_watch_prefs", MODE_PRIVATE) }
    private val KEY_PRIVATE_DIR = "private_dir"

    /**
     * 私密视频目录（可配置的公共文件夹）。
     * 默认指向我们设定的 Download/VideoWatch（公共存储，文件管理器可直接访问、重装后不丢）。
     * 用户可在「设置」中通过系统目录选择器更换，路径持久化在 SharedPreferences。
     * 注意：公共文件夹在 Android 11+ 需要「所有文件访问」权限才能读写（见 backupReady()）。
     */
    private val appVideoDir: File
        get() {
            val saved = prefs.getString(KEY_PRIVATE_DIR, null)
            val dir = if (saved.isNullOrEmpty()) backupDir else File(saved)
            if (!dir.exists()) {
                try { dir.mkdirs() } catch (_: Exception) {}
            }
            return dir
        }
    private var loadJob: Job? = null
    private var isMultiSelect = false

    /** 隐私视频 path -> MediaStore content Uri 映射（缩略图/播放/删除均优先用 Uri） */
    private val privacyUriMap = mutableMapOf<String, Uri>()

    // 权限请求
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            loadAllVideos()
            maybeGuidePrivacyPermission()
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
        // 确保工作目录没有 .nomedia（有它会阻止媒体库索引私密视频）
        ensureNomedia()
    }

    /**
     * 首次安装时引导用户授予「所有文件访问」权限。
     * 私密视频文件夹是公共目录（Download/VideoWatch）：
     * - 查看/播放已导入的私密视频：只需普通读取权限（MediaStore），不强制要求；
     * - 新增/迁移/删除私密视频、清理 .nomedia：必须「所有文件访问」权限。
     * 每次安装仅提示一次，用户可稍后在 设置 中再次授予。
     */
    private fun maybeGuidePrivacyPermission() {
        if (backupReady()) return
        val key = "guided_manage_perm"
        if (prefs.getBoolean(key, false)) return
        prefs.edit().putBoolean(key, true).apply()
        AlertDialog.Builder(this)
            .setTitle("授权私密视频文件夹")
            .setMessage(
                "私密视频保存在：${appVideoDir.absolutePath}\n\n" +
                    "为了能够导入/迁移/删除私密视频，需要授予「所有文件访问」权限。\n" +
                    "（不授权也可查看和播放已有的私密视频）"
            )
            .setPositiveButton("去授予") { _, _ -> openManageAllFilesAccess() }
            .setNegativeButton("暂不", null)
            .show()
    }

    // ==================== 全屏沉浸 ====================

    private fun setupFullscreen() {
        // 隐藏状态栏，全屏显示
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.statusBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // 状态栏已隐藏，顶栏不再预留整个状态栏高度（否则标题下方会多一条空黑边）。
        // 只按真实 insets 避让：有刘海/打孔屏时留出必要的 cutout 高度，否则标题直接贴顶。
        ViewCompat.setOnApplyWindowInsetsListener(binding.topBar) { v, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val status = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            // 状态栏隐藏时 status.top 通常为 0；两者取最大，仅避让物理遮挡（刘海/打孔）
            val top = maxOf(cutout.top, status.top)
            v.setPadding(v.paddingLeft, top, v.paddingRight, v.paddingBottom)
            insets
        }
        // 立即请求一次 insets，确保监听器生效
        ViewCompat.requestApplyInsets(binding.topBar)
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
            onItemMenuClick = { file, anchor -> showItemMenu(file, anchor) }
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
        binding.btnBatchDelete.setOnClickListener { batchDelete() }
        binding.btnBatchMoveToAlbum.setOnClickListener { batchMoveToAlbum() }
        binding.btnBatchMoveToPrivacy.setOnClickListener { batchMoveToPrivacy() }
        binding.btnSelectAll.setOnClickListener { adapter.selectAll() }
    }

    /**
     * 根据当前页签决定批量操作栏显示哪些按钮：
     * - 相册视频页：全选 / 迁入隐私 / 删除
     * - 隐私视频页：全选 / 迁回相册 / 删除
     */
    private fun updateBatchMigrateButton() {
        val isAlbumTab = currentTab == 0
        binding.btnBatchMoveToPrivacy.visibility = if (isAlbumTab) View.VISIBLE else View.GONE
        binding.btnBatchMoveToAlbum.visibility = if (isAlbumTab) View.GONE else View.VISIBLE
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
            maybeGuidePrivacyPermission()
        } else {
            permissionLauncher.launch(arrayOf(permission))
        }
    }

    // ==================== 加载视频列表 ====================

    /**
     * 读取私密视频目录（公共文件夹 Download/VideoWatch）下的视频，两个来源合并去重：
     * 1) File 直接列目录（已授予「所有文件访问」时可用，能覆盖未被媒体库索引的文件）；
     * 2) MediaStore 查询（只需 READ_MEDIA_VIDEO 普通权限，重装后即可恢复显示；
     *    若目录中残留 .nomedia 会导致媒体库不索引，此时来源 1 可兜底，且 ensureNomedia 会清掉 .nomedia）。
     * 返回：隐私视频 File 列表，以及 path -> content Uri 映射（用于缩略图/播放/删除）。
     */
    private fun queryPrivacyVideos(): Pair<List<File>, Map<String, Uri>> {
        val files = LinkedHashMap<String, File>() // path -> file，按路径去重
        val uriMap = mutableMapOf<String, Uri>()
        val dirPath = appVideoDir.absolutePath

        // 来源 1：File 直接读取
        try {
            appVideoDir.listFiles()?.forEach { f ->
                if (!f.isDirectory && !f.name.startsWith(".") &&
                    isVideoFile(f) && !f.name.endsWith(".enc")
                ) {
                    files[f.absolutePath] = f
                }
            }
        } catch (_: Exception) {
        }

        // 来源 2：MediaStore 查询
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DISPLAY_NAME
        )
        val selection = "${MediaStore.Video.Media.DATA} LIKE ?"
        val selectionArgs = arrayOf("$dirPath/%")
        try {
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection, selection, selectionArgs, null
            )?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val dataIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                while (c.moveToNext()) {
                    val path = c.getString(dataIdx) ?: continue
                    val file = File(path)
                    if (file.exists() && file.isFile && isVideoFile(file) && !file.name.endsWith(".enc")) {
                        files[path] = file
                        val id = c.getLong(idIdx)
                        uriMap[path] = ContentUris.withAppendedId(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "queryPrivacyVideos 异常", e)
        }
        return files.values.toList() to uriMap
    }

    private fun loadAllVideos() {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch(Dispatchers.IO) {
            binding.progressBar.post { binding.progressBar.visibility = View.VISIBLE }

            // 0. 清理历史遗留的 .enc 文件（加密功能已移除，按用户选择直接丢弃，不保留）
            cleanupLegacyEnc()

            // 1. 加载隐私视频（通过 MediaStore 读取我们设定的公共文件夹，无需“所有文件访问”权限）
            val (privacyList, privacyUris) = queryPrivacyVideos()
            privacyUriMap.clear()
            privacyUriMap.putAll(privacyUris)

            // 2. 从 MediaStore 加载所有设备视频，并隐藏已在隐私目录中的同名项（避免重复显示）
            albumUriMap.clear()
            val privateFileNames = privacyList.map { it.name }.toSet()
            val albumList = queryMediaStoreVideos(albumUriMap, privateFileNames)

            withContext(Dispatchers.Main) {
                albumVideos.clear()
                albumVideos.addAll(albumList)
                privacyVideos.clear()
                privacyVideos.addAll(privacyList)

                // 缩略图统一用 content Uri 取帧更可靠（相册 + 隐私合并）
                adapter.setAlbumUriMap(albumUriMap + privacyUriMap)

                binding.progressBar.visibility = View.GONE
                switchTab()
            }
        }
    }

    private fun queryMediaStoreVideos(
        uriMap: MutableMap<String, Uri>,
        privateFileNames: Set<String>
    ): List<File> {
        val files = mutableListOf<File>()
        try {
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
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
                val idIndex = it.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val dataIndex = it.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                val nameIndex = it.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                while (it.moveToNext()) {
                    val path = it.getString(dataIndex)
                    val name = it.getString(nameIndex)
                    if (path != null) {
                        val file = File(path)
                        // 如果该视频在隐私目录中已有副本，从相册中隐藏
                        if (name != null && privateFileNames.contains(name)) continue
                        if (file.exists() && file.isFile && isVideoFile(file)) {
                            files.add(file)
                            // 记录 path -> content Uri，缩略图优先按 Uri 取帧（Android 11+ 下按路径取帧易失败）
                            val id = it.getLong(idIndex)
                            uriMap[path] = ContentUris.withAppendedId(
                                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return files
    }

    private fun isVideoFile(file: File): Boolean {
        val name = file.name.lowercase()
        return name.endsWith(".mp4") || name.endsWith(".avi") || name.endsWith(".mkv") ||
            name.endsWith(".mov") || name.endsWith(".wmv") || name.endsWith(".flv") ||
            name.endsWith(".webm") || name.endsWith(".3gp") || name.endsWith(".m4v") ||
            name.endsWith(".ts")
    }

    // ==================== Tab切换 ====================

    private fun switchTab() {
        val currentList = if (currentTab == 0) albumVideos else privacyVideos

        // 退出多选
        if (isMultiSelect) cancelMultiSelect()

        adapter.setShowItemMenu(true) // 相册与隐私视频页签都显示“更多”菜单
        adapter.updateFiles(currentList.toList())
        updateBatchMigrateButton()

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
                binding.tvEmpty.text = "暂无隐私视频"
                binding.tvEmptyHint.text = "请先在「相册视频」中选择视频\n然后长按进入多选模式迁入隐私"
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
        if (!ensurePrivacyDirWritable()) return
        try {
            importLauncher.launch(arrayOf("video/*"))
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun importFromFile() {
        if (!ensurePrivacyDirWritable()) return
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

    /** 选择/更换私密视频文件夹（系统目录选择器） */
    private val pickFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> onPrivacyFolderPicked(uri) }

    /** 打开/跳转私密视频文件夹（仅查看，不持久化） */
    private val openFolderLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* 仅用于跳转到文件夹查看，无需处理返回结果 */ }

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
                // 切换到隐私视频tab
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
            .setMessage("视频已成功导入到隐私目录，是否删除相册中的原文件以释放空间？")
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
            // 隐私视频通过 MediaStore content Uri 播放，无需“所有文件访问”权限
            privacyUriMap[file.absolutePath]?.let { putExtra("video_uri", it.toString()) }
        }
        startActivity(intent)
    }

    // ==================== 隐私视频迁移 ====================

    /**
     * 批量迁回相册：将选中的隐私视频（App 私有目录中的普通视频）复制到系统相册（Movies），
     * 并删除 App 内的副本。不会破坏任何原有视频。
     */
    private fun batchMoveToAlbum() {
        val selected = adapter.getSelectedFiles()
        if (selected.isEmpty()) {
            Toast.makeText(this, "请先选择要移动的视频", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.migrate_to_album)
            .setMessage("将选中的 ${selected.size} 个隐私视频保存到系统相册（Movies），并删除 App 内的副本。\n不会破坏任何原有视频。")
            .setPositiveButton("确定移动") { _, _ -> doMovePlainFilesToAlbum(selected) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 批量将相册视频迁移到 App 私有目录作为隐私视频（复制成功后删除相册原视频） */
    private fun batchMoveToPrivacy() {
        val selected = adapter.getSelectedFiles()
        if (selected.isEmpty()) {
            Toast.makeText(this, "请先选择要加入隐私的视频", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.migrate_to_encrypt)
            .setMessage("将选中的 ${selected.size} 个相册视频移动到 App 私有目录作为隐私视频。\n系统相册中的原视频将被删除。")
            .setPositiveButton("确定") { _, _ -> moveAlbumVideoToPrivacy(selected) }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 清理历史遗留的 .enc 文件：加密功能已移除，按用户选择直接丢弃，不保留。
     * 同时清理 App 私有目录与备份目录（Download/VideoWatch）中的 .enc。
     */
    private fun cleanupLegacyEnc() {
        try {
            appVideoDir.listFiles()?.forEach { f -> if (f.name.endsWith(".enc")) f.delete() }
        } catch (_: Exception) {}
        try {
            val backup = backupDir
            if (backup.exists()) {
                backup.listFiles()?.forEach { f -> if (f.name.endsWith(".enc")) f.delete() }
            }
        } catch (_: Exception) {}
    }

    /** 将私有目录中的明文视频副本批量移回系统相册 */
    private fun doMovePlainFilesToAlbum(files: List<File>) {
        lifecycleScope.launch(Dispatchers.IO) {
            var success = 0
            for (file in files) {
                try {
                    val uri = insertVideoToAlbum(file, file.name)
                    if (uri != null) {
                        file.delete()
                        success++
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "移动明文副本到相册异常: ${file.name}", e)
                }
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@MainActivity,
                    "已移动到相册：$success/${files.size}",
                    Toast.LENGTH_LONG
                ).show()
                loadAllVideos()
                binding.tabLayout.getTabAt(0)?.select()
            }
        }
    }

    /**
     * 每项视频的“更多”菜单，内容随分页不同：
     * - 相册视频页：迁入隐私（复制到 App 私有目录）/ 删除（从系统相册删除）
     * - 隐私视频页：迁回相册 / 删除（删 App 内文件）
     *
     * 使用自定义 PopupWindow（深色底 + 白字），避免 PopupMenu 在系统深浅色
     * 模式下出现文字与背景对比度不足、“颜色太暗看不清”的问题。
     */
    private fun showItemMenu(file: File, anchor: View) {
        val isAlbumTab = currentTab == 0

        val view = LayoutInflater.from(this).inflate(R.layout.popup_item_menu, null)
        val tvMigrate = view.findViewById<TextView>(R.id.tvMenuMigrate)
        val tvDelete = view.findViewById<TextView>(R.id.tvMenuDelete)

        if (isAlbumTab) {
            tvMigrate.setText(R.string.migrate_to_encrypt)
            tvMigrate.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_add_video, 0, 0, 0)
        } else {
            tvMigrate.setText(R.string.migrate_to_album)
            tvMigrate.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_add_video, 0, 0, 0)
        }

        val popup = PopupWindow(
            view,
            (232 * resources.displayMetrics.density).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            isFocusable = true
            elevation = 12 * resources.displayMetrics.density
        }

        tvMigrate.setOnClickListener {
            popup.dismiss()
            if (isAlbumTab) {
                // 相册视频 -> 复制到 App 私有目录（隐私）
                AlertDialog.Builder(this)
                    .setTitle(R.string.migrate_to_encrypt)
                    .setMessage("将该相册视频移动到 App 私有目录（${appVideoDir.name}）作为隐私视频。\n系统相册中的原视频将被删除。")
                    .setPositiveButton("确定") { _, _ -> moveAlbumVideoToPrivacy(listOf(file)) }
                    .setNegativeButton("取消", null)
                    .show()
            } else {
                // 隐私视频 -> 移回系统相册
                AlertDialog.Builder(this)
                    .setTitle(R.string.migrate_to_album)
                    .setMessage("将该隐私视频保存到系统相册（Movies），并删除 App 内的副本。\n不会破坏任何原有视频。")
                    .setPositiveButton("确定") { _, _ -> movePlainFileToAlbum(file) }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }

        tvDelete.setOnClickListener {
            popup.dismiss()
            if (isAlbumTab) {
                confirmDeleteAlbumVideo(file)
            } else {
                AlertDialog.Builder(this)
                    .setTitle(R.string.delete)
                    .setMessage("确定删除隐私视频「${file.name}」？")
                    .setPositiveButton("删除") { _, _ ->
                        try {
                            if (file.delete()) {
                                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(this, "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                        loadAllVideos()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }

        // 右对齐锚点按钮弹出，超出屏幕时自动向左展开
        popup.showAsDropDown(anchor, 0, 0, Gravity.END)
    }

    /** 将私有目录中的明文视频副本移回系统相册，然后删除 App 内副本 */
    private fun movePlainFileToAlbum(file: File) {
        doMovePlainFilesToAlbum(listOf(file))
    }

    /**
     * 将选中的相册视频迁移到 App 私有目录作为“隐私视频”。
     * 复制成功后，删除系统相册中的原视频（通过 MediaStore 真实删除，Android 11+ 弹系统确认框），
     * 避免相册与隐私中出现两份相同视频占用双倍空间。
     */
    private fun moveAlbumVideoToPrivacy(files: List<File>) {
        if (!ensurePrivacyDirWritable()) return
        lifecycleScope.launch(Dispatchers.IO) {
            val copied = mutableListOf<File>()
            for (file in files) {
                if (copyVideoToPrivateDir(file) != null) copied.add(file)
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@MainActivity,
                    "已加入隐私：${copied.size}/${files.size}",
                    Toast.LENGTH_LONG
                ).show()
                if (copied.isEmpty()) {
                    loadAllVideos()
                    binding.tabLayout.getTabAt(1)?.select()
                    return@withContext
                }
                // 收集复制成功的原视频对应的 MediaStore ID / 真实路径，用于删除原相册视频
                val mediaIds = mutableListOf<Long>()
                val paths = mutableListOf<String>()
                for (f in copied) {
                    val uri = getMediaStoreUriForFile(f)
                    if (uri != null) {
                        mediaIds.add(ContentUris.parseId(uri))
                    } else {
                        paths.add(f.absolutePath)
                    }
                }
                if (mediaIds.isNotEmpty() || paths.isNotEmpty()) {
                    deleteOriginalFilesById(mediaIds, paths)
                } else {
                    loadAllVideos()
                }
                binding.tabLayout.getTabAt(1)?.select()
            }
        }
    }

    /**
     * 把相册视频拷贝到 App 私有目录（明文临时文件）。
     * 优先直接按路径拷贝；失败时退化为通过 MediaStore content URI 读取
     * （部分 ROM/文件管理器选择的文件无法直接按路径读取）。
     */
    private fun copyVideoToPrivateDir(file: File): File? {
        val dest = File(appVideoDir, file.name)
        return try {
            if (dest.exists()) dest.delete()
            file.copyTo(dest, overwrite = true)
            if (dest.exists() && dest.length() > 0) dest else null
        } catch (e: Exception) {
            Log.w(TAG, "直接拷贝失败，尝试通过 MediaStore 读取: ${file.name}", e)
            try {
                val uri = getMediaStoreUriForFile(file) ?: return null
                contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } ?: return null
                if (dest.exists() && dest.length() > 0) dest else null
            } catch (e2: Exception) {
                Log.e(TAG, "通过 MediaStore 拷贝也失败: ${file.name}", e2)
                dest.delete()
                null
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
        val isAlbumTab = currentTab == 0
        val msg = if (isAlbumTab) {
            "确定删除选中的 ${selected.size} 个相册视频吗？\n（同时删除系统相册中的原文件）"
        } else {
            "确定删除选中的 ${selected.size} 个隐私视频吗？"
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
                    // 隐私视频优先用 MediaStore content Uri 删除（无需“所有文件访问”权限）
                    val privacyUri = privacyUriMap[file.absolutePath]
                    if (privacyUri != null) {
                        originalsToDeleteUris.add(privacyUri)
                        deleted++
                    } else {
                        if (file.exists()) file.delete()
                        deleted++
                        // 相册视频：同时删除系统相册中的原文件
                        if (!file.absolutePath.startsWith(appVideoDir.absolutePath)) {
                            getMediaStoreUriForFile(file)?.let { originalsToDeleteUris.add(it) }
                        }
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

    // ==================== 存储权限 ====================

    private fun ensureNomedia() {
        // 私密视频现在通过 MediaStore 读取，若目录里有 .nomedia 会导致 MediaStore 不扫描这些视频，
        // 从而列表为空。因此这里不再创建 .nomedia，并清掉历史遗留的 .nomedia，让媒体库能索引到它们。
        // 为避免在 App 内“相册视频”页与“隐私视频”页重复显示，已在相册查询中按文件名隐藏同名隐私项。
        val nomedia = File(appVideoDir, ".nomedia")
        if (nomedia.exists()) {
            try { nomedia.delete() } catch (_: Exception) {}
        }
    }

    /**
     * 备份目录：内部存储 / Download / VideoWatch。
     * 使用公共存储（而非 App 私有目录），这样：
     *  1) 卸载 App 后文件不会被系统清除；
     *  2) 重装后无需任何授权即可直接读取并自动恢复（SAF 的持久授权在重装后会失效，
     *     因此需要“所有文件访问”权限来直接读写该公共目录）。
     */
    private val backupDir: File
        get() = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "VideoWatch"
        )

    /** 是否已获得“所有文件访问”权限（Android 11+ 需要，才能直接读写 Download/VideoWatch） */
    private fun backupReady(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

    /** 跳转系统设置授予“所有文件访问”权限 */
    private fun openManageAllFilesAccess() {
        try {
            val uri = Uri.fromParts("package", packageName, null)
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri))
        } catch (_: Exception) {
            // 部分设备没有该设置页，退而求其次打开应用详情
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            } catch (_: Exception) {}
        }
    }

    /** 设置对话框（含私密视频文件夹的跳转与更换） */
    /**
     * 设置弹窗：状态 + 当前私密视频文件夹路径 + 三个操作入口。
     * 使用自定义布局（部分 ROM 上 setItems 与 setMessage 同用会导致列表不渲染）。
     */
    private fun showSettingsDialog() {
        val current = appVideoDir.absolutePath
        val status = if (backupReady()) "已授予「所有文件访问」权限" else "尚未授予「所有文件访问」权限"
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()

        var dialogRef: AlertDialog? = null
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(TextView(context).apply {
                text = "$status\n\n当前私密视频文件夹：\n$current"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            })
            fun menuItem(label: String, onClick: () -> Unit) {
                addView(TextView(context).apply {
                    text = label
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    val vPad = (12 * density).toInt()
                    setPadding(0, vPad, 0, vPad)
                    setOnClickListener {
                        dialogRef?.dismiss()
                        onClick()
                    }
                })
            }
            menuItem("打开私密视频文件夹") { openFolderJump() }
            menuItem("选择/更换私密视频文件夹") { pickFolderLauncher.launch(null) }
            menuItem("授予所有文件访问权限") { openManageAllFilesAccess() }
        }

        dialogRef = AlertDialog.Builder(this)
            .setTitle("设置")
            .setView(container)
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 打开当前私密视频文件夹（Download/VideoWatch）用于查看/管理文件。
     * 优先用系统文件管理器直接打开该文件夹（语义上的“打开”，必定落在我们设定的目录）；
     * 若设备上没有可处理的应用，再退化为系统目录选择器并尽量定位到该文件夹。
     */
    private fun openFolderJump() {
        val path = appVideoDir.absolutePath
        // 构造该文件夹对应的 document Uri（仅内部存储）
        val rest = path.removePrefix("/storage/emulated/0/").trimStart('/')
        val docUri = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents", "primary:$rest"
        )
        val viewIntent = Intent(Intent.ACTION_VIEW, docUri).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (viewIntent.resolveActivity(packageManager) != null) {
            try {
                startActivity(viewIntent)
                return
            } catch (_: Exception) {
                // 退化到目录选择器
            }
        }
        // 退化：系统目录选择器，并尽量定位到该文件夹
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val treeUri = pathToTreeUri(path)
            if (treeUri != null) {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, treeUri)
            }
        }
        try {
            openFolderLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e(TAG, "打开文件夹失败", e)
            Toast.makeText(this, "无法打开文件夹", Toast.LENGTH_SHORT).show()
        }
    }

    /** 选择/更换私密视频文件夹：持久化用户选择的真实路径 */
    private fun onPrivacyFolderPicked(uri: Uri?) {
        if (uri == null) return
        val path = treeUriToPath(uri)
        if (path == null) {
            Toast.makeText(this, "暂仅支持选择内部存储下的文件夹", Toast.LENGTH_LONG).show()
            return
        }
        prefs.edit().putString(KEY_PRIVATE_DIR, path).apply()
        val dir = File(path)
        try { if (!dir.exists()) dir.mkdirs() } catch (_: Exception) {}
        ensureNomedia()
        Toast.makeText(this, "私密视频文件夹已更新：$path", Toast.LENGTH_LONG).show()
        loadAllVideos()
        binding.tabLayout.getTabAt(1)?.select()
    }

    /** 将系统目录选择器返回的 tree Uri 转为真实路径（仅支持内部存储 primary） */
    private fun treeUriToPath(uri: Uri): String? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val idx = docId.indexOf(':')
            if (idx < 0) return null
            val type = docId.substring(0, idx)
            val rest = docId.substring(idx + 1)
            if (type == "primary") "/storage/emulated/0/$rest" else null
        } catch (_: Exception) {
            null
        }
    }

    /** 由私密视频目录的真实路径构建 tree Uri，用于打开/跳转目录选择器 */
    private fun pathToTreeUri(path: String): Uri? {
        if (!path.startsWith("/storage/emulated/0/")) return null
        val rest = path.removePrefix("/storage/emulated/0/")
        val treeId = "primary:$rest"
        return try {
            DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", treeId)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 写入私密视频目录前的权限校验：公共文件夹在 Android 11+ 需要「所有文件访问」权限。
     * 不满足时弹窗引导授予，并返回 false。
     */
    private fun ensurePrivacyDirWritable(): Boolean {
        if (backupReady()) return true
        AlertDialog.Builder(this)
            .setTitle("需要权限")
            .setMessage("写入私密视频文件夹「${appVideoDir.absolutePath}」需要「所有文件访问」权限，是否前往授予？")
            .setPositiveButton("去授予") { _, _ -> openManageAllFilesAccess() }
            .setNegativeButton("取消", null)
            .show()
        return false
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
