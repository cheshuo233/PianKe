# 片刻 (pianke)

片刻是一个 Android 胶片相机 App：取景时用 GPU 上的 LUT 加轻颗粒预览，按下快门后由 C 写的冲洗引擎（`engine/`，经 NDK 编译）把照片冲洗成所选胶片的样子，存进 `DCIM/Pianke`。胶片的色彩数据由 `tools/bake_films.py` 从 `films/films.toml` 烘焙而来，只有 `CAMERA` 一项权限，不联网。

## 构建前提

- Android Studio，SDK Manager 里装 SDK 37 平台、NDK 30.0.16248370、CMake 4.1.2；`local.properties` 里有 `sdk.dir`。
- 宿主机测试：MSVC 2022 Build Tools，以及 `tools/.venv`（Python 3.13，按 `tools/requirements.txt` 安装）。

## 构建与安装

```
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:installDebug
```

JDK 17 及以上；用 Android Studio 自带的 `jbr` 即可。

## 烘焙胶片

改了 `films/films.toml` 或 `tools/` 之后重新烘焙：

```
tools\.venv\Scripts\python.exe tools/bake_films.py --toml films/films.toml --out app/src/main/assets/films.bin --json app/src/main/assets/films.json
```

## 闸口与钩子

```
pwsh -NoProfile -File scripts\check.ps1
pwsh -NoProfile -File scripts\install-hooks.ps1
```

`check.ps1` 依次跑 `pytest tests/engine tests/assets`、（`films.toml` 或 `tools/` 相对上游有改动或没有上游时）`pytest tests/bake`，最后是 `gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest`；任何一步失败即以非 0 退出，成功时最后一行是 `check passed`。JAVA_HOME 未设置时取注册表里 Android Studio 的 `jbr`。`install-hooks.ps1` 把它装成 `.git/hooks/pre-push`，重复运行无害。

## 插桩测试（HyperOS）

HyperOS 拒绝 `install -g`（INSTALL_GRANT_RUNTIME_PERMISSIONS），所以 `connectedDebugAndroidTest` 会跑出 0 个测试。改用：

1. `adb install -r -t` 分别装 app 和 test 两个 APK，在手机上确认每次安装提示；
2. `adb shell am instrument -w -r app.pianke.test/androidx.test.runner.AndroidJUnitRunner`。

## 真机清单

每次发布前按 [docs/device-checklist.md](docs/device-checklist.md) 在真机上逐条跑，结果记在该文件末尾的表里。

## 协议与致谢

- 代码：GPL-3.0，见 `LICENSE`。
- 字体：Share Tech Mono、IBM Plex Mono，均为 OFL，协议文本在 `app/src/main/assets/licenses/`。
- 音效：原创合成，来源见 `tools/SOUND_SOURCES.md`。
- 胶片模拟数据来自 [spektrafilm](https://github.com/andreavolpato/spektrafilm)，感谢其作者。

胶片名称仅用于说明所模拟的胶片，商标归各自所有者。
