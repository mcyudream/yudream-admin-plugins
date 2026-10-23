# 插件开发规范：后端分层、访问边界、YD UI 与打包

> **适用范围**：本仓 Java 后端 JAR、Vue Web remote frontend、React Native 插件适配边界。
> **目标**：保持宿主契约隔离、用户/管理员数据严格分权、插件生命周期完整、UI 只消费已发布公开组件。

宿主规范权威与本仓同步地图见主仓仓库内 `docs/dev/plugin-system.md`、`docs/dev/backend-architecture.md`、`docs/dev/permission-codes.md`、`docs/dev/repo-sync.md`。组件视觉规范见本仓同步副本 `docs/yd-ui-guidelines.md`。

## 1. 第一步与影响面

1. 确认目标 plugin code，存在时同时检查 `yudream-plugins/yudream-plugin-{code}` 与 `yudream-frontend/packages/plugin-{code}`。
2. 检视 plugin.yml、前端入口、Vite 与 Maven 打包；先对照最近似插件，不发明平行架构。
3. 标记受影响 routes/menus/permissions/endpoints/migrations/data scope/lifecycle/assets。
4. 工作前确认 git status；保留用户改动，提交严格限定工作范围。

## 2. 契约边界、元数据与跨插件 API

- Maven 唯一宿主编译契约：已发布 `online.yudream.base:yudream-plugin-spi`；前端唯一共享契约：已发布 `@yudream/plugin-sdk` 与 `@yudream/components`。严禁本地宿主 workspace、源码复制、vendoring、宿主内部包、私有宿主 HTTP client、Spring Bean 绕过。宿主缺能力先升级/发布稳定 SPI/SDK，再由本仓消费。
- JAR 根权威 `plugin.yml` 必须声明 `name/main/version`；`name` 稳定 code，`displayName` 只展示。`depend` 硬依赖、`softdepend` 可选依赖。禁止 `META-INF/services/...YuDreamPlugin`，运行时从 `main` 建实例。
- 新插件 menu **必须**声明语义分类：内置 `mc/social/play/todo/content/campus/client/trade/tools/appearance`，或用 `{code,name,icon,sort}` 自定义；**不提供「其他」兜底**，缺 `category` 的 menu 块会被宿主拒绝。有 menu 块默认 CATEGORY，分类是宿主共享一级入口，功能放分类下。**一级菜单名称（分类名与 INDEPENDENT 独立入口名）最多 5 个字**，超长在宿主 manifest 解析与菜单投影时失败；二级及以下名称由子侧栏承载，不受限。除明确例外用 INDEPENDENT/parentCode；无 menu 或 LEGACY 保持旧插件行为；不自动搬管理员已持久化菜单。旧宿主兼容需确认。
- 硬依赖先于 consumer 加载启用；软依赖缺失不阻塞主体，相关贡献和 UI 显式降级。调用前查可用性、隔离 optional 类型引用以免 NoClassDefFoundError；provider disable/reload 间不缓存 API 对象。`depend/softdepend` 图必须无环；`launcher-adapter` 不得依赖 `minecraft-server`，服务器列表/YMCL scope 由后者以 LauncherProvider 贡献。
- 插件业务 API 不放宿主 SPI。provider 在自身 JAR 的稳定最小 `*.api` 包声明；consumer `provided` 依赖、经依赖 ClassLoader 调用；禁止复制/shade/relocate/重复打包 API，也禁止用 registerExtension/getExtension、framework.extension(s)、私有 HTTP 代理或 Spring Bean 调用跨插件业务 API。
- 插件之间的消费方向、类加载器快照、级联启停以主仓 `docs/dev/plugin-system.md` 为准；provider reload 前先按依赖顺序 unload consumer。

## 3. 后端结构、HTTP 与生命周期

复杂度决定是否建包，但职责不得混杂：

```text
bootstrap/        入口、装配、注册、生命周期、资源清理
domain/           聚合、值对象、不变量、领域服务、仓储接口
application/      cmd/query/dto、用例编排、事务
infrastructure/   持久化、外部 SDK、技术适配
interfaces/       controller、request/res、assembler、HTTP facade
migration/        迁移任务与状态
```

- 入口只做元数据、组装、注册、生命周期与清理；禁止把业务流程、复杂 HTTP 映射、迁移主体、大批处理塞进 YuDreamPlugin。
- Domain 放业务含义/不变量/聚合/值对象/repo 契约；application 放用例/编排/cmd/query/dto/事务/assembler；infra 放 dataobj/mapper/repo impl/外部适配；interfaces 放 controller/request/res/web assembler/校验/边界解析；bootstrap 装配。
- Controller 薄：边界校验、调用应用 service、返回响应；request→cmd、DTO→response 经 assembler/facade；禁止业务规则、直接持久化、大段 JSON 解析。宿主分层硬规则参照主仓 `docs/dev/backend-architecture.md`。
- 静态权限/菜单/路由/端点/迁移优先注解；动态/条件贡献通过 `PluginContext.registerXxx(...)`，使 disable/unload 可回收。
- 端点方法在 `interfaces/controller`，写插件内相对 path，最终挂 `/api/plugins/{pluginCode}/**`；管理端点必须权限保护，HTTP controller 不放 plugin entry。
- 使用宿主能力只通过 SPI（FrameworkServices、PluginContext、files/documents 等）；不得暴露领域聚合/repo、应用服务、DO/Spring Bean 或宿主 request/res。
- 插件自有 Thymeleaf 模板在自身 `src/main/resources/templates`，经 `PluginContext.templateRenderer()` 用逻辑名（不带 `.html`），禁止读宿主/其他插件模板、绝对路径或 `..`。
- 构造/扫描阶段禁止外部连接、迁移、网络请求或大 I/O；onEnable 轻装配；注册线程池/连接/SSE/client/定时任务/回调须通过 `context.onDispose(...)` 或生命周期在 disable/unload 释放。
- 插件 DTO 使用可序列化 record/简单值；HTTP/插件边界的 Long ID 转字符串，application 内再解析。
- 管理集合超过 5 条必须 server-side page/size/filter + total；删除/disable 要对缺失、受保护、被引用、不可删记录给确定业务响应；最终 JAR 验证 metadata/assets。

## 4. Public / User / Admin 双维度访问边界（强制）

权限决定能否进入，数据范围决定能访问哪些记录；管理权限不能扩大用户端数据范围。每个 route/component/endpoint/use case 实现前分为 Public、User、Admin。不能在一个端点里 `if(hasManagePermission)` 切换数据集。

### 4.1 必须分离的面

同时有自助与管理的插件须分别定义：权限、前端 route/menu、page component、API wrapper、HTTP path/endpoint、controller、application query/command/use case、授权与归属测试。可参考 skin 插件 `YuDreamSkinUserController` `/me/**` 与 `YuDreamSkinAdminController` `/admin/**`。

### 4.2 User 面

- 用 `/me/**`（禁止新增 `/my/**`）；subject/owner 只从 `request.principal().userId()` 或等价可信身份推导。
- personal request/query/body 不接受 `userId/ownerId/memberId` 等选择所有者字段；按 `resourceId + currentOwnerId` 查询/变更；自然键也必须验证 owner。
- 只返当前主体记录和聚合数，不能泄漏他人存在性/总数/ID/名称/状态/metadata。管理员调用 user route 仍只是普通自助用户。
- 禁止 user 端管理权限分支扩大数据集；不放 admin 控件、跨用户搜索、user selector、全局统计。

### 4.3 Admin 面

- 跨用户/系统管理只放独立 `/admin/**` 与管理 permission、controller、use case、API wrapper、route/page/store；显式 target ID 只进管理 query/request。
- 按域支持 list/detail/create/edit/enable-disable/delete-archive/assign/approve 等所需生命周期；需用户持久记录就要同工作项评估管理员支持闭环。超过 5 条后端分页；不让管理员 impersonate 用户。
- 管理写操作遵守领域不变量与审计，管理权限不等于绕过业务规则。Personal response 不泄漏所有者之外数据，管理 response 可标 owner。

### 4.4 状态、测试矩阵与旁路

- 用户与管理 route/path/name/title/component/permission metadata 分开；store/composable 不混缓存用户和管理员数据；账号切换/登出/插件卸载清个人缓存。
- 每个 user-owned resource 测：普通用户 user-own 允许、user-other deny/not-found；普通用户 admin deny；管理员 user-own 仍只访问自己、user-other deny；管理员 admin 按权限允许；未认证拒绝。
- 同样覆盖 total、分页、download/export、dashboard、autocomplete/options、batch、SSE/events、files 等旁路。内部标识有权威列表时必须给 options/selector，禁止手输 user/provider/connection/group ID。
- 定向扫描可用：`rg -n "hasPermission\(.*MANAGE|userId|ownerId|memberId" yudream-plugins/yudream-plugin-{code}/src/main/java -g "*.java"`；`rg -n "isAdmin|canManage|userId|ownerId|admin" yudream-frontend/packages/plugin-{code}/src -g "*.ts" -g "*.vue"`，每个命中人工判定。

## 5. Vue Web 前端与 YD 唯一入口

- 使用 Composition API、`<script setup lang="ts">`；目录职责 `src/api`（SDK wrappers）、`components`、`composables`、`pages`、`index.ts` remote contract、`types.ts`。
- 每个 `@PluginRoute.component` 必须导出真实页面，不用一个 shell 按 path/meta 猜页面；主要管理面拆独立 route，不以巨型 tabs 藏功能。
- 普通 API 仅用宿主注入 `@yudream/plugin-sdk`，禁止私有 axios/fetch 或硬编码宿主 origin。用户 wrapper 仅 `/me/**`，管理员 wrapper 仅 `/admin/**`；不共用混数据 cache。
- `@yudream/components` 只从包公开入口显式导入，remote 包不继承宿主 auto-import。
- Vue Web 业务 UI **只使用已发布 `@yudream/components` 公开 `Yd*`/`useYd*`**；禁止 Fa/useFa、裸 Arco/TDesign/Reka/通用 UI、别名与手写通用交互。允许语义内容与纯布局；编辑器/图表/流程画布/可视化 builder 必须 typed YD adapter。
- 缺能力先在宿主共享 YD 封装，保全 model/event/slot/ref/keyboard/focus/theme/overlay；更新根导出/resolver/SDK bridge/preview，发布验签后才更新 catalog/lockfile；禁止复制宿主代码、本地路径/未发布版本/页面第三方 fallback。
- 关联实体预览卡：宿主/其他插件页面用 `YdRelatedEntity entity-type="..." :entity-id="..." :label="..."` 渲染已登记实体的摘要卡（宿主内置 `user/dept/role`，未注册类型降级纯文本）；插件自有实体类型经 `install(sdk)`（SDK 1.15.1 起注入 sdk 实例）调 `sdk.relatedEntity?.register(type, provider)` 登记——实际键 `${pluginCode}:${type}`，只能在本插件命名空间注册，`dispose()` 对称注销、宿主卸载时按插件码兜底回收；提供者组件接收 `RelatedEntityRef`（自 `@yudream/components` 导入类型）并自取细节。components 1.11.1 / SDK 1.15.1 发布验签后方可更新 catalog 消费。
- 三外观、全状态、视觉和组件选型以本仓 `docs/yd-ui-guidelines.md` 为同步副本，主仓为权威；规则见 `docs/dev/conventions.md` 的双仓节。React Native 插件用独立 `@yudream/plugin-mobile-ui`，不导 Vue YD。
- 远程插件列表按管理数据规范：超过 5 条分页；可比较字段用 `YdResponsiveTable` + `#card`，`YdTable` 仅桌面内部表；破坏性删除经 `useYdModal().confirm`，成功后 toast、刷新与末页修正。细则由主仓组件规范与本文件经验对应。
- 页面一条主流程；列表/设置/统计/审计/导入导出/迁移/诊断/复杂编辑器应独立路由；短小临时命令用 YdModal，中型上下文详情 YdDrawer，长/多步骤用 route page；禁止嵌套卡片、Drawer、Modal。
- 表单显式类型/校验，防重复提交、展示后端错误、关闭清理临时状态；提供 loading/empty/error/disabled/success 与窄屏布局；中文 UTF-8，禁止 Unicode 转义与乱码。

## 6. 产物、样式与资源

- JAR 必须打包 `META-INF/yudream-plugin/frontend/{pluginCode}/remoteEntry.js`、manifest、assets；Vite 输出 ESM `dist/remoteEntry.js`，resource/chunk/CSS/image/font 用相对 base，从插件 assets 地址解析。
- CSS 使用声明式 `style.css`：Vite `yuDreamPluginUnoCss()`（`@yudream/plugin-sdk/uno-config`）、入口 `virtual:uno.css`、lib `cssFileName:'style'`、`@PluginFrontend(styles={"style.css"})`。禁止 `styles.css?inline` + `install()` 手写 style。
- SDK ≥1.6.0 CSS 进入 `@layer yudream-plugin`，层序 `yudream-base < yudream-plugin < host utilities`；只用宿主主题语义变量；不全局 reset/固定品牌色/覆盖宿主同名工具类。ADMIN 根变量覆盖、SITE 仅 SITE scope；功能动画三外观都保留。
- 插件主题 style.css 只有唯一 scope 可声明 `--primary-N`：SITE 要明确 SITE 锚点，ADMIN 限 `:root/.dark`；消费仍违规，未证明隔离的 SITE/ADMIN 双 scope fail-closed。
- 工作台站点型 SITE 主题：在 homePreset JSON 的 `studioPackage` 携带站点便携包，媒体放进本插件前端资产目录并按 `/api/platform/plugins/{code}/assets/...` 引用，禁止引用 `/api/files/...`；激活时宿主仅在无站点时导入发布，规则见主仓 `docs/plugin-system/specification.md`「主题自带工作台站点」。
- 禁止浏览器/宿主对 `assets/**` 通配扫描盲加载（含动态 chunk/CSS/fonts/images），动态 ESM chunk 按需加载。非 Vite 或入口前必须执行脚本才显式声明 styles/scripts，路径相对、不含 `/` 开头、`..`、反斜杠，顺序稳定去重。
- 静态资源进 dist；使用已发布 SDK `sdk.assets.url("assets/logo.svg")` 或构建相对 URL，不写宿主 origin。受保护下载用 SDK blob，不用裸 window.open。

## 7. 完成检查

- 前后端 code/入口/menu/route/permission/API/resource path 一致？有无宿主内部依赖、重复共享包、私有 client、数字长 ID、未授权 endpoint？
- user/admin 分离且 personal 面无管理员越权分支？
- 组件是已发布 YD 公开面且共享桥确实提供？是否有对应窄屏卡片、分页、管理操作、所有状态/主题/键盘/动效？
- 前端目标 typecheck/build、后端目标 Maven test/package 是否完成？最终 JAR 是否检出 remote assets？同步了所有规范与副本？
