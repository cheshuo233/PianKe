# 真机清单

每次发布逐条跑。设备：Redmi K90 Pro Max（K90），USB 已连，`adb devices` 能列出。结果记在末尾「首次运行」表里：通过，或现象加截图路径。

## 清单

1. **档位与长焦**：哪些档位留了下来，长焦（115 / 135 / 200 mm）能不能用。记下实测档位和 `CONTROL_ZOOM_RATIO_RANGE`；到不了的档位应当已自动消失。
2. **成片文件**：`DCIM/Pianke` 里的文件名（`PK_yyyyMMdd_HHmmss.jpg`）、EXIF（曝光时间、ISO、光圈、焦距、拍摄时间、Make/Model、ImageDescription 为胶片名、Software 为 `Pianke <版本号>`、Orientation 为 1）；竖拍有没有转正，日期在转正后的右下角；1:1 取的是中心正方形。
3. **取景与成片并排**：同一场景，比较取景（GPU LUT + 轻颗粒）和成片的色调、颗粒观感。
4. **杀进程后恢复**：拍 2 张后立刻 `adb shell am force-stop app.pianke`，重开后两张都在 `DCIM/Pianke`，`pending/` 清空。
5. **权限**：拒绝相机权限（显示「需要相机权限」和按钮）；再选「不再询问」（按钮变为打开应用设置页，授权后返回能直接取景）。
6. **反馈**：每一档变焦和每次换胶片的震动与 NOTCH 声；快门、过片、倒片（拍到第 36 张后）、拒绝各自的震动与声音；静音模式下只震动不出声；MENU 开关的 NOTCH。
7. **冲洗时间和内存峰值**：每张的冲洗耗时（logcat 或 `adb shell dumpsys meminfo app.pianke` 的峰值），3:4 与 1:1 各记一组。
8. **连按快门与满队列**（Review Focus 3）：
   - 快门快速连按：上一次 `capture` 没返回时不响应，不多拍；
   - 队列里已有 2 帧时再按：播 REFUSE，不拍，计数不动；
   - 队列腾出位置后恢复正常，计数只在拍下后 +1。
9. **相机被占用与从后台返回**（Review Focus 4）：
   - 在另一个 App（如系统相机）里打开相机，再切回片刻：取景区显示「相机被占用」；
   - 另一个 App 释放相机后，取景自动恢复，提示消失（`issue` 清空）；
   - 按 Home 进后台再返回，取景恢复，不需要重开。

## 首次运行需确认

前面任务留下的、只能上真机确认的项：

- [ ] 取景的旋转、镜像和裁切：后置、前置各一遍，竖拍和 1:1 都看。
- [ ] 着色器能否在设备 GPU 上编译（logcat 无 GL 错误，取景不是黑屏）。
- [ ] LUT 和颗粒在取景里的观感（颗粒 关 / 弱 / 标准 / 强 四档）。
- [ ] `RENDERMODE_WHEN_DIRTY` 下取景持续刷新，不卡帧。
- [ ] 重绑一次（rebind-once）：造成可恢复错误时先重绑一次，再失败才显示「相机出错，请重开」。
- [ ] 变焦范围：`CONTROL_ZOOM_RATIO_RANGE` 的实际范围，与第 1 条的档位对得上。
- [ ] 存储已满时抛出的是哪种异常（`IOException` 的 cause 链里有没有 ENOSPC），LCD 是否显示「存储已满」，`DCIM/Pianke` 不残留。
- [ ] EXIF Orientation 不是 6 的情形（2、3、4、5、7、8）：成片都被转正，Orientation 为 1。产生方法：后置竖拍通常得 6；把手机转到另外三个方向各拍一张，得 1、3、8；镜像值（2、4、5、7）如果会出现，只会来自前置镜头，在四个方向各拍一张。记下实际出现了哪些。
- [ ] 两个插桩测试：`GoldenDevelopTest`（任务 4，JNI 胶水层此前从未真正执行）和 `DeveloperTest`（任务 7）。**已在 K90（Android 17）上通过：`OK (3 tests)`。** 运行方法：HyperOS 拒绝 `install -g`（INSTALL_GRANT_RUNTIME_PERMISSIONS），所以 `connectedDebugAndroidTest` 会跑出 0 个测试。改用 `adb install -r -t` 装 app 和 test 两个 APK（手机上确认安装提示），再运行 `adb shell am instrument -w -r app.pianke.test/androidx.test.runner.AndroidJUnitRunner`。
- [ ] 闪光灯：后置 AUTO / 开 / 关真的各自生效（开时闪，关时不闪）；前置镜头上闪光灯按钮不可用，拍摄时不闪（任务 6）。
- [ ] 拍摄尺寸：成片是不超过 4096×3072 的最大 4:3 尺寸（任务 6）。
- [ ] Camera2 参数是否真的生效：记下 K90 列出了 EDGE_MODE_OFF、NOISE_REDUCTION_MODE_MINIMAL、TONEMAP sRGB 预设曲线中的哪几项（任务 6）。
- [ ] LCD 的实时快门速度和光圈会随场景明暗更新，即曝光回调在这台设备上会触发。

## 首次运行

| # | 项 | 结果（通过，或现象加截图路径） | 备注 |
|---|---|---|---|
| 1 | 档位与长焦 | | 实测档位： |
| 2 | 成片文件 | | |
| 3 | 取景与成片并排 | | |
| 4 | 杀进程后恢复 | | |
| 5 | 权限 | | |
| 6 | 反馈 | | |
| 7 | 冲洗时间和内存峰值 | | |
| 8 | 连按快门与满队列 | | |
| 9 | 相机被占用与从后台返回 | | |
| A | 取景旋转、镜像、裁切 | | |
| B | 着色器编译 | | |
| C | LUT 和颗粒观感 | | |
| D | WHEN_DIRTY 取景 | | |
| E | rebind-once | | |
| F | 变焦范围 | | |
| G | 存储已满的异常 | | |
| H | Orientation 非 6 | | 出现过的值： |
| I | `GoldenDevelopTest` | 通过（K90，Android 17） | 与 J 一起 `OK (3 tests)`；`adb install -r -t` + `am instrument` |
| J | `DeveloperTest` | 通过（K90，Android 17） | 同上 |
| K | 闪光灯 AUTO / 开 / 关，前置不可用 | | |
| L | 拍摄尺寸 | | 实测： |
| M | Camera2 参数 | | 设备列出： |
| N | LCD 实时快门与光圈 | | |
