# MC 面板节点协议 v1（wire 契约）

> 状态：v1 冻结稿（2026-09-19，协调者已批准）。本文件是面板后端（`yudream-plugin-mcpanel`）与节点守护进程（`mcpanel-node`，Go）的共享实现契约，两边不得各自增删字段。
>
> 范围：M1 必选部分已冻结为可实现；M2+ 方法仅列为预留名称，未实现者不得注册空实现占位。协议变更必须先改本文件并经协调确认，再改两侧代码。
>
> 设计背景见 `docs/mc-panel-design.md`；实施验收状态见 `docs/mc-panel-acceptance.md`。

## 1. 表面与信任模型

| 表面 | 认证 | 说明 |
| --- | --- | --- |
| machine bootstrap | 一次性 enrollToken | `POST /node/bootstrap`，唯一免登录机器注册端点；不属于 `/admin` 或 `/me` 表面 |
| 控制信道 | TLS（pin/PKIX）+ 握手 Bearer | 面板为 WS 客户端主动拨节点；认证只在握手层执行一次 |
| 管理端点 | 宿主权限 `plugin:mcpanel:*` | M1-M5 全部 `/admin/**` |
| 玩家端点（M6+） | OAuth principal | 必须使用 `/me/**` 命名空间 |

安全基线（强制）：

- 强制 `wss://`。证书校验二选一：节点自签 → 面板按 SHA-256 叶证书 pin（DER）校验（pinned 模式）：管理员可**预录入** pin，也可**留空**由注册（bootstrap）时节点上报的 `tlsCertSha256` **自动登记为初始 pin（TOFU，2026-09 起）**——信任锚为一次性 enrollToken（持有 token 即等同可冒充节点，无新增攻击面），已预录 pin 时仍严格相等校验、**绝不自动覆盖**；PKIX 模式（CA 签发 → 标准 PKIX + hostname 校验，节点记录无 pin）不做 pin 相等校验，也不因上报指纹改变校验模式。已注册节点的 pin 不得清空（不会再次 enroll，清空即失去钉住依据）。
- frp 隧道只做 TCP 中继，**不得终结 TLS**，否则 pin 失效。
- 凭据（enrollToken、nodeSecret）只走 body/header，禁止进 URL 与查询串；落盘 0600；面板侧存 SPI `PluginSecretStore`；禁止写日志。
- 面板出站拨号目标只能来自管理员预创建的节点记录：endpoint 字段为 **authority-only**（仅 `wss://host[:port]`，不含路径），`/control` 路径由面板程序固定追加；字段仅 `plugin:mcpanel:manage` 可写、scheme 仅允许 `wss`。目标 IP 校验：默认拒绝 loopback/link-local/metadata 地址；`localDevelopment` 开关仅放宽到**允许 loopback 目标 IP**（link-local/metadata 仍拒绝），且**仍强制 wss，不允许 ws 明文**。任何 hello/bootstrap 帧都不能改写拨号目标。
- enroll token 重签端点 `POST /admin/nodes/{id}/enrollment`：仅允许对**未注册**节点（token 未被消费）重签；已注册节点请求必须返回 `409`——M1 无 rekey，不得以重新 enroll 冒充密钥轮换（需轮换时删除节点记录重建）。
- 握手失败：常量时间比对 secret、限速、审计、返回泛化 401。
- 认证后的帧完整性与机密性由 TLS 保证，**没有逐帧应用层签名**（v0.7 旧 `p.sig` HMAC 方案已废弃）。

## 2. 注册（enroll bootstrap）

`POST /node/bootstrap`（运行时挂载 `/api/plugins/mcpanel/node/bootstrap`；注意相对端点带前导 `/`）

请求体：

```json
{
  "enrollToken": "c8f4…（≥128 位熵，10 分钟有效，一次性，绑定管理员预创建的节点记录）",
  "hostname": "node-abc",
  "agentVersion": "0.1.0",
  "caps": ["node.hello", "node.stats"],
  "tlsCertSha256": "a1b2…（64 位小写 hex，节点叶证书 DER 的 SHA-256）"
}
```

校验：token 常量时间比对、限速、失败审计；pinned 节点的 `tlsCertSha256` 与记录内 pin 相等校验——**pin 不匹配时拒绝且不消费 token**（管理员纠正 pin 后同一 token 可重试）；**记录内 pin 为空白时以上报指纹登记为初始 pin（TOFU）**，登记与注册在同一原子 CAS 内完成；token 已消费后再用回 `409`。

成功响应 200（machine rawJson，不走 admin 端点的 wrapped 包装；`nodeSecret` 仅此一次下发，节点落盘后不得再次获取）：

```json
{
  "nodeId": "1937…",
  "nodeSecret": "43 字符 base64url（32 字节熵）",
  "serverTimeMs": 1737619200000,
  "controlPlan": { "mode": "direct" }
}
```

错误：`401 auth.badToken`、`401 auth.pinMismatch`、`409 auth.tokenConsumed`、`409 node-disabled`（节点记录已被管理员停用，拒绝注册）、`429`（限速）。

`controlPlan`：

- M1a（直连，基线）：`{"mode": "direct"}`——面板直接拨节点记录中的 endpoint。
- M1b（NAT/frp，**实验性，未生产启用**）：

```json
{
  "mode": "frp",
  "frp": {
    "serverAddr": "frps.example.com",
    "serverPort": 7000,
    "proxyUser": "node-<nodeId>",
    "proxyToken": "<节点专属代理凭据>",
    "remotePort": 7101
  }
}
```

M1b 约束：`proxyToken` 必须是节点专属代理凭据，**绝不复用 frps 管理员 token**；专属代理授权机制未实现前，M1b 功能标记实验性、不在生产启用。流程为：节点先起内嵌 frpc 建立隧道 → 面板经隧道拨入控制信道；不存在"必须有控制信道才能配置 frp"的循环。

## 3. 控制信道

- URL：`{节点记录 endpoint}` + 固定路径 `/control`。endpoint 字段仅存 authority（`wss://host[:port]`，不含路径），`/control` 由面板程序固定追加，配置与实现均不得在 endpoint 中书写路径。
- 握手头（固定两根，不自造复杂 grammar）：

```text
Authorization: Bearer <nodeSecret>
X-Mcpanel-Node-Id: <nodeId>
```

- 节点校验 `X-Mcpanel-Node-Id` 属于自身 + Bearer secret 常量时间比对。M1 不做 rekey（属 M2+）；M1 期密钥泄露的处置 = 删除节点记录并重新 enroll。
- 握手成功后，面板立即发送 `node.hello`（§5.1）作为首帧与身份确认；不得以 evt hello / `node.info` 等替代形态。
- 重连由面板负责：指数退避 1s→30s 封顶 + 抖动；面板 15s 未收到**成功解析且符合已协商协议的有效帧** → 节点标记 `offline`，其下实例状态置 `unknown`（unknown/malformed 帧不续命计时器）。
- `node.hello` 握手有**独立 30s 超时**：WS 建立后面板等待 hello res 至多 30s，超时按握手失败处理，不受 15s online 计时器误杀；15s 计时自首个有效帧（hello res）起算。
- `node.hello` 响应携带新 `sessionId`；evt `seq` 以连接为作用域，重连后从 0 重新计数。`node.stats` 是快照语义，无需跨连接续传；控制台输出的 `sinceSeq` 续传属 M2，另定义。

## 4. 信封（所有帧）

JSON，UTF-8；单帧上限（序列化字节）：基线 256KB；节点 0.6.1+ 在 node.hello 应答中上报 `maxFrame`（当前 4MB），面板按该值放宽出站检查（未知字段忽略，旧面板仍自限 256KB）；字段名精确小驼峰；未知字段必须忽略（向前兼容）；整数不超过 JS 安全整数范围。

```jsonc
{
  "v": 1,                    // 协议版本，常量 1
  "id": "01JQY…",            // ULID 字符串；req 必填；res 回显对应 req id；evt 不带 id
  "t": "req",                // req | res | err | evt
  "m": "node.hello",         // 方法名；evt 时为 topic
  "ts": 1737619200000,       // epoch ms，仅诊断用途，不参与安全
  "seq": 0,                  // 仅 evt：同连接同 topic 单调递增整数（0 起），作用域为 sessionId
  "p": {}                    // payload；err 时为 {code, message, retryable}
}
```

语义规则：

- `req` 必须应答 `res` 或 `err`，面板默认超时 30s。
- 变更类 `req` 携带顶层幂等键 `ik`（面板按逻辑操作生成一次，重试复用同一键）；节点持久化 key→结果（TTL 24h + LRU 上限），命中直接返回上次结果。查询类 `req` 禁带 `ik`。M1 必选方法无变更类操作，`ik` 随 M2 变更方法一并启用。
- 长操作（镜像拉取、整合包安装等 M2+）必须立即返回 `res`（accepted 语义），终态走 evt；禁止在 30s 超时内跑不完的同步 res。
- 未知方法：接收方必须返回 `err proto.unknownMethod`，禁止注册空实现占位。

## 5. M1 方法与事件

caps 规则：`caps` 列出节点**真实实现**的全部方法与事件（含基线两项 `"node.hello"`、`"node.stats"`）；`node.rekey`、`port.check`、`instance.list`、`evt.instance.state` 等只有真实实现才可列入，面板对未列入的方法直接降级不调用；调用未实现方法时节点仍必须回 `err proto.unknownMethod`，禁止注册空实现占位。

### 5.1 node.hello（M1 必选）

面板握手成功后立即首发，不得以 evt hello / `node.info` 等替代形态。

```json
// req p（面板自述）
{"panelVersion": "0.1.0"}

// res p（节点自述，回显 req id；dockerVersion 可省略）
{"nodeId": "1937…", "sessionId": "01JQY…", "hostname": "node-abc", "agentVersion": "0.1.0", "caps": ["node.hello", "node.stats"], "dockerVersion": "24.0.7"}
```

`sessionId`（ULID）标识本次连接会话，evt `seq` 以其为作用域。caps 列出节点真实实现的全部方法与事件，基线必含 `"node.hello"`、`"node.stats"`；不使用发明的能力名。

### 5.2 evt node.stats（M1 必选）

节点在返回 `node.hello` res 后**立即发送第一帧**，此后每 5s 一帧。

```json
{
  "cpuPercent": 12.5,
  "memUsedMb": 1024,
  "memTotalMb": 8192,
  "diskUsedGb": 51,
  "diskTotalGb": 204,
  "load1": 0.42,
  "containers": [
    {"instanceId": "1937…", "state": "running", "cpuPercent": 5.1, "memUsedMb": 256}
  ],
  "dockerVersion": "24.0.7",
  "agentVersion": "0.1.0"
}
```

- `container.state` 枚举：`created|running|paused|stopped|exited|unknown`（Docker 原语小写直传）。
- `containers` 仅统计按容器 label 识别的 mcpanel 管理实例。
- 单位约定（字段名沿用历史 Mb/Gb 拼写**不改名**，换算必须按本约定执行）：`cpuPercent` 百分比一位小数；`memUsedMb`/`memTotalMb`/容器 `memUsedMb` 实际为 **MiB**（1024 基）整数；`diskUsedGb`/`diskTotalGb` 实际为 **GiB**（1024 基）整数。
- `load1` 在无此概念的平台上为 `null`（键可省略）。

### 5.3 M1 可选（caps 声明后才可调用；未实现不注册占位）

| 方法/事件 | schema 要点 |
| --- | --- |
| `port.check` | req p `{"checks": [{"port": 25565, "proto": "tcp"}]}`；res p `{"results": [{"port": 25565, "proto": "tcp", "free": true}]}` |
| `instance.list` | req p `{}`；res p `{"instances": [{"instanceId": "…", "name": "…", "state": "running", "lastExitCode": null}]}`；按容器 label 发现 |
| `evt.instance.state` | p `{"instanceId": "…", "state": "exited", "lastExitCode": 0, "reason": ""}`；受 seq 定序 |

### 5.4 预留（M2+，本版本未实现）

`node.rekey`（实现时旧 secret 握手宽限 5 分钟）、`template.list` `template.sync`、`instance.create/update/delete/start/stop/restart/kill/command`、`instance.output.subscribe/unsubscribe`（含 `sinceSeq` 与 `output.gap`）、`instance.config.read/write`、`file.*`、`file.upload.ticket/download.ticket`、`ftp.open/close`、`p2p.session.open/close`、`p2p.signal`、`image.pull`；事件 `task.progress`、`ftp.opened/closed`、`p2p.session.state`。名称以 `docs/mc-panel-design.md` §3.5 为准，进入实现前必须先在本文件补 schema。

## 6. 错误码

M1 生效：`auth.badToken`、`auth.tokenConsumed`、`auth.pinMismatch`、`node-disabled`、`auth.badSecret`、`auth.unknownNode`、`proto.badFrame`、`proto.unknownMethod`、`proto.badPayload`、`internal.error`。

M2+ 预留：`output.gap`、`instance.notFound`、`port.conflict`。

`err` 帧 `p`：`{"code": "…", "message": "…", "retryable": false}`；握手层失败不走信封，直接 HTTP 401/429。

## 7. 跨语言实现注意（Java / Go）

- 无逐帧签名，因此**不存在 JSON canonicalization 问题**；两侧只需对收到的 JSON 做常规解析。
- 时间一律 epoch ms 数字；ID 一律字符串（Snowflake/ULID 不用数值类型）；布尔/枚举值严格按上文小写拼写。
- `seq` 为普通 JSON 整数，每连接每 topic 独立计数，接收方只用于排序/去重：乱序（超前）帧正常处理；**seq 回退（小于该 topic 已见最大值）的帧必须丢弃，不得覆盖该 topic 已应用的较新快照**——TLS 保序不代表应用层 bug 允许状态倒退。
- 帧序列化后超 256KB 属 `proto.badFrame`，发送方有责任分块（M1 无分块场景）。

## 9. 业务操作契约（向后兼容扩展）

仅在 `node.hello.caps` 声明的方法可调用。变更请求必须携带顶层 `ik`，长度 16–128，重试保持不变；同键不同方法或载荷返回 `operation.conflict`。节点将操作摘要与结果原子持久化，按实例串行执行：执行成功落 `done` 并持久化结果（同键重放直接返回）；执行明确失败即清除该键记录（失败是确定结果，同键可安全重试）；仅进程在执行中途崩溃残留的 `pending` 记录返回 `operation.uncertain`，需人工核对实例状态。耗时安装、镜像拉取与压缩备份返回任务 `{taskId,state}`，用 `task.get` 查询；控制信道继续处理心跳。

- `instance.create` / `instance.update`：`{instanceId,name,image,command:string[],env:object,memoryMb,cpuMillis,diskMb,ports:[{port,containerPort,proto}],kind,config:object,fromTrash?}`。实例 ID 是 1–64 位字母数字或短横线。镜像必须先由 `image.pull` 拉取，禁止特权、host 网络、任意挂载或宿主环境继承。`fromTrash`（节点 0.4.0+）为回收站 trashId：创建前先把 `trash/<trashId>` 改名回 `instances/<instanceId>`（目标已存在报错，不覆盖），与容器创建在同一实例锁内原子完成。
- `instance.list`：`{page,size}` → `{records,total,page,size}`；`instance.inspect`：`{instanceId}` → 配置、容器状态与退出码。
- `instance.start/stop/restart/kill/delete`：`{instanceId,timeoutSec?}`；停止默认 30 秒，删除运行中实例必须先停止。删除（节点 0.4.0+）会把数据目录改名移入 `trash/<instanceId>-<毫秒>/`（写入 `.mcpanel-trash.json` 元数据），应答为 `{deleted:true,dataRetained:false,trashed:true}`；rename 失败（如外部句柄占用）则整题失败、记录保留可重试；数据目录本就不存在时 `trashed:false`。旧节点不迁移目录、应答无 `trashed` 字段（目录原地保留）。删除与 `instance.purge` 前节点会先关闭该实例的 SFTP 会话并中止进行中的分块上传（Windows 句柄占用防护）。单独 `instance.purge` 明确销毁数据：`RemoveAll` 数据目录并连带清除回收站内该实例的全部目录；实例记录已缺失（半途删除后的重试）不再报错，仍继续清理目录。
- `instance.command`：`{instanceId,command}` → `{sent:true}`，只能写该实例标准输入，禁止 CR/LF 与超长命令。
- `instance.output.read`：`{instanceId,since?,tail?}` → `{text,nextCursor,truncated}`；Docker 多路复用输出解码后返回纯文本，客户端绝不作为 HTML 解释。
- `port.check`：`{checks:[{port,proto}]}` → `{results:[{port,proto,free}]}`。实测并非预留，创建容器时以 Docker 端口绑定结果为准，面板分配失败必须保留可恢复的操作状态。
- `file.list`：`{instanceId,path,page?,size?,keyword?}` → `{entries,total,page,size}`。目录优先排序；`keyword` 对名称做不区分大小写子串过滤；`size<=0` 不分页返回整目录（兼容旧面板/目录树），`total` 为过滤后总数。节点 0.3.0 起支持分页参数，旧节点忽略之。
- `file.zip`：`{instanceId,paths:[...],dest}` 批量打包为同一实例内 zip（目录递归、符号链接跳过，条目以实例根为基准）；兼容旧 `path` 单路径形态，二者都缺报错。`dest` 面板必须显式传（旧节点在 dest 缺省时会在实例根旁生成 `<root>.zip` 杂散文件）。节点 0.3.0 起支持 `paths`。
- `file.read/write/mkdir/rename/delete`：均绑定 `instanceId`，`path` 为实例数据根内相对路径，拒绝绝对路径、`..`、符号链接逃逸。读取返回 `{content,encoding:"base64",size}`，写入接收相同格式；单次文件数据最多 96KiB，配置编辑使用 UTF-8。
- `file.upload.begin/chunk/commit/abort`：开始 `{instanceId,path,size,sha256}` 返回 `uploadId`；分块 `{instanceId,uploadId,offset,content}`（base64，最多 96KiB）；提交校验长度和 SHA-256 后原子替换目标。会话固定绑定实例/路径，过期清理临时文件，禁止用上传 ID 跨实例访问。
- `file.download.chunk`：`{instanceId,path,offset,length}` → `{content,size,eof}`，块大小同上。使用已有控制信道作为已发布 SPI 下的大文件有界内存兼容路径，不将整文件载入堆。
- `backup.create/list/restore/delete`：实例级 zip 备份，恢复只能在停止状态进行，解压大小/文件数受限；拒绝路径逃逸及符号链接，备份位于不可被实例修改的独立目录。
- `image.pull`：`{image}` → 任务；`task.get`：`{taskId}` → 状态、进度与脱敏错误。任务使用节点生命周期 context，关闭节点时取消。
- `instance.output.subscribe` / `instance.output.unsubscribe`：`{instanceId}`；订阅后节点经 attach 流式读取容器 stdout/stderr，以 `evt instance.output`（`{instanceId,text}`，300ms 聚合、8KiB 上限）增量推送，seq 按 `instance.output` 主题独立递增。会话断开自动清理订阅。
- `ftp.open` / `ftp.close`：`{instanceId,ttlMinutes?}` → `{port,user,password,expiresAt}`（实例数据根 SFTP）；`close` → `{closed}`。端口由节点 FTP 基端口 + 实例哈希偏移确定，凭据随机、TTL 上限 2h；成功开启另发 `evt ftp.opened`，关闭发 `evt ftp.closed`。

### 9.1 节点机级方法（node.*，无 instanceId）

针对节点机本身（非容器实例）的运维能力，全部仅在 caps 声明后可用：

- `node.file.list/read/write/mkdir/rename/delete/zip/download.chunk`：与实例级 `file.*` 同签名同约束，仅将 `instanceId` 替换为 `root`（节点 `fileRoots` 白名单中的命名根，默认 `data`）；路径拒绝绝对路径、`..` 与符号链接逃逸，分块上限同为 96KiB。`node.file.zip`（节点 0.4.0+）与实例级 `file.zip` 同参（`paths`/`path`/`dest`，均相对命名根）。
- `trash.list`：无参 → `{items:[{trashId,instanceId,name,deletedAt}],retentionDays}`（按删除时间倒序；trashId = `<instanceId>-<删除毫秒>`）。`trash.delete`：`{trashId}` → `{deleted:true}`，永久删除单个回收目录（RemoveAll）。二者与 `instance.delete` 的目录迁移同属回收站（节点 0.4.0+），保留期由节点配置 `trash.retentionDays`（默认 14，0=永久保留）控制，节点每小时清扫过期目录。
- `node.terminal.open`：`{terminalId,shell?}` → `{terminalId}`。在节点机上以管道模式（无 PTY）启动持久 shell（Unix 默认 `bash -i`，Windows 默认 `cmd`），并发上限由节点自定（当前 3），空闲 30 分钟回收；控制连接被替换等会话级取消会终止 shell 并移除登记。
- `node.terminal.input`：`{terminalId,data}` → `{sent:true}`，调用方自带换行。
- `node.terminal.close`：`{terminalId}` → `{closed}`。
- `evt node.terminal.output`：`{terminalId,text,closed?}`，输出 200ms 聚合、单块 16KiB 上限；通道结束后推 `closed:true` 终帧，seq 按 `node.terminal.output` 主题独立递增。
- `ftp.open.node` / `ftp.close.node`：`{root,ttlMinutes?}` → 同 `ftp.open` 结构，SFTP 根目录固定为命名根，实例哈希偏移换成根名哈希。

## 8. 示例帧

```json
{"v":1,"id":"01JQY0EMZ8X2VQ9R3S4T5U6V7W","t":"req","m":"node.hello","ts":1737619200000,"p":{"panelVersion":"0.1.0"}}
{"v":1,"id":"01JQY0EMZ8X2VQ9R3S4T5U6V7W","t":"res","m":"node.hello","ts":1737619200001,"p":{"nodeId":"1937…","sessionId":"01JQY0EMZ8X2VQ9R3S4T5U6V7X","hostname":"node-abc","agentVersion":"0.1.0","caps":["node.hello","node.stats"],"dockerVersion":"24.0.7"}}
{"v":1,"t":"evt","m":"node.stats","ts":1737619200002,"seq":0,"p":{"cpuPercent":12.5,"memUsedMb":1024,"memTotalMb":8192,"diskUsedGb":51,"diskTotalGb":204,"load1":0.42,"containers":[],"dockerVersion":"24.0.7","agentVersion":"0.1.0"}}
{"v":1,"id":"01JQY0EMZ8X2VQ9R3S4T5U6V7Y","t":"err","m":"port.check","ts":1737619200006,"p":{"code":"proto.unknownMethod","message":"port.check not implemented by this agent","retryable":false}}
```
