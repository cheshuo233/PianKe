# 片刻（pianke）设计说明：交互迭代（换卷抽屉、触摸对焦、仪式感、变焦）

- 日期：2026-11-06
- 基线：main `fa1e107`（阶段一已合并）。阶段一的 spec `2026-11-06-pianke-design.md` 继续有效，本文只写改动；两者冲突的地方以本文为准。
- 来源：brainstorming 第 1、2 节，均已获批。效果图和生成脚本在 `docs/design/interactions/`：
  - 变焦条：`zoom-strip.png`；
  - 换卷抽屉：`arc-drawer.png`。
- 写法：同阶段一，只写到骨架，函数体留给实施者。裁决写进提交信息，不回写本文。

## 0. 范围

**做**（编号沿用用户的需求编号）
1. 弧形换卷抽屉：点胶片行里当前那卷，从右边缘滑出，单手拨选。
2. 触摸对焦：点哪儿就在哪儿对焦并测光。
4. 仪式感三档：关 / 中 / 高，默认中。
5. 变焦方式「档位 / 任意」，以及新的镜头式变焦条，取代 `ZoomRing`。

**不做**
- 3（预设强度）、6（自定义预设与分享）：用户推迟。
- 双指缩放、长按锁定对焦或曝光、曝光补偿。
- 抽屉不取代胶片行：拨盘和标签窗的拖动、点按都照旧。
- 不新增依赖（新下载需要先问用户）。用到的 CameraX、Compose 动画 API 都在现有依赖里。

## 1. 契约

只有 K6 有变化。其他契约（K1–K5）不变。

| # | 契约 | 本次改动 |
|---|---|---|
| K6 | DataStore 的键 | 新增两个键：<br>• `ritual`：`OFF` / `MEDIUM` / `HIGH`，默认 `MEDIUM`；<br>• `zoomMode`：`STOPS` / `FREE`，默认 `STOPS`。<br>两个都按枚举的 `name` 存。原有键不动。 |

K3（照片的 EXIF）不受任意变焦影响：焦距和等效焦距本来就是从原片拷贝的。

## 2. 模块地图（只列改动）

模块划分和依赖方向都不变：`camera` 仍然不依赖 `feedback`、`ui`、`develop`。

| 模块 | 改动 |
|---|---|
| `app.pianke.settings` | 新增枚举 `Ritual`、`ZoomMode`；`Settings` 加两个字段；`SettingsStore` 加两个键 |
| `app.pianke.feedback` | 新增 `Cue.TICK` |
| `app.pianke.camera` | 新增 `ZoomScale.kt`（刻度表、mm↔倍率、跨刻度判断、最近档位），`MAIN_MM` 从 `ZoomStops` 搬过来。<br>`CameraController`：`setZoom(stop)` 改成 `setZoom(ratio)`，新增 `focusAt`。<br>`PreviewRenderer`：新增 `viewToFrame` |
| `app.pianke.ui` | 新增 `FilmDrawer.kt`、`ZoomStrip.kt`；删除 `ZoomRing.kt`。<br>改动：`ShooterUiState`、`ShooterViewModel`、`CameraScreen`（对焦框、倒片压暗、抽屉叠层）、`TopBar`（计数鼓的隐藏和倒转）、`LcdPanel`（REWIND）、`FilmRow`（点当前卷打开抽屉）、`MenuScreen`（两行新设置） |

## 3. 核心类型

```kotlin
// settings/Settings.kt
enum class Ritual { OFF, MEDIUM, HIGH }
enum class ZoomMode { STOPS, FREE }
data class Settings(
    // …阶段一的字段原样保留…
    val ritual: Ritual = Ritual.MEDIUM,
    val zoomMode: ZoomMode = ZoomMode.STOPS,
)

// feedback/Feedback.kt
enum class Cue { NOTCH, SHUTTER, ADVANCE, REWIND, REFUSE, TICK }

// camera/ZoomScale.kt
data class Tick(val mm: Int, val marked: Boolean)   // marked：这个焦距是一个档位，刻度上标数字

// ui/ShooterUiState.kt
enum class Drawer { CLOSED, OPEN, REQUIRED }        // REQUIRED：仪式感「高」倒片之后，必须选一卷才能关
data class ShooterUiState(
    val films: List<Film>, val filmIndex: Int,
    val settings: Settings,
    val stops: List<ZoomStop>,
    val zoomRatio: Float,                           // 取代 stopIndex；当前档位由它推导（§6 第 30 条）
    val exposure: Exposure?,
    val issue: CameraIssue?, val permission: Permission,
    val developing: Int,
    @StringRes val lcdMessage: Int?,
    val lastPhoto: Uri?,
    val drawer: Drawer,
    val rewinding: Boolean,                         // 倒片仪式进行中，持续 REWIND_MS
)
internal const val REWIND_MS = 1_500L              // 倒片时长；ViewModel 计时和计数鼓动画共用这一个值
internal val ShooterUiState.focalMm: Int            // 当前等效焦距（§6 第 30 条）
internal val ShooterUiState.stop: ZoomStop?         // 当前所在档位，不在档位上时为 null
internal val ShooterUiState.counterShown: Boolean   // 仪式感不为 OFF
```

## 4. 接缝签名

```kotlin
// camera
object ZoomScale {
    const val MAIN_MM = 23f                         // 只在这里；ZoomStops.BACK 用 ratio(mm) 算倍率
    fun ratio(mm: Float): Float
    fun mm(ratio: Float): Float                     // 只对后置有意义
    fun ticks(stops: List<ZoomStop>): List<Tick>    // §6 第 26 条
    fun crossed(fromMm: Float, toMm: Float, ticks: List<Tick>): Tick?   // §6 第 27 条
    fun nearest(stops: List<ZoomStop>, ratio: Float): ZoomStop          // 对数距离最近的档位
}
class CameraController {
    fun setZoom(ratio: Float)                       // 照旧记住倍率，相机 OPEN 时重新应用
    suspend fun focusAt(x: Float, y: Float): Boolean
    // x、y：归一化坐标（0..1），基于转正后、未裁切、按屏幕所见方向的画面（前置为镜像后的样子）。
    // 返回 isFocusSuccessful；没有相机、被更新的一次对焦取消、或出错时返回 false。
}
class PreviewRenderer {
    fun viewToFrame(x: Float, y: Float): android.graphics.PointF?
    // 取景视图内的归一化坐标 → focusAt 要的画面坐标；还不知道画面尺寸时返回 null。主线程调用。
}

// ui
class ShooterViewModel {
    fun selectFilmAt(index: Int)                    // 唯一的选卷入口：设下标、NOTCH、持久化，并把抽屉置为 CLOSED
    fun selectFilm(delta: Int)                      // 胶片行用：selectFilmAt(floorMod(filmIndex + delta, n))
    fun openDrawer()                                // CLOSED → OPEN；其他状态不动
    fun closeDrawer()                               // OPEN → CLOSED；REQUIRED 不动
    fun drawerStep()                                // 抽屉每转过一卷：播 TICK
    fun selectZoom(ratio: Float)                    // 取代 selectStop：夹范围，同值不动，setZoom，按跨刻度播 cue
    suspend fun focusAt(x: Float, y: Float): Boolean   // x、y 为取景视图内的归一化坐标；成功时播 TICK
    // selectStop(index) 删除；drive 改为同时记住 renderer（focusAt 要用 viewToFrame）
}

@Composable fun ZoomStrip(stops: List<ZoomStop>, ratio: Float, mode: ZoomMode, focalMm: Int, current: ZoomStop?,
                          onZoom: (Float) -> Unit)
// focalMm 和 current 由 ShooterUiState 推导好再传进来（第 30 条），变焦条自己不再推导
@Composable fun FilmDrawer(films: List<Film>, filmIndex: Int, required: Boolean,
                           onStep: () -> Unit, onPick: (Int) -> Unit, onClose: () -> Unit)
// FilmRow 加参数 onOpen: () -> Unit；TopBar 加参数 counterShown、rewinding；LcdPanel 加参数 rewinding
```

## 5. 状态归属与并发

| 状态 | 持有者 | 线程 |
|---|---|---|
| `zoomRatio`、`drawer`、`rewinding` | `ShooterViewModel`，在 `ShooterUiState` 里 | 主线程 |
| 抽屉轮子的连续位置、开合偏移 | `FilmDrawer` 内部的 `Animatable`，不进 ViewModel | 主线程 |
| 变焦条拖动中的鼓位置 | `ZoomStrip` 内部 | 主线程 |
| 对焦框（位置、成败、淡出） | `CameraScreen` 的取景组件内部 | 主线程 |
| 取景裁切系数 `sx`、`sy` | `PreviewRenderer`；GL 线程写，`@Volatile` 发布，主线程的 `viewToFrame` 只读 | GL 写 / 主读 |
| 倒片计时 | `viewModelScope` 里的一个协程 | 主线程 |

- 对焦请求由 UI 的协程作用域发起。新的一次点按会取消上一次；CameraX 自己也会取消旧的对焦动作，旧的那次返回 `false`，对应的框直接被新框替换。
- 任意变焦拖动时，每一帧都可能调一次 `setZoom`，交给 CameraX 合并。

## 6. 规则落点表（新增和改动的条目，接着阶段一编号）

| # | 规则 | 唯一落点 |
|---|---|---|
| 17′ | 档位类型不变。倍率改为 `ZoomScale.ratio(mm)`，`MAIN_MM` 搬走 | `ZoomStops.BACK` |
| 26 | 刻度表 = 网格 ∪ 所有档位的 mm。网格：18–35 每 1 mm，35–100 每 5 mm，100–200 每 10 mm（所以 115、135 是因为档位才有刻度）。只保留 [首档 mm, 末档 mm] 之内的刻度；mm 等于某个档位的标 `marked` | `ZoomScale.ticks` |
| 27 | 跨刻度：从 `fromMm` 到 `toMm`，沿移动方向「经过或到达」的刻度算数，起点上的那个不算。有带数字的刻度就返回它，否则返回任意一个经过的刻度，都没有就返回 null。一次变化最多出一个 cue | `ZoomScale.crossed` |
| 28 | 变焦 cue：<br>• 新倍率正好等于某个档位的倍率 → NOTCH。档位可能被夹到相机范围的边上，夹过之后的 mm 不再是整数，所以先按倍率判断。<br>• 否则看 `crossed`：返回带数字的刻度 → NOTCH，返回普通刻度 → TICK，返回 null → 不响。<br>两种模式共用这一条。档位模式下每次变化都落在一个档位上，所以总是 NOTCH | `ShooterViewModel.selectZoom` |
| 29 | 倍率夹在 [首档 ratio, 末档 ratio]；与当前值相同时什么都不做 | `ShooterViewModel.selectZoom` |
| 30 | 当前焦距：前置取唯一档位的 mm，后置取 `round(ZoomScale.mm(zoomRatio))`。当前档位 = mm 与当前焦距相等的那一档 | `ShooterUiState.focalMm` / `.stop` |
| 31 | 档位模式：拖动中，当前档位为离鼓中心最近的档位（`ZoomScale.nearest`），变化时调 `onZoom`；松手后鼓吸到该档。从「任意」切回「档位」时，若不在档位上，吸到最近一档，不播 cue | 吸附判断：`ZoomScale.nearest`；切换时机：`ShooterViewModel.drive` |
| 32 | 换镜头、重新绑定后，倍率回到 1（阶段一 H4 的复位，改为设 `zoomRatio = 1f`） | `ShooterViewModel.drive` |
| 33 | 取景视图坐标 → 画面坐标：与着色器的中心裁切共用同一组 `sx`、`sy`；不在这里处理镜像（交给 `DisplayOrientedMeteringPointFactory`） | `PreviewRenderer`（`updateViewToCamera` 算系数，`viewToFrame` 只用它们） |
| 34 | 对焦测光：`DisplayOrientedMeteringPointFactory(display, cameraInfo, 1f, 1f)`，`FLAG_AF or FLAG_AE`，5 s 后自动取消、恢复连续对焦 | `CameraController.focusAt` |
| 35 | 1:1 画幅下，点在上下各 60 dp 的遮罩里不对焦 | `CameraScreen` 的取景组件 |
| 36 | 仪式流程：<br>• OFF：任何一张都不播 ADVANCE、REWIND，36 → 1 时也不倒片、不开抽屉；<br>• MEDIUM / HIGH：普通一张播 ADVANCE；`Roll.next` 得到 1 时播 REWIND，`rewinding = true`，持续 `REWIND_MS`；<br>• HIGH：倒片结束后 `drawer = REQUIRED`；<br>• 倒片中的快门和「上一张还没拍完」走同一条路径：直接忽略，不播 REFUSE。<br>不管哪一档，计数都照常 +1（阶段一第 21 条不变） | `ShooterViewModel.onShutter` |
| 37 | 抽屉状态转换：<br>• CLOSED → OPEN：只有 `openDrawer`；<br>• OPEN → CLOSED：`closeDrawer` 或 `selectFilmAt`；<br>• → REQUIRED：只有第 36 条；<br>• REQUIRED → CLOSED：只有 `selectFilmAt`（选当前那卷也算） | `ShooterViewModel` |
| 38 | 抽屉的几何、手势、惯性和吸附（§9.3） | `ui/FilmDrawer.kt` |
| 39 | 变焦条的几何：刻度位置 `x = 窗口中线 + 120 dp × ln(f / 当前焦距)`；数字颜色（当前档位红，DIGITAL 档琥珀，其余浅色） | `ui/ZoomStrip.kt` |
| 40 | 各 cue 的震动强弱、有没有声音 | `Feedback` |
| 41 | 计数鼓只在 `counterShown` 时显示；倒片中显示动画值，不看 `frame` | `TopBar` |

## 7. 错误流向

不新增错误类型。

| 来源 | 在哪一层处理 | 用户看到什么 |
|---|---|---|
| 对焦失败、被取消、相机正在重绑 | `CameraController.focusAt` 返回 `false` | 对焦框保持奶白色，照常淡出；没有提示，没有 cue |
| `viewToFrame` 返回 null（第一帧还没到） | `ShooterViewModel.focusAt` 返回 `false` | 同上 |
| 进程在倒片中或 REQUIRED 时被杀 | 不处理 | 状态丢失，下次启动抽屉是关着的。计数已经是 1，不受影响 |

## 8. 关键流程

**快门**：阶段一 §8 的流程不变，只改两处。
- 第 1 步：「`capture` 还没返回」改为「`capturing || rewinding`」，两种情况都直接忽略。
- 第 3 步：「结果为 1 时播 `REWIND`，否则播 `ADVANCE`」换成 §6 第 36 条。
  - 倒片的计时协程在 `submit` 之后才启动，所以冲洗不会被它推迟。
  - 计时结束：`rewinding = false`；如果是 HIGH，再把 `drawer` 设为 `REQUIRED`。

**变焦**：`ZoomStrip` 的拖动 → `onZoom(ratio)` → `selectZoom` 依次执行：
1. 夹到范围内；
2. 与当前倍率相同就结束；
3. `crossed(mm(旧倍率), mm(新倍率), ticks)` 决定 cue；
4. 更新 `zoomRatio`；
5. `camera.setZoom(ratio)`；
6. 播 cue。

前置镜头只有一档：变焦条锁死，`onZoom` 不会被调用。

**换卷抽屉**
1. 打开：点胶片行里当前那卷 → `openDrawer`。
2. 转动：轮子每转过一卷 → `drawerStep`（TICK）。
3. 选中：点一张露出来的标签 → 轮子转到它（约 200 ms）→ `selectFilmAt(floorMod(k, n))`。之后和胶片行换卷一样：LUT 立即切换，NOTCH，抽屉滑回。
4. 关闭：
   - 向右划、点抽屉外面、按系统返回键 → `closeDrawer`。
   - REQUIRED 时这三种都不起作用，返回键也被拦下，什么都不做。
   - 抽屉开着时，后面的整页（快门、MENU、胶片行）都被遮罩挡住，点不到。

**触摸对焦**
1. 点取景区：
   - 如果落在 1:1 遮罩里，忽略；
   - 否则立刻在点击位置画出奶白色的对焦框。
2. 换算并对焦：`viewModel.focusAt(x/宽, y/高)` 执行 `renderer.viewToFrame` → `camera.focusAt`。
3. 结果：
   - 成功：框变橙色，播 TICK；
   - 失败：框保持奶白。
4. 结果返回 2 s 后，框用 300 ms 淡出。在这期间再点，旧框直接被新框取代。

## 9. 界面

### 9.1 布局改动

- 变焦条：`ZoomStrip` 外框 360×48 dp，取代 `ZoomRing` 的 360×41 dp。整页高度由胶片行下面的弹性空白吸收。
- 抽屉：叠在整页最上层，盖住包括顶栏在内的所有内容。
- 胶片行：当前那卷的标签变成可点，点了打开抽屉。相邻标签、拨盘的行为不变。

### 9.2 变焦条（视觉以 `zoom-strip.png` 为准）

**外框**
- 浅色拉丝银，从上到下大约 `#E2E2DE` 渐变到 `#C0C0BC`，圆角 10 dp。
- 上沿一道 1 dp 的亮边 `#FAF9F5`；下方投一层阴影，偏移 3 dp、模糊 4 dp。整体看起来比页面凸起一点。

**刻度窗**
- 位置：横向 56–266 dp，高 30 dp，竖直居中偏上，圆角 5 dp。
- 凹陷：窗口四周有 3 dp 的斜边，上沿暗 `#969590`、下沿亮 `#F4F3EE`。
- 刻度鼓：
  - 底色 `#2C2B29`；
  - 刻度线从窗口顶边往下画，颜色 `#D6D3CA`；
  - 带数字的刻度长 9 dp、粗 0.9 dp，其余长 6 dp、粗 0.6 dp；
  - 数字 10 sp，位于刻度线下方，颜色按第 39 条：当前红 `#EC483A`，DIGITAL 档琥珀 `#D6A054`，其余 `#E8E4DA`。
- 玻璃效果：顶部一层内阴影，两端按曲率压暗，中上部一条柔和的反光带。

**固定标识**
- 窗口下方正中一个深色三角 `#3C3B38`，宽 6 dp、高 5 dp，尖朝上。
- 左侧：当前焦距的数字，9 sp，红色 `#CE3228`；下面一行小字 `mm`。
- 右侧：`{首档 mm}-{末档 mm}mm`，9 sp，`#403F3C`；下面一行 6 sp 的 `PIANKE ZOOM`。

**手感**
- 鼓跟手：向右拖，较短的焦段从左边进来，和 `ZoomRing` 一致。
- 档位模式：按第 31 条吸附，松手后用 200 ms 动画回到档位。
- 任意模式：松手停在原处，没有惯性。
- 前置镜头：锁死，不响应拖动，只显示那一档。

**取景内的焦段小牌**
- 在档位上（`stop != null`）：显示内容和阶段一一样，例如「23mm · 1× · 主摄」。
- 不在档位上：只显示「42mm · 1.8×」，不带注释。

### 9.3 换卷抽屉（视觉以 `arc-drawer.png` 为准，参照屏 390×866 dp）

**几何**（只写在 `FilmDrawer.kt`）
- 圆心：屏幕右边缘向右 300 dp，竖直方向居中。
- 抽屉外缘半径 500 dp，所以抽屉左缘最远到屏幕左边起 190 dp 处，大约占右半屏。
- 标签轮半径 416 dp，相邻两卷相隔 15°。显示当前卷以及上下各 3 卷，更远的不画。

**外观**
- 抽屉面板：
  - 浅色拉丝银；
  - 沿弧边内侧 5 dp 有一道凸起的亮边；
  - 向页面投阴影，模糊 14 dp，向左下偏移。
- 标签轨道：标签轮半径内外各 64 dp 的一条凹陷暗带。
- 标签：
  - 使用 `labels/<id>.png`，尺寸 84×77 dp，沿切线旋转，带投影；
  - 当前卷放大到 1.12 倍，向圆心反方向（往屏幕内）移 14 dp；
  - 当前卷外面一圈 3 dp 的橙色描边 `#E8833A`，圆角 8 dp；下面用 11 sp 奶白色 `#F6F0E2` 显示胶片名，例如 `GOLD 200`。
- 指示：橙色三角 `#E8833A`，尖朝左，位于当前卷右侧（x = 圆心 − 416 + 70…80 dp）。
- 握持条：弧边上方一个 4×40 dp 的圆角短条。
- 遮罩：抽屉以外压一层 45% 的黑，取景在下面继续实时显示。
- REQUIRED 时，抽屉顶部多一行小字「装一卷新胶卷」。这是唯一能说明它为什么关不掉的提示。

**手势**
- 跟手：手指绕圆心转过多少角度，轮子就转多少。
- 惯性：在弧长像素上计算，用 `splineBasedDecay`，与列表惯性同一手感。算出落点后取整到最近一卷，再用 spring 收尾，不过冲。
- 每跨过一个整数位置调一次 `onStep`。
- 首尾相接：标签下标为 `floorMod(k, 15)`。
- 关闭手势：
  - 手指移动超过触摸阈值时，若横向位移大于纵向且向右，就进入关闭手势：抽屉跟手往右移。
  - 松手时，右移超过 64 dp 或向右的速度超过 1000 dp/s → `onClose`；否则弹回。
  - `required` 时没有关闭手势，横向的移动都忽略。
- 开合：用 spring 横向滑入、滑出，约 300 ms 落定。
- 无障碍：每张露出来的标签是一个 Button，读胶片名；当前卷额外标为已选中。

### 9.4 对焦框

- 形状：奶白色 `#F3ECDD` 的四角括号，60 dp 见方，线宽 1.5 dp，每个角的臂长 12 dp，中心对准点击位置。
- 成功变成橙色 `#F28C3A`（和日期水印同一个橙）。淡出规则见 §8。

### 9.5 倒片仪式（MEDIUM / HIGH，`rewinding` 期间）

- 计数鼓：用 `REWIND_MS` 从 36 倒转到 1，先快后慢，逐个数字滚过。
- 取景：叠一层 55% 的黑，150 ms 淡入、300 ms 淡出。
- LCD：第 4 行左侧显示 `REWIND`，优先于 DEV 点和 2 s 提示。
- 快门：被锁住（第 36 条）。
- 倒片期间翻转镜头照常进行，计数鼓的动画按原定时间跑完。

### 9.6 MENU（「拍摄」组，加在网格线下面）

- **仪式感**：三档滑块，刻度上写 `关` / `中` / `高`。拖动旋钮或点刻度字都能换档，松手吸到最近一档，每换一档经 `updateSettings` 播 NOTCH。
- **变焦方式**：`ValueRow`，值为 `档位` / `任意`，点一下切换。

### 9.7 反馈（新增一行，其余照阶段一 §9.5）

| Cue | 什么时候 | 震动 | 音效 |
|---|---|---|---|
| TICK | 任意变焦经过普通刻度、抽屉每转过一卷、对焦成功 | `PRIMITIVE_LOW_TICK`，强度 0.4；不支持时用 `PRIMITIVE_TICK`，强度 0.25；都不支持就不震 | 无 |

- 任意变焦经过带数字的焦段时播 NOTCH，比 TICK 稍重。
- 抽屉里选中一卷时播 NOTCH。

## 10. 测试与闸口

**JVM 单测**：只加一组表驱动的 `ZoomScaleTest`。理由：刻度表和跨刻度判断是纯逻辑，在真机上很难一格一格核对。

| 用例 | 断言 |
|---|---|
| `ticks(available(BACK, 0.7f..10f))`（本机的范围） | 共 43 个刻度：18–35 共 18 个，40–100 每 5 共 13 个，110–200 每 10 共 10 个，再加 115、135。marked 恰好是 {18, 23, 28, 35, 50, 115, 135, 200} |
| `ticks(available(BACK, 1f..5f))` | 首个是 23，末个是 115，范围外的刻度都没有 |
| `crossed(33.5, 36.2)` | 返回 35（marked），虽然途中还经过了 34 |
| `crossed(36.0, 39.0)` | null（35 和 40 之间没有刻度） |
| `crossed(37.0, 40.0)` | 40（恰好到达也算） |
| `crossed(40.0, 41.0)` | null（起点上的那个不算） |
| `crossed(52.0, 44.0)` | 50（向下移动，经过带数字的刻度） |

**真机清单**（追加到阶段一 §11.5）

1. 抽屉：单手拨动、惯性、吸附、首尾循环、点选、向右划关闭、点外面关闭、返回键关闭，都正常；不掉帧。
2. 对焦：后置、前置、1:1 画幅下，点哪儿就对哪儿（看画面里清晰的位置）；遮罩区不响应；5 s 后恢复连续对焦。
3. 仪式感：在第 36 张时检查三档：
   - 关：没有过片声和倒片声，看不到计数鼓；
   - 中：倒片 1.5 s，期间快门无效；
   - 高：倒片后抽屉弹出，关不掉，选卷后恢复。
4. 任意变焦：拖动顺滑，每格都有轻震，带数字的焦段稍重；回桌面再回来，倍率保持不变（OPEN 时会重新应用任意倍率）。
5. 新变焦条在浅色页面上的观感；整页不溢出屏幕。

**闸口**：`scripts/check.ps1` 不变。

## 11. 交给实施者的已知风险

- **对焦坐标映射**：`DisplayOrientedMeteringPointFactory` 默认画面按 PreviewView 的方式显示：转正、前置镜像。我们的取景与它一致（阶段一已实测「正的且像镜子」）。真机清单第 2 条是唯一的证明，必须前后两个镜头都测。
- **触摸事件**：GLSurfaceView 嵌在 `AndroidView` 里。对焦的点击层要放在它上面，用一个透明的 Compose 层，不依赖 View 自己把事件透传出来。
