# YDA 下载网关适配契约 v1（ymcl-download-gateway）

> 状态：v1 定稿（2026-10-23），随 ymcl-adapter 0.12.0 实现。
> 适用对象：BMCLAPI 兼容镜像网关（阿里云 PDS 存储，`mirror/server.mjs` 一族）。
> 目标：把网关的静态 `mk_*` key 门控升级为 **YDA 玩家身份鉴权**，并把
> 下载统计回流 YDA 后台。

## 1. 角色与总流程

```
启动器                      YDA (ymcl-adapter)                 镜像网关 (BMCLAPI 兼容)
  │  POST /v1/download/token      │                                │
  │──────────────────────────────>│ 玩家身份取自域会话 principal      │
  │<──────────────────────────────│ { token, expires_at }           │
  │  GET {gateway}/mc/game/...?key=<token>                          │
  │─────────────────────────────────────────────────────────────────>│
  │                                │  POST /v1/download/token/verify │
  │                                │<────────────────────────────────│
  │                                │ { active, user_id, expires_at } │
  │<── 302 → OSS 直出 ──────────────────────────────────────────────│
  │                                │  POST /v1/download/stats/report │
  │                                │<────────────────────────────────│
  │                                │ 落明细 + 四集合聚合 → 管理端页面   │
```

- 启动器的域会话令牌**永不出站**；网关只见短效下载令牌。
- 网关不直连 PDS 之外的 YDA 数据，只调 verify/report 两个回调端点。
- 下载流量模型不变：网关 302 → OSS 直出，统计在网关 finish 时记账。

## 2. 启动器获取网关地址与顺序（分发配置）

`GET /api/plugins/ymcl-adapter/v1/download/config`（匿名可达，snake_case）：

```json
{
  "protocol_version": 1,
  "managed": true,
  "updated_at": 1761180000000,
  "gateway": { "name": "YDA 镜像", "base_url": "https://mirror.example.com", "auth": "token" },
  "official_fallback": true,
  "sources": [
    { "id": "gateway", "provider": "gateway", "name": "域网关", "base_url": "https://mirror.example.com" },
    { "id": "bmclapi", "provider": "bmclapi", "name": null, "base_url": null }
  ],
  "categories": {
    "minecraft_metadata": ["official", "bmclapi", "gateway"],
    "minecraft_files":    ["bmclapi", "gateway"],
    "loaders":            ["bmclapi", "gateway", "official"],
    "modrinth":           ["mcim", "tianpao", "official"],
    "curseforge":         ["mcim", "official"]
  }
}
```

- `provider` 词表：`official | bmclapi | mcim | tianpao | aliyun | gateway | custom`。
  `gateway`/`custom` 为 BMCLAPI 路由兼容基址（路径结构与 bmclapi2.bangbang93.com 一致）。
- 列表顺序即回退顺序；`official_fallback: true` 时官方源始终追加在链尾兜底。
- capabilities 宣告：`capabilities` 数组含 `download` 时，payload 带
  `download.config_url` 与 `download.token_url`。
- **分发激活期间源顺序由 YDA 统一下发，不受启动器本地下载源设置控制**；
  未分发的类别（启动器侧 java/other）回退本地设置。

## 3. 下载令牌（玩家身份鉴权）

### 3.1 签发（启动器 → YDA）

`POST {YDA}/api/plugins/ymcl-adapter/v1/download/token`（域会话鉴权，VIEW 权限）：

```json
{ "token": "dlt1.<userId>.<expMillis>.<hmac>", "expires_at": 1761201600000, "token_type": "download" }
```

- 默认有效期 6 小时；提前 10 分钟视为过期。
- 形态：`dlt1.` + base64url(userId) + `.` + base64url(expMillis) + `.` +
  HMAC-SHA256(tokenSecret, `userPart.expPart`)。签名密钥只在 YDA 侧。

### 3.2 携带（启动器 → 网关）

网关兼容的三种 key 通道中，启动器使用**查询参数**：`GET {gateway}/...?key=<token>`。
选择原因：下载引擎（H2 分段/native/XMCL 兼容）统一按 URL 构造请求，query 通道
无需逐引擎注入请求头；网关侧 `require_key` 现有逻辑即可承接。

### 3.3 校验（网关 → YDA）

`POST {YDA}/api/plugins/ymcl-adapter/v1/download/token/verify`

- 请求头：`X-YDA-Gateway-Secret: <gatewaySecret>`（管理端「下载分发 → 网关对接」页获取，可轮换）
- 请求体：`{"token": "dlt1...."}`
- 响应：`{"active": true, "user_id": "42", "expires_at": 1761201600000}`；
  无效令牌 `{"active": false}`。
- 网关应缓存校验结果（建议 ≤5 分钟），避免每个分片请求都回调。

> **数值字段为十进制字符串**：宿主全局把 Long 序列化为字符串（长 ID 防精度
> 约定），dev 实测 `expires_at`/`updated_at` 回传 `"1792802066442"` 而非数字，
> 统计查询里的 requests/bytes 等计数同样是字符串。消费方（启动器/网关/前端）
> 必须宽容解析两种形态；启动器侧已用 lenient deserializer 兜底并有契约测试
> 锁定（`parses_stringified_updated_at_from_live_host`）。

网关适配要点（对 `mirror/server.mjs`）：

1. `requireKey` 阶段：仍先匹配 `keys.json` 的 `mk_*`（兼容旧客户端）；不命中且
   key 形如 `dlt1.*` 时走 YDA verify 通道，`active:true` 放行并把 `userId`
   挂到请求上下文；`active:false` 返回 401。
2. 配置新增 `yda_verify_url`、`yda_gateway_secret`（env 可覆盖）。
3. verify 不可达时 fail-closed（拒绝 `dlt1.*` 令牌，返回 401），`mk_*` 行为不变。

## 4. 下载统计回传（网关 → YDA）

`POST {YDA}/api/plugins/ymcl-adapter/v1/download/stats/report`

- 请求头：`X-YDA-Gateway-Secret: <gatewaySecret>`
- 请求体（≤500 条/批，建议网关每 5–10 秒或满 100 条刷新）：

```json
{
  "token": "dlt1....",
  "events": [
    {
      "category": "minecraft_assets",
      "source": "gateway",
      "host": "mirror.example.com",
      "path": "/assets/aa/<sha1>",
      "bytes": 1048576,
      "status": 200,
      "occurredAt": 1761180123456
    }
  ]
}
```

- `token` 为该批事件对应的下载令牌；YDA **重新校验**并以解析出的 userId 归因，
  网关自报的任何 playerId 字段不被采信。
- `category` 建议取分发配置的类别词表（`minecraft_metadata` / `minecraft_files` /
  `loaders` / `modrinth` / `curseforge`），未分类用 `other`。
- `status` 为网关响应给客户端的状态码；2xx 计入「完成下载」。
- `occurredAt` 缺省/超前时 YDA 取服务器当前时间。

YDA 侧落库（`plugin_ymcl_adapter__*`）：

| 集合 | id | 内容 |
|---|---|---|
| `ymcl_download_events` | 零填充时间戳+序号 | 明细（保留 2 万条滚动清理） |
| `ymcl_download_daily` | `d:{yyyyMMdd}` | 当日请求/完成/字节/活跃玩家 |
| `ymcl_download_totals` | `all` / `c:{category}` / `s:{source}` | 累计与末次时间 |
| `ymcl_download_users` | `u:{userId}` | 玩家档案（请求/下载/字节/首末次） |

管理端页面：「YMCL 适配器 → 下载分发」（配置与网关对接信息、密钥轮换）、
「YMCL 适配器 → 下载统计」（摘要/趋势/最近动态/玩家档案）。

## 5. 密钥与轮换

- `gatewaySecret`：网关 ↔ YDA 共享，verify/report 鉴权用；管理端可一键轮换，
  轮换同时重置 `tokenSecret` → **全部未过期下载令牌立即失效**，网关需更新配置。
- `tokenSecret`：仅 YDA 侧签名用，不出站。
- 存储位置：文档存储 `ymcl_download_gateway` / `secret`（随插件集合隔离）。

## 6. 兼容与降级矩阵

| 场景 | 行为 |
|---|---|
| YDA 未配置分发 | capabilities 不宣告 `download`；config `managed:false`；启动器用本地设置 |
| 启动器无域会话 | 拿不到令牌；网关路由 401 后按链回退下一源（官方兜底仍在） |
| 网关不可达 / verify 挂 | 该路由失败 → 链内下一源；`official_fallback` 保证官方链尾兜底 |
| 旧版启动器（无 download face） | 忽略未知 capabilities 字段，行为完全不变 |
| 旧网关（仅 mk_*） | YDA 分发里不配 gateway 源即可，其余类别照常分发 |
