# neco-pixel：NECO 像素风公开站主题

参考 [RandomLemon/neco](https://github.com/RandomLemon/neco)（南京大学 Minecraft 协会站）的像素皮肤体系，以主题插件形式接管公开站外观与交互音效。

## 形态

- **纯主题插件**：不注册 HTTP 端点、权限、菜单与路由，入口类只做声明。
- `@PluginTheme(code="neco-pixel", scopes={SITE}, styles={"style.css"}, preview="preview.png")`：主题样式打进 JAR 的 `META-INF/yudream-plugin/frontend/neco-pixel/style.css`，由宿主 theme-runtime 在公开路由注入、进入后台时通过 `link.disabled` 停用。
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

## 音效

- WebAudio 实时合成 8-bit 芯片音（方波/三角波扫频），无音频资产；悬停短促高音、点击双音。
- 仅在主题 link 存在且未 `disabled`（即公开路由）且事件发生在公开页容器内时播放；切回后台自动静默。
- 左下角像素开关（`.neco-sound-toggle`）可随时静音，选择持久化在 `localStorage["neco-pixel:sound"]`；AudioContext 在首次 pointerdown 时解锁，不违反自动播放策略。

## 资产

- `public/fonts/press-start-2p.woff2`：Press Start 2P（SIL OFL 1.1，Google Fonts 拉丁子集，12KB）。
- `public/preview.png`：主题设置页展示的预览图（程序生成的像素 mock）。
- 主题 CSS 经 vite lib 构建合并为 `style.css`；`@PluginFrontend(styles)` 保持为空，避免样式未经 scope 管控被常驻注入。
