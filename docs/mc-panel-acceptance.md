# MC 面板实施验收跟踪

> 用途：本表是 MC 面板各里程碑「实现完成」的唯一依据。**设计文档定稿、协议冻结、代码骨架合入都不等于验收通过**；只有本表对应条目勾选（附可复现验收记录）才算完成。
>
> 契约源：`docs/mc-panel-design.md`（v0.7.1 定点修订）、`docs/mc-panel-protocol-v1.md`（wire 契约冻结稿，§9 为 M2+ 业务操作扩展）。
>
> 状态（2026-09-19 第二轮）：**0.2.0（M2/M3 全量 + M4/M7/M8 骨架）实现完成**——面板 63 tests 全过、前端 typecheck/build 通过、节点 Go 12 包全过；**面板业务全链路真实闭环已验证**（真实 Java 应用服务 → 真实 Go 节点 → 真实 Docker：建实例含端口分配 → 拉镜像 → 启动 running → 控制台输出 → 文件写读列 → 备份 → 停止删除并回收端口）。M1 整体仍未验收：真实宿主 UI、HTTPS 完整 enroll E2E、真实 NAT 未验证；生产 enroll 前置未满足。宿主测试 98 全过（同前轮）。本轮不发布。

## 0. 依赖与前置

| 依赖 | 状态 | 结论 |
| --- | --- | --- |
| SPI 2.31.0（本仓编译版本） | 已发布，公开 jar 含 `PluginSecretStore` / `PluginSseStream` / `PluginHttpPart` / `PluginDocumentStore` | M1 可用，无阻塞 |
| SPI 2.33.0（P1/P2 载体） | **未发布** | 发布前不得消费；不影响 M1 |
| 宿主 P1（插件 WebSocket 端点） | **本地实现验证通过**（host 98 内含），新版本**未发布未部署** | 非 M1 依赖（M1 控制信道 = 面板出站 WS 客户端 + 节点侧 WS 服务端）；发布前不得消费 |
| 宿主 P2（流式请求/响应体） | **本地实现验证通过**（P1-final 14/0/0），新版本**未发布未部署** | 非 M1 依赖；大文件路径 M3 按设计稿 §5.5 分层方案执行 |
| Docker SDK（Go，node 侧） | 与 frp genproto 依赖冲突 | **M1 采用 Go 最小只读 Docker Engine HTTP adapter**（已批准）；M2 再评估换 SDK |

生产 enroll 前置（未满足前 M1a 只能验收至"测试环境可用"，不得生产启用）：

- [ ] 宿主通用日志脱敏修复已生效：enrollToken/nodeSecret 不得出现在 `api_log`/syslog 等宿主日志。修复代码已完成，**但测试尚未重启宿主验证生效，前置仍未满足**（无新 SPI 依赖）

## 0.1 M1 0.1.0 构建证据（最终 artifact 已冻结）

- **最终插件 artifact**：`yudream-plugins/yudream-plugin-mcpanel/target/yudream-plugin-mcpanel-0.1.0.jar`——140856B，sha256 `e7ab0aff49800f2603ddbe123c5fa12419752f8e23c01a87463a1c4dffa2a853`（冻结，不再重打；主审已独立 zip/hash 复核）。
- 后端：clean 全量 mvn **58 tests**（0 fail / 0 error / 0 skip）；新增 40 个 Java 主源码文件 + 12 个 test/fixture 文件（均为 M1 范围）。分页上限修复已验证：>200 边界两条——255 节点按 status 过滤（210 enrolling + 45 online）全页 total 正确；250 节点（200 启用 + 50 停用）restore 恰好 200 次调用；App internal 100 / restore 100 与 query cap 一致，repo 直连 SPI 200 保持。
- 前端 `plugin-mcpanel`：15 个 SSE 测试自然退出全过；typecheck/build 通过。JAR 内 manifest 为两条：index（remoteEntry.js）+ style.css（独立样式 entry，`@PluginFrontend(styles={"style.css"})` 显式声明，remoteEntry 条目不含 css 数组）；无 assets 目录（当前产物无图片 chunks，属合法）。**未在真实宿主 UI 验证**。
- 节点 `mcpanel-node` 0.1.0：Go 10 包 build/vet/test 全过；真实 Docker 29.2.1 只读 stats；WSS pin/hello/stats 冒烟；linux/windows 二进制与镜像 version/fingerprint 冒烟（证据见节点仓 `docs/acceptance.md`）。
- 跨 Java/Go 5 场景（严格分类）：**真实 transport** ① happy hello + stats 5s 周期；② 进程断开 → onClose 立即 offline；③ 同凭据重启 → SESSION_CHANGED_OK；④ 错 pin → Java 侧 TLS 拒绝。**fault 模拟（非真实 Go）** ⑤ 15s 静默计时（gap 15342ms / close 1000）。
- 未测试：HTTPS 完整 enroll E2E、真实宿主 UI、真实 NAT。
- CI：个人越权扫描路径感知（仅豁免 `src/pages/admin/**`，并拒该目录 `/me`、`/my` 字面），fixture 三向自测通过；conformance 实跑 **mcpanel 0 发现（第二轮复核，文件页原生 textarea/file input 已替换为 FaTextarea/FaFileUpload）**、6 条预存保留（mc-wiki select、ymcl-adapter input、neco-pixel input、mc-news×2 FaTable 容器、material FaTable 容器）；registry 步在隔离容器 mvn 3.9.16/JDK21 exit 0（SPI 2.31.0 解析成功）。
- Raw descriptor：**三方一致**（主审已独立复核 JAR 内嵌 notes 为 565 字符单行）。生成/核对命令（在仓库根执行，`OUT` 为临时目录；`SRC_STORE` 绝对路径经 env 传入，规避 `cd $OUT` 后相对路径失效）：

```sh
ROOT_DIR=$(pwd); . ci/lib/plugin-store-catalog.sh; plugin_store_select_python
OUT=$(mktemp -d)
SRC_STORE="$(pwd)/yudream-plugins/yudream-plugin-mcpanel/src/main/resources/store.json"
JAR="yudream-plugins/yudream-plugin-mcpanel/target/yudream-plugin-mcpanel-0.1.0.jar"
printf '%s\n' "$JAR" > "$OUT/jars.txt"
unzip -p "$JAR" store.json > "$OUT/jar-store.json"
plugin_store_write_catalog "$OUT" "https://nexus.yudream.online/repository/maven-public" \
  "https://nexus.yudream.online/repository/plugin-store-releases" "$OUT/jars.txt"
# 核对（cd 进 OUT 用相对路径，避免 Windows python 对 /tmp 的映射差异）：
cd "$OUT" && SRC_STORE="$SRC_STORE" "$PLUGIN_STORE_PYTHON" - <<'PY'
import json, os
d = json.load(open('plugins/mcpanel/versions/0.1.0.json', encoding='utf-8'))
j = json.load(open('jar-store.json', encoding='utf-8'))
s = json.load(open(os.environ['SRC_STORE'], encoding='utf-8'))
assert j == s, 'JAR-embedded store.json != src store.json'
assert d['plugin']['releaseNotes'] == j['releaseNotes'] == s['releaseNotes']
assert d['plugin']['version'] == d['releaseVersion'] == '0.1.0'
assert d['jar']['sha256'] == 'e7ab0aff49800f2603ddbe123c5fa12419752f8e23c01a87463a1c4dffa2a853'
print('three-way OK', d['plugin']['version'], d['releaseVersion'])
PY
```

实测结果（2026-09-19）：JAR 内嵌 store.json == 源码 store.json（cmp IDENTICAL）；descriptor releaseNotes == JAR 内嵌 == 源码；`plugin.version`/`releaseVersion`=0.1.0；descriptor jar.sha256 == 最终实测 sha256；坐标 `online.yudream.plugins:yudream-plugin-mcpanel:0.1.0:jar`。JAR 前端资产口径：manifest 条目 = index + style.css（如上），无 assets 目录属合法；store.json 的 icon 为 iconify id、无 screenshots，无文件型 resources 要求。

## 0.1b 0.2.0（M2/M3 全量 + M4/M7/M8 骨架）构建证据

- **插件 artifact（最终冻结）**：`yudream-plugins/yudream-plugin-mcpanel/target/yudream-plugin-mcpanel-0.2.0.jar`——262716B，sha256 `f6f71a77b7feeb806c89cddcad5bbfa23f55bb22eb868c8587dd63d7816d47f7`（M5 回传并入后的最终值，269510B；主审独立 zip/hash 复核：plugin.yml version=0.2.0、store.json 单行 notes 837 字符且 JAR 内嵌==源码、META-INF/yudream-plugin/frontend/mcpanel/{remoteEntry.js,manifest.json,style.css} 齐）。
- 后端：**63 tests**（0 fail / 0 error / 0 skip，10 测试类）。新增 McpanelInstanceAppServiceTest 5 用例：TCP/UDP 端口分配、幂等键载荷（ik=panel-create-{id}）下发、创建失败回收端口并删记录、非法规格先于节点调用拒绝、删除释放端口。
- 协议扩展：protocol v1 **§9 业务操作契约**（instance.*/file.*/backup.*/image.pull/install.run/task.get/port.check，含幂等键 16-128、operation.conflict/operation.uncertain、分块上传 96KiB、备份 zip-slip 禁止、容器安全基线）。
- 节点 `mcpanel-node`：Go **12 包** build/vet/test 全过（新增 dockerx 写面/instances/fileutil/tasks 四包 + 各自测试）。真实 Docker 29.2.1 上的**节点级业务冒烟**（`scripts/business-smoke.sh`）全绿：hello（caps 33 项）→ image.pull → instance.create（幂等重放）→ start running → output.read 游标 → instance.command（Docker Desktop sock 代理不转发劫持 stdin，sent=true 确认 + SKIP 注记；宿主侧 exec -i 已证守护进程可用）→ file write/read/list → backup.create → stop → purge。双平台二进制已重建（linux-amd64 17.2MB / windows-amd64.exe 17.4MB，trimpath -s -w）。
- **面板业务全链路真实闭环**（`InstanceBusinessHarness`，宿主 JDK21 直跑 target/classes）：节点经容器发布 7701 端口（pin 拨号）→ 上线 caps 33 项 → image.pull 任务轮询 done → `McpanelInstanceAppService.create`（端口分配 hostPort=25565/tcp + ik 下发）→ start running → 控制台输出含 tick- 增量游标 → 文件写/读/列（panel/harness.txt 13B）→ backup.create → stop → delete（端口全释放）。输出末行 `PANEL BUSINESS SMOKE OK`。
- 前端：typecheck 通过（0 错误）；build 通过（remoteEntry.js 117KB ESM + style.css + manifest.json）。新页面：Instances（FaTable 后端分页 + 行内启停/强杀/控制台/文件/编辑/删除 + 节点筛选）、InstanceDetail（信息 + 控制台 2s 轮询 + 单行命令 + 备份表）、InstanceFiles（目录导航/在线编辑 base64/上传/新建/重命名/删除/下载）、Templates（双形态模板 CRUD）、Settings（注入/DNS/租户/贡献/P2P 七节表单）。
- 骨架范围（如实标注未闭环）：M4 模板实体与展开逻辑齐但**核心运行时回退链未实现**（静态候选链已生成）；mrpack 解析与过滤已实现（env.server=unsupported 剔除、三目录 overrides 语义）但 **CurseForge API 换址未接**（key 存储已备）；M7 租户 scope 当前恒放行（实体/配额计算器已就位，`tenancy.enabled` 打开即收紧的前置接线完成）；M8 贡献申请/撤销/审核全链路已实现（含每人上限与限额落档）；M6 P2P 仅票据签发端点（节点 caps 无 p2p.*，如实返回能力缺口）。

## 0.2 本轮交付边界（结论摘要）

| 类别 | 状态 |
| --- | --- |
| M1a 代码与部分测试证据 | 已落地：后端 0.1.0（40 主源码 + 12 test/fixture，**58 tests** 过，含分页 >200 边界回归）、前端（15 SSE tests、typecheck/build、dist manifest = index + 独立 style.css）、节点 0.1.0（Go 10 包 + vet、真实 Docker 只读 stats、WSS pin/hello/stats smoke、双平台二进制与镜像 smoke）；跨 Java/Go 真实 transport 4 场景 + fault 模拟 1 场景；未验：真实宿主 UI、HTTPS 完整 enroll E2E、真实 NAT。本地可跑的 unit/package 全部如实记录，无遮蔽 |
| 宿主侧 | **98 tests 全过**（0 failures / 0 errors / 0 skipped，主审解析 live target XML）：streaming 34（21+13）/ WS interfaces 43（8+8+6+6+9+6）/ registry 6 / logs 15；P1-final 14/0/0 与 package BUILD SUCCESS——**定向回归，非全宿主 test suite** |
| M1b | 实验性：内嵌 frpc 单机隧道证据（进程内真实 frps + 端到端 TLS）；专属 proxy 授权控制面未集成；真实 NAT 未验证；不在生产启用 |
| M2–M8 | 未实施 |
| 宿主 SPI 2.33 / 日志脱敏 | P1/P2 载体 2.33.0 未发布、未部署，M1 未消费；日志脱敏修复代码完成但宿主未重启验证生效，生产 enroll 前置未满足 |
| 静态门禁 | readiness/conformance 实跑：mcpanel 相关 0 发现；剩余 6 条预存违规属其他插件（历史遗留，保留未扩修）。`verify-core-maven-registry.sh` 已在隔离 Maven 3.9.16 / JDK 21 容器实跑通过（exit 0，SPI 2.31.0 Nexus 解析成功；此前 exit 127 仅因本机 shell 无 mvn 未执行，非校验失败）。仓库默认 `.m2/verify-repository` 专属 repo 由脚本清并重建（本次 614 个缓存文件，已 gitignore），用户 `.m2` 未触碰。**整体 readiness 仍因该 6 条预存 conformance 失败不标绿** |
| Raw descriptor | **三方一致（最终 JAR）**：JAR 内嵌 store.json == 源码 store.json（cmp IDENTICAL）== descriptor releaseNotes；version/releaseVersion/sha256/坐标核对通过（§0.1 命令与实测结果）。0.2.0 的 descriptor 三方核对待发布前复核（§0.1b hash 已独立核验 JAR 本体） |
| **0.2.0 第二轮交付** | M2/M3 全量（63 tests + 真实 Docker 全链路 PANEL BUSINESS SMOKE OK）+ M4/M6/M7/M8 骨架（§0.1b）；前端 7 页面 typecheck/build 过；节点 Go 12 包全过 + 双平台二进制重建。未发布 |

**结论：本轮交付 M1a 的代码与本地可验证证据，不声称完整面板全部完成。** 真实宿主 UI 与多 NAT 属外部环境项，待环境就绪单独验收；这不影响、也不掩盖上述本地 unit/package 证据。

## 1. M1a 直连（验收基线；实现完成·证据已注记·整体未验收）

前置：一台可跑 Docker 的机器（可与面板同机，L0 localhost 场景）；JDK 21 / Maven 3.9+ / Node 22+ / pnpm / Go。

- [x] node 仓骨架：Go 单二进制，config（panel 地址、enrollToken、数据目录），`fingerprint` 子命令输出叶证书 SHA-256（证据：mcpanel-node 仓 acceptance——`internal/certutil`/`internal/config`/`internal/credential` 单测、镜像 `version` 输出 0.1.0、fingerprint smoke；面板侧已随 58 tests 实现）
- [ ] enroll：`POST /node/bootstrap` 一次性 token（10min/单消费/常量时间比对/限速/审计），raw JSON 响应；pin 相等校验，pin 不匹配拒绝**且不消费 token**（纠正后同 token 可重试），已消费 token 复用回 409（**单元已过，真实 HTTP 未验**：Go client 单测 + 后端 58 tests 含 bootstrap 兑换语义；HTTPS 完整 enroll E2E 未测，未勾）
- [ ] 禁用节点拒绝注册：`409 node-disabled`（协议 §2/§6 已录；注册修复与测试待，未验）
- [ ] enrollment 重签：`POST /admin/nodes/{id}/enrollment` 未注册节点可重签；已注册节点 409（M1 无 rekey，不得以 re-enroll 冒充轮换）（**单元已过，真实 HTTP 未验**：后端 handler tests 覆盖，未勾）
- [x] 控制信道（节点侧）：节点 WSS 服务端 `/control`，`Authorization: Bearer` + `X-Mcpanel-Node-Id` 握手认证；凭据不进 URL；失败限速+审计+泛化 401（证据：mcpanel-node 仓 acceptance——`internal/wsserver` 握手 401/429/hello 一次/立即+5s stats seq/binary 1003/超帧 1009/会话替换 4000/Shutdown 关会话；wssprobe 真实 TLS+pin+双握手头完成 hello req/res 并收到 seq=0 stats；日志 secret `[redacted]`。跨 Java/Go 已由真实 transport 场景①-④验证，见 §0.1）
- [x] 信封编解码：req/res/err/evt、帧 ≤256KB、未知字段忽略、未知方法回 `proto.unknownMethod`（证据：Go 侧 `internal/proto` 单测 + 跨 Java/Go 真实 transport 场景①-④实帧互通；fault 场景⑤不涉及本项）
- [x] `node.hello`：req `{panelVersion}` → res `{nodeId, sessionId, hostname, agentVersion, caps, dockerVersion?}`（证据：真实 Java → 真实 Go 场景①；wssprobe 冒烟回显 nodeId/sessionId/caps/dockerVersion=真实 daemon 版本）
- [x] `evt node.stats`：hello 后立即一帧、此后 5s 周期；字段与单位按 protocol v1 §5.2（证据：`internal/stats` 单测 + 真实 Docker 29.2.1 socket 只读 stats 用例 + 跨 Java/Go 真实 transport 场景① 5s 周期验证）
- [ ] 面板插件：节点 CRUD（manage 权限）、enrollToken 一次性展示、节点页 stats SSE 实时展示（后端 58 tests 覆盖接口层；**真实宿主 UI 未验，未勾**）
- [x] offline 判定（两级语义分开验证）：进程断开 → WSS onClose **立即** offline（真实 transport 场景②，不依赖静默计时）；socket 存活但 15s 无帧 → offline（**fault-agent 模拟**：gap 15342ms / close 1000，非真实 Go）；同凭据重启 → SESSION_CHANGED_OK、seq 重置（真实 transport 场景③）
- [x] 面板拨号安全：endpoint 字段 authority-only（`wss://host[:port]`，程序固定追加 `/control`）、仅 manage 可写；默认拒绝 loopback/link-local/metadata；localDevelopment 仅放宽 loopback 且仍强制 wss（无 ws 明文）（证据：错 pin → Java TLS 拒绝为真实 transport 场景④；authority-only/localDevelopment 为后端单元测试）
- [x] `nodeSecret` 经 `PluginSecretStore` 存储；日志无 token/secret（证据：后端测试 + 节点 `internal/logutil` 脱敏，节点日志 secret 显示 `[redacted]`）
- [ ] 管理端点授权矩阵：未认证拒 / 无 view 拒 / view 只读 / manage 变更；错误路径不泄露存在性（**单元已过——Maven PermissionMatrix 5 tests**；真实 HTTP 未验，未勾）
- [ ] E2E：跨语言真实 transport 已覆盖上线/stats 滚动/进程断开 offline/同凭据重启/错 pin 拒绝（场景①-④）；**HTTPS 完整 enroll E2E（token 兑换/重放 409/已注册重签 409）未测，未勾**

## 2. M1b NAT/frp（实验性；实现中·真实 NAT 未验证·未验收）

- [ ] frps docker compose（面板侧配套：控制端口、游戏代理端口段、防火墙收敛、dashboard 不对公网）
- [x] 节点内嵌 frpc（证据：`internal/frpc` TestTunnelEndToEndTLS——进程内真实 frps + 内嵌 frpc 端到端 TLS，进程级真实隧道；**compose 双网络仿真未实际执行、真实 NAT 未验，见下条**）
- [ ] bootstrap 响应 `controlPlan={mode:"frp",…}` 下发**节点专属**代理凭据（严禁复用 frps 管理员 token）（待 Java 侧，未验）
- [ ] 节点先起 frpc → 面板经隧道拨入 `/control`（先 frp 后 WSS 的顺序成立）
- [ ] 专属 proxy 授权机制实现前，M1b 标记实验性、不在生产启用
- [ ] **真实 NAT 多机验收（未执行）**：NAT 节点经隧道上线、stats 正常、断隧道 offline、隧道恢复重连。此项完成前 M1b 不得标注完成，M1 不得宣称"完整"；**专属 frp 授权控制面未集成、真实 NAT 未验证的约束对 M2+ 同样生效，任何里程碑不得把 NAT 相关项标为完成**

## 3. M2–M8 进度（2026-09-19 第二轮更新）

| 里程碑 | 范围（见设计稿 §8） | 状态 |
| --- | --- | --- |
| M2 实例与控制台 | 实例 CRUD/启停、控制台、端口池 + 配置重写 | **本地实现+真实闭环已验**（面板 63 tests + InstanceBusinessHarness 全链路：create/start/output/stop/delete + 端口分配回收 + server.properties 启动前重写在节点侧单测覆盖）；L3 游戏端口 frp 代理、NAT 分级探测（UPnP/IPv6）**未实现**（真实 NAT 项见 §4）；控制台为 2s 轮询非 SSE（P1 WS 落地后升级） |
| M3 文件与 FTP | 文件管理器、备份 | **本地实现+真实闭环已验**（文件 CRUD + 96KiB 分块上传通道 + zip 备份/恢复/删除 + zip-slip 禁止，节点单测 + harness 实测）；票据通道以控制信道分块承载（§9）；**进程内 SFTP 已实现**（实例级 ftp.open + 节点级 ftp.open.node，随机凭据 TTL≤2h、基端口+哈希偏移、前端凭据弹窗；createprobe 实测通过）；SFTP 端口段穿 NAT 属真实多机项仍未验 |
| M4 模板与注入 | 模板、注入、域名、整合包 | **骨架**：模板 CRUD/展开、mrpack 解析与服务端过滤（env.server=unsupported 剔除、三目录 overrides 语义）、安装计划 install.run；**域名自动解析已实现（三驱动）**：Cloudflare/阿里云/腾讯云 DNSPod 的 A+SRV 增删查、凭据分槽只写不读、创建时分配/删除时释放、实例域名页（同步/校验/解除），签名与业务逻辑由面板单测钉住——**真实云商调用未凭据验证**（需管理员填入子账号凭据后用「校验解析」实测）；**未闭环**：authlib/时长插件实际注入管线（下载源落盘+javaagent 前插——模板开关已备、执行未接）、CurseForge API 换址（key 存储已备）、核心运行时回退链（静态候选链已生成） |
| M5 联动与打磨 | minecraft-server 联动、L3' 代理、计划任务、备份策略、大盘 | **部分闭环**：minecraft-server 1.6.2 本地 install（notifyPanelInstanceState/minecraftPanelState 契约），面板 link.writebackState 回写 + ServerListThemeBlockProvider 面板态兜底已实现（未发布 Nexus，发布后可跨机验收）；节点级终端/文件/SFTP 已实现（createprobe 全量通过，含节点终端 evt 回显）；**性能历史采集后台常驻**（实例维度 + 节点维度 30s 采样、文档落库 24h 环形窗口；总览、节点列表、实例详情同源读历史，不再请求驱动攒点）；**单端口入口（mc-router）适配已实现**：域名按入口模式只写 A（默认端口免 SRV）、面板下发/删除路由并 1 分钟对账、设置页连通测试与手动对账，且只在入口模式实例存在时外呼——入口进程本身需单独部署，**真实 router 联调未做**（面板侧以假 router HTTP 桩覆盖）；**PROXY protocol 一键开关 + 随接入方式自动同步**（只翻已存在键：paper-global.yml/velocity.toml/config.yml，切非入口模式自动关）；**P2P 直连（M6）面板信令 + 节点 TCP 传输已实现**：会话状态机/票据/限额/候选中继/审计/管理端断流（面板 11 例单测），节点 `p2p.*` 能力 + 票据握手 + **目标钉死宿主端口** + 双向泵/限速/回收（Go 回环 9 例 + 控制信道端到端 1 例）；**UDP 打洞 + QUIC、启动器 sidecar 二进制、真实双 NAT spike 未做**（协议见 docs/mc-panel-p2p-protocol.md）；控制台 SSE 升级、L3' 自研代理未做 |
| M6 启动器 P2P | 打洞+QUIC+限额、/me/p2p 信令 | **骨架**：/me/p2p/session 票据签发（TTL 120s、并发上限、白名单校验、实例级开关）；节点 p2p.* 未实现（caps 如实不含），端点如实返回能力缺口；**打洞/QUIC/spike 未开始** |
| M7 多租户与配额 | tenancy.enabled、数据范围、配额池、钱包 | **骨架**：设置项+MembershipResolver（宿主部门/角色实时解析）+QuotaUsage 计算器+配额断言（instances/cpu/memory+过期冻结）已就位；**当前 scope 恒放行**（租户实体 CRUD 未建，接线后收紧）；钱包计费未实现（SPI 2.31 无钱包端口，仅能记账） |
| M8 节点贡献 | /me/contributions 申请/审核/限额 | **后端全链路**：玩家申请/撤销（每人数额上限）+ 管理端分页/审核 + 限额落档（maxInstances/Cpu/Mem）；**前端页面未建**（挂宿主用户中心菜单待 M8 收尾）；贡献→节点记录转化（专属 enroll）未接 |

### 3.1 终端统一轮（0.10.0，2026-09-20）

实例控制台与节点终端合并为共享终端工作区 `TerminalConsole.vue` + `useTerminalConsole.ts`（传输层保留：实例 2s 轮询 / 节点 SSE）。本轮新增，均为本地实现+测试：

- ANSI 着色渲染（`utils/ansi.ts`：SGR 8/16/256/truecolor、非 SGR/OSC 剔除；ansi.test 13 例）
- 日志级别筛选（全部/信息/警告/错误，MC log4j 前缀解析与 shell 关键词分级统一到 `utils/terminalLine.ts`）
- 渲染行数窗口（最近 200/500/1000/全部，默认 500；缓冲 2500→1800 裁剪不变）
- 滚动优化：贴底自动跟随、上翻不拽底并显示「N 行新输出」回底角标
- 命令补全下拉：历史（会话内 sessionStorage）+ 内置命令表（MC 40+/shell 60+）+ 在线玩家名，Tab 接受、↑↓ 选择、Esc 关闭（`utils/commandComplete.ts`，11 例测试）
- 实例在线玩家列表：`GET /admin/instances/{id}/players`（`ServerListPing` 无依赖实现 handshake/status/ping-pong，探测目标=节点端点主机+TCP hostPort，1.5s 超时、8s TTL 缓存，未运行/基岩版/无 TCP 映射返回原因；ServerListPingTest 以本地假服务端验证协议全流程）
- 实例标题栏/基本信息显示 TCP 端口

证据：前端 vue-tsc + vite build 通过、node:test 48 例全绿；后端 `mvn -pl yudream-plugin-mcpanel -am test` 83 例全绿；JAR 0.10.0 含 plugin.yml/remoteEntry.js/manifest.json/style.css（与 0.9.0 布局一致）。**真实实例玩家探测需真机联验**（列入 §4 同类约束：localhost 假服务端只验协议，不验真实服务端行为）。

## 4. 仅真实多机（或真实外网环境）可验收项

以下项不得用本机 localhost 或模拟冒充；未执行前对应功能只能标"未验证"：

- NAT 节点经 frp 隧道接入（M1b）；双层 NAT / CGNAT 场景探测与回落（M2）
- UPnP L1 真实路由器映射 + 公网回探；IPv6 L2 直连（M2）
- 真实玩家客户端连中继/直连实例游戏端口（M2）
- FTP 被动端口段穿 NAT（M3）
- NS 委托经公共解析器验证（含 DNSSEC 父区场景）（M4）
- P2P 双 NAT 打洞成功率与回落透明性（M6 spike）
- 贡献节点从 NAT 机器接入与 owner 下线迁移（M8）
- 广域链路断连/重连风暴（面板重启、节点重启、隧道闪断组合）

## 5. 记录纪律

- 勾选条目必须附证据：命令、日志摘录、截图或测试名；描述"基本可用"不算验收。
- 协议变更：先改 `docs/mc-panel-protocol-v1.md` 并经协调确认，再改两侧实现，本表不记录协议本身。
- 阶段完成后更新本表，同时按仓规更新插件版本与 `store.json` releaseNotes。
