package com.doubao.repkg

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.doubao.repkg.core.AssetKind
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Tab(val label: String) {
    Extract("提取"), History("历史"), Settings("设置")
}

class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = MaterialTheme.colorScheme) {
                AppRoot()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(vm: MainViewModel = viewModel()) {
    var tab by remember { mutableStateOf(Tab.Extract) }
    var viewingImage by remember { mutableStateOf<HistoryRecord?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Text(when (tab) {
                    Tab.Extract -> "壁纸视频/图片提取"
                    Tab.History -> "历史记录"
                    Tab.Settings -> "设置"
                })
            })
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == Tab.Extract,
                    onClick = { tab = Tab.Extract },
                    icon = { Icon(Icons.Filled.Movie, null) },
                    label = { Text("提取") }
                )
                NavigationBarItem(
                    selected = tab == Tab.History,
                    onClick = { tab = Tab.History; vm.refreshHistory() },
                    icon = { Icon(Icons.Filled.History, null) },
                    label = { Text("历史") }
                )
                NavigationBarItem(
                    selected = tab == Tab.Settings,
                    onClick = { tab = Tab.Settings },
                    icon = { Icon(Icons.Filled.Settings, null) },
                    label = { Text("设置") }
                )
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val context = LocalContext.current
            when (tab) {
                Tab.Extract -> ExtractScreen(vm)
                Tab.History -> HistoryScreen(
                    vm = vm,
                    onViewImage = { viewingImage = it },
                    onOpenVideo = { openVideoExternal(context, it) }
                )
                Tab.Settings -> SettingsScreen(vm)
            }
        }
    }

    viewingImage?.let { rec ->
        FullScreenImage(rec, onClose = { viewingImage = null })
    }

    // 冷启动自动检查更新（节流 / 开关 / 防重入都在 VM 里）
    LaunchedEffect(Unit) { vm.maybeCheckForUpdate() }

    val update by vm.updatePrompt.collectAsStateWithLifecycle()
    update?.let { info ->
        val context = LocalContext.current
        UpdateDialog(
            info = info,
            currentVersionName = appVersion(context),
            onUpdate = { vm.onUpdateNow(info) },
            onLater = { vm.onUpdateLater(info) },
            onIgnore = { vm.onUpdateIgnore(info) },
            onExit = { (context as? android.app.Activity)?.finish() }
        )
    }
}

// ================= Extract =================

@Composable
private fun ExtractScreen(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingUri by remember { mutableStateOf<Uri?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val needsPerm = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                    ContextCompat.checkSelfPermission(
                        context, Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) != PackageManager.PERMISSION_GRANTED
            if (needsPerm) pendingUri = uri else vm.process(uri)
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val uri = pendingUri
        if (granted && uri != null) vm.process(uri)
        pendingUri = null
    }

    LaunchedEffect(pendingUri) {
        if (pendingUri != null) {
            permLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp)
    ) {
        when (val s = state) {
            is UiState.Idle -> EmptyBody(null) { picker.launch(arrayOf("*/*")) }
            is UiState.Error -> EmptyBody(s.message) { picker.launch(arrayOf("*/*")) }
            is UiState.Working -> WorkingBody(s)
            is UiState.Ready -> ReadyBody(
                s, vm, onPick = { picker.launch(arrayOf("*/*")) }
            )
        }
    }
}

@Composable
private fun EmptyBody(error: String?, onPick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Filled.Movie, null, Modifier.size(72.dp), MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(20.dp))
        Text(
            "选择 Wallpaper Engine 的 .mpkg / .pkg 壁纸\n提取 MP4 视频、原图或预览图",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onPick) { Text("选择壁纸文件") }
        if (error != null) {
            Spacer(Modifier.height(24.dp))
            Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.errorContainer)) {
                Text(error, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "提示：电脑端 Wallpaper Engine 右键壁纸 →\n发送至移动设备 → 导出 .mpkg，再传到手机。",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun WorkingBody(s: UiState.Working) {
    Column(
        Modifier.fillMaxWidth().padding(top = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val total = s.total.coerceAtLeast(1)
        LinearProgressIndicator(
            progress = { s.current.toFloat() / total },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        Text("正在扫描 ${s.current.coerceAtMost(s.total)}/${s.total}",
            style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(s.message, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ReadyBody(
    s: UiState.Ready,
    vm: MainViewModel,
    onPick: () -> Unit
) {
    val context = LocalContext.current
    val busy by vm.busy.collectAsStateWithLifecycle()

    // One-shot export feedback.
    LaunchedEffect(Unit) {
        vm.events.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            if (s.isVideo) "动态壁纸" else "静态壁纸",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(12.dp))
                        val imgPath = s.cardImagePath?.takeIf { File(it).exists() }
                        val bmp = ImageLoader.rememberFileBitmap(imgPath, CARD_DECODE_EDGE)
                        if (bmp != null) {
                            Image(
                                bmp.asImageBitmap(), "预览",
                                Modifier.fillMaxWidth().heightIn(max = 320.dp),
                                contentScale = ContentScale.Fit
                            )
                        } else {
                            PreviewPlaceholder(s.isVideo)
                        }
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (s.hasMain) {
                        Button(
                            onClick = { vm.exportMain() },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                if (s.isVideo) Icons.Filled.Movie else Icons.Filled.Image,
                                null, Modifier.size(20.dp)
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(s.mainLabel)
                        }
                    }
                    if (s.hasPreview) {
                        OutlinedButton(
                            onClick = { vm.exportPreview() },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Image, null, Modifier.size(20.dp))
                            Spacer(Modifier.size(8.dp))
                            Text(s.previewLabel)
                        }
                    }
                }
            }
        }

        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
        }
        OutlinedButton(onClick = onPick, Modifier.fillMaxWidth()) {
            Text("选择其他壁纸")
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PreviewPlaceholder(isVideo: Boolean) {
    Box(
        Modifier.fillMaxWidth().height(200.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (isVideo) Icons.Filled.Movie else Icons.Filled.Image,
            null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline
        )
    }
}

// ================= History =================

@Composable
private fun HistoryScreen(
    vm: MainViewModel,
    onViewImage: (HistoryRecord) -> Unit,
    onOpenVideo: (HistoryRecord) -> Unit
) {
    val history by vm.history.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (history.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.History, null, Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(12.dp))
                    Text("还没有历史记录", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text("成功导出的视频和图片会显示在这里",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("共 ${history.size} 条记录", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium)
                TextButtonText("清空", onClick = { vm.clearHistory() })
            }
            HorizontalDivider()
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(history, key = { it.id }) { rec ->
                    HistoryRow(rec,
                        onClick = {
                            if (rec.kind == AssetKind.IMAGE) onViewImage(rec) else onOpenVideo(rec)
                        },
                        onDelete = { vm.deleteRecord(rec.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(rec: HistoryRecord, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).background(
                MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center) {
                val thumbPath = rec.thumbPath?.takeIf { File(it).exists() }
                val bmp = ImageLoader.rememberFileBitmap(thumbPath, THUMB_DECODE_EDGE)
                when {
                    bmp != null -> {
                        Image(bmp.asImageBitmap(), null, Modifier.size(56.dp),
                            contentScale = ContentScale.Crop)
                        if (rec.kind == AssetKind.VIDEO) {
                            // Play badge on top of the cover frame.
                            // NOTE: all args named -- passing `modifier` both positionally
                            // and by name is a duplicate-argument compile error.
                            Icon(
                                imageVector = Icons.Filled.PlayCircle,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                                    .align(Alignment.BottomEnd)
                                    .padding(2.dp),
                                tint = Color.White
                            )
                        }
                    }
                    rec.kind == AssetKind.IMAGE ->
                        Icon(Icons.Filled.Image, null, Modifier.size(28.dp))
                    else ->
                        Icon(Icons.Filled.PlayCircle, null, Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(rec.fileName, style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, maxLines = 1)
                Spacer(Modifier.height(2.dp))
                Text(formatTime(rec.timeMs), style = MaterialTheme.typography.bodySmall)
                Text(
                    (if (rec.kind == AssetKind.IMAGE) "图片" else "视频") +
                            " · ${formatSize(rec.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, "删除", Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun FullScreenImage(rec: HistoryRecord, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val context = LocalContext.current
        val bmp = ImageLoader.rememberUriBitmap(context, rec.uri, FULLSCREEN_DECODE_EDGE)
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (bmp != null) {
                Image(bmp.asImageBitmap(), rec.fileName,
                    Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit)
            } else {
                Text("无法加载图片", color = Color.White,
                    modifier = Modifier.align(Alignment.Center))
            }
            IconButton(onClick = onClose,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Icon(Icons.Filled.Close, "关闭", tint = Color.White)
            }
        }
    }
}

private fun openVideoExternal(context: android.content.Context, rec: HistoryRecord) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(rec.uri, "video/mp4")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val chooser = Intent.createChooser(intent, "用哪个应用打开视频").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
}

// ================= Settings =================

@Composable
private fun SettingsScreen(vm: MainViewModel) {
    val context = LocalContext.current
    var refreshTick by remember { mutableStateOf(0) }
    var autoCheck by remember { mutableStateOf(UpdateSettings.autoCheckEnabled(context)) }
    val checking by vm.checkingUpdate.collectAsStateWithLifecycle()
    val lastCheckMs by vm.lastCheckMs.collectAsStateWithLifecycle()

    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                val perms = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, perms)
                ExportSettings.setTreeUri(context, uri)
                refreshTick++
            } catch (e: SecurityException) {
                ExportSettings.setTreeUri(context, uri)
                refreshTick++
            }
        }
    }

    LaunchedEffect(refreshTick) { /* trigger recomposition */ }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Folder, null, Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("导出文件夹", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(10.dp))
                val treeUri = ExportSettings.getTreeUri(context)
                if (treeUri == null) {
                    Text("当前：默认目录", style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium)
                    Text("视频 →  Movies/Mpkg2Mp4\n图片 →  Pictures/Mpkg2Mp4",
                        style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("当前：自定义文件夹", style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                    Text(treeUri.toString(), style = MaterialTheme.typography.bodySmall, maxLines = 3)
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = { treeLauncher.launch(null) }, Modifier.fillMaxWidth()) {
                    Text(if (treeUri == null) "选择导出文件夹" else "更改导出文件夹")
                }
                if (treeUri != null) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        ExportSettings.clear(context); refreshTick++
                    }, Modifier.fillMaxWidth()) {
                        Text("恢复默认目录")
                    }
                }
            }
        }

        // ---- 检查更新 ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.SystemUpdate, null, Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("检查更新", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(6.dp))
                Text("点击检查最新版本", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "当前版本 v${appVersion(context)}　·　" +
                            if (lastCheckMs > 0) "上次检查 ${formatTime(lastCheckMs)}" else "尚未检查过",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { vm.checkForUpdateNow() },
                    enabled = !checking,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (checking) {
                        CircularProgressIndicator(
                            Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("正在检查…")
                    } else {
                        Text("检查更新")
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("自动检查更新", style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium)
                        Text("启动时检查，每 12 小时最多一次", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = autoCheck,
                        onCheckedChange = {
                            autoCheck = it
                            UpdateSettings.setAutoCheckEnabled(context, it)
                        }
                    )
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("历史记录", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text("清空所有导出历史（不会删除已导出的文件）。",
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { vm.clearHistory() }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Delete, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("清空历史记录")
                }
            }
        }

        // ---- 关于 ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Info, null, Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("关于", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(6.dp))
                Text("Mpkg2Mp4  v${appVersion(context)}", style = MaterialTheme.typography.bodySmall)
                Text("本地解析 Wallpaper Engine 壁纸，提取 MP4、原图与预览图，文件不上传。",
                    style = MaterialTheme.typography.bodySmall)

                Spacer(Modifier.height(10.dp))
                Text("开源地址", style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium)
                Text(
                    UpdateConfig.REPO_URL,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        val ok = try {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(UpdateConfig.REPO_URL))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                            true
                        } catch (e: Exception) {
                            false
                        }
                        if (!ok) Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }
}

// ================= Utils =================

/** Decode targets: never decode more pixels than the UI can actually show. */
private const val THUMB_DECODE_EDGE = 192
private const val CARD_DECODE_EDGE = 1080
private const val FULLSCREEN_DECODE_EDGE = 2048

@Composable
private fun TextButtonText(text: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) { Text(text) }
}

/** 供设置页与更新弹窗共用。 */
internal fun formatSize(bytes: Long): String {
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 1) String.format(Locale.US, "%.1f MB", mb)
    else "${bytes / 1024} KB"
}

@Suppress("DEPRECATION")
private fun appVersion(context: android.content.Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
        .getOrNull() ?: "1.6.0"

private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
private fun formatTime(ms: Long): String = timeFmt.format(Date(ms))
