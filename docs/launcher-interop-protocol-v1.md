# 跨站启动器互操作协议 v1（mc-interop v1）

> 状态：**草案（设计定稿，未实现）**。本文件是站点对外的统一出口协议：整合包目录 +
> 服务器绑定清单 + 多站发现，供任意启动器与第三方站点消费（不限于 YDA / YMCL）。
>
> 与 `launcher-adapter-protocol-v2.md` 的关系：v2 是 YDA **站内**启动器契约（导航/页面/动作/分发），
> 本协议是**跨站**互操作契约。两者并存：统一出口在站点内部可由 launcher-adapter v2 或
> ymcl-adapter（YAP/MIP）作为分发与绑定后端承接，经适配层投影为本文档的中立模型。
> 本文件与 v2 文档平级、互不覆盖；冲突时站内行为以 v2 文档为准，跨站出口以本文档为准。

## 0. 目标与非目标

目标：

1. 每个站点只暴露**一个稳定统一出口**（well-known 端点），任何启动器据此获得：
   站点身份、整合包目录、服务器列表与整合包/版本绑定、友站发现。
2. **适配器架构**：入口侧适配多种整合包格式（mrpack / CurseForge zip / MultiMC / 原版 /
   遗留 schema），出口侧序列化**唯一**——v1 只出一种 canonical JSON。
3. **多站互通**：跨站聚合发生在**站点服务端**——本家站轮询友站清单、合并后经自己的
   统一出口下发给启动器；启动器只读本家一个 origin，不做多站客户端聚合。
4. **启动器侧对应协议**：发现、缓存、安装/更新、加入服务器的完整客户端行为规范。

非目标（v1 明确不做）：

- 不替代 YDA 站内 v2/YAP（页面、动作、认证、主题仍走站内契约）。
- **不含启动器自升级策略**。参考站 `skin.sitmc.club/api/launcher/manifest` 中的
  `launcher{min_version,latest_version,force_update}` 属于启动器应用自身的更新通道，
  与「哪个站点有哪些包、服务器要什么包」是两回事，继续留在各启动器自己的升级端点。
- 不做跨站账号互通、通用文件 CDN、支付/身份体系。

## 1. 总体架构

```text
                    ┌─ ingest 适配器（站点内部）────────────────┐
 mrpack 包 ───────►│ mrpack ingest → canonical Pack 元数据      │
 CF zip 包 ───────►│ cf-zip ingest  → canonical Pack 元数据      │──┐
 原版/serverjar ──►│ vanilla ingest → canonical Pack 元数据      │  │
 遗留 schema:1 ───►│ legacy import  → canonical（一次性迁移）    │  │
                    └────────────────────────────────────────────┘  │
                                                                    ▼
 服务器档案（mc-server 周目绑定、                ┌──────────────────────────┐
 发布控制台绑定、YAP serverBindings）──────────►│  统一出口（唯一序列化）    │
                                                │  /.well-known/           │
 分发后端（launcher-adapter v2 /                 │  mc-interop-v1.json      │
 ymcl-adapter MIP / 纯 URL 托管）──────────────►│  SiteDocument            │
                                                └──────────────────────────┘
                                                            ▲
 任意启动器（YMCL / 第三方）────────────────────────────────┘
  site 内部：canonical → native 适配器
```

- **入口适配**：整合包以什么格式上传/导入由站点内部决定，ingest 适配器把不同格式解析为
  canonical 元数据（gameVersion、loader、hash、size、artifact URL）。
- **出口唯一**：无论内部用哪套存储/分发系统，对外只有一份 SiteDocument，一种 wire 格式。
  「多站互通」的所有消费方只依赖这份文档，不再出现 sitmc schema:1、YAP MIP、v2 各说各话。
- **分发后端可插拔**：artifact 从哪下载、能否增量对账，由 `artifact.distribution` 声明，
  支持它的启动器走后端原生端点（如 v2 的 head→manifest→sha1 对账），其余走整包 URL。

## 2. 端点与 wire 约定

### 2.1 统一出口端点

```text
GET {origin}/.well-known/mc-interop-v1.json        → SiteDocument（200）
```

- `origin` 为站点 HTTPS 根；强制 HTTPS，http 请求方应自行升级。
- 路径**版本化**（`mc-interop-v1.json`）：未来破坏性变更发布 `mc-interop-v2.json` 并并存过渡，
  已发布路径永不改变语义。
- YDA 部署形态：协议入口挂插件分发路径
  `/api/plugins/mc-interop/.well-known/mc-interop-v1.json`——零反代、零网关配置，
  任何部署形态直接可达；宿主另在根路径 `/.well-known/mc-interop-v1.json` 提供同文档
  别名（进程内聚合，ETag/缓存）。非 YDA 站点用任意静态文件/网关实现均可——协议只约定
  路径与响应体。
- 文档较大时的扩展路径（v1 预留，不强制实现）：SiteDocument 中集合块可改放
  `{"href": ".../mc-interop-v1/packs"}` 拆分拉取，见 §3。

### 2.2 wire 约定（与 launcher-adapter v2 同基调）

- 响应为裸 JSON（无 `{code,data}` 信封）；错误用 HTTP 状态码 + `{"message": "..."}`。
- 字段命名 **camelCase**（站内 YAP/MIP 的 snake_case 属内部形态，出口一律转 camelCase）。
- 所有 Long/Snowflake ID 在 JSON 中一律为 `string`。
- 枚举值小写中划线（`stable`、`mc-interop`、`yda-launcher-v2`）。
- 时间戳一律 UTC ISO-8601（`2026-10-21T08:00:00Z`）。
- **容忍规则**：客户端必须忽略未知字段；服务端必须容忍缺省字段并给默认值。
  未知枚举值：集合类（packs/servers/peers）跳过该条并记诊断，标量字段按缺省处理。
- 缓存：响应应带 `ETag`（由 `updatedAt`/revision 派生）与
  `Cache-Control: public, max-age=300`；客户端条件请求 `If-None-Match` → `304`。
- 尺寸建议：SiteDocument ≤ 256 KiB；超限站点必须用 §3 的 `href` 拆分。

## 3. SiteDocument（发现文档）

```json
{
  "manifestVersion": "1",
  "protocol": {"name": "mc-interop", "features": ["packs", "servers", "peers"]},
  "site": {
    "id": "sitmc",
    "name": "SIT Minecraft",
    "description": "SIT MC 公共服务器与整合包",
    "icon": "https://skin.sitmc.club/favicon.ico",
    "origin": "https://skin.sitmc.club",
    "website": "https://skin.sitmc.club",
    "contact": "admin@sitmc.club"
  },
  "auth": {"type": "none"},
  "packs":   { "updatedAt": "…", "items": [ /* PackEntry，§4 */ ] },
  "servers": { "updatedAt": "…", "items": [ /* ServerEntry，§5 */ ] },
  "peers":   [ /* PeerEntry，§7 */ ],
  "mirrors": [ {"name": "mirror-1", "origin": "https://dl2.sitmc.club"} ],
  "updatedAt": "2026-10-21T08:00:00Z"
}
```

- `manifestVersion` 主版本是客户端选择解释器的唯一依据；同主版本内只允许增量加字段。
- `protocol.features` 声明站点实现的扩展面，v1 固定含 `packs`、`servers`；
  `peers` 仅在列表非空时必须出现。
- `auth.type` v1 只有 `none`（匿名可读）；预留 `bearer` 供未来门控内容，客户端遇到
  未实现的 auth 类型按公开内容处理并提示能力受限。
- 集合块（`packs`/`servers`）允许两种形态：内联 `{"items": [...]}`（默认）或
  `{"href": "/.well-known/mc-interop-v1/packs", "updatedAt": "…"}`（大站拆分）。
  `href` 为相对路径时**相对站点 origin** 解析。
- 出口中的 `packs`/`servers` 可包含本站互采合并的友站内容（§7），外来条目携带
  `source` 溯源字段、本家条目省略；`peers` 是给**其他站点**互采用的推荐列表，
  启动器可完全忽略。
- `updatedAt`（顶层与各集合块）变化即整块重拉；不做块内增量（整合包目录规模小，
  增量价值在对账分发，不在目录）。

## 4. PackEntry：整合包目录（多格式载体）

```json
{
  "id": "survival",
  "name": "生存服整合包",
  "summary": "第三周目生存服官方整合包",
  "icon": "https://skin.sitmc.club/icons/survival.png",
  "order": 1,
  "versions": [
    {
      "version": "12",
      "revision": 12,
      "channel": "stable",
      "gameVersion": "26.2",
      "loader": "fabric",
      "loaderVersion": "0.19.5",
      "artifact": {
        "format": "mrpack",
        "url": "https://skin.sitmc.club/api/launcher/packs/survival/12",
        "size": 511459395,
        "sha1": "b00e0021aada1321b81cbd9693782df39b5f67a0",
        "sha512": null,
        "distribution": {"system": "yda-launcher-v2", "packId": "survival", "versionId": "12"}
      },
      "flags": {"recommended": true, "required": false},
      "releaseNotes": "第三周目开服版本"
    }
  ]
}
```

字段规则：

| 字段 | 规则 |
| --- | --- |
| `id` | 站内稳定 ID（string）；**站点内唯一**，跨站全局键见 §7。 |
| `versions[].version` | 发布方语义版本或序号（string），仅展示与固定用；单调性以 `revision` 为准。 |
| `versions[].revision` | 单调递增整数，同包同 version 内容变化也必须 +1；是启动器更新检测基准。 |
| `channel` | `stable` \| `beta` \| `alpha`；缺省 `stable`。 |
| `gameVersion` | 必填；MC 版本字符串，原样透传不做猜测。 |
| `loader` | `vanilla` \| `fabric` \| `forge` \| `neoforge` \| `quilt`；**枚举外值禁止出站**（fail-closed，见 §6.4 遗留导入）。`vanilla` 时 `loaderVersion` 省略。 |
| `artifact.format` | `mrpack` \| `multimc` \| `curseforge-zip` \| `server-zip` \| `raw`。声明**artifact 容器格式**，ingest 适配器清单见 §6.1。 |
| `artifact.url` | 必填、绝对 HTTPS URL；跨站可用（消费方不一定在该站 origin 上）。 |
| `artifact.sha1/sha512/size` | size 字节；`sha512` 建议必给，`sha1` 为兼容保留；两者同时存在时以 `sha512` 为准。 |
| `artifact.distribution` | 可选。声明可增量对账的分发后端，当前登记：`yda-launcher-v2`（launcher-adapter `/v2/packs/**` head→manifest→files 对账）、`yap-mip`（ymcl-adapter CAS）。不支持的启动器忽略它、整包装。 |
| `flags.required` | `true` 表示该服必装（对应遗留 schema `instances[].required`）；`false` 仅为推荐目录项。 |
| `flags.recommended` | 未固定版本时默认安装/更新的版本。 |

- 目录**只含包级/版本级元数据**，不含文件级 entry 清单——文件级细节在 artifact 内部
  （mrpack index 等）或分发后端端点里，出口保持轻量。
- 服务器绑定引用包用 `{packId, version?}`（§5）；`version` 省略 = 跟随该包
  `flags.recommended` 版本的最新 `stable` revision。

## 5. ServerEntry：服务器与绑定清单

```json
{
  "id": "srv-survival",
  "name": "生存服",
  "address": "play.sitmc.club",
  "port": 25565,
  "endpoints": [
    {"name": "电信", "address": "tel.sitmc.club", "port": 25565, "primary": true}
  ],
  "status": {"online": true, "players": 12, "max": 100, "motd": "…", "favicon": "data:image/png;base64,…"},
  "currentSeason": {"id": "s3", "name": "第三周目"},
  "binding": {
    "kind": "pack",
    "packRef": {"packId": "survival", "version": "12"},
    "updatePolicy": "prompt",
    "optionalPackRef": {"packId": "pixelsit"},
    "authority": "console",
    "conflict": false,
    "sources": [{"authority": "console", "packRef": {"packId": "survival", "version": "12"}}]
  }
}
```

- `id` 站内唯一即可；`address`+`port` 为玩家可见主地址。`port` 省略 = 不指定
  （由 SRV 记录或客户端默认处理，**禁止**服务端擅自补 25565——遗留源里
  `port: null` 必须保持省略）。`endpoints[]` 为多线路，`primary: true` 至多一条。
- `status` 为可选实时快照（出口聚合时注入，缓存 TTL 由站点自定）；启动器不得把它当
  权威在线判据，连接以 ping 为准。
- `binding` 三种形态（对齐现有 `ModpackBinding` / YAP 附录 B，语义收编为两种 + 无绑定）：

  | 形态 | 内容 | 语义 |
  | --- | --- | --- |
  | 省略 `binding` | — | 无绑定（NONE）：启动器回落本地实例匹配，不提示安装。 |
  | `kind: "pack"` | `packRef` 必填 | 进服需要安装指定整合包（原 MRPACK 形态）。 |
  | `kind: "vanilla"` | `gameVersion` + `loader` 必填 | 仅要求游戏版本/加载器（原 VANILLA 形态），可用本地实例或原版安装进服。 |

  非法组合（`pack` 缺 `packRef`、`vanilla` 带 `packRef` 等）为站点实现错误，客户端按
  无绑定处理并上报诊断。
- `updatePolicy`：`prompt`（进服前提示，默认）| `background`（静默更新）| `pinned`
  （必须 `packRef.version` 显式固定，禁止自动升级）。
- `optionalPackRef`：可选增强包（玩家可关，YAP §7 三选一的收编）；仅 `kind: "pack"` 下有效。
- **双源聚合与冲突**（收编现状：mc-server 周目绑定与 ymcl 发布控制台绑定互不感知）：
  出口聚合两个来源，`sources[]` 保留全部原始值，`authority` 标注选中来源
  （`season`（mc-server 周目）| `console`（ymcl 发布控制台））；
  默认优先级 **season > console**（周目绑定与实际开服内容同处管理，站点可配置反转）；
  两者内容不一致时 `conflict: true`，由管理面告警，**出口永远只下发选中值**，
  客户端不感知多源细节（`sources` 仅供诊断，消费方可忽略）。

## 6. 适配器架构

### 6.1 ingest 适配器（包格式 → canonical 元数据）

| 适配器 | 输入 | 解析产出 | 备注 |
| --- | --- | --- | --- |
| `mrpack` | Modrinth `.mrpack`（`modrinth.index.json`） | `gameVersion`、`loader`/`loaderVersion`（dependencies）、hash（sha1/sha512，与官方 files 字段同名同义）、size | 首选格式；文件级 entry 留在包内，不物化进目录。 |
| `curseforge-zip` | CF 导出 zip | 同上（解析其 manifest） | 平台托管文件需在发布时解析为直链或托管副本；**再分发许可由站点发布方自证**，协议不背书。 |
| `multimc` | MultiMC 实例目录打包 | `instance.cfg` + `mmc-pack.json` → 版本/加载器 | `format: "multimc"`。 |
| `vanilla` | 手填版本 + 可选 overrides.zip | `gameVersion`/`loader`（可 `vanilla`） | 对应 VANILLA channel；overrides 走分发后端对账或 `server-zip` 整包。 |
| `legacy-sitmc-1` | 遗留 `schema:1` manifest | 一次性导入为 canonical（§6.4） | 迁移完成后停用。 |

通用规则：适配器**只产出元数据与 artifact 引用，不改变包体**；解析失败必须整版本拒绝
（fail-closed），不得产出半填充的 canonical 条目；loader/枚举校验在 ingest 完成后出站数据
保证合法。

### 6.2 分发后端适配（artifact 从哪来、怎么更新）

- `yda-launcher-v2`：`distribution.packId/versionId` 指向 launcher-adapter 的 pack 体系，
  支持 head/etag 检测 + entries 对账增量更新（v2 §5.4 算法原样复用）。
- `yap-mip`：`distribution` 指向 ymcl-adapter `ymcl_packs`（CAS sha512 寻址、mirror 加速）。
- 无 `distribution`：纯 URL 整包安装，`sha512/sha1` 校验通过即完成，无增量。
- 两套 YDA pack 目录的 `packId` 命名空间不互通（现状风险），canonical 层用
  `distribution.system` 隔离命名空间，禁止跨系统裸引用 packId。

### 6.3 出口序列化器（唯一）

站点内部任意存储 → 单一 SiteDocument。序列化器职责：camelCase 投影、ID 字符串化、
枚举白名单校验、绝对 URL 化（相对路径一律按站点 origin 补全）、双源绑定聚合（§5）、
ETag/updatedAt 生成。**出口不允许按请求方切换格式**（那会重新碎片化）；
未来新格式 = 新 major 路径，不做运行时协商。

### 6.4 启动器侧适配器（canonical → native）

每个启动器实现一个 canonical 消费适配器：

- **YMCL**：SiteDocument → 站内 v2 模型映射（§9.1），目录页用既有 `pack-catalog`
  renderer，安装走既有分发管线；加入服务器动作映射 `client:launch-server`。
- **通用第三方启动器**：直接按本文档实现发现/目录/绑定三步即可，无 YDA 依赖。
- 启动器本地声明自己支持的 `artifact.format` 集合；目录中不支持的 format 条目
  显示但禁用安装，不算协议违规。

## 7. 多站互通（站点侧互采聚合）

跨站聚合发生在**站点服务端**，启动器不参与：启动器只认一个本家 origin，本家站后台
轮询友站清单、合并进自己的统一出口。角色只有两种，且可兼任（A 站采 B 站的同时被 C 站采）：

- **源站**：被别人采的站。只需按本协议发布 well-known 清单，无需任何"被聚合"配置。
- **本家（聚合站）**：启动器配置的站点。后台维护友站 allowlist，定时拉取各友站
  SiteDocument 并合并为自己的出口视图。

### 7.1 PeerEntry（本家声明自己采谁 / 推荐谁）

```json
{"siteId": "yda", "name": "余梦", "origin": "https://mc.yudream.app",
 "manifestUrl": "https://mc.yudream.app/.well-known/mc-interop-v1.json",
 "trust": "linked"}
```

- `trust`：`linked`（管理员手工添加）| `verified`（域名/签名校验通过，v1 仅约定字段，
  校验机制后续版本定义）。
- `peers[]` 的消费方是**其他站点**（互采发现用），启动器可完全忽略该字段。

### 7.2 互采与合并规则

- **拉取**：条件请求（`If-None-Match`）+ 15s 超时 + 3 次退避重试；友站失败时沿用最近
  一次成功快照继续服务，并把该来源条目的 `source.stale: true` 置位，**永不静默清空**
  已采入内容；友站 `manifestVersion` 主版本升级时暂停采入并告警，等本站适配。
- **溯源**：合并视图中每条外来 PackEntry/ServerEntry 携带
  `source: {"siteId": "...", "origin": "..."}`（快照过期时加 `"stale": true`）；
  本家自有条目省略 `source`。前端可据此分组展示"友站内容"。
- **命名空间**：本站内部资源键 = `origin + "/" + packId` / `origin + "/" + serverId`，
  跨站同名 id 不冲突；对外出口仍是一个扁平 `packs[]`/`servers[]`，消费方无感知。
- **绑定重写**：采入友站服务器时，其 `binding.packRef` 必须重写指向合并目录中的对应包
  （友站的包必须一同采入）；友站 binding 引用了未采入的包时丢弃该 `binding`
  （服务器条目保留、按无绑定下发）并记诊断。
- **门控**：本家可对合并视图整体启用 `auth`（§3），对友站内容同样生效——启动器只需
  理解本家一种 auth，友站门控与本家用户体系在服务端解耦。
- **本地屏蔽（站点本地策略）**：本家可对单个友站设置屏蔽——声明保留（`peers[]` 仍列出），
  但互采不再拉取、其已采入条目从合并视图移除；屏蔽是站点自治行为，不改协议形状。
- **artifact 不代理**：外来条目的 `artifact.url` 保持指向源站绝对 URL，下载流量不经
  本家转发（除非管理员显式把它们加进 `mirrors`）；完整性始终以 hash 校验为准。
- **镜像**：`mirrors[].origin` 是本站 artifact 的镜像源；启动器可按延迟择优下载，
  校验以 hash 为准（镜像不可信不影响完整性）。

### 7.3 站点添加深链

`mcinterop://add-site?url={origin}`（编码后的站点 origin 或 manifestUrl）语义是
"把该站**添加/切换为本家站点**"：启动器维护的是本家 origin 列表（供切换），不是聚合源
列表。启动器必须同时兼容遗留 `ymcl://add-site?url=`。

## 8. 启动器侧行为规范

1. **单源原则与发现**：启动器**只访问本家 origin**，不实现多站客户端聚合（`peers`
   字段可忽略；多站切换 = 本家站点列表切换后重新发现）。用户给 origin（或深链）→
   `GET /.well-known/mc-interop-v1.json`。超时 15s、响应上限 1 MiB（含拆分块）、
   失败重试 3 次退避；发现失败给出明确错误（DNS/TLS/404/格式），不得静默降级为空目录。
2. **缓存**：持久化最近 SiteDocument + ETag；启动时与每 6 小时条件请求刷新；
   `manifestVersion` 主版本变化视为新站点协议，弹窗确认后重解释。
3. **目录页**：packs 按 `order` 渲染；每包展示 recommended stable 版本；历史版本可展开。
4. **加入服务器（join 流程）**：
   - 无 `binding` → 直接启动/让用户选本地实例；
   - `kind: "pack"` → 检查目标包本地安装态：未装按 `updatePolicy` 安装
     （有 `distribution` 且支持 → 走对账增量；否则整包下载 + hash 校验）；
     已装 → `revision` 落后时按 `updatePolicy`（`pinned` 不动、`prompt` 询问、
     `background` 静默）更新后启动；
   - `kind: "vanilla"` → 按 `gameVersion+loader(+loaderVersion)` 匹配/创建本地实例；
   - `optionalPackRef` → 用户可选安装，不阻塞启动。
5. **多线路**：`endpoints[]` 优先 `primary`，失败依次降级；复制地址默认复制主地址。
6. **诊断与降级**：未知字段忽略；未知枚举（channel/loader/format/updatePolicy）按 §2.2
   容忍规则处理并记本地诊断日志；`conflict: true` 的服务器按 `binding` 下发值执行
   （冲突是站点内部事务）。

## 9. 与现有系统的对接与迁移

### 9.1 canonical ↔ launcher-adapter v2 字段映射

| canonical | v2 对应 | 说明 |
| --- | --- | --- |
| `site` | `domain` | 出口用 `site.id`，站内仍用 `pluginCode`。 |
| `packs[]` | `LauncherPack`/`PackVersion`（MRPACK+VANILLA） | `revision` 同义；`channel` 大小写归一。 |
| `artifact.distribution.system="yda-launcher-v2"` | `/v2/packs/{packId}/versions/{versionId}/**` | 支持 v2 的启动器走原对账。 |
| `servers[].binding` | `ModpackBinding`（NONE/VANILLA/MRPACK） | `kind: pack`↔MRPACK、`kind: vanilla`↔VANILLA。 |
| `client:launch-server` 等动作 | v2 §4.3 动作表 | 语义不变，出口不承载动作（目录协议无交互动作）。 |

### 9.2 canonical ↔ YAP/MIP 映射（ymcl-adapter）

| canonical | YAP/MIP 对应 |
| --- | --- |
| `servers[]` | `/mip/api/servers` 聚合视图（serverId/name/mcAddress/endpoints/currentSeason）。 |
| `binding.packRef` | `binding{packId,pinnedVersion,channel}`；`updatePolicy` 直传（`launch-only` 归一为 `prompt`）。 |
| `packs[]` | `ymcl_packs`/`ymcl_pack_versions`（CAS sha512 → `artifact.sha512`）。 |
| 认证 | MIP 面需 `plugin:ymcl-adapter:view`；**统一出口匿名可读**，门控内容 v1 不出口。 |

### 9.3 mc-server 周目绑定投影

`MinecraftServerSeason.modpackBinding`（NONE/VANILLA/MRPACK）按 §5 规则投影为
`binding`（`authority: "season"`），与发布控制台绑定做双源聚合；`serverBindings()`
provider 无需改签名，出口聚合器在 provider 之外补齐 modpackBinding 读取
（收编现状缺口：MIP 清单目前只反映控制台绑定）。

### 9.4 遗留 sitmc schema:1 迁移（一次性）

- `instances[]` → PackEntry + ServerEntry：`pack.kind` → `artifact.format`；
  `pack.url/sha1/size` → artifact；`minecraft.*` → 版本三元组；`server.address/port` →
  ServerEntry；`required` → `flags.required`；`revision` → `revision`。
- `launcher{...}` 块**不迁移**（启动器自升级，见 §0 非目标）。
- 数据修正规则：`loader: "fabirc"` 等非法枚举**导入即拒绝该版本**并告警，修源后重导；
  `port: null` → 省略字段；`sort` 重复值 → 以 `(sort,id)` 稳定排序。
- 迁移完成后源 manifest 保留只读一个观察期，然后 301/下线。

### 9.5 落地顺序

1. 定稿本规范 → 2. 出口序列化器 + 双源绑定聚合（launcher-adapter 新增 interop 读端点，
   宿主原生 well-known 聚合端点）→ 3. legacy-sitmc-1 ingest 导入 → 4. YMCL canonical 适配器
   （目录/绑定/join）→ 5. 站点侧互采聚合（peers 拉取/合并/溯源）与深链。

## 10. 安全

- 强制 HTTPS；出口匿名可读，不放置任何 secret/token/内网地址。
- artifact 必须带 hash（建议 sha512）；启动器下载后先验 hash 再落盘，原子替换。
- ingest 适配器解析外来包时按不可信输入处理（zip 路径穿越、超大小 entry、解压炸弹
  防护）；artifact URL 只允许 https 且禁止指向站点内网段（SSRF 防护在发布侧校验）。
- `curseforge-zip` 等第三方格式再分发许可由站点发布方负责，协议层只做技术适配。
- `status.favicon` 等内联数据设上限（如 64 KiB），防止出口膨胀。

## 附录 A：字段速查

- SiteDocument：`manifestVersion` / `protocol{name,features[]}` / `site{id,name,description,icon,origin,website,contact}` / `auth{type}` / `packs` / `servers` / `peers[]` / `mirrors[]` / `updatedAt`
- PackEntry：`id` / `name` / `summary` / `icon` / `order` / `source?{siteId,origin,stale}` / `versions[]{version,revision,channel,gameVersion,loader,loaderVersion,artifact{format,url,size,sha1,sha512,distribution{system,packId,versionId}},flags{recommended,required},releaseNotes}`
- ServerEntry：`id` / `name` / `address` / `port` / `source?{siteId,origin,stale}` / `endpoints[]{name,address,port,primary}` / `status{online,players,max,motd,favicon}` / `currentSeason{id,name}` / `binding{kind,packRef{packId,version},gameVersion,loader,loaderVersion,updatePolicy,optionalPackRef,authority,conflict,sources[]}`
- PeerEntry：`siteId` / `name` / `origin` / `manifestUrl` / `trust`
