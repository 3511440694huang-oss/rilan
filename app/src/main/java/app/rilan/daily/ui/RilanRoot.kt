package app.rilan.daily.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rilan.daily.MainViewModel
import app.rilan.daily.ui.components.AppBackdrop
import app.rilan.daily.ui.components.GlassCard
import app.rilan.daily.ui.extensions.ExtensionsScreen
import app.rilan.daily.ui.extensions.ExtensionsSection
import app.rilan.daily.ui.home.HomeScreen
import app.rilan.daily.ui.review.ReviewScreen
import app.rilan.daily.ui.schedule.ScheduleScreen
import app.rilan.daily.ui.settings.SettingsScreen
import app.rilan.daily.ui.theme.RilanColors
import kotlinx.coroutines.delay

private enum class MainTab(val label: String, val icon: ImageVector) {
    HOME("日报", Icons.Filled.Home),
    SCHEDULE("日程", Icons.Filled.DateRange),
    REVIEW("回望", Icons.Filled.Star),
    EXTENSIONS("扩展", Icons.Filled.Build),
}

/** 非首页内容给悬浮导航让出的基础高度：56dp 胶囊 + 8dp 间距 + 16dp 呼吸位。 */
private val DOCK_CONTENT_BASE_INSET = 80.dp

/**
 * 悬浮导航让出的高度要叠加系统导航栏：手势导航约 24dp，三键导航约 48dp。
 * 之前固定 92dp 在三键导航机型上会让最后一条内容压到胶囊下面。
 */
@Composable
private fun dockContentInset(): Dp =
    DOCK_CONTENT_BASE_INSET +
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

@Composable
fun RilanRoot(vm: MainViewModel) {
    val context = LocalContext.current
    val home by vm.home.collectAsStateWithLifecycle()
    val schedules by vm.schedules.collectAsStateWithLifecycle()
    val records by vm.records.collectAsStateWithLifecycle()
    val digests by vm.digests.collectAsStateWithLifecycle()
    val summaryLoading by vm.summaryLoading.collectAsStateWithLifecycle()
    val settingsUi by vm.settingsUi.collectAsStateWithLifecycle()
    val mcpState by vm.mcpState.collectAsStateWithLifecycle()
    val mcpTest by vm.mcpTest.collectAsStateWithLifecycle()
    val plugins by vm.plugins.collectAsStateWithLifecycle()
    val pluginErrors by vm.pluginErrors.collectAsStateWithLifecycle()
    val aiTest by vm.aiTest.collectAsStateWithLifecycle()

    var tabName by rememberSaveable { mutableStateOf(MainTab.HOME.name) }
    val tab = runCatching { MainTab.valueOf(tabName) }.getOrDefault(MainTab.HOME)
    // 首页 / 设置页 / 回望页跳扩展页时，指定落点（来源 / 服务 / AI / 更多）。
    var extensionsStart by rememberSaveable { mutableStateOf(ExtensionsSection.SOURCES) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var notificationsGranted by remember { mutableStateOf(vm.notificationsEnabled()) }
    var showLocationIntro by remember { mutableStateOf(false) }
    // 内容底部留白跟随系统导航栏，悬浮胶囊不会压住最后一行。
    val dockInset = dockContentInset()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationsGranted = granted
        if (!granted) {
            Toast.makeText(context, "没有通知权限，日程提醒不会出现", Toast.LENGTH_LONG).show()
        }
    }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.any { it }
        if (granted) {
            Toast.makeText(context, "已按当前位置获取天气", Toast.LENGTH_SHORT).show()
            vm.locateNow()
        } else {
            vm.setAutoLocation(false)
            vm.refreshAll()
            Toast.makeText(context, "未授权定位，已改为手动城市，可在设置里选择", Toast.LENGTH_LONG).show()
        }
    }

    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                notificationsGranted = true
            }
        } else {
            notificationsGranted = vm.notificationsEnabled()
            if (!notificationsGranted) {
                Toast.makeText(context, "请在系统设置中允许日览发送通知", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun launchLocationPermission() {
        vm.markLocationAsked()
        locationLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        )
    }

    fun requestLocation() {
        if (hasLocationPermission(context)) {
            vm.locateNow()
        } else {
            launchLocationPermission()
        }
    }

    LaunchedEffect(settingsUi.settings.locationAsked, settingsUi.settings.autoLocation) {
        if (settingsUi.settings.autoLocation &&
            !settingsUi.settings.locationAsked &&
            !hasLocationPermission(context)
        ) {
            delay(700)
            showLocationIntro = true
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current

    // 应用一直开着时，天气最多 5 分钟、信息流最多 8 分钟就会刷新一次；
    // 跨天也会顺带换壁纸和清掉昨天的 AI 总览。只在 RESUMED 时发请求。
    LaunchedEffect(lifecycleOwner) {
        while (true) {
            delay(3 * 60_000L)
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                vm.onForeground()
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsGranted = vm.notificationsEnabled()
                vm.onForeground()
                vm.ensureMcpRunningIfEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AppBackdrop(wallpaper = home.wallpaper) {
        AnimatedContent(
            targetState = settingsOpen,
            transitionSpec = {
                if (targetState) {
                    (slideInHorizontally(tween(300)) { it / 6 } + fadeIn(tween(240))) togetherWith
                        fadeOut(tween(160))
                } else {
                    fadeIn(tween(220)) togetherWith
                        (slideOutHorizontally(tween(260)) { it / 6 } + fadeOut(tween(180)))
                }
            },
            label = "settings",
        ) { settingsRoute ->
            if (settingsRoute) {
                SettingsScreen(
                    state = settingsUi,
                    onBack = { settingsOpen = false },
                    onSave = vm::saveSettings,
                    onSearchCity = vm::searchCity,
                    onChooseCity = vm::chooseCity,
                    aiTest = aiTest,
                    onTestDeepSeek = vm::testDeepSeek,
                    onClearAiContent = vm::clearAiContent,
                    onTestNotification = {
                        if (notificationsGranted) {
                            vm.sendTestNotification()
                            Toast.makeText(context, "测试通知已发送", Toast.LENGTH_SHORT).show()
                        } else {
                            requestNotifications()
                        }
                    },
                    onOpenExtensions = {
                        extensionsStart = ExtensionsSection.AI
                        settingsOpen = false
                        tabName = MainTab.EXTENSIONS.name
                    },
                    onRelocate = ::requestLocation,
                    onAutoLocationChange = { enabled ->
                        if (enabled) {
                            vm.setAutoLocation(true)
                            if (hasLocationPermission(context)) {
                                vm.locateNow()
                            } else {
                                launchLocationPermission()
                            }
                        } else {
                            vm.setAutoLocation(false)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize()) {
                    AnimatedContent(
                        targetState = tab,
                        transitionSpec = {
                            val direction = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                            (fadeIn(tween(360, easing = FastOutSlowInEasing)) +
                                slideInHorizontally(tween(440, easing = FastOutSlowInEasing)) {
                                    direction * it / 9
                                }) togetherWith
                                (fadeOut(tween(210)) +
                                    slideOutHorizontally(tween(320, easing = FastOutSlowInEasing)) {
                                        -direction * it / 14
                                    })
                        },
                        label = "tab",
                    ) { current ->
                        when (current) {
                            MainTab.HOME -> HomeScreen(
                                state = home,
                                aiConfigured = settingsUi.settings.deepseekConfigured,
                                events = schedules,
                                onRefresh = vm::refreshAll,
                                onOpenSettings = { settingsOpen = true },
                                onItemClick = { item -> openUrl(context, item.url) },
                                // 「去设置连接 DeepSeek」直接落在扩展页的 AI 分段，不用再自己找。
                                onOpenAiSettings = {
                                    extensionsStart = ExtensionsSection.AI
                                    settingsOpen = false
                                    tabName = MainTab.EXTENSIONS.name
                                },
                                onGenerateBrief = vm::generateDailyBrief,
                                onRelocate = ::requestLocation,
                                onOpenSchedule = { tabName = MainTab.SCHEDULE.name },
                                modifier = Modifier.fillMaxSize(),
                            )
                            MainTab.SCHEDULE -> ScheduleScreen(
                                events = schedules,
                                notificationsEnabled = notificationsGranted,
                                reminderLabel = settingsUi.settings.reminderLabel,
                                onAdd = { event ->
                                    vm.addSchedule(event)
                                    if (!notificationsGranted) requestNotifications()
                                },
                                onUpdate = vm::updateSchedule,
                                onDelete = vm::deleteSchedule,
                                onToggleDone = vm::toggleScheduleDone,
                                onRequestNotification = ::requestNotifications,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(bottom = dockInset),
                            )
                            MainTab.REVIEW -> ReviewScreen(
                                records = records,
                                digests = digests,
                                loading = summaryLoading,
                                aiConfigured = mcpState.running,
                                onGenerate = {
                                    extensionsStart = ExtensionsSection.SERVICE
                                    tabName = MainTab.EXTENSIONS.name
                                },
                                onOpenSettings = {
                                    extensionsStart = ExtensionsSection.AI
                                    tabName = MainTab.EXTENSIONS.name
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(bottom = dockInset),
                                deepseekConfigured = settingsUi.settings.deepseekConfigured,
                                summaryMode = settingsUi.settings.summaryMode,
                                onModeChange = vm::setSummaryMode,
                                onGenerateDirect = vm::generatePeriodSummaryDirect,
                            )
                            MainTab.EXTENSIONS -> ExtensionsScreen(
                                mcpState = mcpState,
                                mcpTest = mcpTest,
                                plugins = plugins,
                                pluginErrors = pluginErrors,
                                pluginDirectory = vm.pluginDirectory,
                                onStartMcp = {
                                    if (!notificationsGranted) requestNotifications()
                                    vm.startMcp()
                                },
                                onStopMcp = vm::stopMcp,
                                onTestMcp = {
                                    if (!notificationsGranted) requestNotifications()
                                    vm.testMcp()
                                },
                                onSavePlugin = { raw, originalId -> vm.savePlugin(raw, originalId) },
                                onDeletePlugin = vm::deletePlugin,
                                onTogglePlugin = vm::togglePlugin,
                                weiboHotEnabled = settingsUi.settings.weiboHotEnabled,
                                bilibiliHotEnabled = settingsUi.settings.bilibiliHotEnabled,
                                douyinHotEnabled = settingsUi.settings.douyinHotEnabled,
                                onToggleHot = vm::setHotListEnabled,
                                onReloadPlugins = vm::reloadPlugins,
                                onRefreshContent = vm::refreshAll,
                                settings = settingsUi.settings,
                                aiTest = aiTest,
                                onSaveSettings = vm::saveSettings,
                                onTestDeepSeek = vm::testDeepSeek,
                                initialSection = extensionsStart,
                                notificationsGranted = notificationsGranted,
                                onRequestNotifications = ::requestNotifications,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(bottom = dockInset),
                            )
                        }
                    }

                    // 悬浮导航下面压一层很淡的夜色，内容从下面经过时也能看清图标。
                    BottomDockScrim(Modifier.align(Alignment.BottomCenter))
                    RilanNavBar(
                        current = tab,
                        onSelect = { tabName = it.name },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }

    if (showLocationIntro) {
        AlertDialog(
            onDismissRequest = {
                vm.markLocationAsked()
                showLocationIntro = false
            },
            containerColor = RilanColors.SurfaceRaised,
            titleContentColor = RilanColors.Text,
            textContentColor = RilanColors.TextSecondary,
            title = {
                Text("让天气跟着你走", style = MaterialTheme.typography.headlineSmall)
            },
            text = {
                Text(
                    "日览会用当前位置获取天气和穿衣建议，不再固定在北京。位置只用于天气，不会上传或保存到别处。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLocationIntro = false
                        launchLocationPermission()
                    }
                ) {
                    Text("允许定位", color = RilanColors.Mint)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        vm.markLocationAsked()
                        vm.setAutoLocation(false)
                        showLocationIntro = false
                        settingsOpen = true
                    }
                ) {
                    Text("手动选城市", color = RilanColors.TextTertiary)
                }
            },
        )
    }

    BackHandler(enabled = settingsOpen) { settingsOpen = false }
}

// ─────────────────────────────────────────────────────────────────────────────
// 底部导航：一枚会自己变宽的悬浮胶囊，只有当前项展开文字
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun BottomDockScrim(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            // 从 220dp 处就开始极缓慢地压暗，一路匀到导航下面：
            // 没有明显起步线，透明卡片上不会再出现一条把卡片「切断」的横边。
            .height(220.dp)
            .background(
                Brush.verticalGradient(
                    0.00f to Color.Transparent,
                    0.18f to RilanColors.Ink.copy(alpha = 0.03f),
                    0.36f to RilanColors.Ink.copy(alpha = 0.08f),
                    0.54f to RilanColors.Ink.copy(alpha = 0.16f),
                    0.72f to RilanColors.Ink.copy(alpha = 0.29f),
                    0.86f to RilanColors.Ink.copy(alpha = 0.45f),
                    1.00f to RilanColors.Ink.copy(alpha = 0.64f),
                )
            )
    )
}

@Composable
private fun RilanNavBar(
    current: MainTab,
    onSelect: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        GlassCard(
            modifier = Modifier
                .height(56.dp)
                .animateContentSize(spring(stiffness = 380f, dampingRatio = 0.88f)),
            shape = RoundedCornerShape(28.dp),
            strong = false,
            refraction = true,
            sheen = 0.40f,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) {
            Row(
                Modifier
                    .fillMaxHeight()
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MainTab.entries.forEach { item ->
                    val selected = item == current
                    val progress by animateFloatAsState(
                        targetValue = if (selected) 1f else 0f,
                        animationSpec = spring(stiffness = 420f, dampingRatio = 0.78f),
                        label = "navItem",
                    )
                    val tint by animateColorAsState(
                        targetValue = if (selected) RilanColors.Moon else RilanColors.TextTertiary,
                        animationSpec = tween(240),
                        label = "navTint",
                    )
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(22.dp))
                            .background(
                                if (selected) RilanColors.Moon.copy(alpha = 0.10f)
                                else Color.Transparent
                            )
                            .clickable { onSelect(item) }
                            .padding(
                                start = if (selected) 13.dp else 12.dp,
                                end = if (selected) 14.dp else 12.dp,
                                top = 8.dp,
                                bottom = 8.dp,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            item.icon,
                            contentDescription = item.label,
                            tint = tint,
                            modifier = Modifier
                                .size(20.dp)
                                .graphicsLayer {
                                    scaleX = 0.94f + 0.10f * progress
                                    scaleY = 0.94f + 0.10f * progress
                                    translationY = -1.2.dp.toPx() * progress
                                },
                        )
                        AnimatedVisibility(
                            visible = selected,
                            enter = expandHorizontally(
                                animationSpec = spring(stiffness = 460f, dampingRatio = 0.82f),
                            ) + fadeIn(tween(180)),
                            exit = shrinkHorizontally(tween(150)) + fadeOut(tween(110)),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    item.label,
                                    color = tint,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 0.3.sp,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

private fun openUrl(context: Context, url: String) {
    if (url.isBlank()) {
        Toast.makeText(context, "没有可打开的链接", Toast.LENGTH_SHORT).show()
        return
    }
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        Toast.makeText(context, "无法打开这个链接", Toast.LENGTH_SHORT).show()
    }
}
