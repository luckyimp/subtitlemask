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
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.subtitlemask.app.MainActivity
import com.subtitlemask.app.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class SubtitleMaskService : Service() {

    companion object {
        const val CHANNEL_ID = "subtitle_mask_channel"
        const val NOTIFICATION_ID = 1001

        const val EXTRA_ALPHA = "extra_alpha"
        const val EXTRA_COLOR = "extra_color"
        const val EXTRA_WIDTH = "extra_width"
        const val EXTRA_HEIGHT = "extra_height"
        const val EXTRA_RADIUS = "extra_radius"
        const val EXTRA_SHOW_BALL = "extra_show_ball"
        const val EXTRA_BALL_IDLE_ALPHA = "extra_ball_idle_alpha"
    }

    private lateinit var windowManager: WindowManager

    // 1. 遮挡条视图与参数
    private var barOverlayView: View? = null
    private lateinit var barLayoutParams: WindowManager.LayoutParams
    private var isBarAdded = false
    private var isBarVisible = true

    // 2. 悬浮球视图与参数
    private var ballOverlayView: View? = null
    private lateinit var ballLayoutParams: WindowManager.LayoutParams
    private var isBallAdded = false
    private var showFloatingBall = true
    private var ballIdleAlpha = 0.35f

    // 遮挡条配置属性
    private var maskAlpha = 0.95f
    private var maskColor = Color.BLACK
    private var maskWidth = 600
    private var maskHeight = 120
    private var cornerRadius = 24f
    private var isTemporaryHidden = false

    // 悬浮球弱化定时器
    private val mainHandler = Handler(Looper.getMainLooper())
    private val idleFadeRunnable = Runnable {
        ballOverlayView?.animate()?.alpha(ballIdleAlpha)?.setDuration(300)?.start()
    }

    // 锁屏与解锁监听 BroadcastReceiver
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    // 锁屏时隐藏遮挡条以保护隐私和省电
                    hideBar()
                }
                Intent.ACTION_USER_PRESENT -> {
                    // 解锁后默认收纳为侧边悬浮球待命，由用户轻触按需呼出
                    hideBar()
                    if (showFloatingBall) {
                        showBall()
                        resetBallIdleTimer()
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (e: Exception) {
            Log.e("SubtitleMaskService", "startForeground failed", e)
            try {
                startForeground(NOTIFICATION_ID, buildNotification())
            } catch (ignored: Exception) {}
        }

        // 注册屏幕锁定与解锁广播
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            maskAlpha = it.getFloatExtra(EXTRA_ALPHA, maskAlpha)
            maskColor = it.getIntExtra(EXTRA_COLOR, maskColor)
            val reqWidthDp = it.getIntExtra(EXTRA_WIDTH, 320)
            val reqHeightDp = it.getIntExtra(EXTRA_HEIGHT, 52)
            val reqRadiusDp = it.getIntExtra(EXTRA_RADIUS, 12)
            showFloatingBall = it.getBooleanExtra(EXTRA_SHOW_BALL, showFloatingBall)
            ballIdleAlpha = it.getFloatExtra(EXTRA_BALL_IDLE_ALPHA, ballIdleAlpha)

            maskWidth = dpToPx(reqWidthDp)
            maskHeight = dpToPx(reqHeightDp)
            cornerRadius = dpToPx(reqRadiusDp).toFloat()
        }

        // 初始化或刷新遮挡条
        if (barOverlayView == null) {
            initBarOverlayView()
        } else {
            updateBarAppearance()
        }

        // 初始化或刷新侧边悬浮球
        if (showFloatingBall) {
            if (ballOverlayView == null) {
                initBallOverlayView()
            } else {
                showBall()
            }
        } else {
            hideBall()
        }

        return START_STICKY
    }

    // ==========================================
    // 1. 遮挡条 (Mask Bar) 初始化与手势
    // ==========================================
    @SuppressLint("ClickableViewAccessibility")
    private fun initBarOverlayView() {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val displayMetrics = resources.displayMetrics
        barLayoutParams = WindowManager.LayoutParams(
            maskWidth,
            maskHeight,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (displayMetrics.widthPixels - maskWidth).coerceAtLeast(0) / 2
            y = (displayMetrics.heightPixels * 0.70f).toInt()
        }

        val inflater = LayoutInflater.from(this)
        barOverlayView = inflater.inflate(R.layout.layout_subtitle_mask, null)

        val maskBar = barOverlayView!!.findViewById<View>(R.id.viewMaskBar)
        val btnEye = barOverlayView!!.findViewById<ImageView>(R.id.btnPeek)
        val btnClose = barOverlayView!!.findViewById<ImageView>(R.id.btnClose)
        val btnMinimize = barOverlayView!!.findViewById<ImageView>(R.id.btnMinimize)
        val handleResize = barOverlayView!!.findViewById<View>(R.id.handleResize)

        // 样式绑定
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(maskColor)
            cornerRadius = this@SubtitleMaskService.cornerRadius
        }
        maskBar.background = drawable
        maskBar.alpha = maskAlpha

        // 单指拖拽
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        maskBar.setOnTouchListener { _, event ->
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
                            Log.e("SubtitleMaskService", "bar updateViewLayout error", e)
                        }
                    }
                    true
                }
                else -> false
            }
        }

        // 尺寸缩放手柄
        var startW = 0
        var startH = 0
        handleResize.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startW = barLayoutParams.width
                    startH = barLayoutParams.height
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - initialTouchX).toInt()
                    val deltaY = (event.rawY - initialTouchY).toInt()
                    barLayoutParams.width = max(dpToPx(120), startW + deltaX * 2)
                    barLayoutParams.height = max(dpToPx(30), startH + deltaY * 2)
                    if (isBarAdded && barOverlayView != null) {
                        try {
                            windowManager.updateViewLayout(barOverlayView, barLayoutParams)
                        } catch (e: Exception) {
                            Log.e("SubtitleMaskService", "bar resize error", e)
                        }
                    }
                    true
                }
                else -> false
            }
        }

        btnEye.setOnClickListener { togglePeek() }
        btnClose.setOnClickListener { stopSelf() }
        btnMinimize?.setOnClickListener { hideBar() }

        try {
            windowManager.addView(barOverlayView, barLayoutParams)
            isBarAdded = true
            isBarVisible = true
        } catch (e: Exception) {
            Log.e("SubtitleMaskService", "addBarView failed", e)
        }
    }

    // ==========================================
    // 2. 侧边悬浮球 (Floating Ball) 初始化与手势
    // ==========================================
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
            x = displayMetrics.widthPixels - ballSize - dpToPx(8) // 默认停靠右侧
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
                        // 单击悬浮球：切换遮挡条显示/隐藏
                        toggleBarVisibility()
                    } else {
                        // 拖拽释放：触发平滑磁吸贴边 (Magnetic Snap to Edge)
                        snapBallToNearestEdge()
                    }
                    resetBallIdleTimer()
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(ballOverlayView, ballLayoutParams)
            isBallAdded = true
            resetBallIdleTimer()
        } catch (e: Exception) {
            Log.e("SubtitleMaskService", "addBallView failed", e)
        }
    }

    private fun snapBallToNearestEdge() {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val ballSize = ballLayoutParams.width
        val currentX = ballLayoutParams.x

        val targetX = if (currentX + ballSize / 2 < screenWidth / 2) {
            dpToPx(8) // 贴紧左侧
        } else {
            screenWidth - ballSize - dpToPx(8) // 贴紧右侧
        }

        val animator = ValueAnimator.ofInt(currentX, targetX)
        animator.duration = 200
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener { animation ->
            ballLayoutParams.x = animation.animatedValue as Int
            if (isBallAdded && ballOverlayView != null) {
                try {
                    windowManager.updateViewLayout(ballOverlayView, ballLayoutParams)
                } catch (ignored: Exception) {}
            }
        }
        animator.start()
    }

    private fun resetBallIdleTimer() {
        mainHandler.removeCallbacks(idleFadeRunnable)
        ballOverlayView?.alpha = 0.95f
        mainHandler.postDelayed(idleFadeRunnable, 2500)
    }

    // ==========================================
    // 3. 状态控制方法
    // ==========================================
    private fun toggleBarVisibility() {
        if (isBarVisible) {
            hideBar()
        } else {
            showBar()
        }
    }

    private fun showBar() {
        if (!isBarAdded && barOverlayView != null) {
            try {
                windowManager.addView(barOverlayView, barLayoutParams)
                isBarAdded = true
            } catch (ignored: Exception) {}
        }
        barOverlayView?.visibility = View.VISIBLE
        isBarVisible = true
        updateBallIndicator()
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

    private fun togglePeek() {
        isTemporaryHidden = !isTemporaryHidden
        val maskBar = barOverlayView?.findViewById<View>(R.id.viewMaskBar) ?: return
        val btnEye = barOverlayView?.findViewById<ImageView>(R.id.btnPeek) ?: return

        if (isTemporaryHidden) {
            maskBar.alpha = 0.08f
            btnEye.setImageResource(R.drawable.ic_eye_off)
        } else {
            maskBar.alpha = maskAlpha
            btnEye.setImageResource(R.drawable.ic_eye)
        }
    }

    private fun updateBarAppearance() {
        val maskBar = barOverlayView?.findViewById<View>(R.id.viewMaskBar) ?: return
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(maskColor)
            cornerRadius = this@SubtitleMaskService.cornerRadius
        }
        maskBar.background = drawable
        maskBar.alpha = if (isTemporaryHidden) 0.08f else maskAlpha

        if (isBarAdded && barOverlayView != null) {
            barLayoutParams.width = maskWidth
            barLayoutParams.height = maskHeight
            try {
                windowManager.updateViewLayout(barOverlayView, barLayoutParams)
            } catch (e: Exception) {
                Log.e("SubtitleMaskService", "updateBarAppearance error", e)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "字幕遮挡条前台服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持悬浮遮挡条在后台不被系统杀死"
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
            .setContentTitle("字幕遮挡条运行中")
            .setContentText("轻触侧边悬浮球随时呼出/收起遮挡条")
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

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(idleFadeRunnable)

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