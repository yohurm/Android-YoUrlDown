# app/libs

| 文件 | 来源 | 构建方式 |
| --- | --- | --- |
| `youi_kit-1.0.0-94a30da1.aar` | [yohurm/Android-YoUI](https://github.com/yohurm/Android-YoUI) `main` @ `5a475bad` + 本地修复 `94a30da1`（见下） | `./gradlew :youi_kit:assembleRelease`（release：R8 混淆、不内嵌 HarmonyOS Sans 字体） |

升级步骤：在 Android-YoUI 拉到目标提交 → 重新执行上面的命令 → 用新提交号命名替换本文件 →
同步修改 `app/build.gradle` 里的文件名和本表。应用代码只能引用 `com.yohu.youi.api.*`。

## 尚未合入上游的 YoUI 修复

- `patches/youi-behind-plate-capture.patch`（`94a30da1 fix(backdrop): paint only what sits behind an in-tree plate`）：
  控件玻璃（YoSegmentButton 等）采样“背后”时把盖在它上面的悬浮 YoTabs 底栏也画了进去，
  底栏又采样含该控件的内容层，两个 RenderNode 互相引用，切到任务页 / 设置页的第一帧渲染线程递归崩溃（SIGSEGV）。
  在 Android-YoUI 执行 `git am <本文件>` 即可合入；上游合入后按上面的升级步骤换回上游提交号，删除本节和补丁。
