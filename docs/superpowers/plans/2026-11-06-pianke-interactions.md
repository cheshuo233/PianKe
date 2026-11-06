# 片刻 交互迭代 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal**：在阶段一的 App 上加四项交互：弧形换卷抽屉、触摸对焦、仪式感三档、变焦方式与新变焦条。

**Architecture**：
- 不新增模块，包依赖方向不变。
- 新的纯逻辑只有 `camera/ZoomScale.kt`。
- 其余改动都在 `ShooterViewModel` 的编排和 `ui` 的几个组件里。
- 先一次性提交整轮的骨架，由 Opus 审组织结构；之后按功能逐块填函数体，每块各自可验收。

**Tech Stack**：Kotlin、Jetpack Compose（动画用 `Animatable`、`splineBasedDecay`、spring）、CameraX 1.6.2（`FocusMeteringAction`、`DisplayOrientedMeteringPointFactory`）、DataStore Preferences、VibrationEffect 原语。

**Spec**：`docs/superpowers/specs/2026-11-06-pianke-interactions-design.md`。下文的「§n」「第 n 条」都指这份 spec。阶段一的 spec 继续有效。

## 全局约束

- 分支：`interactions`，spec 已经提交在上面。不碰 `main`，推送前先问用户。
- 不新增任何依赖，不下载任何东西。用到的 API 都在现有的 CameraX、Compose、DataStore 里。
- SDK：minSdk 31，compileSdk 37，targetSdk 36（取 `gradle/libs.versions.toml` 里的值）。Kotlin 开着 `allWarningsAsErrors = true`。
- 包依赖方向同阶段一 §2，`camera` 不得依赖 `feedback`、`ui`、`develop`。
- K6：只新增 `ritual`、`zoomMode` 两个键，按枚举的 `name` 存；原有键名不动。
- 文案逐字使用 spec 里的中文，放在 `res/values/strings.xml`：
  - `仪式感`、`关`、`中`、`高`；
  - `变焦方式`、`档位`、`任意`；
  - `REWIND`、`装一卷新胶卷`。
- 颜色、尺寸、几何以 §9 的数值为准，其次以 `docs/design/interactions/` 的两张效果图为准。
- 测试：不要求 TDD。只新增一个 JVM 单测 `ZoomScaleTest`，不新增插桩测试。其余都由任务 7 的真机清单验证。
- 每个规则只落在 §6 指定的那一处，不得在别处再算一遍。最容易重复的几处：
  - mm↔倍率；
  - 当前档位的推导；
  - cue 的选择；
  - 抽屉状态转换。
- Gradle 命令在 PowerShell 里执行，先设 `$env:JAVA_HOME = 'D:\Tools\Android Studio\jbr'`。
- 闸口：`pwsh -NoProfile -File scripts\check.ps1`，最后一行必须是 `check passed`。

## 执行方式（按全局 CLAUDE.md）

- 每个任务派一个 Sonnet 实施者，按顺序执行，不并行。
- 任务 1 是整轮的骨架，提交后由一个 Opus 只审组织：
  - 规则是否只在一处（对照 §6）；
  - 依赖方向是否正确；
  - 有没有用不上的抽象。

  审过、按意见改完，才开始任务 2。
- 任务 2–6 填函数体，每个任务只填自己那块，不做逐任务评审，验收看任务里的命令。
- 任务 7 需要用户在真机上操作。HyperOS 拦截 `input` 注入，安装 APK 时也要用户在手机上点确认。
- 全部完成后做收尾双审，见文末。

## Review Focus（收尾评审必须逐条核对；每条都已落到所属任务的真机验收里）

1. **抽屉惯性转动中再次触摸**：手指一按下，惯性立即停止，和列表一样；这时点在某张标签上，就选中那一卷，不会被吸附动画带偏到别的卷。归任务 4。
2. **在第 36 张附近连按快门**：倒片期间的按压全部无声忽略，不播 REFUSE；冲洗队列的占位不泄漏；计数只从 36 变成 1 一次。归任务 5。
3. **倒片中切到后台再回来**：
   - MEDIUM：回来时倒片已经结束，快门可用；
   - HIGH：回来时抽屉处于 REQUIRED，关不掉。

   归任务 5。
4. **任意模式拖过范围两端**：倍率停在端点，不越界；到达端点时播一次 NOTCH，继续拖不再重复 `setZoom` 和 cue。归任务 3。
5. **在任意焦段（如 42 mm）把变焦方式切回档位**：变焦吸到对数距离最近的档位（42 → 35），不播 cue；小牌、变焦条、取景三者一致。归任务 3。

---

### 任务 1：整轮骨架

**文件**
- 修改：
  - `settings/Settings.kt`：新增枚举 `Ritual`、`ZoomMode`，以及两个字段（§3）；
  - `feedback/Feedback.kt`：新增 `Cue.TICK`，`effect` 的分支先写 `TODO()`；
  - `camera/ZoomStops.kt`：删掉 `MAIN_MM`，`BACK` 改用 `ZoomScale.ratio(mm)` 算倍率；
  - `camera/CameraController.kt`：
    - `setZoom(ratio: Float)` 只改签名，函数体可以直接改好；
    - 新增 `focusAt`，函数体先写 `TODO()`；
  - `camera/PreviewRenderer.kt`：
    - 新增 `viewToFrame`，函数体先写 `TODO()`；
    - 新增两个 `@Volatile` 字段，存放裁切系数 `sx`、`sy`。
- 新建：
  - `camera/ZoomScale.kt`：`Tick` 和 `ZoomScale`；
  - `ui/ZoomStrip.kt`；
  - `ui/FilmDrawer.kt`。
- 修改 ui：
  - `ui/ShooterUiState.kt`：`Drawer`、新字段、`REWIND_MS`、三个推导属性；
  - `ui/ShooterViewModel.kt`：§4 的全部新函数；删除 `selectStop`；`drive` 改为同时记住 renderer；
  - `ui/CameraScreen.kt`：接上 `ZoomStrip` 和 `FilmDrawer`（`drawer != CLOSED` 时叠在最上层）；
  - `ui/TopBar.kt`、`ui/LcdPanel.kt`、`ui/FilmRow.kt`：按 §4 加参数；
  - `ui/MenuScreen.kt`：「拍摄」组加两行，函数体先写 `TODO()`。
- 删除：`ui/ZoomRing.kt`。
- 修改：`res/values/strings.xml`，加入全局约束里列的全部文案，以及抽屉标签的无障碍描述。

**落实**：§2、§3、§4 的全部类型和签名。

**做法**
- 新函数的函数体一律写 `TODO()`。
- 只改签名的旧函数（`setZoom`、`selectFilm` 等）可以直接改好。
- 不写任何新的逻辑。

**验收**
- [ ] `.\gradlew :app:assembleDebug :app:lintDebug :app:compileDebugUnitTestKotlin`：`BUILD SUCCESSFUL`。骨架里 `ZoomScale` 是 `TODO()`，所以本任务不跑单测。
- [ ] `git grep -n "ZoomRing\|selectStop\|stopIndex" app/src`：没有输出。
- [ ] 提交 `app: skeleton for the interactions iteration`。
- [ ] Opus 骨架评审通过，按意见修改后再提交一次。

### 任务 2：下层：`settings`、`feedback`、`ZoomScale`

**文件**
- 修改：
  - `settings/SettingsStore.kt`：新增键 `ritual`、`zoomMode`，读写方式和现有的枚举键一样，值无法识别时回落到默认值；
  - `feedback/Feedback.kt`：`TICK` 的震动按 §9.7，没有声音；
  - `camera/ZoomScale.kt`：填全部函数体。
- 新建：`app/src/test/java/app/pianke/camera/ZoomScaleTest.kt`。

**落实**：§1 K6、§6 第 17′、26、27 条和 `nearest`、§9.7。

**`ZoomScaleTest`**
- 用例照 §10 的表，7 行，每行一个断言。
- 用表驱动写，不另加用例。

**验收**
- [ ] `pwsh -NoProfile -File scripts\check.ps1`：`ZoomScaleTest` 和现有的 `ZoomStopsTest` 都通过；最后一行是 `check passed`。
- [ ] 提交 `app: ritual and zoom-mode settings, tick cue, zoom scale`。

### 任务 3：变焦

**文件**
- 修改：
  - `ui/ShooterUiState.kt`：`focalMm`、`stop`；
  - `ui/ShooterViewModel.kt`：
    - `selectZoom`；
    - `drive` 里绑定后复位到 `zoomRatio = 1f`（第 32 条）；
    - `drive` 里新增一个按 `zoomMode` 去重的收集：切到 STOPS 时，按第 31 条吸到最近一档；
  - `ui/ZoomStrip.kt`：§9.2 的外观和手感；
    - mm 与倍率之间的换算只调用 `ZoomScale.ratio` / `ZoomScale.mm`：鼓的精确位置用 `ZoomScale.mm(ratio)`，`focalMm` 只用于左侧读数；
  - 骨架评审的裁决 S1：「在档位上」只在一处判断：
    - `ShooterUiState.stop` 先按倍率相等找，找不到再按 mm 等于 `focalMm` 找；
    - 按倍率找到档位时，`focalMm` 显示该档位的 mm；
    - `selectZoom` 的 NOTCH 分支用新状态的 `.stop?.ratio == 新倍率` 来判断；
  - `ui/CameraScreen.kt`：按 §9.2 改焦段小牌（不在档位上时用新文案「42mm · 1.8×」）；`zoomNoteRes` 改为接收 `stop`；
  - `ui/MenuScreen.kt`：「变焦方式」这一行。

**落实**：§6 第 28–32、39 条，§8「变焦」，§9.1，§9.2，§9.6「变焦方式」，Review Focus 第 4、5 条。

**验收**
- [ ] `pwsh -NoProfile -File scripts\check.ps1`：`check passed`。
- [ ] 提交 `app: zoom strip with stops and free zoom`。

### 任务 4：换卷抽屉

**文件**
- 修改：
  - `ui/FilmDrawer.kt`：§9.3 的全部内容（几何、外观、跟手、惯性、吸附、关闭手势、开合、无障碍）；
  - `ui/ShooterViewModel.kt`：`selectFilmAt`、`openDrawer`、`closeDrawer`、`drawerStep`，`selectFilm` 改为调用 `selectFilmAt`；
  - `ui/FilmRow.kt`：当前那卷的标签可点，点了调 `onOpen`（骨架里已接好）；标签解码用骨架评审抽出的 `rememberLabels`。
  - `FilmDrawer` 还要负责 45% 遮罩、点外面关闭，以及 `drawer != CLOSED` 时启用的 `BackHandler`。三者都无条件调用 `onClose`；REQUIRED 的判断只在 `closeDrawer` 里，REQUIRED 时返回键被它吃掉。`CameraScreen` 只负责把 `FilmDrawer` 一直叠在最上层。
    - 骨架评审的裁决：§4 的 `required: Boolean` 改为 `drawer: Drawer`，这样滑出动画留在 `FilmDrawer` 里。

**落实**：§6 第 37、38 条，§8「换卷抽屉」，§9.1 的抽屉和胶片行两条，§9.3，§9.7 的抽屉部分，Review Focus 第 1 条。

**验收**
- [ ] `pwsh -NoProfile -File scripts\check.ps1`：`check passed`。
- [ ] 提交 `app: arc film drawer`。

### 任务 5：仪式感

**文件**
- 修改：
  - `ui/ShooterViewModel.kt`：
    - `onShutter` 按第 36 条和 §8「快门」改；
    - 倒片计时用 `REWIND_MS`；
  - `ui/ShooterUiState.kt`：`counterShown`；
  - `ui/TopBar.kt`：
    - 计数鼓的隐藏；
    - 倒片时从 36 倒转到 1 的动画（§9.5，第 41 条）；
  - `ui/LcdPanel.kt`：倒片时第 4 行左侧显示 `REWIND`；
  - `ui/CameraScreen.kt`：倒片时取景压暗；
  - `ui/MenuScreen.kt`：「仪式感」三档滑块（§9.6）。

**落实**：§6 第 36、41 条，§8「快门」，§9.5，§9.6「仪式感」，Review Focus 第 2、3 条。

**验收**
- [ ] `pwsh -NoProfile -File scripts\check.ps1`：`check passed`。
- [ ] 提交 `app: ritual levels and the rewind ceremony`。

### 任务 6：触摸对焦

**文件**
- 修改：
  - `camera/PreviewRenderer.kt`：
    - `updateViewToCamera` 算出的 `sx`、`sy` 同时写进 `@Volatile` 字段；
    - `viewToFrame` 只用这两个字段（第 33 条）；
  - `camera/CameraController.kt`：`focusAt`（第 34 条，§4 的返回值约定）；
  - `ui/ShooterViewModel.kt`：`focusAt`，成功时播 TICK；
  - `ui/CameraScreen.kt`：
    - 在 `AndroidView` 上面加一层透明的点击层（§11）；
    - 1:1 遮罩区不响应（第 35 条）；
    - 对焦框（§9.4）；
    - 新点击取消上一次对焦；结果返回 2 s 后用 300 ms 淡出（§8「触摸对焦」）。

**落实**：§5（对焦请求、裁切系数两行），§6 第 33–35 条，§7，§8「触摸对焦」，§9.4，§11。

**验收**
- [ ] `pwsh -NoProfile -File scripts\check.ps1`：`check passed`。
- [ ] 提交 `app: tap to focus and meter`。

### 任务 7：真机清单（需要用户操作）

**文件**
- 修改 `docs/device-checklist.md`：
  - 「清单」部分追加第 11–15 条：§10 真机清单的 5 条，Review Focus 的 5 条分别并入对应条目：
    - 第 1 条并入抽屉那条；
    - 第 2、3 条并入仪式感那条；
    - 第 4、5 条并入任意变焦那条；
  - 「首次运行」表追加第 11–15 行；
  - 第 10 行改为「通过」：用户在 fa1e107 之后实测，115 mm 回到桌面再返回，仍是 115。

**做法**
1. 实施者更新清单。
2. 主会话安装 debug APK：`adb install -r app\build\outputs\apk\debug\app-debug.apk`，并请用户在手机上确认安装。
3. 用户逐条操作、汇报，主会话把结果记进「首次运行」表：通过，或者现象加截图路径。
4. 发现的问题按任务 3–6 的归属修复，修复后重跑闸口，并请用户复测对应条目。

**验收**
- [ ] 第 11–15 条全部是「通过」。
- [ ] 修复之后 `pwsh -NoProfile -File scripts\check.ps1`：`check passed`。
- [ ] 提交 `docs: device checklist for the interactions iteration`。修复各自单独提交。

### 收尾

- [ ] 全分支双审，范围 `main..interactions`，正确性和优雅性两席各派一个 Opus。
  - Review Focus 的 5 条要逐条核对。
  - 优雅性一席要回答：
    - 「哪些测试删了也不损失验证力」；
    - 「哪段新代码答不出存在理由」。
- [ ] 两席结论合并后，派一个 Sonnet 修复，最多两轮。修复后重跑闸口；改到的真机条目请用户复测。
- [ ] 按 superpowers:finishing-a-development-branch 处理，由用户决定合并方式。推送前先问用户。
