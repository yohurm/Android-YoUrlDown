# Android-YoUrlDown

链接解析与下载的 Android 工程（骨架阶段）。

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

| 模块 | 职责 |
| --- | --- |
| `app` | 界面与应用装配（当前只有一个占位首页） |
| `lib_urlparse` | 解析引擎模块，**当前为空**，见 [lib_urlparse/README.md](lib_urlparse/README.md) |

## 构建

需要 JDK 21（AGP 9 要求），本机可用 `D:\Dev\Env\Java\jdk-21`：

```powershell
$env:JAVA_HOME = "D:\Dev\Env\Java\jdk-21"
.\gradlew.bat :app:assembleDebug
```

`local.properties` 里的 `sdk.dir` 指向本机 SDK（`E:\Android\Sdk`），该文件不入库。

## 设计文档

实现前先看设计（当前阶段**只有设计，没有业务代码**）：

- [docs/design/README.md](docs/design/README.md) —— 索引与摘要
- [01-能力盘点.md](docs/design/01-能力盘点.md) —— 旧工程能力清单（19 个包 / 12 个通道 / 8 个契约 / 8-12 平台实测）
- [02-统一架构.md](docs/design/02-统一架构.md) —— 分层、依赖方向、契约草案、调度模型、边界校验
- [03-lib_urlparse-模块设计.md](docs/design/03-lib_urlparse-模块设计.md) —— 包结构、公开面、测试策略、迁移映射
- [04-迁移计划与未决问题.md](docs/design/04-迁移计划与未决问题.md) —— B0–B8 批次、风险、6 个待决策项

## 状态

- 工程可编译：`:app:assembleDebug` 通过（AGP 9.4.1 / Gradle 9.6.0 / JDK 21 / compileSdk 37.2）。
- `lib_urlparse` 目前是**空模块**（只有构建配置与设计）。
- 解析能力**尚未接入**：首页点「解析」只提示引擎未接入，不伪造结果。