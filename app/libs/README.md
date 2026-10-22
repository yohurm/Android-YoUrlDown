# app/libs

| 文件 | 来源 | 构建方式 |
| --- | --- | --- |
| `youi_kit-1.0.0-625b0320.aar` | [yohurm/Android-YoUI](https://github.com/yohurm/Android-YoUI) `main` @ `625b0320` | `./gradlew :youi_kit:assembleRelease`（release：R8 混淆、不内嵌 HarmonyOS Sans 字体） |

升级步骤：在 Android-YoUI 拉到目标提交 → 重新执行上面的命令 → 用新提交号命名替换本文件 →
同步修改 `app/build.gradle` 里的文件名和本表。应用代码只能引用 `com.yohu.youi.api.*`。
