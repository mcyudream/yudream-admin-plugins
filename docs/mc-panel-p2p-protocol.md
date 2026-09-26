# 启动器 P2P 直连协议（M6 · 面板 ⇄ 节点 ⇄ 启动器）

设计背景见 `mc-panel-design.md` §5.9。本文是**实现契约**：面板即 rendezvous，启动器（或其 sidecar）
与节点在拿到票据后**直接建流**，玩家流量不经面板。

```
启动器 sidecar ──①开会话(HTTPS, OAuth)──▶ 面板 ──②p2p.session.open(WSS)──▶ 节点
      │                                   │                                │
      └──③中继 ICE 候选(可选, HTTPS)────────┘── p2p.signal(WSS) ─────────────┘
      │
      └──④带票据握手(TCP, 直连节点)────────────────────────────────────────▶ 节点 ──▶ 实例端口
```

## 1. 面板端点（玩家面，`/api/plugins/mcpanel/me/p2p/**`）

鉴权：宿主 OAuth 登录态（`/me/**` 规则，principal.userId 即玩家身份）。

### `POST /me/p2p/session` — 开会话

请求：`{"instanceId": "<实例 ID>"}`

响应（成功）：
```json
{
  "sessionId": "…", "instanceId": "…", "nodeId": "…",
  "state": "waiting", "expiresAt": 1790000000000, "rateKbps": 2048,
  "targetPort": 25600,
  "ticket": "…32 字节 base64url，仅此一次下发…",
  "nodeCandidates": []
}
```
错误（HTTP 4xx/5xx + 机器码）：`p2p.disabled` 全局未开启 / `p2p.instance-disabled` 实例未开 /
`p2p.not-allowed` 不在白名单 / `p2p.not-running` 实例未运行 / `p2p.no-port` 无 TCP 端口 /
`p2p.limit` 并发超限 / `p2p.busy` 会话池满 / `p2p.node-unreachable` 节点不可达。

节点未实现 `p2p.*` 能力时返回 `{"capabilityGap": {"nodeCapability": "unavailable", "message": "…"}}`，
**不伪造成功**（启动器据此提示"该节点暂不支持 P2P"）。

### `POST /me/p2p/session/signal` — 中继 ICE 候选

请求：`{"sessionId": "…", "candidates": [{"proto": "udp", "host": "198.51.100.7", "port": 52100}]}`
响应：会话视图 + `nodeCandidates`（节点侧候选，主机为空时由面板按节点对外地址补齐）。

打洞路径双方必须互见候选；MVP 的 TCP 直连只用到 `nodeCandidates`。

### `GET /me/p2p/session?sessionId=…` — 状态轮询

响应：`{sessionId, state, nodeCandidates, bytesSent, bytesReceived, reason?}`；
`state ∈ waiting | connecting | direct | relayed | failed | closed | expired`。
节点回报的状态经面板状态机落定（启动器 1-2 秒轮询一次即可，无需 SSE）。

### `DELETE /me/p2p/session?sessionId=…&reason=…` — 关闭

通知节点回收监听与连接；幂等。

### 管理端（管理员治理）

- `GET /admin/p2p/sessions`（VIEW）：活跃会话（不含票据明文）。
- `DELETE /admin/p2p/sessions/{sessionId}?reason=…`（MANAGE）：强制断开并吊销票据。

## 2. 节点握手（启动器 → 节点，TCP 直连）

对每个 `nodeCandidates` 候选依次尝试（`proto: "tcp"`）：

```
→ "MCP2P1 <sessionId> <ticket>\n"      // 单行，≤512 字节，5 秒内必须发出
← "OK\n"                               // 通过：之后即为原始字节流（双工）
← "ERR bad_handshake\n"                // 格式/magic 不对
← "ERR unauthorized\n"                  // sessionId/ticket 不匹配（不区分是哪一个错）
← "ERR busy\n"                         // 该会话连接数超上限
← "ERR upstream_unreachable\n"         // 实例端口不可达（实例没在跑/端口未就绪）
```

**目的端钉死（设计红线）**：启动器**不能**指定任何目标地址——节点只把流量送进票据绑定的
`targetPort`（面板分配的宿主端口，节点侧 `127.0.0.1:<targetPort>`）。因此该通道在协议上
无法被当作通用代理/翻墙工具使用。

其余约束：票据 120 秒有效、单会话并发连接 ≤8、空闲 5 分钟断开、可选速率档（面板下发
`rateKbps`，节点本地 token bucket 执行）、节点随时可被面板 `p2p.session.close` 回收。

## 3. 启动器 sidecar（已实现：`cmd/mcpanel-p2p`，节点仓）

```sh
mcpanel-p2p --panel https://panel.example.com --instance <实例ID> [-auth-scheme Bearer] [-verbose]
# 就绪后 stdout 输出一行，启动器据此启动 MC 客户端：
# MCP2P-READY {"local":"127.0.0.1:52341","session_id":"…","instance_id":"…","target_port":25600}
```

行为（`internal/p2pclient`，与本文协议一一对应）：

1. 读配置：面板地址（`--panel`/`MCPANEL_PANEL`）+ 域令牌（`--token`/`MCPANEL_TOKEN`）+ 实例 ID
   （`--instance`/`MCPANEL_INSTANCE`）。地址可以是站点根或 `…/v1/p2p/session`，一律归一化到适配器基址
   `…/api/plugins/ymcl-adapter/v1/p2p`；
2. 鉴权：`Authorization` 默认是**裸 token**（宿主与启动器约定，无 `Bearer ` 前缀）；需要前缀时用
   `--auth-scheme`/`MCPANEL_AUTH_SCHEME` 显式声明。令牌优先走环境变量，避免出现在进程列表里；
3. `POST /session {instance_id}` 拿票据与节点候选（响应 snake_case：`session_id/ticket/target_port/
   candidates/expires_at`）；上游未装/未开 P2P 时适配器回 **501**，sidecar 原样透出说明；
4. 本地监听 **只允许 loopback**（传非 loopback 地址直接拒绝启动），默认随机端口；
5. 玩家客户端连本地端口 → 候选**按优先级依次尝试** → `MCP2P1 <sessionId> <ticket>` 握手 → 双向转发
   （`unauthorized` 立即失败不再试其它候选，连接失败才换候选）；
6. 每 2 秒轮询会话状态（`--status-interval`）：`failed/closed/expired`（`terminal=true`）时断开本地连接并输出原因；
7. 退出（stdin 关闭 / SIGINT / SIGTERM / 状态终态）时 `DELETE /session` 回收；被强杀时节点按 TTL 兜底。

构建（节点仓）：`sh scripts/build-p2p-sidecar.sh <版本>` → `dist/mcpanel-p2p-{linux,darwin,darwin-arm64,windows}-*`
（`CGO_ENABLED=0`，纯 Go 标准库，可交叉编译；Windows 约 5.9MB）。

**启动器集成**：读 sidecar 的 stdout `MCP2P-READY` 行拿 `local` 地址 → 用该地址启动 MC 客户端 →
玩家退出时关闭 sidecar 的 stdin（sidecar 自动回收会话）。sidecar 与启动器框架解耦（localhost IPC）。

## 3.5 启动器侧接入（YMCL 适配器，玩家无感）

玩家侧**不出现任何 P2P 入口**：启动器在玩家点「连接」时静默完成开会话与建隧道。
`ymcl-adapter` 因此成为 mcpanel P2P 能力的消费方与启动器面向的唯一出口：

| 适配器端点（YAP） | 作用 |
| --- | --- |
| `GET /v1/capabilities` | 装了 mcpanel 且面板开启 P2P 时宣告 `p2p` 能力并附 `p2p.session_url`；否则不宣告（启动器回退公网连接） |
| `GET /v1/p2p/capabilities` | 能力探测（available/reason） |
| `POST /v1/p2p/session` `{instance_id}` | 玩家连接时自动开会话 → 返回 `ticket / target_port / candidates / expires_at`（snake_case） |
| `GET /v1/p2p/session?session_id=` | 轮询状态；`terminal=true` 时启动器收隧道并提示 `reason` |
| `POST /v1/p2p/session/signal` | 中继本端候选（打洞路径） |
| `DELETE /v1/p2p/session?session_id=` | 退出/切服时关闭（幂等） |
| `/v1/mip/api/servers` 的 `p2pInstanceId` | 服务器条目自动标注可直连实例（见 §3.7） |

- 失败透传：mcpanel 的业务机器码（`p2p.instance-disabled` / `p2p.not-allowed` / `p2p.not-running` / `p2p.node-capability` …）原样返回，启动器据此给玩家可读提示；provider 缺失或未开 P2P → **501**，启动器自动回退普通公网连接。
- **扩展点方向（关键）**：适配器声明 `online.yudream.base.plugin.ymcl.api.YmclP2pProvider`（接口 + `YmclP2pSessionView`/`YmclP2pCandidateView`/`YmclP2pException`），**面板实现并注册**（`context.registerExtension`），适配器只聚合（`YmclP2pLink` 每次请求动态查询，启停即时反映）。适配器**不声明**对 mcpanel 的依赖，因此不会与「业务插件 → 适配器」的贡献方向成环。
- **为什么不能反过来**：宿主启用插件时递归启用硬/软依赖，遇到环会抛「插件依赖存在循环」并把该可选依赖整体丢弃（只有一行 WARN）——能力静默失效。此前的环是 `ymcl-adapter → mcpanel → minecraft-server → ymcl-adapter`；`launcher-adapter` 时代的同类规则见宿主仓 `AGENTS.md`（平台适配器不得依赖软依赖自己的业务插件）。
- `ci/verify-plugin-dependency-acyclic.sh` 在本地把这张图建出来做环检测（已接入 `ci/verify-plugin-repo-readiness.sh`），任何环都会直接失败并打印环路径。
- 开发模式：面板侧 `provided` 依赖适配器，接口类由宿主按声明的 softdepend 装配（与 `minecraft-server → ymcl-adapter` 同一机制），无需 dev-export 导出适配器 JAR。

## 3.6 启动器侧（YMCL/Axolotl，玩家无感）

启动器只做三件事：能力门禁、sidecar 生命周期、把回环地址塞进启动参数。会话创建/候选/握手全在
sidecar 里，令牌也只交给需要它的进程。

| 位置 | 职责 |
| --- | --- |
| `api/ymcl/manifest.rs` | `YmclCapabilities.p2p` 节点（`session_url`）+ `p2p_session_url()` 兜底推导 |
| `api/ymcl/p2p/mod.rs` | 能力门禁（`p2p` 能力或 p2p 节点）、会话客户端（`domain_request_opt`，裸 token）、sidecar 注册表（按实例复用，进程死了自动摘除）、`release_for_instance/release_all` |
| `api/ymcl/p2p/sidecar.rs` | 二进制解析（`MCPANEL_P2P_BIN` → exe 同目录 → `bin/` → `resources/`）、拉起（令牌走环境变量）、`MCP2P-READY` 解析与**回环地址校验**、stdin 关闭优雅退出（3 秒后强杀） |
| `apps/app/src/api/ymcl.rs` | Tauri 命令 `ymcl_p2p_status / prepare / session_get / close / release` |
| `state/process.rs` | 游戏进程退出时 `release_for_instance`（会话随游戏会话结束） |
| `JoinServerModal.vue` | 启动时用 `launch_address ?? mc_address`（一行；玩家侧无任何入口或提示） |

- 触发点在**进服预览**：`ymcl_join_preview` 在服务器带 `p2pInstanceId` 时静默拉起 sidecar，把回环地址通过
  `launch_address` 返回；失败（未装 sidecar / 域未宣告 / 网络不通）只记 warn 并返回 `None`，进服仍走公网地址。
- 复用与回收：同一实例重复进服复用已运行的 sidecar（不会开出第二个会话）；游戏退出即回收；启动器退出由
  `kill_on_drop` 兜底；sidecar 自身在会话终态时退出，面板 120 秒 TTL 是最后一道保险。
- 玩家可见性：连接地址栏/提示里显示的仍是域的公网地址（`mc_address`），回环地址只进启动参数。

## 3.7 服务器条目 → 实例的映射（`p2pInstanceId`）

启动器只知道「玩家点了哪台服务器」，不知道后台是哪台实例，因此域侧必须给出映射。
映射按**玩家看到的连接地址**反查，而不是让管理员手填实例 ID：

| 侧 | 行为 |
| --- | --- |
| mcpanel | `DomainService.instanceForHost(address)`：把地址归一化（去端口/末尾点/大小写、去 IPv6 方括号）后与 `{slug}.{suffix}` 比对，**只认已分配域名**（持久化 slug + domainEnabled）的实例；`YmclP2pProvider.instanceForAddress`（适配器声明的扩展点）在此基础上再过滤「已开 P2P」，只回实例 ID |
| ymcl-adapter | 聚合 `/mip/api/servers` 时对每个条目的 `mcAddress` 与各 `endpoints[].address` 逐个反查，命中即写 `p2pInstanceId`（已在视图里则不覆盖） |

- 语义边界：反查命中的是**该域名对外服务的那台实例**——单端口入口（entry）模式下即入口路由的目标实例，代理形态下即代理实例；隧道目标端口由面板票据钉死，适配器/启动器都无法指定。
- 失败一律不写字段：未装/未启用 mcpanel、面板未开 P2P、地址无匹配、实例未开 P2P、provider 版本旧到没有这个方法（`AbstractMethodError` 属 `LinkageError`，同属降级）——服务器保持原来的公网地址，进服不受影响。
- **不泄露信息**：反查只回实例 ID，不含实例名、节点、地址、白名单；未匹配也不暴露「有哪些实例」。
- 现状：管理员无需任何额外配置，只要实例在「实例域名」页分配过域名并开了 P2P，条目就会自动可直连。

## 4. 当前实现范围与后续

| 项 | 状态 |
| --- | --- |
| 面板信令（校验/票据/限额/候选中继/状态/审计/管理端断开） | ✅ 已实现并单测覆盖（`P2PSessionServiceTest` 11 例） |
| 节点 `p2p.session.open/signal/close` + 票据握手 + 目标钉死 + 双向泵 + 限速 + 回收 | ✅ 已实现并回环测试覆盖（`internal/p2p` 9 例 + `internal/wsserver` 端到端 1 例） |
| 传输：TCP 直连（节点开临时端口） | ✅ 可用前提：节点公网可达或已有端口映射（frp/UPnP） |
| 传输：UDP 打洞 + QUIC（对称 NAT 失败回落中继） | ⏳ 待做（模块图已有 quic-go；面板信令协议不变，只需替换候选生成） |
| 启动器 sidecar 二进制（Win/Linux/macOS + localhost IPC） | ✅ 已实现（`cmd/mcpanel-p2p` + `internal/p2pclient`，回环端到端测试 4 例；构建脚本产出四平台二进制） |
| 扩展点 `YmclP2pProvider`（面板实现/注册，适配器只聚合） + `/v1/p2p/**` + capabilities 宣告 p2p | ✅ 已实现（mcpanel 5 例 + 适配器 18 例；依赖图无环已由实测数据库图 + CI 门禁双向确认） |
| 实例级 P2P 开关 + 玩家白名单（管理端） | ✅ 已实现（实例设置卡；白名单只收数字 ID、≤50） |
| 启动器（YMCL/Axolotl）拉起 sidecar：能力门禁 + 二进制解析 + 就绪行解析 + 回环校验 + 注册表复用 + 游戏退出回收 | ✅ 已实现（`app-lib/src/api/ymcl/p2p/{mod,sidecar}.rs` 13 例；Tauri 命令 `ymcl_p2p_status/prepare/session_get/close/release`） |
| 启动器进服接线：`ymcl_join_preview` 产出 `launch_address`（回环口），进服模态用它替代公网地址；不可用时静默回退 | ✅ 已实现（`JoinServerModal.vue` 只改一行取值） |
| 域侧「服务器 → P2P 实例」映射（`p2pInstanceId`，见 §3.7） | ✅ 已实现：面板按域名反查实例（`DomainService.instanceForHost` + 契约 `instanceForAddress`，只广告开了 P2P 的实例），适配器聚合列表时逐地址标注（mcpanel 5 例 + 适配器 5 例；未命中/未装/旧版 provider 一律不写字段） |
| 该映射的现场验证（需一台已分配域名且开 P2P 的实例 + 域名与服务器地址一致） | ⏳ 待做：单测与打包已覆盖，现场需真实域名数据 |
| sidecar 分发（随安装包/资源目录放置，或由面板文件存储下发） | ⏳ 待做：当前启动器按 `MCPANEL_P2P_BIN` → exe 同目录 → `bin/` → `resources/` 顺序解析；打包分发方式待定 |
| 真实双 NAT spike（打洞成功率、回落透明性、MC 握手 hostname=localhost 兼容性） | ⏳ 待做（需两台不同 NAT 机器） |

**MVP 边界**：当前 TCP 直连路径对「节点有公网地址或已做端口映射」的场景即可用；
纯 NAT 场景要等打洞（或回落中继）落地。这一点在设计里也是明确的取舍——
P2P 不改变中继的成本下限，也不是所有 NAT 环境都能打洞直连。
