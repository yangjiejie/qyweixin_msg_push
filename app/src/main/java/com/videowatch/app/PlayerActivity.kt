package com.videowatch.app

import android.app.AlertDialog
import android.content.ContentUris
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.videowatch.app.player.ZoomableVideoView
import com.videowatch.app.utils.AudioExtractor
import com.videowatch.app.utils.GifMaker
import com.videowatch.app.utils.VideoExtractor
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class PlayerActivity : AppCompatActivity() {

    private lateinit var videoView: ZoomableVideoView
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var seekBar: SeekBar
    private lateinit var tvTime: TextView
    private lateinit var tvFileName: TextView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnSpeed: TextView
    private lateinit var btnSetA: TextView
    private lateinit var btnSetB: TextView
    private lateinit var btnClearAB: TextView
    private lateinit var btnSingleLoop: ImageButton
    private lateinit var btnScreenshot: ImageButton
    private lateinit var btnGif: ImageButton
    private lateinit var btnFrameForward: ImageButton
    private lateinit var btnFrameBackward: ImageButton
    private lateinit var btnBack: ImageButton
    private lateinit var btnRotate: ImageButton
    private lateinit var btnAspectRatio: TextView
    private lateinit var btnDelete: ImageButton
    private lateinit var btnAudio: ImageButton
    private lateinit var btnVideoClip: ImageButton
    private lateinit var btnHideUi: ImageButton

    /** 视频片段截取线程 */
    private val videoClipExecutor = Executors.newSingleThreadExecutor()

    private var player: ExoPlayer? = null
    private var isPlaying = false
    private var isSingleLoop = true
    private var videoUri: Uri? = null
    private var isCurrentPrivacy = false
    private var videoFileName: String = ""
    private var videoPath: String = ""
    private var currentSpeed = 1.0f
    private val speeds = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    private val speedLabels = arrayOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x")

    // AB循环 - 2按钮模式
    private var pointA: Long = -1L
    private var pointB: Long = -1L
    private var isABLoopActive = false

    // 控件显隐
    private val hideHandler = Handler(Looper.getMainLooper())
    private var controlsVisible = false
    private var controlsPermanent = false
    /** 是否因“点击屏幕显示控件”而自动暂停（隐藏控件时自动恢复播放） */
    private var autoPausedForUi = false
    /**
     * 进度条 / AB循环轮询专用 Handler。
     * 注意：不能与 hideHandler 共用，否则 autoHideControls() 的
     * removeCallbacksAndMessages(null) 会把轮询链一起清掉，导致
     * 进度条卡住、AB循环失效。
     */
    private val progressHandler = Handler(Looper.getMainLooper())

    // GIF录制
    private var isRecordingGif = false
    private val gifFrames = mutableListOf<Bitmap>()
    private val gifExecutor = Executors.newSingleThreadExecutor()
    /** GIF 帧捕获必须在 UI 线程执行（TextureView.getBitmap() 不能在后台线程调用，否则取不到画面） */
    private val gifCaptureHandler = Handler(Looper.getMainLooper())
    private var gifCaptureRunnable: Runnable? = null
    private val gifMaxFrames = 120          // 10fps × 12s，超出自动停止，防止内存溢出
    private val gifFrameIntervalMs = 100L   // 10fps
    private val gifMaxWidth = 360           // 捕获时直接降尺度，降低内存占用

    // 音频截取
    private val audioExecutor = Executors.newSingleThreadExecutor()
    /** 勾选的录制格式（可多种同时录制），默认 OGG */
    private val selectedAudioFormats =
        LinkedHashSet(listOf(AudioExtractor.OutFormat.OGG))

    // 画面比例模式
    private var aspectRatioMode = ZoomableVideoView.ASPECT_FIT
    private val aspectModeLabels = arrayOf("适应", "裁剪", "拉伸")

    // 屏幕方向
    private var isLandscape = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setContentView(R.layout.activity_player)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        initViews()
        setupListeners()
        handleIntent()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                exitPlayer()
            }
        })
    }

    private fun initViews() {
        videoView = findViewById(R.id.videoView)
        topBar = findViewById(R.id.topBar)
        bottomBar = findViewById(R.id.bottomBar)
        seekBar = findViewById(R.id.seekBar)
        tvTime = findViewById(R.id.tvTime)
        tvFileName = findViewById(R.id.tvFileName)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnSpeed = findViewById(R.id.btnSpeed)
        btnSetA = findViewById(R.id.btnSetA)
        btnSetB = findViewById(R.id.btnSetB)
        btnClearAB = findViewById(R.id.btnClearAB)
        btnSingleLoop = findViewById(R.id.btnSingleLoop)
        btnScreenshot = findViewById(R.id.btnScreenshot)
        btnGif = findViewById(R.id.btnGif)
        btnFrameForward = findViewById(R.id.btnFrameForward)
        btnFrameBackward = findViewById(R.id.btnFrameBackward)
        btnBack = findViewById(R.id.btnBack)
        btnRotate = findViewById(R.id.btnRotate)
        btnAspectRatio = findViewById(R.id.btnAspectRatio)
        btnDelete = findViewById(R.id.btnDelete)
        btnAudio = findViewById(R.id.btnAudio)
        btnVideoClip = findViewById(R.id.btnVideoClip)
        btnHideUi = findViewById(R.id.btnHideUi)

        btnSingleLoop.setImageResource(R.drawable.ic_loop_active)
        btnAspectRatio.text = aspectModeLabels[aspectRatioMode]
        videoView.setAspectRatioMode(aspectRatioMode)
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { exitPlayer() }

        btnPlayPause.setOnClickListener {
            if (isPlaying) pauseVideo() else playVideo()
        }

        btnFrameForward.setOnClickListener { stepFrame(true) }
        btnFrameBackward.setOnClickListener { stepFrame(false) }

        // 单视频循环
        btnSingleLoop.setOnClickListener {
            isSingleLoop = !isSingleLoop
            btnSingleLoop.setImageResource(
                if (isSingleLoop) R.drawable.ic_loop_active else R.drawable.ic_loop_inactive
            )
            player?.repeatMode = if (isSingleLoop) {
                Player.REPEAT_MODE_ONE
            } else {
                Player.REPEAT_MODE_OFF
            }
            if (!isSingleLoop) {
                controlsPermanent = false
            }
            Toast.makeText(this, if (isSingleLoop) "单视频循环：开" else "单视频循环：关", Toast.LENGTH_SHORT).show()
        }

        // 倍速 - 弹出选择框
        btnSpeed.setOnClickListener { showSpeedDialog() }

        // AB循环 - 按钮A
        btnSetA.setOnClickListener {
            val pos = player?.currentPosition ?: return@setOnClickListener
            pointA = pos
            btnSetA.setTextColor(getColor(R.color.btn_active))
            btnSetA.setBackgroundColor(0x66E94560.toInt())
            Toast.makeText(this, "A点已设置", Toast.LENGTH_SHORT).show()
            // 如果B已存在且> A，自动启用AB循环
            checkAndEnableABLoop()
        }

        // AB循环 - 按钮B
        btnSetB.setOnClickListener {
            val pos = player?.currentPosition ?: return@setOnClickListener
            if (pointA < 0) {
                Toast.makeText(this, "请先设置A点", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (pos <= pointA + 500) {
                Toast.makeText(this, "B点必须在A点之后", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pointB = pos
            btnSetB.setTextColor(getColor(R.color.btn_active))
            btnSetB.setBackgroundColor(0x66E94560.toInt())
            checkAndEnableABLoop()
        }

        // AB循环 - 清除
        btnClearAB.setOnClickListener { clearABLoop() }

        // AB按钮长按清除
        btnSetA.setOnLongClickListener { clearABLoop(); true }
        btnSetB.setOnLongClickListener { clearABLoop(); true }

        // 截屏
        btnScreenshot.setOnClickListener { captureScreenshot() }

        // GIF
        btnGif.setOnClickListener {
            if (isRecordingGif) stopGifCapture() else startGifCapture()
        }

        // 旋转屏幕
        btnRotate.setOnClickListener { toggleOrientation() }

        // 比例模式 - 弹出选择框
        btnAspectRatio.setOnClickListener { showAspectRatioDialog() }

        // 删除当前视频
        btnDelete.setOnClickListener { deleteCurrentVideo() }

        // 录制音频：截取 A~B 点之间的声音（可选 WAV / OGG）
        btnAudio.setOnClickListener { requestAudioExtract() }
        btnVideoClip.setOnClickListener { requestVideoClip() }

        // 手动隐藏控件（立即进入沉浸模式，如因点屏暂停过则恢复播放）
        btnHideUi.setOnClickListener {
            hideHandler.removeCallbacksAndMessages(null)
            hideControls()
        }

        // 触摸视频区域：
        // 控件未显示 -> 显示控件并暂停播放（用户点屏通常是为了操作，如逐帧、设AB点）；
        // 控件已显示 -> 重置“5秒后自动隐藏”计时器。
        // 注意：监听必须直接挂在 videoView 上，挂在 decorView 等父容器上不会被子视图的触摸触发。
        videoView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                if (!controlsVisible && !controlsPermanent) {
                    showControlsForUser()
                } else {
                    autoHideControls()
                }
            }
            // 返回 false，保证 ZoomableVideoView 自身的缩放/平移手势仍可正常处理
            false
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = player?.duration ?: return
                    val pos = (progress / 1000f * duration).toLong()
                    player?.seekTo(pos)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    // ==================== 弹出选择框 ====================

    private fun showSpeedDialog() {
        val currentIndex = speeds.indexOfFirst { it == currentSpeed }
        val currentLabel = speedLabels[currentIndex.coerceAtLeast(0)]

        val builder = AlertDialog.Builder(this)
        builder.setTitle("选择播放倍速 (当前: $currentLabel)")
        builder.setSingleChoiceItems(speedLabels, currentIndex) { dialog, which ->
            currentSpeed = speeds[which]
            btnSpeed.text = speedLabels[which]
            player?.setPlaybackParameters(PlaybackParameters(currentSpeed))
            dialog.dismiss()
        }
        builder.setNegativeButton("取消", null)
        builder.show()
    }

    private fun showAspectRatioDialog() {
        val currentLabel = aspectModeLabels[aspectRatioMode]
        val builder = AlertDialog.Builder(this)
        builder.setTitle("选择画面比例 (当前: $currentLabel)")
        builder.setSingleChoiceItems(aspectModeLabels, aspectRatioMode) { dialog, which ->
            aspectRatioMode = which
            videoView.setAspectRatioMode(which)
            btnAspectRatio.text = aspectModeLabels[which]
            dialog.dismiss()
        }
        builder.setNegativeButton("取消", null)
        builder.show()
    }

    // ==================== AB循环 ====================

    private fun checkAndEnableABLoop() {
        if (pointA >= 0 && pointB >= 0 && pointB > pointA) {
            isABLoopActive = true
            btnClearAB.visibility = View.VISIBLE
            seekABLoop()
            Toast.makeText(this, "AB循环已启用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearABLoop() {
        pointA = -1L
        pointB = -1L
        isABLoopActive = false
        btnSetA.setTextColor(getColor(R.color.text_secondary))
        btnSetA.setBackgroundResource(R.drawable.bg_control_btn)
        btnSetB.setTextColor(getColor(R.color.text_secondary))
        btnSetB.setBackgroundResource(R.drawable.bg_control_btn)
        btnClearAB.visibility = View.GONE
        Toast.makeText(this, "AB循环已清除", Toast.LENGTH_SHORT).show()
    }

    // ==================== 意图处理 ====================

    private fun handleIntent() {
        val path = intent.getStringExtra("video_path") ?: run {
            Toast.makeText(this, "无法播放此视频", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        videoPath = path
        val videoFile = File(path)
        // 若主界面传来了 MediaStore content Uri（隐私视频），优先使用，避免依赖“所有文件访问”权限
        val uriStr = intent.getStringExtra("video_uri")
        val contentUri = if (!uriStr.isNullOrEmpty()) Uri.parse(uriStr) else null

        val accessible = if (contentUri != null) {
            try {
                contentResolver.openFileDescriptor(contentUri, "r")?.close()
                true
            } catch (_: Exception) {
                false
            }
        } else {
            videoFile.exists()
        }
        if (!accessible) {
            Toast.makeText(this, "视频文件不存在或无法访问", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        videoUri = contentUri ?: Uri.fromFile(videoFile)
        isCurrentPrivacy = contentUri != null
        videoFileName = if (contentUri != null) getDisplayName(contentUri) ?: videoFile.name else videoFile.name
        tvFileName.text = videoFileName
        initializePlayer()
    }

    /** 从 content Uri 取显示名（隐私视频可能没有真实文件名可依赖） */
    private fun getDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(MediaStore.Video.Media.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)) else null
                }
        } catch (_: Exception) {
            null
        }
    }

    // ==================== 播放器 ====================

    private fun initializePlayer() {
        val uri = videoUri ?: run {
            Toast.makeText(this, "无法播放此视频", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        player = ExoPlayer.Builder(this).build().also { p ->
            videoView.setPlayer(p)
            p.repeatMode = Player.REPEAT_MODE_ONE
            p.setMediaItem(MediaItem.fromUri(uri))
            p.prepare()
            p.playWhenReady = true

            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_READY -> {
                            isPlaying = p.playWhenReady
                            updatePlayPauseButton()
                            updateVideoResolution()
                        }
                        Player.STATE_ENDED -> {
                            if (isABLoopActive && pointB > pointA) {
                                // AB循环兜底：播放到结尾时回到A点继续循环
                                p.seekTo(pointA)
                                p.play()
                            } else if (!isSingleLoop) {
                                isPlaying = false
                                updatePlayPauseButton()
                                controlsPermanent = true
                                showControls()
                                hideHandler.removeCallbacksAndMessages(null)
                            }
                        }
                        else -> {}
                    }
                }

                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                    updatePlayPauseButton()
                }

                override fun onPlayerError(error: PlaybackException) {
                    Toast.makeText(
                        this@PlayerActivity,
                        "播放错误: ${error.localizedMessage ?: error.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                    updateVideoResolution()
                }
            })
        }

        // 初始隐藏所有控件，触摸屏幕后才显示（沉浸观看）
        hideControls()

        startProgressUpdate()
    }

    private fun updateVideoResolution() {
        val format = player?.videoFormat ?: return
        val width = format.width
        val height = format.height
        if (width > 0 && height > 0) {
            videoView.setVideoSize(width, height)
            tvFileName.text = "$videoFileName  ${width}x$height"
        }
    }

    private fun playVideo() {
        autoPausedForUi = false // 用户手动播放，不再自动恢复
        controlsPermanent = false // 重播后恢复“无操作自动隐藏”行为
        player?.play()
        isPlaying = true
        updatePlayPauseButton()
    }

    private fun pauseVideo() {
        autoPausedForUi = false // 用户手动暂停，隐藏控件时不自动恢复
        player?.pause()
        isPlaying = false
        updatePlayPauseButton()
    }

    private fun updatePlayPauseButton() {
        if (isPlaying) {
            btnPlayPause.setImageResource(R.drawable.ic_pause)
            btnFrameForward.visibility = View.GONE
            btnFrameBackward.visibility = View.GONE
        } else {
            btnPlayPause.setImageResource(R.drawable.ic_play)
            // 暂停时才显示逐帧按钮，方便逐帧查看
            btnFrameForward.visibility = View.VISIBLE
            btnFrameBackward.visibility = View.VISIBLE
        }
    }

    private fun stepFrame(forward: Boolean) {
        val p = player ?: return
        val frameDuration = 1000L / 30
        val newPos = if (forward) {
            minOf(p.currentPosition + frameDuration, p.duration)
        } else {
            maxOf(p.currentPosition - frameDuration, 0L)
        }
        p.seekTo(newPos)
    }

    private fun seekABLoop() {
        val p = player ?: return
        val pos = p.currentPosition
        if (isABLoopActive && pointB > 0 && pos >= pointB) {
            p.seekTo(pointA)
        }
    }

    // ==================== 控件显隐 ====================

    /** 用户点击屏幕：显示控件，并暂停播放（用户点屏通常是为了操作） */
    private fun showControlsForUser() {
        showControls()
        // GIF录制中不自动暂停：用户点屏多半是为了停止录制
        if (isPlaying && !isRecordingGif) {
            autoPausedForUi = true
            player?.pause()
            isPlaying = false
            updatePlayPauseButton()
        }
    }

    private fun showControls() {
        controlsVisible = true
        topBar.visibility = View.VISIBLE
        bottomBar.visibility = View.VISIBLE
        updatePlayPauseButton()
        showSystemUi()
        autoHideControls()
    }

    private fun hideControls() {
        if (controlsPermanent) return
        controlsVisible = false
        topBar.visibility = View.GONE
        bottomBar.visibility = View.GONE
        btnFrameForward.visibility = View.GONE
        btnFrameBackward.visibility = View.GONE
        // 沉浸模式：隐藏系统状态栏/导航栏，纯享观看
        hideSystemUi()
        // 若之前是因“点屏显示控件”而暂停的，隐藏时自动恢复播放
        if (autoPausedForUi) {
            autoPausedForUi = false
            player?.play()
            isPlaying = true
            updatePlayPauseButton()
        }
    }

    private fun autoHideControls() {
        hideHandler.removeCallbacksAndMessages(null)
        if (controlsPermanent) return
        // 无操作 5 秒后隐藏全部 UI，进入沉浸观看
        hideHandler.postDelayed({ hideControls() }, 5000)
    }

    private fun hideSystemUi() {
        try {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        } catch (_: Exception) {}
    }

    private fun showSystemUi() {
        try {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        } catch (_: Exception) {}
    }

    // ==================== 进度更新 ====================

    /**
     * 进度条 + AB循环轮询。
     * 使用独立的 progressHandler：控件自动隐藏用的 hideHandler 会频繁
     * removeCallbacksAndMessages(null)，共用 Handler 会导致轮询链被清掉，
     * 表现为进度条不走、AB循环失效。
     */
    private fun startProgressUpdate() {
        progressHandler.postDelayed(object : Runnable {
            override fun run() {
                val p = player
                if (p != null) {
                    val duration = p.duration
                    if (duration > 0) {
                        if (controlsVisible || isPlaying) {
                            val progress = (p.currentPosition.toFloat() / duration * 1000).toInt()
                            seekBar.progress = progress
                            tvTime.text = "${formatTime(p.currentPosition)} / ${formatTime(duration)}"
                        }
                        // AB循环检查必须与 UI 显隐无关：控件隐藏时也要能回跳
                        if (isABLoopActive) seekABLoop()
                    }
                }
                progressHandler.postDelayed(this, 100)
            }
        }, 100)
    }

    // ==================== 截屏 ====================

    private fun captureScreenshot() {
        val bitmap = videoView.getBitmap()
        if (bitmap == null) {
            Toast.makeText(this, "截屏失败", Toast.LENGTH_SHORT).show()
            return
        }
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "VideoWatch"
        )
        dir.mkdirs()
        val fileName = "screenshot_${System.currentTimeMillis()}.jpg"
        val file = File(dir, fileName)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        bitmap.recycle()
        Toast.makeText(this, "截图已保存: $fileName", Toast.LENGTH_LONG).show()
    }

    // ==================== GIF ====================

    private fun startGifCapture() {
        if (isRecordingGif) return
        isRecordingGif = true
        btnGif.setImageResource(R.drawable.ic_gif_recording)
        Toast.makeText(this, "开始录制GIF，再次点击停止", Toast.LENGTH_SHORT).show()

        gifFrames.clear()
        val runnable = object : Runnable {
            override fun run() {
                if (!isRecordingGif) return
                // TextureView.getBitmap() 必须在 UI 线程调用，否则取不到画面（返回 null/黑帧）
                val bitmap = videoView.getBitmap()
                if (bitmap != null) {
                    val scaled = scaleBitmapForGif(bitmap)
                    bitmap.recycle()
                    synchronized(gifFrames) { gifFrames.add(scaled) }
                }
                if (gifFrames.size >= gifMaxFrames) {
                    // 达到时长上限，自动停止，避免内存溢出
                    Toast.makeText(
                        this@PlayerActivity,
                        "已达到最长录制时长(约${gifMaxFrames / 10}秒)，自动停止",
                        Toast.LENGTH_SHORT
                    ).show()
                    stopGifCapture()
                    return
                }
                gifCaptureHandler.postDelayed(this, gifFrameIntervalMs)
            }
        }
        gifCaptureRunnable = runnable
        gifCaptureHandler.postDelayed(runnable, gifFrameIntervalMs)
    }

    private fun stopGifCapture() {
        if (!isRecordingGif) return
        isRecordingGif = false
        gifCaptureRunnable?.let { gifCaptureHandler.removeCallbacks(it) }
        gifCaptureRunnable = null
        btnGif.setImageResource(R.drawable.ic_gif)
        gifExecutor.execute { saveGif() }
    }

    /** 捕获时直接降尺度，避免整帧全分辨率Bitmap堆积导致OOM */
    private fun scaleBitmapForGif(bitmap: Bitmap): Bitmap {
        if (bitmap.width <= gifMaxWidth) return bitmap
        val ratio = gifMaxWidth.toFloat() / bitmap.width
        return Bitmap.createScaledBitmap(
            bitmap, gifMaxWidth, (bitmap.height * ratio).toInt(), true
        )
    }

    private fun saveGif() {
        val frames: List<Bitmap>
        synchronized(gifFrames) {
            if (gifFrames.isEmpty()) {
                runOnUiThread {
                    Toast.makeText(this@PlayerActivity, "GIF录制失败：无帧数据", Toast.LENGTH_SHORT).show()
                }
                return
            }
            frames = gifFrames.toList()
            gifFrames.clear()
        }

        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "VideoWatch"
        )
        dir.mkdirs()
        val file = File(dir, "gif_${System.currentTimeMillis()}.gif")

        val success = GifMaker.createGif(frames, file, 10)
        for (frame in frames) { frame.recycle() }

        runOnUiThread {
            if (success) {
                Toast.makeText(this@PlayerActivity, "GIF已保存: ${file.name}", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@PlayerActivity, "GIF录制失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ==================== 录制音频(OGG / WAV / MP3) ====================

    /**
     * 录制音频：直接复用 AB 点区间。
     * 用底栏 A/B 按钮选好区间后，点顶栏音符按钮弹出格式勾选框
     * （OGG / WAV / MP3，可多选），点“开始录制”即按勾选的格式逐个输出。
     */
    private fun requestAudioExtract() {
        if (pointA < 0 || pointB <= pointA) {
            Toast.makeText(
                this,
                "请先用底部「A / B」按钮设置要录制的区间，再点此录制该段音频",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val seconds = (pointB - pointA) / 1000

        val view = LayoutInflater.from(this).inflate(R.layout.dialog_audio_record, null)
        view.findViewById<TextView>(R.id.tvRange).text =
            "区间: A(${formatTime(pointA)}) ~ B(${formatTime(pointB)})，约 ${seconds} 秒"

        val cbOgg = view.findViewById<CheckBox>(R.id.cbOgg)
        val cbWav = view.findViewById<CheckBox>(R.id.cbWav)
        val cbMp3 = view.findViewById<CheckBox>(R.id.cbMp3)
        cbOgg.isChecked = AudioExtractor.OutFormat.OGG in selectedAudioFormats
        cbWav.isChecked = AudioExtractor.OutFormat.WAV in selectedAudioFormats
        cbMp3.isChecked = AudioExtractor.OutFormat.MP3 in selectedAudioFormats

        cbOgg.setOnCheckedChangeListener { _, checked -> toggleAudioFormat(AudioExtractor.OutFormat.OGG, checked) }
        cbWav.setOnCheckedChangeListener { _, checked -> toggleAudioFormat(AudioExtractor.OutFormat.WAV, checked) }
        cbMp3.setOnCheckedChangeListener { _, checked -> toggleAudioFormat(AudioExtractor.OutFormat.MP3, checked) }

        AlertDialog.Builder(this)
            .setTitle("录制音频")
            .setView(view)
            .setNegativeButton("取消", null)
            .setPositiveButton("开始录制") { _, _ ->
                val formats = selectedAudioFormats.toList()
                if (formats.isEmpty()) {
                    Toast.makeText(this, "请至少勾选一种格式", Toast.LENGTH_SHORT).show()
                } else {
                    startAudioExtract(pointA, pointB, formats)
                }
            }
            .show()
    }

    private fun toggleAudioFormat(format: AudioExtractor.OutFormat, checked: Boolean) {
        if (checked) selectedAudioFormats.add(format) else selectedAudioFormats.remove(format)
    }

    /** 按勾选的格式逐个录制（同一时间戳前缀，便于成组识别） */
    private fun startAudioExtract(
        startMs: Long,
        endMs: Long,
        formats: List<AudioExtractor.OutFormat>
    ) {
        val inputPath = videoPath
        if (inputPath.isEmpty()) {
            Toast.makeText(this, "无法获取视频文件", Toast.LENGTH_SHORT).show()
            return
        }
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "VideoWatch"
        )
        dir.mkdirs()

        Toast.makeText(this, "正在录制音频…", Toast.LENGTH_SHORT).show()
        audioExecutor.execute {
            for (format in formats) {
                val ext = when (format) {
                    AudioExtractor.OutFormat.WAV -> "wav"
                    AudioExtractor.OutFormat.MP3 -> "mp3"
                    AudioExtractor.OutFormat.OGG -> "ogg"
                }
                val outFile = File(dir, "audio_${System.currentTimeMillis()}.$ext")
                AudioExtractor.extract(inputPath, startMs, endMs, outFile, format,
                    object : AudioExtractor.Callback {
                        override fun onSuccess(file: File, note: String) {
                            runOnUiThread {
                                // 通知媒体库扫描，让文件管理器/相册能立刻看到
                                MediaScannerConnection.scanFile(
                                    this@PlayerActivity, arrayOf(file.absolutePath), null, null
                                )
                                Toast.makeText(
                                    this@PlayerActivity,
                                    "音频已保存: ${file.name}\n($note)",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }

                        override fun onError(message: String) {
                            runOnUiThread {
                                Toast.makeText(this@PlayerActivity, message, Toast.LENGTH_LONG).show()
                            }
                        }
                    })
            }
        }
    }

    // ==================== 截取视频片段 ====================

    /**
     * 截取视频片段：复用 A/B 区间。
     * 点顶栏“截取视频”按钮，确认区间后点“开始截取”，
     * 会把 A~B 这段（含音轨）混流成 MP4（不重编码，速度快）。
     */
    private fun requestVideoClip() {
        if (pointA < 0 || pointB <= pointA) {
            Toast.makeText(
                this,
                "请先用底部「A / B」按钮设置要截取的区间，再点此截取该段视频",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val seconds = (pointB - pointA) / 1000
        AlertDialog.Builder(this)
            .setTitle("截取视频片段")
            .setMessage(
                "区间: A(${formatTime(pointA)}) ~ B(${formatTime(pointB)})，约 ${seconds} 秒\n" +
                        "将把这段视频（含音轨）保存为 MP4，直接混流不重新编码。\n" +
                        "保存位置: 内部存储/Movies/VideoWatch"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("开始截取") { _, _ ->
                startVideoClip(pointA, pointB)
            }
            .show()
    }

    private fun startVideoClip(startMs: Long, endMs: Long) {
        val inputPath = videoPath
        if (inputPath.isEmpty()) {
            Toast.makeText(this, "无法获取视频文件", Toast.LENGTH_SHORT).show()
            return
        }
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "VideoWatch"
        )
        dir.mkdirs()
        val outFile = File(dir, "clip_${System.currentTimeMillis()}.mp4")

        Toast.makeText(this, "正在截取视频…", Toast.LENGTH_SHORT).show()
        videoClipExecutor.execute {
            VideoExtractor.clip(inputPath, startMs, endMs, outFile,
                object : VideoExtractor.Callback {
                    override fun onSuccess(file: File, note: String) {
                        runOnUiThread {
                            MediaScannerConnection.scanFile(
                                this@PlayerActivity, arrayOf(file.absolutePath), null, null
                            )
                            Toast.makeText(
                                this@PlayerActivity,
                                "视频片段已保存: ${file.name}\n($note)",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }

                    override fun onError(message: String) {
                        runOnUiThread {
                            Toast.makeText(this@PlayerActivity, message, Toast.LENGTH_LONG).show()
                        }
                    }
                })
        }
    }

    // ==================== 方向/退出 ====================

    private fun toggleOrientation() {
        isLandscape = !isLandscape
        requestedOrientation = if (isLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    private fun exitPlayer() {
        hideHandler.removeCallbacksAndMessages(null)
        player?.stop()
        player?.release()
        player = null
        finish()
    }

    // ==================== 删除当前视频 ====================

    private val mediaDeleteLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "已取消删除", Toast.LENGTH_SHORT).show()
        }
        exitPlayer()
    }

    private fun deleteCurrentVideo() {
        val name = videoFileName
        val isPrivacy = isPrivacyVideo(videoPath)
        val msg = if (isPrivacy) "确定删除隐私视频「$name」？" else "确定从设备相册删除视频「$name」？"
        AlertDialog.Builder(this)
            .setTitle("删除视频")
            .setMessage(msg)
            .setPositiveButton("删除") { _, _ -> doDeleteCurrentVideo(isPrivacy) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 隐私视频通过 MediaStore content Uri 传入（位于公共文件夹 Download/VideoWatch） */
    private fun isPrivacyVideo(path: String): Boolean = isCurrentPrivacy

    private fun doDeleteCurrentVideo(isPrivacy: Boolean) {
        if (isPrivacy) {
            // 优先用 MediaStore content Uri 删除（无需“所有文件访问”权限，系统会弹确认框）
            val uri = if (videoUri?.scheme == "content") videoUri else getMediaStoreUriForPath(videoPath)
            if (uri != null) {
                try {
                    val request = MediaStore.createDeleteRequest(contentResolver, listOf(uri))
                    mediaDeleteLauncher.launch(
                        IntentSenderRequest.Builder(request.intentSender).build()
                    )
                } catch (e: Exception) {
                    Toast.makeText(this, "删除申请失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                return
            }
            // 退化：直接删除文件（可能需要“所有文件访问”权限）
            try {
                if (!File(videoPath).delete()) {
                    Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show()
                    return
                }
            } catch (e: Exception) {
                Toast.makeText(this, "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
                return
            }
            Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
            exitPlayer()
        } else {
            // 相册视频：通过 MediaStore 申请删除，不破坏其他数据
            val uri = getMediaStoreUriForPath(videoPath)
            if (uri == null) {
                // 找不到媒体库记录，尝试直接删除文件
                if (File(videoPath).delete()) {
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                    exitPlayer()
                } else {
                    Toast.makeText(this, "找不到该视频的媒体记录", Toast.LENGTH_SHORT).show()
                }
                return
            }
            try {
                val request = MediaStore.createDeleteRequest(contentResolver, listOf(uri))
                mediaDeleteLauncher.launch(
                    IntentSenderRequest.Builder(request.intentSender).build()
                )
            } catch (e: Exception) {
                Toast.makeText(this, "删除申请失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 根据文件路径在 MediaStore 中查找视频 Uri */
    private fun getMediaStoreUriForPath(path: String): Uri? {
        val proj = arrayOf(MediaStore.Video.Media._ID)
        val sel = "${MediaStore.Video.Media.DATA} = ?"
        val cursor = contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, sel, arrayOf(path), null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
                return ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
            }
        }
        return null
    }

    // ==================== 工具方法 ====================

    private fun formatTime(ms: Long): String {
        if (ms < 0) return "00:00"
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d".format(minutes, seconds)
    }

    override fun onStop() {
        super.onStop()
        if (isRecordingGif) stopGifCapture()
    }

    override fun onDestroy() {
        super.onDestroy()
        hideHandler.removeCallbacksAndMessages(null)
        progressHandler.removeCallbacksAndMessages(null)
        gifCaptureHandler.removeCallbacksAndMessages(null)
        isRecordingGif = false
        player?.stop()
        player?.release()
        player = null
        gifExecutor.shutdownNow()
        audioExecutor.shutdownNow()
    }
}
