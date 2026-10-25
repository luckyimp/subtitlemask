package com.subtitlemask.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subtitlemask.app.service.SubtitleMaskService
import com.subtitlemask.app.ui.theme.SubtitleMaskTheme

class MainActivity : ComponentActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var pendingServiceStartArgs: Bundle? = null

    // 1. 悬浮窗 SYSTEM_ALERT_WINDOW 权限申请
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "悬浮窗权限已授权", Toast.LENGTH_SHORT).show()
            pendingServiceStartArgs?.let { startMaskServiceInternal(it) }
        } else {
            Toast.makeText(this, "需要悬浮窗权限才能在视频上方显示遮挡条", Toast.LENGTH_LONG).show()
        }
    }

    // 2. 方案 A: MediaProjection 屏幕捕获权限申请 (用于实时无损光学放大英文字幕)
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, SubtitleMaskService::class.java).apply {
                action = SubtitleMaskService.ACTION_UPDATE_PROJECTION
                putExtra(SubtitleMaskService.EXTRA_PROJECTION_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Toast.makeText(this, "光学放大镜已激活：GPU 实时放大就绪", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setContent {
            SubtitleMaskTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SubtitleMaskScreen(
                        onRequestPermission = { checkAndRequestOverlayPermission() },
                        onRequestProjection = { requestProjectionPermission() },
                        onToggleService = { isRunning, alpha, colorInt, width, height, radius, showBall, ballAlpha, magEnabled, magScale, magHeight ->
                            if (isRunning) {
                                val bundle = Bundle().apply {
                                    putFloat("alpha", alpha)
                                    putInt("color", colorInt)
                                    putInt("width", width)
                                    putInt("height", height)
                                    putInt("radius", radius)
                                    putBoolean("showBall", showBall)
                                    putFloat("ballAlpha", ballAlpha)
                                    putBoolean("magEnabled", magEnabled)
                                    putFloat("magScale", magScale)
                                    putInt("magHeight", magHeight)
                                }
                                startMaskService(bundle)
                            } else {
                                stopMaskService()
                            }
                        }
                    )
                }
            }
        }
    }

    private fun checkAndRequestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }
    }

    private fun requestProjectionPermission() {
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        projectionLauncher.launch(captureIntent)
    }

    private fun startMaskService(args: Bundle) {
        if (!Settings.canDrawOverlays(this)) {
            pendingServiceStartArgs = args
            checkAndRequestOverlayPermission()
            return
        }
        startMaskServiceInternal(args)
    }

    private fun startMaskServiceInternal(args: Bundle) {
        val serviceIntent = Intent(this, SubtitleMaskService::class.java).apply {
            action = SubtitleMaskService.ACTION_START
            putExtras(args)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        // 如果开启了光学放大镜，且尚未获得投影令牌，自动请求一次投影授权
        if (args.getBoolean("magEnabled", true)) {
            requestProjectionPermission()
        }
    }

    private fun stopMaskService() {
        val serviceIntent = Intent(this, SubtitleMaskService::class.java).apply {
            action = SubtitleMaskService.ACTION_STOP
        }
        startService(serviceIntent)
    }
}

@Composable
fun SubtitleMaskScreen(
    onRequestPermission: () -> void = {},
    onRequestProjection: () -> void = {},
    onToggleService: (Boolean, Float, Int, Int, Int, Int, Boolean, Float, Boolean, Float, Int) -> Unit
) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var isServiceRunning by remember { mutableStateOf(false) }

    // 遮挡条属性
    var alphaValue by remember { mutableFloatStateOf(0.95f) }
    var selectedColor by remember { mutableStateOf(Color(0xFF000000)) }
    var barWidth by remember { mutableIntStateOf(320) }
    var barHeight by remember { mutableIntStateOf(40) }
    var cornerRadius by remember { mutableIntStateOf(12) }

    // 侧边悬浮球属性
    var showFloatingBall by remember { mutableStateOf(true) }
    var ballIdleAlpha by remember { mutableFloatStateOf(0.35f) }

    // 方案 A: 光学字幕放大镜属性
    var magnifierEnabled by remember { mutableStateOf(true) }
    var magnifierScale by remember { mutableFloatStateOf(1.8f) }
    var magnifierHeight by remember { mutableIntStateOf(38) }

    val presetColors = listOf(
        Color(0xFF000000) to "纯黑 OLED",
        Color(0xFF1E1E24) to "深炭灰",
        Color(0xFF0F172A) to "午夜蓝",
        Color(0xFF334155) to "石墨灰",
        Color(0xFFFFFFFF) to "纯白"
    )

    fun notifyUpdate() {
        if (isServiceRunning) {
            onToggleService(
                true,
                alphaValue,
                selectedColor.toArgb(),
                barWidth,
                barHeight,
                cornerRadius,
                showFloatingBall,
                ballIdleAlpha,
                magnifierEnabled,
                magnifierScale,
                magnifierHeight
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App 顶栏标题
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "字幕遮挡条 & 光学透镜",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "上层遮挡中文 · 下层放大英文 · 磁吸悬浮球",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 1. 悬浮窗权限检查卡片
        if (!hasPermission) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "未授权悬浮窗权限",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "必须授予 SYSTEM_ALERT_WINDOW 权限，遮挡条才能常驻在视频播放器之上。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onRequestPermission,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("去设置中开启权限")
                    }
                }
            }
        }

        // 2. 主服务启闭开关
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isServiceRunning)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isServiceRunning) "遮挡条已在前台开启" else "悬浮遮挡条已关闭",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (isServiceRunning) "轻触侧边悬浮球随时收纳/呼出，支持自动磁吸贴边" else "开启后将在屏幕侧边显示悬浮球与字幕遮挡条",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isServiceRunning,
                    onCheckedChange = { checked ->
                        if (!Settings.canDrawOverlays(context)) {
                            onRequestPermission()
                            return@Switch
                        }
                        isServiceRunning = checked
                        onToggleService(
                            checked,
                            alphaValue,
                            selectedColor.toArgb(),
                            barWidth,
                            barHeight,
                            cornerRadius,
                            showFloatingBall,
                            ballIdleAlpha,
                            magnifierEnabled,
                            magnifierScale,
                            magnifierHeight
                        )
                    }
                )
            }
        }

        // 3. 方案 A: 英文字幕光学放大镜配置卡片
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "英文字幕光学放大镜 (方案 A)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "MediaProjection + GPU 实时无损放大下排小字",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = magnifierEnabled,
                        onCheckedChange = {
                            magnifierEnabled = it
                            if (it) onRequestProjection()
                            notifyUpdate()
                        }
                    )
                }

                if (magnifierEnabled) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("放大倍率：${magnifierScale}×")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(1.4f to "1.4×", 1.8f to "1.8× (推荐)", 2.2f to "2.2×").forEach { (scale, label) ->
                                FilterChip(
                                    selected = magnifierScale == scale,
                                    onClick = {
                                        magnifierScale = scale
                                        notifyUpdate()
                                    },
                                    label = { Text(label) }
                                )
                            }
                        }
                    }

                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("放大视窗高度", style = MaterialTheme.typography.bodyMedium)
                            Text("${magnifierHeight} dp", fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = magnifierHeight.toFloat(),
                            onValueChange = {
                                magnifierHeight = it.toInt()
                                notifyUpdate()
                            },
                            valueRange = 28f..56f
                        )
                    }
                }
            }
        }

        // 4. 侧边悬浮球控制卡片
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "侧边极简悬浮球 (Floating Bubble)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "单击展开/收纳遮挡条，拖拽松手自动贴边",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = showFloatingBall,
                        onCheckedChange = {
                            showFloatingBall = it
                            notifyUpdate()
                        }
                    )
                }

                if (showFloatingBall) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("静止 2.5 秒后弱化透明度", style = MaterialTheme.typography.bodyMedium)
                            Text("${(ballIdleAlpha * 100).toInt()}%", fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = ballIdleAlpha,
                            onValueChange = {
                                ballIdleAlpha = it
                                notifyUpdate()
                            },
                            valueRange = 0.15f..0.8f
                        )
                    }
                }
            }
        }

        // 5. 遮挡条外观微调面板
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    "遮挡条外观微调",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                // 不透明度
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("不透明度 (Alpha)")
                        Text("${(alphaValue * 100).toInt()}%")
                    }
                    Slider(
                        value = alphaValue,
                        onValueChange = {
                            alphaValue = it
                            notifyUpdate()
                        },
                        valueRange = 0.2f..1.0f
                    )
                }

                // 尺寸调节：宽度与高度
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("默认宽度")
                        Text("${barWidth} dp")
                    }
                    Slider(
                        value = barWidth.toFloat(),
                        onValueChange = {
                            barWidth = it.toInt()
                            notifyUpdate()
                        },
                        valueRange = 160f..380f
                    )
                }

                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("遮挡高度")
                        Text("${barHeight} dp")
                    }
                    Slider(
                        value = barHeight.toFloat(),
                        onValueChange = {
                            barHeight = it.toInt()
                            notifyUpdate()
                        },
                        valueRange = 26f..90f
                    )
                }

                // 边缘平滑圆角
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("边缘平滑圆角")
                        Text("${cornerRadius} dp")
                    }
                    Slider(
                        value = cornerRadius.toFloat(),
                        onValueChange = {
                            cornerRadius = it.toInt()
                            notifyUpdate()
                        },
                        valueRange = 0f..24f
                    )
                }
            }
        }
    }
}