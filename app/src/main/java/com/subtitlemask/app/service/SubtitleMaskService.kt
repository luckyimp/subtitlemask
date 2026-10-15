package com.subtitlemask.app.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import com.subtitlemask.app.MainActivity
import com.subtitlemask.app.R
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
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private lateinit var layoutParams: WindowManager.LayoutParams

    private var maskAlpha = 0.95f
    private var maskColor = Color.BLACK
    private var maskWidth = 600
    private var maskHeight = 120
    private var cornerRadius = 24f

    private var isTemporaryHidden = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            maskAlpha = it.getFloatExtra(EXTRA_ALPHA, maskAlpha)
            maskColor = it.getIntExtra(EXTRA_COLOR, maskColor)
            val reqWidthDp = it.getIntExtra(EXTRA_WIDTH, 320)
            val reqHeightDp = it.getIntExtra(EXTRA_HEIGHT, 52)
            val reqRadiusDp = it.getIntExtra(EXTRA_RADIUS, 12)

            maskWidth = dpToPx(reqWidthDp)
            maskHeight = dpToPx(reqHeightDp)
            cornerRadius = dpToPx(reqRadiusDp).toFloat()
        }

        if (overlayView == null) {
            initOverlayView()
        } else {
            updateMaskAppearance()
        }

        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initOverlayView() {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            maskWidth,
            maskHeight,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            x = 0
            y = dpToPx(200)
        }

        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.layout_subtitle_mask, null)

        val maskBar = overlayView!!.findViewById<View>(R.id.viewMaskBar)
        val btnEye = overlayView!!.findViewById<ImageView>(R.id.btnPeek)
        val btnClose = overlayView!!.findViewById<ImageView>(R.id.btnClose)
        val handleResize = overlayView!!.findViewById<View>(R.id.handleResize)

        updateMaskAppearance()

        // 单指拖拽自由移动
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        maskBar.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(overlayView, layoutParams)
                    true
                }
                else -> false
            }
        }

        // 边缘拉伸手柄
        var startW = 0
        var startH = 0
        handleResize.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startW = layoutParams.width
                    startH = layoutParams.height
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - initialTouchX).toInt()
                    val deltaY = (event.rawY - initialTouchY).toInt()
                    layoutParams.width = max(dpToPx(120), startW + deltaX * 2)
                    layoutParams.height = max(dpToPx(30), startH + deltaY * 2)
                    windowManager.updateViewLayout(overlayView, layoutParams)
                    true
                }
                else -> false
            }
        }

        btnEye.setOnClickListener {
            togglePeek()
        }

        btnClose.setOnClickListener {
            stopSelf()
        }

        windowManager.addView(overlayView, layoutParams)
    }

    private fun togglePeek() {
        isTemporaryHidden = !isTemporaryHidden
        val maskBar = overlayView?.findViewById<View>(R.id.viewMaskBar) ?: return
        val btnEye = overlayView?.findViewById<ImageView>(R.id.btnPeek) ?: return

        if (isTemporaryHidden) {
            maskBar.alpha = 0.08f
            btnEye.setImageResource(R.drawable.ic_eye_off)
        } else {
            maskBar.alpha = maskAlpha
            btnEye.setImageResource(R.drawable.ic_eye)
        }
    }

    private fun updateMaskAppearance() {
        val maskBar = overlayView?.findViewById<View>(R.id.viewMaskBar) ?: return
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(maskColor)
            cornerRadius = this@SubtitleMaskService.cornerRadius
        }
        maskBar.background = drawable
        maskBar.alpha = if (isTemporaryHidden) 0.08f else maskAlpha

        layoutParams.width = maskWidth
        layoutParams.height = maskHeight
        windowManager.updateViewLayout(overlayView, layoutParams)
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
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("字幕遮挡条运行中")
            .setContentText("悬浮窗常驻中，单指拖拽移动，点击❌即可退出")
            .setSmallIcon(R.drawable.ic_mask_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
    }

    override fun onDestroy() {
        super.onDestroy()
        overlayView?.let {
            windowManager.removeView(it)
            overlayView = null
        }
    }
}