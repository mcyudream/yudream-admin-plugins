# ymcl-adapter 插件

YMCL 启动器域适配协议（**YAP — YMCL Adapter Protocol v1**）的 yda 侧实现。
一个 yda 部署即一个「域」；本插件是域对启动器的唯一协议入口。

- 协议契约（权威）：YMCL 仓 `docs/ymcl-adapter-protocol.md`
- 启动器侧解释器：YMCL-Axolotl 仓（Rust `packages/app-lib/src/api/ymcl/`）
- **命名约定**：应用标识/协议/新增文件统一 `ymcl`；OAuth 公开客户端 id 固定 `ymcl`；
  启动器深链为 `ymcl://add-site?url={origin}`（一键唤起 / 拖拽加域）

## 已实现端点（0.2.0）

> 0.2.0 起域外观管理端（chrome/domain、chrome/home、chrome/navigation、
> admin/packs、admin/bundles）输出宿主 wrapped 信封；协议端点
> （capabilities/manifest/MIP）保持裸 JSON（wrapResult=false）。
>
> **导航树两级扩展**：manifest `navigation` 项新增可选 `children` 数组
> （tab → 子菜单，最多两级）；导航配置支持 `kind=page`（引用页面注册表，
> 可覆盖展示 title/icon）与 `kind=directory`（纯目录，manifest 输出为
> `type=group`，无路由）。页面属性（route/权限）由页面注册表决定；
> 停用项不下发。启动器侧需同步支持 `children` 渲染二级菜单与 group 节点。

| 端点 | 鉴权 | 说明 |
|------|------|------|
| `GET /api/plugins/ymcl-adapter/v1/capabilities` | 匿名（`wrapResult = false` 裸 JSON） | 协议版本、domain 摘要、认证方式发现（password / oauth-web PKCE，client id `ymcl`） |
| `GET /api/plugins/ymcl-adapter/v1/session` | `plugin:ymcl-adapter:view` | 规范化 SessionInfo（user/permissions/context，部门角色选项） |
| `GET /api/plugins/ymcl-adapter/v1/manifest` | 匿名可得，带 token 按权限裁剪 | 导航树（native 页 + 提供方页面）、pages 注册表、dataSources、actions.allow 白名单 |
| `GET /api/plugins/ymcl-adapter/v1/data/{providerCode}/{sourceCode}` | `plugin:ymcl-adapter:view` | 数据信封（records + 页面级/条目级声明式动作） |
| `POST /api/plugins/ymcl-adapter/v1/action/{providerCode}/{actionCode}` | `plugin:ymcl-adapter:view` | server 动作执行（提供方内部细化权限） |

## 扩展点（供其他插件贡献页面与数据源）

实现 `online.yudream.base.plugin.ymcl.api.YmclContributionProvider` 并
`context.registerExtension(YmclContributionProvider.class, impl)`（provided 依赖
`yudream-plugin-ymcl-adapter`，plugin.yml `softdepend: ymcl-adapter`，缺失时
捕获 LinkageError 降级）。参考实现：minecraft-server 插件的
`MinecraftYmclContributionProvider`（`mc.servers` 页面 + `minecraft-server.servers`
数据源，复用 `MinecraftLauncherProvider.toCard`）、activity-proof 的
`ActivityYmclContributionProvider`（活动卡片页 + server 动作报名/取消）与
project-progress 的 `ProjectProgressYmclContributionProvider`（我的任务/可认领页 +
server 动作认领/时长打卡 + `my-stats` 统计数据源）。

字段名与启动器侧 Rust serde 结构**逐字一致（snake_case）**；YMCL 仓
`packages/app-lib/src/api/ymcl/client.rs` 内嵌本 payload 样例做契约锁测试，
任一侧改字段名该测试即失败。

| `POST /api/plugins/ymcl-adapter/mip/api/packs/{packId}/ingest` | `plugin:ymcl-adapter:publish` | 初始包（base64 JSON 通道） |
| `POST .../mip/api/packs/{packId}/ingest/delta` | `plugin:ymcl-adapter:publish` | 增量包（base+变更集合成标准 manifest；`bind` 一键绑定） |
| `GET /api/plugins/ymcl-adapter/mip/api/packs/{packId}/versions` | view | 版本列表（MIP §4：releasedAt 倒序，`?channel=` 过滤，yanked 不出现） |
| `POST .../mip/api/packs/{packId}/versions/{version}/yank` | publish | 撤销发布（MIP §4）：列表隐藏 + 标记 yanked，清单保留，已安装实例不受影响 |
| `GET .../mip/api/packs/{packId}/manifest/{version}` | view | 不可变 manifest |
| `GET /api/plugins/ymcl-adapter/mip/objects/{sha512}` | 匿名 | CAS 对象（immutable 缓存头） |
| `PUT /api/plugins/ymcl-adapter/v1/admin/packs/{packId}` | publish | 编辑 pack 展示元数据（name/description/icon/defaultChannel） |
| `DELETE /api/plugins/ymcl-adapter/v1/admin/packs/{packId}` | publish | 删除 pack 及全部版本，并解除引用该包的服务器绑定 |
| `DELETE .../v1/admin/packs/{packId}/versions/{version}` | publish | 删除单个版本（仍有服务器 pin 时 409） |
| `GET /api/plugins/ymcl-adapter/v1/admin/cas/{sha512}/preview` | view | 文件预览（text / mod 元数据 / image / binary） |
| `GET /api/plugins/ymcl-adapter/v1/chrome/home` | view / PUT design | 首页布局托管（PluginDocumentStore 持久化） |
| `GET /api/plugins/ymcl-adapter/v1/events` | view | SSE 事件流（环形重放 1000 条） |
| `GET .../mip/api/servers` / `PUT .../servers/{id}/binding` | view / publish | 服务器绑定聚合（MIP 附录 B + updatePolicy 扩展） |
| `PUT /api/plugins/ymcl-adapter/v1/bundles/{bundleId}/{version}` | publish | 上传注册扩展页面包（base64 zip，服务端重算 sha256，版本不可变） |
| `GET .../v1/bundles/{bundleId}/{version}/package.zip` | 匿名 | 下发扩展页面包 zip（immutable 缓存；启动器按 manifest sha256 校验） |

## 未实现（后续版本）

- `POST /v1/context/switch`（部门/角色切换端点；session 已下发选项）
- MIP 分发面：`/api/packs/**`（ingest / ingest/delta / versions / manifest / objects）与
  `/api/servers` 绑定聚合 —— **实现并宣告前不输出 `mip` capability 节点**，
  否则启动器启动前检查会对未实现端点 404 报错

## 部署 / 联调

0. **0.1.0 已构建并部署到本机 dev 实例**：JAR 位于
   `D:\code\yudream-admim\plugins\yudream-plugin-ymcl-adapter-0.1.0.jar`
   （dev 后端 = IDEA 从 D:\code\yudream-admim 启动的 8080 进程，插件目录
   `./plugins` 已确认）。`dev-projects.json` 已注册 ymcl-adapter。
1. **启用（需管理员，未完成）**：yda 后台「插件管理」→ 刷新 → 启用
   `ymcl-adapter`（或 `POST /api/platform/plugins/refresh` + enable，需
   管理员 token）。启用状态持久化在宿主，后续重启自动恢复。
2. **OAuth 客户端注册**：宿主「OAuth 客户端」注册公开客户端 client id
   `ymcl`，redirect `http://127.0.0.1:<port>/auth/callback`（回环）与
   `ymcl://auth/callback`（深链），PKCE S256；scope 覆盖 `profile` 与
   `plugin:ymcl-adapter:*`。
3. **端点冒烟验证**：`ci\ymcl-smoke.ps1 -Origin http://localhost:8080
   -Username admin -Password <密码>`（逐项校验 capabilities / manifest /
   登录 / session / data 信封）。
4. **启动器联调清单**：设置 → 域 → 输入 yda 地址加入 → 登录 → 域导航
   渲染 → mc.servers 服务器列表页（需 minecraft-server 同步启用）→
   /ymcl/design 设计器 → /ymcl/publish 发布推送。

## 端到端联调清单（加入域流程）

- [ ] `GET {origin}/api/plugins/ymcl-adapter/v1/capabilities` 返回 200 裸 JSON
- [ ] 启动器「加入域」成功，域列表显示 display name（当前为 origin，待 domain.name 配置化）
- [ ] 「启用」后导航为个人域内置树（manifest 端点未实现，预期降级）
- [ ] 域账号区显示「使用浏览器登录」（oauth-web）与「用户名/密码」两种方式
