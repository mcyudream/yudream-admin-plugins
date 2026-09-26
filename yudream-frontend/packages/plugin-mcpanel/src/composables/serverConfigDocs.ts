/** server.properties / 代理配置字典：中文说明 + 类型 + 可选值，供结构化配置页展示。 */

export type McpConfigType = 'boolean' | 'number' | 'enum' | 'text'

export type McpConfigGroup = '网络' | '安全' | '玩法' | '世界' | '性能' | '远程' | '资源包' | '子服' | '转发' | '其他'

export interface McpConfigFieldDoc {
  key: string
  label: string
  desc: string
  group: McpConfigGroup
  type: McpConfigType
  options?: Array<{ label: string, value: string }>
  defaultValue?: string
  tip?: string
}

export const CONFIG_GROUP_ORDER: McpConfigGroup[] = ['网络', '安全', '玩法', '世界', '性能', '远程', '资源包', '子服', '转发', '其他']

export const CONFIG_GROUP_META: Record<McpConfigGroup, { title: string, desc: string }> = {
  网络: {
    title: '网络与连接',
    desc: '端口、人数、服务器列表展示等与玩家能否连上、看到服务器相关的选项。',
  },
  安全: {
    title: '安全与权限',
    desc: '正版验证、白名单、出生点保护、OP 权限等，决定谁可以进入以及能做什么。',
  },
  玩法: {
    title: '玩法规则',
    desc: '游戏模式、难度、PVP、飞行等直接影响玩家体验的规则。',
  },
  世界: {
    title: '世界生成',
    desc: '世界名称、种子、结构与生物生成。改种子只影响新建世界，已有世界不会重置。',
  },
  性能: {
    title: '性能与距离',
    desc: '视距、模拟距离、Tick 超时等调优项；数值越大越吃 CPU/带宽。',
  },
  远程: {
    title: '远程控制 (RCON/Query)',
    desc: '通过 RCON/Query 从外部管理或查询服务器；密码请勿使用面板登录密码。',
  },
  资源包: {
    title: '资源包',
    desc: '强制或可选的客户端资源包地址与校验。',
  },
  子服: {
    title: '子服与转发目标',
    desc: '代理（BungeeCord / Velocity）注册的后端服务器地址与默认顺序。',
  },
  转发: {
    title: '玩家信息转发',
    desc: '代理与后端之间传递玩家身份（IP / 正版档案）的方式；模式需与后端配置一致。',
  },
  其他: {
    title: '其他',
    desc: '调试、数据采集及文件中出现但未归类的自定义键。',
  },
}

const YES_NO = [
  { label: '是 (true)', value: 'true' },
  { label: '否 (false)', value: 'false' },
]

export const SERVER_PROPERTIES_DOCS: McpConfigFieldDoc[] = [
  // 网络
  {
    key: 'server-port',
    label: '服务端端口',
    desc: '玩家连接使用的 TCP 端口。面板创建实例时通常已按节点端口池分配；手动改端口后需同步节点防火墙 / 安全组，并重启实例生效。',
    group: '网络',
    type: 'number',
    defaultValue: '25565',
  },
  {
    key: 'server-ip',
    label: '绑定 IP',
    desc: '监听的网卡地址。留空表示监听全部网卡（0.0.0.0）。仅在多网卡或只想对外暴露某一地址时填写。',
    group: '网络',
    type: 'text',
    defaultValue: '',
  },
  {
    key: 'max-players',
    label: '最大玩家数',
    desc: '同时在线人数上限。超过后新玩家无法进入；与白名单无关，白名单玩家同样受此限制。',
    group: '网络',
    type: 'number',
    defaultValue: '20',
  },
  {
    key: 'motd',
    label: '服务器描述 (MOTD)',
    desc: '多人游戏服务器列表中显示的简介文字，支持颜色代码（§ 或 \\u00A7）。使用 ColorMotd 等插件时可留空，由插件接管。',
    group: '网络',
    type: 'text',
  },
  {
    key: 'enable-status',
    label: '响应列表查询',
    desc: '是否在服务器列表中显示在线状态。关闭后客户端列表里会显示为离线，但仍可直连。',
    group: '网络',
    type: 'boolean',
    options: YES_NO,
    defaultValue: 'true',
  },
  {
    key: 'hide-online-players',
    label: '隐藏在线人数',
    desc: '是否在服务器列表中隐藏当前在线玩家列表（人数仍可能显示，视客户端版本）。',
    group: '网络',
    type: 'boolean',
    options: YES_NO,
    defaultValue: 'false',
  },
  {
    key: 'prevent-proxy-connections',
    label: '阻止代理连接',
    desc: '拦截部分已知代理 / VPN 链路。可能误伤正常玩家，一般保持 false。',
    group: '网络',
    type: 'boolean',
    options: YES_NO,
    defaultValue: 'false',
  },
  {
    key: 'network-compression-threshold',
    label: '网络压缩阈值',
    desc: '数据包超过该字节数才压缩。数值越小压缩越多（省带宽、费 CPU）；-1 表示关闭压缩。',
    group: '网络',
    type: 'number',
    defaultValue: '256',
  },
  {
    key: 'rate-limit',
    label: '连接限速',
    desc: '玩家客户端单位时间内发送数据包的上限，超过会被踢出。0 表示不限制。用于缓解刷包。',
    group: '网络',
    type: 'number',
    defaultValue: '0',
  },
  {
    key: 'use-native-transport',
    label: 'Linux 网络优化',
    desc: '是否启用针对 Linux 的 epoll 数据包收发优化。Docker / Linux 节点建议 true；Windows 无效。',
    group: '网络',
    type: 'boolean',
    options: YES_NO,
    defaultValue: 'true',
  },
  {
    key: 'entity-broadcast-range-percentage',
    label: '实体广播范围 %',
    desc: '相对视距的百分比，决定客户端能看到多远的实体。调低可减轻带宽压力，远处生物可能不可见。',
    group: '网络',
    type: 'number',
    defaultValue: '100',
  },

  // 安全
  {
    key: 'online-mode',
    label: '正版验证',
    desc: 'true：仅允许 Minecraft 正版账号登录（会校验 Mojang 会话）。false：允许离线/盗版客户端，必须配合 AuthMe 等登录插件，否则任何人都能冒充玩家。',
    group: '安全',
    type: 'boolean',
    options: YES_NO,
    defaultValue: 'true',
    tip: '对外网开放的生存服强烈建议保持 true。',
  },
  {
    key: 'white-list',
    label: '启用白名单',
    desc: '开启后，只有白名单中的玩家可以进入。名单维护在 whitelist.json，或用控制台指令 whitelist add/remove。',
    group: '安全',
    type: 'boolean',
    options: YES_NO,
    defaultValue: 'false',
  },
  {
    key: 'allow-list',
    label: '启用白名单 (1.19+)',
    desc: '部分新版本核心用 allow-list 代替 white-list。两者含义相同，按你的核心实际写入的键为准。',
    group: '安全',
    type: 'boolean',
    options: YES_NO,
  },
  {
    key: 'enforce-whitelist',
    label: '强制白名单',
    desc: '白名单变更后是否立即踢出已不在名单中的在线玩家。true 更安全；false 时已在线玩家可继续玩到退出。',
    group: '安全',
    type: 'boolean',
    options: YES_NO,
    defaultValue: 'false',
  },
  {
    key: 'spawn-protection',
    label: '出生点保护半径',
    desc: '保护出生点周围非 OP 玩家不可破坏的区域。实际半径约为 2×N+1。设为 0 或负数可关闭保护（版本相关）。',
    group: '安全',
    type: 'number',
    defaultValue: '16',
  },
  {
    key: 'op-permission-level',
    label: 'OP 权限等级',
    desc: 'OP 可使用的指令权限级别：1=基本 / 2=作弊指令 / 3=管理指令 / 4=全部（含停止服务器）。公开服建议 2 或 3，慎给 4。',
    group: '安全',
    type: 'enum',
    options: [
      { label: '1 · 基础', value: '1' },
      { label: '2 · 作弊指令', value: '2' },
      { label: '3 · 管理指令', value: '3' },
      { label: '4 · 全部权限', value: '4' },
    ],
    defaultValue: '4',
  },
  {
    key: 'function-permission-level',
    label: '函数权限等级',
    desc: '数据包 / 函数（function）执行时使用的 OP 等级。过高可能让数据包拥有过大权限。',
    group: '安全',
    type: 'enum',
    options: [
      { label: '1', value: '1' },
      { label: '2', value: '2' },
      { label: '3', value: '3' },
      { label: '4', value: '4' },
    ],
    defaultValue: '2',
  },
  {
    key: 'enforce-secure-profile',
    label: '强制安全档案',
    desc: '开启后，没有 Mojang 签名公钥的客户端无法加入（主要影响部分第三方启动器）。离线服或需要兼容启动器时请关闭。',
    group: '安全',
    type: 'boolean',
    options: YES_NO,
  },

  // 玩法
  {
    key: 'gamemode',
    label: '默认游戏模式',
    desc: '新玩家首次进入或重生时的游戏模式。',
    group: '玩法',
    type: 'enum',
    options: [
      { label: '生存 survival', value: 'survival' },
      { label: '创造 creative', value: 'creative' },
      { label: '冒险 adventure', value: 'adventure' },
      { label: '旁观 spectator', value: 'spectator' },
    ],
    defaultValue: 'survival',
  },
  {
    key: 'force-gamemode',
    label: '强制默认模式',
    desc: '玩家每次进入服务器时是否强制回到默认游戏模式，忽略客户端上次的模式。',
    group: '玩法',
    type: 'boolean',
    options: YES_NO,
    defaultValue: 'false',
  },
  {
    key: 'difficulty',
    label: '难度',
    desc: '和平 peaceful / 简单 easy / 普通 normal / 困难 hard。部分核心允许用指令动态修改。',
    group: '玩法',
    type: 'enum',
    options: [
      { label: '和平 peaceful', value: 'peaceful' },
      { label: '简单 easy', value: 'easy' },
      { label: '普通 normal', value: 'normal' },
      { label: '困难 hard', value: 'hard' },
    ],
    defaultValue: 'easy',
  },
  {
    key: 'hardcore',
    label: '极限模式',
    desc: '开启后难度锁定为困难，玩家死亡进入旁观或被封禁（视核心实现）。请确认备份后再开。',
    group: '玩法',
    type: 'boolean',
    options: YES_NO,
    defaultValue: 'false',
  },
  {
    key: 'pvp',
    label: 'PVP',
    desc: '是否允许玩家之间互相造成伤害。关闭后仍可能被怪物伤害。',
    group: '玩法',
    type: 'boolean',
    options: YES_NO,
    defaultValue: 'true',
  },
  {
    key: 'allow-nether',
    label: '允许下界',
    desc: '是否生成下界并允许传送门。关闭后无法进入下界。',
    group: '玩法',
    type: 'boolean',
    options: YES_NO,
    defaultValue: 'true',
  },
  {
    key: 'allow-flight',
    label: '允许飞行',
    desc: 'false 时服务端会检测飞行并可能踢出玩家。安装了创造、鞘翅、飞行模组/插件的服务器通常需要设为 true。',
    group: '玩法',
    type: 'boolean',
    options: YES_NO,
    defaultValue: 'false',
    tip: '模组服、创造建筑服建议 true，否则玩家容易被误踢。',
  },
  {
    key: 'enable-command-block',
    label: '命令方块',
    desc: '是否允许命令方块执行指令。关闭后命令方块无效。',
    group: '玩法',
    type: 'boolean',
    options: YES_NO,
    defaultValue: 'false',
  },
  {
    key: 'announce-player-achievements',
    label: '广播成就',
    desc: '玩家达成进度/成就时是否在全服聊天栏广播。旧键名，新版本多为 announce-advancements。',
    group: '玩法',
    type: 'boolean',
    options: YES_NO,
    defaultValue: 'true',
  },
  {
    key: 'announce-advancements',
    label: '广播进度',
    desc: '新版本中替代 announce-player-achievements，控制进度达成是否全服可见。',
    group: '玩法',
    type: 'boolean',
    options: YES_NO,
    defaultValue: 'true',
  },
  {
    key: 'player-idle-timeout',
    label: '挂机踢出（分钟）',
    desc: '玩家无操作超过该分钟数后自动踢出。0 表示不踢。',
    group: '玩法',
    type: 'number',
    defaultValue: '0',
  },

  // 世界
  {
    key: 'level-name',
    label: '世界名称',
    desc: '实例数据目录下世界文件夹的名称，通常为 world。修改后新启动会读取同名文件夹；没有则生成新世界。',
    group: '世界',
    type: 'text',
    defaultValue: 'world',
  },
  {
    key: 'level-seed',
    label: '世界种子',
    desc: '生成新世界时使用的种子。留空表示随机。已有世界再改种子不会重置地图，只影响之后新建的世界。',
    group: '世界',
    type: 'text',
    defaultValue: '',
  },
  {
    key: 'level-type',
    label: '世界类型',
    desc: 'default（普通）/ flat（超平坦）/ amplified（放大化）/ largeBiomes（巨型生物群系）。部分核心或数据包会改写此值。',
    group: '世界',
    type: 'enum',
    options: [
      { label: '默认 default', value: 'minecraft:normal' },
      { label: '超平坦 flat', value: 'minecraft:flat' },
      { label: '放大化 amplified', value: 'minecraft:amplified' },
      { label: '巨型生物群系', value: 'minecraft:large_biomes' },
    ],
    tip: '不同版本写法可能不同（如 default 或 minecraft:normal），以启动日志/核心文档为准。',
  },
  {
    key: 'generator-settings',
    label: '生成器参数',
    desc: '自定义世界生成参数，常见于超平坦预设。不用超平坦请留空。',
    group: '世界',
    type: 'text',
    defaultValue: '',
  },
  {
    key: 'max-world-size',
    label: '世界最大半径',
    desc: '世界边界半径（格）。默认值很大（约 29999984），调小可限制地图扩张、降低磁盘占用。',
    group: '世界',
    type: 'number',
  },
  {
    key: 'max-build-height',
    label: '最高建筑高度',
    desc: '玩家可放置方块的最大高度（通常相对世界基岩）。不同版本语义略有差异。',
    group: '世界',
    type: 'number',
  },
  {
    key: 'generate-structures',
    desc: '新生成区块时是否生成村庄、要塞等结构。关闭后地牢与地下要塞仍可能生成（版本相关）。',
    label: '生成结构',
    group: '世界',
    type: 'boolean',
    options: YES_NO,
    defaultValue: 'true',
  },
  {
    key: 'spawn-npcs',
    label: '生成 NPC',
    desc: '世界中是否生成村民等 NPC。',
    group: '世界',
    type: 'boolean',
    options: YES_NO,
    defaultValue: 'true',
  },
  {
    key: 'spawn-animals',
    label: '生成动物',
    desc: '是否生成友好生物（牛、羊等）。',
    group: '世界',
    type: 'boolean',
    options: YES_NO,
    defaultValue: 'true',
  },
  {
    key: 'spawn-monsters',
    label: '生成怪物',
    desc: '是否生成敌对生物。和平难度下即使开启也不会生成攻击型生物。',
    group: '世界',
    type: 'boolean',
    options: YES_NO,
    defaultValue: 'true',
  },

  // 性能
  {
    key: 'view-distance',
    label: '视距（区块）',
    desc: '服务端向客户端发送的区块距离。越大玩家视野越远，内存/CPU/带宽消耗越高。生存服建议 8–12，群组服可试 6–8。',
    group: '性能',
    type: 'number',
    defaultValue: '10',
  },
  {
    key: 'simulation-distance',
    label: '模拟距离（区块）',
    desc: '生物 AI、红石、作物生长等逻辑的模拟距离。可以低于视距以省性能，远处只显示不模拟。',
    group: '性能',
    type: 'number',
    defaultValue: '10',
  },
  {
    key: 'max-tick-time',
    label: '单 Tick 最大毫秒',
    desc: '超过该毫秒数会判定服务器卡死并可能自动停止（默认 60000）。设为 -1 可禁用 watchdog（不推荐，排查卡服时有用）。',
    group: '性能',
    type: 'number',
    defaultValue: '60000',
  },
  {
    key: 'sync-chunk-writes',
    label: '同步写区块',
    desc: '同步写入区块文件，更安全、略慢。机械硬盘或高负载时可结合 Paper 配置权衡。',
    group: '性能',
    type: 'boolean',
    options: YES_NO,
    defaultValue: 'true',
  },
  {
    key: 'max-chained-neighbor-updates',
    label: '连锁更新上限',
    desc: '限制一次连锁方块更新的数量，防止单 tick 更新过多导致卡服。负值表示不限制。',
    group: '性能',
    type: 'number',
  },
  {
    key: 'snooper-enabled',
    label: '数据采集 (Snooper)',
    desc: '是否向 Mojang 发送匿名统计数据。可关闭。',
    group: '性能',
    type: 'boolean',
    options: YES_NO,
    defaultValue: 'false',
  },
  {
    key: 'enable-jmx-monitoring',
    label: 'JMX 监控',
    desc: '是否暴露 JMX 以便用 VisualVM 等工具监控 JVM。一般保持关闭。',
    group: '性能',
    type: 'boolean',
    options: YES_NO,
    defaultValue: 'false',
  },
  {
    key: 'debug',
    label: '调试模式',
    desc: '启用调试输出，日常运行请保持 false。',
    group: '性能',
    type: 'boolean',
    options: YES_NO,
    defaultValue: 'false',
  },

  // 远程
  {
    key: 'enable-rcon',
    label: '启用 RCON',
    desc: '是否开启远程控制台协议，供面板/工具远程执行指令。面板部分高级功能可能依赖 RCON。',
    group: '远程',
    type: 'boolean',
    options: YES_NO,
    defaultValue: 'false',
  },
  {
    key: 'rcon.port',
    label: 'RCON 端口',
    desc: 'RCON 监听端口，需与 enable-rcon 配合，并在防火墙放行（若对外）。不要与 server-port 相同。',
    group: '远程',
    type: 'number',
  },
  {
    key: 'rcon.password',
    label: 'RCON 密码',
    desc: 'RCON 登录密码。请使用高强度独立密码，不要与面板账号或服务器其它密码相同。文件明文保存，注意节点权限。',
    group: '远程',
    type: 'text',
  },
  {
    key: 'broadcast-rcon-to-ops',
    label: '向 OP 播报 RCON',
    desc: '通过 RCON 执行的指令是否通知在线 OP。',
    group: '远程',
    type: 'boolean',
    options: YES_NO,
    defaultValue: 'true',
  },
  {
    key: 'broadcast-console-to-ops',
    label: '向 OP 播报控制台',
    desc: '控制台消息是否转发给在线 OP。',
    group: '远程',
    type: 'boolean',
    options: YES_NO,
    defaultValue: 'true',
  },
  {
    key: 'enable-query',
    label: '启用 Query',
    desc: '是否启用 GameSpy4 查询协议，供外部工具查询服务器状态。',
    group: '远程',
    type: 'boolean',
    options: YES_NO,
    defaultValue: 'false',
  },
  {
    key: 'query.port',
    label: 'Query 端口',
    desc: 'Query 协议监听端口，需与 enable-query 配合。',
    group: '远程',
    type: 'number',
  },

  // 资源包
  {
    key: 'resource-pack',
    label: '资源包地址',
    desc: '指向资源包的 HTTP(S) 直链。留空表示不推送资源包。',
    group: '资源包',
    type: 'text',
  },
  {
    key: 'resource-pack-sha1',
    label: '资源包 SHA-1',
    desc: '资源包文件的 SHA-1 摘要（小写十六进制），用于校验完整性。可选。',
    group: '资源包',
    type: 'text',
  },
  {
    key: 'require-resource-pack',
    label: '强制资源包',
    desc: '玩家是否必须接受资源包才能进入。需配合 resource-pack 使用。',
    group: '资源包',
    type: 'boolean',
    options: YES_NO,
    defaultValue: 'false',
  },
  {
    key: 'resource-pack-prompt',
    label: '资源包提示文案',
    desc: '强制资源包时显示在提示窗口上的自定义说明文字。',
    group: '资源包',
    type: 'text',
  },

  // 其他
  {
    key: 'previews-chat',
    label: '聊天预览',
    desc: '是否启用聊天预览（1.19+）。与安全档案、聊天签名相关，离线服通常关闭。',
    group: '其他',
    type: 'boolean',
    options: YES_NO,
  },
  {
    key: 'text-filtering-config',
    label: '文本过滤配置',
    desc: '文本过滤规则文件路径，部分环境可能工作异常。',
    group: '其他',
    type: 'text',
  },
]

/** 面板内部写入实例 config 的键，不应出现在 server.properties 编辑器。 */
export const PANEL_INTERNAL_KEYS = new Set([
  'installType',
  'installUrl',
  'installFileName',
  'installMcVersion',
  'installSource',
  'autoDownloadCore',
  'dockerImageId',
  'dockerImageName',
  'quickStartPackage',
  'mcpanelNodeId',
])

export function configDocMap(path?: string, kind?: string): Map<string, McpConfigFieldDoc> {
  const map = new Map<string, McpConfigFieldDoc>()
  docsForFile(path, kind).forEach((doc) => {
    map.set(doc.key, doc)
  })
  return map
}

/** 按配置文件选择字段字典：代理配置按「kind + 根级文件名」识别，其余回落 server.properties。 */
export function docsForFile(path?: string, kind?: string): McpConfigFieldDoc[] {
  const normalized = (path ?? '').toLowerCase()
  if (kind === 'velocity' && normalized === 'velocity.toml') {
    return VELOCITY_TOML_DOCS
  }
  if (kind === 'bungee' && normalized === 'config.yml') {
    // MC 后端没有根级 config.yml；只有 BungeeCord 家族（且页面按 kind 进入）才用它。
    return BUNGEE_CONFIG_DOCS
  }
  return SERVER_PROPERTIES_DOCS
}

/** 代理配置中无静态字典的键（如 servers.<name>.address）按前缀归类展示。 */
export function groupForUnknownKey(path: string | undefined, kind: string | undefined, key: string): McpConfigGroup | null {
  const normalized = (path ?? '').toLowerCase()
  const isProxyConfig = (kind === 'velocity' && normalized === 'velocity.toml')
    || (kind === 'bungee' && normalized === 'config.yml')
  if (!isProxyConfig) {
    return null
  }
  if (key === 'servers' || key.startsWith('servers.')) {
    return '子服'
  }
  return null
}

const FORWARD_MODES = [
  { label: 'modern（Velocity 现代，推荐）', value: 'modern' },
  { label: 'legacy（BungeeCord 兼容）', value: 'legacy' },
  { label: 'bungeeguard', value: 'bungeeguard' },
  { label: 'none（不转发，离线身份）', value: 'none' },
]

export const VELOCITY_TOML_DOCS: McpConfigFieldDoc[] = [
  {
    key: 'config-version',
    label: '配置版本',
    desc: 'velocity.toml 的结构版本号，由 Velocity 维护，请勿手动修改。',
    group: '其他',
    type: 'text',
  },
  {
    key: 'bind',
    label: '监听地址',
    desc: '代理对外监听的地址与端口（host:port）。面板创建实例时按节点端口池分配；玩家连接代理而非子服。',
    group: '网络',
    type: 'text',
    defaultValue: '0.0.0.0:25577',
  },
  {
    key: 'motd',
    label: '服务器 MOTD',
    desc: '服务器列表里显示的介绍文字，支持 MiniMessage / 颜色代码。',
    group: '网络',
    type: 'text',
  },
  {
    key: 'show-max-players',
    label: '显示的最大人数',
    desc: '服务器列表显示的人数上限（仅展示，不限制实际转发）。',
    group: '网络',
    type: 'number',
    defaultValue: '500',
  },
  {
    key: 'online-mode',
    label: '正版验证',
    desc: '是否在代理层做正版验证。true 时子服应关闭自身正版验证并启用转发；false 为离线代理。',
    group: '安全',
    type: 'boolean',
    options: YES_NO,
    defaultValue: 'true',
  },
  {
    key: 'player-info-forwarding',
    label: '玩家信息转发模式',
    desc: '向子服传递玩家 IP / 正版档案的方式。modern 需 Paper 系子服并在 paper-global.yml 配置相同密钥；legacy 兼容 BungeeCord 插件；none 会丢失真实 IP 且不安全。',
    group: '转发',
    type: 'enum',
    options: FORWARD_MODES,
    defaultValue: 'modern',
    tip: '转发密钥需与每个子服一致，泄露密钥 = 任何人可伪造玩家身份，请勿提交到公开仓库。',
  },
  {
    key: 'forwarding-secret',
    label: '转发密钥',
    desc: 'modern 转发的共享密钥，需与所有 Paper 系子服 proxies.velocity.secret 一致。',
    group: '转发',
    type: 'text',
  },
  {
    key: 'servers.try',
    label: '默认进入顺序',
    desc: '玩家进入代理时的尝试顺序（逗号分隔），第一个可用子服为默认服；可用 /server 命令切换。',
    group: '子服',
    type: 'text',
    tip: '示例：lobby, survival。列表内子服名需与 servers 表中的键一致。',
  },
  {
    key: 'enable-query',
    label: 'Query 协议',
    desc: '是否对外提供 Query 查询（第三方服务器列表统计用）。',
    group: '远程',
    type: 'boolean',
    options: YES_NO,
    defaultValue: 'false',
  },
]

export const BUNGEE_CONFIG_DOCS: McpConfigFieldDoc[] = [
  {
    key: 'ip_forward',
    label: 'IP 转发',
    desc: '是否向子服传递真实玩家 IP 与正版档案（Spigot 子服需同步开启 bungeecord: true）。关闭时子服只能看到代理 IP。',
    group: '转发',
    type: 'boolean',
    options: YES_NO,
    defaultValue: 'false',
    tip: '开启后必须确保子服不直接对外暴露端口，否则任何人可伪造身份进入。',
  },
  {
    key: 'online_mode',
    label: '正版验证',
    desc: '是否在代理层做正版验证；子服应关闭自身正版验证（offline-mode）并依赖本项。',
    group: '安全',
    type: 'boolean',
    options: YES_NO,
    defaultValue: 'true',
  },
  {
    key: 'priorities',
    label: '默认子服顺序',
    desc: '玩家进入代理时的尝试顺序（逗号分隔），第一个可用项为默认服。',
    group: '子服',
    type: 'text',
    tip: '示例：lobby, survival。名称需与 servers 表中的键一致。',
  },
  {
    key: 'server_connect_timeout',
    label: '子服连接超时',
    desc: '连接子服的超时时间（毫秒）。',
    group: '网络',
    type: 'number',
    defaultValue: '5000',
  },
  {
    key: 'remote_ping_timeout',
    label: '子服 Ping 超时',
    desc: '探测子服状态的超时时间（毫秒）。',
    group: '网络',
    type: 'number',
    defaultValue: '5000',
  },
  {
    key: 'remote_ping_cache',
    label: 'Ping 缓存',
    desc: '子服状态缓存时长（毫秒），降低对子服的 ping 压力。',
    group: '性能',
    type: 'number',
    defaultValue: '90000',
  },
  {
    key: 'stats',
    label: '匿名统计',
    desc: '是否向 BungeeCord 上报匿名使用统计（opt-out 统计）。',
    group: '其他',
    type: 'text',
    tip: '默认值类似 80706f03-a136-4c86-8ab7-373d541a5eee，保留即可。',
  },
]

export function filterPanelKeys(properties: Record<string, string>): Record<string, string> {
  const out: Record<string, string> = {}
  Object.entries(properties).forEach(([key, value]) => {
    if (!PANEL_INTERNAL_KEYS.has(key)) {
      out[key] = value
    }
  })
  return out
}

export function typeLabel(type: McpConfigType): string {
  if (type === 'boolean') {
    return '开关'
  }
  if (type === 'number') {
    return '数字'
  }
  if (type === 'enum') {
    return '单选'
  }
  return '文本'
}
