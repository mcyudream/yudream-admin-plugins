<script setup lang="ts">
import { resourceSpecError } from '../../utils/resourceSpec.ts'
import { YdLoadingOverlay, YdSettingsLayout, YdSettingsSection } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { YdAlert, YdButton, YdForm, YdFormItem, YdInput, YdModal, YdNumberField, YdPageHeader, YdSelect, YdSwitch, YdTag, YdTextarea, useYdToast } from '@yudream/components'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { createMcPanelExtra } from '../../api/api-extra'
import { MCPANEL_PERMISSION, accountHasPermission } from '../../composables/permissions'
import { errorMessage } from '../../composables/utils'

/**
 * 实例设置（对标 MCSM InstanceDetail / TermConfig / Rcon / Ping 对话框集合）：
 * 启动命令、环境变量、Java 运行时（镜像目录）、RCON、Ping、终端展示配置 → 写入实例 spec/config。
 */
const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const extra = createMcPanelExtra(props.sdk)
const toast = useYdToast()
const route = useRoute()
const router = useRouter()

const canManage = computed(() => accountHasPermission(props.sdk.account, MCPANEL_PERMISSION.manage))
const instanceId = computed(() => String(route.params.id ?? ''))

const loading = ref(false)
const saving = ref(false)
const error = ref('')
const instance = ref<Record<string, unknown> | null>(null)

/** Java 运行时镜像目录（管理端维护的内部名 + 多标签）。 */
const imageOptions = ref<Array<{ id: string, name: string, primaryImage: string, tags: string[], javaVersion?: string }>>([])
const imageDirectoryId = ref('')
const imageTag = ref('')

const imageDirectoryOptions = computed(() => [
  { label: '（不修改）', value: '' },
  ...imageOptions.value.map(item => ({
    label: `${item.name}${item.javaVersion ? ` · ${item.javaVersion}` : ''}`,
    value: item.id,
  })),
])

const selectedDirectory = computed(() =>
  imageOptions.value.find(item => item.id === imageDirectoryId.value) ?? null)

const imageTagOptions = computed(() => (selectedDirectory.value?.tags ?? [])
  .map(tag => ({ label: tag, value: tag })))

const resolvedImage = computed(() => {
  if (!selectedDirectory.value) {
    return ''
  }
  return imageTag.value || selectedDirectory.value.primaryImage
})

const imageDirty = computed(() =>
  Boolean(instance.value?.image) && resolvedImage.value !== ''
  && resolvedImage.value !== String(instance.value?.image ?? ''))

async function loadImageOptions() {
  try {
    const result = await extra.dockerImageOptions() as {
      records?: Array<{ id?: string, name?: string, primaryImage?: string, tags?: string[], javaVersion?: string }>
    }
    imageOptions.value = (result.records ?? []).map(item => ({
      id: String(item.id ?? ''),
      name: String(item.name ?? item.id ?? ''),
      primaryImage: String(item.primaryImage ?? ''),
      tags: Array.isArray(item.tags) ? item.tags.map(String) : [],
      javaVersion: String(item.javaVersion ?? ''),
    }))
    // 当前镜像反查目录：优先标签精确命中，再按 primaryImage 前缀。
    const current = String(instance.value?.image ?? '')
    const direct = imageOptions.value.find(item => item.tags.includes(current))
      ?? imageOptions.value.find(item => item.primaryImage && current.startsWith(item.primaryImage.split(':')[0]))
    if (direct) {
      imageDirectoryId.value = direct.id
      imageTag.value = direct.tags.includes(current) ? current : direct.primaryImage
    }
  }
  catch {
    imageOptions.value = []
  }
}

watch(imageDirectoryId, () => {
  // 切目录时标签回落到该目录首选镜像。
  imageTag.value = selectedDirectory.value?.primaryImage ?? ''
})

const form = reactive({
  name: '',
  command: '',
  memoryMb: 2048,
  cpuMillis: 1000,
  diskMb: 10240,
  remark: '',
  envText: '',
  rconHost: '',
  rconPort: 25575,
  rconPassword: '',
  pingAddress: '',
  pingPort: 25565,
  terminalFontSize: 13,
  terminalTimestamp: true,
  p2pEnabled: false,
  p2pWhitelistText: '',
})

/** 事件触发型任务（对标 MCSM eventTask）：面板侧语义，运行中可随时保存。 */
const eventTask = reactive({ autoRestart: false, autoStart: false })
const savingEventTask = ref(false)

/** 端口管理：附加端口开放/回收（停止态操作，成功后节点按新端口重建容器）。 */
interface PortRow {
  hostPort: number
  containerPort: number
  proto: string
  primary: boolean
  address?: string
}
const portsView = ref<{ accessHost?: string, ports?: PortRow[], ranges?: Record<string, number> } | null>(null)
const portProto = ref<'tcp' | 'udp'>('tcp')
const portInput = ref('')
const portBusy = ref(false)
const removeTarget = ref<PortRow | null>(null)
const removeOpen = computed({
  get: () => removeTarget.value != null,
  set: (open: boolean) => {
    if (!open) {
      removeTarget.value = null
    }
  },
})

const running = computed(() => String(instance.value?.state ?? '') === 'running')

const portRangeHint = computed(() => {
  const ranges = portsView.value?.ranges
  if (!ranges?.tcpStart || !ranges?.tcpEnd) {
    return ''
  }
  return `可用范围：tcp ${ranges.tcpStart}-${ranges.tcpEnd} · udp ${ranges.udpStart}-${ranges.udpEnd}（节点端口池配置）`
})

async function loadPorts() {
  try {
    portsView.value = await extra.instancePorts(instanceId.value) as typeof portsView.value
  }
  catch {
    portsView.value = null
  }
}

async function addPort() {
  if (!canManage.value || portBusy.value) {
    return
  }
  // YdInput type=number 的 v-model 回传 number（Vue 原生行为）：统一转字符串再解析，
  // 否则 number.trim() 抛 TypeError，点击处理器静默崩溃、表现为「点了没反应」。
  const raw = String(portInput.value ?? '').trim()
  let port: number | undefined
  if (raw) {
    port = Number(raw)
    if (!Number.isInteger(port) || port! <= 0) {
      toast.error('端口号必须是正整数；留空则由面板自动分配')
      return
    }
  }
  portBusy.value = true
  try {
    portsView.value = await extra.addInstancePort(instanceId.value, portProto.value, port) as typeof portsView.value
    portInput.value = ''
    toast.success('端口已开放：实例保持停止，下次启动按新端口运行')
    void load()
  }
  catch (e) {
    toast.error(errorMessage(e, '开放端口失败'))
  }
  finally {
    portBusy.value = false
  }
}

function confirmRemovePort(action: 'confirm' | 'cancel' | 'close', done: () => void) {
  if (action !== 'confirm') {
    done()
    return
  }
  void (async () => {
    const target = removeTarget.value
    if (!target || portBusy.value) {
      return
    }
    portBusy.value = true
    try {
      portsView.value = await extra.removeInstancePort(instanceId.value, target.hostPort, target.proto) as typeof portsView.value
      toast.success('端口已回收')
      void load()
      removeTarget.value = null
      done()
    }
    catch (e) {
      // beforeClose 内异常不能外抛（弹窗 loading 无 finally 兜底）：只 toast，弹窗保持打开。
      toast.error(errorMessage(e, '回收端口失败'))
    }
    finally {
      portBusy.value = false
    }
  })()
}

const closeOpen = ref(false)

function confirmClosePorts(action: 'confirm' | 'cancel' | 'close', done: () => void) {
  if (action !== 'confirm') {
    done()
    return
  }
  void (async () => {
    if (portBusy.value) {
      return
    }
    portBusy.value = true
    try {
      portsView.value = await extra.closeInstancePorts(instanceId.value) as typeof portsView.value
      toast.success('已关闭全部端口开放')
      void load()
      done()
    }
    catch (e) {
      toast.error(errorMessage(e, '关闭端口开放失败'))
    }
    finally {
      portBusy.value = false
    }
  })()
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const detail = await extra.instanceDetail(instanceId.value) as Record<string, unknown>
    instance.value = detail
    const config = (detail.config ?? {}) as Record<string, unknown>
    form.name = String(detail.name ?? '')
    form.command = Array.isArray(detail.command) ? (detail.command as string[]).join(' ') : String(detail.command ?? '')
    form.memoryMb = Number(detail.memoryMb ?? 2048)
    form.cpuMillis = Number(detail.cpuMillis ?? 1000)
    form.diskMb = Number(detail.diskMb ?? 10240)
    form.remark = String(detail.remark ?? '')
    const env = (detail.env ?? {}) as Record<string, string>
    form.envText = Object.entries(env).map(([k, v]) => `${k}=${v}`).join('\n')
    form.rconHost = String(config.rconHost ?? '')
    form.rconPort = Number(config.rconPort ?? 25575)
    form.rconPassword = String(config.rconPassword ?? '')
    form.pingAddress = String(config.pingAddress ?? '')
    form.pingPort = Number(config.pingPort ?? 25565)
    form.terminalFontSize = Number(config.terminalFontSize ?? 13)
    form.terminalTimestamp = config.terminalTimestamp !== false
    eventTask.autoRestart = detail.autoRestart === true
    eventTask.autoStart = detail.autoStart === true
    form.p2pEnabled = detail.p2pEnabled === true
    form.p2pWhitelistText = Array.isArray(detail.p2pWhitelist)
      ? (detail.p2pWhitelist as unknown[]).map(item => String(item)).join('\n')
      : ''
  }
  catch (e) {
    error.value = errorMessage(e, '加载实例设置失败')
  }
  finally {
    loading.value = false
  }
}

async function save() {
  if (!canManage.value || saving.value || !instance.value) {
    return
  }
  const problem = resourceSpecError(form.memoryMb, form.cpuMillis, form.diskMb)
  if (problem) { toast.error(problem); return }
  saving.value = true
  try {
    const env: Record<string, string> = {}
    form.envText.split('\n').forEach((line) => {
      const idx = line.indexOf('=')
      if (idx > 0) {
        env[line.slice(0, idx).trim()] = line.slice(idx + 1)
      }
    })
    const prevConfig = (instance.value.config ?? {}) as Record<string, unknown>
    const nextImage = resolvedImage.value || String(instance.value.image ?? '')
    await extra.updateInstance(instanceId.value, {
      ...(instance.value as Record<string, unknown>),
      id: instanceId.value,
      name: form.name.trim(),
      command: form.command.trim().split(/\s+/).filter(Boolean),
      image: nextImage,
      memoryMb: Number(form.memoryMb),
      cpuMillis: Number(form.cpuMillis),
      diskMb: Number(form.diskMb),
      remark: form.remark,
      p2pEnabled: form.p2pEnabled,
      p2pWhitelist: form.p2pWhitelistText
        .split(/[\s,，]+/)
        .map(item => item.trim())
        .filter(Boolean),
      env,
      config: {
        ...prevConfig,
        rconHost: form.rconHost.trim(),
        rconPort: Number(form.rconPort),
        rconPassword: form.rconPassword,
        pingAddress: form.pingAddress.trim(),
        pingPort: Number(form.pingPort),
        terminalFontSize: Number(form.terminalFontSize),
        terminalTimestamp: form.terminalTimestamp,
      },
    })
    toast.success(`实例设置已保存${imageDirty.value ? '；镜像已更新，下次启动按新 Java 运行时重建容器' : '（运行中实例需先停止才能改规格）'}`)
    void load()
    void loadImageOptions()
  }
  catch (e) {
    toast.error(errorMessage(e, '保存失败：运行中实例禁止改配置，请先停止'))
  }
  finally {
    saving.value = false
  }
}

/** 事件任务开关独立保存：纯面板侧字段，不受「运行中禁改规格」限制。 */
async function saveEventTask() {
  if (!canManage.value || savingEventTask.value) {
    return
  }
  savingEventTask.value = true
  try {
    await extra.saveEventTask(instanceId.value, eventTask.autoRestart, eventTask.autoStart)
    toast.success('事件任务已保存')
    void load()
  }
  catch (e) {
    toast.error(errorMessage(e, '事件任务保存失败'))
  }
  finally {
    savingEventTask.value = false
  }
}

watch(() => route.params.id, () => {
  void load().then(() => {
    void loadImageOptions()
    void loadPorts()
  })
})
onMounted(() => {
  void load().then(() => {
    void loadImageOptions()
    void loadPorts()
  })
})

const activeSection = ref('sec1')
const sectionList: { icon?: string, label: string, value: string }[] = [
  { label: "基础与启动", value: "sec1" },
  { label: "事件触发任务", value: "sec2" },
  { label: "Java 运行时", value: "sec3" },
  { label: "端口管理", value: "sec4" },
  { label: "P2P 直连", value: "sec5" },
  { label: "RCON / Ping", value: "sec6" },
]
</script>

<template>
  <div class="settings-page-shell">
  <YdPageHeader :title="`实例设置 · ${instance?.name || instanceId}`" description="启动命令、环境变量、RCON / Ping、终端展示（对标 MCSM 实例设置）">
    <YdButton variant="outline" @click="router.push(`/platform/plugins/mcpanel/admin/instances/${instanceId}`)">
      返回终端
    </YdButton>
    <YdButton v-if="canManage && activeSection === 'sec2'" :loading="savingEventTask" @click="saveEventTask">
      保存事件任务
    </YdButton>
    <YdButton v-else-if="canManage" :loading="saving" @click="save">
      保存设置
    </YdButton>
  </YdPageHeader>
  <YdLoadingOverlay :loading="loading">
  <YdSettingsLayout
    v-model="activeSection"
    :list="sectionList"
    surface
    scroll
    width-class="max-w-[960px]"
  >
    <template #sec1>
      <YdSettingsSection title="基础与启动">
        <YdForm :model="form" :disabled="!canManage || saving">
          <YdFormItem field="name" label="名称" required><YdInput v-model="form.name" placeholder="实例名称" /></YdFormItem>
          <YdFormItem field="command" label="启动命令" required><YdTextarea v-model="form.command" :rows="2" placeholder="java -jar server.jar nogui" class="mcp-mono" /></YdFormItem>
          <div class="mcp-grid-3">
            <YdFormItem field="memoryMb" label="内存（MB）" required><YdNumberField v-model="form.memoryMb" :min="64" :max="1048576" class="w-full" /></YdFormItem>
            <YdFormItem field="cpuMillis" label="CPU（毫核）" required extra="0 表示不限；1000 毫核 = 1 核。"><YdNumberField v-model="form.cpuMillis" :min="0" :max="256000" :step="100" class="w-full" /></YdFormItem>
            <YdFormItem field="diskMb" label="磁盘（MB）" required><YdNumberField v-model="form.diskMb" :min="64" :max="104857600" class="w-full" /></YdFormItem>
          </div>
          <YdFormItem field="envText" label="环境变量" extra="每行 KEY=VALUE。"><YdTextarea v-model="form.envText" :rows="4" placeholder="JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8" class="mcp-mono" /></YdFormItem>
          <YdFormItem field="remark" label="备注"><YdInput v-model="form.remark" placeholder="选填" /></YdFormItem>
        </YdForm>
      </YdSettingsSection>
    </template>
    <template #sec2>
      <YdSettingsSection title="事件触发型任务">
<div class="mcp-form">
          <label class="flex items-start gap-3 text-sm">
            <YdSwitch v-model="eventTask.autoRestart" />
            <span>
              <b>自动重启</b>
              <span class="mcp-form-hint block">
                若实例在未经面板操作的情况下变为非运行状态（崩溃、意外退出），将立刻发起一次启动实例操作。
                面板发起的停止 / 重启 / 强杀 / 删除，以及控制台输入 stop，都不会触发自动重启。
              </span>
            </span>
          </label>
          <label class="flex items-start gap-3 text-sm">
            <YdSwitch v-model="eventTask.autoStart" />
            <span>
              <b>自动启动</b>
              <span class="mcp-form-hint block">
                只要远程节点处于运行（与面板连接）状态，就自动发起一次启动实例操作。
                如果将节点程序设为开机自启，则可用于开机自启实例。
              </span>
            </span>
          </label>
          <span class="mcp-form-hint">
            自动拉起后若实例持续快速崩溃（60 秒内连续 3 次），会暂停自动重启并在审计中提示；手动启动一次即恢复。
          </span>
        </div>
      </YdSettingsSection>
    </template>
    <template #sec3>
      <YdSettingsSection title="Java 运行时（换版本 / 镜像源）">
<div class="mcp-form">
          <label class="mcp-form-item">
            <span class="mcp-form-label">运行时目录</span>
            <YdSelect v-model="imageDirectoryId" :options="imageDirectoryOptions" class="mcp-w-full" placeholder="选择 Java 版本对应的运行时" />
          </label>
          <label v-if="selectedDirectory?.tags?.length" class="mcp-form-item">
            <span class="mcp-form-label">标签 / 拉取地址</span>
            <YdSelect v-model="imageTag" :options="imageTagOptions" class="mcp-w-full" />
          </label>
          <div class="mcp-form-item">
            <span class="mcp-form-label">生效镜像</span>
            <div class="rounded-lg border bg-muted/40 px-3 py-2 font-mono text-xs break-all">
              {{ resolvedImage || String(instance?.image ?? '-') }}
              <YdTag v-if="imageDirty" variant="secondary" class="ml-2">
                已修改
              </YdTag>
            </div>
            <p class="mcp-form-hint">
              换 Java 版本 = 换 JRE 镜像。运行中的实例需先停止，保存后下次启动按新镜像重建容器；
              数据目录（存档/配置）不受影响。
            </p>
          </div>
        </div>
      </YdSettingsSection>
    </template>
    <template #sec4>
      <YdSettingsSection title="端口管理（附加端口开放/回收）">
<div class="mcp-form">
          <YdAlert v-if="running" icon="i-lucide:info" title="实例运行中">
            <template #description>
              开放或回收端口会重建容器，需先停止实例再操作。
            </template>
          </YdAlert>
          <div
            v-for="row in portsView?.ports ?? []"
            :key="`${row.proto}-${row.hostPort}`"
            class="flex flex-wrap items-center gap-2 rounded-lg border px-3 py-2 text-sm sm:gap-3"
          >
            <YdTag :variant="row.primary ? 'default' : 'secondary'">
              {{ row.primary ? '主端口' : '附加端口' }}
            </YdTag>
            <span class="font-mono">{{ row.hostPort }}/{{ row.proto }}</span>
            <span class="ml-auto font-mono text-xs text-muted-foreground">
              {{ row.address || '节点未配置对外地址' }}
            </span>
            <YdButton
              v-if="canManage && !row.primary && !running"
              variant="outline"
              @click="removeTarget = row"
            >
              回收
            </YdButton>
          </div>
          <div v-if="!portsView?.ports?.length" class="mcp-form-hint">
            端口已全部关闭：实例不对节点宿主机发布任何端口，玩家与代理都无法直连（重新开放会在端口池内分配新主端口）。
          </div>
          <div v-if="canManage && !running" class="flex flex-wrap items-end gap-3">
            <label class="mcp-form-item w-32">
              <span class="mcp-form-label">协议</span>
              <YdSelect
                v-model="portProto"
                :options="[{ label: 'TCP', value: 'tcp' }, { label: 'UDP', value: 'udp' }]"
                class="mcp-w-full"
              />
            </label>
            <label class="mcp-form-item min-w-40 flex-1">
              <span class="mcp-form-label">端口号（留空自动分配）</span>
              <YdInput v-model="portInput" type="number" placeholder="自动分配" class="mcp-w-full" />
            </label>
            <YdButton :loading="portBusy" @click="addPort">
              开放端口
            </YdButton>
            <YdButton
              v-if="portsView?.ports?.length"
              variant="destructive"
              :disabled="portBusy"
              @click="closeOpen = true"
            >
              关闭全部端口开放
            </YdButton>
          </div>
          <span class="mcp-form-hint">
            {{ portRangeHint }}。开放/回收后节点会按新端口重建容器（存档与配置不受影响，实例保持停止态）。
            附加插件（如 Dynmap、BlueMap 的 web 端口）请在插件配置中填写对应端口号；
            访问地址按「节点直连地址:端口」计算，域名与单端口入口模式仅服务游戏主端口。
          </span>
        </div>
      </YdSettingsSection>
    </template>
    <template #sec5>
      <YdSettingsSection title="P2P 直连（启动器无感接入）">
<div class="mcp-form">
          <label class="flex items-start gap-3 text-sm">
            <YdSwitch v-model="form.p2pEnabled" />
            <span>
              允许玩家经启动器 P2P 直连本实例（面板不中转玩家流量）
            </span>
          </label>
          <label class="mcp-form-item">
            <span class="mcp-form-label">白名单（选填，每行或逗号分隔用户 ID）</span>
            <YdTextarea v-model="form.p2pWhitelistText" :rows="2" class="w-full" placeholder="留空 = 所有玩家可连" />
          </label>
          <span class="mcp-form-hint">
            前置条件：① 面板设置 →「启动器 P2P 直连」已开启并签发票据；② 节点程序支持 p2p.* 能力；
            ③ 实例处于运行中。玩家侧**无感**：启动器点「连接」即自动开会话与建隧道，玩家不需要任何配置或入口；
            当前传输为直连（节点公网可达或已做端口映射），打洞落地后 NAT 场景自动可用。
          </span>
        </div>
      </YdSettingsSection>
    </template>
    <template #sec6>
      <YdSettingsSection title="RCON / Ping / 终端">
<div class="mcp-form">
          <label class="mcp-form-item"><span class="mcp-form-label">RCON 主机</span><YdInput v-model="form.rconHost" class="mcp-w-full" placeholder="127.0.0.1" /></label>
          <div class="mcp-grid-2">
            <label class="mcp-form-item"><span class="mcp-form-label">RCON 端口</span><YdInput v-model="form.rconPort" type="number" class="mcp-w-full" /></label>
            <label class="mcp-form-item"><span class="mcp-form-label">RCON 密码</span><YdInput v-model="form.rconPassword" type="password" class="mcp-w-full" /></label>
          </div>
          <div class="mcp-grid-2">
            <label class="mcp-form-item"><span class="mcp-form-label">Ping 地址</span><YdInput v-model="form.pingAddress" class="mcp-w-full" /></label>
            <label class="mcp-form-item"><span class="mcp-form-label">Ping 端口</span><YdInput v-model="form.pingPort" type="number" class="mcp-w-full" /></label>
          </div>
          <div class="mcp-grid-2">
            <label class="mcp-form-item"><span class="mcp-form-label">终端字号</span><YdInput v-model="form.terminalFontSize" type="number" class="mcp-w-full" /></label>
            <label class="mcp-form-item"><span class="mcp-form-label">时间戳</span><YdSwitch v-model="form.terminalTimestamp" /></label>
          </div>
          <p class="mcp-form-hint">
            RCON/Ping 为实例级配置存储；面板侧 RCON 协议发送可在节点能力具备后接入。当前控制台命令仍走容器 stdin。
          </p>
        </div>
      </YdSettingsSection>
    </template>
  </YdSettingsLayout>
  </YdLoadingOverlay>
  </div>
  <YdModal
    v-model="removeOpen"
    title="回收端口"
    confirm-button-text="确认回收"
    :confirm-button-loading="portBusy"
    :before-close="confirmRemovePort"
  >
    <p v-if="removeTarget" class="text-sm">
      确认回收端口 <span class="font-mono">{{ removeTarget.hostPort }}/{{ removeTarget.proto }}</span>？
      节点会按剩余端口重建容器，实例保持停止态；该端口随之释放回节点端口池。
    </p>
  </YdModal>
  <YdModal
    v-model="closeOpen"
    title="关闭全部端口开放"
    confirm-button-text="确认关闭"
    :confirm-button-loading="portBusy"
    :before-close="confirmClosePorts"
  >
    <p class="text-sm">
      确认关闭该实例的全部 {{ portsView?.ports?.length ?? 0 }} 个端口？
      关闭后容器不再向节点宿主机发布任何端口——玩家与代理（含代理组子服互通）都无法直连本实例，
      仅代理与实例同机走内网/容器网络等替代路径时可用；实例域名与 P2P 直连也随之失效。
      端口全部释放回节点端口池，重新开放时分配新主端口。存档与配置不受影响，实例保持停止态。
    </p>
  </YdModal>
</template>
