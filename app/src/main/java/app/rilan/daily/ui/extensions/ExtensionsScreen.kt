@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package app.rilan.daily.ui.extensions

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rilan.daily.core.Category
import app.rilan.daily.mcp.McpServerState
import app.rilan.daily.mcp.McpTestState
import app.rilan.daily.plugins.SourcePlugin
import app.rilan.daily.ui.components.ErrorLine
import app.rilan.daily.ui.components.GlassCard
import app.rilan.daily.ui.components.GlassDivider
import app.rilan.daily.ui.components.GlassIconButton
import app.rilan.daily.ui.components.GlassPill
import app.rilan.daily.ui.components.ScreenTitle
import app.rilan.daily.ui.components.SectionLabel
import app.rilan.daily.ui.components.TextAction
import app.rilan.daily.ui.theme.RilanColors
import kotlinx.coroutines.delay

private data class PluginEditorRequest(
    val originalId: String?,
    val title: String,
    val initialText: String,
    val initialError: String? = null,
)

@Composable
fun ExtensionsScreen(
    mcpState: McpServerState,
    mcpTest: McpTestState,
    plugins: List<SourcePlugin>,
    pluginErrors: List<String>,
    pluginDirectory: String,
    onStartMcp: () -> Unit,
    onStopMcp: () -> Unit,
    onTestMcp: () -> Unit,
    onSavePlugin: (rawJson: String, originalId: String?) -> String?,
    onDeletePlugin: (String) -> Unit,
    onTogglePlugin: (String, Boolean) -> Unit,
    weiboHotEnabled: Boolean,
    bilibiliHotEnabled: Boolean,
    douyinHotEnabled: Boolean,
    onToggleHot: (Category, Boolean) -> Unit,
    onReloadPlugins: () -> Unit,
    onRefreshContent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showToken by remember { mutableStateOf(false) }
    var showMcpDetails by rememberSaveable { mutableStateOf(false) }
    var editor by remember { mutableStateOf<PluginEditorRequest?>(null) }
    var pendingDelete by remember { mutableStateOf<SourcePlugin?>(null) }

    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(mcpState.running, mcpState.startedAt) {
        if (!mcpState.running || mcpState.startedAt <= 0L) return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val uptimeMs = if (mcpState.running && mcpState.startedAt > 0L) {
        (now - mcpState.startedAt).coerceAtLeast(0L)
    } else 0L

    fun openEditor(plugin: SourcePlugin?) {
        editor = if (plugin == null) {
            PluginEditorRequest(null, "添加来源", SourcePlugin.TEMPLATE_RSS)
        } else {
            PluginEditorRequest(plugin.id, "编辑 ${plugin.name}", plugin.toJson())
        }
    }

    fun importFromClipboard() {
        val raw = readClipboard(context)
        if (raw.isBlank()) {
            Toast.makeText(context, "剪贴板为空", Toast.LENGTH_SHORT).show()
            return
        }
        val failure = onSavePlugin(raw, null)
        if (failure == null) {
            Toast.makeText(context, "已从剪贴板导入", Toast.LENGTH_SHORT).show()
        } else {
            editor = PluginEditorRequest(null, "添加来源", raw, failure)
            Toast.makeText(context, "导入失败：$failure", Toast.LENGTH_LONG).show()
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ScreenTitle(
                eyebrow = "扩展",
                title = "信息与连接",
                subtitle = "来源可以增删，DeepSeek 直连与 MCP 各走一条路",
                actions = {
                    GlassIconButton(
                        icon = Icons.Filled.Add,
                        contentDescription = "添加来源",
                        onClick = { openEditor(null) },
                        tint = RilanColors.Mint,
                    )
                },
            )
        }

        // ── 来源 ─────────────────────────────────────────────────────────────
        item {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                SectionLabel("来源", RilanColors.Blue) {
                    GlassPill(
                        text = "${plugins.size} 个",
                        color = RilanColors.Blue,
                        background = RilanColors.Blue.copy(alpha = 0.09f),
                    )
                }
                Text(
                    "每个来源一份 JSON 插件。RSS / Atom 直接填地址；JSON 接口用字段映射。热榜默认过滤明星娱乐。",
                    style = MaterialTheme.typography.bodySmall,
                    color = RilanColors.TextTertiary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("从剪贴板导入", onClick = { importFromClipboard() })
                    GhostButton("刷新内容", onClick = onRefreshContent, icon = Icons.Filled.Refresh)
                }
            }
        }

        item {
            if (plugins.isEmpty()) {
                EmptySourcesCard(onAdd = { openEditor(null) })
            } else {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        plugins.forEachIndexed { index, plugin ->
                            SourceRow(
                                plugin = plugin,
                                onToggle = { enabled -> onTogglePlugin(plugin.id, enabled) },
                                onEdit = { openEditor(plugin) },
                                onDelete = { pendingDelete = plugin },
                            )
                            if (index != plugins.lastIndex) {
                                GlassDivider(Modifier.padding(start = 16.dp, end = 16.dp))
                            }
                        }
                    }
                }
            }
        }

        if (pluginErrors.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "有 ${pluginErrors.size} 个插件没能加载",
                        style = MaterialTheme.typography.labelMedium,
                        color = RilanColors.Coral,
                    )
                    pluginErrors.take(4).forEach { message ->
                        Text(
                            "· $message",
                            style = MaterialTheme.typography.labelSmall,
                            color = RilanColors.Coral.copy(alpha = 0.82f),
                        )
                    }
                    if (pluginErrors.size > 4) {
                        Text(
                            "…还有 ${pluginErrors.size - 4} 条，修好后点刷新",
                            style = MaterialTheme.typography.labelSmall,
                            color = RilanColors.TextTertiary,
                        )
                    }
                }
            }
        }

        item {
            BuiltinHotCard(
                weibo = weiboHotEnabled,
                bilibili = bilibiliHotEnabled,
                douyin = douyinHotEnabled,
                onToggle = onToggleHot,
            )
        }

        item {
            DirectoryRow(
                directory = pluginDirectory,
                onCopy = { copyText(context, pluginDirectory, "插件目录已复制") },
                onReload = onReloadPlugins,
            )
        }

        // ── MCP ──────────────────────────────────────────────────────────────
        item {
            SectionLabel("MCP 服务", RilanColors.Gold) {
                GlassPill(
                    text = if (mcpState.running) "运行中" else "已停止",
                    color = if (mcpState.running) RilanColors.Mint else RilanColors.TextTertiary,
                    background = (if (mcpState.running) RilanColors.Mint else Color.White).copy(alpha = 0.09f),
                )
            }
        }

        item {
            McpCardCompact(
                state = mcpState,
                test = mcpTest,
                uptimeMs = uptimeMs,
                showToken = showToken,
                showDetails = showMcpDetails,
                onToggleToken = { showToken = !showToken },
                onToggleDetails = { showMcpDetails = !showMcpDetails },
                onCopyEndpoint = { copyText(context, mcpState.endpoint, "端点已复制") },
                onCopyToken = { copyText(context, mcpState.token, "令牌已复制") },
                onCopyDsh = {
                    if (mcpState.token.isBlank()) {
                        Toast.makeText(context, "服务还没准备好，先启动一次", Toast.LENGTH_SHORT).show()
                    } else {
                        copyText(context, mcpConfigDsh(mcpState), "DSH 配置已复制")
                    }
                },
                onCopyJson = {
                    if (mcpState.token.isBlank()) {
                        Toast.makeText(context, "服务还没准备好，先启动一次", Toast.LENGTH_SHORT).show()
                    } else {
                        copyText(context, mcpConfigJson(mcpState), "通用 MCP 配置已复制")
                    }
                },
                onStart = onStartMcp,
                onStop = onStopMcp,
                onTest = onTestMcp,
            )
        }

        item {
            Text(
                "MCP 只监听 127.0.0.1，需要 Bearer token；App 在后台时由前台服务保活。插件文件在应用私有目录，卸载会一起删除。",
                style = MaterialTheme.typography.labelSmall,
                color = RilanColors.TextTertiary,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
    }

    editor?.let { request ->
        PluginEditorSheet(
            request = request,
            onDismiss = { editor = null },
            onSave = { raw ->
                val failure = onSavePlugin(raw, request.originalId)
                if (failure == null) {
                    editor = null
                    Toast.makeText(context, "插件已保存", Toast.LENGTH_SHORT).show()
                }
                failure
            },
        )
    }

    pendingDelete?.let { plugin ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = RilanColors.SurfaceRaised,
            titleContentColor = RilanColors.Text,
            textContentColor = RilanColors.TextSecondary,
            title = { Text("删除来源", style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    "确定删除「${plugin.name}」吗？对应的 ${plugin.id}.json 会一起删掉。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeletePlugin(plugin.id)
                        pendingDelete = null
                    },
                ) { Text("删除", color = RilanColors.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("取消", color = RilanColors.TextTertiary)
                }
            },
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 来源列表
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SourceRow(
    plugin: SourcePlugin,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val accent = Color(plugin.category.accent)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(start = 16.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(28.dp)
                .clip(CircleShape)
                .background(if (plugin.enabled) accent else accent.copy(alpha = 0.25f))
        )
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                plugin.name,
                style = MaterialTheme.typography.titleSmall,
                color = if (plugin.enabled) RilanColors.Text else RilanColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(
                    plugin.category.label,
                    plugin.format.label,
                    if (plugin.builtIn) "内置" else "插件",
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = RilanColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Filled.Edit,
            contentDescription = "编辑",
            tint = RilanColors.TextTertiary.copy(alpha = 0.7f),
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .clickable(onClick = onEdit)
                .padding(9.dp),
        )
        Icon(
            Icons.Filled.Delete,
            contentDescription = "删除",
            tint = RilanColors.Coral.copy(alpha = 0.72f),
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .clickable(onClick = onDelete)
                .padding(9.dp),
        )
        Switch(
            checked = plugin.enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = RilanColors.Ink,
                checkedTrackColor = RilanColors.Mint,
                uncheckedThumbColor = RilanColors.TextTertiary,
                uncheckedTrackColor = Color(0x1AFFFFFF),
                uncheckedBorderColor = Color(0x2AFFFFFF),
            ),
        )
    }
}

@Composable
private fun EmptySourcesCard(onAdd: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("还没有来源", style = MaterialTheme.typography.titleSmall, color = RilanColors.TextSecondary)
            Text(
                "添加一个 RSS 或 JSON 接口，日报就会开始收录。",
                style = MaterialTheme.typography.bodySmall,
                color = RilanColors.TextTertiary,
            )
            MintButton("添加来源", onClick = onAdd, icon = Icons.Filled.Add)
        }
    }
}

@Composable
private fun BuiltinHotCard(
    weibo: Boolean,
    bilibili: Boolean,
    douyin: Boolean,
    onToggle: (Category, Boolean) -> Unit,
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "内置热榜",
                    style = MaterialTheme.typography.titleSmall,
                    color = RilanColors.Text,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "直接抓取，不走插件",
                    style = MaterialTheme.typography.labelSmall,
                    color = RilanColors.TextTertiary,
                )
            }
            HotSwitchRow("微博热搜", "此刻大家在讨论", RilanColors.Coral, weibo) {
                onToggle(Category.WEIBO, it)
            }
            HotSwitchRow("B站热门", "年轻人的兴趣现场", Color(0xFFFFB3D1), bilibili) {
                onToggle(Category.BILIBILI, it)
            }
            HotSwitchRow("抖音热搜", "短视频里的风向", Color(0xFF8BE0FF), douyin) {
                onToggle(Category.DOUYIN, it)
            }
        }
    }
}

@Composable
private fun HotSwitchRow(
    name: String,
    subtitle: String,
    color: Color,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(if (checked) color else color.copy(alpha = 0.28f)))
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.titleSmall,
                color = if (checked) RilanColors.Text else RilanColors.TextTertiary,
            )
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = RilanColors.TextTertiary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = RilanColors.Ink,
                checkedTrackColor = RilanColors.Mint,
                uncheckedThumbColor = RilanColors.TextTertiary,
                uncheckedTrackColor = Color(0x1AFFFFFF),
                uncheckedBorderColor = Color(0x2AFFFFFF),
            ),
        )
    }
}

@Composable
private fun DirectoryRow(
    directory: String,
    onCopy: () -> Unit,
    onReload: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x08FFFFFF))
            .border(0.75.dp, Color(0x14FFFFFF), RoundedCornerShape(14.dp))
            .padding(horizontal = 13.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("插件目录", style = MaterialTheme.typography.labelSmall, color = RilanColors.TextTertiary)
            Text(
                directory.ifBlank { "（目录不可用）" },
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                ),
                color = RilanColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextAction("复制", onCopy, color = RilanColors.TextSecondary)
        Spacer(Modifier.width(6.dp))
        TextAction("重新加载", onReload, color = RilanColors.Blue)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MCP：默认只露状态和主操作，细节按需展开
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun McpCardCompact(
    state: McpServerState,
    test: McpTestState,
    uptimeMs: Long,
    showToken: Boolean,
    showDetails: Boolean,
    onToggleToken: () -> Unit,
    onToggleDetails: () -> Unit,
    onCopyEndpoint: () -> Unit,
    onCopyToken: () -> Unit,
    onCopyDsh: () -> Unit,
    onCopyJson: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onTest: () -> Unit,
) {
    GlassCard(Modifier.fillMaxWidth(), strong = true, shape = RoundedCornerShape(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(running = state.running)
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text("MCP 服务", style = MaterialTheme.typography.titleMedium, color = RilanColors.Text)
                    Text(
                        if (state.running) {
                            "已在本机开放 · 请求 ${state.requestCount} 次"
                        } else {
                            "未启动 · 不会开放端口"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.running) RilanColors.Mint.copy(alpha = 0.85f)
                        else RilanColors.TextTertiary,
                    )
                }
                if (state.running) {
                    Text(
                        formatUptime(uptimeMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = RilanColors.Mint.copy(alpha = 0.8f),
                    )
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x07FFFFFF))
                    .padding(horizontal = 11.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    state.endpoint,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = RilanColors.Mint.copy(alpha = 0.9f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextAction("复制", onCopyEndpoint)
            }

            Text(
                if (state.running) {
                    "本机自检通过后，把 DSH 配置粘进 MCP 列表并在 DSH 里重载即可。"
                } else {
                    "点一次「启动 MCP」或「检测连接」，服务会在前台通知里常驻。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = RilanColors.TextTertiary,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MintButton(
                    text = if (state.running) "停止服务" else "启动 MCP",
                    onClick = if (state.running) onStop else onStart,
                    modifier = Modifier.weight(1f),
                    background = if (state.running) RilanColors.Coral else RilanColors.Mint,
                )
                GhostButton(
                    text = if (test.loading) "检测中…" else "检测连接",
                    onClick = { if (!test.loading) onTest() },
                    modifier = Modifier.weight(1f),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MintButton(
                    text = "复制 DSH 配置",
                    onClick = onCopyDsh,
                    modifier = Modifier.weight(1f),
                    background = RilanColors.Moon,
                )
                GhostButton(
                    text = "通用 JSON",
                    onClick = onCopyJson,
                    modifier = Modifier.weight(1f),
                )
            }

            when {
                test.loading -> Text(
                    "正在走本机回环检测：initialize → tools/list…",
                    style = MaterialTheme.typography.labelSmall,
                    color = RilanColors.TextTertiary,
                )
                test.message != null -> Text(
                    (if (test.ok) "✓ " else "× ") + test.message,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (test.ok) RilanColors.Mint else RilanColors.Coral,
                )
            }

            if (state.lastError != null) ErrorLine("服务错误：${state.lastError}")

            TextAction(
                if (showDetails) "收起连接说明与令牌" else "连接说明与令牌",
                onToggleDetails,
                color = RilanColors.TextSecondary,
            )

            AnimatedVisibility(
                visible = showDetails,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    GlassDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (showToken) state.token.ifBlank { "启动后生成" } else maskToken(state.token),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                            ),
                            color = RilanColors.TextSecondary,
                            maxLines = if (showToken) 3 else 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextAction(if (showToken) "隐藏" else "显示", onToggleToken, color = RilanColors.TextSecondary)
                        Spacer(Modifier.width(8.dp))
                        TextAction("复制令牌", onCopyToken)
                    }
                    Text(
                        "协议 2025-06-18 / 2025-03-26；只监听 127.0.0.1，除 OPTIONS 外都要 Bearer token。App 在后台时由前台服务保活。",
                        style = MaterialTheme.typography.labelSmall,
                        color = RilanColors.TextTertiary,
                    )
                    if (state.lastRequestAt > 0L) {
                        Text(
                            "最近一次请求：${formatClock(state.lastRequestAt)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = RilanColors.TextTertiary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusDot(running: Boolean) {
    val transition = rememberInfiniteTransition(label = "mcpDot")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_700, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "mcpPulse",
    )
    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
        if (running) {
            Box(
                Modifier
                    .size(8.dp + 10.dp * pulse)
                    .clip(CircleShape)
                    .background(RilanColors.Mint.copy(alpha = (1f - pulse) * 0.28f))
            )
        }
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (running) RilanColors.Mint else RilanColors.TextTertiary.copy(alpha = 0.55f))
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 插件编辑器
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PluginEditorSheet(
    request: PluginEditorRequest,
    onDismiss: () -> Unit,
    onSave: (String) -> String?,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember(request) { mutableStateOf(request.initialText) }
    var formError by remember(request) { mutableStateOf(request.initialError) }
    var showHelp by remember { mutableStateOf(false) }
    var template by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = RilanColors.Surface,
        contentColor = RilanColors.Text,
        scrimColor = Color(0xB3060812),
        windowInsets = WindowInsets(0, 0, 0, 0),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 2.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(Color(0x35FFFFFF))
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 6.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "来源插件",
                        style = MaterialTheme.typography.headlineSmall,
                        color = RilanColors.Text,
                    )
                    Text(
                        "一份 JSON 对应一个来源",
                        style = MaterialTheme.typography.labelSmall,
                        color = RilanColors.TextTertiary,
                    )
                }
                GlassIconButton(
                    icon = Icons.Filled.Close,
                    contentDescription = "关闭",
                    onClick = onDismiss,
                    size = 34.dp,
                )
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TemplateChip("RSS / Atom", template == "rss", RilanColors.Blue) {
                    text = SourcePlugin.TEMPLATE_RSS
                    template = "rss"
                    formError = null
                }
                TemplateChip("JSON 接口", template == "json", RilanColors.Gold) {
                    text = SourcePlugin.TEMPLATE_JSON
                    template = "json"
                    formError = null
                }
            }

            BasicTextField(
                value = text,
                onValueChange = { value ->
                    text = value
                    template = null
                    formError = null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 300.dp, max = 360.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0x0DFFFFFF))
                    .border(
                        width = 0.75.dp,
                        color = if (formError != null) RilanColors.Coral.copy(alpha = 0.6f) else Color(0x24FFFFFF),
                        shape = RoundedCornerShape(14.dp),
                    )
                    .verticalScroll(rememberScrollState()),
                textStyle = TextStyle(
                    color = RilanColors.Text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                ),
                cursorBrush = SolidColor(RilanColors.Mint),
                decorationBox = { innerTextField ->
                    Box(Modifier.fillMaxWidth().padding(13.dp)) {
                        if (text.isEmpty()) {
                            Text(
                                "在这里写完整 JSON，或点上面的模板。",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = RilanColors.TextTertiary,
                            )
                        }
                        innerTextField()
                    }
                },
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextAction(
                    if (showHelp) "收起格式说明" else "查看格式说明",
                    { showHelp = !showHelp },
                    color = RilanColors.Blue,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "id 必须与文件名一致",
                    style = MaterialTheme.typography.labelSmall,
                    color = RilanColors.TextTertiary,
                )
            }

            AnimatedVisibility(
                visible = showHelp,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xD90A0D18))
                        .border(0.75.dp, Color(0x1AFFFFFF), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                ) {
                    Text(
                        SourcePlugin.FORMAT_HELP,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            lineHeight = 15.sp,
                        ),
                        color = RilanColors.TextSecondary,
                    )
                }
            }

            if (formError != null) {
                Text("× $formError", style = MaterialTheme.typography.bodySmall, color = RilanColors.Coral)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                GhostButton("取消", onClick = onDismiss, modifier = Modifier.weight(0.42f))
                MintButton(
                    text = "保存",
                    onClick = {
                        val raw = text.trim()
                        if (raw.isEmpty()) {
                            formError = "先写一段 JSON，或点上面的模板"
                        } else {
                            val failure = onSave(raw)
                            if (failure != null) formError = failure
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TemplateChip(
    label: String,
    selected: Boolean,
    color: Color,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) color.copy(alpha = 0.18f) else Color(0x0AFFFFFF))
            .border(0.75.dp, if (selected) color.copy(alpha = 0.6f) else Color(0x20FFFFFF), RoundedCornerShape(11.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) color else RilanColors.TextSecondary)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 按钮与工具
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun MintButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    background: Color = RilanColors.Mint,
    contentColor: Color = RilanColors.Ink,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(13.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(13.dp))
            .background(Color(0x0EFFFFFF))
            .border(0.75.dp, Color(0x22FFFFFF), RoundedCornerShape(13.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = RilanColors.TextSecondary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = RilanColors.TextSecondary)
    }
}

private fun mcpConfigJson(state: McpServerState): String =
    "{\"mcpServers\":{\"rilan\":{\"transport\":\"streamable-http\",\"url\":\"${state.endpoint}\"," +
        "\"headers\":{\"Authorization\":\"Bearer ${state.token}\"}}}}"

/** 直接贴进 DeepSeek Harness 的 MCP 列表；比通用 JSON 少一步改字段。 */
private fun mcpConfigDsh(state: McpServerState): String = buildString {
    appendLine("- id: mcp-rilan")
    appendLine("  name: '@deepseek-ai/dsh-mcp-client'")
    appendLine("  config:")
    appendLine("    serverName: rilan")
    appendLine("    transport: streamable-http")
    appendLine("    url: ${state.endpoint}")
    appendLine("    headers:")
    appendLine("      Authorization: 'Bearer ${state.token}'")
}

private fun formatClock(epochMillis: Long): String = runCatching {
    java.time.Instant.ofEpochMilli(epochMillis)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
}.getOrDefault("刚刚")

private fun maskToken(token: String): String =
    if (token.length <= 8) "••••••••" else "${token.take(4)}••••••••${token.takeLast(4)}"

private fun formatUptime(millis: Long): String {
    val seconds = (millis.coerceAtLeast(0L) / 1_000L).toInt()
    val hours = seconds / 3_600
    val minutes = (seconds % 3_600) / 60
    val rest = seconds % 60
    return when {
        hours > 0 -> "${hours} 小时 ${minutes} 分"
        minutes > 0 -> "${minutes} 分 ${rest} 秒"
        else -> "${rest} 秒"
    }
}

private fun copyText(context: Context, text: String, toast: String) {
    if (text.isBlank()) {
        Toast.makeText(context, "没有可复制的内容", Toast.LENGTH_SHORT).show()
        return
    }
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    if (manager == null) {
        Toast.makeText(context, "这台手机不支持剪贴板", Toast.LENGTH_SHORT).show()
        return
    }
    val ok = runCatching {
        manager.setPrimaryClip(ClipData.newPlainText("日览", text))
    }.isSuccess
    Toast.makeText(context, if (ok) toast else "复制失败", Toast.LENGTH_SHORT).show()
}

private fun readClipboard(context: Context): String {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return ""
    return runCatching {
        val clip = manager.primaryClip ?: return@runCatching ""
        if (clip.itemCount <= 0) return@runCatching ""
        clip.getItemAt(0).coerceToText(context)?.toString().orEmpty().trim()
    }.getOrDefault("")
}
