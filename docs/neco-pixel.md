# neco-pixel：NECO 像素风公开站主题

完整复刻 [RandomLemon/neco](https://github.com/RandomLemon/neco)（南京大学 Minecraft 协会站，MIT License）的主题体系，以主题插件形式接管公开站外观、交互音效、强调色体系与整套像素风页面。

2.0.0 起主题版式页全面改为 **Vue 原生页面**（`homeComponent` + `chromeComponent` + 公开路由），不再使用 CMS 首页方案与 data-yb 模板引擎；CMS 回归内容系统（新闻详情等 `/site/:slug` 仍由 CMS 渲染、由主题 style.css 美化）。

## 形态

- **纯主题插件**：不注册 HTTP 端点、权限与后台菜单，入口类只做声明。
- `@PluginTheme(code="neco-pixel", scopes={SITE}, styles={"style.css"}, preview="preview.png", homeComponent="theme/Home", chromeComponent="theme/Chrome", configSchema="theme-config.json")`：主题样式、首页/chrome 组件名与配置 schema 打进 JAR 的 `META-INF/yudream-plugin/frontend/neco-pixel/`。
- **chrome 由主题自管**：`chromeComponent="theme/Chrome"` 接管公开站页头/页脚。导航数据由宿主注入（首页 `/site` + 站点导航 + 插件 `siteNav` 路由合并，主题再隐藏 `/news` `/about`），主题自己画 NMO 覆盖式导航条（按实际文字宽度测量滑块、深色 overlay、登录/头像在右上）。切页不再换 chrome，也不会回落到宿主浅色 SiteChrome。公开 Wiki（`/wiki`，导航文案「知识库」）与 mc-wiki 百科（`/encyclopedia`）都进导航，互不替代。
- **版式页 = 插件前端包里的 Vue SFC**：`@PluginFrontend(moduleName="neco-pixel")` + 公开路由 `/servers`、`/activities`（进导航）与 `/activities/:id`（`siteNav` 只为套主题 chrome，Chrome 按路径/标题隐藏「活动详情」）；新闻与关于我们留在首页区块，百科由 mc-wiki 的 `/encyclopedia` 进导航。
- **主题配置（WordPress 自定义器形态）**：`configSchema` 声明 `theme-config.json` 后，宿主在「平台 → 主题中心」主题卡上给出「配置」入口（`/platform/theme-center/config/neco-pixel`）；配置按主题持久化（Setting `pluginTheme.config.neco-pixel`），Vue 页面经 `sdk.site.context()` 返回的 `themeConfig` 消费，保存后公开站即时生效。
- **软依赖声明**：`plugin.yml` 声明 `softdepend: [minecraft-server, minecraft-activity-proof, timeline, mc-wiki]`（仅表达增强关系与加载顺序，缺失不阻塞主题）；主题代码不 import 任何业务插件。
- 同 scope 主题互斥由宿主保证：启用本插件自动顶替其他 SITE 主题插件，禁用/卸载后回落宿主内置主题；顶替/停用由宿主在「主题中心」一键操作。

## 页面结构（2.0.0）

| 路径 | 组件 | 数据来源 |
|---|---|---|
| 全站 chrome | `theme/Chrome.vue`（chromeComponent） | 宿主注入 `navigation`/`footer*`/`siteName`/`isLogin`；NMO overlay 导航 + 页脚免责声明 |
| `/site`（首页） | `theme/Home.vue`（homeComponent） | hero/关于/服务器预览/最新动态，`sdk.site.context({blocks:['server-list'], limit:4, cmsLatest:12})` |
| `/servers` | `theme/Servers.vue` | `server-list` 块实时状态优先，未装 minecraft-server 回落 `themeConfig.staticServers`；NMO 维度列表：`list-background.jpg` + ping 条动画 + 64px 图标 |
| `/activities` | `theme/Activities.vue` | `activity-square` 块（含 `cover`/`id`/`url`）；有封面用活动封面，点击跳 `/activities/{id}`；未装插件回落 CMS 最新文章或空态 |
| `/activities/:id` | `theme/ActivityDetail.vue` | 匿名 `GET /api/plugins/minecraft-activity-proof/public/activities/{id}`；不进导航；登录后「立即参与」进登录广场详情 |
| `/encyclopedia`（百科） | **mc-wiki 插件自己的公开页** | 物品图鉴 + 合成配方；未装/未发布时导航项消失或页内空态 |
| `/wiki`（知识库） | **宿主公开 Wiki** | 站点文档空间；有公开空间时宿主自动注入导航项，主题不再隐藏 |
| `/timeline`（大事记） | **timeline 插件自己的页面**，主题不接管 | 主题只对 `.tl-page` 写像素兼容样式；未装 timeline 时导航项由宿主自动消失 |

所有页面 SFC 使用统一 prop 形态 `defineProps<{ sdk: YuDreamPluginSdk, route?: ... }>()`，站内跳转用共享 vue-router 的 `RouterLink`（vite 经 `yuDreamPluginSharedAliases()` 别名到宿主实例，不打包第二份 vue-router）；每页 `useThemeSeo` 设置标题/canonical。

## chrome 接管（替代 homeCss / 变量 hack）

旧版（1.x）用 homeCss RAW 注入 + `.site-chrome:has(.neco-lobby)` overlay hack 压过页头样式；2.0 前期曾用 chrome CSS 变量契约改宿主 SiteChrome。两者都无法保证首页/内页导航一致（宿主 SiteChrome 是浅色 sticky 栏，NMO 是深色绝对覆盖导航）。

现在官方通道是 `chromeComponent`：主题远程 Vue 组件完全接管页头页脚。未声明 chrome 的主题仍可用 chrome 变量契约作为回落，完整清单见主仓 `docs/plugin-system/specification.md` 9.1。

默认配色是 NMO 深色（`--neco-bg-page: #0f0e0d`）。公开站只保留深色方案，色板不再提供浅色切换。

## 复刻内容对照（上游 neco → 本插件）

| 上游 | 本插件 | 说明 |
|---|---|---|
| 完整设计令牌（bg/card/elevated/sunken、文本 alpha 阶梯、bevel/shadow、focus-ring 等） | `theme.css` 的 `--neco-*` 变量族 | 深色为唯一方案；同时映射宿主 `--yb-site-*` 与 Arco `--primary-6` |
| `[data-accent]` 6 色强调色（绿/红石/青金石/黄金/紫水晶/海晶，各含 light/dark/bright/pale 派生） | `theme.css` accent 变量块 + `src/accent.ts` | 翠绿为默认（不挂属性）；浮动像素色板只切换 6 色强调色，持久化 `localStorage["neco-pixel:accent"]` |
| ThemePalette.vue | `.neco-palette-toggle` / `.neco-palette-panel` | 固定在左下音效开关上方，仅主题激活时可见 |
| fade-in / fade-in-down/left/right 入场动画 | `@keyframes neco-fade-in-*` + `.neco-anim*` 工具类 | 尊重 `prefers-reduced-motion` |
| NavBar 活动项像素滑块 | `theme/Chrome.vue` 的 `.slider` | 按当前路径最长前缀匹配活动项，用 `offsetLeft`/`offsetWidth` 贴合文字宽度；强调色走 `--neco-nav-slider` |
| `border-image` 对话框面板（Mojang 贴图） | `.neco-panel` / `.neco-hero-panel` / `.neco-block-bg` | 贴图不复制：9-slice 边框与深板岩/蓝冰平铺纹理由 `gen_textures.py`（PIL）程序化自绘 |
| Minecraft-Tenv2 / Ark Latin / Cubic 11 字体栈 | `.mctitle` / `.mcfont` | 上游字体随包（MIT 仓）+ Cubic 11（SIL OFL） |
| Lobby / List / Activity / About / News | `src/theme/*.vue` | 原生 Vue 复刻；List 对标 `/list`，Activity 对标 `/activity` |
| `button.click.ogg` | `public/button.click.ogg` + `src/sounds.ts` | 原版 MC 点击音，音量 0.3；不再用 WebAudio 合成器 |

许可：上游代码、设计令牌、UI 贴图、背景图、点击音与截图资产按 MIT 移植（`public/ASSETS.md` 逐文件标注来源）；Cubic 11 / Press Start 2P 字体按 SIL OFL 随包分发。

## 主题覆盖范围

| 界面 | 机制 |
|---|---|
| 公开站骨架（/site、CMS 页） | `theme/Chrome.vue` 接管页头页脚；`.site-chrome`/`.site-page`/`html:has(.neco-chrome)` 覆写 `--yb-site-*` 与 html 底色 |
| 主题五个版式页 | 组件 scoped 样式 + theme.css 页面规则 |
| wiki 知识库（/wiki/**） | `.wiki-public`/`.wiki-home`/`.wiki-search-page` 覆写 Arco `--color-*` 与 `--primary-6` |
| 大事记（/timeline） | `--tl-*` 桥接变量自动跟随 `--yb-site-*`；卡片方角硬阴影、时间轴圆点改方块、隐藏模糊光斑 |
| 表单公开页（/forms/:code） | `.public-form-page` Arco 变量 + 面板方角立体边 |
| 学历核验 / 世界地图 / 题库公开页 | `.ev-page`/`.world-map-viewer`/`.qb-screen`/`.qb-shared` 并入 Arco 变量换肤组 |
| 登录 / 注册 | `/login`、`/register` 标 `public` 后套 SITE 主题；`.login-page` 像素字体、MC 村庄 ken-burns 背景、输入组去掉外圈 ring 改立体 bevel |
| 未来新增的 siteNav 插件公开页 | 包在 `.plugin-site-page`/`.site-chrome` 内即自动继承；自带 `--xx-*` 桥接变量回退到 `--yb-site-*` 的插件（如 timeline）无需任何改动 |

像素化通用处理：全局方角（`border-radius: 0`）、MC 立体边按钮（inset bevel + 按下反转）、硬位移卡片阴影、像素字体栈、选区/焦点/内部滚动条样式。

## 主题配置项（theme-config.json）

schema 分六节，全部有默认值，留空即回落站点设置或主题内置文案。图片字段走宿主上传器，不必手填 URL。Vue 页面经 `sdk.site.context()` 的 `themeConfig` 读取（`configText/configList/configNumber/configSwitch` helper）：

| 分节 | 字段 | 说明 |
|---|---|---|
| 首屏 Hero | `heroTitle` / `heroSubtitle` / `heroLogo` / `heroBackground` | 主标题留空显示站点名称；LOGO 留空用站点 LOGO；背景默认 `background/hero-garden.webp` |
| 关于我们 | `aboutTitle` / `introItems[]`（标题/描述/配图） | 介绍项 list，首页与 /about 页按 index 奇偶左右交替渲染；默认配图 `intro/intro-servers.webp`、`intro/intro-create.webp` |
| 服务器 | `showServers`（开关） / `staticServers[]`（名称/地址/描述/图标） / `serversHint` | minecraft-server 插件的 `server-list` 块可用时优先渲染实时状态，静态 list 仅作无插件降级；`serversHint` 为 /servers 页提示语 |
| 新闻 | `newsTitle` / `newsLimit`（默认 4，上限 12） | 驱动首页最新动态区块与 /news 页 |
| 友情链接 | `friendLinks[]`（名称/URL/图标） | /about 页渲染 |
| 部门 | `departments[]`（名称/简介） | /about 页渲染 |

## 主题块依赖（可选，软依赖降级）

Vue 页面经 `sdk.site.context({blocks: [...]})` 消费其他插件贡献的数据（`GET /api/public/theme/context`，匿名）；插件未安装/未启用时块缺省，页面一律带静态回落分支，**均为软依赖、缺失不阻塞主题**：

| 块 | 提供插件（最低版本） | 数据 | 消费位置 |
|---|---|---|---|
| `server-list` | minecraft-server 1.5.0 | `servers[]`：名称/图标/描述/地址/在线/状态文案/MOTD/人数/延迟（取自状态快照缓存，不实时 ping） | 首页服务器区块、/servers 页 |
| `activity-square` | minecraft-activity-proof 2.4.1 | `activities[]`：`id`/标题/`cover`/`url`/`statusKey`(upcoming/ongoing/ended)/状态文案/`meta`/摘要 | /activities 页；有封面用活动封面，点击进 `/activities/{id}` |
| `timeline` | timeline 1.3.0 | `events[]`：标题/时间/摘要/链接（统一导向 /timeline） | 任意主题页面可自行引用 |

## 运行时

- **音效**：播放上游 `button.click.ogg`（Minecraft 原版按钮点击，音量 0.3）。仅在主题 link 存在且未 `disabled` / `media !== 'not all'`（即公开路由）且事件发生在公开页容器内时播放；切回后台自动静默。左下角像素开关（`.neco-sound-toggle`）可随时静音，选择持久化在 `localStorage["neco-pixel:sound"]`。
- **强调色色板**（`src/accent.ts`）：左下音效开关上方的 2×2 彩色方块按钮，展开 6 色色板；选择写 `localStorage["neco-pixel:accent"]`，挂/卸 `<html data-accent>`。安装时清掉历史 `neco-pixel:scheme` 与 `data-theme`，公开站只走深色。显隐跟随主题 link 的 `disabled`/`media`。
- **导航滑块**：由 `theme/Chrome.vue` 自己按 `route.path` 最长前缀匹配活动项并 `translateX` 滑块，不再补丁宿主 SiteChrome。

## 资产

- `public/fonts/press-start-2p.woff2`：Press Start 2P（SIL OFL 1.1，Google Fonts 拉丁子集）。
- `public/fonts/cubic-11.woff2`：Cubic 11 v1.500（SIL OFL，`public/fonts/OFL-cubic11.txt`），CJK 像素正文/标题字体。
- `public/fonts/Minecraft-Tenv2.woff2` / `ark-pixel-latin.woff2`：上游公开站字体（MIT 仓随包）。
- `public/ui/**`：上游 MC 风格按钮、开关、对话框、玩家头与 ping 条（MIT）。
- `public/button.click.ogg`：上游打包的原版 MC 点击音（MIT，6785 bytes）。
- `public/loading.gif`：上游加载动画（MIT）。
- `public/blockbg/deepslate.png` / `blue-ice.png`：32×32 深板岩/蓝冰平铺纹理（程序化自绘），供 hero 面板与卡片底纹。
- `public/preview.png`：主题设置页展示的预览图（程序生成的像素 mock）。
- `public/background/hero-garden.webp`：首页满幅 Hero 背景。
- `public/background/beidalou.webp`：备用大厅建筑背景。
- `public/background/list-background.jpg`：服务器列表满幅背景（对标 NMO `/list`）。
- `public/background/header-bg.jpg` / `bg.jpg`：活动页顶栏平铺 + 正文平铺（对标 NMO `/activity`）。
- `public/background/links-background.jpg`：友链背景。
- `public/intro/intro-servers.webp` / `intro-create.webp`：关于我们介绍项默认配图。
- `public/theme-config.json`：主题配置 schema（见「主题配置项」），随 dist 打包并经 `configSchema` 声明。
- 主题 CSS 经 vite lib 构建合并为 `style.css`；`@PluginTheme(styles={"style.css"})` 声明后由宿主 theme-runtime 按 SITE scope 注入。

## 升级说明（1.x → 2.0.0）

- `home-preset.json` 已删除，`@PluginTheme` 不再声明 `homePreset`；首页由 `theme/Home` Vue 组件接管，页头页脚由 `theme/Chrome` 接管。
- 1.x 导入的 CMS 页面（neco-servers/neco-news/neco-activity/neco-docs/neco-about）与 `plugin:neco-pixel` 首页方案作为历史数据保留在 `neco-pixel` 主题名下，不再被主题管理，可在主题中心手工清理；公开导航改为插件路由（/servers、/activities、/news、/about），旧 `neco-*` slug 页不再出现在导航。
- 主题配置（Setting `pluginTheme.config.neco-pixel`）完整保留，2.0.0 新增字段（`serversHint`、introItems 默认配图）按 schema 默认值自动补全。
- SDK 说明：页面使用的 `sdk.site.*` 客户端属 SDK 1.7.0（本仓发布前插件经 `src/sdk-site.d.ts` 本地类型桥接消费，运行时宿主已注入实现）。
