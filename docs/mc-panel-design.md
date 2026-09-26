# MC 面板（仿 MCSManager）设计稿

> 状态：v0.7.1（2026-09-19 v0.7 定稿；同日协议 v1 冻结并做确定性修订：§3.3 注册与认证改两段式、§3.2 信封、§4 端口 ingress 作用域、§5.4/§5.5/§5.6/§5.8/§5.9/§5.11 修正、M1 拆分 M1a/M1b；wire 契约唯一来源 `docs/mc-panel-protocol-v1.md`，实施验收跟踪 `docs/mc-panel-acceptance.md`）。§9 决策全部拍板；v0.6 启动器 P2P 进主线 M6/§5.9；v0.7 多租户配额 M7/§5.10 + 节点贡献 M8/§5.11。

## 0. 目标与非目标

目标：

- 前后端分离的 MC 服务器面板。前端 = 本仓插件的管理端页面（与宿主菜单/权限/主题联动）。
- 后端节点（Node Daemon）以 Docker 独立部署到任意机器，一个后端 = 一个节点，支持多节点。
- 实例由节点通过 Docker 派发（每个实例一个容器）。实例类型覆盖：MC Java 系（vanilla/paper/folia/fabric/forge）、代理端（Velocity/Bungee）、**基岩版（UDP 端口）**、**通用控制台应用**（任意命令行游戏/程序，如泰拉瑞亚，对标 MCSM 的通用控制台）。
- 基础能力对标 MCSM：实例 CRUD、在线控制台、启停/重启/强杀、配置管理、文件管理、资源监控。
- 增强能力：一键临时 FTP、镜像/服务端模板、**整合包一键开服（mrpack/CurseForge，含客户端模组剔除与核心回退）**、authlib-injector 自动注入、时长记录插件按类型自动安装、端口范围管理与强制重写配置文件、实例域名自动分配、与 `minecraft-server` 插件数据联动（§5.7）、**启动器 P2P 直连（§5.9，YMCL 启动器用户专属传输层）**、**多租户配额下发（§5.10，可开关 + 钱包租赁软联动）**、**玩家节点贡献（§5.11，闲置算力接入统一调度）**。

非目标（v1）：

- 用户端自助开服（v1 纯管理端 `/admin/**`）。
- 计划任务、备份策略、实例市场、特定游戏的深度集成（创意工坊/模组一键安装等，v2+）。

## 1. 总体架构

```
浏览器(管理端)                宿主 + 面板插件(panel)                 节点机器
┌──────────────┐   HTTPS/SSE   ┌───────────────────┐   WSS/HTTPS   ┌──────────────────┐
│ plugin-mcpanel│ ───────────► │ yudream-plugin-    │ ───────────► │ mcpanel-node     │
│ (Vue remote)  │ ◄─────────── │ mcpanel (本仓后端) │ ◄─────────── │ (Go 单二进制)     │
└──────────────┘               └───────────────────┘              │  └─ Docker SDK   │
                                                                    │  └─ 实例容器 N 个 │
                                                                    └──────────────────┘
```

- **浏览器 ↔ 面板**：走宿主插件 HTTP 端点。控制台输出用 SSE（fetch-stream 带 Authorization，仓内已有 4 处先例），控制台输入/命令用 POST。
- **面板 ↔ 节点**：v1 采用 MCSM 同构的**正向连接**——面板作为 WS 客户端主动连节点的 WSS 控制信道（节点为 WSS 服务端；面板侧只需出站 WS 客户端，不依赖宿主 WebSocket SPI）；注册（enroll）为节点 → 面板的一次性 HTTPS bootstrap（见 §3.3）；大流量（文件上传/下载）走节点 HTTPS 临时票据接口。浏览器永远不直连节点。Wire 契约见 `docs/mc-panel-protocol-v1.md`。
- NAT/内网节点由内嵌 frpc 反向拨出到面板侧 frps，传输层抹平后管理面仍只有「面板正向连节点」一种代码路径（见 §3.7）；YMCL 启动器玩家另有 P2P 直连传输层（见 §5.9）。

### 模块划分

| 模块 | 位置 | 说明 |
| --- | --- | --- |
| `yudream-plugin-mcpanel` | 本仓 `yudream-plugins/` | 面板后端：节点/实例/模板/端口池数据、权限、SSE/HTTP 端点、对节点的 WSS 客户端 |
| `plugin-mcpanel` | 本仓 `yudream-frontend/packages/` | 管理端页面：节点、实例列表、控制台、文件、模板、端口 |
| `mcpanel-node` | **新独立仓**（参照 taru-sso 先例） | 节点守护进程 + Dockerfile，Go 单二进制，内嵌 frpc |
| `frps` | 面板侧 docker compose 配套服务 | frp 服务端，承接 NAT 节点隧道与游戏端口代理（见 §3.7） |
| `mcpanel-p2p` | `mcpanel-node` 仓内 Go 模块 | P2P 传输库：节点内嵌 + 编译为启动器 sidecar（Windows/Linux/macOS），打洞/QUIC 隧道/限额（见 §5.9） |

约束备注：面板后端只能用宿主 SPI（文档存储、files()、templateRenderer 等），插件端点默认公开需自行鉴权。SPI 现状（2.31.0，2026-09-19 宿主源码核实）：multipart 已支持但 parts 全量进堆内存；无二进制流式请求体；无二进制流式响应（全量缓冲）；SSE 完善（30min 超时 + Last-Event-ID）；**无插件 WebSocket 端点**。SPI 不足处允许扩展宿主（见 §10）。

## 2. 权限模型

权限码遵循 `plugin:mcpanel:{动作}` 与宿主词表：

- `plugin:mcpanel:view` — 看节点/实例/监控/控制台输出
- `plugin:mcpanel:use` — 控制台输入、启停/重启/强杀、执行命令
- `plugin:mcpanel:manage` — 实例 CRUD、模板、端口池、节点管理、一键 FTP、租户管理
- `plugin:mcpanel:delete` — 删除实例/节点（前端二次确认）

**租户数据范围（M7 起，配合 §5.10）**：权限码不变——**权限决定能否进入，租户范围决定能访问哪些记录**。设置页配置「平台管理角色」（宿主角色），命中的权限持有者为平台管理员，数据范围不限；其余 view/use/manage 持有者的数据范围收窄为其所属租户（成员关系由租户绑定的部门/角色实时解析）。节点、实例、端口分配、票据、审计均记录 `tenantId`（平台直属为空），查询与变更一律带范围约束；管理员走玩家面时仍只是普通用户范围。

M1–M5 全部是 `/admin/**` 管理端点；M6 起出现玩家面端点（P2P 信令 §5.9、节点贡献 §5.11），**必须使用 `/me/**` 命名空间**（宿主仓强制规则），校验 OAuth principal 自鉴权，授权矩阵（自身/他人/管理员/未认证）按宿主规范全覆盖。

**机器主体表面**：`POST /node/bootstrap`（enroll，§3.3）是唯一免登录的 machine bootstrap 端点，不属于 `/admin` 或 `/me` 表面，凭据为一次性高熵 enroll token；节点控制信道凭 WSS 握手期 nodeSecret 认证（TLS pin/PKIX + Bearer）。玩家贡献节点（§5.11）的 enroll token 在审核通过后才签发，走同一机制。

## 3. 通信协议（核心）

### 3.1 通道分层

| 通道 | 方向 | 用途 |
| --- | --- | --- |
| WSS 控制信道 | panel ⇄ node | 认证、心跳、命令 req/res、事件推送（实例状态/控制台输出/任务进度） |
| HTTPS 票据接口 | panel → node | 文件上传/下载、镜像拉取等大流量，凭一次性 ticket 调用 |
| SSE + POST | browser ⇄ panel | v1 浏览器侧实时输出与指令（面板做转发代理）；SPI 扩展插件 WS 后可升级为双向 WS（见 §10） |

控制信道一条够用：多路复用靠信封里的 `id` 关联与 `topic` 订阅，不开第二条 WS。

### 3.2 信封格式（JSON，UTF-8）

```jsonc
{
  "v": 1,                    // 协议版本
  "id": "01JQY…",            // ULID；req 必填、res 回显对应 req id；evt 不带 id
  "t": "req|res|err|evt",
  "m": "instance.start",     // 方法名 / evt 时为 topic
  "ts": 1737619200000,       // epoch ms，仅诊断用途，不参与安全
  "seq": 7,                  // 仅 evt：同连接同 topic 单调递增整数（0 起），作用域为 sessionId，重连可重置
  "p": { }                   // payload；err 时 {code, message, retryable}
}
```

规则：

- `req` 必须应答 `res` 或 `err`，默认超时 30s；超时按幂等键可安全重发。
- 变更类请求携带顶层幂等键 `ik`（面板生成），节点对已执行键直接返回上次结果，防断线重发导致重复建容器；查询类禁带。
- 长操作必须立即返回 accepted 语义的 `res`，终态走 evt；禁止 30s 超时内跑不完的同步 res。
- 未知方法必须返回 `err proto.unknownMethod`，禁止注册空实现占位；caps 只声明真实实现的能力。
- 认证后帧完整性由 TLS 保证，无逐帧应用层签名（详见 §3.3）；单帧 ≤ 256KB，超出走分块或票据通道；控制台输出按 100ms 窗口聚合、单事件 ≤ 16KB。
- 所有字符串时间用 epoch ms 数字；所有 ID 用字符串（遵守长 ID 规则）。

### 3.3 认证与注册（enroll）

> v0.7.1 修订：注册改为「节点 → 面板 HTTPS bootstrap + 面板主动拨节点 WSS」两段式；废弃逐帧 HMAC（原 `p.sig` 自引用与跨语言 canonicalization 隐患随之消除）。Wire 细节以 `docs/mc-panel-protocol-v1.md` 为唯一契约源。

1. 管理员在面板「节点」页**预创建**节点记录：录入名称、拨号 authority（endpoint 字段仅存 `wss://host[:port]`，`/control` 路径由面板程序固定追加，不得书写路径）、并用节点首次运行的 `fingerprint` 命令输出录入叶证书 SHA-256 pin（或对该节点选择 PKIX 模式）。面板签发一次性 `enrollToken`（≥128 位熵，10 分钟有效，单次消费，绑定该节点记录）；重签端点 `POST /admin/nodes/{id}/enrollment` 仅对**未注册**节点可用，已注册节点必须返回 `409`——M1 无 rekey，不得以重新 enroll 冒充密钥轮换（需轮换时删除节点记录重建）。
2. 节点首次启动以 HTTPS 调面板 `POST /node/bootstrap`（挂载 `/api/plugins/mcpanel/node/bootstrap`，machine rawJson 响应，不走 admin wrapped 包装），请求体 `{enrollToken, hostname, agentVersion, caps, tlsCertSha256}`；`tlsCertSha256` 仅在 **pinned 模式**下与记录内 pin **相等校验**（PKIX 模式节点记录无 pin，不做相等校验），bootstrap 绝不自动批准新 pin；pin 不匹配拒绝**且不消费 token**（纠正后同一 token 可重试）。端点限速、常量时间比对 token、失败审计。
3. 面板校验通过后响应 `{nodeId, nodeSecret(32 字节 base64url), serverTimeMs, controlPlan:{mode:"direct"}}`；`nodeSecret` 仅此一次下发、节点落盘 0600、面板侧经 SPI `PluginSecretStore`（2.31.0 已提供）存储，不进文档明文与日志。`controlPlan.mode=frp`（M1b）时附带节点**专属**代理凭据（绝不复用 frps 管理员 token），节点先起内嵌 frpc 建立隧道，面板再经隧道拨入——不存在"必须有控制信道才能配置 frp"的循环。
4. 面板主动拨 `{节点endpoint}/control`（endpoint 字段已含 `wss://` scheme，程序只追加 `/control` 路径），握手头 `Authorization: Bearer <nodeSecret>` + `X-Mcpanel-Node-Id: <nodeId>`（凭据禁止进 URL/查询串），节点常量时间比对。握手成功后面板先发 `req node.hello`（p `{panelVersion}`），节点回 res（p `{nodeId, sessionId, hostname, agentVersion, caps, dockerVersion?}`）。认证只在握手层执行一次，此后帧完整性由 TLS 保证，**无逐帧应用层签名**。
5. M1 不做 rekey；密钥泄露处置 = 删除节点记录重新 enroll（`node.rekey` 属 M2+，实现时旧 secret 握手宽限 5 分钟）。

TLS 与拨号安全：强制 `wss://`；自签场景按记录内 SHA-256 叶证书 pin 校验，CA 签发走标准 PKIX + hostname 校验；frp 隧道只做 TCP 中继、不得终结 TLS，否则 pin 失效。面板出站拨号目标只能来自管理员节点记录：endpoint 为 authority-only（仅 `manage` 权限可写、scheme 仅 `wss`），`/control` 由程序固定追加；目标 IP 默认拒绝 loopback/link-local/metadata，`localDevelopment` 仅放宽 loopback 目标 IP 且仍强制 wss（不允许 ws 明文）——hello/bootstrap 任何帧都不能改写拨号目标（防 SSRF）。节点监听地址/端口可配，建议只绑内网网卡 + 防火墙白名单。

### 3.4 心跳与拓扑

- 节点在 `node.hello` res 后立即推第一帧 `evt: node.stats`，此后每 5s 一帧：`{cpuPercent, memUsedMb, memTotalMb, diskUsedGb, diskTotalGb, load1?, containers:[{instanceId, state, cpuPercent, memUsedMb}], dockerVersion, agentVersion}`——字段名、单位（mem=MiB、disk=GiB）与 seq 语义严格以 `docs/mc-panel-protocol-v1.md` §5.2 为准，此处不得另造字段。
- 面板 15s 未收到**成功解析且符合已协商协议的有效帧**（unknown/malformed 帧不续命计时器）→ 标记节点 `offline`，其下实例状态置 `unknown`，控制台订阅挂起待重连恢复；`node.hello` 握手有独立 30s 超时，不受 15s online 计时器误杀，15s 计时自首个有效帧起算。
- 重连由面板负责（指数退避 1s→30s + 抖动）；重连后 `node.hello` 响应携带新 `sessionId`，evt `seq` 以连接为作用域可重置；`node.stats` 为快照语义，无需跨连接续传；控制台输出续传（`sinceSeq`）推迟到 M2 单独定义。
- `node.hello` 响应里带能力位 `caps`（真实实现的方法/事件，基线含 `node.hello`/`node.stats`），面板按能力位降级 UI。

### 3.5 方法集（v1）

| 方法 | 说明 |
| --- | --- |
| `node.hello` / `node.rekey` | 认证 / 换密钥 |
| `template.list` / `template.sync` | 节点缓存的镜像与模板清单 / 面板推送模板到节点 |
| `instance.create` `update` `delete` | 创建（含端口分配结果回执）、改配、删除 |
| `instance.start` `stop` `restart` `kill` | 生命周期；`stop` 带 `timeoutSec`，超时节点自动转 kill |
| `instance.command` | 向容器 stdin 写一行（控制台输入） |
| `instance.output.subscribe` / `unsubscribe` | 订阅输出；支持 `sinceSeq` 游标断线续传 |
| `instance.config.read` / `config.write` | 白名单配置文件读写（server.properties、eula.txt 等，由模板声明） |
| `file.list` `read` `write` `delete` `mkdir` `rename` `chmod` `compress` `decompress` | 文件管理（小文件直接走控制信道） |
| `file.upload.ticket` / `file.download.ticket` | 换发一次性 HTTPS 票据（见 §3.6） |
| `ftp.open` / `ftp.close` | 启停 sidecar FTP 容器（见 §5.4） |
| `port.check` | 批量探测端口空闲（docker port + 本地 bind 双检） |
| `p2p.session.open` / `p2p.session.close` | 建立/拆除启动器 P2P 会话，携带面板签发的 `rendezvousTicket`（见 §5.9） |
| `p2p.signal` | 双向透传打洞信令（ICE 候选等），面板中继 launcher⇄node |
| `image.pull` | 拉镜像，进度走 `evt: task.progress` |

事件 topic：`node.stats`、`instance.state`（含退出码/原因）、`instance.output`、`task.progress`、`ftp.opened/closed`、`p2p.session.state`。

M1 范围：仅 `node.hello`、`evt node.stats` 为必选；`port.check`、`instance.list`、`evt.instance.state` 只有真实实现才在 caps 声明；其余方法为 M2+ 预留，未知方法必须回 `err proto.unknownMethod`，禁止空实现占位。

### 3.6 大流量票据通道

`file.upload.ticket` 响应：

```json
{ "url": "https://node:24443/t/upload/01JR…", "ticket": "…", "expiresInSec": 120, "maxBytes": 536870912 }
```

- 票据一次性、绑定 `(nodeId, instanceId, 目标路径, 操作, sha256?)`，120s 过期。
- 面板作为 HTTP 客户端对节点 PUT/GET；浏览器侧永远只跟面板通信（面板转发字节流）。
- 控制信道收到完成 `res` 后才算事务提交；中途断连节点清理临时文件。

### 3.7 节点接入形态与 NAT 穿透（分级探测）

**核心设计决策：管理面协议只有「面板正向连节点」一种代码路径，NAT 差异由传输层抹平**——面板不感知节点以何种方式被触达。节点上线时按阶梯自动探测出连能力，上报面板，面板据此决定实例的对外暴露模式：

| 级别 | 模式 | 原理 | 代价/限制 |
| --- | --- | --- | --- |
| L0 | 直连 | 节点有公网 IPv4 / 与面板同内网，面板直连 `wss://节点地址` | 无 |
| L1 | UPnP/NAT-PMP/PCP 自动端口映射 | 节点向家用路由器申请端口映射（Go 库直接支持），玩家/面板经「路由器公网 IP:映射端口」直连 | 仅当上级 NAT 有公网 IP；**运营商 CGNAT（大内网）下无效**，探测失败自动降级 |
| L2 | IPv6 直连 | 家宽普遍有公网 IPv6；AAAA 记录指节点，SRV 照常 | 玩家端无 IPv6 时连不上；需与 L3 中继并存做 IPv4 兜底 |
| L3 | frp 中继（兜底，必实现） | 节点内嵌 frpc 拨出面板上架的 frps，管理端口与游戏端口经隧道注册到入口机 | 游戏与文件流量绕一道入口机，延迟/带宽取决于入口机链路，适合中小服/测试服 |

探测顺序 L0→L1→L2→L3，取最高可用级；**L1/L2 任何一步不可用都无条件落 L3 frp 中继——任何 NAT 环境下节点必可接入，直连只是省中继成本的优化而非前提**。L1 外部 IP 变化（家宽动态 IP）由节点周期复探并推动面板更新 DNS。面板的节点/实例页展示当前暴露模式。

**L1 全自动化说明（无需人工操作路由器）**：节点通过 SSDP 组播发现网关、调 IGD 的 `AddPortMapping`（NAT-PMP/PCP 同理），全程协议交互、零人工、零路由器凭据。唯一前提是**路由器开启了 UPnP**——家用路由器大多默认开启；光猫+路由器双层 NAT 时内层映射无效，需光猫改桥接（一次性人工）。映射成功后节点还要请**面板侧回探**（从公网对 `外部IP:映射端口` 发起真实连接测试），确认是真的直连而非「映射成功但被 CGNAT 挡住」的假象，回探失败自动降级 L3。映射带租约，节点周期续租，实例删除/节点下线时主动 `DeletePortMapping` 清理。探测/回探失败的具体原因（UPnP 未开启、双层 NAT、CGNAT）在节点页展示并给出操作指引。

**为什么必须连游戏流量一起考虑**：节点在 NAT 后不只是面板连不上它——**玩家也连不上这台机器上的实例**。vanilla 玩家是普通游戏客户端，无法装任何隧道客户端，所以通用 P2P 工具（xtcp/ZeroTier/Tailscale 一类）对他们不成立，公网可达性只剩「直连（L0-L2）或中继（L3）」两条路；但 **YMCL 启动器用户拥有我们控制的端**，可内嵌 P2P 传输层实现打洞直连，作为叠加在公网阶梯之上的玩家侧传输（见 §5.9）。

**frps 部署**：面板侧 docker compose 配套服务（v1 与面板同机）：控制端口（节点 frpc 拨入）、一段游戏代理端口范围（= L3 节点端口池）、token 鉴权 + 防火墙收敛；管控端口与 dashboard 不对公网开放。frpc 以 Go 库内嵌进节点二进制（约 +10MB），非独立进程。

**v2 优化——MC 感知虚拟主机代理（L3'，已采纳，排 M5）**：L3 的 MC Java 实例升级为 MC 协议层代理（基于 Go 的 Gate/minekube 库自研）：玩家连入口机 **25565 单端口**，代理解析握手包里的 hostname 路由到对应实例的隧道。收益：NAT 的 MC Java 实例不耗端口池、免 SRV 直接默认端口连接、MOTD/在线人数可在入口层聚合；仅适用 MC Java 协议（基岩 UDP 握手无 hostname，通用游戏同理，仍走 L3 普通中继）。

信封协议（§3.2）保持方向无关设计不变，作为 frp 不可用时的备选隧道手段（v2 再评估是否需要）。

## 4. 端口池与配置强制重写

- 面板文档存储是唯一权威：`port_allocations {ingressId, port, proto(tcp/udp), instanceId, nodeId}`。**唯一键作用域为 `(ingressId, port, proto)`**：L0/L1 直连/映射节点 `ingressId = nodeId`（各节点端口空间独立）；L3 中继节点 `ingressId = frps 入口`（入口端口跨所有节点全局唯一，两个 NAT 节点不可能分到同一入口端口）。文档存储无跨文档事务，分配原子性靠确定性文档 id（如 `ingress:{ingressId}:{port}:{proto}`）或单文档内子结构实现，不得假设跨文档事务。节点配置 `portRange: [start,end]` 与 `reservedPorts[]` 由面板下发，节点本地不持久化分配结果。
- **L0/L2 节点（直连）**：端口池 = 节点本机端口；`port.check` 探测节点本机 docker port + bind。
- **L1 节点（UPnP/PCP 映射）**：端口池 = 期望的外部端口区间；节点向路由器申请映射成功才算分配完成，外部 IP 变化触发 DNS 更新。
- **L3 节点（frp 中继）**：端口池 = frps 入口机端口；分配后注册 frp TCP 代理 → 入口机端口转发至节点容器端口；`port.check` 探测入口机端口空闲 + 代理注册成功。MC Java 实例升级 L3' 后不耗端口池。
- 创建/改配实例：面板事务内分配 → 端口实测 → 冲突则换端口重试（最多 5 次）→ 下发实例。
- **TCP/UDP 双协议**：`port_allocations.proto` 区分；MC Java/代理走 TCP，基岩版走 UDP（19132 段），通用应用按模板声明。同端口号 TCP/UDP 互不冲突，分配按 `(port, proto)` 联合唯一。
- 节点在**每次启动实例前**按模板规则重写配置文件（不是只在创建时）：
  - `server.properties`: `server-port`、`query.port`、`rcon.port`
  - `velocity.toml`、`config.yml`(Bungee) 等按模板声明的 `configRewrites: [{file, key/jsonPath, value: "${ALLOC_PORT}"}]`
- 这样用户在容器里手改端口也会被下一次启动纠正，满足「强制重写」。

## 5. 实例、模板与注入

### 5.1 数据模型（面板文档存储）

```
nodes      {name, endpoint, pinMode(pin|pkix), certPinSha256, nodeSecretRef(PluginSecretStore),
            labels[], caps, status, lastSeenAt, portRange, reservedPorts[], agentVersion,
            tenantId(可空=平台直属), poolTags[]}
instances  {nodeId, name, type, mcVersion, templateKey, imageKey,
            ports[{proto, alloc, container}], env{}, memMB, cpuLimit,
            volumeName, state, lastExitCode, createdAt, remark, tenantId(可空)}
templates  {kind: image|server, key, ...}
audit_logs {actor, action, instanceId, detail, at, tenantId(可空)}
ftp_sessions{instanceId, port, user, expiresAt, state, tenantId(可空)}
```

多租户字段自 M1 起保留在数据模型中，M7 只加校验逻辑、不做数据迁移；`nodeSecret` 不落文档明文，经 SPI `PluginSecretStore` 引用存储。

### 5.2 模板

- **镜像模板** `imageTemplate`：`{key, image:"eclipse-temurin:21-jre", javaVersion, defaultJvmOpts, extraEnv}`。节点可预拉取；面板展示 `image.pull` 进度。
- **服务端模板** `serverTemplate`：`{key, type, installer(下载源/构建 API), startup, configRewrites[], inject}`。`type` 分三组：
  - **MC Java 系**：`vanilla/paper/folia/fabric/forge` —— `startup:{jarGlob, jvmOpts}`，支持 authlib/时长插件注入，配置重写走 properties/yml 规则，TCP 端口。
  - **MC 代理**：`velocity/bungee` —— 同上但注入规则按类型裁剪（authlib 不适用，时长插件视版本）。
  - **基岩版**：`bedrock` —— **UDP 端口**（默认 19132），重写 `server.properties` 的 `server-port`/`server-portv6`，无注入。
  - **通用控制台应用**：`generic` —— `startup:{command, args[]}` 任意命令（泰拉瑞亚、其他游戏服、自定义程序），无注入、配置重写可选（模板声明才启用），proto 按模板声明 tcp/udp。这是对标的 MCSM「通用控制台」形态，其他游戏后续以新模板键扩展，不改协议。

### 5.3 authlib 与时长插件注入

- **authlib-injector**：面板配置 jar 下载地址 + 验证服 API 根；节点缓存 jar（sha256 校验），挂载到 `/data/.panel/authlib.jar`，启动命令前插 `-javaagent:…=<apiRoot>`。对离线/特定类型可由模板关闭。
- **时长记录插件**：`type ∈ {paper, spigot, purpur, folia}`（及 Bungee/Velocity 若该插件有对应版）时自动从配置的制品地址拉取钉住版本的 jar 放入 `/data/plugins`，带 sha256；模板可关。
- 注入统一发生在「首次安装」与「每次启动前的配置重写阶段」，保证手删也能恢复（可配置）。

### 5.4 一键 FTP

- 面板点「开启临时传输」→ 节点拉起 sidecar 容器：受限临时 **FTPS**（explicit TLS）或 SFTP，二选一实现；**明文 FTP 不作为默认形态**。挂载实例同一 volume，控制端口与**被动数据端口段**都从端口池分配（被动段在 L1 需一并 UPnP 映射、L3 需一并 frp 代理，缺一不可连）。
- 生成随机账号密码；每节点一张传输用 TLS 证书，指纹在开启弹窗展示供用户钉住。TTL 默认 2h，到期或手动关闭即销毁容器并回收端口；`ftp_sessions` 落库用于审计。
- UI 弹窗展示主机/端口/账号/密码与倒计时（FaModal 短任务合适）。

#### 5.4.1 SFTP 单端口网关（0.11.0 已落地，实例级）

节点直连形态要求每个实例的 SFTP 端口都能被桌面客户端可达（NAT/防火墙逐端口放行），多机部署是真实痛点。网关方案把入口收敛为面板上的一个端口：

- **形态**：面板 JVM 内嵌 Apache MINA SSHD（shade 进插件 JAR，slf4j 由宿主提供）监听单端口（设置页可配、保存热生效、默认关闭）。纯 L4 转发不可行——SSH 无 SNI，网关必须在 L7 终结 SSH 后按凭据路由。
- **标识**：username = `mc-{6位无歧义短别名}`（30 字符表，去 0/o/1/l/i），password = 面板签发的随机串（注册表只存 SHA-256，常数时间比较）。别名是路由键不是凭据；完整 instanceId 作为兜底用户名（调试/自动化）。**节点不进标识**——instanceId 查面板数据库即得归属节点；别名唯一性只需在活跃会话内成立（纯内存、与 ftp.open 同 TTL、重启即清）。
- **数据面**：认证通过后网关以 ftp.open 签发的随机凭据向节点侧 SFTP 发起 SSH 连接，两侧 sftp subsystem 字节流对泵（32KB 缓冲双线程）；节点侧实现零改动。面板→节点 host key 用进程内 TOFU；认证失败按来源 IP 限速（10 次/10 分钟窗口）。
- **凭据治理**：ftp.open/close 端点复用——开启时在节点随机凭据之上追加网关别名签发，响应换成网关四件套（host=广告地址留空则前端按站点主机名展示、port=网关端口、user=mc-别名、password=网关密码），节点凭据不下发；手动 close 联动撤销别名。断开 SFTP 客户端不撤销（凭据 TTL 内可重连）；host key 持久化到平台文件存储，客户端书签不告警。
- **合规**：插件自绑业务端口属插件自有网络行为，不经宿主 HTTP/SPI 边界；节点级 `ftp.open.node` 暂未接网关（形态一致，后续按需补）。

### 5.5 文件管理（分层路径）

- **中小文件上传**（配置、插件 jar、world 小压缩包，阈值暂定 64MB）：浏览器 → 面板 multipart（SPI 2.31.0 `parts`，已可用）→ 面板经票据通道 PUT 到节点。注意 parts 在宿主侧全量进堆——面板内 64MB 校验发生在读取之后，**无法阻止宿主先吃堆**；P2 流式扩展落地前，真实防线是**网关/反向代理的请求体长度前置限制**，面板校验仅作第二道。
- **大文件上传**（整存档、整合包）：浏览器 → 宿主文件存储（SDK `uploadImage` 可传任意文件，走 S3 分片）→ 面板从存储取流 → 票据通道推到节点。绕开堆缓冲。
- **下载**：小文件节点 → 面板 → 浏览器 `application/octet-stream`（避开宿主 blob() 见 JSON 即抛的坑）；大文件面板先落宿主文件存储再返回平台文件下载地址（自带缓存与断点续传基础设施）。
- 配置/文本文件（<256KB）走控制信道 `file.read/write`，编辑器用代码高亮文本域。
- 待 §10 P2 流式扩展落地后，大文件可改为面板直管流式中转，去掉文件存储这一跳。

### 5.6 实例域名自动分配（玩家侧，无需证书，可选功能）

实例可选开启「自动域名」：为实例生成 `{实例名slug}.{游戏域后缀}` 形式的记录。MC 协议不走 TLS，**无需任何证书**。该功能是可选开关，未配置 DNS 驱动时 UI 降级隐藏。

- **A 记录** → 按节点暴露模式（§3.7）取值：L0 节点公网 IP；L1 路由器外部 IP（变化时自动更新）；L2 写 AAAA；L3 指向 frps 入口机 IP；L3' 的 MC Java 实例指代理入口且可免 SRV。
- **SRV 记录** `_minecraft._tcp.{slug}.{后缀}` → 指向实例分配端口，target 使用实例自身 hostname（SRV target 不得是 CNAME）。MC Java 客户端原生支持 SRV 解析，玩家**只输域名不带端口**即可连到非标端口实例；不支持 SRV 的老客户端回退 `域名:端口`。
- 生命周期随实例：创建/端口变更时写入或更新，删除实例时清理记录；slug 全局唯一校验，改名联动换绑。
- TTL 设低（120s）保证端口变更快速生效；实例卡片与控制台展示连接地址（域名与 IP:端口 双格式）。
- 基岩版客户端不支持 SRV，只能 A 记录 + 显式端口；通用游戏无 SRV 概念，仅发 A 记录并在 UI 展示 `域名:端口`。

**DNS 驱动三选一（设置页配置，互不影响主域 HTTP 业务）**：

1. **子域 NS 委托 + 面板内嵌权威 DNS（推荐）**：主域 DNS 只手工加一条 NS 记录，把 `mc.example.com` 委托给面板 NS 的 **hostname**（NS 必须指向主机名；当该主机名位于被委托子域内部时还需补 glue A/AAAA；父区启用 DNSSEC 时需处理 DS/委托签名——设置页应校验并提示），此后 `*.mc.example.com` 整个子域由面板自建的权威 DNS 服务管理（Go 栈内嵌 miekg/dns 或 CoreDNS，记录由面板数据库驱动）。即写即生效、TTL 可控、不需要任何 DNS 商 API；**主域及其他子域的解析完全不经过面板**，撤销只需删掉那条 NS 记录。代价：面板机需开放 UDP/TCP 53，权威 DNS 可用性自担（v1 单点可接受）。
2. **DNS 商 API + 受限凭据**：域名仍托管在 DNSPod/Cloudflare/阿里云，面板只增删固定前缀的记录，凭据使用服务商的受限 token/子账号。实现最轻，但 token 权限粒度取决于服务商，且受 API 限流与传播延迟影响。
3. **独立游戏域名**：单独使用一个专用域名（如 `xxx-play.net`）给实例，主域零接触；搭配方案 1 或 2 驱动均可。

> 泛解析（一条静态 `*.mc.example.com` A 记录）不能替代动态记录——通配符记录在 DNS 协议层本身可行，但一条静态泛记录无法为每个实例给出不同的 SRV 端口/目标，「免端口直连」仍必须逐实例写动态 SRV。

### 5.7 与 minecraft-server 插件数据联动

遵守仓内跨插件 API 规则：**`mcpanel` 以 `softdepend` 消费 `minecraft-server` 的稳定 `*.api` 包（provided 编译），单向依赖保证无环**；`minecraft-server` 不可用时面板全部功能独立可用，联动 UI 显式降级隐藏。

- **实例绑定子服**：实例表单提供「绑定子服」选择器，选项来自 `minecraft-server` api 包的分页查询接口（禁止手输 ID）。绑定关系存 `instances.mcServerId`。
- **运行时状态回传**：面板把实例状态（在线/离线、玩家数、TPS 若可得）回写 `minecraft-server`，使其公开服务器列表展示实时数据；回传走 api 包接口，带失败重试与降级（回写失败不影响实例本身）。
- **注入联动**：绑定子服后，创建/重启实例时把子服与周目标识以下发环境变量（如 `MCSERVER_ID`、`MCSERVER_TERM`），时长记录插件读取后按子服维度记账——与 §5.3 注入管线同一阶段执行。
- **联动开关**：逐实例可解绑；解绑只清绑定与状态回传，不动实例数据。

### 5.8 整合包一键开服（mrpack / CurseForge）

实例创建向导提供第三种来源（空白模板 / 整合包导入）。导入支持：上传 `.mrpack` 文件（multipart，小包直传）、粘贴 Modrinth 项目/版本链接（面板调 MR API 解析出 mrpack 下载地址）、上传 CurseForge 格式整合包 zip。

**解析与过滤（面板侧）**：

- mrpack = zip（`modrinth.index.json` + overrides）。索引给出 `dependencies`（MC 版本、加载器及版本）与 `files[]`（下载地址、sha512/sha1、size、`env:{client,server}`）。
- **服务端过滤**：`env.server == "unsupported"` 的文件直接剔除（纯客户端模组如光影/小地图）；`server: "optional"` 默认保留、导入向导里可逐项取消；overrides 目录**遵官方语义**：`overrides/` 全量应用（含服务端配置），`server-overrides/` 仅服务端应用，`client-overrides/` 服务端安装时跳过。CurseForge manifest 无 client/server 标注，按 CF API 返回的 environment 信息过滤，无法判定的文件列入导入结果供管理员确认，不静默安装。
- CurseForge zip：`manifest.json` 列出 `{projectID, fileID}`，面板经 CF API 逐个换下载地址（API key 由管理员在设置页配置，密文存储、只写不读、回显 configured 标记）；**作者禁止分发的文件 API 返回空地址**——跳过并在导入结果里给出需手动上传的清单。

**安装计划下发（节点执行）**：面板产出安装计划 `[{url, path, sha512, size}]` 发给节点，节点并发下载 + 哈希校验 + `task.progress` 事件汇报进度；文件直连 CDN 不绕面板。下载源可配镜像（如 BMCLAPI），适配国内节点网络。

**核心选择链（优先插件核心，失败回退官方核心）**：

1. 静态分析定候选链：包内为纯插件（文件进 `plugins/`、无加载器模组）→ `[paper, purpur]`；声明 forge/fabric/neoforge/quilt 且有 `mods/` 文件 → 插件核心不可行，直接 `[声明的加载器]`；模糊情况 → `[paper, 声明的加载器]`。
2. **运行时回退兜底**：节点启动后监听成功标记（控制台出现 `Done (` 且进程存活，超时默认 180s 可配）；启动失败/超时 → 自动按候选链换下一个核心重建启动，实例记录当前核心与回退历史，UI 通知管理员。
3. 落在插件核心上时，§5.3 注入管线正常生效；落在加载器核心（fabric/forge/neoforge 等）上时两类注入区别对待：**时长插件是 Bukkit 系插件，自动跳过**并在实例页标注原因；**authlib-injector 是 JVM `-javaagent`，与 Bukkit 插件体系无关，加载器核心仍可注入**（除非模板显式关闭）。
4. 各核心下载源：Paper/Purpur 官方 API、Fabric meta、Forge/NeoForge maven installer（`--installServer`）、vanilla 走 piston-meta；全部可配镜像。

**后续更新**：实例记录整合包来源与版本（`modpack:{source, projectId, versionId}`），可「检查更新」→ 拉新版本索引与本地文件做 diff（删移除项、下新增项、保留用户后加的文件与世界），v1 只做安装，更新 diff 排 v2。

### 5.9 启动器 P2P 直连（玩家侧传输层，M6）

定位：**叠加在公网暴露阶梯（§3.7）之上的玩家侧可选传输**，仅 YMCL 启动器用户可用，vanilla 玩家仍走 L0-L3 公网地址。两个核心价值：L3 中继节点的带宽/延迟卸载（frps 流量是面板的持续成本）；**零公网暴露的私密服**——实例不开任何公网映射、仅接受启动器 P2P 接入，扫描与 DDoS 面最小（前提为 P2P 链路对目标玩家可用；打洞失败回落中继时流量仍经入口机，仅保留 frp 端口暴露面）。

**形态**：`mcpanel-p2p` Go 库两端复用——节点内嵌，启动器侧编译为 sidecar 二进制（Windows/Linux/macOS，与启动器框架无关，localhost IPC）。玩家在启动器游戏列表点「连接」→ sidecar 监听 `127.0.0.1:随机端口` → MC 客户端连本地端口 → 流量经打洞 UDP + QUIC（TLS 1.3 自带加密与多路复用）到节点 → 节点转发进实例容器；基岩版 UDP 天然适配。**打洞失败（对称型 NAT/校园网/蜂窝）透明回落 frp 中继**——玩家体验统一：永远连本地端口，底层路径不可见；但打洞成功率有上限，且回落路径仍消耗入口机中继带宽——**P2P 不改变 L3 中继的成本下限，也不是所有 NAT 环境都能打洞直连**。

**信令（面板即 rendezvous，零新增基础设施）**：节点侧走既有 WSS 控制信道；启动器侧走既有 OAuth 登录态 + 插件玩家侧端点 `/me/p2p/**`（挂载 `/api/plugins/mcpanel/me/p2p/**`，遵守 `/me/**` 用户面强制规则，自行校验 OAuth principal，M6 才引入，不破 v1 纯 admin 约束；信令下行用 SSE，P1 WS 扩展落地后升级）。流程：启动器请求连接实例 X → 面板校验（实例开了 P2P 开关 + 玩家在白名单内）→ 签发短时 `rendezvousTicket`（绑定 userId/instanceId/过期时间/速率档）→ 面板经 WSS 向节点发 `p2p.session.open` → 双方 ICE 候选经 `p2p.signal` 由面板中继转发 → 打洞建立，或超时透明落中继。

**加密**：QUIC 即 TLS 1.3；节点公钥由面板在信令中背书（信令通道本身已认证，无 MITM 空间），每会话 ECDHE 前向保密。

**防滥用（设计红线）**：

1. **目的端钉死**：启动器侧只监听 `127.0.0.1`，节点侧只允许 dial 票据绑定的实例端口——协议上就无法被当成通用代理/翻墙/自由组网工具。
2. **票据授权**：面板按 `(userId, instanceId, 过期时间, 速率档)` 签发；实例级 P2P 开关 + 可选玩家白名单。
3. **限额**：每用户/每实例带宽 token bucket 与并发会话上限，agent 本地执行、用量回传面板；管理员在设置页配全局档位。
4. **拓扑收敛**：只做启动器↔节点（节点是可信基础设施），不做玩家↔玩家 P2P。
5. **治理**：会话审计日志（谁/何时/哪个实例/流量量）、管理员一键断会话并吊销票据；信令端点限流；agent 不开任何公网监听端口（无反射放大面）。

**排期与验证**：进主线 M6。先做 spike——两台不同 NAT 后的机器实测打洞成功率（家宽经验值 70-85%）与回落中继的透明性；MC Java 握手 hostname 变为 localhost 的兼容性（反 VPN/反代类插件）一并验证；spike 不达标则降级为「启动器统一本地入口 + 底层纯中继」，保留 UX、砍掉打洞。

### 5.10 租户下发与性能配额（M7，可开关）

总开关 `tenancy.enabled`（设置页）：关闭 = 现状（全局管理员直管）；开启后出现租户实体与数据范围校验（§2）。**租户绑定宿主部门或角色**——SPI `PluginUserService.listDepartments(keyword)` / `listRoles(userId)` / `listDepartments(userId)` 现成可用，**无需新 SPI 扩展**；成员关系实时解析、不复制清单，`adminUserIds[]` 支持绑定之外个别指定租户管理员。

- 实体：`tenant {id, name, bindType: dept|role, bindId, adminUserIds[], status}`、`tenant_quota {tenantId, poolTag, maxInstances, maxCpuMillis, maxMemoryMb, maxDiskMb, expiresAt?}`。
- 资源分配两种模式可混合：**专属节点**（`node.tenantIds[]`，整台机器划给某租户）；**配额池**（节点打 `poolTags[]` 进共享池，租户配额挂在池标签上，创建实例不指定节点时由面板在池内 first-fit 调度，按内存余量选节点）。
- 配额口径：实例登记 cpu/mem/disk 规格，租户用量 = 其下运行实例规格求和；创建/改配/启动前置校验，超限拒绝并提示剩余量。
- 端口池同步按租户收口：租户实例只能从被分配节点/池的端口区间分配。

**性能租赁（钱包软联动，softdepend `yudream-wallet`，子开关 `tenancy.billing`）**：配额可挂为商品包 `quotaPackage {quota..., priceAsset, priceAmount, durationDays}`；租户管理员在租户页用钱包余额购买/续费——`PluginWalletService.debit`，`businessNo = mcpanel:quota:{orderId}` 保证幂等；到期未续费配额冻结（实例可停不可启，宽限期可配，**不删数据**）。钱包插件未安装时计费整体隐藏，退化为管理员手工分配配额。

### 5.11 节点贡献（玩家侧，M8）

玩家把闲置机器接入面板成为**受控贡献节点**：玩家面（`/me/contributions/**`，挂载 `/api/plugins/mcpanel/me/contributions/**`）提交申请（机器配置、带宽、可在线时段）→ 管理员审核（审核可关，设置项）→ 发放绑定 `ownerUserId` 的专属 enrollToken（内含资源上限）→ 玩家一行 `docker run` 接入。授权矩阵按宿主规范全覆盖：玩家只能看/管自己贡献的节点，审核与管理在 `/admin/**`。

**信任分级（核心设计）**：贡献节点 = **半可信**——owner 持有机器 root，可窥探其上实例的文件与内存，且**节点侧的资源限额执行与 stats 上报均不可信**。因此：实例页明确标注「运行在贡献节点」；实例可声明 `nodeTrust: platform|any`（默认 `platform`，正式/敏感实例不落贡献节点）；平台级秘密（authlib 密钥等）从不下发贡献节点；enroll 时登记资源上限（maxInstances/maxCpu/maxMem/maxDisk）——节点侧容器配额只是依赖 owner 配合的**软保证**，面板唯一可执行的硬约束是自身调度决策（DB 内实例数/规格求和，只把不超过限额的实例调度上去）；stats 合理性校验（上报 256 核之类的离谱数据直接拒）是防明显造假的启发式，**不构成安全边界**。

**统一分配**：贡献节点默认进 `contributed` 池，可再被打 poolTag 参与租户配额调度（§5.10）；owner 可选「独占/共享」——独占则只承载 owner 获授权的实例。贡献节点多在 NAT 后：默认 L3 frp 中继；M6 之后可标记「仅启动器 P2P 接入」，零公网暴露、零中继带宽成本。

**激励（软）**：钱包在线且 `contribution.reward` 开启时，按在线时长/承载实例量/实际流量（节点 stats + P2P 会话审计）周期结算 `credit` 给 owner，费率管理员可配；钱包缺失只记账目不发币。

**退出**：owner 申请下线 → 实例 graceful 迁出或停止并通知实例所属管理员；管理员可强制下线/拉黑贡献者；节点离线超 N 天自动标记（其下实例状态 unknown 沿用 §3.4 既有行为）。

## 6. 浏览器侧（管理端页面）

路由（全部 admin）：

- `/admin/mcpanel/nodes` — 节点列表/新建（enrollToken 一次性展示）/详情（stats 实时）
- `/admin/mcpanel/instances` — 实例 FaTable（状态、节点、类型、端口、行操作）
- `/admin/mcpanel/instances/:id/console` — 控制台（SSE 输出 + 底部命令行，仿 MCSM 终端）
- `/admin/mcpanel/instances/:id/files` — 文件管理器
- `/admin/mcpanel/instances/:id/config` — 白名单配置文件编辑器 + 常用项表单
- `/admin/mcpanel/templates` — 镜像/服务端模板
- `/admin/mcpanel/tenants` — 租户与配额（绑定部门/角色、专属节点/配额池、用量、租赁订单；M7）
- `/admin/mcpanel/settings` — authlib 地址、时长插件制品地址、默认端口池、CF API key、DNS 驱动与游戏域后缀、P2P 全局开关与限额档位、租户/计费/节点贡献开关、平台管理角色
- 玩家面（M8）：「我的贡献节点」页——申请/审核状态/在线与收益（挂宿主用户中心菜单，遵循插件路由注册约定）

前端约束：只用 `@yudream/plugin-sdk` + `@yudream/components`，SSE 用 fetch-stream 带 Authorization；控制台渲染注意 ANSI 转义与 XSS。

## 7. 节点守护进程（mcpanel-node）

- Go 单二进制 + Docker Engine API；**M1 实际采用 Go 最小只读 Docker Engine HTTP adapter**（Docker SDK 与 frp 的 genproto 依赖冲突，已批准；M2 再评估换 SDK）；配置 `config.yaml`（panel 地址、enrollToken/持久化凭证、数据目录、FTP 镜像名）。
- 职责：WSS 服务端（`/control`，面板为客户端）、Docker 容器生命周期、日志流转发（docker logs follow → 聚合帧）、文件 API（限制在实例 volume 内，路径防逃逸）、sidecar FTP、端口探测、镜像拉取。
- 自身也容器化部署，挂载 `/var/run/docker.sock` 与数据卷；要求 Docker ≥ 24。
- 安全：文件 API 根目录钉死在实例 volume；拒绝 `..` 与绝对路径；配置重写只碰模板白名单文件。

## 8. 阶段计划

| 里程碑 | 内容 | 验收 |
| --- | --- | --- |
| M1 协议与骨架 | 协议 v1 冻结（`docs/mc-panel-protocol-v1.md`）；node 仓初始化（enroll/握手认证/hello/stats/docker 封装）；面板节点管理页。**M1a 直连**（验收基线）：直连节点 enroll→上线→stats 流；**M1b NAT**：frps 配套服务 + 节点内嵌 frpc + 节点专属代理凭据（实验性，专属 proxy 授权未实现前不在生产启用） | M1a：直连节点上线可见、stats 流正常、拔线 offline、重连对账；M1b：NAT 节点经隧道上线——**仅真实多机可验收，未执行前 M1b 不得标注完成，也不得宣称"M1 完整"** |
| M2 实例与控制台 | 实例 CRUD、启停、SSE 控制台、端口池+配置重写、L3 游戏端口 frp 代理、NAT 分级探测（UPnP/IPv6，L1/L2） | 直连与中继节点各建一个 paper 实例全程跑通、玩家可连；UPnP 可用的节点自动免中继 |
| M3 文件与 FTP | 文件管理器、票据通道、一键 FTP | 上传/下载/编辑/压缩；FTP 2h 自毁 |
| M4 模板与注入 | 镜像/服务端模板（Java 系+代理+基岩+通用控制台应用）、authlib、时长插件、实例域名自动分配（§5.6）、整合包一键开服（§5.8） | 四类模板建服跑通；MC 实例自动注入并可登录验证服；开域名的实例玩家免端口直连；mrpack 导入→剔除客户端模组→插件核心启动成功，失败自动回退官方核心 |
| M5 联动与打磨 | `minecraft-server` 数据联动（§5.7）、L3' MC 虚拟主机代理、计划任务、备份、监控大盘、（可选）用户端 | 绑定子服后状态回传与按子服记账生效；中继 MC Java 实例走 25565 单端口免 SRV |
| M6 启动器 P2P | `mcpanel-p2p` 库（打洞 + QUIC + 限额）、面板信令与票据签发、启动器 sidecar 集成、私密服模式（仅 P2P 接入、零公网映射）（§5.9） | spike 实测双 NAT 打洞成功率达标且失败透明回落中继；目的端钉死、限额与审计生效；私密服实例无公网映射可被启动器连入 |
| M7 多租户与配额 | 租户实体（部门/角色绑定）、数据范围校验、专属节点 + 配额池调度、端口池租户收口、钱包租赁软联动（§5.10） | 开关开启后租户管理员只见本租户数据；配额超限拒绝；钱包在线时购买/续费/到期冻结全链路跑通，钱包缺失时降级为手工分配 |
| M8 节点贡献 | 玩家申请/审核/专属 enroll、半可信分级与资源硬限、contributed 池统一调度、激励结算（§5.11） | 玩家在 NAT 机器一行 docker run 接入并被调度实例；独占/共享模式行为正确；owner 下线 graceful 迁出 |

## 9. 待决策点

全部已拍板：

1. **节点连通形态**：直连 + frp 隧道两种都支持（见 §3.7）；frps v1 与面板同机部署，后续可按需加独立入口机。
2. **节点技术栈**：Go（单二进制、frp 库内嵌）。
3. **插件 code**：`mcpanel`（后端 `yudream-plugin-mcpanel`、前端 `plugin-mcpanel`），与 `minecraft-server` 并存。
4. **实例范围**：v1 全覆盖——MC Java 系 + Velocity/Bungee + 基岩版（UDP 端口池）+ 通用控制台应用（见 §5.2）。
5. **与 `minecraft-server` 插件关系**：数据联动，`mcpanel` softdepend 消费其 `*.api` 包（见 §5.7）。
6. **SPI 扩展**：P1（插件 WebSocket 端点）+ P2（流式请求/响应体）立项，进宿主排期；宿主改动范围限定 SPI 模块 + 插件桥接层，不侵入无关代码。
7. **实例域名分配**：启用，可选开关；DNS 驱动按 §5.6 三选一，推荐子域 NS 委托 + 面板内嵌权威 DNS。
8. **CF API key**：管理员在设置页配置（密文存储、只写不读）；未配置时 CF 导入入口降级隐藏。
9. **启动器 P2P**：进主线排 M6（见 §5.9）；仅限 YMCL 启动器用户，不改变 vanilla 玩家的 L0-L3 公网阶梯；防滥用四件套 = 目的端钉死 + 面板票据授权 + 带宽/并发限额 + 审计吊销。
10. **租户下发**：启用，总开关 `tenancy.enabled`（§5.10）；租户绑定宿主部门或角色（SPI 现成，无需新扩展）；分配模式 = 专属节点 + 配额池双轨；性能租赁 softdepend `yudream-wallet`（子开关可关，缺失降级手工分配）。
11. **节点贡献**：启用，排 M8（§5.11）；管理员审核制（可关）；贡献节点半可信分级——默认不承载 `nodeTrust: platform` 实例、不下发平台秘密、资源硬限；统一进 contributed 池调度；激励走钱包 `credit`（软）。

## 10. 宿主 SPI 现状与扩展计划

2026-09-19 核实（SPI **2.31.0** = 本仓当前编译版本，公开 jar 已含 `PluginSecretStore`、`PluginSseStream`、`PluginHttpPart`、`PluginDocumentStore`，直接可用；宿主 2.32.0 已发布）。**P1 插件 WebSocket / P2 流式体实现中但新版本尚未发布——M1 只依赖已发布 SPI**：M1 控制信道为插件侧出站 WS 客户端 + 节点侧 WS 服务端，不需要宿主 WebSocket 端点。

> 2026-09-27 更新：本仓编译版本已升级至 SPI **2.33.0**（`registerStreamingHttpHandler` / `registerWebSocketHandler` 与流式体契约随该版本发布），前端契约同步至 `@yudream/plugin-sdk` 1.8.0（`sdk.backup.targets()`）。上文「尚未发布」只描述 2026-09-19 时点。

| 能力 | 现状 | 面板对策 |
| --- | --- | --- |
| SSE | ✅ `PluginSseStream` 桥接 SseEmitter，30min 超时、Last-Event-ID 续传 | 控制台输出/节点事件直用，零扩展 |
| multipart 上传 | ✅ 2.31.0 已发布 `parts` 契约；⚠️ 宿主侧 `readAllBytes()` 全量进堆 | 中小文件直传；大文件走文件存储中转 |
| 二进制响应 | ⚠️ byte[] 全量缓冲（ContentCachingResponseWrapper） | 小下载直出；大下载落文件存储给平台 URL |
| 插件 WebSocket | ❌ 无（宿主有 WS 基础设施，仅 AI AG-UI 专用） | v1 SSE+POST；P1 扩展后升级 |
| 权限/principal | ✅ `principal()` 用户+权限全量透传 | 端点自行 `requirePermission` |

扩展项（宿主仓 `yudream-plugins/yudream-plugin-spi` + 桥接层，先发 Nexus 再升本仓根 pom。**P1/P2 已立项**；改动范围限定 SPI 模块与插件桥接层，不侵入无关代码）：

- **P1 插件 WebSocket 端点**：宿主新增 `PluginWebSocketDispatcher`（挂在 `/api/plugins/{code}/ws/**`，复用握手期 sa-token/Authorization 解析为 `PluginPrincipal`）；SPI 新增 `PluginWebSocketHandler`（onOpen/onMessage(text|binary)/onClose/onError，session 支持发送与关闭）。收益：控制台真双向、输入零 HTTP 往返、终端 resize/二进制帧；且是通用能力（AI、机器人调试等后续插件可用）。不阻塞 M1–M3，控制台先用 SSE+POST 上线，扩展发布后无缝切换（前端同一 composable 换传输层）。
- **P2 流式体扩展**（优化项，不立项也能跑）：`PluginHttpPart` 增加流式来源（InputStream/临时文件）替代全量 byte[]；响应支持 InputStream body 流式输出。落地后大文件上传下载去掉文件存储中转。
- 明确**不扩展**：SSE（够用）、定时任务 SPI（插件自建 ExecutorService + onDispose 是官方模式）、文件存储 list/range（面板用不上）。
