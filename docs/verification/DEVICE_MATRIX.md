# 真机验证记录（NEPI4E0106 / Android 15）

> 判定原则：**只记录实测到的结果**。没测的写"未测"，失败的写失败原因，不做推断填空。
> 本轮验证的是"引擎在设备上能否端到端跑通"，不是"12 平台全部达标"。

## 环境

| 项 | 值 |
| --- | --- |
| 设备 | motorola edge 60 pro（`NEPI4E0106`），Android 15 |
| 安装包 | `app-debug.apk`（`adb install -r` Success） |
| 驱动方式 | `adb shell am start -n com.yohu.urldown/.MainActivity -e url "<链接>"`（应用支持 intent extra，便于脚本化验证） |

## 结果

| 平台 | 输入 | 通道 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| 哔哩哔哩 | `BV1HU4y1K7TT` | `bilibili.api` | ✅ 通过 | 标题「能力觉醒！颠覆四皇的极恶时代…」、作者「秃头的白小明」、视频 852×480 / 1700s、备用地址 2 条、音频 1 条 |
| 今日头条 | `m.toutiao.com/i7035542323689685535/` | `toutiao.webpage` | ✅ 通过 | 标题「"直接当场就社会性死亡了！"」、作者「阿浪171」、视频 1 条 |
| 快手 | `v.kuaishou.com/abcdef` | `sniffer.webview` | ❌ 失败 | `NO_MEDIA_FOUND`（修复假通过后如实失败，见下） |
| Instagram | `/p/Ca3V3zGIZ08/` | — | ⚠️ 未测出结论 | 26s 内界面无结果文本（WebView 加载 + 网络不可达，等待超时） |

## 本轮发现并修复的真实缺陷：嗅探通道的"假通过"

**现象**：快手链接被 `sniffer.webview` 报成"成功，12 张图片"。那 12 张是页面头像/图标，不是作品。

**为什么危险**：假通过比失败危害大——失败会暴露，假通过会写进结论。

**根因**（两个叠加）：
1. `WebViewPageSniffer` 没有把**页面标题**带回 `SniffResult`，导致 `BlockPageDetector` 无标题可判；
2. `SnifferProvider` 只要嗅到任何媒体就报成功，"只有图片"这种形态没有防线。

**修复**：
1. 嗅探器在 `onPageFinished` 后用 `document.title` 取标题并写入结果；
2. 嗅探通道增加防线：**只有图片、没有视频/音频，且该平台作品主体不是图片**时，判 `NO_MEDIA_FOUND` 并说明"疑似验证页/错误页上的头像与图标"；
3. 补两条回归测试：快手形态必须失败、小红书（图集类）必须照收。

修复后真机重测：快手 → `解析失败 / NO_MEDIA_FOUND / sniffer.webview` ✅ 符合预期。

## 尚未验证（如实标注）

| 平台 | 原因 |
| --- | --- |
| 小红书 / 微博 / 抖音 / 微视 / 皮皮虾 / 公众号 | 本轮未测：缺少可用的真实分享链接（抖音图集、小红书 xsec_token、微博 status id 均有有效期） |
| 快手 | 旧报告为 `antispam need captcha`；本轮表现为"验证页/无可下载媒体"，未拿到视频 |
| Instagram | 主机不可达（旧报告同因）；本轮 26s 内未出结果，需更长超时或独立网络环境再测 |
| 微信视频号 | 需 root / 登录态，未验证 |
| 趣头条 | 无可用网页作品页，未验证 |

## 复用方式

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.yohu.urldown/.MainActivity -e url "https://www.bilibili.com/video/BV1HU4y1K7TT"
sleep 15
adb shell uiautomator dump /sdcard/ui.xml && adb shell cat /sdcard/ui.xml
```