<script setup lang="ts">
import type { McpNode, McpNodeAccessMode, McpNodeSaveRequest, McpTlsMode } from '../types'
import { computed, reactive, ref, watch } from 'vue'
import { FaAlert, FaButton, FaInput, FaModal, FaSelect, FaSwitch, FaTextarea } from '@yudream/components'
import type { McPanelApi } from '../api/mcpanel-api'
import { errorMessage } from '../composables/utils'

const props = defineProps<{
  api: McPanelApi
  /** null = 创建；否则编辑该节点。 */
  node: McpNode | null
}>()

const emit = defineEmits<{
  'saved': [node: McpNode]
  'created': [node: McpNode]
}>()

const open = defineModel<boolean>('open', { default: false })

const TLS_OPTIONS: Array<{ label: string, value: McpTlsMode }> = [
  { label: '标准证书校验（PKIX）', value: 'pkix' },
  { label: '自签指纹钉住（pinned）', value: 'pinned' },
]

const submitting = ref(false)
const formError = ref('')

const form = reactive({
  name: '',
  endpoint: '',
  sftpHost: '',
  accessMode: 'direct' as McpNodeAccessMode,
  accessHost: '',
  tlsMode: 'pkix' as McpTlsMode,
  pinSha256: '',
  portRangeStart: '' as string | number,
  portRangeEnd: '' as string | number,
  localDevelopment: false,
  enabled: true,
  remark: '',
})

/** 玩家接入方式：决定实例域名解析记录怎么写（后端 NodeAccess）。 */
const ACCESS_MODES: Array<{ label: string, value: McpNodeAccessMode }> = [
  { label: '节点直连（用节点对外地址）', value: 'direct' },
  { label: '自定义解析地址（自己填 IP）', value: 'manual' },
  { label: 'FRP 入口（入口机地址，端口 1:1）', value: 'frp' },
  { label: '单端口入口（mc-router 按域名转发）', value: 'entry' },
  { label: '启动器打洞（不写公网解析）', value: 'p2p' },
]

const accessHint = computed(() => {
  switch (form.accessMode) {
    case 'manual':
      return '实例域名解析指向该地址（填 IP 或主机名）：适用于家用宽带端口映射 / DDNS / 反向代理——填你对外实际可达的地址。'
    case 'frp':
      return '实例域名解析指向 FRP 入口机（填 IP 或主机名）。约定入口端口与实例端口 1:1（如 frps 映射 25565–25665 到节点同端口）；映射不同请改用「自定义解析地址」。'
    case 'entry':
      return '切换为该模式后，节点上实例的 PROXY protocol 会自动开启（玩家 IP 才不会被入口机地址覆盖）；'
        + '切回其它模式会自动关闭。所有实例共用一个入口端口：解析 A 记录指向「面板设置 → 单端口入口」里填的入口地址，'
        + '玩家用各自域名连接，入口（mc-router）按域名转发到本节点实例。下方地址填「入口机可达的本节点地址」（选填）：'
        + '节点与入口同机（frp 映射到本机）填 127.0.0.1，留空则用节点对外地址。'
    case 'p2p':
      return '玩家经启动器打洞直连，实例不开放公网映射，因此不写公网解析记录（实例域名页会禁用分配）。'
    default:
      return '解析指向节点对外地址（留空 = 控制信道地址的 host）。典型用法：控制信道走内网地址、这里填公网 IP/DDNS 域名，玩家从公网接入。'
  }
})

watch(open, (visible) => {
  if (!visible) {
    return
  }
  formError.value = ''
  const node = props.node
  form.name = node?.name ?? ''
  form.endpoint = node?.endpoint ?? ''
  form.sftpHost = node?.sftpHost ?? ''
  form.accessMode = (node?.accessMode as McpNodeAccessMode) ?? 'direct'
  form.accessHost = node?.accessHost ?? ''
  form.tlsMode = (node?.tlsMode as McpTlsMode) ?? 'pkix'
  form.pinSha256 = node?.pinSha256 ?? ''
  form.portRangeStart = node?.portRangeStart ?? ''
  form.portRangeEnd = node?.portRangeEnd ?? ''
  form.localDevelopment = node?.localDevelopment ?? false
  form.enabled = node?.enabled ?? true
  form.remark = node?.remark ?? ''
})

const pinned = computed(() => form.tlsMode === 'pinned')
/** 只有「自定义解析地址 / FRP 入口」需要在节点上填写解析地址。 */
const needsAccessHost = computed(() => form.accessMode === 'manual' || form.accessMode === 'frp')

function toPort(value: string | number): number | null {
  if (value === '' || value === null || value === undefined) {
    return null
  }
  const port = Number(value)
  if (!Number.isInteger(port)) {
    return null
  }
  return port
}

function isValidHost(host: string): boolean {
  if (host.includes('://') || host.includes('/') || host.includes('\\') || /\s/.test(host)) {
    return false
  }
  const isIpv4 = /^(\d{1,3}\.){3}\d{1,3}$/.test(host) && host.split('.').every(part => Number(part) <= 255)
  const isIpv6 = host.includes(':') && /^[0-9a-fA-F:]+$/.test(host)
  const isHostname = !host.includes(':') && !/^[0-9.]+$/.test(host)
    && /^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$/.test(host)
  return isIpv4 || isIpv6 || isHostname
}

function validate(): string {
  if (!form.name.trim()) {
    return '请输入节点名称'
  }
  if (form.accessMode === 'entry' && form.accessHost.trim() && !isValidHost(form.accessHost.trim())) {
    return '入口后端地址只能是 IP（IPv4/IPv6）或主机名，且不能带端口'
  }
  if (needsAccessHost.value) {
    const host = form.accessHost.trim()
    if (!host) {
      return '请填写解析地址（IP 或主机名）'
    }
    if (host.includes('://') || host.includes('/') || host.includes('\\') || /\s/.test(host)) {
      return '解析地址不能带协议、端口或路径'
    }
    if (!isValidHost(host)) {
      return '解析地址只能是 IP（IPv4/IPv6）或主机名，且不能带端口'
    }
  }
  const endpoint = form.endpoint.trim()
  if (!endpoint) {
    return '请输入控制信道地址（wss://…）'
  }
  if (!/^wss:\/\//i.test(endpoint)) {
    return '控制信道地址必须以 wss:// 开头（强制 TLS，明文 ws:// 一律禁止）'
  }
  try {
    const url = new URL(endpoint)
    if (url.pathname && url.pathname !== '/') {
      return '地址只需 wss://host[:port]，不要带路径（/control 由面板统一追加）'
    }
    if (url.username || url.password) {
      return '地址不能携带用户名或密码'
    }
    if (url.search) {
      return '地址不能携带查询参数'
    }
    if (url.hash) {
      return '地址不能携带锚点（#）'
    }
    if (url.port) {
      const port = Number(url.port)
      if (!Number.isInteger(port) || port < 1 || port > 65535) {
        return '端口必须是 1-65535 的整数'
      }
    }
    const host = url.hostname.toLowerCase()
    if (!host) {
      return '请填写有效的主机地址'
    }
    const isLoopback = host === 'localhost' || host.endsWith('.localhost') || host === '::1' || /^127\.\d{1,3}\.\d{1,3}\.\d{1,3}$/.test(host)
    if (isLoopback && !form.localDevelopment) {
      return 'loopback 目标地址需开启「本地开发模式」'
    }
    if (!isLoopback && form.localDevelopment) {
      return '「本地开发模式」仅允许 loopback 目标地址'
    }
  }
  catch {
    return '控制信道地址格式无效'
  }
  if (pinned.value) {
    const pinSha256 = form.pinSha256.trim().replace(/:/g, '').toLowerCase()
    if (!pinSha256 && props.node) {
      // 新建节点允许留空：注册（enroll）时以节点上报指纹自动登记（TOFU）。
      // 已注册节点不会再 enroll，清空指纹等于放弃钉住，必须明确保留。
      return '已注册的 pinned 节点必须保留证书指纹，不能清空'
    }
    if (pinSha256 && !/^[0-9a-f]{64}$/.test(pinSha256)) {
      return '指纹格式应为 64 位十六进制（sha256）'
    }
  }
  const rangeStart = toPort(form.portRangeStart)
  const rangeEnd = toPort(form.portRangeEnd)
  if ((rangeStart === null) !== (rangeEnd === null)) {
    return '端口分配范围需成对填写（起始与结束端口都填或都留空）'
  }
  if (rangeStart !== null && rangeEnd !== null) {
    if (rangeStart < 1 || rangeStart > 65535 || rangeEnd < 1 || rangeEnd > 65535) {
      return '端口分配范围必须是 1-65535 的整数'
    }
    if (rangeStart > rangeEnd) {
      return '端口分配范围起始端口不能大于结束端口'
    }
  }
  return ''
}

function buildRequest(): McpNodeSaveRequest {
  // 后端 PUT null = 保持现值：布尔与 remark 必须始终显式发送，否则 true→false、
  // 清空备注都无法生效；pinSha256 在切回 pkix 时发空串以清除旧指纹。
  const request: McpNodeSaveRequest = {
    name: form.name.trim(),
    endpoint: form.endpoint.trim(),
    sftpHost: form.sftpHost.trim(),
    accessMode: form.accessMode,
    accessHost: (needsAccessHost.value || form.accessMode === 'entry') ? form.accessHost.trim() : '',
    tlsMode: form.tlsMode,
    pinSha256: pinned.value ? form.pinSha256.trim().replace(/:/g, '').toLowerCase() : '',
    localDevelopment: form.localDevelopment,
    remark: form.remark.trim(),
  }
  // 端口范围：创建时留空 = 缺省回落默认段（不发送）；更新时 null = 保持现值，
  // 已设范围的节点被清空时发 0/0 让后端清除并回落默认段。
  const rangeStart = toPort(form.portRangeStart)
  const rangeEnd = toPort(form.portRangeEnd)
  if (rangeStart !== null && rangeEnd !== null) {
    request.portRangeStart = rangeStart
    request.portRangeEnd = rangeEnd
  }
  else if (props.node && (props.node.portRangeStart != null || props.node.portRangeEnd != null)) {
    request.portRangeStart = 0
    request.portRangeEnd = 0
  }
  if (props.node) {
    request.enabled = form.enabled
  }
  return request
}

/** 门控关闭：confirm 时先校验并提交，成功才 done()；取消/直接关闭立即放行。 */
function beforeClose(action: 'confirm' | 'cancel' | 'close', done: () => void) {
  if (action !== 'confirm') {
    done()
    return
  }
  void submit(done)
}

async function submit(done: () => void) {
  formError.value = validate()
  if (formError.value || submitting.value) {
    return
  }
  submitting.value = true
  try {
    const request = buildRequest()
    if (props.node) {
      const node = await props.api.updateNode(props.node.id, request)
      done()
      emit('saved', node)
    }
    else {
      const node = await props.api.createNode(request)
      done()
      emit('created', node)
    }
  }
  catch (error) {
    formError.value = errorMessage(error, '保存失败，请稍后重试')
  }
  finally {
    submitting.value = false
  }
}
</script>

<template>
  <FaModal
    v-model="open"
    :title="node ? '编辑节点' : '新增节点'"
    :show-cancel-button="true"
    :confirm-button-text="node ? '保存修改' : '创建节点'"
    :confirm-button-loading="submitting"
    class="max-w-[min(42rem,calc(100vw-2rem))]"
    :before-close="beforeClose"
  >
    <div class="mcp-form">
      <label class="mcp-form-item">
        <span class="mcp-form-label">节点名称</span>
        <FaInput v-model="form.name" clearable placeholder="例如：家中-NAT-01" class="w-full" />
      </label>
      <label class="mcp-form-item">
        <span class="mcp-form-label">控制信道地址</span>
        <FaInput v-model="form.endpoint" clearable placeholder="wss://节点主机:端口" class="w-full" />
        <span class="mcp-form-hint">只填 wss://host[:port]，/control 路径由面板统一追加。面板服务器会主动拨号该地址：必须填<b>面板可达</b>的地址。节点在无公网映射的内网/容器里时，需先做端口映射或内网穿透（frp、tailscale 等），把穿透后的 wss 地址填到这里。</span>
      </label>
      <label class="mcp-form-item">
        <span class="mcp-form-label">节点对外地址（选填）</span>
        <FaInput v-model="form.sftpHost" clearable placeholder="默认取控制信道地址的 host" class="w-full" />
        <span class="mcp-form-hint">给<b>玩家接入</b>用的地址：接入方式选「节点直连」时，实例域名解析指向这里。典型场景——控制信道走内网地址（面板与节点同内网），这里填<b>公网 IP / DDNS 域名</b>供玩家从公网接入，两者互不影响；留空 = 解析也指向控制信道 host。开启面板 SFTP 网关后 SFTP 全部经网关中转、不使用该地址（仅网关关闭时，FTP 客户端才会直连它，须填你电脑可达的地址）。</span>
      </label>
      <div class="mcp-form-item">
        <span class="mcp-form-label">玩家接入方式（域名解析取值）</span>
        <FaSelect v-model="form.accessMode" :options="ACCESS_MODES" class="w-full" />
        <span class="mcp-form-hint">{{ accessHint }}</span>
      </div>
      <label v-if="needsAccessHost" class="mcp-form-item">
        <span class="mcp-form-label">{{ form.accessMode === 'frp' ? 'FRP 入口地址' : '解析地址' }}</span>
        <FaInput v-model="form.accessHost" clearable placeholder="IP 或主机名（如 1.2.3.4 / frp.example.com）" class="w-full" />
        <span class="mcp-form-hint">IP 会写成 A/AAAA 记录；主机名会写成 CNAME（此时 SRV 目标直接指向该主机）。</span>
      </label>
      <label v-if="form.accessMode === 'entry'" class="mcp-form-item">
        <span class="mcp-form-label">入口后端地址（选填）</span>
        <FaInput v-model="form.accessHost" clearable placeholder="入口机可达的节点地址，如 127.0.0.1 / 10.0.0.8" class="w-full" />
        <span class="mcp-form-hint">
          面板会把「域名 → 该地址:实例端口」推送给入口；节点与入口同机（frp 映射到本机端口）时填 127.0.0.1，留空则用节点对外地址。
        </span>
      </label>
      <div class="mcp-form-item">
        <span class="mcp-form-label">实例端口分配范围（选填）</span>
        <div class="flex items-center gap-2">
          <FaInput v-model="form.portRangeStart" type="number" placeholder="起始端口" class="w-full" />
          <span class="shrink-0 text-sm opacity-60">–</span>
          <FaInput v-model="form.portRangeEnd" type="number" placeholder="结束端口" class="w-full" />
        </div>
        <span class="mcp-form-hint">该节点上实例对外端口从该区间分配；留空回落面板默认段。编辑时清空已设范围即回落默认段。</span>
      </div>
      <label class="mcp-form-item">
        <span class="mcp-form-label">TLS 校验</span>
        <FaSelect v-model="form.tlsMode" :options="TLS_OPTIONS" class="w-full" />
      </label>
      <div v-if="pinned" class="mcp-form-item">
        <span class="mcp-form-label">预批准指纹（sha256）</span>
        <FaTextarea v-model="form.pinSha256" :rows="2" placeholder="留空则节点注册时自动登记自签证书指纹（推荐）" class="w-full font-mono" />
        <div v-if="props.node?.reportedCertSha256" class="flex items-center gap-2">
          <FaButton size="sm" variant="outline" @click="form.pinSha256 = props.node!.reportedCertSha256!">
            填入注册上报指纹
          </FaButton>
          <span class="mcp-mono truncate text-xs opacity-70">{{ props.node.reportedCertSha256 }}</span>
        </div>
        <span class="mcp-form-hint">新建节点建议留空：节点完成注册时自动以自签证书指纹登记钉住，无需登机取指纹；也可在节点上执行 mcpanel-node fingerprint 手工填入。已注册节点不能清空。</span>
      </div>
      <div class="mcp-form-item">
        <span class="mcp-form-label">本地开发模式</span>
        <label class="flex items-center gap-2 text-sm">
          <FaSwitch v-model="form.localDevelopment" />
          <span>允许 loopback 目标地址</span>
        </label>
        <span class="mcp-form-hint">仅放开目标地址为 localhost/127.0.0.1（本地联调节点用）；不放宽任何 TLS 校验，仍禁止明文 ws://。</span>
      </div>
      <div v-if="node" class="mcp-form-item">
        <span class="mcp-form-label">启用状态</span>
        <label class="flex items-center gap-2 text-sm">
          <FaSwitch v-model="form.enabled" />
          <span>{{ form.enabled ? '已启用' : '已停用' }}</span>
        </label>
        <span class="mcp-form-hint">停用仅断开面板与该节点的控制信道，不会启停其上任何实例。</span>
      </div>
      <label class="mcp-form-item">
        <span class="mcp-form-label">备注</span>
        <FaTextarea v-model="form.remark" :rows="2" placeholder="选填" class="w-full" />
      </label>
      <FaAlert v-if="formError" variant="destructive" title="无法保存">
  <template #description>
        {{ formError }}
  </template>
      </FaAlert>
      <div v-else-if="!node" class="mcp-form-hint">
        创建成功后请在节点详情页签发一次性注册凭据（token 仅签发当次展示）。
      </div>
    </div>
  </FaModal>
</template>
