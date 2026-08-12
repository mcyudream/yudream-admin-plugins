# YMCL × yudream-launcher 接口契约

本文件是 YMCL 启动器与 `yudream-launcher` 插件之间的**权威接口契约**，两侧（`YMCL` 仓库的 Rust/React 端、`yudream-admin-plugins` 的 Java/Vue 端）都以此为准并行实现，避免返工。

- 插件 code：`yudream-launcher`
- 所有端点挂载在宿主命名空间 `/api/plugins/yudream-launcher/**` 下（宿主自动前缀，插件内路径全部相对）。
- SPI 版本：`2.6.0`。
- 认证头：沿用 yudream 核心的 Sa-Token 约定，`Authorization: <裸 token>`（**无 `Bearer` 前缀**）；或 per-user API Key（`Authorization: Bearer yda_...` / `X-API-Key: <key>`）。
- ID 一律以 String 出入 HTTP 边界。
- 包裹约定：对外协议端点一律 `wrapResult=false`（返回原始 JSON / 字节流，不套宿主 `{code,message,data}`）；错误也以原始 JSON 返回并带正确 HTTP 状态码。

## 0. 术语

| 术语 | 含义 |
|------|------|
| Node（节点） | 一个 yudream-admin 站点实例，由 `baseUrl` 唯一标识。 |
| Server（服务器） | 节点下的一个 Minecraft 服务器条目（来自 `minecraft-server` 插件）。 |
| Profile（分发约定） | 面向某服务器的一套客户端配置蓝图，含多个版本。 |
| Revision（版本） | Profile 的一次不可变快照，管理员每次「更新版本」产生一条。 |
| Entry（资源条目） | Revision 内的一条资源，含目标路径、档位、校验值、来源。 |
| tier（档位） | `required` / `recommended` / `optional` 三档。 |
| source（来源） | 资源获取方式：`url`（外链，节点零存储）或 `managed`（节点 S3 托管）。 |

## 1. 权限

| 权限 code | 说明 |
|-----------|------|
| `plugin:yudream-launcher:view` | 只读浏览（一般匿名端点无需权限，此权限预留给受限只读场景）。 |
| `plugin:yudream-launcher:user` | 登录用户：管理自己拥有/被授权的 profile、下载受限资源。 |
| `plugin:yudream-launcher:manage` | 节点级管理员：建改任意 profile、上传资源、提交/发布版本。 |

粒度：`manage` 先做**节点级**（能管该节点所有 profile）。数据模型预留 profile 级授权字段 `Profile.managerUserIds[]`，供后续把管理范围收紧到「某 server 的管理员只能推自己那个 profile」。owner 一律从 `principal().userId()` 派生，禁止从 query/body/path 取 userId，禁止在同一端点内用 `hasPermission(manage)` 放大数据范围。

## 2. 数据模型（Mongo，存 `documents()`）

### 2.1 Profile 集合 `profile`
```json
{
  "id": "string",
  "serverId": "string",
  "name": "string",
  "gameVersion": "1.20.1",
  "loader": "forge | fabric | neoforge | quilt | vanilla",
  "loaderVersion": "string | null",
  "iconUrl": "string | null",
  "currentRevision": 7,
  "managerUserIds": ["string"],
  "createdAt": 1700000000000,
  "updatedAt": 1700000000000
}
```

### 2.2 Revision 集合 `revision`
```json
{
  "id": "string",
  "profileId": "string",
  "rev": 7,
  "status": "draft | published | archived",
  "note": "string",
  "entries": [ /* Entry[]，见 2.3 */ ],
  "ignorePatterns": ["mods/optifine*.jar", "config/**", "options.txt"],
  "createdBy": "string",
  "createdAt": 1700000000000,
  "publishedAt": 1700000000000
}
```
- `rev` 在 profile 内单调递增。draft 不改 `Profile.currentRevision`；publish 时才前移。
- Revision 一旦 published 即不可变，可 archive 但不可编辑（回滚 = 发布指向旧内容的新 rev）。

### 2.3 Entry（资源条目）
```json
{
  "path": "mods/jei-1.20.1.jar",
  "tier": "required | recommended | optional",
  "sha1": "40 位十六进制",
  "size": 1234567,
  "source": {
    "type": "url",
    "url": "https://cdn.modrinth.com/data/xxx/versions/yyy/jei.jar"
  }
}
```
或托管来源：
```json
{
  "path": "mods/private-mod.jar",
  "tier": "required",
  "sha1": "……",
  "size": 234567,
  "source": {
    "type": "managed",
    "resourceId": "string"
  }
}
```
- `path`：相对实例根（`.minecraft`）的目标安装路径，使用正斜杠，禁止 `..` 与绝对路径。
- `sha1` + `size`：完整性元数据，供 YMCL 对账/校验/续传（核心 `FileObject` 不存 hash，此处补齐）。
- `source.type=url`：节点不存文件，YMCL 直连该 URL 下载。
- `source.type=managed`：文件在节点 S3，YMCL 走 `GET /resources/{resourceId}/download`（见 3.4）。

## 3. 只读端点（YMCL 消费端）

### 3.1 `GET /info` — 节点发现（匿名，`wrapResult=false`）
YMCL 绑定节点时首个探测点。轻量、匿名。
```json
{
  "node": "yudream-launcher",
  "apiVersion": 1,
  "name": "示例节点",
  "iconUrl": "https://.../icon.png",
  "features": {
    "authlibInjector": "/api/plugins/authlib-injector",
    "skin": "/api/plugins/yudream-skin",
    "minecraftServer": "/api/plugins/minecraft-server",
    "deviceCodeOAuth": true
  },
  "serverCount": 3,
  "profiles": [
    { "id": "p1", "name": "生存服", "serverId": "s1", "currentRevision": 7 }
  ]
}
```
- `features` 里给出同节点其他插件的挂载点，YMCL 据此接线 authlib/skin，无需额外探测。缺失或值为 `null` 表示该节点未启用对应插件。

### 3.2 `GET /profiles/{id}` — profile 详情（匿名，`wrapResult=false`）
返回单个 profile 的完整元信息，供管理端忠实回显 `gameVersion`/`loader`/`loaderVersion`/`iconUrl`，无需从 manifest 反推或靠编辑表单回填。
```json
{
  "id": "p1",
  "serverId": "s1",
  "name": "生存服",
  "gameVersion": "1.20.1",
  "loader": "forge",
  "loaderVersion": "47.2.0",
  "iconUrl": "https://.../icon.png",
  "currentRevision": 7,
  "managerUserIds": [],
  "createdAt": 1700000000000,
  "updatedAt": 1700000000000
}
```
- 不存在：`404`，`{ "message": "not found" }`。
- 与 `/info` 中的精简 profile 视图互补：`/info` 只给列表页所需字段，此端点给单个 profile 的全部元信息。

### 3.3 `GET /profiles/{id}/head` — 更新检测（匿名，`wrapResult=false`）
极小响应，供 YMCL 廉价比对是否有更新。启动器打开时 / 启动游戏前调用。
```json
{ "profileId": "p1", "currentRevision": 7, "etag": "sha1-of-manifest", "publishedAt": 1700000000000 }
```
- YMCL 缓存上次已应用的 `rev` 与 `etag`；不一致才拉完整 manifest（3.4）。

### 3.4 `GET /profiles/{id}/manifest?rev=<n>` — 完整清单（匿名，`wrapResult=false`）
不带 `rev` 取 `currentRevision`。支持 `If-None-Match: <etag>`，无变化返回 `304 Not Found` 空体。
```json
{
  "profileId": "p1",
  "rev": 7,
  "etag": "sha1-of-manifest",
  "gameVersion": "1.20.1",
  "loader": "forge",
  "loaderVersion": "47.2.0",
  "note": "更新 JEI 到最新版",
  "ignorePatterns": ["mods/optifine*.jar", "config/**", "options.txt"],
  "entries": [ /* Entry[]，见 2.3；含三档、每条 sha1/size、url 或 managed */ ],
  "mrpack": { "source": { "type": "url", "url": "https://.../pack.mrpack" }, "sha1": "…", "size": 0 }
}
```
- `mrpack`（可选）：该版本对应的整合包快照，供 YMCL「从零到有」首次建实例（复用 modrinth import）。之后版本变化走 entries 增量对账。没有则为 `null`。

### 3.5 `GET /resources/{resourceId}/download` — 托管资源下载（`user` 权限，`wrapResult=false`）
仅 `source.type=managed` 的资源走此端点。校验 `principal().userId()` 是否被授权访问该 profile 后，从 S3 流式下发原始字节。
- 成功：`200`，`Content-Type: application/octet-stream`，`Content-Length` 准确，body 为原始字节。
- 无权限：`403`，`{ "message": "forbidden" }`。
- 不存在：`404`，`{ "message": "not found" }`。
- url 类资源不经此端点，YMCL 直连原始 CDN。

### 3.6 `GET /sync/events` — 更新推送（SSE，`user` 权限）
`Content-Type: text/event-stream`。管理员发布新版本后向在线客户端推送。
- 事件 `connected`：`{ "ok": true }`（订阅即发）。
- 事件 `release`：`{ "profileId": "p1", "newRev": 8, "etag": "…" }`。
- YMCL 收到后提示「有更新」并可一键对账。SSE 是即时性优化，`head` 轮询是兜底最终一致，二者并存。

## 4. 写端点（管理端，`manage` 权限，`wrapResult=false`）

启动器内有 `manage` 权限的管理员直接调用，无需回 web 后台。

### 4.1 `POST /profiles` — 建 profile
Body：`{ serverId, name, gameVersion, loader, loaderVersion?, iconUrl? }`。返回创建后的 Profile。

### 4.2 `PUT /profiles/{id}` — 改 profile 元信息
Body：可选字段同上 + `managerUserIds?`。返回更新后的 Profile。

### 4.3 `POST /resources` — 上传托管资源（两段式）
先由前端/客户端调宿主通用上传拿到平台 `fileId`（见 §6），再 POST：
```json
{ "fileId": "平台文件id", "fileName": "private-mod.jar" }
```
后端用 `framework().platformFile(fileId)` 读字节，落入插件 `files()`，计算并存 `sha1/size`，返回：
```json
{ "resourceId": "string", "sha1": "…", "size": 234567 }
```

### 4.4 `POST /profiles/{id}/revisions` — 提交草稿版本
Body：
```json
{ "note": "更新说明", "entries": [ /* Entry[] */ ], "ignorePatterns": ["…"] }
```
创建 `status=draft` 的 revision，`rev = 当前最大 rev + 1`，**不改** `currentRevision`。返回该 draft（含分配的 `rev`）。

### 4.5 `POST /profiles/{id}/revisions/{rev}/publish` — 发布
draft → `published`，`Profile.currentRevision = rev`，`publishedAt` 置当前。触发 `/sync/events` 的 `release` 推送。返回更新后的 Profile。

### 4.6 `GET /profiles/{id}/revisions` — 版本历史（`manage`）
返回该 profile 的 revision 列表（`rev`、`status`、`note`、`createdAt`、`publishedAt`），供回滚/审计。回滚 = 以旧内容提交并发布新 rev。

## 5. device-code OAuth（无浏览器登录，`wrapResult=false`）

供 YMCL 在不弹浏览器、不暴露明文密码的前提下登录。端点实现在插件内，token 由插件自管（`secrets()` 存签名密钥），不改宿主核心。

### 5.1 `POST /device/code` — 申请设备码（匿名）
Body：`{ "clientId": "ymcl" }`。返回：
```json
{
  "deviceCode": "长随机串（客户端轮询用）",
  "userCode": "ABCD-1234（展示给用户）",
  "verificationUri": "https://节点/launcher/device",
  "verificationUriComplete": "https://节点/launcher/device?code=ABCD-1234",
  "interval": 5,
  "expiresIn": 600
}
```

### 5.2 用户在浏览器/站点确认
用户在 `verificationUri` 输入 `userCode` 并登录确认（复用站点登录态）。确认端点（`user` 权限）把 `deviceCode` 绑定到 `principal().userId()`。

### 5.3 `POST /device/token` — 轮询换 token（匿名）
Body：`{ "deviceCode": "…" }`。
- 未确认：`{ "error": "authorization_pending" }`（HTTP 200 或 400，YMCL 按 `error` 字段判定）。
- 过快：`{ "error": "slow_down" }`。
- 过期：`{ "error": "expired_token" }`。
- 成功：
```json
{ "accessToken": "…", "refreshToken": "…", "tokenType": "Bearer", "expiresIn": 86400, "userId": "…", "username": "…" }
```

## 6. 资源上传的两段式约定（重要）

SPI 不提供 multipart 解析。上传统一：
1. 前端/客户端先调**宿主平台**通用上传（前端 `sdk.files.uploadImage(file, { module: 'yudream-launcher', publicAccess: false })`，接受任意文件类型），拿回平台 `fileId`。
2. 把 `fileId` 作为 JSON 字段 POST 给插件写端点（如 4.3）。
3. 插件后端 `framework().platformFile(fileId)` 读字节，落入自己的 `files()` 存储。

YMCL 管理端（Rust 侧）走等价路径：先上传到宿主文件接口拿 `fileId`，再调 4.3。

## 7. YMCL 对账流程（客户端行为约定）

1. 绑定节点 + 选定 profile 后，`GET /info` 接线 authlib/skin。
2. 更新检测：`GET /profiles/{id}/head` 比对本地 `rev`/`etag`；变化则 `GET .../manifest`。
3. 首次安装：若 manifest 带 `mrpack`，用现成 modrinth import 建实例；否则按 entries 全量下载。
4. 增量对账：遍历 entries 按 `sha1` 比对本地文件 → 缺失/不符则下载（url 直连 / managed 走 3.5）→ 本地多出且**不匹配 `ignorePatterns`** 才提示清理。
5. 档位：`required` 强制，`recommended` 默认选中可取消，`optional` 默认不选。
6. `ignorePatterns` 保护玩家本地文件（Optifine、个人 mod、`options.txt`、`config/**` 等）。

## 8. 错误约定

对外端点（`wrapResult=false`）错误体统一：
```json
{ "message": "人类可读信息", "error": "机器可读码（可选）" }
```
配合正确 HTTP 状态码（400/401/403/404/409/500）。device-code 的 `error` 用 OAuth 标准码（`authorization_pending`/`slow_down`/`expired_token`/`access_denied`）。
