# YD 联合认证协议 v1（yudream-union v1）

> 状态：**实现中（与代码同工作项落地）**。本文件是 YD 联合认证（去中心化、纯点对点）的
> 唯一权威契约，供 YDA 插件（yudream-union / authlib-injector）与**任何第三方技术栈站点**
> 对等实现。与本仓 `launcher-adapter-protocol-v2.md`、`mc-interop` 清单协议无关、互不依赖。
>
> 设计基线：启动器与 Minecraft 服务器**零改造**；玩家密码不出本站；信任注册采用
> 一次性 enrollToken + TOFU 固定公钥；跨站封禁按规范化角色名全局匹配。

## 0. 角色与信任模型（纯点对点）

```text
[玩家启动器]──Yggdrasil──▶[B 站 authlib 会话站]      （玩家照常在本站认证，不变）
                                │ ①join 记录（本地）
[MC 服务器]──hasJoined──▶[A 站会话站]                 （服务器只认 A 的 API root）
                                │ ②本地未命中 → 向受信站并发查询（≤8 站、2s 超时）
                                │ ③B 返回签名断言 → A 用 TOFU 公钥验签
                                │ ④A 用 A 的站点密钥重签 textures 属性返回
```

- **信任单向**：「A 信任 B」只意味着 A 会在 hasJoined 未命中时向 B 查询并接受其断言。
  B 无需配置 A；双向互通 = 两站各自注册对方。
- **信任注册**：A 管理员签发一次性 enrollToken（5 分钟、SHA-256 落库、明文只显示一次），
  B 站管理员持 token 向 A 提交自己的 origin；A 拉取 B 发现文档，TOFU 固定 B 的
  TLS 证书指纹与联合签名公钥。
- **无中心根**：没有联盟根节点，没有全局发号；每站自治决定信任谁。

## 1. 身份模型（两层，禁止混用）

| 层 | 键 | 说明 |
| --- | --- | --- |
| 联合身份 | `issuerKeyId + ":" + subjectId` | issuerKeyId = 联合签名公钥 SHA-256 前 16 字节 hex；subjectId = 签发站内部用户主键（string）。跨站唯一、改名不变。 |
| Profile UUID | Minecraft 角色 UUID | 游戏层身份。正版角色 = 官方 UUID 原样；离线角色 = 先生成随机、不可变的 `offlineIdentityId`（128 位 hex 联合角色 ID），再用 UUIDv5 从 issuerKeyId+subjectId+offlineIdentityId 派生。输入**不含角色名**，改名不变、删后重建同名角色也不会复用 UUID；创建时仍强制检查文档库 UUID 冲突。断言只传递 Profile UUID 与角色名，联合身份不进 Yggdrasil wire。 |

角色名在协议中一律先**规范化**再比较：trim、转小写、剥离 `§` 颜色码。

## 2. wire 约定

- 端点前缀：YDA 部署为 `{origin}/api/plugins/yudream-union`；第三方栈可用任意路径，
  但必须在注册时按约定提供 `GET {origin}/api/plugins/yudream-union/peer/meta`，或由双方
  以 out-of-band 配置确定等价 API 根。消费方收到的 `apiRoot` 始终是**前缀根**，
  后续端点在其后拼接 `/peer/...`，不得把 `/peer/meta` 本身当作 apiRoot。
- 响应裸 JSON（无 `{code,data}` 信封）；错误 = HTTP 状态码 + `{"error", "errorMessage"}`。
- 枚举小写中划线；时间戳 epoch 毫秒（Long）；所有 ID 一律 string。
- 客户端必须容忍未知字段；服务端必须容忍缺省字段。
- 强制 HTTPS；每端点限流（建议 ≥60s/30 次/来源站）；响应带 `X-Union-Version: 1`。

## 3. 端点

### 3.1 GET /peer/meta —— 发现文档（匿名 + 限流）

```json
{
  "unionVersion": "1",
  "site": {"code": "sitmc", "name": "SIT MC", "origin": "https://skin.sitmc.club"},
  "endpoints": {"apiRoot": "https://skin.sitmc.club/api/plugins/yudream-union"},
  "authlib": {"apiRoot": "https://skin.sitmc.club/api/plugins/authlib-injector"},
  "textureBaseUrl": "https://skin.sitmc.club/api/plugins/yudream-skin/textures",
  "signaturePublickey": "-----BEGIN PUBLIC KEY-----\n…\n-----END PUBLIC KEY-----\n",
  "backupSignaturePublickey": null,
  "features": ["join-assertions", "ban-ledger"],
  "bans": {"cursor": "42"},
  "enrollEnabled": true
}
```

- `signaturePublickey`：联合断言签名公钥（RSA，PEM）。与 Yggdrasil 元数据
  `signaturePublickey`（材质属性签名密钥）**是两把不同的密钥**，语义不得混用。
- 轮换：新钥先上 `backupSignaturePublickey`，观察期后升主钥；验签方在主钥失败时
  尝试备钥，旧主钥保留 7 天宽限期。
- `textureBaseUrl` 的 host 必须与 `authlib.apiRoot` 同源或被消费方明确加入白名单。

### 3.2 GET /peer/joins/query?username=&serverId=&aud= —— 联合 join 断言查询（匿名 + 限流）

调用方（A 站会话站）在本地 join 未命中时，向每个受信站查询。参数均必填；
`aud` = A 站 siteCode（断言受众绑定）。

命中（200）：

```json
{
  "assertion": {
    "profileId": "2536a169d63d54b3b19c1ee02d1fdfc5",
    "profileName": "Notch",
    "serverId": "-7c9d5b0044c130109a5d7b5fb5c317c02b4e28c1",
    "aud": "yda",
    "issuerKeyId": "9f2a7c3e1b8d4f60",
    "jti": "b3c1d9e84f7a2c50d7e1f4a9c6b8e2d0",
    "issuedAt": 1761030000000,
    "expiresAt": 1761030060000,
    "textures": "<base64(材质属性 value，原样来自本站 profile 响应)>"
  },
  "signature": "<base64(SHA256withRSA(规范化 JSON(assertion)))>"
}
```

未命中返回 **204**（与 Yggdrasil hasJoined 语义一致，不是 404）。

规则：

- 被查询站（B）只在**最近 60 秒且未过期**的本地 join 记录中按 `username`（不区分大小写）+
  `serverId` 精确匹配；命中即生成断言（`expiresAt` ≤ 签发后 60s），`textures` 取本站该角色
  profile 响应的 textures 属性 value 原文。
- **断言签发前必须自查联合封禁**：规范化角色名命中 ACTIVE 封禁 → 一律 204（不签发、
  不泄露封禁状态）。
- `signature` 对 assertion 的**规范化 JSON**做 SHA256withRSA：发送/验收均须只保留
  `profileId, profileName, serverId, aud, issuerKeyId, jti, issuedAt, expiresAt, textures` 这九个字段，
  按此固定顺序写为紧凑 UTF-8 JSON；缺任一字段或试图解释额外字段均拒绝。
- 消费方（A）验签规则：签名公钥 = 注册时 TOFU 固定的 B 主钥（失败再试备钥），
  且 `issuerKeyId` 必须等于**实际验签成功那把公钥**的 SHA-256 前 16 字节 hex；`aud` ==
  本站 siteCode；`issuedAt` 不得晚于验收方时钟 5 秒，`expiresAt-issuedAt` 不得超过 65 秒；
  `profileId` 必须为 32 位 hex、角色名必须为 3–16 位 `[A-Za-z0-9_]`；解码后的 `textures`
  中 SKIN/CAPE URL 只能使用 B 已注册 `textureBaseUrl` 的 HTTPS host；`jti` 为 32 位 hex，
  60 秒内重复响应丢弃。
- **多站同时命中**：A 站丢弃全部结果、返回 204 并告警（防跨站撞名身份混淆）。
  受信站越多越保守；站点应避免同时信任角色名空间重叠的站。

### 3.3 GET /peer/bans?since= —— 联合封禁账本（匿名 + 限流）

```json
{"cursor": "42", "bans": [
  {"name": "griefer_x", "reason": "…", "sourceSite": "sitmc",
   "createdAt": 1761000000000, "expiresAt": null, "status": "active", "seq": "41"}
]}
```

- `since` 传消费方已见过的最大 `cursor`（十进制字符串；省略 = `"0"`）；响应只含 `seq > since`
  的 append-only 事件（含 LIFTED），最多 500 条。`cursor` 只能推进到**本次实际交付的最后一条**，
  无条目时原样回显 since，消费方不得跳过未交付记录。
- 键 = 规范化角色名；条目只含 name/reason/sourceSite/时间，**不含邮箱、证据或操作人**等个人/审计信息。
- 合并策略：消费方按「各**直接受信站** ACTIVE 名单并集」生效；`lifted` 仅解除原封禁站的
  贡献，本站管理员另有独立封禁权。站点只导出自己签发的本地裁决，**不得转发**从别站拉取的
  条目，避免点对点信任被隐式扩展为传递信任。

### 3.4 POST /peer/bans/report —— 封禁主动推送（可选）

- 鉴权：`Authorization: Bearer <peerSecret>`，peerSecret 在 enroll 响应中一次性下发、
  落库 SHA-256。body 为 §3.3 单条 ban 形状。
- 拉取是主通道；推送只是降低同步延迟，消费方可忽略本能力（`features` 不声明即无）。

### 3.5 POST /peer/enroll —— 信任注册（enrollToken 门禁）

```json
{"enrollToken": "…", "origin": "https://skin.sitmc.club", "note": "友站互联"}
```

- 注册方（A）校验：限流（60s/30 次）→ token 存在且未过期 → 拉取 `origin` 的发现文档
  （3s 超时）→ 校验 `unionVersion`、siteCode/origin、同源 API/纹理 URL、必需能力和 PEM 公钥
  → TOFU 固定 TLS 证书指纹（SHA-256）与联合签名公钥 → **CAS 一次性消费 token** → 落库 TRUSTED。
- 响应：`{"status": "trusted", "peerSecret": "…（仅本次返回，用于 §3.4 推送）"}`。
- 失败码：401 `auth.badToken`（不区分无效/过期，防枚举）、409 `auth.tokenConsumed`、
  429 `auth.rateLimited`、400 `invalid-request`、502 `peer-unreachable`。
- payload 上限：enrollToken ≤ 512、origin ≤ 255（必须 https URL）、note ≤ 255。

## 4. 安全要求（实现方必读）

1. **SSRF**：拉取发现文档/断言查询仅允许 https；解析目标主机后拒绝环回、私网
   （RFC 1918/4193）、链路本地地址；出站客户端可按站点固定 TLS 证书指纹。
2. **重放**：断言 TTL ≤ 60s + 32 位 hex jti 去重；原始 Yggdrasil join 记录 TTL ≤ 60 秒，
   联合断言只允许使用最近 60 秒内签发的记录。
3. **撞名**：hasJoined 聚合必须唯一命中；命中即拒 + 告警（§3.2）。
4. **密钥**：联合签名密钥 RSA-4096，私钥进加密/秘密存储（禁止明文落普通文档库）；
   轮换走主/备双钥宽限（§3.1）。
5. **降级**：受信站不可达 = 该站断言不可用，不影响本站登录与本站玩家；
   联合层整体故障必须退化为纯本站行为（fail-open 到本地语义，不阻塞进服）。
6. **审计**：enroll、信任启停、封禁增解、多站冲突告警全部落审计。管理面按时间倒序提供关键词检索、单条删除与全量清空；审计默认保留 30 天，跨站登录事件默认保留 90 天，站点可配置保留天数，填 `0` 表示永久保留。清理任务启动后 60 秒首跑，之后每日执行一次。

## 5. YDA 实现映射（本仓）

| 协议件 | 实现 |
| --- | --- |
| 发现/断言/账本端点 | `yudream-plugin-yudream-union` `UnionPeerController` |
| B 侧 join 证据 | authlib-injector 经 union api 扩展点 `UnionJoinEvidenceProvider` 回供（join 库归 authlib 所有） |
| A 侧验签接入 | authlib-injector `hasJoined` 未命中 → `UnionFederationService.verifyRemoteJoin`；命中后用**本站 authlib 密钥重签** textures，省略 `uploadableTextures` |
| skinDomains 合并 | authlib 元数据 `skinDomains` += `UnionFederationService.trustedTextureHosts()` |
| 离线 UUID | skin 插件 UUIDv5 派生（§1），创建时强制 UUID 冲突检查；离线身份另持久化 `offlineIdentityId`，不随角色名变化。 |
| 正版绑定 | skin 插件 `official-binds` 聚合（Microsoft OAuth + PKCE + `offline_access` 自动续签，禁手填 UUID）；`POST /api/plugins/yudream-skin/me/official-bind/session` 以已认证主体换取 `accountKind:microsoft-java`、`minecraftAccessToken`、官方 profile，响应 `Cache-Control:no-store`，仅供具备官方 Microsoft 登录支持的原生启动器安全存储后走原版会话服务，可进入纯正版在线模式服。 |
| 依赖方向 | `authlib-injector` softdepend `yudream-union`（provided 编译其 api）；union 不依赖任何业务插件，无环 |
