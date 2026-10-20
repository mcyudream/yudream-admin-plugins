# YuDream Admin Plugins

> **适用范围**：`D:/code/yudream-admin-plugins` 插件仓全部工作。
> **目标**：提供本仓硬规则入口、最短必读路径与主仓规范同步纪律。

## 1. 项目卡

| 项 | 内容 |
|---|---|
| 类型 | 独立官方业务插件仓：Java JAR + Vue Web ESM remote + React Native 插件 |
| 主仓 | `D:/code/yudream-admim`，宿主与契约唯一权威 |
| 插件后端 | `yudream-plugins/yudream-plugin-{code}` |
| 插件前端 | `yudream-frontend/packages/plugin-{code}` |
| 契约 | 已发布 `yudream-plugin-spi`、`@yudream/plugin-sdk`、`@yudream/components` |

## 2. 开始工作前必读

- 全部任务：本文件 + `docs/dev/conventions.md` + `docs/dev/plugin-development.md`。
- 前端 UI：`yudream-frontend/AGENTS.md` 与 `docs/yd-ui-guidelines.md`（主仓同名文件是权威）。
- 插件用户/管理员边界：`docs/dev/plugin-development.md` 第 4 节；经验看 `docs/exp/access.md`。
- 管理列表、分页与响应式：规范见 `docs/dev/plugin-development.md` 第 5 节；经验看 `docs/exp/frontend.md`。
- 契约升级/发布/版本/打包：`docs/dev/conventions.md`；先读主仓 `docs/dev/contract-release.md`；宿主版本定级与发布 tag 规则以主仓 `docs/dev/release-versioning.md` 为权威。
- 修改本仓/主仓 AGENTS、docs/dev 或写入约束性记忆：先查主仓 `D:/code/yudream-admim/docs/dev/repo-sync.md`。
- 故障经验：`docs/exp/README.md` 分类索引。
- 本仓根 AGENTS 是硬规则入口；`docs/dev/` 是规范；`docs/exp/` 是经验。不得再建立 skill 作为项目规范权威。

## 3. 硬规则

1. **双仓同步**：修改任一仓的 `AGENTS.md`、`docs/dev/**`，或写入约束性记忆前，必须对照主仓 `docs/dev/repo-sync.md`。命中映射的同一工作项内同步对方仓对应文件，提交注明同步面；暂不能同步则提交标 `[sync-pending]` 并登记待同步。规范冲突以主仓 `docs/dev/` 为准。两仓 AGENTS.md 本条规则保持同文并互检。
2. **契约独立**：Java 仅用 Nexus 已发布 SPI；前端仅用已发布 SDK/components。禁止宿主内部代码、本地 workspace、复制/vendoring、私有宿主 client 或 Spring Bean 绕过。
3. **依赖版本单点维护**：SPI 用根 pom 的 `yudream.plugin.spi.version`；provider 插件依赖由根 pom dependencyManagement 管理；SDK/components 用 `pnpm-workspace.yaml` catalog，包依赖写 `catalog:`。改 catalog 后刷新 lockfile；消费契约前必须确认已发布并验签。
4. **插件边界**：JAR 根 `plugin.yml` 权威，`name/main/version` 必填；`depend/softdepend` 图无环；入口只装配；贡献经 PluginContext 注册并能完整释放。细则见 `docs/dev/plugin-development.md`。
5. **访问边界**：权限决定能否进入，数据 scope 决定记录范围；`/me/**` 永远从可信 principal 推导 owner，管理员走 user 面也只看本人；跨用户只走独立 `/admin/**`。
6. **前端组件**：Vue Web 业务只用已发布 `@yudream/components` 公开 `Yd*`/`useYd*`，禁止 Fa/裸 Arco/TDesign/Reka、别名和手写通用交互；缺能力先主仓封装、发布验签再消费。RN 使用独立 `@yudream/plugin-mobile-ui`。
7. **长 ID**：Java Long/Snowflake 跨 JSON/DTO/TS/表单/路由/SDK 都为 string，禁止 `Number(id)`。
8. **菜单与权限**：新插件按主要业务类别声明 `menu.category`，一级分类共享，功能放分类下；权限末段词遵守主仓 `docs/dev/permission-codes.md`，主仓权威。
9. **版本与发版清单**：行为变更只升受影响插件 SemVer 并同步 pom/plugin.yml/store.json releaseNotes；发布范围由 `release/plugins.txt` 选择。纯文档不升版；tag 与插件 SemVer 不要求相等。插件发布/发布 tag 的通用版本规则（已 tag 不可移动、源码变化发新版本、禁止新建后缀 tag、`d*` 部署标签不得更新 latest）以主仓 `docs/dev/release-versioning.md` 为权威。
10. **验证与提交**：目标前端 typecheck/build、后端 Maven test/package；跨插件/依赖/打包/发布跑 readiness，完整栈检查最终 JAR remote assets。独立模块验证后仅暂存其文件并中文提交。
11. **机密与外部动作**：secret 只放系统凭据管理器/env 或被忽略的 `docs/local.md`；计划在被忽略 `docs/plan/`。未经明确授权不 push/tag/release。
12. **保留工作区**：本仓可能有大量并行插件改动与未跟踪验收物，严禁全仓暂存；保留无关用户改动，不格式化/回退无关代码。

## 4. 沟通与推进

- 用户明确要求实现时按本规范自主推进到验证与结果汇报；评估/提问类请求先给判断，不擅自改代码。
- 删除、覆盖、发布等难逆/对外动作先确认；被明确要求的本地可逆工作自主完成。
- 验证未跑或失败必须明确报告，不把单测说成真实环境验收。

## 5. 当前边界

- `docs/yd-ui-guidelines.md` 是主仓同名规范的同步副本，不得独立扩展公共契约。
- 主仓插件运行时/主题/DevTools 规范在 `D:/code/yudream-admim/docs/dev/plugin-system.md`；插件开发适配细节在本仓 `docs/dev/plugin-development.md`。
- 插件契约发布顺序由主仓 `docs/dev/contract-release.md` 定义，本仓只消费已发布版本。
- 组件主题资产的 token 声明边界按同步的 `docs/yd-ui-guidelines.md`：唯一 SITE scope 要有 SITE 页面/公开路由锚点，唯一 ADMIN scope 限 `:root`/`.dark`；token 消费仍违规；未证明隔离的双 scope fail-closed。扫描器按 Java 元数据、入口和构建输出精确定位资产，不给业务 CSS 目录豁免。

## 6. 规则与经验入口

- 独立仓总纲与版本/构建：`docs/dev/conventions.md`
- 插件 DDD、权限/数据边界、remote frontend、主题与生命周期：`docs/dev/plugin-development.md`
- 经验：`docs/exp/README.md`
- YD 视觉/组件选型：`docs/yd-ui-guidelines.md`（主仓权威）
- 双仓同步唯一权威：主仓 `docs/dev/repo-sync.md`
