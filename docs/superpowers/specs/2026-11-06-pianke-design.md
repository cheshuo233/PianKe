# 片刻（pianke）设计说明：v1 阶段一

- 日期：2026-11-06
- 来源：brainstorming §1–§6，均已获批。界面以设计画布 v38 为准，副本见 `docs/design/canvas-v38/`，由 plan 的第一个任务放入。
- 写法：按全局 CLAUDE.md，本文只写到骨架，函数体留给实施者。裁决写进提交信息，不回写本文。

## 0. 范围

**本 spec 覆盖阶段一**：在现有单 LUT 引擎上把整个 App 跑通，内容包括：
- 相机、取景、选胶片、拍摄、冲洗、存相册、MENU；
- 引擎为安卓做的改动；
- 旧实现缺陷的修复（以 §10 的裁决为准）。

**阶段二另出 spec。** 阶段二就是 brainstorming §3 的物理顺序管线：三张 LUT，加上 µm 尺度的光晕、分层颗粒、DIR 耦合，再配自定色调曲线。它要先读 spektrafilm 的 grain / couplers 源码，有自己的对照套件。按「不为尚未开工的模块预造接口」，本 spec 不给它预留任何接口。

**v1 做**
- 安卓 App，先适配 Redmi K90 Pro Max。
- 后置：逻辑相机 0，8 个焦段档位（§9.3）；前置：单档。
- 取景：GPU 实时套当前胶片的 LUT，叠一层轻颗粒，画幅 3:4 或 1:1。
- 选胶片：15 卷，用扇形标签窗和半个拨盘选择（§9.2）。
- 拍摄：快门 → 落盘到 `pending/` → 后台冲洗（光晕 → LUT → 颗粒 → 日期水印）→ 存入 `DCIM/Pianke`。
- 每个镜头各有一卷 36 张的计数，只作氛围，不限制拍摄。
- 震动与音效。
- MENU：画幅、日期水印、网格线、颗粒、光晕、快门声、震动、关于。
- 协议 GPL-3.0。

**v1 不做**
- RAW 冲洗、多帧降噪、视频；
- 相册里换胶片重冲、AI 功能、iOS；
- 胶卷盒总览、保存位置设置；
- 崩溃上报、数据统计、网络权限。

## 1. 契约（有真实依赖方的才算）

| # | 契约 | 依赖方 | 内容 |
|---|---|---|---|
| K1 | `films.bin` v1 格式 | `engine/src/catalog.c` ↔ `tools/filmsbin.py` | 原样沿用 mosaico 的格式：16 字节头，每卷 68 字节条目，LUT 为 33³ RGB888，红色变化最快。本版不改。 |
| K2 | `films.json` | 烘焙工具 → App 的 `FilmCatalog` | 见 §3.3。 |
| K3 | 存下来的照片 | 用户的相册和其他 App | 路径 `DCIM/Pianke/PK_yyyyMMdd_HHmmss.jpg`。像素已转正，EXIF 的 Orientation 为 1。EXIF 规则见 §6 第 13 条。 |
| K4 | `pending/` 目录 | 下一次启动，可能是升级后的新版本 | 见 §3.4。元数据带 `v=1`，新版本必须能读旧版本留下的帧。 |
| K5 | `fe_*` C API | JNI 胶水层、宿主机黑盒套件 | 见 §4.1。 |
| K6 | DataStore 的键 | 升级后的新版本 | 键名和取值见 §3.2，只增不改。 |

契约之外的东西（模块内部、界面细节）都可以随便改。

## 2. 模块地图

| 模块 | 一句话职责 | 不许依赖 |
|---|---|---|
| `engine/`（C11） | 读 `films.bin`，在 RGB888 或 RGBA8888 图像上原地冲洗 | Android、JNI、任何平台头文件 |
| `app/src/main/cpp/`（JNI） | `fe_*` ↔ Kotlin；从 AssetManager 零拷贝打开 `films.bin`；把 `fe_err_t` 转成 `EngineException` | 业务规则（只做转换） |
| `app.pianke.film` | `films.json` + 原生目录 → `Film` 列表；提供 LUT 字节和冲洗调用 | 其他所有 app 包 |
| `app.pianke.settings` | 设置的持久化（DataStore）、默认值、损坏重置、胶卷计数规则 | 其他所有 app 包 |
| `app.pianke.feedback` | 震动和音效的播放；静音模式、强制快门声的判断 | 其他所有 app 包（开关作为参数传入） |
| `app.pianke.camera` | CameraX 绑定、Camera2 参数、变焦档位、拍摄落文件、GL 取景渲染、`CameraIssue` | `develop`、`ui`、`settings`（只吃 `settings` 的枚举类型） |
| `app.pianke.develop` | `pending/` 落盘与恢复、冲洗队列、解码 → 转正/裁切 → 引擎 → JPEG → MediaStore + EXIF | `camera`、`ui` |
| `app.pianke.ui` | Compose 界面、`ShooterViewModel` 编排、权限 | 不被任何包依赖 |
| `app.pianke`（根） | `PiankeApp : Application`，持有进程级单例；`MainActivity` | — |
| `tools/` | 烘焙：`films.toml` → `films.bin` + `films.json` | App |
| `tests/` | 宿主机套件：`engine/`（黑盒）、`assets/`（资产一致）、`bake/`（ΔE） | App |

依赖方向：`ui → camera, develop, film, settings, feedback`；`develop → film, settings`；`camera → film, settings`（只用类型）。不允许反向，也不允许成环。

`settings` 的枚举（`Framing`、`FlashMode`、`GrainLevel`、`Lens`）放在 `settings` 包，`camera` 和 `develop` 可以 import，这是唯一允许的横向引用。

## 3. 核心类型

### 3.1 C（`engine/include/film_engine.h`）

**改动**
1. `fe_image_t` 支持 4 通道，字段从 `rgb` 改名为 `pixels`：
   ```c
   typedef struct {
       uint8_t *pixels;    /* 行首指针，原地处理 */
       uint16_t width, height;
       uint32_t stride;    /* 每行字节数，>= channels * width */
       uint8_t  channels;  /* 3 = RGB888；4 = RGBA8888，第 4 字节从不读也从不写 */
   } fe_image_t;
   ```
2. 删除 `fe_preview_table_build`、`fe_preview_apply` 和 `src/preview.c`，因为取景改在 GPU 上做。
3. 头文件注释中设备专属的说法（如 PSRAM）改成通用说法。

**不变**：`fe_err_t`、`fe_film_t`、`fe_catalog_t`、`fe_date_t`、`fe_allocator_t`、`fe_develop_params_t`、`fe_catalog_*`，以及「无全局状态，同一时间只从一个线程调用」的约定。

### 3.2 Kotlin

```kotlin
// app.pianke.film
data class Film(val index: Int, val id: String, val name: String, val iso: Int, val process: String) {
    val labelAsset: String get() = "labels/$id.png"      // 标签文件命名规则只在这里
}
class EngineException(val code: Int) : RuntimeException("film_engine error $code")
class FilmCatalogBroken(cause: Throwable) : Exception(cause)

// app.pianke.settings  —— DataStore Preferences；键名 = 下列属性名，枚举存 name
enum class Framing { FOUR_THREE, SQUARE }
enum class FlashMode { AUTO, ON, OFF }
enum class GrainLevel(val scale: Float) { OFF(0f), LOW(0.5f), NORMAL(1f), HIGH(1.5f) }
enum class Lens { BACK, FRONT }
data class Settings(
    val filmId: String? = null,            // null：第一卷；保存 id 而不是下标，films.toml 调整顺序后不会错位
    val framing: Framing = Framing.FOUR_THREE,
    val dateStamp: Boolean = true,
    val grid: Boolean = false,
    val grain: GrainLevel = GrainLevel.NORMAL,
    val halation: Boolean = true,
    val shutterSound: Boolean = true,
    val vibration: Boolean = true,
    val flash: FlashMode = FlashMode.AUTO,
    val lens: Lens = Lens.BACK,
    val rollBack: Int = 1,                 // 下一张的张数，1..36
    val rollFront: Int = 1,
)

// app.pianke.camera
enum class StopKind { NATIVE, CROP, DIGITAL }
data class ZoomStop(val mm: Int, val ratio: Float, val kind: StopKind)
sealed interface CameraIssue { data object InUse : CameraIssue; data object Disabled : CameraIssue; data object Fatal : CameraIssue }
data class Exposure(val exposureNs: Long, val aperture: Float)   // LCD 上的实时读数

// app.pianke.develop
data class Shot(
    val id: String,                        // 拍摄时刻的 epoch 毫秒；拍摄是串行发起的（§8），不会重复
    val filmId: String,
    val framing: Framing,
    val dateStamp: Boolean,
    val grain: GrainLevel,
    val halation: Boolean,
    val capturedAt: java.time.LocalDateTime, // 本地时间，用于日期水印和文件名
)
enum class KeptReason { STORAGE_FULL, DEVELOP_FAILED }
sealed interface Outcome { data class Saved(val uri: android.net.Uri) : Outcome; data class Kept(val reason: KeptReason) : Outcome }

// app.pianke.ui
data class ShooterUiState(
    val films: List<Film>, val filmIndex: Int,
    val settings: Settings,
    val stops: List<ZoomStop>, val stopIndex: Int,
    val exposure: Exposure?,
    val issue: CameraIssue?, val permission: Permission,
    val developing: Int,                   // 队列中的帧数，驱动 LCD 的 "DEV ●●"
    val lcdMessage: String?,               // 2 s 的提示，以及 "冲洗失败，已暂存" / "存储已满"
    val lastPhoto: android.net.Uri?,
)
enum class Permission { UNKNOWN, GRANTED, DENIED, DENIED_FOREVER }
```

拍摄时的颗粒和光晕设置随帧记入 `Shot`。这样恢复出来的帧按拍摄当时的设置冲洗，不受之后改设置的影响；日期水印也是这样。

### 3.3 `films.toml` 新增字段与 `films.json`（K2）

每个 `[[film]]` 新增三个字段：
- `display`：界面上的名字，不带品牌前缀，如 `"Portra 160"`、`"Superia X-TRA 400"`；
- `iso`：整数，如 `500`；
- `process`：`"C-41"`、`"ECN-2"`、`"E-6"` 或 `"K-14"`。

`name`（如 "Kodak Portra 160"）照旧写进 `films.bin`，App 不读它。

烘焙工具同时写出 `app/src/main/assets/films.bin` 和 `app/src/main/assets/films.json`：
```json
{ "toml_sha256": "<films.toml 的 sha256>", "films": [ { "id": "portra160", "name": "Portra 160", "iso": 160, "process": "C-41" } ] }
```
`films` 的顺序与 `films.bin` 相同。标签图放在 `app/src/main/assets/labels/<id>.png`（288×264），母版就是这些文件本身，不另存一份。这里调整了 brainstorming §6「放在 films/labels/」的说法：放进 assets 后，打包不需要任何拷贝步骤。

### 3.4 `pending/` 目录（K4）

目录是 `filesDir/pending/`，每一帧对应两个文件：
- `<id>.jpg`：CameraX 写出的原始 JPEG，自带 EXIF，包括 Orientation；
- `<id>.meta`：`java.util.Properties` 格式，键为 `v=1`、`film`、`framing`、`dateStamp`、`grain`、`halation`、`capturedAt`（ISO-8601 本地时间）。

元数据用 Properties 而不是 JSON，是因为 JVM 单测里不需要额外依赖。

**写入顺序就是规则**：
1. CameraX 写 `<id>.jpg.part`；
2. 重命名为 `<id>.jpg`；
3. 写 `<id>.meta.part`；
4. 重命名为 `<id>.meta`。

`.meta` 存在，就等于「已拍下」。

**恢复规则**：
- 有 `.meta` 的帧会被重新入队；
- 只有 `.jpg`、没有 `.meta` 的，以及所有 `.part`，一律删除；
- 读不了的 `.meta`，或 `v` 不认识的，保留文件，并在 logcat 记一条，不让启动崩溃。

## 4. 接缝签名

### 4.1 C API（K5，`engine/include/film_engine.h`）

```c
FE_API fe_err_t         fe_catalog_open(const void *blob, size_t size, const fe_allocator_t *alloc, fe_catalog_t **out);
FE_API size_t           fe_catalog_count(const fe_catalog_t *catalog);
FE_API const fe_film_t *fe_catalog_film(const fe_catalog_t *catalog, size_t index);
FE_API int              fe_catalog_find(const fe_catalog_t *catalog, const char *id);
FE_API void             fe_catalog_close(fe_catalog_t *catalog);
FE_API fe_err_t         fe_develop(const fe_develop_params_t *params, fe_image_t *image, const fe_allocator_t *alloc);
```

`fe_develop` 的参数检查新增两条：`channels` 必须是 3 或 4；`stride >= channels * width`。

像素寻址只在 `src/pixel.h` 里定义：步长为 `channels`，各阶段只写 0..2 号字节。

### 4.2 JNI（`app/src/main/cpp/pianke_jni.c`，库名 `pianke`）

```kotlin
// app.pianke.film.FilmEngine —— internal，只有 FilmCatalog 调用
internal object FilmEngine {
    /** 打开 assets/films.bin（不压缩打包），用 AAsset_getBuffer 零拷贝；句柄活到进程结束。 */
    external fun open(assets: android.content.res.AssetManager): Long
    external fun ids(handle: Long): Array<String>
    external fun lutSize(handle: Long): Int
    /** 第 index 卷 LUT 的只读 direct ByteBuffer：lutSize³ 个 RGB888，红色变化最快，直接指向 films.bin。 */
    external fun lut(handle: Long, index: Int): java.nio.ByteBuffer
    /** 原地冲洗 ARGB_8888 可写位图（内存顺序 RGBA，channels = 4）。dateYmd = 0 表示不印日期。 */
    external fun develop(handle: Long, index: Int, grainScale: Float, halation: Boolean,
                         dateYmd: Int, seed: Int, bitmap: android.graphics.Bitmap)
}
```

- `fe_err_t != FE_OK` 时抛 `EngineException(code)`；`AndroidBitmap_*` 失败时抛 `EngineException(FE_ERR_INVALID_ARG)`。胶水层不做别的判断。
- 用户设置怎么作用到胶片参数，只在胶水层的一个函数里定义：复制一份 `fe_film_t`，`grain_strength *= grainScale`；`halation == false` 时 `halation_strength = 0`。

### 4.3 包之间

```kotlin
// film
class FilmCatalog private constructor(val films: List<Film>, private val handle: Long) {
    val lutSize: Int
    fun lut(film: Film): java.nio.ByteBuffer
    fun develop(film: Film, grainScale: Float, halation: Boolean, date: java.time.LocalDate?, seed: Int, bitmap: android.graphics.Bitmap)
    companion object { fun load(context: android.content.Context): FilmCatalog }   // 失败抛 FilmCatalogBroken
}

// settings
class SettingsStore(context: android.content.Context) {
    val settings: kotlinx.coroutines.flow.Flow<Settings>
    suspend fun update(transform: (Settings) -> Settings)
}
object Roll { const val FRAMES = 36; fun next(frame: Int): Int }   // 36 → 1；结果为 1 就是倒片

// feedback
enum class Cue { NOTCH, SHUTTER, ADVANCE, REWIND, REFUSE }
class Feedback(context: android.content.Context) {
    fun play(cue: Cue, sound: Boolean, vibration: Boolean)
}

// camera
object ZoomStops {
    val BACK: List<ZoomStop>                // 18,23,28,35,50,115,135,200；ratio = mm / 23f
    fun available(stops: List<ZoomStop>, range: ClosedFloatingPointRange<Float>): List<ZoomStop>
}
class CameraController(context: android.content.Context, owner: androidx.lifecycle.LifecycleOwner) {
    val stops: kotlinx.coroutines.flow.StateFlow<List<ZoomStop>>
    val issue: kotlinx.coroutines.flow.StateFlow<CameraIssue?>
    val exposure: kotlinx.coroutines.flow.StateFlow<Exposure?>   // 取景 CaptureResult 的读数，经 Camera2Interop 会话回调拿到
    fun bind(lens: Lens, flash: FlashMode, surface: androidx.camera.core.Preview.SurfaceProvider)
    fun setZoom(stop: ZoomStop)
    fun setFlash(mode: FlashMode)
    suspend fun capture(target: java.io.File, rotation: Int): Boolean   // 写 JPEG 到 target；失败返回 false
}
class PreviewRenderer : android.opengl.GLSurfaceView.Renderer {
    val surfaceProvider: androidx.camera.core.Preview.SurfaceProvider
    fun setFilm(lut: java.nio.ByteBuffer, size: Int)                  // 投递到 GL 线程
    fun setGrain(scale: Float)
}

// develop
class PendingStore(dir: java.io.File) {
    fun partFile(id: String): java.io.File
    fun commit(shot: Shot)                  // §3.4 的第 2–4 步
    fun recover(): List<Shot>               // §3.4 的恢复规则
    fun frame(shot: Shot): java.io.File
    fun delete(shot: Shot)
}
class Developer(context: android.content.Context, catalog: FilmCatalog) {
    fun develop(shot: Shot, frame: java.io.File): android.net.Uri      // 抛 IOException / EngineException / OutOfMemoryError
    fun latest(): android.net.Uri?          // DCIM/Pianke 里本 App 最新的一张，启动时给相册小照片用
}
class DevelopQueue(scope: kotlinx.coroutines.CoroutineScope, store: PendingStore, developer: Developer) {
    val inFlight: kotlinx.coroutines.flow.StateFlow<Int>
    val outcomes: kotlinx.coroutines.flow.SharedFlow<Pair<Shot, Outcome>>
    fun tryReserve(): Boolean               // 占位数 + 在队数 < CAPACITY 时才成功
    fun release()                           // 拍摄失败时归还占位
    fun submit(shot: Shot)                  // 消耗一个占位
    fun submitRecovered(shots: List<Shot>)  // 启动恢复用，可以超过容量
    companion object { const val CAPACITY = 2 }
}
```

## 5. 状态归属与并发

| 状态 | 持有者 | 线程 |
|---|---|---|
| `FilmCatalog`（加载后不变）、`SettingsStore`、`PendingStore`、`DevelopQueue`、`Feedback` | `PiankeApp`，进程级 | — |
| 冲洗 | `DevelopQueue` 唯一的 worker 协程 | `Dispatchers.Default.limitedParallelism(1)`，满足引擎单线程调用的约定 |
| `ShooterUiState` | `ShooterViewModel`，`StateFlow` | 主线程 |
| CameraX 会话 | `CameraController`，绑定在 Activity 的生命周期上 | CameraX 执行器 |
| 取景纹理（OES、3D LUT、颗粒） | `PreviewRenderer` | GL 线程；外部只能 post |
| 设置写入 | `SettingsStore`（DataStore 自带串行） | IO |

通信方式：
- ViewModel 订阅 `settings`、`stops`、`issue`、`exposure`、`inFlight`、`outcomes` 这几个 Flow；
- 换胶片时调用 `renderer.setFilm(catalog.lut(film), lutSize)`；
- 拍摄调用 `capture()`（挂起）和 `commit`（IO）。

## 6. 规则落点表

| # | 规则 | 唯一落点 |
|---|---|---|
| 1 | 冲洗顺序：光晕 → LUT → 颗粒 → 日期；`film == NULL` 跳过前三步 | `engine/src/develop.c` |
| 2 | 像素寻址和步长、alpha 不碰 | `engine/src/pixel.h` |
| 3 | 饱和到 0..255、亮度公式 | `engine/src/pixel.h` |
| 4 | 光晕的阈值、高光图尺寸 | `engine/src/halation.c` |
| 5 | 颗粒的纹理、中间调权重、尺寸换算（§10 D2） | `engine/src/grain.c` |
| 6 | 日期水印的版式与字号 | `engine/src/date_stamp.c` |
| 7 | `films.bin` 的字节布局 | C：`catalog.c`；Python：`tools/filmsbin.py`。两种语言各一份，由 `tests/assets` 交叉核对 |
| 8 | 胶片清单（顺序、id、显示名、ISO、工艺、烘焙参数） | `films/films.toml`，烘焙到 assets |
| 9 | 设置如何改变胶片参数（颗粒倍率、光晕开关） | JNI 胶水层的一个函数（§4.2） |
| 10 | 标签文件命名 `labels/<id>.png` | `Film.labelAsset`；`tests/assets` 按同一约定核对 |
| 11 | 冲洗队列容量 2，拍摄过程中的占位也算在内 | `DevelopQueue` |
| 12 | 「已拍下」= `.meta` 落盘；恢复与清理 | `PendingStore` |
| 13 | 照片的文件名、路径、EXIF：拷贝原片的曝光时间、ISO、光圈、焦距、等效焦距、拍摄时间、Make/Model；ImageDescription 写 `Film.name`；Software 写 `Pianke <版本号>`；Orientation 写 1 | `Developer` |
| 14 | 冲洗前先转正，1:1 时再取中心正方形 | `Developer` |
| 15 | 异常 → `KeptReason`：ENOSPC → 存储已满；其他 IO、引擎、OOM → 冲洗失败 | `DevelopQueue` |
| 16 | 变焦档位按相机范围过滤（±3% 容差，并夹到范围内） | `ZoomStops.available` |
| 17 | 档位类型：18/23/115 为 NATIVE，28/35/135 为 CROP，50/200 为 DIGITAL | `ZoomStops.BACK` |
| 18 | CameraX 错误码 → `CameraIssue` 的映射（§7） | `CameraController` |
| 19 | Camera2 参数：EDGE_MODE OFF、NR MINIMAL、TONEMAP sRGB 预设曲线（阶段一的 LUT 按 sRGB 输入烘焙）。只设设备声明支持的，取景和拍摄设同一套。拍摄分辨率固定为不超过 4096×3072 的最大 4:3 尺寸 | `CameraController` |
| 20 | `fe_err_t` → `EngineException` | JNI 胶水层 |
| 21 | 计数规则：拍下后 +1，36 之后回到 1（倒片） | `Roll.next`；+1 的时机由 `ShooterViewModel` 在 `commit` 成功后触发 |
| 22 | 强制快门声（`MediaActionSound.mustPlayShutterSound()`）和静音模式 | `Feedback` |
| 23 | 扇形几何：每卷 30°，拨盘与标签同角度转动；拖动 58 dp / 26 dp 一档 | `ui/FilmRow.kt` |
| 24 | 设置默认值、损坏时重置 | `SettingsStore`（`ReplaceFileCorruptionHandler`） |
| 25 | 取景和成片用同一份 LUT 字节 | `FilmCatalog.lut`（直接指向 `films.bin`） |

## 7. 错误流向

| 来源 | 在哪一层转换 | 变成什么 | 用户看到什么 |
|---|---|---|---|
| `fe_err_t`（INVALID_ARG / NO_MEM / BAD_FILMS） | JNI 胶水层 | `EngineException(code)` | 冲洗时：`Kept(DEVELOP_FAILED)`，LCD 显示 "冲洗失败，已暂存" |
| 目录打开失败、`films.json` 与 `films.bin` 的 id 对不上 | `FilmCatalog.load` | `FilmCatalogBroken` | 全屏 "胶片库损坏，请重新安装"，别的都不做 |
| CameraX `CameraState.ERROR_CAMERA_IN_USE` / `MAX_CAMERAS_IN_USE` | `CameraController` | `InUse` | 取景区显示 "相机被占用"，CameraX 会自己重试 |
| `ERROR_CAMERA_DISABLED` / `DO_NOT_DISTURB_MODE_ENABLED` | `CameraController` | `Disabled` | 同上 |
| `ERROR_CAMERA_FATAL_ERROR` / `STREAM_CONFIG` / `OTHER_RECOVERABLE_ERROR` | `CameraController` | 先重绑一次，再失败才变成 `Fatal` | "相机出错，请重开" |
| `ImageCaptureException` | `CameraController.capture` | 返回 `false` | 没有帧、计数不动、`REFUSE`、LCD "未拍到" 2 s |
| `IOException` / `EngineException` / `OutOfMemoryError`（冲洗、保存） | `DevelopQueue` | `Kept(reason)`，帧留在 `pending/` | "冲洗失败，已暂存" 或 "存储已满"，下次启动重试 |
| 权限 | `ui/PermissionGate` | `Permission` | "需要相机权限"，加一个按钮；`DENIED_FOREVER` 时按钮打开应用设置页 |
| DataStore 损坏 | `SettingsStore` | 恢复默认值 | 无提示 |

权限不放进 `CameraIssue`，因为申请权限需要 Activity，`CameraController` 拿不到。这一点调整了 brainstorming §5 的说法，其余与它一致。

GL 上下文失败和发热降频暂不处理；不输出日志以外的任何东西。

## 8. 关键流程

**快门**（`ShooterViewModel.onShutter`）
1. 上一次 `capture` 还没返回时，不响应。`queue.tryReserve()` 失败：播 `REFUSE`，结束。
2. 播 `SHUTTER`，执行 `capture(store.partFile(id), 设备方向)`。设备方向由 UI 的 `OrientationEventListener` 给出。
3. 成功：
   - `store.commit(shot)`；
   - `Roll.next` 写回设置；
   - 结果为 1 时播 `REWIND`，否则播 `ADVANCE`；
   - `queue.submit(shot)`。
4. 失败：`queue.release()`，删除 `.part`，播 `REFUSE`，LCD 显示 "未拍到"。

**冲洗**（worker）
1. 解码成可写的 ARGB_8888 位图。
2. 按 EXIF 转正；若是 1:1，取中心正方形。拍摄总是 4:3，1:1 在这里裁切，所以切换画幅不用重绑相机。这一点调整了 brainstorming §4「1:1 拍 3072×3072」的说法，成片结果相同。
3. `catalog.develop(…)`，seed 取 `Shot.id` 的哈希。
4. JPEG 质量 95。
5. 写入 MediaStore：`RELATIVE_PATH = DCIM/Pianke`，写入期间 `IS_PENDING` 为 1。同一秒内重名时，交给 MediaStore 自己去重。
6. 用 ExifInterface 写 EXIF（§6 第 13 条）。
7. `IS_PENDING` 设为 0，然后 `store.delete(shot)`，发出 `Saved(uri)`。

**启动**（`PiankeApp.onCreate`）
1. `FilmCatalog.load`。失败时只显示全屏提示，后面的步骤都不做。
2. `queue.submitRecovered(store.recover())`。
3. 相册小照片取 `developer.latest()`。

**换胶片**：下标对 15 取模 → `settings.update(filmId)` → `renderer.setFilm` → 播 `NOTCH`。

**变焦**：
- 每跨过一档：`setZoom` + `NOTCH`。
- 前置镜头只有一档，环锁死。
- 换镜头时重新绑定，计数切换到该镜头的那一卷。

## 9. 界面（视觉以画布 v38 为准）

### 9.1 布局

参照尺寸 390×866 dp，按屏幕宽度缩放。从上到下：

1. **顶栏**：MENU 键、计数鼓（`n/36`，当前镜头的那一卷）、闪光（自动 / 开 / 关，前置镜头时不可用）。
2. **取景**：360×480，3:4。1:1 时叠一层正方形遮罩。取景内还有：
   - 左下角焦段小牌，如 "23mm · 1× · 主摄"；
   - 日期水印开着时，右下角显示橙色的 `'YY MM DD`，用当天日期；
   - 网格线（开关）。
3. **变焦镜筒**：焦距刻度窗，下面是可以拖动的滚花环，每档 48 dp。NATIVE 档有橙点，DIGITAL 档用橙色数字。
4. **胶片行**：LCD、弧形标签窗、半个拨盘（§9.2）。
5. **底栏**：
   - 相册小照片：显示最近一张，点开 `ACTION_VIEW`；
   - 快门；
   - 前后翻转。

### 9.2 胶片行

**LCD**（宽 178，Share Tech Mono）

| 行 | 左 | 右 |
|---|---|---|
| 1 | `ISO {iso}` | `{process} · AWB` |
| 2 | 胶片名，大写 | — |
| 3 | 实时快门速度 | 实时光圈 |
| 4 | 状态：`DEV ●●`，或 2 s 的提示 | `{n}/15` |

`aria-live` 对应的是 Compose 的 `liveRegion`。

**标签窗**
- 形状：以拨盘圆心为中心、半径 70–164 dp 的环带，上下边被行高截断。
- 标签以拨盘圆心为轴排成扇形，每卷 30°。当前卷正放在最上层；相邻两卷转 ±30°，亮度 0.7，露出边角；更远的不显示。
- 标签尺寸 82×75 dp，带 1 dp 浅色描边和阴影。
- 拨盘是半个 112 dp 的滚花旋钮，圆心在屏幕右边缘，转动角度与标签相同，作为一个整体。

**操作**
- 在窗口上拖动 58 dp 换一档，在拨盘上拖动 26 dp 换一档；松手后吸附到最近的一档。
- 点露出的相邻标签，换到那一卷。
- 点拨盘上半或下半，换到上一卷或下一卷。
- 列表首尾相接。
- 每跨一档：播 `NOTCH`，取景立即换 LUT。

### 9.3 变焦档位

后置档位为 `ZoomStops.BACK`，`ratio = mm / 23`，经 `available()` 过滤：
- 首次真机构建要实测能不能打开长焦，以及 `CONTROL_ZOOM_RATIO_RANGE` 的实际范围；
- 到不了的档位自动消失。

### 9.4 MENU

一张纸卡，分四组：
- 拍摄：画幅（3:4 / 1:1）、日期水印、网格线；
- 冲洗：颗粒（关 / 弱 / 标准 / 强）、光晕；
- 反馈：快门声、震动；
- 其他：关于。

"保存位置"已删掉。画布的 Menu 板上还留着它，跟实现一起去掉即可。

"关于"页的内容：
- 版本、GPL-3.0、源码地址；
- spektrafilm 致谢；
- 一句商标声明："胶片名称仅用于说明所模拟的胶片，商标归各自所有者。"

### 9.5 反馈

| Cue | 什么时候 | 震动 | 音效 |
|---|---|---|---|
| NOTCH | 换胶片、变焦每档、MENU 开关 | `PRIMITIVE_TICK`，不支持时退回 `EFFECT_TICK` | 轻 "咔" |
| SHUTTER | 按下快门 | `PRIMITIVE_CLICK`，退回 `EFFECT_CLICK` | 快门声 |
| ADVANCE | 拍下之后 | 一串轻颤，约 0.3 s | 过片声 |
| REWIND | 计数从 36 回到 1 | 长一些的轻颤 | 倒片声 |
| REFUSE | 队列满、拍摄失败 | `EFFECT_DOUBLE_CLICK` | 无 |

- 声音只在 `RINGER_MODE_NORMAL` 下播放。
- 强制快门声的设备，SHUTTER 改用 `MediaActionSound.SHUTTER_CLICK`，不受开关和静音影响。
- 音效素材：v1 用 `tools/gen_sounds.py` 程序合成的原创短音（WAV），放在 `res/raw/`，来源写在 `res/raw/SOURCES.md`；以后可以换成录音或 CC0 素材。

## 10. 旧实现缺陷清单（请一次裁决）

这些来自通读 mosaico 1e6c4ca 的 `film_engine` 源码和 `films.toml`；代码中没有 TODO 或 FIXME。旧引擎只在 768×768 正方形上跑过，所以下面几条在旧设备上都不会出现。没有任何依赖方依赖这些错误行为。

| # | 现行为 | 正确行为 | 证据 |
|---|---|---|---|
| D1 | 光晕高光图固定为 96×96。在 4096×3072 上每格是 42.7×32 px，光晕横向比纵向宽 1.33 倍，竖拍则反过来。 | 格子保持正方形：长边 96 格，短边按比例取整（4:3 时为 72），半径单位不变，光晕是圆的。 | `halation.c` 两个方向都用 `THUMB_DIM` |
| D2 | 颗粒尺寸以成片像素计，按 768 宽调的参数。到 3072 短边上，颗粒相对画面细了 4 倍，几乎看不见。 | 实际尺寸 = `grain_size × min(W, H) / 768`，相对画面的观感与原调参一致。 | `films.toml` 注释「颗粒直径（成片像素）」；旧 spec 规定成片为 768×768 |
| D3 | 颗粒纹理是 256×256 的平铺块，在 4096×3072 上重复 16×12 次，天空这类平坦区域可能看出周期。 | 纹理边长 1024（临时内存 2 MB），重复 4×3 次，看不出周期。 | `grain.c` 的 `GRAIN_TEX_DIM 256` |
| D4 | 日期数字高度 = 图高 / 24。竖拍（3072×4096）时字串占宽度的 40%，横拍只占 22%。 | 数字高度 = `min(W, H) / 24`，横拍竖拍一样大。 | `date_stamp.c` 用的是 `image->height` |
| D5 | `halation.c` 注释写阈值 191（0.75），代码是 204（0.8）。 | 改注释。行为不变，不需要裁决。 | — |

**我的建议：D1–D4 全部修复。** 裁定修复的每一条，在黑盒套件里各加一条用例：新引擎断言正确行为，旧引擎标为预期不同（strict xfail）。

## 11. 测试与闸口

### 11.1 引擎黑盒套件（`tests/engine`，先建，冻结后实施者不得修改）

- **构建**：conftest 用 MSVC 把 `engine/src/*.c` 编成 DLL，用 ctypes 加载。
- **新旧两个引擎都跑**：设 `FE_IMPL=legacy` 和 `FE_SRC=<mosaico 1e6c4ca 的 film_engine 路径>` 时跑旧引擎。适配层按引擎选 `fe_image_t` 的布局：旧引擎没有 `channels`，字段名是 `rgb`。
- **沿用的契约**：
  - 目录读取；
  - 拒绝损坏的目录；
  - LUT 与参考实现一致；
  - 没有高光时光晕不改图；
  - 颗粒零均值、中间调最强；
  - 日期水印只碰自己的区域；
  - 不会因溢出回绕。

  取景表那条测试连同功能一起删除。
- **新增：RGBA 与 RGB 等价**。同一批像素走两条路径，RGB 字节相同，alpha 原样不动。旧引擎跳过这一条。
- **新增：金样**。768×768 的 3 张固定输入 × 15 卷，固定 seed，颗粒关掉，由旧引擎生成后冻结，新引擎必须逐字节一致。正方形输入上 D1、D2、D4 的修复不改变结果，所以金样新旧都能过。颗粒由统计契约覆盖：D3 会改变纹理，所以颗粒不做逐像素金样。
- **新增：D1–D4 各一条**（裁定修复的）：
  - 4:3 输入上光晕两个方向的扩散相等；
  - 颗粒的自相关宽度随短边缩放；
  - 平坦图上颗粒没有 256 周期；
  - 横竖两种朝向的日期字高相等。

### 11.2 资产一致（`tests/assets`，每次都跑，很快）

- `films.json` 的 `toml_sha256` 等于 `films.toml` 的 sha256；
- `films.toml`、`films.json`、`films.bin`（用 `filmsbin` 读）三者的 id 和顺序一致；
- 每个 id 都有 `assets/labels/<id>.png`。

### 11.3 烘焙保真（`tests/bake`）

现有的与 spektrafilm 比 ΔE 的检查，以及条目和头部检查。只在 `films/films.toml` 或 `tools/` 有改动时运行。

### 11.4 App JVM 单测（只写这两组）

- **`ZoomStopsTest`**：表驱动，覆盖范围 [1.0, 5.0]、[0.6, 10.0]、[0.8, 8.7]、只有前置等。防的是：不同机型范围不同，单台真机覆盖不到。
- **`PendingStoreTest`**：
  - 新实例能找回有 `.meta` 的帧；
  - 已删除的帧不会回来；
  - 孤立的 `.jpg` 和 `.part` 被清掉；
  - 坏掉的 `.meta` 保留文件，不抛异常。

  防的是：丢照片，而且这种情况手工很难触发。

### 11.5 真机

- **插桩测试**（`androidTest`）：
  - 金样输入经 JNI 冲洗，必须等于宿主机新引擎的输出，每通道允许 ±1；
  - 两组浮点运算的编译器可能不同，所以留这点容差。
  - 证明 NDK 构建与宿主机构建一致。
- **真机清单** `docs/device-checklist.md`，每次发布逐条跑：
  1. 哪些档位留了下来，长焦能不能用；
  2. `DCIM/Pianke` 里的文件名、EXIF、胶片名，竖拍有没有转正；
  3. 取景和成片并排对比；
  4. 拍 2 张后立刻 `adb shell am force-stop`，重开后两张都在；
  5. 拒绝权限，以及「不再询问」；
  6. 每档的震动、各个声音、静音模式；
  7. 每张的冲洗时间和内存峰值。

### 11.6 闸口：`scripts/check.ps1`

按顺序执行，第一个失败就退出，退出码非 0：
1. `pytest tests/engine tests/assets`；
2. 如果相对上游分支（没有上游时总是）改动了 `films/films.toml` 或 `tools/`，再跑 `pytest tests/bake`；
3. `gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest`，Kotlin 的 `allWarningsAsErrors = true`。

`scripts/install-hooks.ps1` 把它装成 pre-push 钩子。GitHub Actions 等 GitHub 仓库建好后再加，那时宿主机构建需要补一条 gcc/clang 路径。

## 12. 仓库、迁移与工具链

### 仓库

- 位置：`D:\projects\others\pianke`，`main` 分支，协议 GPL-3.0。LICENSE 文本取自本机 spektrafilm 发行包里的 GPL-3.0 原文。
- 目录：
  - `app/`：单个 Gradle 模块，包结构见 §2；
  - `engine/`：源码加一个普通 CMakeLists，给 NDK 用；
  - `films/films.toml`；
  - `tools/`；
  - `tests/{engine,assets,bake}`；
  - `scripts/`；
  - `docs/{superpowers,design}`。
- GitHub 远端由用户自己建，推送前先问。

### 迁移

从 mosaico `1e6c4ca` 按文件拷贝，不带历史，提交信息里写明来源。拷贝的内容：
- `films.toml`；
- `tools/bake_films.py`、`tools/filmsbin.py`、`tools/requirements.txt`；
- `tests/engine`、`tests/bake`；
- `scripts/check.ps1`（重写）、`scripts/install-hooks.ps1`；
- `film_engine`，放到 `engine/`。

`gen_font.ps1` 不拷：日期水印是程序绘制的七段数码，不用字体，这个脚本在新仓库里没有用处。这一点调整了 brainstorming §6 的清单。mosaico 的 `film-camera` 分支保持不动。

### 工具链

- minSdk 31，compile/targetSdk 36，只构建 arm64-v8a。
- NDK r27、CMake 用 SDK Manager 自带的版本，JDK 17 及以上（Android Studio 自带的 JBR 即可）。
- 版本号统一写在 `gradle/libs.versions.toml`。
- 依赖只有这些：
  - CameraX（core / camera2 / lifecycle）；
  - Compose（BOM、ui、foundation）；
  - lifecycle-viewmodel-compose；
  - DataStore Preferences；
  - ExifInterface；
  - kotlinx-coroutines。
- `films.bin` 不压缩打包：`androidResources.noCompress += "bin"`。
- applicationId 用 `app.pianke`，首次公开发布前可以改。

### 前置条件（用户自己完成）

- 安装 Android Studio，在 SDK Manager 里勾上 NDK 和 CMake。
- 手机上打开 USB 调试和 USB 安装。HyperOS 可能还要求登录小米账号并打开「USB 调试（安全设置）」。
