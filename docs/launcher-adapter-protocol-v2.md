# 启动器适配协议 v2（launcher-adapter v2）

> 状态：设计定稿，实现中。本文档是 launcher-adapter 与 YMCL 之间的**唯一权威契约**，
> 两侧以此为准并行实现，避免返工。
> 与 v1 的关系：v1 端点（`/v1/manifest`、`/v1/pages/**`、`/v1/packs/**`）保留至 YMCL 完成 v2 迁移；
> `launcher-contract.md` 的分发模型（Profile/Revision/Entry 对账）与 device-code 登录被本文档吸收（见 §5、§2.5），原文档其余部分作废。

## 0. 设计原则与 v1 痛点

YMCL 是**协议解释器**：不在代码里写死任何业务页面、菜单、tab、权限判断。域侧一切可变内容
（导航树、页面、数据源、动作、可安装包、认证方式、当前用户权限）全部由 launcher-adapter 下发。

v1 的实际痛点（本协议逐项解决）：

1. **权限节点靠 scope 后缀推导**：`can_manage_packs` 由 `granted_scopes` 是否含 `:manage` 后缀拼出来，
   与宿主真实权限脱节，超管/普通用户表现不一致。→ v2 由后端直接下发 `permissions` 全集与已过滤的
   `actions`，前端零推导（§2.6、§6）。
2. **导航数据三处拼装**：provider pages、chrome 菜单树、`pageVisibility` 硬编码 Map 共同决定导航，
   字段语义重叠、顺序/可见性规则分散，是"数据格式定义始终出问题"的根源。→ v2 导航树是**单一事实源**，
   chrome 只做覆盖（§3.4），`pageVisibility` 删除。
3. **页面类型与前端组件硬编码绑定**：`server-list/article-list/...` 在 YMCL 侧是业务 if-else。→ v2 收敛为
   **渲染器注册表**（§3.3），页面只声明 `view.renderer + props`，未知渲染器优雅降级不崩溃。
4. **包分发只有 mrpack 全量**：无原版渠道、无增量更新。→ v2 引入 channel + Revision/Entry 对账（§5）。
5. **认证只有 OAuth 授权码一条路**：离线/无浏览器/快速登录场景跑不通。→ v2 四种认证方式，
   统一会话模型（§2）。

## 1. 总览

| 面 | 端点前缀 | 说明 |
| --- | --- | --- |
| 认证 | `/api/plugins/launcher-adapter/v2/auth/{methods,session}` + 宿主公开端点 | adapter 只做方式发现与会话规范化；账密/刷新/OAuth 交换直连宿主（§2） |
| 清单 | `/api/plugins/launcher-adapter/v2/manifest` | 导航/页面/数据源/主题/权限，按当前用户个性化 |
| 数据 | `/api/plugins/launcher-adapter/v2/data/{providerCode}/{dataSourceCode}` | 统一信封 v2，含动作 |
| 动作 | `/api/plugins/launcher-adapter/v2/action/{providerCode}/{actionCode}` | `remote` 类型动作回传 |
| 分发 | `/api/plugins/launcher-adapter/v2/packs/**` | mrpack / vanilla / 增量对账 |

约定：

- 所有响应裸 JSON（插件端点 `wrapResult=false`），错误为 HTTP 状态码 + `{"message": "..."}`。
- 认证头为**裸 token**：`Authorization: <accessToken>`（sa-token 约定，不加 `Bearer` 前缀）。
- 所有 Long/Snowflake ID 在 JSON 中一律为 `string`。
- 所有枚举值小写中划线（`server-list`、`oauth-web`）。
- 客户端必须忽略未知字段；服务端必须容忍旧客户端缺字段（有默认值）。

## 2. 认证与会话

> 一期（本版实现）原则：**复用宿主既有公开端点，零宿主改动**。账密登录、token 刷新、
> OAuth token 交换都是宿主已经发布的稳定公开 API（Web 前端同用），YMCL 直连这些端点拿 token，
> 再统一调 adapter 的 `/v2/auth/session` 换取规范化会话。adapter 不重打包宿主认证逻辑
> （登录日志、验证码、限流、邮箱校验等已在宿主端点内）。device-code 与 external 交换为二期
> （需宿主 OAuth 设备许可与外部登录回跳目标支持），协议字段先冻结，`methods` 届时才广告。

### 2.1 统一会话（AuthSession）

**认证分两步**：①方式专属端点拿 token（见 §2.3/§2.4，返回各方式原生结构，YMCL 只抽取
`accessToken/refreshToken/expiresIn`）；②携带 token 调 adapter 会话端点换规范化会话信息：

```
GET /v2/auth/session        （Authorization: <accessToken>）
→ 200 SessionInfo           | 401 令牌失效
```

服务端响应 `SessionInfo`（adapter 无法得知 token 原值、refreshToken 与过期时间——
它们由 YMCL 在步骤①的登录响应中持有，与 `issuedVia` 一起本地合并为完整 `AuthSession` 持久化）：

```json
{
  "tokenName": "Authorization",
  "user": {
    "id": "3088506834",
    "username": "husky",
    "nickname": "哈士奇",
    "avatar": "https://...",
    "email": "3088506834@qq.com"
  },
  "permissions": ["plugin:launcher-adapter:view", "plugin:minecraft-server:view", "plugin:mc-news:view"]
}
```

YMCL 本地持久化的完整会话对象 `AuthSession = SessionInfo + {accessToken, refreshToken, expiresIn, issuedVia}`：

```json
{
  "accessToken": "sa-token 值",
  "tokenName": "Authorization",
  "refreshToken": "刷新令牌，无则空串",
  "expiresIn": 604800,
  "user": {
    "id": "3088506834",
    "username": "husky",
    "nickname": "哈士奇",
    "avatar": "https://..."
  },
  "permissions": ["plugin:launcher-adapter:view", "plugin:minecraft-server:view", "plugin:mc-news:view"],
  "issuedVia": "password"
}
```

- `permissions` 由宿主在插件请求分发时解析（sa-token 会话 = 全量 RBAC 权限；OAuth 令牌 = 已授权
  scope 集合，天然是该令牌的收窄身份），adapter 原样透传。YMCL 只用它做显示级判断
  （按钮显隐），**禁止**再从 OAuth scope 后缀推导能力。
- 真正的强制鉴权永远在后端：每个 v2 端点按自身权限码拦截，manifest/数据/动作在构建期按
  `permissions` 裁剪（§3.5、§4.2）。
- `issuedVia` 取值：`password` | `oauth-web` | `device-code` | `external:{providerCode}`；
  session 端点无从得知登录方式，由 YMCL 本地记录随 token 一并持久化、查询时回显。
- 所有方式共用同一个 `/v2/auth/session` 建立会话——YMCL 只有一条会话建立代码路径。

### 2.2 认证方式发现

```
GET /v2/auth/methods        （匿名）
```

```json
{
  "methods": [
    {"type": "password", "title": "账号密码登录",
     "endpoint": "/api/user/login", "refreshEndpoint": "/api/user/token/refresh"},
    {"type": "oauth-web", "title": "网页授权登录",
     "authorizeUrl": "/oauth/authorize?response_type=code&client_id=ymcl&redirect_uri=ymcl%3A%2F%2Fauth%2Fcallback&scope=openid+profile&state={state}",
     "tokenEndpoint": "/api/oauth/token"},
    {"type": "external", "title": "QQ 登录", "providerCode": "qq",
     "providersEndpoint": "/api/external-login/providers"}
  ],
  "registrationEnabled": true
}
```

- 后端按宿主配置动态生成：password 恒在（宿主账密体系是基线能力）；oauth-web 仅当 YMCL 公开
  OAuth 客户端登记成功；external 二期实现（届时列表来自宿主公开 provider 端点）；device-code 二期。
- YMCL 按 `type` 渲染对应登录表单/按钮，**列表里没有的方式不渲染**；条目里的 `endpoint` 一律
  原样使用，禁止客户端拼宿主路径。

### 2.3 账密直连（password）

YMCL 直接调宿主公开端点（`methods` 条目的 `endpoint` 原样使用）：

```
POST /api/user/login
{"principal": "用户名或邮箱", "password": "..."}
→ 宿主 UserLoginRes（Result 包裹）：token / refreshToken / expiresIn / 用户基础字段
```

随后 `GET /v2/auth/session` 换 AuthSession。密码只在 TLS 通道内传输，YMCL 本地不持久化明文。
失败沿用宿主语义（401/400 + 中文 message），登录日志/限流由宿主端点自带。

### 2.4 网页授权（oauth-web）

保留 v1 的 OAuth2 授权码流：`authorizeUrl` 在系统浏览器打开，回调 `ymcl://auth/callback` 拿到
code 后，YMCL 直连 `tokenEndpoint`（`/api/oauth/token`，form：grant_type=authorization_code、
code、redirect_uri、client_id）换 token，再 `GET /v2/auth/session`。

- 与 v1 的唯一区别：scope 由 adapter 在 `authorizeUrl` 模板里按已登记客户端聚合下发，
  YMCL 不自行拼 scope；能力判断改走 `session.permissions`，granted_scopes 不再出现在客户端逻辑。

### 2.5 设备码（device-code，二期）

协议冻结（吸收自 `launcher-contract.md` §3），宿主 OAuth 服务端需新增设备许可，一期不广告：

```
POST /v2/auth/device/code            → {"deviceCode","userCode","verificationUri","expiresIn","interval"}
POST /v2/auth/device/token           {"deviceCode"} → 202 等待中 | token 载荷
POST /v2/auth/device/approve         （网页端已登录用户确认 userCode）
```

### 2.6 第三方登录（external，二期）

- 流程：读 `providersEndpoint` 拿启用列表 → 系统浏览器打开 provider 授权 URL（宿主生成，需支持
  回跳目标 `ymcl://auth/external`，宿主外部登录现状只回跳站点前端，二期扩展）→ 拿到一次性 ticket →
  `POST /v2/auth/external/exchange {providerCode, ticket, state}` 换 token → `/v2/auth/session`。
- 未绑定宿主账号的第三方身份：428 `{"message":"该第三方账号未绑定","claimToken":"..."}`，
  引导先账密登录后在“账号绑定”完成认领（宿主既有 `/api/user/me/external-accounts/claim`）。

### 2.7 刷新、登出

- 刷新：YMCL 直连 `refreshEndpoint`（`/api/user/token/refresh`，`{"refreshToken":"..."}`），
  用新 token 重取 `/v2/auth/session`。
- 登出：客户端本地丢弃 token 即可（宿主无登出端点，Web 前端同此惯例）；sa-token 会话到期自然失效。
- `GET /v2/auth/session` 同时是启动时恢复会话的校验端点：401 即掉线清本地 token。

## 3. Manifest v2：导航 / 页面 / 菜单全下发

```
GET /v2/manifest            （匿名可得公开版；带 token 得个性化版）
```

```json
{
  "manifestVersion": "2",
  "domain": {"name": "余梦", "pluginCode": "launcher-adapter", "version": "0.6.0"},
  "auth": {"methods": ["password", "oauth-web"]},
  "session": {
    "authenticated": true,
    "user": {"id": "3088506834", "username": "husky", "nickname": "哈士奇", "avatar": ""},
    "permissions": ["plugin:launcher-adapter:view", "plugin:minecraft-server:view"]
  },
  "capabilities": {"packs": true, "distribution": true, "extensionPages": true},
  "navigation": {
    "mode": "tree",
    "tabs": [ /* NavNode 树，见 §3.2 */ ]
  },
  "pages": [ /* Page，见 §3.3 */ ],
  "dataSources": [ /* DataSource，见 §4.1 */ ],
  "theme": {"displayName": "...", "logoUrl": "...", "backgroundUrl": "..."},
  "updatedAt": "2026-09-14T12:00:00"
}
```

- `session.authenticated=false` 时 `user/permissions` 省略；匿名只收到无 `requiresPermission` 的页面。
- YMCL 本地缓存最近一次 manifest 用于离线启动骨架；`updatedAt` 变化即整份重拉（v2 不做 manifest 增量）。

### 3.1 单一事实源

导航**只有一棵树**：`navigation.tabs`（顶层 tab）→ `children`（分组/页面）。它由 adapter 按下述规则
生成，v1 的 `pageVisibility`、平行的 `menuGroups`、YMCL 侧任何"内置页面列表"全部废除。

### 3.2 NavNode

```json
{
  "code": "tab-main",
  "kind": "tab",
  "title": "主页",
  "icon": "i-ri:planet-line",
  "order": 1,
  "pageCode": "",
  "children": [
    {"code": "grp-servers", "kind": "group", "title": "服务器", "icon": "i-ri:server-line", "order": 1,
     "children": [
       {"code": "page-mc.servers", "kind": "page", "title": "服务器列表", "icon": "i-ri:list-check",
        "order": 1, "pageCode": "mc.servers", "path": "/domain/mc.servers"}
     ]}
  ]
}
```

- `kind`：`tab`（顶层页签）| `group`（页签内分组，无页面）| `page`（叶子，必有 `pageCode`+`path`）。
- `path` 由 adapter 统一生成（`/domain/{pageCode}`），YMCL 只做路由拼接，不自行造路径。
- 节点标题/图标缺省时回落页面自身 title/icon；`group`/`tab` 无页面时 `path` 取首个后代的 path。

### 3.3 Page 与渲染器注册表

```json
{
  "code": "mc.servers",
  "title": "服务器列表",
  "icon": "i-ri:server-line",
  "path": "/domain/mc.servers",
  "providerCode": "minecraft-server",
  "view": {
    "renderer": "server-list",
    "props": {"showStatus": true, "playerClickAction": "copy"}
  },
  "dataSourceCode": "mc.servers.list",
  "requiresPermission": "plugin:minecraft-server:view"
}
```

- `view.renderer` 是**渲染器名**，YMCL 内置注册表按键查表（查表 ≠ 业务硬编码：表内只有通用
  展示组件，没有任何 provider/页面 code 判断）：

  | renderer | 用途 | payload 项形状 |
  | --- | --- | --- |
  | `server-list` | MC 服务器卡片列表（状态/ping/人数/复制地址） | ServerEntry |
  | `article-list` | 图文资讯列表 | ArticleEntry |
  | `card-grid` | 通用卡片网格（图/标题/副标题/动作） | CardEntry |
  | `activity` | 活动/签到面板 | ActivityEntry |
  | `skin-closet` | 皮肤衣柜 | SkinEntry |
  | `pack-catalog` | 整合包目录（安装/更新入口；旧值 `pack-list` 仅作 v1 manifest 别名） | PackEntry |
  | `rich-text` | 静态富文本（payload 单条 html/markdown） | — |
  | `iframe` | 内嵌网页（`props.url`） | — |
  | `extension` | 远程扩展模块（§3.6） | 扩展自定义 |

- 未知 renderer：YMCL 渲染"当前启动器版本不支持此页面（{renderer}），请升级"占位页，**不崩溃、不空白**。
- `props` 为渲染器专属配置（键值对），渲染器文档化自己的 props；YMCL 透传不解释。
- `dataSourceCode` 可空（纯静态/iframe 页）。

### 3.4 chrome 覆盖语义（管理端）

站点管理员在后台「启动器外观」只编辑**覆盖层**：

- 增删/重排 tab 与 group、把 page 节点挂到任意 tab/group 下；
- 覆盖任意节点的 title/icon/visible；
- 品牌三件套（displayName/logoUrl/backgroundUrl）。

**不允许**在 chrome 里定义页面本体（页面永远来自 provider）。新生成的导航树 = 
provider 页面按 `navOrder` 的默认树 → 套用 chrome 覆盖 → 按当前用户 `permissions` 裁剪（§3.5）。
新启用的 provider 页面若未被覆盖树引用，自动追加到第一个 tab 末尾，保证"装了插件就能看见"。

### 3.5 构建期权限裁剪

adapter 构建 manifest 时：

1. 解析当前用户的 `permissions`（匿名 = 空集）；
2. 丢弃 `requiresPermission` 不在其中的 Page，并同步剪掉导航树中引用它们的 `page` 节点
   （空 group/tab 一并剪掉）；
3. `session.permissions` 原样下发。

YMCL 拿到的一定是"当前用户全部可见"的视图，无需也不允许再做权限判断。

### 3.6 扩展页面（extension renderer）

需要富交互的 provider 可走 `renderer: "extension"` + `props.extensionPackageId`：YMCL 从
`/api/plugins/launcher-adapter/v2/extensions/{extensionPackageId}/remoteEntry.js` 拉取 ESM 远程模块渲染
（与宿主插件前端同一套 module federation 沙箱）。扩展包清单与加载失败降级（回落到
`props.fallbackRenderer` 指定的内置渲染器）由 adapter 下发。v2 首版 YMCL 可实现为占位页，
协议字段先行冻结。

## 4. 数据信封 v2 与动作模型

### 4.1 DataSource 声明

```json
{
  "code": "mc.servers.list",
  "providerCode": "minecraft-server",
  "schemaVersion": 2,
  "endpoint": "/api/plugins/launcher-adapter/v2/data/minecraft-server/mc.servers.list",
  "pageCode": "mc.servers"
}
```

### 4.2 统一信封

```
GET /v2/data/{providerCode}/{dataSourceCode}?page=1&pageSize=20&query=...
```

```json
{
  "schemaVersion": 2,
  "dataSource": "mc.servers.list",
  "page": {"page": 1, "pageSize": 20, "total": 42},
  "payload": [ /* renderer 定义的条目形状 */ ],
  "actions": [
    {"code": "refresh", "title": "刷新", "icon": "i-ri:refresh-line", "kind": "secondary", "type": "client:reload"},
    {"code": "copy-all", "title": "复制全部地址", "kind": "secondary", "type": "client:copy", "payload": {"text": "{items.address}"}}
  ],
  "itemActions": [
    {"code": "join", "title": "加入服务器", "kind": "primary", "type": "client:launch-server", "payload": {"address": "{item.address}"}},
    {"code": "favorite", "title": "收藏", "kind": "secondary", "type": "remote", "endpoint": "/api/plugins/launcher-adapter/v2/action/minecraft-server/mc.servers.favorite", "payload": {"serverId": "{item.id}"}}
  ]
}
```

- 分页参数与 `page` 块对全部分页数据源一致；不分页的数据源省略 `page`。
- `actions`（页面级）与 `itemActions`（条目级）均已按当前用户权限**在后端过滤**，YMCL 全量渲染。
- `payload` 中的 `{item.xxx}` / `{items.xxx}` 是渲染器侧模板占位，YMCL 通用求值。

### 4.3 动作类型

| type | 语义 | YMCL 处理 |
| --- | --- | --- |
| `client:reload` | 重新拉取当前数据源 | 通用 |
| `client:copy` | 复制文本到剪贴板 | 通用 |
| `client:open-url` | 系统浏览器打开 `payload.url` | 通用 |
| `client:install-pack` | 安装/更新整合包（§5） | 通用，走分发管线 |
| `client:launch-server` | 启动游戏并直连 `payload.address` | 通用 |
| `remote` | 回传后端执行 | `POST {endpoint}` 带 `payload`；响应 §4.4 |
| `extension:*` | 交给扩展模块处理 | 仅 extension 渲染器 |

YMCL 内置 `client:*` 处理器注册表；未知 type 的动作**隐藏不渲染**（不报错）。

### 4.4 remote 动作响应

```
POST /v2/action/{providerCode}/{actionCode}
{"payload": {...}}
→ 200 {"toast": "已收藏", "refresh": true}
```

- `refresh=true` 时 YMCL 重拉当前数据源；`toast` 统一提示。业务错误走 HTTP 4xx + `message`。

## 5. 分发与更新协议（mrpack / vanilla / 增量）

吸收 `launcher-contract.md` 的 Revision/Entry 对账模型，落到 launcher-adapter 既有 Pack 体系。

### 5.1 模型演进

`LauncherPackVersion` 增加：

| 字段 | 说明 |
| --- | --- |
| `channel` | `MRPACK`（默认，兼容现状）| `VANILLA` |
| `gameVersion` | 如 `1.21.1`（两个 channel 必填） |
| `loader` | `vanilla` / `fabric` / `forge` / `neoforge` / `quilt` |
| `loaderVersion` | loader 版本，`vanilla` 时空串 |
| `revision` | 单调递增整数，每次发布 +1（对账基准） |
| `entries` | Entry 列表（发布时由 mrpack index + overrides 物化；VANILLA 可为空） |
| `ignorePatterns` | glob 清单，对账时跳过（保护玩家本地文件：截图、存档、配置等） |

`Entry`：

```json
{
  "path": "mods/jei-1.21.1.jar",
  "sha1": "abc...",
  "size": 1234567,
  "tier": "required",
  "source": {"type": "managed"},
  "env": {"client": "required", "server": "unsupported"}
}
```

- `tier`：`required`（缺失/不符必装）| `recommended`（默认装，用户可关）| `optional`（默认不装）。
- `source.type`：`managed`（文件托管在 adapter，经 `/v2/packs/{packId}/files/{sha1}` 下载）|
  `url`（`source.url` 直链，通常是 Modrinth CDN）。
- `env` 语义同 mrpack index，缺省 `{client: "required", server: "optional"}`。

### 5.2 端点

```
GET /v2/packs/{packId}/versions/{versionId}/head
→ {"revision": 7, "etag": "\"7-abc123\""}

GET /v2/packs/{packId}/versions/{versionId}/manifest
→ {
    "packId": "...", "versionId": "...", "revision": 7,
    "channel": "MRPACK",
    "gameVersion": "1.21.1", "loader": "fabric", "loaderVersion": "0.16.5",
    "entries": [Entry...],
    "ignorePatterns": ["options.txt", "saves/**", "screenshots/**", "config/**"],
    "mrpack": "/api/plugins/launcher-adapter/v2/packs/{packId}/versions/{versionId}/mrpack"
  }

GET /v2/packs/{packId}/files/{sha1}          → 文件流（managed 条目）
GET /v2/packs/{packId}/versions/{versionId}/mrpack  → 首装快照（标准 mrpack，可选）
```

- `head` 是廉价更新检测：YMCL 本地记录已装 revision/etag，启动时逐已装包打 head，一致即跳过。
- `manifest.mrpack` 仅在发布方上传了完整 mrpack 时存在，供**首次安装**快速路径
  （标准 mrpack 导入，装完即等价于对账完成）；缺失时首装也走逐条 entries 下载。
- v1 的 `/v1/packs/{id}/versions/{vid}/index` + `/overrides` 保留给旧客户端。

### 5.3 VANILLA channel

- `entries` 为空（或仅 overrides 条目），客户端只按 `gameVersion + loader + loaderVersion`
  走标准原版/loader 安装；overrides 仍按对账模型下发（同一套 `/files/{sha1}`）。
- 更新语义：`gameVersion/loader/loaderVersion` 变化或 `revision` 提升（overrides 变化）即触发更新提示。

### 5.4 客户端对账算法（增量更新）

1. `head` 比对：revision/etag 与本地一致 → 完毕。
2. 拉 `manifest`。
3. 逐 Entry 计算本地文件 sha1（只算 `tier != optional` 或用户已勾选的 optional）：
   - 缺失或 hash 不符 → 加入下载队列（`managed` 走 `/files/{sha1}`，`url` 走 `source.url`）；
   - 本地存在但不在 entries 且**不匹配任何 `ignorePatterns`** → 删除；
   - 匹配 `ignorePatterns` → 永不触碰。
4. 下载完成校验 sha1 后原子替换，写本地 revision。

服务端无状态：任意 from→to 版本组合都由同一份最新 manifest 对账收敛，无链式 diff、
玩家改过的文件不会被误删。

### 5.5 发布管线

- 管理端发布 MRPACK 版本时上传完整 mrpack：adapter 解析 index + overrides，物化 `entries`
  （hashes 取 sha1，同时保留 sha512 于存储侧供 mrpack 快照重组），`revision+1`。
- VANILLA 版本只填 gameVersion/loader/loaderVersion + 可选 overrides.zip（物化为 overrides entries）。
- 权限：发布/回滚 `plugin:launcher-adapter:manage`；下载/对账 `plugin:launcher-adapter:view`（可匿名，站点可配）。

## 6. 权限模型

| 权限码 | 语义 |
| --- | --- |
| `plugin:launcher-adapter:view` | 消费 manifest/data/packs（通常随公开站点默认放开） |
| `plugin:launcher-adapter:manage` | chrome 覆盖编辑、包发布/回滚、扩展包管理 |
| provider 自身权限码 | 如 `plugin:minecraft-server:view`，决定其页面/数据源是否下发 |

规则：

- 权限**只**在后端判定与裁剪；YMCL 只消费下发结果（`permissions` 仅用于显示态，如"管理"入口显隐）。
- 废除 v1 的 scope 后缀推导（`can_manage_packs` 等）。OAuth scope 仅在 oauth-web 令牌签发时有效，
  不再出现在任何客户端逻辑里。
- 动作词遵循仓库词表：view / manage / publish / download（下载如对匿名关闭则用 `download` 控制）。

## 7. 迁移与兼容

1. launcher-adapter 同时暴露 v1 与 v2；`manifestVersion` 是客户端选择解释器的唯一依据。
2. YMCL 落地顺序：认证（§2）→ manifest v2 骨架与渲染器注册表（§3、§4）→ 对账安装器（§5.4）。
   每一步都可独立上线（v1 端点仍在）。
3. v1 下线条件：YMCL 全量发布 v2 客户端且观测期无回退，由 adapter 配置开关关闭 v1 端点。
4. SPI 演进保持二进制兼容：`LauncherPage` 新增构造器重载，旧 8/9 参构造保留并桥接
   （`type` → `renderer` 同名映射）；`LauncherProvider` 只新增 default 方法。

## 8. 实现清单

### launcher-adapter（后端）

- [x] `AuthV2Controller`：`GET /v2/auth/methods`（方式发现：password 恒在并声明宿主端点、
  oauth-web 按已登记客户端生成 authorizeUrl、external/device-code 二期再广告）与
  `GET /v2/auth/session`（principal + `PluginUserService.findById` 组装 AuthSession）。
  一期无需宿主 SPI/端点改动。
- [x] `ManifestV2AppService`：导航树生成（默认树 + chrome 覆盖 + 权限裁剪）、Page/DataSource 视图、
  session 块；`pageVisibility` 删除。
- [x] `LauncherDataV2Controller`：统一信封（page/payload/actions/itemActions）、remote action 转发。
- [x] SPI（adapter 自身 api 包）：`LauncherPage` 增加 `renderer/props/requiresPermission` 构造
  （10 参 + 兼容桥接，`type` → `renderer` 同名映射）；`LauncherDataSource` 不变；
  `LauncherProvider` 新增 `default` 动作执行钩子。
- [x] 分发：`LauncherPackVersion` 增 channel/gameVersion/loader/loaderVersion/revision/entries/
  ignorePatterns；发布时物化 entries；`/v2/packs/**` head/manifest/files/mrpack 端点；managed 文件存储
  （独立于插件扫描目录，沿用 market-source 经验）。
- [x] minecraft-server / mc-news 等 provider 改用新构造声明 renderer + requiresPermission。
- [ ] （二期，需宿主配合）device-code 许可、external 回跳 `ymcl://` 与 `/v2/auth/external/exchange`。

### YMCL（前端/Rust）

- [ ] `auth/`：登录页按 `/v2/auth/methods` 渲染；password/oauth-web 直连宿主端点拿 token；
  统一 `GET /v2/auth/session` 建会话；启动时 session 恢复（401 清本地）；删除 scope 推导。
- [ ] `manifest/`：v2 拉取与缓存；导航树 → 路由/菜单纯数据驱动渲染。
- [ ] `renderers/`：注册表（§3.3 九个内置渲染器）+ 未知渲染器占位页；`client:*` 动作处理器。
- [ ] `install/`：head → manifest → sha1 对账 → 并发下载 → 原子替换；VANILLA channel 接标准安装器；
  mrpack 首装快速路径沿用既有 `write_ymcl_mrpack`。
- [ ] 删除：pageVisibility 处理、内置页面清单、v1 packs 拼接逻辑（迁移观察期后）。
