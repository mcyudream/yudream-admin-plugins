# neco-pixel：NECO 像素风公开站主题

参考 [RandomLemon/neco](https://github.com/RandomLemon/neco)（南京大学 Minecraft 协会站）的像素皮肤体系，以主题插件形式接管公开站外观与交互音效。

## 形态

- **纯主题插件**：不注册 HTTP 端点、权限、菜单与路由，入口类只做声明。
- `@PluginTheme(code="neco-pixel", scopes={SITE}, styles={"style.css"}, preview="preview.png", homePreset="home-preset.json")`：主题样式与首页方案打进 JAR 的 `META-INF/yudream-plugin/frontend/neco-pixel/`，样式由宿主 theme-runtime 在公开路由注入、进入后台时通过 `link.disabled` 停用。
- `@PluginFrontend(moduleName="neco-pixel")`（无 routes/styles）：仅携带音效运行时 remoteEntry；宿主在主题激活期间加载并调用 `install()`，取消激活后 `dispose()`。
- 同 scope 主题互斥由宿主保证：启用本插件会自动顶替其他 SITE 主题插件，禁用/卸载后回落宿主内置主题。

## 主题覆盖范围

| 界面 | 机制 |
|---|---|
| 公开站骨架（/site、CMS 页） | 覆写 `.site-chrome`/`.site-page` 的 `--yb-site-*` 变量（MC 浅色/深色两套，经 `html.dark` 双写），头部深底浅字、3px 硬边、hero 棋盘格压纹 |
| wiki 知识库（/wiki/**） | `.wiki-public`/`.wiki-home`/`.wiki-search-page` 覆写 Arco `--color-*` 与 `--primary-6` |
| 大事记（/timeline） | `--tl-*` 桥接变量自动跟随 `--yb-site-*`；卡片方角硬阴影、时间轴圆点改方块、隐藏模糊光斑 |
| 表单公开页（/forms/:code） | `.public-form-page` Arco 变量 + 面板方角立体边 |
| 学历核验 / 世界地图 / 题库公开页 | `.ev-page`/`.world-map-viewer`/`.qb-screen`/`.qb-shared` 并入 Arco 变量换肤组 |
| 未来新增的 siteNav 插件公开页 | 包在 `.plugin-site-page`/`.site-chrome` 内即自动继承；自带 `--xx-*` 桥接变量回退到 `--yb-site-*` 的插件（如 timeline）无需任何改动 |

像素化通用处理：全局方角（`border-radius: 0`）、MC 立体边按钮（inset bevel + 按下反转）、硬位移卡片阴影、像素标题字体（Press Start 2P，仅拉丁/数字，CJK 自动回落系统字体）、选区/焦点/内部滚动条样式。

## 自带首页方案（home-preset.json）

- 主题启用（SITE scope 激活）时，宿主自动读取 `home-preset.json` 导入为方案 `plugin:neco-pixel` 并立即应用——公开站首页整套切换为像素风演示内容，无需手工进 CMS 调整。
- **方案内容**：hero 标题/副标题 + 首页区块（FEATURE/CTA），以及方案声明的 settings 键；本插件**不声明** `navigationJson` 等键，导入时这些键保留站点当前值（合并语义：方案只覆盖它声明的键）。
- **自动快照**：应用方案前，宿主先把当前首页定制存为「切换前快照」方案（内容与最近快照一致则跳过，快照最多保留 10 份）；在 内容站点 → 首页方案 里可一键切回任何方案，或把当前定制另存为自己的方案。
- **回退**：换用其他 SITE 主题或关闭本主题时，首页方案不会被自动还原——之前的内容都在「首页方案」列表里，手动应用即可；主题 CSS 与音效仍由宿主在取消激活时完整回收。
- 维护：方案 JSON 放在前端包 `public/` 下随 dist 打包；区块 `id` 需稳定，升级主题时同 code 方案会被 upsert 覆盖。

## 音效

- WebAudio 实时合成 8-bit 芯片音（方波/三角波扫频），无音频资产；悬停短促高音、点击双音。
- 仅在主题 link 存在且未 `disabled`（即公开路由）且事件发生在公开页容器内时播放；切回后台自动静默。
- 左下角像素开关（`.neco-sound-toggle`）可随时静音，选择持久化在 `localStorage["neco-pixel:sound"]`；AudioContext 在首次 pointerdown 时解锁，不违反自动播放策略。

## 资产

- `public/fonts/press-start-2p.woff2`：Press Start 2P（SIL OFL 1.1，Google Fonts 拉丁子集，12KB）。
- `public/preview.png`：主题设置页展示的预览图（程序生成的像素 mock）。
- `public/home-preset.json`：内置首页方案（见上一节），随 dist 打进 JAR。
- 主题 CSS 经 vite lib 构建合并为 `style.css`；`@PluginFrontend(styles)` 保持为空，避免样式未经 scope 管控被常驻注入。
