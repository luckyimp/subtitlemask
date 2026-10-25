package com.subtitlemask.app.service

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.subtitlemask.app.MainActivity
import com.subtitlemask.app.R
import kotlin.math.abs
import kotlin.math.max

class SubtitleMaskService : Service() {

    companion object {
        const val ACTION_START = "com.subtitlemask.app.ACTION_START"
        const val ACTION_STOP = "com.subtitlemask.app.ACTION_STOP"
        const val ACTION_UPDATE_PROJECTION = "com.subtitlemask.app.ACTION_UPDATE_PROJECTION"
        const val EXTRA_PROJECTION_DATA = "extra_projection_data"
        private const val CHANNEL_ID = "subtitle_mask_foreground_service"
        private const val NOTIFICATION_ID = 1001
    }

    private lateinit var windowManager: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    // 复合悬浮窗状态 (上层遮挡条 + 下层放大镜)
    private var barOverlayView: View? = null
    private lateinit var barLayoutParams: WindowManager.LayoutParams
    private var isBarAdded = false
    private var isBarVisible = true
    private var isTemporaryHidden = false

    // 侧边悬浮球状态
    private var ballOverlayView: View? = null
    private lateinit var ballLayoutParams: WindowManager.LayoutParams
    private var isBallAdded = false

    // 方案 A: MediaProjection 光学放大镜
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var magnifierEnabled = true
    private var magnifierScale = 1.8f
    private var magnifierHeightDp = 38

    // 基础参数
    private var maskAlpha = 0.95f
    private var maskColor = Color.BLACK
    private var maskWidth = 0
    private var maskHeight = 0
    private var cornerRadius = 12f
    private var showFloatingBall = true
    private var ballIdleAlpha = 0.35f

    // 手机锁屏监听 BroadcastReceiver
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    barOverlayView?.visibility = View.GONE
                    isBarVisible = false
                    updateBallIndicator()
                }
                Intent.ACTION_USER_PRESENT -> {
                    if (showFloatingBall) {
                        showBall()
                    }
                }
            }
        }
    }

    private val idleFadeRunnable = Runnable {
        ballOverlayView?.animate()?.alpha(ballIdleAlpha)?.setDuration(400)?.start()
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_STICKY

        when (intent.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_PROJECTION -> {
                val projData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
                }
                if (projData != null) {
                    setupMediaProjection(projData)
                }
            }
            ACTION_START, null -> {
                maskAlpha = intent.getFloatExtra("alpha", 0.95f)
                maskColor = intent.getIntExtra("color", Color.BLACK)
                maskWidth = dpToPx(intent.getIntExtra("width", 320))
                maskHeight = dpToPx(intent.getIntExtra("height", 40))
                cornerRadius = dpToPx(intent.getIntExtra("radius", 12)).toFloat()
                showFloatingBall = intent.getBooleanExtra("showBall", true)
                ballIdleAlpha = intent.getFloatExtra("ballAlpha", 0.35f)
                magnifierEnabled = intent.getBooleanExtra("magEnabled", true)
                magnifierScale = intent.getFloatExtra("magScale", 1.8f)
                magnifierHeightDp = intent.getIntExtra("magHeight", 38)

                startForegroundNotification()

                if (!isBarAdded) {
                    initBarOverlayView()
                } else {
                    updateBarAppearance()
                }

                if (showFloatingBall) {
                    if (!isBallAdded) {
                        initBallOverlayView()
                    } else {
                        showBall()
                    }
                } else {
                    hideBall()
                }
            }
        }

        return START_STICKY
    }

    private fun startForegroundNotification() {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val fgType = if (mediaProjection != null) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                }
                startForeground(NOTIFICATION_ID, notification, fgType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e("SubtitleMaskService", "startForeground error", e)
        }
    }

    private fun setupMediaProjection(projData: Intent) {
        try {
            val mg = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mg.getMediaProjection(Activity.RESULT_OK, projData)
            setupVirtualDisplayForMagnifier()
        } catch (e: Exception) {
            Log.e("SubtitleMaskService", "setupMediaProjection failed", e)
        }
    }

    private fun setupVirtualDisplayForMagnifier() {
        val textureView = barOverlayView?.findViewById<TextureView>(R.id.textureMagnifier) ?: return
        if (!magnifierEnabled || mediaProjection == null) return

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                val displayMetrics = resources.displayMetrics
                val surfaceObj = Surface(surface)
                try {
                    virtualDisplay?.release()
                    virtualDisplay = mediaProjection?.createVirtualDisplay(
                        "SubtitleMagnifierDisplay",
                        displayMetrics.widthPixels,
                        displayMetrics.heightPixels,
                        displayMetrics.densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        surfaceObj,
                        null,
                        mainHandler
                    )

                    // 应用 Matrix 硬件缩放 (方案 A: 将英文字幕像素放大 1.8×)
                    val matrix = Matrix().apply {
                        postScale(magnifierScale, magnifierScale, (width / 2).toFloat(), (height / 2).toFloat())
                    }
                    textureView.setTransform(matrix)
                } catch (e: Exception) {
                    Log.e("SubtitleMaskService", "virtualDisplay creation error", e)
                }
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initBarOverlayView() {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val displayMetrics = resources.displayMetrics
        val totalHeight = if (magnifierEnabled) maskHeight + dpToPx(magnifierHeightDp) else maskHeight

        barLayoutParams = WindowManager.LayoutParams(
            maskWidth,
            totalHeight,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (displayMetrics.widthPixels - maskWidth) / 2
            y = (displayMetrics.heightPixels * 0.76f).toInt()
        }

        val inflater = LayoutInflater.from(this)
        barOverlayView = inflater.inflate(R.layout.layout_subtitle_mask, null)

        val maskBar = barOverlayView!!.findViewById<View>(R.id.viewMaskBar)
        val btnEye = barOverlayView!!.findViewById<ImageView>(R.id.btnPeek)
        val btnZoom = barOverlayView!!.findViewById<ImageView>(R.id.btnZoomToggle)
        val btnClose = barOverlayView!!.findViewById<ImageView>(R.id.btnClose)
        val btnMinimize = barOverlayView!!.findViewById<ImageView>(R.id.btnMinimize)
        val handleResize = barOverlayView!!.findViewById<View>(R.id.handleResize)

        updateBarAppearance()

        // 拖拽与缩放事件绑定
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        barOverlayView!!.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = barLayoutParams.x
                    initialY = barLayoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    barLayoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    barLayoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    if (isBarAdded && barOverlayView != null) {
                        try {
                            windowManager.updateViewLayout(barOverlayView, barLayoutParams)
                        } catch (e: Exception) {
                            Log.e("SubtitleMaskService", "bar move error", e)
                        }
                    }
                    true
                }
                else -> false
            }
        }

        btnEye.setOnClickListener { togglePeek() }
        btnZoom?.setOnClickListener {
            magnifierEnabled = !magnifierEnabled
            updateBarAppearance()
        }
        btnClose.setOnClickListener { stopSelf() }
        btnMinimize?.setOnClickListener { hideBar() }

        try {
            windowManager.addView(barOverlayView, barLayoutParams)
            isBarAdded = true
            isBarVisible = true
            setupVirtualDisplayForMagnifier()
        } catch (e: Exception) {
            Log.e("SubtitleMaskService", "addBarView failed", e)
        }
    }

    private fun updateBarAppearance() {
        val maskBar = barOverlayView?.findViewById<View>(R.id.viewMaskBar) ?: return
        val layoutMag = barOverlayView?.findViewById<View>(R.id.layoutMagnifier)
        val tvScale = barOverlayView?.findViewById<TextView>(R.id.tvScaleBadge)

        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(maskColor)
            cornerRadii = floatArrayOf(
                cornerRadius, cornerRadius,
                cornerRadius, cornerRadius,
                if (magnifierEnabled) 0f else cornerRadius, if (magnifierEnabled) 0f else cornerRadius,
                if (magnifierEnabled) 0f else cornerRadius, if (magnifierEnabled) 0f else cornerRadius
            )
        }
        maskBar.background = drawable
        maskBar.alpha = if (isTemporaryHidden) 0.08f else maskAlpha

        layoutMag?.visibility = if (magnifierEnabled) View.VISIBLE else View.GONE
        tvScale?.text = "${magnifierScale}× 光学透镜"

        if (isBarAdded && barOverlayView != null) {
            val totalH = if (magnifierEnabled) maskHeight + dpToPx(magnifierHeightDp) else maskHeight
            barLayoutParams.width = maskWidth
            barLayoutParams.height = totalH
            try {
                windowManager.updateViewLayout(barOverlayView, barLayoutParams)
            } catch (ignored: Exception) {}
        }
    }

    private fun togglePeek() {
        isTemporaryHidden = !isTemporaryHidden
        updateBarAppearance()
    }

    private fun hideBar() {
        barOverlayView?.visibility = View.GONE
        isBarVisible = false
        updateBallIndicator()
    }

    private fun showBall() {
        if (!isBallAdded && ballOverlayView != null) {
            try {
                windowManager.addView(ballOverlayView, ballLayoutParams)
                isBallAdded = true
            } catch (ignored: Exception) {}
        }
        ballOverlayView?.visibility = View.VISIBLE
        updateBallIndicator()
    }

    private fun hideBall() {
        ballOverlayView?.visibility = View.GONE
    }

    private fun updateBallIndicator() {
        val dot = ballOverlayView?.findViewById<View>(R.id.dotActiveState) ?: return
        dot.visibility = if (isBarVisible) View.VISIBLE else View.GONE
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initBallOverlayView() {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val displayMetrics = resources.displayMetrics
        val ballSize = dpToPx(48)

        ballLayoutParams = WindowManager.LayoutParams(
            ballSize,
            ballSize,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = displayMetrics.widthPixels - ballSize - dpToPx(8)
            y = (displayMetrics.heightPixels * 0.45f).toInt()
        }

        val inflater = LayoutInflater.from(this)
        ballOverlayView = inflater.inflate(R.layout.layout_floating_ball, null)

        var initialX = 0
        var initialY = 0
        var touchStartX = 0f
        var touchStartY = 0f
        var isClick = true

        ballOverlayView!!.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = ballLayoutParams.x
                    initialY = ballLayoutParams.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    isClick = true
                    ballOverlayView?.alpha = 0.95f
                    mainHandler.removeCallbacks(idleFadeRunnable)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchStartX).toInt()
                    val dy = (event.rawY - touchStartY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        isClick = false
                    }
                    ballLayoutParams.x = initialX + dx
                    ballLayoutParams.y = initialY + dy
                    if (isBallAdded && ballOverlayView != null) {
                        try {
                            windowManager.updateViewLayout(ballOverlayView, ballLayoutParams)
                        } catch (ignored: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isClick) {
                        toggleBarVisibility()
                    } else {
                        // 磁吸贴边
                        val screenWidth = resources.displayMetrics.widthPixels
                        val targetX = if (ballLayoutParams.x + ballSize / 2 < screenWidth / 2) {
                            dpToPx(8)
                        } else {
                            screenWidth - ballSize - dpToPx(8)
                        }
                        animateBallToEdge(ballLayoutParams.x, targetX)
                    }
                    mainHandler.postDelayed(idleFadeRunnable, 2500)
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(ballOverlayView, ballLayoutParams)
            isBallAdded = true
            updateBallIndicator()
            mainHandler.postDelayed(idleFadeRunnable, 2500)
        } catch (ignored: Exception) {}
    }

    private fun toggleBarVisibility() {
        if (isBarVisible) {
            hideBar()
        } else {
            barOverlayView?.visibility = View.VISIBLE
            isBarVisible = true
            updateBallIndicator()
        }
    }

    private fun animateBallToEdge(fromX: Int, toX: Int) {
        val animator = ValueAnimator.ofInt(fromX, toX).apply {
            duration = 250
            interpolator = DecelerateInterpolator()
            addUpdateListener { va ->
                ballLayoutParams.x = va.animatedValue as Int
                if (isBallAdded && ballOverlayView != null) {
                    try {
                        windowManager.updateViewLayout(ballOverlayView, ballLayoutParams)
                    } catch (ignored: Exception) {}
                }
            }
        }
        animator.start()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "字幕遮挡条前台服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持悬浮遮挡条与放大镜在后台不被系统杀死"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("字幕遮挡条 & 光学透镜运行中")
            .setContentText("正在遮挡中文字幕并实时放大英文字幕")
            .setSmallIcon(R.drawable.ic_mask_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(idleFadeRunnable)
        virtualDisplay?.release()
        mediaProjection?.stop()

        try {
            unregisterReceiver(screenReceiver)
        } catch (ignored: Exception) {}

        if (isBarAdded && barOverlayView != null) {
            try {
                windowManager.removeView(barOverlayView)
            } catch (ignored: Exception) {}
            barOverlayView = null
            isBarAdded = false
        }

        if (isBallAdded && ballOverlayView != null) {
            try {
                windowManager.removeView(ballOverlayView)
            } catch (ignored: Exception) {}
            ballOverlayView = null
            isBallAdded = false
        }
    }
}