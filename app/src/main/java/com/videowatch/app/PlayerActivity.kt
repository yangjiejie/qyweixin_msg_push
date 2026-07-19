package com.videowatch.app

import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.videowatch.app.player.ZoomableVideoView
import com.videowatch.app.utils.GifMaker
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

    private var player: ExoPlayer? = null
    private var isPlaying = false
    private var isSingleLoop = true
    private var videoUri: Uri? = null
    private var videoFileName: String = ""
    private var currentSpeed = 1.0f
    private val speeds = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    private val speedLabels = arrayOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x")

    // AB循环 - 2按钮模式
    private var pointA: Long = -1L
    private var pointB: Long = -1L
    private var isABLoopActive = false

    // 控件显隐
    private val hideHandler = Handler(Looper.getMainLooper())
    private var controlsVisible = true
    private var controlsPermanent = false

    // GIF录制
    private var isRecordingGif = false
    private val gifFrames = mutableListOf<Bitmap>()
    private val gifExecutor = Executors.newSingleThreadExecutor()

    // 画面比例模式
    private var aspectRatioMode = ZoomableVideoView.ASPECT_FIT
    private val aspectModeLabels = arrayOf("适应", "裁剪", "拉伸")

    // 屏幕方向
    private var isLandscape = false
    // 是否正在解密播放
    private var isDecryptedTemp = false
    private var tempDecryptFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setContentView(R.layout.activity_player)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        initViews()
        // 状态栏高度补偿，避免标题被遮挡
        topBar.post {
            val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
            val statusBarHeight = if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0
            topBar.setPadding(
                topBar.paddingLeft,
                topBar.paddingTop + statusBarHeight,
                topBar.paddingRight,
                topBar.paddingBottom
            )
        }
        setupListeners()
        handleIntent()
        initializePlayer()

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

        // 点击视频切换控制栏
        videoView.setOnClickListener {
            if (controlsVisible) hideControls() else showControls()
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
        val path = intent.getStringExtra("video_path") ?: return
        val isEncrypted = intent.getBooleanExtra("is_encrypted", false)

        if (isEncrypted) {
            // 解密到临时文件再播放
            decryptAndPlay(path)
        } else {
            val videoFile = File(path)
            videoUri = Uri.fromFile(videoFile)
            videoFileName = videoFile.name
            tvFileName.text = videoFileName
        }
    }

    private fun decryptAndPlay(encryptedPath: String) {
        try {
            val cacheDir = cacheDir
            val encFile = File(encryptedPath)
            val tempFile = File(cacheDir, encFile.nameWithoutExtension + "_dec_" + System.currentTimeMillis() + ".mp4")
            val success = com.videowatch.app.crypto.VideoEncryptor.decryptToFile(encryptedPath, tempFile.absolutePath)
            if (success) {
                isDecryptedTemp = true
                tempDecryptFile = tempFile
                videoUri = Uri.fromFile(tempFile)
                videoFileName = encFile.name
                tvFileName.text = videoFileName
            } else {
                Toast.makeText(this, "解密失败", Toast.LENGTH_SHORT).show()
                finish()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "播放失败: ${e.message}", Toast.LENGTH_SHORT).show()
            finish()
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
                            if (!isSingleLoop && !isABLoopActive) {
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
        player?.play()
        isPlaying = true
        updatePlayPauseButton()
    }

    private fun pauseVideo() {
        player?.pause()
        isPlaying = false
        updatePlayPauseButton()
        btnFrameForward.visibility = View.VISIBLE
        btnFrameBackward.visibility = View.VISIBLE
    }

    private fun updatePlayPauseButton() {
        if (isPlaying) {
            btnPlayPause.setImageResource(R.drawable.ic_pause)
            btnFrameForward.visibility = View.GONE
            btnFrameBackward.visibility = View.GONE
        } else {
            btnPlayPause.setImageResource(R.drawable.ic_play)
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

    private fun showControls() {
        controlsVisible = true
        topBar.visibility = View.VISIBLE
        bottomBar.visibility = View.VISIBLE
        if (!isPlaying) {
            btnFrameForward.visibility = View.VISIBLE
            btnFrameBackward.visibility = View.VISIBLE
        }
        autoHideControls()
    }

    private fun hideControls() {
        if (controlsPermanent) return
        controlsVisible = false
        topBar.visibility = View.GONE
        bottomBar.visibility = View.GONE
        btnFrameForward.visibility = View.GONE
        btnFrameBackward.visibility = View.GONE
    }

    private fun autoHideControls() {
        hideHandler.removeCallbacksAndMessages(null)
        if (controlsPermanent) return
        hideHandler.postDelayed({
            if (isPlaying) hideControls()
        }, 4000)
    }

    // ==================== 进度更新 ====================

    private fun startProgressUpdate() {
        hideHandler.postDelayed(object : Runnable {
            override fun run() {
                val p = player
                if (p != null && (isPlaying || controlsVisible)) {
                    val duration = p.duration
                    if (duration > 0) {
                        val progress = (p.currentPosition.toFloat() / duration * 1000).toInt()
                        seekBar.progress = progress
                        tvTime.text = "${formatTime(p.currentPosition)} / ${formatTime(duration)}"
                    }
                    seekABLoop()
                    hideHandler.postDelayed(this, 200)
                } else {
                    hideHandler.postDelayed(this, 200)
                }
            }
        }, 200)
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
        isRecordingGif = true
        btnGif.setImageResource(R.drawable.ic_gif_recording)
        Toast.makeText(this, "开始录制GIF，再次点击停止", Toast.LENGTH_SHORT).show()

        gifFrames.clear()
        gifExecutor.execute {
            while (isRecordingGif) {
                val bitmap = videoView.getBitmap()
                if (bitmap != null) {
                    synchronized(gifFrames) { gifFrames.add(bitmap) }
                }
                try {
                    Thread.sleep(100)
                } catch (_: InterruptedException) {
                }
            }
            saveGif()
        }
    }

    private fun stopGifCapture() {
        isRecordingGif = false
        btnGif.setImageResource(R.drawable.ic_gif)
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
        // 清理解密临时文件
        tempDecryptFile?.let {
            try { it.delete() } catch (_: Exception) {}
        }
        finish()
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
        player?.stop()
        player?.release()
        player = null
        gifExecutor.shutdownNow()
        tempDecryptFile?.let {
            try { it.delete() } catch (_: Exception) {}
        }
    }
}
