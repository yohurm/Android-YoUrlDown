# 真机验证记录（NEPI4E0106 / Android 15）

> **本文件是人工结论；逐条实测数据以自动产物 [`MATRIX_RUN.md`](MATRIX_RUN.md) 为准。**
> 两者冲突时以 `MATRIX_RUN.md` 为准（它是脚本直接读设备界面得到的，不经人手）。
>
> 判定原则：**只记录实测到的结果**。没测的写「未验证」，失败的写具体原因，不推断、不填空、
> 不用旧报告的结论替新实现背书。

## 1. 环境

| 项 | 值 |
| --- | --- |
| 设备 | motorola edge 60 pro（`NEPI4E0106`），Android 15 |
| 安装包 | `app-debug.apk`（`adb install -r` Success） |
| 驱动方式 | `adb shell am start -n com.yohu.urldown/.MainActivity -e url "<链接>"` |
| 复现命令 | `pwsh -File tools/device-matrix.ps1`（自动安装、逐条验证、生成矩阵） |

## 2. 当前结论（已测 5 项：2 通过 / 3 失败；另 6 个平台未验证）

| 平台 | 通道 | 结果 | 说明 |
| --- | --- | --- | --- |
| 哔哩哔哩 | `bilibili.api` | ✅ 通过 | 标题/作者/视频 852×480/1700s/备用地址 2 条/音频 1 条 |
| 今日头条 | `toutiao.webpage` | ✅ 通过 | 标题/作者/视频 1 条 |
| 微信视频号 | `wechat.channels` | ❌ `CREDENTIALS_MISSING` | 取流必须持有腾讯侧登录会话，且媒体是加密 MP4（需 `decode_key`） |
| 快手 | `sniffer.webview` | ❌ `NO_MEDIA_FOUND` | 只嗅到 13 张图片、无视频/音频，判为验证页上的头像/图标 |
| Instagram | `instagram.embed` | ❌ `NETWORK` | 请求 20s 超时，主机不可达（与旧报告同一原因） |
| 抖音 / 小红书 / 微博 / 微视 / 皮皮虾 / 公众号 | — | ⬜ 未验证 | **缺当前有效分享链接**（分享链接有时效，旧样本不能复用） |

## 3. 本轮发现并修复的真实缺陷（都有回归测试）

### 3.1 嗅探通道「假通过」

- **现象**：快手链接被 `sniffer.webview` 报成"成功，12 张图片"，实为头像/图标。
- **根因**：① `WebViewPageSniffer` 没带回页面标题 → `BlockPageDetector` 无标题可判；
  ② `SnifferProvider` 只要嗅到任何媒体就报成功。
- **修复**：取 `document.title` 写入结果；只有图片且平台非图集类时判 `NO_MEDIA_FOUND`。
- **回归测试**：快手形态必须失败、小红书（图集类）必须照收。

### 3.2 下载点一下就崩

- **现象**：点「下载到应用目录」后应用进程消失。
- **根因**：`AssetDownloader.init(...)` 从未调用 → `YoDownload.enqueue` 失败 →
  后台线程异常杀死进程。
- **修复**：`onCreate` 初始化下载引擎；下载流程整体 `try/catch`，异常变成界面提示。
- **验证**：头条视频 26,215,444 B 落盘，容器校验 `MP4` ✅。

### 3.3 通道写了但没注册

- **现象**：视频号链接被 `sniffer.webview` 接走，报 `NO_MEDIA_FOUND`。
- **根因**：`wechat.mp` / `wechat.channels` / `cloud.api` 未注册进 `EngineBootstrap`
  ——**单测全绿，设备上没有**。
- **修复**：注册后真机实测视频号 → `wechat.channels` → `CREDENTIALS_MISSING`。

### 3.4 测量缺陷（同样会污染结论）

- 矩阵脚本固定 `sleep 22s` → 视频号、Instagram 被误记为"无结论"；改为**轮询到结果出现**。
- 失败文本正则上限 400 → 视频号的长英文详情匹配失败；放宽到 2000。

## 4. 未验证项与补齐方式

| 平台 | 需要什么 |
| --- | --- |
| 抖音 | 一条视频 + 一条图集分享链接（可同时验证 `douyin.webpage` 与 `douyin.webview.js`） |
| 小红书 | 带 `xsec_token` 的 `explore` 链接 |
| 微博 | `m.weibo.cn/status/...` 或桌面 status 链接 |
| 微视 / 皮皮虾 | 当前有效的分享链接（目前由 `sniffer.webview` 覆盖，未写独立直解通道） |
| 公众号 | 一篇含视频的文章链接（`wechat.mp` 已实现，缺真实固件与真机链接） |

拿到链接后只需把它们填进 `tools/device-matrix.ps1` 的 `$cases` 并重跑：

```powershell
pwsh -File tools/device-matrix.ps1
```

## 5. 与旧报告的口径差异（如实）

旧报告：8 通过 / 2 未通过 / 2 未验证。
本轮：2 通过 / 3 失败 / 6 未验证。

差异**不是**"新实现变差了"，而是：

1. 本轮只测了有链接的 5 个条目，其余 6 个平台缺链接无法测；
2. 快手、Instagram 的失败原因与旧报告一致（验证码/网络），只是措辞更具体；
3. 视频号现在有独立通道并给出明确原因（旧报告为"未验证，需 root/登录"）。

要达到旧报告口径的 8 通过，必须先补齐第 4 节的链接——这是输入缺口，不是代码缺口。
