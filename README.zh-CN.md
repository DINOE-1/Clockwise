# Clockwise

[English original](README.md) | [繁體中文](README.zh-TW.md) | **简体中文**

Clockwise 是一款本地优先的 Android 日历与闹钟应用，使用 Jetpack Compose 和 Material 3 构建。
它结合了月/日视图、可全屏响铃的精确闹钟、彩色标签，以及可选的 AI 助手；AI 会在你确认预览后
读取并修改日程。

界面提供英文与繁体中文（zh-TW），默认跟随系统语言，可在 **设置 → 语言** 切换。源代码和文档以
英文为主。

## 界面预览

以下是主要界面的快速预览。应用界面提供英文与繁体中文，两种语言的截图分别放在
[`images/en`](images/en) 与 [`images/zh-TW`](images/zh-TW)；下表使用繁体中文界面。

| 月视图 | 日视图 | 创建活动 |
| :---: | :---: | :---: |
| <img src="images/zh-TW/Calendar_Month.png" width="230" alt="日历月视图"> | <img src="images/zh-TW/Calendar_Day.png" width="230" alt="日历日时间轴"> | <img src="images/zh-TW/Create_Event.png" width="230" alt="创建活动表单"> |

| 闹钟 | AI 助手 | 标签 |
| :---: | :---: | :---: |
| <img src="images/zh-TW/Alarms.png" width="230" alt="闹钟列表"> | <img src="images/zh-TW/AI.png" width="230" alt="AI 助手规划每周运动"> | <img src="images/zh-TW/Tags.png" width="230" alt="彩色标签"> |

| 设置 |
| :---: |
| <img src="images/zh-TW/Settings.png" width="230" alt="设置与外观选项"> |

## 功能

### 日历

- 月和日视图，每周从星期一开始。
- 创建、编辑、删除活动，包含标题、备注、开始/结束时间、标签和可选闹钟。
- 直接在日时间轴上创建活动；拖动草稿块微调开始和结束时间。时间会吸附到可配置的间隔
  （5–60 分钟，默认 15 分钟）。
- 重复活动：每天、每周指定星期几，或重复至指定结束日期。
- 活动提醒：可选 5、10、30 分钟，或自定义 1 到 10080 分钟（最多提前 7 天）的提醒。
- 可编辑或删除单个活动、此活动及所有后续活动，或整个系列。系列编辑会保留各活动的日期，
  仅同步共享字段。

### 闹钟

- 一次性闹钟，以及关联日历活动的闹钟。
- 通过 `AlarmManager` 安排精确闹钟，并在锁屏上显示全屏闹钟。
- 可在响铃界面停止或稍后提醒；稍后提醒默认 10 分钟。
- 可选的 10 秒测试闹钟，以及通知、精确闹钟、全屏 Intent 和电池优化设置的可靠性检查清单。
- 提供 vivo/OriginOS 后台限制的厂商专属指引。
- 重启或应用更新后，会重新安排已启用的闹钟。

### AI 助手

- 自备 **DeepSeek** 或 **Command Code (GOAT)** 的 API 密钥。两者均使用与 OpenAI 兼容的
  Chat Completions API。
- 助手可以列出闹钟和活动、创建/更新/删除两者、创建重复活动，并将闹钟附加到活动。
- 每次写入都会先显示受影响项目的预览；在你确认前不会更改任何内容。
- 对话记录界面保留最近三个对话；超过 24 小时未互动的对话会过期。用量面板显示输入、输出、
  缓存和推理 token。
- 支持获取模型列表、手动输入模型 ID、测试连接和选择推理强度。

### 个性化与数据

- 语言：跟随系统、English 或繁体中文。
- 主题：跟随系统、浅色、深色和 Gruvbox Material。
- 滚轮或时钟时间选择器、可配置的时间轴吸附间隔，以及两种闹钟堆叠动画。
- 活动和闹钟共用彩色标签，每个项目有标签数量上限。
- 可在 **设置 → 数据** 导入或导出日历（`.ics`）文件。导出会把重复活动保留为 `RRULE`；
  导入只会附加活动，不会覆盖现有活动。
- 所有数据都存储在本地 Room 数据库。不涉及账号、分析或 Clockwise 后端。
- Room schema 会导出到 `app/schemas/`，以便审查和测试数据库迁移。

## 界面

| 标签页 | 用途 |
| --- | --- |
| 日历 | 月历网格、日时间轴、活动编辑器、重复范围 |
| 闹钟 | 闹钟列表、倒计时、编辑器、权限/可靠性检查 |
| AI | 对话、变更预览、已保存对话、token 用量 |
| 标签 | 创建和管理彩色标签 |
| 设置 | 语言、主题、时间选择器、吸附间隔、闹钟动画、AI 配置 |

## 环境要求

- 支持 AGP 9.4 的 Android Studio，或命令行 Android SDK 环境
- JDK 17 或更高版本（项目目标为 Java 17）
- Android SDK Platform 37 及对应的 build tools
- 运行 Android 8.0（API 26）或更高版本的设备或模拟器

本应用不依赖 Google Play 服务。

## 构建

使用 Android Studio 打开仓库根目录并让 Gradle 同步。将 Gradle JDK 设置为 17 或更高版本。

从命令行：

```bash
export JAVA_HOME=/path/to/jdk-17-or-newer
export ANDROID_HOME=/path/to/Android/Sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"

./gradlew :app:assembleDebug
```

调试 APK 会输出到 `app/build/outputs/apk/debug/app-debug.apk`。

如果你更喜欢 `local.properties` 文件，可将它指向 SDK，而不用导出 `ANDROID_HOME`：

```properties
sdk.dir=/path/to/Android/Sdk
```

`local.properties` 已被有意加入 git 忽略。

### 发布版构建

发布版会使用 R8 压缩代码并缩减资源。只有在四项签名配置都齐全时才会签名；若缺少任一项，Gradle
会生成未签名的 APK，让没有密钥的环境仍可完成构建。

```bash
export KEYSTORE_FILE=/absolute/path/to/release.keystore
export KEYSTORE_PASSWORD='...'
export KEY_ALIAS='...'
export KEY_PASSWORD='...'

./gradlew :app:assembleRelease
```

`KEYSTORE_FILE` 可以是绝对路径，也可以是相对于仓库根目录的路径。也可以改用同名 Gradle 属性
提供，例如写在 `~/.gradle/gradle.properties`，或使用 `-PKEYSTORE_FILE=...`；属性未提供时会读取
环境变量，最后才读取仓库根目录的 `keystore.properties`（已列入 git 忽略），让本地开发不必每次
构建都导出变量。请勿提交密钥库或其密码（`.gitignore` 已排除 `*.jks`、`*.keystore`、`*.p12`
和 `keystore.properties`）。

配置签名时，APK 会输出到 `app/build/outputs/apk/release/app-release.apk`；否则为
`app/build/outputs/apk/release/app-release-unsigned.apk`。配置签名后，调试版也会使用同一把密钥，
因此本地构建的 APK 可以直接更新已安装的 beta。

### Beta 版

GitHub Actions 会把可安装的 APK 发布成 `clockwise-beta` 产物。它是已签名的发布版，而且每次运行
都使用同一把密钥，所以新版 beta 可以直接覆盖旧版安装。workflow 会读取仓库秘密
`KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS` 和 `KEY_PASSWORD`：

```bash
base64 -w0 release.jks   # 将输出粘贴到 KEYSTORE_BASE64 秘密
```

没有这些秘密时，任务仍会构建、测试和 lint，但不会上传可安装的 APK。调试 APK 不会发布，因为每个
runner 都会用不同的临时密钥签名，这正是更新时出现包冲突的原因。此签名方式之前的 beta 就是用这种
临时密钥签名的，因此首次安装固定密钥签名的版本前，需要先卸载一次。

## 运行

连接设备或模拟器后：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.dinoe.clockwise/.MainActivity
```

发布版会使用 R8 压缩代码；签名方式见[发布版构建](#发布版构建)。调试版不需要任何签名材料，
建议用于本地测试。

## 测试

JVM 单元测试覆盖 AI 工具解析器、提示/上下文构建、token 预算、模型目录、会话序列化，以及与
OpenAI 兼容的客户端（使用 MockWebServer）：

```bash
./gradlew :app:testDebugUnitTest
```

项目目前没有 instrumentation 测试。

## AI 设置

1. 打开 **设置 → AI 助手**。
2. 选择 DeepSeek 或 Command Code，粘贴 API 密钥。
3. 可选刷新模型列表，或手动输入模型 ID。
4. 使用 **测试连接** 验证密钥和模型。
5. 返回 AI 标签页开始对话。

AI 助手为可选功能。没有密钥时，应用的其他部分仍可正常使用。

### 隐私与安全

Clockwise 以本地数据优先。日历活动、闹钟、标签、设置、AI 对话和用量数据都存储在设备上的
Room 数据库和 DataStore 中。Clockwise 没有账号、分析 SDK 或后端服务器。

- API 密钥使用 Android Keystore 中的 AES-GCM 密钥加密后保存。Keystore 密钥无法导出；密钥明文
  仅在发出请求时存在于内存中。
- 使用 AI 助手时，对话消息、提示以及回答所需的日程上下文会通过 HTTPS 直接发送给你选择的服务商，
  密钥会放在请求的 `Authorization` 标头中。这些数据受该服务商的隐私政策约束；Clockwise 不会
  代理请求，也不会保留服务器端副本。
- 对话和用量数据只存储在本地 Room 数据库，不会上传。
- 已禁用云备份和设备间迁移：`allowBackup=false`、旧版完整备份已关闭，Android 12（API 31）
  及以上的数据提取规则会排除所有域。因此数据库和加密后的密钥内容不会通过 Android 备份离开
  设备。Keystore 密钥无法导出，即使复制了密文也无法在其他设备解密。
- 删除对话只会从本地数据库移除，不会删除服务商保留的内容。卸载应用或清除应用数据会永久删除
  本地数据。
- 远程 AI 服务商应使用 HTTPS，让 API 密钥和对话内容在传输过程中保持加密。Clockwise 不会覆盖
  Android 的网络安全策略；本地 HTTP 端点仍受平台的明文流量规则约束，局域网服务可能需要
  HTTPS 反向代理。

## 权限

| 权限 | 用途 |
| --- | --- |
| `POST_NOTIFICATIONS` | 闹钟响铃通知；Android 13+ 需要运行时权限 |
| `SCHEDULE_EXACT_ALARM` | Android 12 及以下的精确闹钟调度 |
| `USE_EXACT_ALARM` | Android 13+ 的精确闹钟调度 |
| `USE_FULL_SCREEN_INTENT` | 锁屏上的全屏闹钟 |
| `RECEIVE_BOOT_COMPLETED` | 重启后恢复已启用的闹钟 |
| `WAKE_LOCK`、`VIBRATE` | 响铃时唤醒设备并振动 |
| `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE` | 保持闹钟响铃服务运行 |
| `INTERNET` | 调用你配置的 AI 服务商 |

本应用不会请求位置、联系人权限，也不会访问系统日历提供程序。

## 项目结构

```
app/src/main/kotlin/com/dinoe/clockwise/
├── MainActivity.kt           # 单 Activity 宿主
├── ClockwiseApp.kt           # 应用组装：数据库、writer、repository
├── data/                     # Room 实体、DAO、重复规则、ScheduleWriter
├── alarm/                    # AlarmManager 调度、receiver、service、notifier
├── ai/                       # 服务商、客户端、提示、工具、解析、密钥存储
└── ui/                       # Compose 界面、view model、主题、选择器
```

- **`ScheduleWriter`** 是闹钟和活动的唯一写入路径，供界面和 AI 助手共用，确保 AI 创建的项目
  与手动创建的一致。
- **`AppDatabase`** 是 Room 数据库（目前 schema 版本 9）。导出的 schema 位于 `app/schemas/`；
  数据库变更时请一并提交新的 schema 文件。
- **`AlarmScheduler`** 负责精确/非精确调度的回退以及重启后的重新调度。
- **`AiTools` / `AiToolParser`** 定义并验证 function-calling 工具。写入工具只会生成提案；
  用户确认后才由 `ScheduleWriter` 执行。
- **`OpenAiChatClient`** 可与任何 OpenAI 兼容的 Chat Completions 端点通信。

## 贡献

欢迎提交 issue 和 pull request。在打开 PR 之前：

1. Kotlin 源代码、注释和 commit 信息请保持英文。
2. 运行 `./gradlew :app:testDebugUnitTest` 和 `./gradlew :app:assembleDebug`。CI
   （`.github/workflows/android.yml`）也会在 push 和 pull request 时运行 `assembleDebug`、
   `testDebugUnitTest` 和 `lintDebug`。
3. 如果修改数据库，请添加 Room schema 文件。
4. 请勿提交 API 密钥、签名密钥、`local.properties` 或构建产物。

## 已知限制

- 日历仅限本地；不会与 Google 日历、CalDAV 或系统日历提供程序同步。
- 没有主屏幕小部件。
- AI 功能依赖外部服务商及其可用性、定价和数据政策。
