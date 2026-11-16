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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rilan.daily.core.AiTestState
import app.rilan.daily.core.AppSettings
import app.rilan.daily.core.Category
import app.rilan.daily.core.DEEPSEEK_MODEL_IDS
import app.rilan.daily.mcp.McpServerState
import app.rilan.daily.mcp.McpTestState
import app.rilan.daily.plugins.SourcePlugin
import app.rilan.daily.schedule.DailyBriefNotifier
import app.rilan.daily.ui.components.ErrorLine
import app.rilan.daily.ui.components.GlassCard
import app.rilan.daily.ui.components.GlassDivider
import app.rilan.daily.ui.components.GlassIconButton
import app.rilan.daily.ui.components.LoadingDots
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

/**
 * 扩展页的四个分段。
 *
 * 早先所有内容纵向堆在一页里，一进来就看到来源、热榜、MCP、令牌、目录，
 * 信息量太大。现在按「用的时候才看」拆开：来源、服务、AI、更多各一格，
 * 每组默认只露一行摘要，点开才展开细节。
 */
enum class ExtensionsSection(val label: String) {
    SOURCES("来源"),
    SERVICE("服务"),
    AI("AI"),
    MORE("更多"),
}

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
    settings: AppSettings = AppSettings(),
    aiTest: AiTestState = AiTestState(),
    onSaveSettings: ((AppSettings) -> Unit)? = null,
    onTestDeepSeek: ((String, String) -> Unit)? = null,
    initialSection: ExtensionsSection = ExtensionsSection.SOURCES,
    notificationsGranted: Boolean = false,
    onRequestNotifications: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var sectionName by rememberSaveable { mutableStateOf(initialSection.name) }
    val section = ExtensionsSection.entries.firstOrNull { it.name == sectionName }
        ?: ExtensionsSection.SOURCES

    var editor by remember { mutableStateOf<PluginEditorRequest?>(null) }
    var pendingDelete by remember { mutableStateOf<SourcePlugin?>(null) }

    // 首页「打开 AI 设置」这类外部入口可以要求首屏落在哪一段。
    // 只在外部值真的变化时才切，避免旋转屏幕把用户已选的分段重置回默认。
    val appliedInitial = remember { mutableStateOf(initialSection.name) }
    LaunchedEffect(initialSection.name) {
        if (appliedInitial.value != initialSection.name) {
            appliedInitial.value = initialSection.name
            sectionName = initialSection.name
        }
    }

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
                subtitle = "来源、MCP 与 DeepSeek 各占一格，点开再动细节",
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

        item {
            SectionTabs(current = section, onSelect = { sectionName = it.name })
        }

        item {
            when (section) {
                ExtensionsSection.SOURCES -> SourcesTab(
                    plugins = plugins,
                    pluginErrors = pluginErrors,
                    weibo = weiboHotEnabled,
                    bilibili = bilibiliHotEnabled,
                    douyin = douyinHotEnabled,
                    onToggleHot = onToggleHot,
                    onAdd = { openEditor(null) },
                    onImport = { importFromClipboard() },
                    onRefresh = onRefreshContent,
                    onEdit = { plugin -> openEditor(plugin) },
                    onDelete = { plugin -> pendingDelete = plugin },
                    onTogglePlugin = onTogglePlugin,
                )

                ExtensionsSection.SERVICE -> ServiceTab(
                    state = mcpState,
                    test = mcpTest,
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

                ExtensionsSection.AI -> AiTab(
                    settings = settings,
                    aiTest = aiTest,
                    onSaveSettings = onSaveSettings,
                    onTestDeepSeek = onTestDeepSeek,
                    onOpenService = { sectionName = ExtensionsSection.SERVICE.name },
                )

                ExtensionsSection.MORE -> MoreTab(
                    directory = pluginDirectory,
                    onCopyDirectory = { copyText(context, pluginDirectory, "插件目录已复制") },
                    onReloadPlugins = onReloadPlugins,
                    settings = settings,
                    onSaveSettings = onSaveSettings,
                    notificationsGranted = notificationsGranted,
                    onRequestNotifications = onRequestNotifications,
                )
            }
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
// 顶部：分段标签
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SectionTabs(
    current: ExtensionsSection,
    onSelect: (ExtensionsSection) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x0AFFFFFF))
            .border(0.75.dp, Color(0x1AFFFFFF), RoundedCornerShape(16.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ExtensionsSection.entries.forEach { item ->
            val selected = item == current
            val accent = sectionAccent(item)
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) accent.copy(alpha = 0.15f) else Color.Transparent)
                    .border(
                        0.75.dp,
                        if (selected) accent.copy(alpha = 0.36f) else Color.Transparent,
                        RoundedCornerShape(12.dp),
                    )
                    .clickable { onSelect(item) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    item.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) accent else RilanColors.TextTertiary,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

private fun sectionAccent(section: ExtensionsSection): Color = when (section) {
    ExtensionsSection.SOURCES -> RilanColors.Blue
    ExtensionsSection.SERVICE -> RilanColors.Gold
    ExtensionsSection.AI -> RilanColors.Dawn
    ExtensionsSection.MORE -> RilanColors.Moon
}

/** 分组摘要行：默认只露这一行，点一下展开细节。 */
@Composable
private fun GroupHeader(
    title: String,
    summary: String,
    accent: Color,
    expanded: Boolean,
    onToggle: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
        } else {
            Box(Modifier.size(6.dp).clip(CircleShape).background(accent.copy(alpha = 0.92f)))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = RilanColors.Text)
            Text(
                summary,
                style = MaterialTheme.typography.labelSmall,
                color = accent.copy(alpha = 0.78f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing?.invoke()
        Text(
            if (expanded) "▴" else "▾",
            style = MaterialTheme.typography.labelLarge,
            color = RilanColors.TextTertiary,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 来源：插件列表 + 内置热榜
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SourcesTab(
    plugins: List<SourcePlugin>,
    pluginErrors: List<String>,
    weibo: Boolean,
    bilibili: Boolean,
    douyin: Boolean,
    onToggleHot: (Category, Boolean) -> Unit,
    onAdd: () -> Unit,
    onImport: () -> Unit,
    onRefresh: () -> Unit,
    onEdit: (SourcePlugin) -> Unit,
    onDelete: (SourcePlugin) -> Unit,
    onTogglePlugin: (String, Boolean) -> Unit,
) {
    var listExpanded by rememberSaveable { mutableStateOf(false) }
    val enabledCount = plugins.count { it.enabled }
    val errorSuffix = if (pluginErrors.isEmpty()) "" else " · ${pluginErrors.size} 个报错"
    val summary = if (plugins.isEmpty()) {
        "还没有来源 · 点 ＋ 添加"
    } else {
        "${plugins.size} 个 · ${enabledCount} 个启用$errorSuffix"
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (pluginErrors.isNotEmpty()) PluginErrorCard(pluginErrors)

        GlassCard(
            Modifier.fillMaxWidth(),
            strong = true,
            shape = RoundedCornerShape(24.dp),
            contentPadding = PaddingValues(0.dp),
        ) {
            Column(Modifier.fillMaxWidth()) {
                GroupHeader(
                    title = "来源插件",
                    summary = summary,
                    accent = RilanColors.Blue,
                    expanded = listExpanded,
                    onToggle = { listExpanded = !listExpanded },
                    trailing = {
                        GlassIconButton(
                            icon = Icons.Filled.Add,
                            contentDescription = "添加来源",
                            onClick = onAdd,
                            tint = RilanColors.Mint,
                            size = 34.dp,
                        )
                        Spacer(Modifier.width(2.dp))
                    },
                )
                AnimatedVisibility(
                    visible = listExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        GlassDivider(Modifier.padding(horizontal = 16.dp))
                        if (plugins.isEmpty()) {
                            EmptySourcesBlock(onAdd = onAdd)
                        } else {
                            plugins.forEachIndexed { index, plugin ->
                                SourceRow(
                                    plugin = plugin,
                                    onToggle = { enabled -> onTogglePlugin(plugin.id, enabled) },
                                    onEdit = { onEdit(plugin) },
                                    onDelete = { onDelete(plugin) },
                                )
                                if (index != plugins.lastIndex) {
                                    GlassDivider(Modifier.padding(start = 16.dp, end = 16.dp))
                                }
                            }
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            GhostButton("从剪贴板导入", onClick = onImport)
                            GhostButton("刷新内容", onClick = onRefresh, icon = Icons.Filled.Refresh)
                        }
                    }
                }
            }
        }

        BuiltinHotCard(
            weibo = weibo,
            bilibili = bilibili,
            douyin = douyin,
            onToggle = onToggleHot,
        )
    }
}

@Composable
private fun PluginErrorCard(errors: List<String>) {
    GlassCard(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 13.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "有 ${errors.size} 个插件没能加载",
                style = MaterialTheme.typography.labelMedium,
                color = RilanColors.Coral,
            )
            errors.take(4).forEach { message ->
                Text(
                    "· $message",
                    style = MaterialTheme.typography.labelSmall,
                    color = RilanColors.Coral.copy(alpha = 0.82f),
                )
            }
            if (errors.size > 4) {
                Text(
                    "…还有 ${errors.size - 4} 条，修好后点刷新",
                    style = MaterialTheme.typography.labelSmall,
                    color = RilanColors.TextTertiary,
                )
            }
        }
    }
}

@Composable
private fun EmptySourcesBlock(onAdd: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
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
private fun BuiltinHotCard(
    weibo: Boolean,
    bilibili: Boolean,
    douyin: Boolean,
    onToggle: (Category, Boolean) -> Unit,
) {
    val onCount = listOf(weibo, bilibili, douyin).count { it }
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
                    "$onCount / 3 已开 · 直接抓取",
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

// ─────────────────────────────────────────────────────────────────────────────
// 服务：MCP 默认只露一行状态
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ServiceTab(
    state: McpServerState,
    test: McpTestState,
    onCopyEndpoint: () -> Unit,
    onCopyToken: () -> Unit,
    onCopyDsh: () -> Unit,
    onCopyJson: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onTest: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var showToken by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(state.running, state.startedAt) {
        if (!state.running || state.startedAt <= 0L) return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val uptimeMs = if (state.running && state.startedAt > 0L) {
        (now - state.startedAt).coerceAtLeast(0L)
    } else 0L
    val tools = test.toolCount
    val summary = when {
        state.running && tools > 0 -> "运行中 · $tools 个工具 · 请求 ${state.requestCount} 次"
        state.running -> "运行中 · 已跑 ${formatUptime(uptimeMs)} · 请求 ${state.requestCount} 次"
        else -> "已停止 · 点开可启动或检测"
    }

    GlassCard(
        Modifier.fillMaxWidth(),
        strong = true,
        shape = RoundedCornerShape(24.dp),
        contentPadding = PaddingValues(0.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            GroupHeader(
                title = "MCP 服务",
                summary = summary,
                accent = RilanColors.Gold,
                expanded = expanded,
                onToggle = { expanded = !expanded },
                leading = { StatusDot(running = state.running) },
            )
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(11.dp),
                ) {
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

                    GlassDivider()

                    Text(
                        if (showToken) state.token.ifBlank { "启动后生成" } else maskToken(state.token),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                        ),
                        color = RilanColors.TextSecondary,
                        maxLines = if (showToken) 3 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextAction(
                            if (showToken) "隐藏令牌" else "显示令牌",
                            { showToken = !showToken },
                            color = RilanColors.TextSecondary,
                        )
                        Spacer(Modifier.weight(1f))
                        TextAction("复制令牌", onCopyToken)
                    }

                    Text(
                        "协议 2025-06-18 / 2025-03-26；只监听 127.0.0.1，除 OPTIONS 外都要 Bearer token。",
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
// AI：DeepSeek API Key 管理
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AiTab(
    settings: AppSettings,
    aiTest: AiTestState,
    onSaveSettings: ((AppSettings) -> Unit)?,
    onTestDeepSeek: ((String, String) -> Unit)?,
    onOpenService: () -> Unit,
) {
    val context = LocalContext.current
    val configured = settings.deepseekConfigured
    // 没配过 Key 时直接把表单打开，省一次点击；配好后默认收起，只留摘要。
    var formExpanded by rememberSaveable { mutableStateOf(!configured) }
    var showKey by remember { mutableStateOf(false) }
    var keyDraft by remember(settings.deepseekApiKey) { mutableStateOf(settings.deepseekApiKey) }
    var modelDraft by remember(settings.deepseekModel) { mutableStateOf(settings.deepseekModel) }
    var confirmClear by remember { mutableStateOf(false) }

    fun persist(key: String, model: String) {
        val save = onSaveSettings
        if (save == null) {
            Toast.makeText(context, "保存入口还没接入", Toast.LENGTH_SHORT).show()
            return
        }
        save(settings.copy(deepseekApiKey = key.trim(), deepseekModel = model))
        Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
    }

    val summary = if (configured) {
        "已连接 · ${maskApiKey(settings.deepseekApiKey)} · ${settings.deepseekModel}"
    } else {
        "未配置 · 填入 Key 即可生成今日总览"
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        GlassCard(
            Modifier.fillMaxWidth(),
            strong = true,
            shape = RoundedCornerShape(24.dp),
            contentPadding = PaddingValues(0.dp),
        ) {
            Column(Modifier.fillMaxWidth()) {
                GroupHeader(
                    title = "DeepSeek 直连",
                    summary = summary,
                    accent = RilanColors.Dawn,
                    expanded = formExpanded,
                    onToggle = { formExpanded = !formExpanded },
                    leading = {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (configured) RilanColors.Dawn
                                    else RilanColors.TextTertiary.copy(alpha = 0.55f)
                                )
                        )
                    },
                )
                AnimatedVisibility(
                    visible = formExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(11.dp),
                    ) {
                        Text(
                            "填入 DeepSeek API Key，首页就能直接总结今天的天气、信息流和日程；生成时这些内容会发给 DeepSeek，Key 只保存在本机。",
                            style = MaterialTheme.typography.bodySmall,
                            color = RilanColors.TextSecondary,
                        )

                        if (onSaveSettings == null) {
                            Text(
                                "保存入口还在接入中，下次更新就能在这里填写。Key 不会写进日志。",
                                style = MaterialTheme.typography.bodySmall,
                                color = RilanColors.TextTertiary,
                            )
                        } else {
                            OutlinedTextField(
                                value = keyDraft,
                                onValueChange = { keyDraft = it },
                                placeholder = { Text("sk-…") },
                                singleLine = true,
                                visualTransformation = if (showKey) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Done,
                                ),
                                trailingIcon = {
                                    Text(
                                        if (showKey) "隐藏" else "显示",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = RilanColors.Dawn,
                                        modifier = Modifier
                                            .clickable { showKey = !showKey }
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(15.dp),
                                colors = deepseekFieldColors(),
                            )

                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                DEEPSEEK_MODEL_IDS.forEach { model ->
                                    ModelChip(
                                        model = model,
                                        selected = modelDraft == model,
                                        onClick = { modelDraft = model },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }

                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                MintButton(
                                    text = "保存配置",
                                    onClick = { persist(keyDraft, modelDraft) },
                                    modifier = Modifier.weight(1f),
                                )
                                GhostButton(
                                    text = if (aiTest.loading) "连接中…" else "测试连接",
                                    onClick = {
                                        if (keyDraft.isNotBlank() && !aiTest.loading) {
                                            onTestDeepSeek?.invoke(keyDraft.trim(), modelDraft)
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }

                            when {
                                aiTest.loading -> LoadingDots(RilanColors.Dawn)
                                aiTest.message != null -> Text(
                                    aiTest.message,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (aiTest.ok) RilanColors.Jade else RilanColors.Danger,
                                )
                            }

                            if (configured) {
                                TextAction(
                                    "清除已保存的 Key",
                                    { confirmClear = true },
                                    color = RilanColors.Coral,
                                )
                            }
                        }

                        Text(
                            "模型：deepseek-flash 更快；deepseek-v4-pro 更细。生成一次大约几千 token，按 DeepSeek 官方价格计费。",
                            style = MaterialTheme.typography.labelSmall,
                            color = RilanColors.TextTertiary,
                        )
                        Text(
                            "只存本机、不写日志：Key 保存在应用私有目录，只有生成总览和测试连接时才会用到。",
                            style = MaterialTheme.typography.labelSmall,
                            color = RilanColors.TextTertiary,
                        )
                    }
                }
            }
        }

        GlassCard(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            contentPadding = PaddingValues(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("MCP 是另一条路", RilanColors.Gold)
                Text(
                    "MCP 让 DSH 等外部 AI 读取日报、写解读、操作日程；和 DeepSeek 直连互不影响，可以只开一个。",
                    style = MaterialTheme.typography.bodySmall,
                    color = RilanColors.TextSecondary,
                )
                TextAction("去服务页 →", onOpenService, color = RilanColors.Gold)
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = RilanColors.SurfaceRaised,
            titleContentColor = RilanColors.Text,
            textContentColor = RilanColors.TextSecondary,
            title = { Text("清除 API Key", style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    "会删掉本机保存的 DeepSeek Key，首页将不再生成 AI 总览。日程和来源不受影响。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        persist("", settings.deepseekModel)
                        confirmClear = false
                    },
                ) { Text("清除", color = RilanColors.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text("取消", color = RilanColors.TextTertiary)
                }
            },
        )
    }
}

@Composable
private fun ModelChip(
    model: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hint = if (model == "deepseek-v4-pro") "更细" else "更快"
    Row(
        modifier
            .clip(RoundedCornerShape(13.dp))
            .background(if (selected) RilanColors.Dawn.copy(alpha = 0.13f) else Color.White.copy(alpha = 0.045f))
            .border(
                0.75.dp,
                if (selected) RilanColors.Dawn.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.11f),
                RoundedCornerShape(13.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(if (selected) RilanColors.Dawn else RilanColors.TextTertiary.copy(alpha = 0.5f))
        )
        Text(
            model,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = if (selected) RilanColors.Text else RilanColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            hint,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) RilanColors.Dawn else RilanColors.TextTertiary,
        )
    }
}

@Composable
private fun deepseekFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = RilanColors.Text,
    unfocusedTextColor = RilanColors.Text,
    focusedBorderColor = RilanColors.Mint.copy(alpha = 0.7f),
    unfocusedBorderColor = Color(0x28FFFFFF),
    focusedLabelColor = RilanColors.Mint,
    unfocusedLabelColor = RilanColors.TextTertiary,
    cursorColor = RilanColors.Mint,
    focusedContainerColor = Color(0x0DFFFFFF),
    unfocusedContainerColor = Color(0x08FFFFFF),
    focusedPlaceholderColor = RilanColors.TextTertiary,
    unfocusedPlaceholderColor = RilanColors.TextTertiary,
)

// ─────────────────────────────────────────────────────────────────────────────
// 更多：插件目录与边界说明
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun MoreTab(
    directory: String,
    onCopyDirectory: () -> Unit,
    onReloadPlugins: () -> Unit,
    settings: AppSettings,
    onSaveSettings: ((AppSettings) -> Unit)?,
    notificationsGranted: Boolean,
    onRequestNotifications: (() -> Unit)?,
) {
    var notesExpanded by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val save = onSaveSettings

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // 通知与自启动：设置本身走 AppSettings 持久化，闹钟由 AppRepository.saveSettings 立刻同步。
        NotifyAutoStartSection(
            dailyEnabled = settings.dailyNoticeEnabled,
            minuteOfDay = settings.dailyNoticeMinuteOfDay,
            autoStartEnabled = settings.autoStartEnabled,
            notificationsGranted = notificationsGranted,
            onDailyEnabledChange = { enabled ->
                save?.invoke(settings.copy(dailyNoticeEnabled = enabled))
                if (enabled && !notificationsGranted) onRequestNotifications?.invoke()
            },
            onMinuteOfDayChange = { minute ->
                save?.invoke(settings.copy(dailyNoticeMinuteOfDay = minute))
            },
            onSendTest = {
                val ok = DailyBriefNotifier.sendNow(context)
                Toast.makeText(
                    context,
                    if (ok) "已发一条今日简报，点通知可回到首页" else "通知权限未开启，先在系统设置里允许通知",
                    Toast.LENGTH_SHORT,
                ).show()
            },
            onAutoStartEnabledChange = { enabled ->
                save?.invoke(settings.copy(autoStartEnabled = enabled))
            },
            onRequestNotifications = { onRequestNotifications?.invoke() },
        )

        GlassCard(
            Modifier.fillMaxWidth(),
            strong = true,
            shape = RoundedCornerShape(22.dp),
            contentPadding = PaddingValues(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("插件目录", RilanColors.Blue)
                DirectoryRow(
                    directory = directory,
                    onCopy = onCopyDirectory,
                    onReload = onReloadPlugins,
                )
                Text(
                    "把来源的 JSON 文件放进这个目录，点「重新加载」立刻生效；删除来源在「来源」里做。",
                    style = MaterialTheme.typography.labelSmall,
                    color = RilanColors.TextTertiary,
                )
            }
        }

        GlassCard(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            contentPadding = PaddingValues(0.dp),
        ) {
            Column(Modifier.fillMaxWidth()) {
                GroupHeader(
                    title = "说明与边界",
                    summary = "本机范围、文件位置与内容提示",
                    accent = RilanColors.TextTertiary,
                    expanded = notesExpanded,
                    onToggle = { notesExpanded = !notesExpanded },
                )
                AnimatedVisibility(
                    visible = notesExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "MCP 只监听 127.0.0.1，除 OPTIONS 外都要 Bearer token；App 在后台时由前台服务保活，关掉通知可能让服务被系统回收。",
                            style = MaterialTheme.typography.labelSmall,
                            color = RilanColors.TextTertiary,
                        )
                        Text(
                            "来源插件与生成内容都保存在应用私有目录，卸载 App 会一起删除。",
                            style = MaterialTheme.typography.labelSmall,
                            color = RilanColors.TextTertiary,
                        )
                        Text(
                            "外部 AI 生成的内容可能不准确，重要信息请打开原文核对。",
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

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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

/** DeepSeek Key 的掩码：sk-…后 4 位，够确认是哪一把，又不会整串露在屏幕上。 */
private fun maskApiKey(key: String): String {
    val trimmed = key.trim()
    return when {
        trimmed.isEmpty() -> "未填写"
        trimmed.length <= 8 -> "sk-…${trimmed.takeLast(2)}"
        else -> "${trimmed.take(3)}…${trimmed.takeLast(4)}"
    }
}

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
