# neco-pixel：NECO 像素风公开站主题

完整复刻 [RandomLemon/neco](https://github.com/RandomLemon/neco)（南京大学 Minecraft 协会站，MIT License）的主题体系，以主题插件形式接管公开站外观、交互音效、强调色体系与整套像素风页面。

## 形态

- **纯主题插件**：不注册 HTTP 端点、权限、菜单与路由，入口类只做声明。
- `@PluginTheme(code="neco-pixel", scopes={SITE}, styles={"style.css"}, preview="preview.png", homePreset="home-preset.json")`：主题样式与首页方案打进 JAR 的 `META-INF/yudream-plugin/frontend/neco-pixel/`，样式由宿主 theme-runtime 在公开路由注入、进入后台时通过 `link.disabled` 停用。
- `@PluginFrontend(moduleName="neco-pixel")`（无 routes/styles）：仅携带运行时 remoteEntry；宿主在主题激活期间加载并调用 `install()`（音效 + 强调色色板 + 导航滑块），取消激活后 `dispose()` 完整回收。
- 同 scope 主题互斥由宿主保证：启用本插件会自动顶替其他 SITE 主题插件，禁用/卸载后回落宿主内置主题；顶替/停用由宿主在「主题中心」一键操作。

## 复刻内容对照（上游 neco → 本插件）

| 上游 | 本插件 | 说明 |
|---|---|---|
| 完整设计令牌（bg/card/elevated/sunken、文本 alpha 阶梯、bevel/shadow、focus-ring 等） | `theme.css` 的 `--neco-*` 变量族 | 深色为默认，`html.dark`/容器 `:not(.dark)` 双写浅色；同时映射宿主 `--yb-site-*` 与 Arco `--primary-6` |
| `[data-accent]` 6 色强调色（绿/红石/青金石/黄金/紫水晶/海晶，各含 light/dark/bright/pale 派生） | `theme.css` accent 变量块 + `src/accent.ts` | 翠绿为默认（不挂属性）；浮动像素色板切换，持久化 `localStorage["neco-pixel:accent"]`；切换瞬间 `html.theme-switching` 0.35s 过渡 |
| ThemePalette.vue | `.neco-palette-toggle` / `.neco-palette-panel` | 固定在左下音效开关上方，仅主题激活时可见 |
| fade-in / fade-in-down/left/right 入场动画 | `@keyframes neco-fade-in-*` + `.neco-anim*` 工具类 | `.site-section` 首页区块自动交错入场；尊重 `prefers-reduced-motion` |
| NavBar 活动项像素滑块 | `src/nav-slider.ts` + `.neco-nav-slider` | 宿主导航无 active 类，运行时按当前路径与导航项 href 最长前缀匹配定位，监听路由/缩放/导航结构变化 |
| `border-image` 对话框面板（Mojang 贴图） | `.neco-panel` / `.neco-hero-panel` / `.neco-block-bg` | 贴图不复制：9-slice 边框与深板岩/蓝冰平铺纹理由 `gen_textures.py`（PIL）程序化自绘 |
| Minecraft-Tenv2 / Cubic 11 字体栈 | Press Start 2P（拉丁标题）+ Cubic 11（CJK 正文/标题，SIL OFL） | Minecraft-Tenv2 许可不明不使用；`font-smooth: never` + 1px 字距 |
| Lobby / About 页面 | `home-preset.json` 的 `settings.homeHtml`/`homeCss` + `pages[]` | 见下两节 |

许可：上游代码与设计令牌按 MIT 移植（见 theme.css 头部声明）；Cubic 11 字体按 SIL OFL 随包分发（`public/fonts/OFL-cubic11.txt`）；纹理与音效均为自绘/合成，不含 Mojang 资产。

## 主题覆盖范围

| 界面 | 机制 |
|---|---|
| 公开站骨架（/site、CMS 页） | 覆写 `.site-chrome`/`.site-page` 的 `--yb-site-*` 变量（MC 浅色/深色两套，经 `html.dark` 双写），头部深底浅字、3px 硬边、hero 棋盘格压纹 |
| wiki 知识库（/wiki/**） | `.wiki-public`/`.wiki-home`/`.wiki-search-page` 覆写 Arco `--color-*` 与 `--primary-6` |
| 大事记（/timeline） | `--tl-*` 桥接变量自动跟随 `--yb-site-*`；卡片方角硬阴影、时间轴圆点改方块、隐藏模糊光斑 |
| 表单公开页（/forms/:code） | `.public-form-page` Arco 变量 + 面板方角立体边 |
| 学历核验 / 世界地图 / 题库公开页 | `.ev-page`/`.world-map-viewer`/`.qb-screen`/`.qb-shared` 并入 Arco 变量换肤组 |
| 未来新增的 siteNav 插件公开页 | 包在 `.plugin-site-page`/`.site-chrome` 内即自动继承；自带 `--xx-*` 桥接变量回退到 `--yb-site-*` 的插件（如 timeline）无需任何改动 |

像素化通用处理：全局方角（`border-radius: 0`）、MC 立体边按钮（inset bevel + 按下反转）、硬位移卡片阴影、像素字体栈、选区/焦点/内部滚动条样式。

## 自带首页方案（home-preset.json）

- 主题启用（SITE scope 激活）时，宿主自动读取 `home-preset.json` 导入为方案 `plugin:neco-pixel` 并立即应用——公开站首页整套切换为像素风演示内容，无需手工进 CMS 调整。
- **完整主页复刻**：方案经 `settings.homeHtml` + `homeCss` 复刻上游 Lobby——方块纹理 hero 大标题面板（`neco-hero-panel`，站点名/简介/Logo 由 `{{site.*}}` 注入）→ 关于我们左右入场面板 → 最新动态卡片流（`data-yb-for="item in cms.pages.latest" data-yb-limit="4"` 注入系统内容，空态经 `cms.pages.latest.count == 0` 兜底）→ 登录态感知 CTA（`auth.isLoggedIn` 比较）。`homeCss` 只做布局并全部以 `.site-builder-home` 命名空间自闭合，配色完全来自 theme.css 变量。
- **内容/导航注入约定**：主页与页面集一律使用 `data-yb-*` 模板指令与 `{{路径}}` 注入系统数据；**不声明** `navigationJson`，导航始终由系统渲染（活动项滑块由运行时适配）。
- **非 homeHtml 回退**：`sections`（FEATURE/CTA）保留，供未开启 homeHtml 渲染的环境回退。
- **自动快照**：应用方案前，宿主先把当前首页定制存为「切换前快照」方案（内容与最近快照一致则跳过，快照最多保留 10 份）；在 主题中心 → 首页方案 里可一键切回任何方案，或把当前定制另存为自己的方案。
- **首页设计独立**：每个主题的首页设计（`homeHtml`/`homeCss`/标题/区块）整套持有、绝不混杂——导入本方案前宿主先把首页还原到「主题接管前」基准（存为 `plugin-backup:neco-pixel` 备份），再整套应用本主题设计；停用/顶替/卸载本主题时，若首页仍由本主题占用则整体还原备份并删除备份，主题 CSS、音效、色板与滑块仍由宿主在取消激活时完整回收。
- 维护：方案 JSON 放在前端包 `public/` 下随 dist 打包；区块 `id` 需稳定，升级主题时同 code 方案会被 upsert 覆盖。

## 自带页面集（pages[]）

- `neco-about`（LANDING 模板）：复刻上游 AboutView——大标题 fade-in-down 入场 + 左右交替图文面板（`neco-anim-left/right` + `neco-panel`），「最新内容」列表由 `data-yb-for` 注入系统页面，`{{site.name}}`/`{{system.copyright.company}}` 注入站点信息；页面 CSS 只写布局，由宿主自动 scope 到 `.site-article`。
- **生命周期**：页面随主题启用导入为已发布（`sourcePluginCode=neco-pixel`）；slug 被管理员或其他主题占用时跳过不覆盖；主题停用/顶替/卸载时该插件页面自动下线转草稿，再次启用恢复发布。管理员自建的同名页面永不被主题覆盖。

## 运行时

- **音效**：WebAudio 实时合成 8-bit 芯片音（方波/三角波扫频），无音频资产；悬停短促高音、点击双音。仅在主题 link 存在且未 `disabled`（即公开路由）且事件发生在公开页容器内时播放；切回后台自动静默。左下角像素开关（`.neco-sound-toggle`）可随时静音，选择持久化在 `localStorage["neco-pixel:sound"]`；AudioContext 在首次 pointerdown 时解锁，不违反自动播放策略。
- **强调色色板**（`src/accent.ts`）：左下音效开关上方的 2×2 彩色方块按钮，展开 6 色色板面板；选择写 `localStorage["neco-pixel:accent"]` 并挂/卸 `<html data-accent>`（翠绿为默认色，卸属性），切换瞬间挂 `theme-switching` 过渡类；显隐跟随主题 link 的 `disabled`。
- **导航滑块**（`src/nav-slider.ts`）：向 `.site-chrome .site-layout-header__nav` 注入绝对定位滑块，按 `location.pathname` 与导航项 href 最长前缀匹配活动项并测量定位；监听 popstate、history.pushState/replaceState 补丁、窗口缩放与导航 DOM 变更；`dispose` 时还原 history 并移除滑块。

## 资产

- `public/fonts/press-start-2p.woff2`：Press Start 2P（SIL OFL 1.1，Google Fonts 拉丁子集，12KB）。
- `public/fonts/cubic-11.woff2`：Cubic 11 v1.500（SIL OFL，`public/fonts/OFL-cubic11.txt`），CJK 像素正文/标题字体。
- `public/ui/neco-dialog.png`：48×48 9-slice 空心对话框边框（`gen_textures.py` 程序化自绘）。
- `public/blockbg/deepslate.png` / `blue-ice.png`：32×32 深板岩/蓝冰平铺纹理（程序化自绘），供 hero 面板与卡片底纹。
- `public/preview.png`：主题设置页展示的预览图（程序生成的像素 mock）。
- `public/home-preset.json`：内置首页方案 + 页面集（见上两节），随 dist 打包。
- 主题 CSS 经 vite lib 构建合并为 `style.css`；`@PluginFrontend(styles)` 保持为空，避免样式未经 scope 管控被常驻注入。
