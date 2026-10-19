# 真机复验矩阵（可复现）
#
# 用途：把「12 平台复验」变成一条可重跑的命令，输出一张 markdown 表格。
# 判定原则：只记录实测结果；没有可用链接的平台写「未验证（缺有效链接）」，不推断、不填空。
#
# 用法（在仓库根目录）：
#   pwsh -File tools/device-matrix.ps1
#   pwsh -File tools/device-matrix.ps1 -SkipInstall
#
# 说明：应用支持 intent extra 带入链接（-e url "<链接>"），因此无需手点 UI。

param(
    [switch]$SkipInstall,
    [string]$Adb = "D:\platform-tools\adb.exe",
    [string]$OutFile = "docs/verification/MATRIX_RUN.md"
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot

# 用例：平台名 / 输入 / 是否可测（缺链接的写 $null）
$cases = @(
    @{ Platform = "哔哩哔哩";   Url = "https://www.bilibili.com/video/BV1HU4y1K7TT" },
    @{ Platform = "今日头条";   Url = "https://m.toutiao.com/i7035542323689685535/" },
    @{ Platform = "微信视频号"; Url = "https://channels.weixin.qq.com/web/pages/feed?x=1" },
    @{ Platform = "快手";       Url = "https://v.kuaishou.com/abcdef" },
    @{ Platform = "Instagram";  Url = "https://www.instagram.com/p/Ca3V3zGIZ08/" },
    @{ Platform = "抖音";       Url = $null },
    @{ Platform = "小红书";     Url = $null },
    @{ Platform = "微博";       Url = $null },
    @{ Platform = "微视";       Url = $null },
    @{ Platform = "皮皮虾";     Url = $null },
    @{ Platform = "微信公众号"; Url = $null }
)

function Dump-Ui {
    & $Adb shell uiautomator dump /sdcard/ui.xml 2>&1 | Out-Null
    return ((& $Adb shell cat /sdcard/ui.xml 2>&1) -join "")
}

function Unescape([string]$s) {
    return $s.Replace("&#10;", "`n").Replace("&amp;", "&").Replace("&quot;", '"')
}

if (-not $SkipInstall) {
    $apk = Get-ChildItem (Join-Path $repo "app\build\outputs\apk\debug\*.apk") | Select-Object -First 1
    if (-not $apk) { throw "未找到 APK，请先运行 :app:assembleDebug" }
    $r = & $Adb install -r $apk.FullName 2>&1 | Select-Object -Last 1
    Write-Host "安装: $r"
}

$rows = @()
foreach ($c in $cases) {
    $platform = $c.Platform
    if (-not $c.Url) {
        $rows += "| $platform | — | — | ⬜ 未验证（缺有效分享链接） | — |"
        Write-Host "[$platform] 未验证（缺链接）"
        continue
    }
    & $Adb shell am force-stop com.yohu.urldown 2>&1 | Out-Null
    & $Adb shell am start -n com.yohu.urldown/.MainActivity -e url $c.Url 2>&1 | Out-Null
    # 轮询到结果出现为止（最多 ~36s）：固定 sleep 会因页面/网络快慢而误判"无结论"，
    # 这在视频号那次已经发生过一次（实际是 CREDENTIALS_MISSING，却被记成无结论）。
    $xml = ""
    for ($i = 0; $i -lt 12; $i++) {
        Start-Sleep -Seconds 3
        $xml = Dump-Ui
        if ($xml -match 'text="(平台:|解析失败)') { break }
    }
    $ok = [regex]::Match($xml, 'text="(平台:[^"]{10,})"')
    if ($ok.Success) {
        $text = Unescape $ok.Groups[1].Value
        $lines = $text -split "`n"
        $platformShown = ($lines | Where-Object { $_ -like "平台:*" }) -replace "平台:\s*", ""
        $channel = ($lines | Where-Object { $_ -like "通道:*" }) -replace "通道:\s*", ""
        $media = ($lines | Where-Object { $_ -like "媒体:*" }) -replace "媒体:\s*", ""
        $rows += "| $platform | ``$($c.Url)`` | ``$channel`` | ✅ 通过 | $media |"
        Write-Host "[$platform] 通过 通道=$channel 媒体=$media"
        continue
    }
    $fail = [regex]::Match($xml, 'text="(解析失败[^"]{10,2000})"')
    if ($fail.Success) {
        $text = Unescape $fail.Groups[1].Value
        $reason = ([regex]::Match($text, "原因:\s*(\w+)")).Groups[1].Value
        $channel = ([regex]::Match($text, "通道:\s*([^\n]+)")).Groups[1].Value.Trim()
        $detail = ([regex]::Match($text, "详情:\s*([^\n]{0,120})")).Groups[1].Value.Trim()
        $rows += "| $platform | ``$($c.Url)`` | ``$channel`` | ❌ 失败（$reason） | $detail |"
        Write-Host "[$platform] 失败 $reason / $channel"
        continue
    }
    $rows += "| $platform | ``$($c.Url)`` | — | ⚠️ 无结论（22s 内无结果，可能超时/网络不可达） | — |"
    Write-Host "[$platform] 无结论"
}

$stamp = Get-Date -Format "yyyy-MM-dd HH:mm"
$md = @()
$md += "# 真机复验矩阵（自动生成）"
$md += ""
$md += "> 生成时间：$stamp　设备：NEPI4E0106　脚本：``tools/device-matrix.ps1``"
$md += "> 判定原则：只记录实测结果；缺链接的平台标「未验证」，不推断、不填空。"
$md += ""
$md += "| 平台 | 输入 | 通道 | 结果 | 媒体/失败详情 |"
$md += "| --- | --- | --- | --- | --- |"
$md += $rows
$md += ""
$md += "## 如何补齐"
$md += ""
$md += "把有效分享链接填进 ``tools/device-matrix.ps1`` 的 ``$cases`` 后重跑即可："
$md += "抖音图集、小红书（含 ``xsec_token``）、微博 status、微视、皮皮虾、公众号文章都需要**当前有效**的链接——"
$md += "这些平台的分享链接有时效，旧报告里的样本不能复用。"

$outPath = Join-Path $repo $OutFile
New-Item -ItemType Directory -Force -Path (Split-Path $outPath) | Out-Null
[System.IO.File]::WriteAllText($outPath, ($md -join "`n"), (New-Object System.Text.UTF8Encoding($false)))
Write-Host ""
Write-Host "已写入: $outPath"
