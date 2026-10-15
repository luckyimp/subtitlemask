package com.subtitlemask.app

import android.content.Intent
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

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "悬浮窗权限已授权", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "需要悬浮窗权限才能在视频上方显示遮挡条", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SubtitleMaskTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SubtitleMaskScreen(
                        onRequestPermission = { checkAndRequestOverlayPermission() },
                        onToggleService = { isRunning, alpha, colorInt, width, height, radius, showBall, ballAlpha ->
                            if (isRunning) {
                                startMaskService(alpha, colorInt, width, height, radius, showBall, ballAlpha)
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

    private fun startMaskService(
        alpha: Float,
        colorInt: Int,
        widthDp: Int,
        heightDp: Int,
        radiusDp: Int,
        showBall: Boolean,
        ballAlpha: Float
    ) {
        if (!Settings.canDrawOverlays(this)) {
            checkAndRequestOverlayPermission()
            return
        }

        val serviceIntent = Intent(this, SubtitleMaskService::class.java).apply {
            putExtra(SubtitleMaskService.EXTRA_ALPHA, alpha)
            putExtra(SubtitleMaskService.EXTRA_COLOR, colorInt)
            putExtra(SubtitleMaskService.EXTRA_WIDTH, widthDp)
            putExtra(SubtitleMaskService.EXTRA_HEIGHT, heightDp)
            putExtra(SubtitleMaskService.EXTRA_RADIUS, radiusDp)
            putExtra(SubtitleMaskService.EXTRA_SHOW_BALL, showBall)
            putExtra(SubtitleMaskService.EXTRA_BALL_IDLE_ALPHA, ballAlpha)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Toast.makeText(this, "悬浮遮挡条与侧边悬浮球已开启", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "启动服务异常: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopMaskService() {
        try {
            val serviceIntent = Intent(this, SubtitleMaskService::class.java)
            stopService(serviceIntent)
            Toast.makeText(this, "悬浮遮挡条已完全关闭", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            // ignore
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitleMaskScreen(
    onRequestPermission: () -> Unit,
    onToggleService: (Boolean, Float, Int, Int, Int, Int, Boolean, Float) -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var isServiceRunning by remember { mutableStateOf(false) }
    var alphaValue by remember { mutableFloatStateOf(0.95f) }
    var selectedColor by remember { mutableStateOf(Color.Black) }
    var barWidth by remember { mutableIntStateOf(320) }
    var barHeight by remember { mutableIntStateOf(52) }
    var cornerRadius by remember { mutableIntStateOf(12) }
    var showFloatingBall by remember { mutableStateOf(true) }
    var ballIdleAlpha by remember { mutableFloatStateOf(0.35f) }

    val presetColors = listOf(
        Color(0xFF000000) to "纯黑 OLED",
        Color(0xFF1E1E24) to "极深灰",
        Color(0xFF2C3E50) to "午夜蓝",
        Color(0xFFFFFFFF) to "纯白"
    )

    fun notifyUpdate() {
        if (isServiceRunning) {
            onToggleService(true, alphaValue, selectedColor.toArgb(), barWidth, barHeight, cornerRadius, showFloatingBall, ballIdleAlpha)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "字幕遮挡条",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                },
                actions = {
                    IconButton(onClick = onRequestPermission) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = "检查悬浮窗权限"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // 权限提示横幅
            val hasPermission = remember(isServiceRunning) {
                Settings.canDrawOverlays(context)
            }
            if (!hasPermission) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "尚未授权“悬浮窗”权限",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                "请点击授权，以便在看视频或网课时常驻遮挡字幕",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        TextButton(onClick = onRequestPermission) {
                            Text("去授权")
                        }
                    }
                }
            }

            // 主开关卡片
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
                                ballIdleAlpha
                            )
                        }
                    )
                }
            }

            // 侧边悬浮球控制卡片
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

                        Text(
                            "💡 锁屏自愈逻辑：手机熄屏锁屏时浮窗自动休眠，解锁手机后自动收纳为侧边悬浮球待命，轻触即可随心呼出遮挡条！",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // 属性调节控制面板
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        "预设遮挡条属性",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    // 1. 透明度 Slider
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

                    // 2. 颜色选择器
                    Column {
                        Text("遮挡条颜色")
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            presetColors.forEach { (color, label) ->
                                val isSelected = selectedColor == color
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                        .border(
                                            width = if (isSelected) 3.dp else 1.dp,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray,
                                            shape = CircleShape
                                        )
                                        .clickable {
                                            selectedColor = color
                                            notifyUpdate()
                                        }
                                )
                            }
                        }
                    }

                    // 3. 尺寸调节：宽度与高度
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
                            valueRange = 160f..400f
                        )
                    }

                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("默认高度")
                            Text("${barHeight} dp")
                        }
                        Slider(
                            value = barHeight.toFloat(),
                            onValueChange = {
                                barHeight = it.toInt()
                                notifyUpdate()
                            },
                            valueRange = 30f..120f
                        )
                    }

                    // 4. 圆角平滑度
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
}