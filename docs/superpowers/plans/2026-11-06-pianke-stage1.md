# 片刻 阶段一 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal**：在现有单 LUT 引擎上做出能装到 K90 上用的完整胶片相机 App：取景、选胶片、拍摄、冲洗、存相册、MENU。

**Architecture**：
- `engine/` 是不依赖平台的 C11 引擎，从 mosaico 迁来，加上 RGBA 路径和 D1–D4 修复。
- App 是单个 Gradle 模块，分 `film / settings / feedback / camera / develop / ui` 六个包，通过 JNI 调用引擎。
- 胶片数据由 `films.toml` 烘焙成 `films.bin` + `films.json`，提交到 assets。

**Tech Stack**：
- Kotlin、Jetpack Compose、CameraX（加 Camera2Interop）、DataStore、ExifInterface；
- NDK r27 + CMake；
- 宿主机测试：Python 3.13、pytest、ctypes、MSVC 2022；
- 烘焙：spektrafilm（锁定 commit）。

**Spec**：`docs/superpowers/specs/2026-11-06-pianke-design.md`。下文的「§n」都指这份 spec 的章节。

**裁决**：D1–D4 全部修复（用户，2026-11-06）。

## 全局约束

- minSdk 31，compileSdk / targetSdk 36，只构建 arm64-v8a；NDK r27；JDK 17 及以上。
- 运行时依赖只有：CameraX（core / camera2 / lifecycle）、Compose（BOM、ui、foundation）、lifecycle-viewmodel-compose、DataStore Preferences、ExifInterface、kotlinx-coroutines。
- 测试依赖只有：junit4、androidx.test（runner、ext-junit）。
- 加其他依赖必须先写明违背了 spec 的哪条契约。
- 包根和 applicationId 都是 `app.pianke`；原生库名 `pianke`；包依赖方向见 §2，不得反向，不得成环。
- 权限只有 `CAMERA`，不加 `INTERNET`；没有崩溃上报和统计。
- Kotlin `allWarningsAsErrors = true`；宿主机 C 用 `/std:c11 /W4 /WX`，NDK 用 `-Wall -Wextra -Werror`。
- `engine/` 不 include 任何平台头文件。
- 界面文案逐字使用 §7 和 §9 的中文，统一放在 `res/values/strings.xml`。
- 测试：不要求 TDD。App 的 JVM 单测只有 §11.4 的两组；插桩测试只有 `GoldenDevelopTest` 和 `DeveloperTest`。
- **冻结**：任务 1 完成后，`tests/engine` 和 `tests/assets` 冻结，之后的实施者不得修改。
- 协议 GPL-3.0；音效是原创合成的；字体只用 OFL 授权的。

## 执行方式（按全局 CLAUDE.md）

- 实施在 `stage1` 分支上做，从 `main` 切出。
- 每个任务派一个 Sonnet 实施者，按顺序执行。
- 任务 2、4–8 先提交能编译的骨架：目录、类型、签名齐全，Kotlin 函数体写 `TODO()`，C 的阶段函数体留空并返回错误码。骨架由一个 Opus 只审组织：
  - 规则是否只在一处（对照 §6）；
  - 依赖方向是否符合 §2；
  - 有没有用不上的抽象。

  审过才填函数体。
- 不做逐任务评审：验收看下面每个任务的命令。
- 全部完成后做一次收尾双审，正确性一席、优雅性一席，各派一个 Opus；之后派一个 Sonnet 修复，最多两轮。

## 前置条件

- **任务 1 之前**：把 mosaico 的 `tools/.venv` 拷到 `pianke/tools/.venv`，不需要下载。然后运行 `tools\.venv\Scripts\python -c "import spektrafilm, colour, pytest"`，必须无报错。若失败，停下来请用户批准用 `uv` 按 `tools/requirements.txt` 安装，这一步会下载。
- **任务 4 之前**：
  - 用户已装好 Android Studio，并在 SDK Manager 里勾选 SDK 36、NDK r27、CMake；
  - `local.properties` 里有 `sdk.dir`；
  - 第一次构建会由 Gradle 下载 Gradle 发行版和 Maven 依赖，先告知用户。
- **任务 8 之前**：用户同意从 `github.com/google/fonts` 下载 Share Tech Mono 和 IBM Plex Mono（OFL）的 TTF。
- **涉及真机的验收**：K90 已用 USB 连上，开启了 USB 调试和 USB 安装，`adb devices` 能列出它。

## Review Focus（收尾评审必须逐条核对）

1. **竖拍和 1:1**：成片像素已转正，EXIF Orientation 为 1，日期在转正后的右下角，1:1 取的是中心正方形。由任务 7 的 `DeveloperTest` 和真机清单第 2 条覆盖。
2. **保存 MediaStore 时存储已满**：成片不残留在 DCIM（失败时删掉 `IS_PENDING` 的那一行），帧留在 `pending/`，LCD 显示 "存储已满"。ENOSPC 要沿着异常的 cause 链去找。
3. **连按快门**：上一次 `capture` 没返回时不响应；队列里已有 2 帧时播 `REFUSE`；被拒绝时计数不动。由真机清单第 8 条覆盖。
4. **相机被别的 App 占用，或从后台返回**：显示 "相机被占用"；相机空出来后取景自动恢复，`issue` 清空。由真机清单第 9 条覆盖。
5. **保存的 `filmId` 已不在 `films.toml` 里**（改名或删除）：回退到第一卷，不崩溃，并写回新的 `filmId`。

---

### 任务 1：仓库骨架迁移与黑盒套件（建好后冻结）

**文件**
- **从 mosaico `1e6c4ca` 原样拷贝**（源目录：`D:\projects\others\mosaico`，当前检出就是这个 commit）：
  - `firmware/components/film_engine/{include,src}` → `engine/{include,src}`，本任务不改内容；
  - `films/films.toml`；
  - `films/films.bin` → `app/src/main/assets/films.bin`；
  - `tools/{bake_films.py,filmsbin.py,requirements.txt}`；
  - `pytest.ini`；
  - `tests/bake/*`；
  - `tests/engine/*`。
- **新建**：
  - `.gitignore`：`tools/.venv`、`tests/engine/_build`、`.gradle`、`build/`、`local.properties`、`.superpowers/`；
  - `.gitattributes`：`* text=auto eol=lf`；`*.bat text eol=crlf`；`*.png *.webp *.bin *.wav *.ttf *.jar` 设为 binary；
  - `LICENSE`：取 `mosaico/tools/.venv/Lib/site-packages/spektrafilm-0.3.4.dist-info/licenses/` 里的 GPL-3.0 原文；
  - `tests/engine/make_golden.py`；
  - `tests/engine/golden/`；
  - `tests/assets/test_assets.py`。
- **修改**：
  - `tests/engine/conftest.py`：
    - 源码目录默认是 `engine/`，可以用 `FE_SRC` 覆盖；
    - `FE_IMPL=legacy|new`（默认 `new`）决定 `fe_image_t` 的 ctypes 布局：legacy 的字段是 `rgb`，没有 `channels`；
    - `films.bin` 路径改为 `app/src/main/assets/films.bin`。
  - `tests/engine/test_engine.py`：删除 `test_preview_table_matches_lut`，按下面的清单增加用例。
  - `tests/bake/*`：`films.bin` 的路径同上。

**落实**：§11.1、§11.2、§10（每条裁定修复的缺陷一条用例）、§12「迁移」。

**新增用例**（legacy 下的预期写在括号里）
- `test_rgba_matches_rgb`：随机图，完整流程（光晕、颗粒、日期都开）。同一批像素分别走 `channels` 3 和 4。RGB 字节必须相同，alpha 字节不变。（legacy：skip）
- `test_golden_square`：3 张 768×768 的平滑合成输入 × 15 卷，`grain_strength` 置 0，`date = NULL`，`seed = 1`，与 `golden/expected/<film>_<input>.png` 逐字节一致。三张输入不含逐像素噪声，以控制 PNG 体积：
  1. 色相 / 明度扫描；
  2. 黑底上三个不同大小的白圆盘；
  3. 6×4 块柔边中间调色块。
- `test_d1_halation_round_on_4x3`：1024×768 黑底，中心一个半径 40 px 的白色圆盘，取光晕强度最大的一卷，颗粒为 0。光晕 = 同一卷分别开、关光晕（`halation_strength` 置 0）冲洗两次的差值。在过中心的横向和纵向剖面上，光晕降到峰值 10% 处的半径之比在 [0.9, 1.1] 内。（legacy：strict xfail）
- `test_d2_grain_scales_with_short_side`：取 grain size 最大的一卷；均匀灰图，768×768 和 3072×3072 各冲一次。颗粒 = 开、关颗粒（`grain_strength` 置 0）两次冲洗的差值。沿 x 方向求它的自相关，用线性插值得出降到 0.5 处的分数滞后，两者之比在 [3.0, 5.0] 内。（legacy：strict xfail）
- `test_d3_grain_no_256_period`：2048×512 均匀灰图，颗粒差值的求法同 D2。差值与自身水平平移 256 px 后的相关系数小于 0.2。（legacy：strict xfail）
- `test_d4_date_height_from_short_side`：`film = NULL`，只印日期；768×1024 和 1024×768 各印一次。被改动像素所在的行数，两者相等。（legacy：strict xfail）
- `tests/assets/test_assets.py`（§11.2，三条）：
  - `films.json` 的 `toml_sha256` 等于 `films.toml` 的 sha256；
  - 三处的 id、顺序，以及 name/iso/process 与 toml 的 display/iso/process 一致；
  - 每个 id 都有 `app/src/main/assets/labels/<id>.png`。

`make_golden.py` 在 `FE_IMPL=legacy` 下生成输入图和期望输出。输入图由固定参数的 numpy 生成，可以重复生成；生成一次后提交。

**验收**
- [ ] `$env:FE_IMPL='legacy'; tools\.venv\Scripts\python -m pytest tests/engine`
  - 默认 `FE_SRC` 就是 `engine/`，此时它还是旧代码。
  - 预期：全部通过；`4 xfailed`（D1–D4）；`test_rgba_matches_rgb` 被 skip。
- [ ] 同上，再加 `$env:FE_SRC='D:\projects\others\mosaico\firmware\components\film_engine'`，预期结果相同。
- [ ] `tools\.venv\Scripts\python -m pytest tests/bake`：预期全部通过（较慢）。
- [ ] `tools\.venv\Scripts\python -m pytest tests/assets`：预期失败，因为 `films.json` 不存在，要到任务 3 才变绿。这是预期中的唯一失败。
- [ ] `tests/engine/golden` 总体积小于 15 MB。
- [ ] 提交 `test: migrate engine and tools from mosaico 1e6c4ca, freeze black-box suite`，正文写明来源 commit 和 D1–D4 的裁决。

### 任务 2：引擎移植（RGBA、删除取景表、修复 D1–D4）

**文件**
- 修改：
  - `engine/include/film_engine.h`；
  - `engine/src/pixel.h`；
  - `engine/src/develop.c`、`lut.c/.h`、`halation.c/.h`、`grain.c/.h`、`date_stamp.c/.h`；
  - `catalog.c`，只改注释：去掉「size_t 在目标上是 32 位」这类设备专属的说法，保留回绕检查。
- 删除：`engine/src/preview.c`。
- 新建：`engine/CMakeLists.txt`，内容是 `add_library(film_engine STATIC …)`，C11，加上全局约束里的警告选项。

**落实**：§3.1、§4.1、§6 第 1–6 条、§10 D1–D5。具体值：
- D1：高光图长边 96 格，短边 `round(96 × 短/长)`，至少 1 格。
- D2：颗粒尺寸 × `min(W, H) / 768`。
- D3：`GRAIN_TEX_DIM 1024`。
- D4：数字高度 `min(W, H) / 24`。
- D5：把注释里的阈值改成 204（0.8）。

**骨架**：头文件改动；`pixel.h` 的寻址 / 步长辅助函数；各阶段签名改成按 `channels` 步进。

**验收**
- [ ] `tools\.venv\Scripts\python -m pytest tests/engine`（new 模式）：全部通过，没有 xfail，也没有 skip。
- [ ] 任务 1 的两条 legacy 命令：结果不变（4 xfailed）。说明套件没有被改动。
- [ ] `git diff main -- tests/engine tests/assets` 为空。
- [ ] 提交 `engine: RGBA path, drop preview table, fix D1-D4`。

### 任务 3：胶片数据与烘焙输出

**文件**
- 修改：
  - `films/films.toml`：每卷加上 `display`、`iso`、`process`，取值见下表；
  - `tools/bake_films.py`：
    - 新增 `--json <path>`，写出 §3.3 的 `films.json`（`toml_sha256` 和 `films`），用 UTF-8、`ensure_ascii=False`；
    - `--out` 照旧写 `films.bin`。
- 再生成：`app/src/main/assets/films.bin`、`app/src/main/assets/films.json`。

| id | display | iso | process |
|---|---|---|---|
| portra160 | Portra 160 | 160 | C-41 |
| portra400 | Portra 400 | 400 | C-41 |
| portra800 | Portra 800 | 800 | C-41 |
| gold200 | Gold 200 | 200 | C-41 |
| ultramax400 | UltraMax 400 | 400 | C-41 |
| ektar100 | Ektar 100 | 100 | C-41 |
| superia400 | Superia X-TRA 400 | 400 | C-41 |
| c200 | C200 | 200 | C-41 |
| pro400h | Pro 400H | 400 | C-41 |
| vision3_500t | Vision3 500T | 500 | ECN-2 |
| vision3_250d | Vision3 250D | 250 | ECN-2 |
| kodachrome64 | Kodachrome 64 | 64 | K-14 |
| velvia100 | Velvia 100 | 100 | E-6 |
| provia100f | Provia 100F | 100 | E-6 |
| ektachrome100 | Ektachrome 100 | 100 | E-6 |

**落实**：§3.3、§1 K1/K2、§6 第 8 条。

**验收**
- [ ] `tools\.venv\Scripts\python tools/bake_films.py --toml films/films.toml --out app/src/main/assets/films.bin --json app/src/main/assets/films.json` 成功。
- [ ] `git diff --stat -- app/src/main/assets/films.bin` 预期为空：影响 LUT 的字段和 spektrafilm 版本都没变。如果有差异，`tests/bake` 仍必须通过，并在提交信息里写明差异的大小和原因。
- [ ] `tools\.venv\Scripts\python -m pytest tests/assets tests/bake tests/engine`：全部通过。
- [ ] 提交 `films: display name, iso and process; bake films.json`。

### 任务 4：Android 工程、`film` 包与 JNI

**文件**
- 新建 Gradle 工程：`settings.gradle.kts`、根 `build.gradle.kts`、`gradle.properties`、`gradle/libs.versions.toml`，以及 Gradle wrapper（`gradlew`、`gradlew.bat`、`gradle/wrapper/*`）。
- 新建 `app/build.gradle.kts`：
  - `namespace` 和 `applicationId` 都是 `app.pianke`；
  - SDK 版本和 ABI 按全局约束；
  - `ndkVersion` 写本机装的 r27 的确切版本号；
  - `externalNativeBuild.cmake.path = "src/main/cpp/CMakeLists.txt"`；
  - `androidResources.noCompress += "bin"`；
  - Compose 开启；
  - androidTest 的 assets 源目录加上 `../tests/engine/golden`。
- 新建 `app/src/main/AndroidManifest.xml`：只申请 `CAMERA` 权限；单个 `MainActivity`；竖屏锁定。
- 新建 `app/src/main/cpp/CMakeLists.txt`（`add_subdirectory(../../../../engine …)`，产出 `libpianke.so`）和 `app/src/main/cpp/pianke_jni.c`。
- 新建 `app/src/main/java/app/pianke/`：
  - `PiankeApp.kt`：本任务只加载 `FilmCatalog`；
  - `MainActivity.kt`：占位，显示胶片数量，或者显示「胶片库损坏，请重新安装」；
  - `film/Film.kt`、`film/FilmCatalog.kt`、`film/FilmEngine.kt`、`film/Errors.kt`（`EngineException`、`FilmCatalogBroken`）。
- 新建 `app/src/androidTest/java/app/pianke/film/GoldenDevelopTest.kt`。

**落实**：§2（film 和 JNI 两行）、§3.2 film、§3.3、§4.2、§4.3 film、§6 第 9、10、20、25 条、§7 前两行、§12 工具链。

**接口**：后面所有任务都通过 `FilmCatalog` 取胶片：`films`、`lutSize`、`lut(film)`、`develop(…)`，签名见 §4.3。

**`GoldenDevelopTest`**：
- 从 androidTest assets 读入 `inputs/*.png`，转成可写的 ARGB_8888 位图；
- 每卷调用 `develop(film, grainScale = 0f, halation = true, date = null, seed = 1, bitmap)`；
- 与 `expected/<film>_<input>.png` 比较，每通道差 ≤ 1。

**验收**
- [ ] `.\gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest`：`BUILD SUCCESSFUL`。
- [ ] 真机 `.\gradlew :app:connectedDebugAndroidTest`：`GoldenDevelopTest` 45 个比较全部通过。
- [ ] 真机安装后，占位页显示「15」。
- [ ] 提交 `app: gradle project, film catalog and JNI bridge`。

### 任务 5：`settings` 与 `feedback`

**文件**
- 新建：
  - `app/src/main/java/app/pianke/settings/{Settings.kt, SettingsStore.kt, Roll.kt}`；
  - `app/src/main/java/app/pianke/feedback/Feedback.kt`；
  - `tools/gen_sounds.py`：用 numpy 确定性地合成 `notch`、`shutter`、`advance`、`rewind` 四段，16-bit 单声道 44.1 kHz WAV，各不超过 0.6 s；
  - `app/src/main/res/raw/{notch,shutter,advance,rewind}.wav`；
  - `app/src/main/res/raw/SOURCES.md`。
- 修改：`PiankeApp.kt`，持有 `SettingsStore` 和 `Feedback`。

**落实**：§3.2 settings、§4.3 settings / feedback、§6 第 21、22、24 条、§9.5、§1 K6。

**验收**
- [ ] `.\gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest`：`BUILD SUCCESSFUL`。
- [ ] 再运行一次 `tools\.venv\Scripts\python tools/gen_sounds.py`，`git status --short app/src/main/res/raw` 为空：输出是确定性的。
- [ ] 提交 `app: settings store, roll counter and feedback`。

### 任务 6：`camera` 包

**文件**
- 新建 `app/src/main/java/app/pianke/camera/`：
  - `ZoomStops.kt`：包含 `ZoomStop`、`StopKind`、`BACK`、`front(mm: Int)` 和 `available()`。
    - `front(mm)` 只有一档：`ZoomStop(mm, 1f, NATIVE)`；
    - `mm` 是前置镜头的 35 mm 等效焦距，由 `CameraController` 根据 `LENS_INFO_AVAILABLE_FOCAL_LENGTHS` 和 `SENSOR_INFO_PHYSICAL_SIZE` 算出（对角线 43.27 mm）。
  - `CameraIssue.kt`（含 `Exposure`）；
  - `CameraController.kt`；
  - `PreviewRenderer.kt`：GLES 3.0，OES 纹理 → 33³ 的 3D LUT 纹理 → 按 `setGrain` 倍率叠加轻颗粒。
- 新建 `app/src/test/java/app/pianke/camera/ZoomStopsTest.kt`。

**落实**：§3.2 camera、§4.3 camera、§5、§6 第 16–19 条、§7 中 CameraX 相关的三行和拍摄失败一行、§9.3。

**`ZoomStopsTest` 表**（输入范围 → 留下的 mm，以及被夹住的 ratio）

| 范围 | 留下的档位 | 备注 |
|---|---|---|
| [1.0, 5.0] | 23, 28, 35, 50, 115 | |
| [1.0, 2.0] | 23, 28, 35 | |
| [0.8, 8.7] | 全部 8 档 | 18 档的 ratio 被夹到 0.8 |
| [0.6, 10.0] | 全部 8 档 | ratio 不变 |
| 前置 `front(24)`，范围 [1.0, 1.0] | 只有 24 那一档 | |

**验收**
- [ ] `.\gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest`：`ZoomStopsTest` 通过，`BUILD SUCCESSFUL`。
- [ ] 提交 `app: camera controller, zoom stops and GL preview`。

### 任务 7：`develop` 包

**文件**
- 新建 `app/src/main/java/app/pianke/develop/`：`Shot.kt`（含 `Outcome`、`KeptReason`）、`PendingStore.kt`、`Developer.kt`、`DevelopQueue.kt`。
- 修改 `PiankeApp.kt`：持有 `PendingStore`、`Developer`、`DevelopQueue`；启动时按 §8 依次执行 `FilmCatalog.load` → `submitRecovered(recover())`。
- 新建 `app/src/test/java/app/pianke/develop/PendingStoreTest.kt`。
- 新建 `app/src/androidTest/java/app/pianke/develop/DeveloperTest.kt`。

**落实**：§3.2 develop、§3.4、§4.3 develop、§5、§6 第 11–15 条、§7 后两行、§8「冲洗」和「启动」、Review Focus 第 1、2 条。

**`PendingStoreTest`**（临时目录）
- `commit` 之后，新建一个实例，`recover()` 返回这一帧，字段完全相同；
- `delete` 之后，`recover()` 为空；
- 只有 `.jpg`、没有 `.meta` 的，以及所有 `.part`，`recover()` 之后都被删掉；
- `.meta` 写坏了（或 `v=9`）：`recover()` 不抛异常、不返回这一帧，文件仍然保留。

**`DeveloperTest`**（真机）
- 合成一张 400×300 的 JPEG，EXIF Orientation 为 6：
  - `FOUR_THREE`：成片是 300×400，Orientation 为 1，ImageDescription 等于 `Film.name`，路径是 `DCIM/Pianke/`；
  - `SQUARE`：成片是 300×300。
- 测试结束时删除它在 MediaStore 里建的所有行。

**验收**
- [ ] `.\gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest`：`PendingStoreTest` 通过。
- [ ] 真机 `.\gradlew :app:connectedDebugAndroidTest`：`DeveloperTest` 和 `GoldenDevelopTest` 都通过。
- [ ] 提交 `app: pending store, developer and develop queue`。

### 任务 8：`ui` 与整机联调

**文件**
- 新建 `app/src/main/java/app/pianke/ui/`：
  - `ShooterUiState.kt`（含 `Permission`）、`ShooterViewModel.kt`；
  - `CameraScreen.kt`、`TopBar.kt`、`ZoomRing.kt`、`FilmRow.kt`、`BottomBar.kt`；
  - `MenuScreen.kt`，「关于」页就在这里面；
  - `PermissionGate.kt`。
- 修改 `MainActivity.kt`：
  - edge-to-edge；
  - 用 `OrientationEventListener` 得到拍摄方向；
  - 出现 `FilmCatalogBroken` 时显示全屏提示。
- 新建：
  - `app/src/main/res/values/strings.xml`：§7 和 §9 的全部文案；
  - `app/src/main/res/font/{share_tech_mono.ttf, ibm_plex_mono_medium.ttf, ibm_plex_mono_semibold.ttf}`，以及 `app/src/main/res/font/OFL.txt`；
  - `docs/device-checklist.md`：§11.5 的 7 条，再加 Review Focus 第 3、4 条，作为第 8、9 条。

**落实**：
- §8 全部，§9 全部；
- §7 中用户可见的部分；
- Review Focus 第 3、4、5 条：`filmId` 找不到时回退到第一卷，并写回设置；
- 几何、颜色、尺寸以 `docs/design/canvas-v38/Main.dc.html` 和 `Menu.dc.html` 为准，Menu 里去掉「保存位置」。

**验收**
- [ ] `.\gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest`：`BUILD SUCCESSFUL`。
- [ ] 真机安装后，逐条跑 `docs/device-checklist.md` 的 1–9 条。每条的结果（通过，或现象加截图路径）记在该文件的「首次运行」表里。第 1 条的实测档位记下来；到不了的档位已经被自动去掉。
- [ ] 提交 `app: shooter UI, film row and menu`。

### 任务 9：闸口与说明

**文件**
- 重写 `scripts/check.ps1`：按 §11.6 的三步执行。「是否改动了 `films.toml` / `tools`」用 `git diff --name-only '@{u}...HEAD'` 判断；没有上游分支时总是跑 `tests/bake`。
- 从 mosaico 拷贝 `scripts/install-hooks.ps1`，改成在新仓库里装 pre-push 钩子。
- 新建 `README.md`：
  - 构建前提（同本文的「前置条件」）；
  - 烘焙命令；
  - `check.ps1` 和钩子的用法；
  - GPL-3.0 说明；
  - §9.4 的商标声明。

**落实**：§11.6、§12。

**验收**
- [ ] `powershell -NoProfile -File scripts\check.ps1`：退出码 0，最后一行是 `check passed`。
- [ ] `powershell -NoProfile -File scripts\install-hooks.ps1` 之后，`.git/hooks/pre-push` 存在，并且会调用 `check.ps1`。
- [ ] 提交 `scripts: check gate and pre-push hook`。

### 收尾

- [ ] 全分支双审，正确性和优雅性两席各一个 Opus。范围是 `main..stage1`，Review Focus 的 5 条必须逐条核对。优雅性一席要回答「哪些测试删了也不损失验证力」。
- [ ] 两席结论合并后，派一个 Sonnet 修复，最多两轮。修复后重跑 `scripts\check.ps1`，以及真机上的两个插桩测试。
- [ ] 按 superpowers:finishing-a-development-branch 合并回 `main`。推送前先问用户。
