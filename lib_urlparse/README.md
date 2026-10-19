# lib_urlparse

链接解析引擎模块（当前为**空模块**，只有构建配置与约定）。

## 职责边界

- 只放**与界面无关**的解析能力：平台识别、规则匹配、HTTP 抓取、JSON 提取、媒体地址产出。
- 不依赖 `app` 的任何类型；不声明权限（权限由消费方 `app` 声明，与参考工程 `lib_common` 的
  manifest 归属约定一致）。
- 不内置任何第三方私有服务或凭据；需要凭据的通道一律由调用方注入。

## 为什么先立空模块

后续会把已在 `DownLoadAnalysis` 里验证过的解析引擎（平台 provider + 规则 + 下载核心）迁入此处。
先立模块有两个好处：迁移的每一批代码都有确定的落点；且构建配置（compileSdk 37.2 / Java 21 /
desugaring / 单测 classpath 处理）在写第一行业务代码之前就已经被验证可用。

## 构建配置约定（与 GGEC-Android-EarBuds 对齐）

| 项 | 取值 |
| --- | --- |
| AGP | 9.4.1（根 `build.gradle` 统一声明） |
| compileSdk | `release(37) { minorApiLevel = 2 }` |
| minSdk | 24（库比 app 低，由宿主决定下限） |
| Java | 21（`JavaVersion.VERSION_21`），并启用 core library desugaring |
| 单测 classpath | 剔除 `android.jar`/`mockable-android`，用 Maven 上的真实 `org.json` |

## 下一步

1. 迁入解析引擎的 API 骨架（抛点、结果模型、provider 接口）。
2. 迁入各平台 provider 与规则，并为其补单测（引擎侧应尽可能做到纯 JVM 可测）。
3. 再接入 `app` 的界面。
