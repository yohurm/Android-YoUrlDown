# Android-YoUrlDown

链接解析与下载的 Android 工程。

## 配置基线

本仓库原名 `Android-YoUrlAnalyse`，已随云端仓库改名为 **`Android-YoUrlDown`**（本地目录、remote、rootProject.name、app 包名同步更新）。构建配置**刻意对齐** `GGEC-Android-EarBuds`，这样两边的构建行为、依赖版本口径、
目录约定一致，迁移代码时不会引入"只有某个工程能编过"的差异。

| 配置项 | 取值 | 说明 |
| --- | --- | --- |
| Android Gradle Plugin | **9.4.1** | 根 `build.gradle` 中 `apply false` 统一声明 |
| Gradle | **9.6.0** | `gradle/wrapper/gradle-wrapper.properties`，wrapper jar 与参考工程一致 |
| DSL | Groovy（`.gradle`） | 与参考工程一致，未使用 Kotlin DSL |
| compileSdk | **`release(37) { minorApiLevel = 2 }`** | AGP 9 的新写法；对应本机已装的 `android-37.2` |
| targetSdk | `release(37)` | app 模块 |
| minSdk | app **29** / 库 **24** | 与参考工程取值一致 |
| Java | **21** | `sourceCompatibility`/`targetCompatibility` |
| core library desugaring | **开启**（`desugar_jdk_libs:2.1.5`） | 根构建脚本对所有 Android 子模块统一开启 |
| 仓库模式 | `FAIL_ON_PROJECT_REPOS` | 依赖仓库只在 `settings.gradle` 声明 |
| 仓库地址 | `google()` + `mavenCentral()` | 插件仓库额外加 `gradlePluginPortal()` |
| `gradle.properties` | `-Xmx2048m -Dfile.encoding=UTF-8`、`android.useAndroidX`、`nonTransitiveRClass` | 与参考工程逐字一致 |
| namespace 约定 | `com.yohu.*` | app：`com.yohu.urldown`；库：`com.yohu.lib_urlparse` |
| 模块命名 | `app` / `lib_*` | 与参考工程的 `lib_common` / `lib_ai` 一致 |

### 与参考工程的有意差异

- **未引入**参考工程的设计系统模块（`yotheme`）与蓝牙/AI/升级等业务模块：本工程暂无这些能力。
- **未移植**参考工程里的自定义校验任务（`checkShellLayerImports`、`checkLibCommonInternalImports` 等）：
  那些规则依赖它的具体包结构。等本工程的包结构稳定后，再按同样的思路补本工程的边界校验。
- 启动图标用矢量占位图，未引入 bitmap 资源与 adaptive icon。

## 模块

| 模块 | 职责 | 状态 |
| --- | --- | --- |
| `app` | 界面、Android 适配、装配 | 三页导航（主页 / 任务 / 设置），基础组件用 YoUI Kit（`app/libs/`，见其 README），应用特有组件在 `ui/widget/` |
| `lib_urlparse` | 解析引擎：契约层 + 调度 + 通道 + 规则 + 判据（纯 JVM 可测，只解析不下载） | 进度见 [docs/design/README.md](docs/design/README.md) |
| `lib_download` | 直链下载引擎：分块 + SQLite 断点（合入自 `lib_common`） | 已合入，由 `app/download/AssetDownloader` 桥接 |

## 构建

需要 JDK 21（AGP 9 要求），本机可用 `D:\Dev\Env\Java\jdk-21`：

```powershell
$env:JAVA_HOME = "D:\Dev\Env\Java\jdk-21"
.\gradlew.bat :app:assembleDebug
```

`local.properties` 里的 `sdk.dir` 指向本机 SDK（`E:\Android\Sdk`），该文件不入库。

## 设计文档

实现前先看设计：

- [docs/design/README.md](docs/design/README.md) —— 索引与摘要
- [01-能力盘点.md](docs/design/01-能力盘点.md) —— 旧工程能力清单（19 个包 / 12 个通道 / 8 个契约 / 8-12 平台实测）
- [02-统一架构.md](docs/design/02-统一架构.md) —— 分层、依赖方向、契约草案、调度模型、边界校验
- [03-lib_urlparse-模块设计.md](docs/design/03-lib_urlparse-模块设计.md) —— 包结构、公开面、测试策略、迁移映射
- [04-迁移计划与未决问题.md](docs/design/04-迁移计划与未决问题.md) —— B0–B8 批次、风险、6 个待决策项
- [05-下载引擎合入.md](docs/design/05-下载引擎合入.md) —— 从 lib_common 摘出下载子系统为 lib_download，及用法
- [06-UI设计.md](docs/design/06-UI设计.md) —— 界面设计依据（参考来源、原则、页面规格、自查表）
- [07-操作流程.md](docs/design/07-操作流程.md) —— 从链接到文件的完整流程、任务状态迁移、后台下载规则

## 状态

- 三模块均可编译：`:app:assembleDebug` 通过（AGP 9.4.1 / Gradle 9.6.0 / JDK 21 / compileSdk 37.2）。
- `lib_urlparse` 三个架构边界校验任务挂在 `check` 上，越界直接构建失败。
- 下载子系统在 `app`：`task/DownloadRunner` 调度，`download/DownloadPipeline` 处理直链（`lib_download`）/ M3U8 / 音视频合并 / 校验。
- 解析通道与真机验证进度以 [docs/verification](docs/verification/) 为准。
- app 界面：底部三页签（YoTabs 悬浮胶囊，随滚动隐藏）。主页是居中的统一搜索栏（YoSearch，内嵌“解析”按钮），粘贴链接或整段分享文字即可识别并解析；
  其他 App 的分享面板里选“解析并下载”也能直接进来。同一链接重复下载会先提醒。
  任务页是卡片列表（缩略图兼平台标识 / 标题 + 进度 + 状态 / 一个主操作），按「全部 / 进行中 / 已结束」筛选，
  卡片上暂停 / 继续 / 重试 / 打开，长按取消 / 分享 / 删除，页菜单全部暂停 / 全部继续。
  下载由前台服务承载（通知栏显示进度、可全部暂停）；断网自动等待、恢复后续传，直链过期自动重新解析。
  设置页含外观、剪贴板、仅 Wi‑Fi 下载、同时下载数（1–3）、保存位置、关于。
  `verifyYouiApiBoundary`（挂在 preBuild）保证 app 只引用 `com.yohu.youi.api.*`。
- 界面截图：`./gradlew :app:testDebugUnitTest --tests '*UiScreenshotTest*'`，
  Robolectric 渲染浅色 / 深色各 10 张到 `app/build/screenshots/`（非真机，仅用于排版自查）。
- 平台图标：`SIMPLE_ICONS=<simple-icons@16 的 icons 目录> python3 tools/icons/gen_platform_icons.py` 重新生成 `res/drawable/ic_platform_*.xml`。
- 任务列表落盘到 `files/tasks.json`；进程被杀时没下完的任务恢复为“已暂停（中断）”，启动后自动继续。