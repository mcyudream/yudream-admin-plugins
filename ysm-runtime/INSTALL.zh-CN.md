# 玩家模型安装与配置

本次发布组合：YDA `1.11.0`、网站插件 `ysm-models 0.5.0`、Folia 插件 `0.4.1`、Fabric 客户端 Mod `0.2.0`。游戏运行端只适配 **Minecraft / Folia 26.3、Java 25**；YDA 宿主使用 Java 21。

## 1. 文件放在哪里

| 文件 | 安装位置 |
| --- | --- |
| `yudream-plugin-ysm-models-0.5.0.jar` | YDA 插件管理中安装；先启用 `yudream-skin 1.7.0` |
| `YudreamModels-folia-26.3-0.4.1.jar` | Folia 服务器的 `plugins/`，替换旧版模型服务端 |
| `YudreamModels-fabric-26.3-0.2.0.jar` | 玩家的对应游戏实例 `mods/` |
| `craft-engine-paper-plugin-26.9.2-folia26.3.2.jar`（CE 模式必需） | Folia 服务器的 `plugins/`，替换旧版 CE，只装 Paper / Bukkit 其中一个 |
| `HandbookCore-mc26_3-plugin-Beta_0_1_1.jar`（可选） | Folia 服务器的 `plugins/`，提供玩家指南 |

网站插件可从 YDA 插件市场安装，也可上传本次发布的 JAR。已安装 0.4.0 配套 CE 时，本次仅替换 YSM 服务端，保留现有配置并重启游戏服；网站、CE 和客户端沿用现有版本。

0.4.1 修复玩家远离世界原点时虚拟模型部件触发 Folia 区域线程检查的问题，并修复较大方块导出超出原版模型坐标限制导致部件缺失的问题。适配失败时暂时恢复原版外观，按 1–30 秒退避重试；每个玩家的同一模型与贴图组合最多每 30 秒记录一次完整异常。网站连接警告会附带 HTTP 状态、连接失败或响应格式等安全诊断；若为 HTTP 401 / 403，请核对网站与游戏服保存的桥接密钥。

## 2. 配置 YDA 网站

先安装并启用 `yudream-skin 1.7.0`，再安装并启用 `ysm-models 0.5.0`。玩家需要已有的皮肤站角色，模型装备关系绑定在本人角色上。

管理员进入「玩家模型 → 模型设置 → 服务器连接」（权限 `plugin:ysm-models:config`）：

1. 填写玩家与游戏服务器均可访问的**网站公网地址**，例如 `https://skin.example.com`。使用部署路径前缀时一并填写，不含账号、查询参数或片段。
2. 点击**生成随机密钥**，或填入已有密钥（32–256 个无空格的可打印英文字符）。密钥默认隐藏，可显示或一键复制。
3. 点击页面上方**保存连接配置**。生成与编辑只是草稿，保存成功后网站端立即生效，无需重启 YDA；连接设置加密存储，重启后保留。
4. 点击**复制游戏服配置**，将 `website-url` 和 `bridge-token` 填入下面的游戏服配置，然后重启游戏服。客户端无需填写密钥。

更换已有密钥时页面会要求确认。保存后，旧密钥和已签发的下载链接立即失效，需同步所有连接该网站的游戏服并重启。

旧部署仍兼容后端环境变量 `YSM_PUBLIC_BASE_URL` 和 `YSM_BRIDGE_TOKEN`；尚未保存页面配置时读取它们。页面配置保存后优先使用持久化设置，再修改环境变量不会覆盖它。原环境变量中的密钥可直接沿用，无需重新生成。

### 上传与审核设置

管理员进入「玩家模型 → 模型设置」（权限 `plugin:ysm-models:config`）：

- **允许上传模型**：默认开启；关闭后，未持有受限上传权限的账号不能上传新模型或新版本，已有可用模型仍可使用。
- **受限上传权限**：将 `plugin:ysm-models:upload` 授予需要在关闭上传后继续提交的管理员或用户；超管 `*` 自动包含此权限。仍需具有基础入口权限 `plugin:ysm-models:user`，上传只归本人所有，审核规则继续适用。管理权限 `:manage`、配置权限 `:config` 本身不包含此豁免。
- **上传后需要审核**：默认关闭；开启后，新模型和新版本进入待审核。开关自动保存，保存成功后立即生效。
- 在「模型管理」（权限 `plugin:ysm-models:manage`）筛选待审核模型，预览后通过或填写驳回原因。所有者可以预览待审核模型，通过后才能装备和同步到游戏。
- 开启审核不会追溯改变已有模型；关闭审核也不会自动通过待审核 / 已驳回模型。上传新版本后，已装备角色会在审核期间暂时恢复皮肤外观。

## 3. 配置 Folia 服务器

放入服务端 JAR，使用 Java 25 启动一次服务器。编辑生成的 `plugins/YudreamModels/config.yml`，然后重启服务器：

```yaml
website-url: 'https://skin.example.com'
bridge-token: 'REPLACE_WITH_THE_SAME_SECRET_AS_YDA'
poll-seconds: 5
refresh-cooldown-seconds: 60
resource-pack-timeout-seconds: 120
model-scale: 0.75
view-distance: 64
max-snapshot-models: 128
handbook:
  enabled: true
pack-host:
  mode: craftengine
  max-cache-mib: 1024
```

- `website-url` 与网站「模型设置 → 服务器连接」中的网站公网地址一致。
- `bridge-token` 与网站设置里保存的桥接密钥必须完全一致；使用“复制游戏服配置”可避免输入错误。
- `pack-host.mode: craftengine` 使用配套 CE 的自托管地址、端口与限流，不启动 YSM 的独立 HTTP 服务，原来的 `bind` / `port` / `public-url` 可删除。
- CE 启动时异步准备资源，YSM 会等待；首次进服最多等待 120 秒，之后可用 `/model refresh` 重试。缺少或旧版 CE 会明确拒绝启动，CE 停用也会关闭 YSM，不会回落并开放独立端口。
- 每位玩家仍只获得本人及服务器共享的模型快照，不把私有模型并入 CE 公共主包。下载链接使用一分钟单次令牌并校验玩家 UUID；每次发送重新签发，代理须原样转发查询参数及 `X-Minecraft-UUID` 等请求头，不缓存模型下载。
- 玩家电脑必须能够访问网站下载地址和资源包地址；仅本机能访问的 `localhost` 地址不能用于公网玩家。
- 可选安装 HandbookCore 后自动注册“模型指南”（`ysm:guide`），无需编辑核心的内容文件。`handbook.enabled: false` 可关闭指南；旧配置缺少此项时默认启用。变更后重启服务器。客户端不需要更新。

### 配套 CraftEngine 配置

CE 原有自托管可用时，沿用已有 `resource-pack.packs`、工作流和 `self-host` 配置即可。下面是 `plugins/CraftEngine/config.yml` 的最小相关片段，请合并到现有 `resource-pack` 节点，避免重复 YAML 键：

```yaml
resource-pack:
  packs:
    default:
      type: self
      storage_path: './cache/hosted/default/resource_pack.zip'
  self-host:
    ip: '你的公网IP或域名'
    port: auto
    protocol: http
    deny_non_minecraft_request: true
    one_time_token: true
    strict_validation: false
```

`port: auto` 复用 Minecraft 服务器端口，玩家下载模型无需再开放 `41008`。例如游戏服使用 `25565`，模型下载就使用同一地址的 `25565`。CE 主包仍按原有工作流生成 / 上传；YSM 的私有快照由运行端自动注册，CE 重载后仍可用。使用代理服时，CE 的公网下载地址须能到达运行 CE 的后端服务器；代理的 Minecraft 转发不等于 HTTP 转发。

如果现有 CE 下载使用反向代理，可保留 `self-host.url: 'https://packs.example.com/'`。这次扩展需同时升级到配套 CE `26.9.2-folia26.3.2`；上游旧版没有动态托管接口。请完整重启游戏服。

### 保留独立托管的旧部署

旧配置没有 `pack-host.mode` 时继续按 `standalone` 运行；切换到 CE 必须显式添加 `mode: craftengine`。需要独立 HTTP 服务时使用：

```yaml
pack-host:
  mode: standalone
  max-cache-mib: 1024
  bind: '0.0.0.0'
  port: 8767
  public-url: 'https://packs.example.com'
```

此时公网下载地址须反向代理到上面的监听端口。快照有单份 128 MiB、最多 256 份和默认合计 1024 MiB 上限；超过上限保留旧外观。停止服务器后可按保留策略清理 `plugins/YudreamModels/packs/` 历史文件。

## 4. 安装客户端 Mod

玩家游戏实例使用 Minecraft `26.3`、Java `25`、Fabric Loader `>=0.20.0`。在同一实例的 `mods/` 内放入：

1. `YudreamModels-fabric-26.3-0.2.0.jar`。
2. 适配 Minecraft `26.3` 的 Fabric API（构建 / 验证所用版本为 `0.165.0+26.3`）。

客户端 Mod 无需填写网站地址或 bridge token。进入已配置插件的服务器后，会自动握手并按实际装备下载模型。未安装本 Mod 的客户端可走原版资源包快照路径，需允许服务器资源包；网站换装后可用 `/model refresh` 更新快照。

## 5. 玩家使用与检查

安装 HandbookCore 后，玩家用 `/model guide` 打开完整指南，也可从手册目录找到“模型指南”或用 `/handbook search 模型` 搜索。需要 `ysm.use` 与 `handbookcore.use`，默认所有玩家可读；手册内按钮以玩家自身权限执行命令。

1. 在网站「玩家模型 → 我的模型」上传原始 `.ysm` 或明文模型 ZIP。
2. 选择贴图和本人皮肤站角色，点击装备；管理员可将模型加入服务器模型库。
3. 进入游戏，执行 `/model status` 查看模式和资源加载状态。
4. 原版快照更新使用 `/model refresh`，默认冷却 60 秒；Fabric 模式支持局部热更新。
5. 原版查看本人替身可用 `/model self on`，再切换第三人称；关闭用 `/model self off`。默认关闭本人替身。
6. 在网站恢复皮肤外观即可取消模型。

## 6. 格式与验证范围

支持原始 `.ysm`、明文 ZIP、box / 逐面 UV 和受限 Molang。默认服装显隐层可运行；专用物理按目标值近似，自定义控制器、外观菜单、声音、粒子、附属实体与第一人称手臂未复现。文件含不支持或畸形的表达式时，预览会显示兼容说明。负尺寸方块规范化后可能改变其 UV 朝向。

网站插件从 `0.3.0` 起，新上传模型使用 **schema 2**。仍在使用早期 `0.1.x` 游戏运行端时，应连同网站升级；旧模型可继续使用。本次 CE 托管更新需替换 Folia 服务端和配套 CE，Fabric `0.2.0` 可继续使用。网站内置解析器，不需要额外安装 Node。原版刷新资源快照可能触发两次资源重载；Fabric 更新失败会保留旧外观。

已有验证包括模型转换、前后端测试、运行端构建、本地 YDA 上传 / 装备 / 共享 / 桥接鉴权，以及使用模拟网站桥接的游戏端显示与热更新。最终游戏端连接真实 YDA 的完整往返、网页 WebGL 三维显示、跨贴图切换和完全无 Mod 的官方客户端仍未验证；详细记录见 [VERIFICATION.md](VERIFICATION.md)。
