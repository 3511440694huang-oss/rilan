# 日览 1.6.0

一个本地优先的 Android 日报应用：每日壁纸、按位置获取的天气、中文新闻与热榜、日程提醒、周期回顾、收录条目导出、每日通知，以及面向外部 AI 的本机 MCP 服务。

仓库：<https://github.com/3511440694huang-oss/rilan>

## 1.6.0 的变化

- **新图标**：换成「简报卡片 + 日出 + 资讯线」，深靛蓝、奶白、杏桃三色纯色块，无渐变无阴影；方形、圆形、自适应前景全部重做。
- **周期总结双模式**：回望页可以在「MCP 写回」和「DeepSeek 直连」之间切换。直连模式用本机记录 + DeepSeek API Key 直接生成，不再依赖外部 AI 客户端；MCP 写回流程保持不变。
- **收录条目导出**：回望页新增「导出收录」，支持当前周期 / 全部、Markdown / CSV，可保存到「下载/日览」或通过系统分享发出；API 26–28 自动改用分享兜底。
- **扩展页重排**：顶部改为「来源 / 服务 / AI / 更多」四段，默认只显示摘要行，点开才展开细节；MCP 令牌、DSH 配置、插件编辑都收进展开区。
- **DeepSeek API Key 管理**：从设置页迁到扩展页「AI」段，支持掩码显示、模型选择、测试连接、保存与清除；只存本机，不写日志。
- **天气刷新提速**：启动时先用缓存坐标拉天气、定位在后台并行补齐，不再让定位阻塞天气；连接超时 6.5 秒、读超时 11 秒，失败自动重试一次；空气质量不再拖住主天气。
- **滑动更顺**：滑动卡片时暂停玻璃折射重绘、降低阴影，天气小时图标改为静态绘制，页轨独立订阅当前页，减少整页重组。
- **底部导航不再切断卡片**：底部渐隐从 136dp 三段硬渐变改成 220dp 七段缓动，四张卡统一底部留白（80dp + 系统导航栏高度），卡片不再出现硬切边。
- **每日通知**：扩展页「更多」里可开启每日简报通知、设置时间、试发；默认关闭，打开时引导通知权限，点击通知直接进入首页。
- **开机自启动**：开关打开后，开机 / 应用更新会恢复每日通知和已启用的 MCP 前台服务；受系统限制不直接弹出界面。

## 安装

APK 直链：<https://github.com/3511440694huang-oss/rilan/releases/download/v1.6.0/rilan-1.6.0.apk>

Release：<https://github.com/3511440694huang-oss/rilan/releases/tag/v1.6.0>

本地副本：`APK/日览-v1.6.0.apk`、`/sdcard/Download/DSH-Go/日览-v1.6.0.apk`

- 包名：`app.rilan.daily`
- 版本：1.6.0（versionCode 7）
- 最低 Android 8.0（API 26），targetSdk 34
- SHA-256：`3ce51333a9a157812002f9282427085ca20cd42a6d5e63ca43bc2f208a48cad1`
- 签名：APK Signature Scheme v2 已验证

## 位置与天气

- 默认开启「自动定位」。首次启动会说明用途并请求定位权限，只用于天气。
- 启动时先显示上次天气，再用上次坐标并行请求新天气；定位和区县反查在后台补，拿到新坐标才再拉一次，位置服务慢不会卡住天气卡。
- 定位成功后按经纬度请求 Open-Meteo；区县与街道用 BigDataCloud 中文反向地理编码，同一位置 24 小时内走本地缓存，缺失时回退系统 Geocoder。
- 刷新节奏：天气最多 3 分钟、信息流最多 6 分钟、定位最多 30 分钟；手动刷会强制重新定位。
- 网络超时或天气服务暂时不可用时保留上一次成功的天气，卡片显示错误文案和「重试」，不会清空。

## DeepSeek 与周期总结

### 配置

1. 打开「扩展 → AI」。
2. 填入 DeepSeek API Key，选择模型：
   - `deepseek-flash`：更快、更省，日常总览够用；
   - `deepseek-v4-pro`：更细，适合想读长一点的分析。
3. 点「测试连接」确认 Key 和网络正常，再点「保存配置」。
4. 首页第 2 张卡「今日简报」可以点「用 DeepSeek 总结今天」生成今日总览。

### 周期总结（回望页）

- **MCP 写回**：外部 AI 通过 MCP 读取 `get_records`，再用 `save_period_summary` 写回，适合已经在用外部客户端的场景。
- **DeepSeek 直连**：在回望页切到「DeepSeek 直连」，点生成，App 用本机收录记录拼提示词调用 DeepSeek，结果写回同一份周期总结。没填 Key 时会引导到扩展页。
- 两种模式写回的数据结构一致，卡片会标注来源（MCP / DeepSeek）和生成时间；已有总结会被覆盖前先确认。

实现说明：

- 请求 `POST https://api.deepseek.com/chat/completions`，使用非思考模式，返回 Markdown 正文。
- Key 只保存在应用私有 SharedPreferences，不写日志、不上传；卸载 App 会一起删除。
- 日览仍然没有内置 AI 对话；外部 AI 读写走 MCP。

## 收录条目导出

1. 打开底部「回望」。
2. 点标题右侧「导出收录」。
3. 选范围（当前周期 / 全部）和格式（Markdown / CSV），点「保存到下载」或「分享」。

- Android 10 及以上写入「下载/日览/日览-收录-<日期>.md / .csv」；Android 8–9 没有公共下载目录写权限，会自动改为系统分享。
- 导出内容包含日期、首次收录时间、分类、来源、标题、链接，按首次收录时间倒序，与本地记录一致。

## 通知与自启动

- **每日通知**：扩展 → 更多 → 打开「今日简报」，设时间（默认 08:00）、可试发。通知内容会带 36 小时内的天气缓存和今天未完成的日程；点通知进入首页。
- **通知权限**：Android 13 及以上第一次打开开关会请求通知权限；没有权限时通知静默跳过，不会崩溃。
- **开机自启动**：打开后，开机 / 应用更新会恢复每日通知；如果 MCP 之前在运行且开关打开，会尝试恢复 MCP 前台服务。
  - 系统限制：开机后不会自动弹出界面，只会恢复后台服务和通知；部分手机（MIUI / ColorOS 等）还需要在系统设置里允许「自启动」与「后台运行」。
- 日程提醒与每日通知是两个独立通道，互不影响。

## MCP

### 启动

1. 打开日览 → 底部「扩展」→「服务」。
2. 点「启动 MCP」，或直接点「检测连接」让服务启动并走一遍本机回环。
3. 看到「本机连接正常 · 19 个工具」后，点「复制 DSH 配置」。
4. 把配置粘贴到 DeepSeek Harness 的 MCP 列表，重载客户端。

App 会自动生成 token；服务只绑定 `127.0.0.1`，端口默认 `8765`。扩展页还提供「通用 JSON」给其他 MCP 客户端。

DeepSeek Harness 的配置格式：

```yaml
- id: mcp-rilan
  name: '@deepseek-ai/dsh-mcp-client'
  config:
    serverName: rilan
    transport: streamable-http
    url: http://127.0.0.1:8765/mcp
    headers:
      Authorization: 'Bearer <扩展页复制的 token>'
```

其他 MCP 客户端：

```
endpoint: http://127.0.0.1:8765/mcp
header:  Authorization: Bearer <token>
```

### 外部 AI 能做什么

读取：`get_weather`、`get_news`、`get_briefing`、`search_news`、`get_records`、`list_schedules`、`list_sources`，以及 5 个 `rilan://` 资源。

写入：

- 日程：`create_schedule`、`update_schedule`、`delete_schedule`
- 来源：`add_source`、`remove_source`、`set_source_enabled`
- 设置与刷新：`set_city`、`set_reminder_time`、`refresh_content`
- 写回内容：`save_weather_insight`、`save_daily_brief`、`save_period_summary`

写入后界面立即更新，重启后保留。

### 连接不上时

- 先点扩展页的「检测连接」：它会验证端口、Bearer token、initialize 和 tools/list，失败会显示具体原因。
- 检查外部客户端里的地址是否和扩展页显示的 endpoint 完全一致，token 是否带 `Bearer ` 前缀。
- MCP 服务需要保持运行；App 在后台时由前台服务保活，回到前台会自动恢复。
- 如果提示端口被占用，通常是其他 App 占了 8765；先在系统里停掉占用方，或等待日览下次启动。

### 安全边界

- 仅监听 `127.0.0.1`，不开放到局域网。
- 所有请求需要 Bearer token，token 只存在本机应用私有存储。
- 服务停止时端口关闭；前台服务通知可直接停止。
- MCP 可以读写本机日程和来源，只连接你信任的 AI 客户端。

## 来源插件

### 目录

`Android/data/app.rilan.daily/files/plugins/sources/`

一个 `<id>.json` 一个插件；文件名必须与 `id` 一致。删掉文件即删除来源。应用内「扩展 → 来源」可添加、编辑、从剪贴板导入、启停和删除。

首次启动写入默认中文来源与 `README.txt`；升级时会移除旧版不可直连的英文默认源，并补上新的中文源，但不会恢复用户主动删除的旧源。

### 默认来源

- 综合：IT之家、少数派、Solidot、爱范儿、中国新闻网、人民网、V2EX、掘金、机核
- AI：橘鸦 AI 早报、量子位
- 热榜：知乎热榜、百度热搜
- 内置热榜：微博 / B站 / 抖音由应用直接抓取，可在扩展页分别关闭
- 安全：四叶草安全、Seebug Paper
- 世界：中国新闻网 · 国际、人民网 · 国际

BBC、Guardian、Al Jazeera、Hacker News 不再默认添加；需要时可以在扩展页手动加 RSS。

### RSS 插件

```json
{
  "schema": 1,
  "id": "my-rss",
  "name": "我的 RSS",
  "category": "GENERAL",
  "type": "rss",
  "url": "https://example.com/feed.xml",
  "enabled": true,
  "maxItems": 12,
  "headers": {
    "Referer": "https://example.com/"
  }
}
```

### JSON 接口插件

```json
{
  "schema": 1,
  "id": "my-json-hot",
  "name": "我的 JSON 热榜",
  "category": "WEIBO",
  "type": "json",
  "url": "https://api.example.com/hot/list",
  "enabled": true,
  "maxItems": 15,
  "mapping": {
    "items": "data.list",
    "title": "title",
    "url": "url",
    "source": "author.name",
    "time": "created_at",
    "hot": "hot_value",
    "snippet": "description",
    "linkPrefix": "https://example.com/item/"
  }
}
```

字段：

- `category`：`GENERAL` / `AI` / `ZHIHU` / `WEIBO` / `BILIBILI` / `DOUYIN` / `BAIDU` / `SECURITY` / `WORLD`
- `type`：`rss` 或 `json`
- `maxItems`：1–40
- `mapping.items`：数组所在的点路径（`data.list`、`items.0.list`）；留空表示响应根就是数组
- `mapping.url`：可以给完整 URL，也可以给 id，再用 `linkPrefix` 拼成网页地址
- `time`：秒/毫秒时间戳，或 ISO/RFC1123 时间文本
- `hot`：原样保留为热度字符串
- 热榜分类默认过滤明星娱乐；可在插件里设置 `"filterEntertainment": false` 关闭

## 构建

```bash
bash build.sh :app:testDebugUnitTest :app:assembleRelease
```

- JDK 17、Gradle 8.11.1、AGP 8.8.2、Kotlin 2.0.21
- APK 输出：`app/build/outputs/apk/release/app-release.apk`
- 测试：日程重复/生日跨年 9 项、插件解析 6 项、MCP 协议与鉴权 6 项、DeepSeek 响应解析 3 项、定位文案 2 项，共 26 项
- MCP 客户端兼容性：服务端单元测试覆盖 initialize、tools/list、鉴权、通知、协议版本回退与 DELETE 会话；实际外部客户端连接需在设备上完成。
