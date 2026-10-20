# `lib_urlparse` 模块设计

> 设计阶段产物。**本文件不包含已实现的代码**；给出的是包结构、公开面、内部约定与验收标准。

## 1. 包结构（确定到包级）

```
com.yohu.lib_urlparse
├── api/                        唯一对外公开面
│   ├── UrlParseEngine          门面：builder / parse / lastTrace
│   ├── UrlParseRequest         请求：rawInput / platformHint / timeout / flags
│   ├── UrlParseResult          结果：platform / channel / workId / title / assets / extras
│   ├── MediaAsset              媒体条目：kind / url / mime / size / headers
│   ├── Author                  作者信息
│   ├── Platform                平台枚举（id + 显示名）
│   ├── ParseTrace              尝试链（结构化证据）
│   └── ParseFailure            失败：reason / channel / message / retryable / userHint
├── spi/                        扩展点（可被外部实现）
│   ├── ParseProvider           一个解析通道
│   ├── ParseContext            通道可用的运行时资源
│   ├── HttpClient               HTTP 抽象
│   ├── JsEvaluator              JS 求值（Android 侧实现）
│   ├── PageSniffer              页面媒体嗅探（Android 侧实现）
│   ├── CredentialProvider       调用方凭据
│   ├── EngineLogger             日志
│   └── SniffOptions / SniffResult
├── core/                       [internal] 引擎编排
│   ├── ProviderRegistry        注册表 + 候选收集 + 优先级排序
│   ├── ParseDispatcher         逐个尝试 + 记录 trace + 兜底策略
│   ├── RequestResolver         短链解析、输入归一化
│   └── ResultAssembler         统一装配与去重
├── fetch/                      [internal] 抓取
│   ├── DefaultHttpClient       java.net 实现（不引 OkHttp）
│   ├── CookieJar               会话 cookie
│   └── HttpHeaders             公共请求头策略
├── sniff/                      媒体识别判据（纯逻辑）
│   ├── MediaExtensions         扩展名白名单
│   └── BlockPageDetector       拦截页识别（防假通过）
├── rules/                      平台规则（纯函数，零 IO、零 Android）
│   └── <platform>/             每平台一组：host 判定、ID 抽取、结构化变量解析
├── provider/                   [internal] 通道实现
│   └── <platform>/             每平台一个或多个 provider
└── util/                       编码、URL、文本、JSON 小工具
```

### 1.1 包可见性约定

- `internal` 标注的包：**不对外承诺**。用 `@RestrictTo(LIBRARY_GROUP)` + 边界任务双向保证。
- `api` / `spi` 之外的包，任何类都不得出现在公开方法签名与公开字段类型上。

## 2. 公开面清单（第一期）

| 类型 | 是否公开 | 说明 |
| --- | --- | --- |
| `api.*` | ✅ | 全部公开，且必须有 javadoc |
| `spi.*` | ✅ | 扩展点，第三方可实现 |
| `core.*` / `provider.*` / `fetch.*` / `util.*` | ❌ | `@RestrictTo` |
| `sniff.*` | ❌ | 判据是内部策略；如需暴露，另开 `spi` |

> 现状把 12 个 provider 与 7 个 sniffer 判据全部公开，导致"哪些能改、哪些不能动"没有边界。新设计收紧。

## 3. 依赖

| 依赖 | 用途 | 约束 |
| --- | --- | --- |
| `androidx.annotation:annotation` | `@Nullable` / `@RestrictTo` | 唯一允许的编译期依赖 |
| `org.json`（测试期） | JSON 解析 | **仅在测试 classpath**；主代码用 Android 自带 `org.json` |
| `junit` / `mockwebserver`（测试期） | 单测 | 不进入主代码 |

**不引入**：OkHttp、Gson、Retrofit、RxJava、Kotlin 协程。理由：现状是零第三方运行时依赖，
这是"可审计"的一部分；引入需单独决策（见 04 文档未决项）。

## 4. 线程与并发约定

- 引擎所有方法：**同步、在当前线程完成**，不创建线程池。
- 库不下载：下载、合并、落盘与并发都在 `app`（见 02 §9、07）。
- `JsEvaluator` / `PageSniffer`：**线程亲和**。契约要求"创建与调用必须在同一 Looper 线程"；
  `ParseContext` 注入时做一次身份校验（记录创建线程，跨线程调用抛 `IllegalStateException`）。
- 取消：`CancelToken` 由调用方创建并贯穿；取消是协作式的，每次网络请求 / JS 求值前检查一次。

## 5. 测试策略（G1 的落地方式）

| 层次 | 测试方式 | 目标 |
| --- | --- | --- |
| `rules/*` | 纯 JVM 单测 + 真实抓取固件（HTML/JSON 样本） | 覆盖每个平台的字符串解析分支 |
| `sniff/*` | 纯 JVM 单测 | 覆盖扩展名判据与拦截页识别 |
| `fetch/*` | `mockwebserver` | 覆盖重定向、cookie、超时、错误码 |
| `provider/*` | 纯 JVM 单测（provider 只依赖 `spi`，可注入假 HTTP/假求值器） | **这是新设计带来的关键收益**：provider 不再需要 WebView 才能测 |
| `core/*` | 纯 JVM 单测（注册表排序、调度、trace 记录、兜底） | 调度语义可测 |
| Android 适配器 | `app` 的 instrumented 测试 | WebView 求值、DASH 合并、落盘 |

> 现状 268 个引擎单测里，凡是需要 WebView 的路径只能靠真机；新设计把 provider 与核心全部
> 解耦到 JVM 可测，**单测数量应显著上升而真机依赖显著下降**。

## 6. 与 `app` 的边界

`app` 负责三件事，且仅此三件与引擎相关：

```java
// 1) 提供 Android 侧适配器
JsEvaluator    jsEval    = new WebViewJsEvaluator(context);
PageSniffer    sniffer   = new WebViewPageSniffer(context);

// 2) 装配（唯一入口，保证各入口配置一致）
UrlParseEngine engine = UrlParseEngine.builder()
        .httpClientFactory(DefaultHttpClient::new)
        .service(JsEvaluator.class, jsEval)
        .service(PageSniffer.class, sniffer)
        .credentialProvider(appCredentials)     // 默认"未配置"
        .logger(appLogger)
        .build();

// 3) 使用
UrlParseResult r = engine.parse(UrlParseRequest.builder(shareText).build());
```

`app` **不得**：解析 HTML/JSON、拼下载 URL。这些都在库里。
下载相关的判断（命名、扩展名白名单、容器校验）属于下载子系统，在 `app/download/`。

## 7. 迁移映射（旧 → 新，仅结构对照）

| 旧（`com.dlparse.engine`） | 新（`com.yohu.lib_urlparse`） | 处理 |
| --- | --- | --- |
| `ParseEngine` / `EngineConfig` / `ParseRequest` | `api.UrlParseEngine` / `api.*` | 重命名 + 收紧公开面 |
| `spi.*` | `spi.*` | 基本沿用，补 `CredentialProvider` |
| `model.ParseResult` / `MediaItem` | `api.UrlParseResult` / `api.MediaAsset` | 改名，字段语义保留 |
| `rules.*`（11 个） | `rules.<platform>.*` | 按平台归组；纯函数化 |
| `provider.*`（12 个） | `provider.<platform>.*` | 保持实现，改依赖为 `spi`+`fetch` |
| `sniffer.*` | `sniff.*` | 判据集中，其它模块只调用 |
| `http.*` | `fetch.*` | 保留 `java.net` 实现 |
| `download.*` | `app/download/*` | 下载整体移出解析库：直链走 `lib_download`，M3U8 / DASH 合并 / 命名 / 容器校验在 `app` |
| `util.MediaVerifier` | `app` | 依赖系统框架，外置 |
| `js.WebView*` | `app` 适配器 | 外置，实现 `spi` |
| `js.RhinoJsEvaluator` | `spi` 的实现之一（可选） | 若保留则需引入 Rhino 依赖，需决策 |

## 8. 验收标准（模块完成时）

1. `:lib_urlparse:test` 全绿，且**不启动 Android**（无 Robolectric）。
2. `:app:testDebugUnitTest` 覆盖适配器以外的装配逻辑。
3. `checkNoAndroidFrameworkImports` / `checkLayerBoundary` / `checkNoSecrets` 全部通过。
4. 迁移后对 8 个已验证平台，在真机上仍能达到 `解析 → 下载 → 校验` 闭环（沿用 `PLATFORM_COVERAGE.md` 口径）。
5. 未通过/未验证的 4 个平台，报告中如实标注原因，不用"看起来能跑"替代证据。
