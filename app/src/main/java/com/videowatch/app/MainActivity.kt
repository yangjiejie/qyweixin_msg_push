package com.videowatch.app

import android.Manifest
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.os.Environment
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

    private val appVideoDir: File by lazy {
        File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "videos").also { it.mkdirs() }
    }
    private var loadJob: Job? = null
    private var isMultiSelect = false

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
        // 确保私有目录有 .nomedia
        ensureNomedia()
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
                    cancelMultiSelect()
                } else {
                    updateSelectionUI()
                }
            }
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
                Toast.makeText(this@MainActivity, "加密完成：$success/$total", Toast.LENGTH_LONG).show()
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

    // ==================== 多选管理 ====================

    private fun cancelMultiSelect() {
        isMultiSelect = false
        binding.bottomBar.visibility = View.GONE
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

    // ==================== 私有目录 .nomedia ====================

    private fun ensureNomedia() {
        // 私有目录本身就不会被相册扫描（在 Android/data/ 下）
        // 但再加 .nomedia 多做一层保障
        val nomedia = File(appVideoDir, ".nomedia")
        if (!nomedia.exists()) nomedia.createNewFile()
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
