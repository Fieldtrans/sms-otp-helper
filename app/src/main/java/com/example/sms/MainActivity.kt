package com.example.sms

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.sms.ui.theme.SMSTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern
import kotlinx.coroutines.delay
import kotlin.math.ceil

private const val MANAGED_CLIP_LABEL_PREFIX = "CodeDelayLSP:"
private const val CLIP_TTL_MS = 90_000L
private const val EMPTY_CODE_TEXT = "无"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SMSTheme {
                LspModuleScreen(
                    onToast = { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() },
                )
            }
        }
    }
}

@Composable
private fun LspModuleScreen(
    onToast: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as? ComponentActivity

    var regex by remember { mutableStateOf(CodeStore.getRegex(context)) }
    var clipboardBridgeEnabled by remember { mutableStateOf(CodeStore.isClipboardBridgeEnabled(context)) }

    var lastCode by remember { mutableStateOf(CodeStore.getLastCode(context)) }
    var lastSource by remember { mutableStateOf(CodeStore.getLastSource(context)) }
    var lastSmsBody by remember { mutableStateOf(CodeStore.getLastSmsBody(context)) }
    var lastCodeAt by remember { mutableLongStateOf(CodeStore.getLastCodeSavedAtMs(context)) }
    var smsPermissionGranted by remember { mutableStateOf(hasSmsPermission(context)) }
    var clipboardOtpState by remember { mutableStateOf(readClipboardOtpState(context)) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var codeHistory by remember { mutableStateOf(CodeStore.getCodeHistory(context)) }
    var showHistory by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var screenStarted by remember {
        mutableStateOf(
            activity?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) != false,
        )
    }
    val smsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        smsPermissionGranted = granted
        if (!granted) {
            openAppPermissionSettings(context)
        }
    }

    fun refreshStatus(readClipboard: Boolean = false) {
        lastCode = CodeStore.getLastCode(context)
        lastSource = CodeStore.getLastSource(context)
        lastSmsBody = CodeStore.getLastSmsBody(context)
        lastCodeAt = CodeStore.getLastCodeSavedAtMs(context)
        smsPermissionGranted = hasSmsPermission(context)
        if (readClipboard) {
            clipboardOtpState = readClipboardOtpState(context)
        }
        clipboardBridgeEnabled = CodeStore.isClipboardBridgeEnabled(context)
        codeHistory = CodeStore.getCodeHistory(context)
    }

    DisposableEffect(activity) {
        val lifecycle = activity?.lifecycle
        if (lifecycle == null) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, _ ->
                screenStarted = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            }
            lifecycle.addObserver(observer)
            screenStarted = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            onDispose {
                lifecycle.removeObserver(observer)
            }
        }
    }

    LaunchedEffect(context, screenStarted) {
        while (screenStarted) {
            nowMs = System.currentTimeMillis()
            refreshStatus()
            delay(1_000L)
        }
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Actions.ACTION_SMS_STATUS_UPDATED) {
                    refreshStatus(readClipboard = true)
                }
            }
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
            refreshStatus(readClipboard = true)
        }
        clipboard?.addPrimaryClipChangedListener(clipListener)
        refreshStatus(readClipboard = true)
        val filter = IntentFilter(Actions.ACTION_SMS_STATUS_UPDATED)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Throwable) {
            }
            clipboard?.removePrimaryClipChangedListener(clipListener)
        }
    }

    fun saveSettings() {
        CodeStore.setRegex(context, regex.trim())
        CodeStore.setClipboardBridgeEnabled(context, clipboardBridgeEnabled)
        CodeStore.ensurePrefsReadable(context)
        refreshStatus(readClipboard = true)
        onToast("已保存")
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color(0xFFF6F7F9),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "验证码助手",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(
                    onClick = { showSettings = true },
                    modifier = Modifier
                        .size(44.dp)
                        .background(Color.White, RoundedCornerShape(22.dp)),
                ) {
                    Text(
                        text = "⋮",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Text(
                text = "短信侧提取验证码，当前输入法从临时保存区自动填入。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionCard(
                title = "环境状态",
                summary = "确认 LSPosed 作用域和短信接收权限。",
            ) {
                StatusRow(
                    label = "作用域状态",
                    value = "已配置",
                    onClick = null,
                )
                StatusRow(
                    label = "短信权限",
                    value = if (smsPermissionGranted) "已授权" else "未授权",
                    onClick = if (smsPermissionGranted) {
                        null
                    } else {
                        {
                            smsPermissionLauncher.launch(Manifest.permission.RECEIVE_SMS)
                        }
                    },
                )
            }

            SectionCard(
                title = "当前验证码",
                summary = "这里显示准备自动填入的验证码，填入后会清空，90 秒后自动失效。",
            ) {
                ClipboardCodeBlock(clipboardOtpState, nowMs)
                Spacer(Modifier.height(12.dp))
                RecentCodeBlock(
                    code = lastCode,
                    timestamp = lastCodeAt,
                    onClick = { showHistory = true },
                )
                MetricBlock("验证码来源", lastSource.ifBlank { "无" })
                MetricBlock("内容预览", lastSmsBody.ifBlank { "还没有收到验证码内容" })
            }

            if (showHistory) {
                CodeHistoryDialog(
                    history = codeHistory,
                    onDismiss = { showHistory = false },
                )
            }
            if (showSettings) {
                SettingsDialog(
                    regex = regex,
                    clipboardBridgeEnabled = clipboardBridgeEnabled,
                    onRegexChange = { regex = it },
                    onClipboardBridgeEnabledChange = { clipboardBridgeEnabled = it },
                    onDismiss = {
                        regex = CodeStore.getRegex(context)
                        clipboardBridgeEnabled = CodeStore.isClipboardBridgeEnabled(context)
                        showSettings = false
                    },
                    onSave = {
                        saveSettings()
                        showSettings = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    summary: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                content()
            },
        )
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    StatusRow(label = label, value = value, onClick = null)
}

@Composable
private fun StatusRow(label: String, value: String, onClick: (() -> Unit)?) {
    val positive = value == "已配置" || value == "已授权"
    var badgeModifier = Modifier.background(
        color = if (positive) Color(0xFFE8F5EC) else Color(0xFFFFF3E0),
        shape = RoundedCornerShape(999.dp),
    )
    if (onClick != null) {
        badgeModifier = badgeModifier.clickable(onClick = onClick)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
        )
        Box(
            modifier = badgeModifier
                .padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = if (positive) Color(0xFF166534) else Color(0xFFB45309),
            )
        }
    }
}

@Composable
private fun ClipboardCodeBlock(state: ClipboardOtpState, nowMs: Long) {
    val remainingMs = state.remainingMs(nowMs)
    val code = state.displayCode(nowMs)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFE8F5EC), RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "待填入验证码",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF166534),
                )
                Text(
                    text = code,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color(0xFF166534),
                    fontWeight = FontWeight.Bold,
                )
            }
            CountdownRing(
                remainingMs = remainingMs,
                totalMs = CLIP_TTL_MS,
            )
        }
    }
}

@Composable
private fun CountdownRing(
    remainingMs: Long,
    totalMs: Long,
) {
    val progress = (remainingMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
    val seconds = ceil(remainingMs / 1_000f).toInt().coerceAtLeast(0)
    Box(
        modifier = Modifier.size(46.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 4.dp.toPx()
            val inset = strokeWidth / 2f
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            drawCircle(
                color = Color(0xFFBBF7D0),
                radius = (size.minDimension - strokeWidth) / 2f,
                style = Stroke(width = strokeWidth),
            )
            drawArc(
                color = Color(0xFF16A34A),
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }
        Text(
            text = if (seconds > 0) seconds.toString() else "⌛",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF166534),
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun RecentCodeBlock(
    code: String,
    timestamp: Long,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFEFF6FF), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "最近验证码",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF1D4ED8),
            )
            Text(
                text = if (code.isBlank()) "无" else "$code · ${formatTime(timestamp)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(Color.White, RoundedCornerShape(17.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "›",
                style = MaterialTheme.typography.titleLarge,
                color = Color(0xFF2563EB),
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun MetricBlock(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF3F4F6), RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun CodeHistoryDialog(
    history: List<CodeStore.CodeHistoryItem>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("最近20条验证码") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (history.isEmpty()) {
                    Text("暂无记录")
                } else {
                    history.forEach { item ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFF3F4F6), RoundedCornerShape(18.dp))
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = item.code,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "${item.source.ifBlank { "未知来源" }} · ${formatTime(item.savedAtMs)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}

@Composable
private fun SettingsDialog(
    regex: String,
    clipboardBridgeEnabled: Boolean,
    onRegexChange: (String) -> Unit,
    onClipboardBridgeEnabledChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = regex,
                    onValueChange = onRegexChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("验证码匹配正则") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = clipboardBridgeEnabled,
                        onCheckedChange = onClipboardBridgeEnabledChange,
                    )
                    Text(
                        text = "启用自动填入",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
        confirmButton = {
            TextButton(onClick = onSave) {
                Text("保存")
            }
        },
    )
}

private fun formatTime(timestamp: Long): String {
    if (timestamp <= 0L) {
        return "无"
    }
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}

private fun hasSmsPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECEIVE_SMS,
    ) == PackageManager.PERMISSION_GRANTED
}

private fun openAppPermissionSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.fromParts("package", context.packageName, null))
    startActivitySafely(context, intent)
}

private fun startActivitySafely(context: Context, intent: Intent): Boolean {
    return try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: Throwable) {
        false
    }
}

private data class ClipboardOtpState(
    val code: String = "",
    val createdAtMs: Long = 0L,
) {
    fun remainingMs(nowMs: Long): Long {
        if (code.isBlank() || createdAtMs <= 0L) return 0L
        return (CLIP_TTL_MS - (nowMs - createdAtMs)).coerceIn(0L, CLIP_TTL_MS)
    }

    fun displayCode(nowMs: Long): String {
        return if (remainingMs(nowMs) > 0L) code else EMPTY_CODE_TEXT
    }
}

private fun readClipboardOtpState(context: Context): ClipboardOtpState {
    return try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return ClipboardOtpState()
        if (!clipboard.hasPrimaryClip()) return ClipboardOtpState()
        val createdAtMs = parseManagedClipCreatedAt(clipboard.primaryClipDescription?.label?.toString())
        if (createdAtMs <= 0L || System.currentTimeMillis() - createdAtMs > CLIP_TTL_MS) {
            return ClipboardOtpState()
        }
        val data: ClipData = clipboard.primaryClip ?: return ClipboardOtpState()
        if (data.itemCount == 0) return ClipboardOtpState()
        val text = data.getItemAt(0).coerceToText(context)?.toString().orEmpty()
        if (text.isBlank()) return ClipboardOtpState()
        val matcher = Pattern.compile("(?<!\\d)(\\d{4,8})(?!\\d)").matcher(text)
        val code = if (matcher.find()) matcher.group(1) ?: text else text
        ClipboardOtpState(code = code, createdAtMs = createdAtMs)
    } catch (_: Throwable) {
        ClipboardOtpState()
    }
}

private fun parseManagedClipCreatedAt(label: String?): Long {
    if (label.isNullOrBlank() || !label.startsWith(MANAGED_CLIP_LABEL_PREFIX)) {
        return 0L
    }
    return label.substring(MANAGED_CLIP_LABEL_PREFIX.length).toLongOrNull() ?: 0L
}
