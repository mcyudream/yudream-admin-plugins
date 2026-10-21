# 插件仓工程总纲

> **适用范围**：`yudream-admin-plugins` 全仓。
> **目标**：明确独立仓边界、版本/发布纪律、验证路径与文档维护规则。

专项规则见 [plugin-development.md](plugin-development.md)；经验库见 `../exp/README.md`；双仓同步按主仓仓库内 `docs/dev/repo-sync.md`。

## 1. 仓库职责与边界

- 后端模块：`yudream-plugins/yudream-plugin-{code}`；前端远程包：`yudream-frontend/packages/plugin-{code}`。
- 本仓是独立插件仓，不是宿主源码镜像；Java 仅消费**已发布** SPI，前端仅消费**已发布** plugin-sdk/components。
- 禁止依赖、导入、复制或 vendoring 宿主 domain/application/infrastructure/interfaces/bootstrap、宿主 Spring Bean/Mapper/Repository/DO/Controller/Request/Response、宿主前端 app 或本地宿主 workspace。
- 前端 workspace **只能**包含 `packages/plugin-*`；不得恢复通用 `packages/*`。
- 新功能前核对受影响 plugin code、权限、菜单、路由、HTTP API、数据范围、生命周期与打包产物；同时检查前后端、plugin.yml、入口、Vite 与 Maven 打包配置。
- 先参考行为最接近的现有插件；复杂 DDD 插件优先 `plugin-project-progress`。本地优先用宿主 plugin dev mode 与 DevTools（`docs/plugin-dev-mode.md`）。
- 保留用户已有未提交修改；禁止覆盖、回退、格式化或重构无关文件。工作区经常存在其他插件源码与临时文件，提交严格限定路径。

## 2. 依赖与版本所有权

| 依赖 | 唯一版本位置 |
|---|---|
| SPI | 根 `pom.xml` 的 `yudream.plugin.spi.version` |
| 插件间 `provided` provider | 根 pom 的 `yudream.plugin.<module>.version` + dependencyManagement |
| plugin-sdk/components | `yudream-frontend/pnpm-workspace.yaml` catalog，包内写 `catalog:` |

- 禁止消费方模块本地写 provider 版本，尤其禁止 `${project.version}` 与自身版本锁步。
- 契约版本**必须**先发布并确认 Nexus 可解析，再更新本仓；调整 catalog/依赖后必须同步 `pnpm-lock.yaml`。
- 禁止复制/打包 SDK、共享组件或 provider `*.api`；插件间 API 由 provider JAR 唯一拥有，consumer 用 `provided` 编译。
- Java Long/Snowflake 在 JSON/DTO/TS/表单/路由/SDK 调用中均为 string；禁止 `Number(id)` 或数字控件承载长 ID。

## 3. 菜单与插件元数据

- 每个 JAR 必须含 `src/main/resources/plugin.yml`，至少 `name/main/version`；`name` 是稳定 code，`displayName` 仅展示。菜单分类、依赖图与 API 规则见 `plugin-development.md`。
- 末段权限动作词按主仓 `docs/dev/permission-codes.md`：修改动作词时按同步地图同步主仓词表、主仓集中分类实现和本仓受影响插件注册；主仓权威，**不得**自行创造同义动作。
- 后端、前端包、资源、路由、权限、API path 的 plugin code 必须一致。

## 4. 版本与更新日志

- 每次功能新增、行为变更或 bug 修复，自动更新**受影响插件**版本与 `releaseNotes`；纯格式、注释、测试、构建脚本、文档且不改产物行为时不得无意义升版。
- 稳定 SemVer：PATCH 修复/稳定/安全；MINOR 向后兼容新功能/API/UI；MAJOR 不兼容配置/API/数据/权限/路由/依赖/行为变化。一次发布含多类变更取最高级别；纯文档不升版。
- 插件 `pom.xml` 与 `plugin.yml` 版本必须一致。Git tag 与插件版本无须相同。
- 发布 tag 通用规则以主仓 `docs/dev/release-versioning.md` 为权威：已发布 tag 不可移动；发布失败或源码变化**必须**发新版本，**禁止**新建后缀 tag（`v1.4.9-1` 等历史后缀 tag 仅存档有效）；RC 为独立版本；`d*` 部署/测试标签**不得**更新 `latest`，`latest` 只随正式版更新。
- `src/main/resources/store.json` 的 `releaseNotes` 用简洁 UTF-8 中文写面向用户变化与必要迁移；不写密钥、token、内部地址或无关实现细节。无文件/字段时在本次发布补齐。
- 多插件提交逐个判断，未受影响插件不得跟随升版；普通业务改动不升根聚合/SPI/SDK 版本。

### 4.1 选择性发布清单

- Tag 发布范围由版本控制的 `release/plugins.txt` 决定，不按 git diff 自动猜测。
- 每次发布提交必须编辑清单：每行一个根 pom `<modules>` 中的 `yudream-plugin-*` artifactId；空、重复、未知项均由 CI 拒绝。
- 受保护 `v*` tag 只打包/发布/回读清单模块（`mvn -pl <清单> -am`；`-am` 依赖不进入发布物）。
- 版本取 plugin.yml 且与模块 pom 一致、必须稳定 SemVer；tag 仅作发布事件/全局 catalog 坐标。未列入清单不发布、不验版本、不升版。
- 全局 `plugin-catalog:<version>` 只含本次选中插件行；禁止同一版本拆给不同 tag 发不同插件，每次发布 tag 版本唯一。
- 发布前必跑 `sh ci/verify-plugin-release-selection.sh`；可用 `PLUGIN_PACKAGE_VERSION` / `PLUGIN_RELEASE_MODULES` 覆盖做本地预检；CI tag 强制 `PLUGIN_RELEASE_ONLY=1`。

## 5. 验证与提交

JDK 21、Maven 3.9+、Node 22.22+/24.15+、pnpm 11.9+。

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

- 前端改动至少目标包 typecheck/lint + build；后端改动目标 Maven test/package；跨插件/依赖/打包/发布/工作区变化跑 readiness。
- 全栈改动检查最终 JAR 确实含 `plugin.yml`、`remoteEntry.js`、`manifest.json` 和所需 assets；否则不得宣称完成。
- CI 凭据仅经受保护变量；不提交凭据、密码、token、API key、敏感文档/示例、本机绝对路径或过时描述。
- 每个独立模块验证后只暂存该模块，创建中文提交，再做下一模块。未经用户明确授权不 push/tag/release。

## 6. 文档与双仓同步

- 根 `AGENTS.md` 是本仓硬规则入口，`docs/dev/` 是规范，`docs/exp/` 是经验；不得再建立 skill 作为项目规范权威。
- 修改任一仓 `AGENTS.md`、`docs/dev/**` 或写入约束性记忆前，先查主仓 `docs/dev/repo-sync.md`；命中映射同一工作项同步主仓对应文件。暂不能同步则 commit 标 `[sync-pending]` 并写入主仓同步地图待办。冲突以主仓 `docs/dev/` 为准。
- 修改 `docs/yd-ui-guidelines.md` 必须先改宿主权威，再同一工作项同步插件副本。更新组件契约还需走发布验签后才能改本仓依赖。
- 经验格式：现象/根因/规避/来源；稳定规则从经验提炼进规范。
- secrets 只进被 gitignore 的 `docs/local.md`；`docs/plan/` 为不提交计划层。

## 7. AOCI 按任务范围读取

读取预算、精确条目分批、压缩恢复与全量读取的唯一规范正文是主仓 `docs/dev/conventions.md` 的「AOCI 按任务范围读取」节。本仓根 `AGENTS.md` 同步用户指定的读取覆盖条款，执行该规范；普通任务和上下文压缩恢复均按任务范围局部读取。
