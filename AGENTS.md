# YuDream Admin Plugins — 工程规则

本文件适用于 `D:/code/yudream-admin-plugins` 下的全部工作。详细参考资料位于 `.codex/skills/yudream-plugin-development/`；进行特定类型工作前必须读取对应 reference：

- 前端/UI：`references/frontend-guidelines.md`、`ui-library.md`、`page-composition.md`
- 列表、CRUD、表格、分页：`references/management-pages.md`
- 用户/管理员权限、归属、个人数据：`references/access-boundaries.md`
- Java 后端：`references/backend-guidelines.md`
- 依赖、构建、打包、发布：`references/repository-workflow.md`

## 1. 工作范围与第一步

- 后端模块：`yudream-plugins/yudream-plugin-{code}`。
- 前端远程包：`yudream-frontend/packages/plugin-{code}`。
- 修改插件前，必须同时检查其前后端模块、`plugin.yml`、前端入口、Vite 配置与 Maven 打包配置。
- 先参考行为最接近的现有插件；复杂 DDD 插件优先参考 `plugin-project-progress`。
- 保留用户已有的未提交修改。禁止覆盖、回退、格式化或重构与当前任务无关的文件。
- 新增/修改功能前，先明确受影响的插件 code、权限、菜单、路由、HTTP API、数据范围、生命周期和打包产物。

## 2. 独立仓与契约边界（强制）

本仓是独立插件仓，不是宿主源码镜像。

- Java 只能消费已发布的 `online.yudream.base:yudream-plugin-spi`。
- 前端只能消费已发布的 `@yudream/plugin-sdk` 和 `@yudream/components`。
- 禁止依赖、导入、复制或 vendoring 宿主的 `domain`、`application`、`infrastructure`、`interfaces`、`bootstrap`、宿主 Spring Bean、Mapper、Repository、DO、Controller、Request/Response、宿主前端 app 或本地宿主 workspace。
- 禁止复制 SDK、共享组件或宿主源码到本仓。宿主缺能力时，先在宿主发布稳定 SPI/SDK 契约，再升级本仓依赖；不得用内部 import、私有 HTTP 客户端或直接 Spring Bean 绕过契约。
- Maven SPI 版本仅在根 `pom.xml` 的 `yudream.plugin.spi.version` 管理。
- npm SDK/组件版本仅在 `yudream-frontend/pnpm-workspace.yaml` 的 catalog 管理；包内依赖使用 `catalog:`。
- 契约版本必须先发布并确认 Nexus 可解析，再更新本仓版本；调整 catalog/依赖后必须同步更新 `pnpm-lock.yaml`。
- 前端 workspace 只能保留 `packages/plugin-*`；禁止改回 `packages/*`。
- Java `Long` / Snowflake ID 在 JSON、DTO、TS 模型、表单、路由参数、SDK 调用中一律使用 `string`；禁止 `Number(id)` 或数字表单控件承载长 ID。

## 3. 插件元数据、依赖与跨插件 API

- 每个插件 JAR 必须包含 `src/main/resources/plugin.yml`，至少声明 `name`、`main`、`version`。
- `name` 是全局唯一、稳定的插件 code，用于依赖、路由、服务发现和资源路径；`displayName` 仅用于展示，禁止用于依赖或查找。
- 后端、前端包、资源目录、路由、权限、API 路径的 code 必须一致。
- 核心必需提供方使用 `depend`；可选集成使用 `softdepend`。软依赖不可用时，插件主体必须仍可运行，受影响的路由、菜单、任务、操作和前端控件要显式降级或禁用。
- 可选依赖调用前检查可用性，并隔离可选类型引用，避免 provider 缺失引发 `NoClassDefFoundError`；不得跨 provider disable/reload 缓存 API 对象。
- 插件业务 API 不进入宿主 SPI。Provider 可在自身 JAR 的稳定、最小 `*.api` 包中公开接口/DTO；Consumer 以 `provided` 依赖编译。
- 禁止复制、shade、relocate 或重复打包 Provider API；禁止通过 `registerExtension`、`getExtension`、`framework().extension(s)`、插件私有 HTTP 代理或直接 Spring Bean 调用跨插件 API。
- 禁止打包 `META-INF/services/...YuDreamPlugin`；运行时由 `plugin.yml` 的 `main` 创建插件实例。

## 4. 后端架构、HTTP 与生命周期

按复杂度采用下列职责，不得把无关逻辑堆进入口类：

```text
bootstrap/        插件入口、装配、注册、生命周期、资源清理
domain/           聚合、值对象、不变量、领域服务、仓储接口
application/      command/query/dto、用例编排、事务
infrastructure/   持久化、外部 SDK、技术适配
interfaces/       controller、request/res、assembler、HTTP facade
migration/        迁移任务与状态
```

- `YuDreamPlugin` 入口只负责元数据、组装、注册、生命周期和清理；不写业务流程、复杂 HTTP 映射、迁移主体或大批处理。
- Controller 必须薄：边界校验、调用 application service、返回响应。`request -> command`、`DTO -> response` 放 assembler/facade；禁止在 Controller 写业务规则、直接操作持久化或大段 JSON 解析。
- 静态权限、菜单、路由、端点优先用注解；仅动态/条件贡献使用 `PluginContext.registerXxx(...)`。
- 插件 HTTP 端点只写插件内相对路径，最终由运行时挂载到 `/api/plugins/{pluginCode}/**`；管理端点必须保护权限。
- 访问宿主能力只能使用 SPI，如 `FrameworkServices`、`PluginContext`、`context.files()`、`context.documents()`。
- 插件自有模板只能位于本插件 `src/main/resources/templates/`，通过 `PluginContext.templateRenderer()` 以不带 `.html` 的逻辑名渲染；禁止读取宿主/其他插件模板、绝对路径或 `..` 跨目录读取。
- 启用时注册的线程池、连接、SSE 资源、外部 client、定时任务和回调必须通过 `context.onDispose(...)` 或对应生命周期在 disable/unload 时释放。
- 不在构造函数或扫描阶段建立外部连接、执行迁移、网络请求或大 I/O；`onEnable` 保持轻量装配。

## 5. Public / User / Admin 数据边界（强制）

每个路由、组件、端点和用例先分类为 public、user、admin。**权限决定能否进入，数据范围决定能访问哪些记录；管理权限不能扩大用户端数据范围。**

- 用户端使用 `/me/**`（禁止新增 `/my/**`），归属/owner 只能从 `request.principal().userId()` 或可信身份上下文推导。
- 用户 DTO、query、path、body 不得用 `userId`、`ownerId`、`memberId` 等选择当前资源所有者；查询/变更尽量以 `resourceId + currentOwnerId` 约束。
- 管理员调用用户端时仍是普通用户范围，只能访问自己的数据。
- 禁止在用户端以 `hasPermission(MANAGE_PERMISSION)`、`isAdmin` 或 `canManage` 切换为跨用户数据集。
- 跨用户、系统范围的查询/修改/删除只放在独立 `/admin/**` API，并使用管理权限、独立 controller、application use case、API wrapper、路由、页面与状态。
- 用户拥有可维护的持久记录时，需同步评估管理员维护闭环：跨用户分页列表、筛选、详情与适用的创建、编辑、状态、删除/归档、审核能力。
- 管理集合只要可能超过 5 条，必须后端分页；禁止前端一次性拉无限列表或以装饰卡片逃避表格、分页和行操作。
- 必须覆盖授权矩阵：普通用户自身/他人、管理员走用户端自身/他人、管理员走管理端、未认证请求，并覆盖 total、分页、下载、导出、批量、SSE、文件等旁路。
- 系统已经拥有权威列表时，禁止要求管理员手输内部 ID、provider code、connection ID、group ID、user ID 等；提供 options/selector API 与带标签选择器。

## 6. 前端远程模块与 UI

推荐结构：

```text
yudream-frontend/packages/plugin-{code}/
  src/api/           SDK-backed API wrappers
  src/components/    可复用插件 UI
  src/composables/   加载、变更、刷新等状态工作流
  src/pages/         路由级页面
  src/index.ts       remote contract
  src/types.ts       共享模型
  vite.config.ts
```

- 使用 Vue Composition API 与 `<script setup lang="ts">`；禁止把完整插件塞入一个 `.vue`。
- `src/index.ts` 必须导出宿主所需 remote contract；生产环境依赖 JAR 中的 ESM remote entry，workspace 加载仅作开发便利。
- 普通插件 API 只能使用宿主注入的 `@yudream/plugin-sdk`；禁止新建私有 axios/fetch 客户端或硬编码宿主 origin。
- 用户 API wrapper 仅调用 `/me/**`，管理员 wrapper 仅调用 `/admin/**`；两者不得共享可混合个人/管理数据的 store/composable cache。
- 远程包不继承宿主 auto-import。`@yudream/components` 必须显式导入。
- UI 优先级：1) `@yudream/components` 的 `Fa*` / `useFa*`；2) 无对应能力时 `@arco-design/web-vue`；3) 两者组合的本地组件；4) 有明确能力缺口才新增第三方 UI 依赖。
- 一个 route page 只服务一个主要业务工作流。独立列表、设置、统计、导入导出、迁移、审计、诊断、复杂编辑器必须拆独立路由；禁止巨型 tabs 页面混装无关管理中心。
- `FaModal` 仅适合短小、聚焦、可完成/取消的任务；中等上下文查看/编辑可用 `FaDrawer`；深度、多段、可链接或长流程必须使用 route page。
- 表单要显式类型和校验，防重复提交，展示后端错误，关闭后清理临时状态。页面必须考虑 loading、empty、error、disabled、success 与窄屏布局。
- 管理表格使用项目标准 `FaTable` 风格；破坏性操作必须确认，并完成权限、端点、反馈、刷新与分页空页回退。
- 中文文案必须为正常 UTF-8，禁止 Unicode 转义和乱码。

## 7. 前端样式、JS 与静态资源

后端 JAR 必须将前端 `dist` 全量打入：

```text
META-INF/yudream-plugin/frontend/{pluginCode}/remoteEntry.js
META-INF/yudream-plugin/frontend/{pluginCode}/manifest.json
META-INF/yudream-plugin/frontend/{pluginCode}/assets/*
```

- Vite remote 必须产出 ESM `dist/remoteEntry.js`。资源引用应保持相对路径/相对 `base`，让 JS chunk、CSS、图片、字体从插件 assets 地址正确解析。
- 默认样式兼容模式是：`import styles from './styles.css?inline'`；在 `install()` 创建或复用稳定 ID 的 `<style>`，并**每次更新**已有元素的 `textContent`。不得在发现标签已存在时直接 return。
- 支持独立 CSS、JS 和资源时，优先使用 Vite `manifest.json` 自动发现入口依赖；插件代码不应手写带 hash 的 `List.of("assets/*.css")` 或 `List.of("assets/*.js")`。
- **禁止**对 `assets/**` 做浏览器端或宿主端目录扫描/通配盲加载。该目录同时包含 dynamic-import chunks、CSS、字体、图片、媒体等；把全部文件当 `<link>`/`<script>` 会造成错误执行、重复请求和不可控顺序。
- 动态 import 的 JS chunk 由 ESM 按需加载，不能因为目录扫描而提前作为 script 执行。
- 只有少数非 Vite 或必须在入口前执行的脚本才使用显式 `styles`/`scripts` 补充清单；路径必须相对、不得以 `/` 开头、不得含 `..` 或反斜杠，并需去重与保持顺序。
- 静态图片、字体、JSON、媒体等资源随 dist 打入；通过宿主 SDK 的 `sdk.assets.url("assets/logo.svg")`（所用 SDK 版本已发布该契约时）或构建器生成的相对 URL 使用，不硬编码宿主 origin。

## 8. 版本与更新日志（强制）

- 每次完成插件功能新增、行为变更或 bug 修复时，必须根据改动范围自动更新**受影响插件**的版本和更新日志；纯格式化、注释、测试、构建脚本或文档改动且不改变插件产物行为时不得无意义升级版本。
- 使用稳定 SemVer `MAJOR.MINOR.PATCH`：
  - `PATCH`：向后兼容的 bug 修复、性能/稳定性/安全修复。
  - `MINOR`：向后兼容的新功能、可选配置、新增 API 或 UI 能力。
  - `MAJOR`：不兼容的配置、API、数据、权限、路由、依赖或行为变更。
- 同一插件的版本必须同步写入其 Maven `pom.xml`、`src/main/resources/plugin.yml`；禁止只修改其中一处。插件版本独立于 Git tag：受保护的 `vMAJOR.MINOR.PATCH` tag 只是发布事件的标记/触发器，不要求与任何插件版本相等。
- 每个受影响插件在 `src/main/resources/store.json` 的 `releaseNotes` 中记录本版本面向用户的变更，使用简洁 UTF-8 中文，说明新增、修复、破坏性变更及必要迁移步骤。若文件不存在或缺少该字段，应在本次版本发布时补齐；不得写入密码、token、内部地址或无关实现细节。
- 一个提交涉及多个插件时，分别判断并更新每个受影响插件的版本与 `releaseNotes`；未受影响插件不得跟随升版。根聚合版本、SPI/SDK 版本不因普通插件业务改动自动升级。
- 版本升级后必须重新构建受影响 JAR，并检查商店生成的 Raw descriptor 中 `plugin.version`、`releaseVersion` 与 `releaseNotes` 与源码一致。
### 发布清单 `release/plugins.txt`（强制）

Tag 发布是显式选择性发布，发布范围由版本控制的 `release/plugins.txt` 决定，不做基于 git diff 的改动模块自动检测。

- 每次发布提交必须同步编辑 `release/plugins.txt`：每行一个本次要发布的 `yudream-plugin-*` artifactId，不写插件 code、版本号或其他内容。
- 清单中的模块必须是根 `pom.xml` `<modules>` 声明的插件模块；空清单、重复项、未知模块都会被 CI 拒绝。
- 受保护的 `v*` tag 流水线只打包（`mvn -pl <清单> -am`）、发布和回读清单中的模块；`-am` 的依赖模块不会进入发布产物。
- 清单中每个插件的发布版本取自其 `plugin.yml version`（与模块 `pom.xml` 同步），必须是稳定 SemVer；插件版本独立于 tag，tag 仅标记/触发发布事件，并作为全局 `plugin-catalog` 清单坐标版本。
- 未列入清单的插件不发布、不检查版本、不跟随升版；其线上 JAR 与 Raw 商店条目保持不变。
- 清单随发布提交进入代码审查，审查人必须核对“清单内容 == 本次实际改动且需要发布的插件”。
- 全局 `plugin-catalog:<version>` 坐标只包含本次所选插件行；禁止把同一版本号拆给两个不同 tag 发布不同插件，每次发布的 tag 版本必须唯一。
- 发布前必须运行 `sh ci/verify-plugin-release-selection.sh`；本地可用 `PLUGIN_PACKAGE_VERSION=vX.Y.Z` 或 `PLUGIN_RELEASE_MODULES=...` 覆盖做选择/版本预检，CI tag 环境强制 `PLUGIN_RELEASE_ONLY=1`，不允许回退全量发布。

## 9. 构建、验证与发布

环境：JDK 21、Maven 3.9+、Node.js 22.22+/24.15+、pnpm 11.9+。

常用验证（以目标插件 code 替换 `{code}`）：

```powershell
Set-Location yudream-frontend
pnpm install --frozen-lockfile
pnpm --filter @yudream/plugin-{code} run typecheck
pnpm --filter @yudream/plugin-{code} run build
Set-Location ..

mvn -pl yudream-plugins/yudream-plugin-{code} -am test
mvn -pl yudream-plugins/yudream-plugin-{code} -am package -DskipTests
& 'C:/Program Files/Git/bin/sh.exe' ci/verify-plugin-repo-readiness.sh
```

- 前端改动必须至少执行目标包 typecheck/lint 与 build；后端改动必须执行目标 Maven test/package 或明确报告环境阻塞。
- 跨插件、依赖、打包、发布或工作区变更必须运行 `ci/verify-plugin-repo-readiness.sh`。
- 全栈改动必须检查最终 JAR 实际包含 `plugin.yml`、`remoteEntry.js`、`manifest.json` 和所需 assets；未检查前不得声称完成。
- CI 凭据、密码、token、API key 等仅通过受保护变量注入；禁止提交到仓库、日志、文档或示例。
- 文档不得包含本机绝对路径、敏感信息或已过时的行为描述。

## 10. 完成前自检

- 前后端 plugin code、入口、菜单、路由、权限、HTTP path、资源路径是否一致？
- 是否引入了宿主内部依赖、重复共享包、私有 API client、数值型长 ID 或未授权端点？
- user/admin 是否完全分离，且个人端没有管理员越权分支？
- 是否满足表格、分页、管理操作、状态反馈与响应式布局要求？
- 是否完成目标构建/测试，检查了最终 JAR，并同步了受影响文档？
