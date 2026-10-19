# 真机复验矩阵（自动生成）

> 生成时间：2026-10-19 15:00　设备：NEPI4E0106　脚本：`tools/device-matrix.ps1`
> 判定原则：只记录实测结果；缺链接的平台标「未验证」，不推断、不填空。

| 平台 | 输入 | 通道 | 结果 | 媒体/失败详情 |
| --- | --- | --- | --- | --- |
| 哔哩哔哩 | `https://www.bilibili.com/video/BV1HU4y1K7TT` | `bilibili.api` | ✅ 通过 | 视频 1 / 图片 0 / 音频 1 |
| 今日头条 | `https://m.toutiao.com/i7035542323689685535/` | `toutiao.webpage` | ✅ 通过 | 视频 1 / 图片 0 / 音频 0 |
| 微信视频号 | `https://channels.weixin.qq.com/web/pages/feed?x=1` | `wechat.channels` | ❌ 失败（CREDENTIALS_MISSING） | WeChat Channels extraction needs a logged-in Tencent/WeChat session: the public official interface is session-bound, and |
| 快手 | `https://v.kuaishou.com/abcdef` | `sniffer.webview` | ❌ 失败（NO_MEDIA_FOUND） | 只嗅到 13 张图片、没有视频/音频，且该平台作品主体不是图片：疑似验证页/错误页上的头像与图标，不当作解析成功。 |
| Instagram | `https://www.instagram.com/p/Ca3V3zGIZ08/` | `instagram.embed` | ❌ 失败（NETWORK） | Instagram 主机不可达或请求失败（旧报告里该平台也是网络层原因未通过）: HttpException{TIMEOUT, status=0, timeout after 20000ms: https://www.instagram.c |
| 抖音 | — | — | ⬜ 未验证（缺有效分享链接） | — |
| 小红书 | — | — | ⬜ 未验证（缺有效分享链接） | — |
| 微博 | — | — | ⬜ 未验证（缺有效分享链接） | — |
| 微视 | — | — | ⬜ 未验证（缺有效分享链接） | — |
| 皮皮虾 | — | — | ⬜ 未验证（缺有效分享链接） | — |
| 微信公众号 | — | — | ⬜ 未验证（缺有效分享链接） | — |

## 如何补齐

把有效分享链接填进 `tools/device-matrix.ps1` 的 `System.Collections.Hashtable System.Collections.Hashtable System.Collections.Hashtable System.Collections.Hashtable System.Collections.Hashtable System.Collections.Hashtable System.Collections.Hashtable System.Collections.Hashtable System.Collections.Hashtable System.Collections.Hashtable System.Collections.Hashtable` 后重跑即可：
抖音图集、小红书（含 `xsec_token`）、微博 status、微视、皮皮虾、公众号文章都需要**当前有效**的链接——
这些平台的分享链接有时效，旧报告里的样本不能复用。