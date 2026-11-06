# 界面设计参考

- `canvas-v38/`：设计画布 v38 的源文件，Main 是主界面，Menu 是设置页。画布地址：https://claude.ai/artifact/GmNEM6YpQpcN44VF1GpyE3（需要登录）。
  - `.dc.html` 是画布自己的组件格式：`{{…}}` 是绑定，`<sc-for>` 是循环，脚本里的 `renderVals()` 算出所有几何参数。
  - 尺寸、颜色、扇形角度等都以这里为准。
  - 里面的 `/_blob/…` 图片地址只在画布中有效。它们与 `app/src/main/assets/labels/` 一一对应，顺序同 `films.toml`。
  - Menu 板上的「保存位置」已按 spec 删除，以 spec 为准。
- `labels-sheet.webp`：标签原图，3 行 × 5 列，顺序同 `films.toml`。App 用的是从中裁出的 288×264 PNG，见 `app/src/main/assets/labels/<id>.png`。
