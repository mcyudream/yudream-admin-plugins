<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { McpNode } from '../types'
import { FaAlert, FaInput, FaModal, FaSelect, FaTextarea } from '@yudream/components'
import { computed, reactive, ref, watch } from 'vue'
import { createMcPanelExtra } from '../api/api-extra'
import { errorMessage } from '../composables/utils'

/**
 * 实例创建/编辑弹窗：节点选择 + 类型 + 镜像/命令 + 资源 + 端口协议提示。
 * 创建成功后由父页面刷新；编辑要求实例已停止（后端强制）。
 */
const props = defineProps<{
  sdk: YuDreamPluginSdk
  instance: Record<string, unknown> | null
  nodes: McpNode[]
}>()

const emit = defineEmits<{ saved: [instance: Record<string, unknown>] }>()
const open = defineModel<boolean>('open', { default: false })
const extra = createMcPanelExtra(props.sdk)

const KIND_OPTIONS = [
  { label: 'Paper（插件服）', value: 'paper' },
  { label: 'Purpur（插件服）', value: 'purpur' },
  { label: 'Folia（多线程插件服）', value: 'folia' },
  { label: 'Fabric（模组服）', value: 'fabric' },
  { label: 'Forge（模组服）', value: 'forge' },
  { label: 'NeoForge（模组服）', value: 'neoforge' },
  { label: 'Quilt（模组服）', value: 'quilt' },
  { label: '原版', value: 'vanilla' },
  { label: 'Velocity（代理）', value: 'velocity' },
  { label: 'BungeeCord（代理）', value: 'bungee' },
  { label: '基岩版（UDP）', value: 'bedrock' },
  { label: '通用控制台应用', value: 'generic' },
]

const submitting = ref(false)
const formError = ref('')

const form = reactive({
  id: '',
  name: '',
  nodeId: '',
  kind: 'paper',
  mcVersion: '',
  image: 'eclipse-temurin:21-jre',
  command: 'java -Xms512M -Xmx2G -jar server.jar nogui',
  memoryMb: 2048,
  cpuMillis: 1000,
  diskMb: 10240,
  remark: '',
  mcServerId: '',
  startDetect: '',
})

/** 各类型的默认启动成功标记（与节点 DefaultStartDetect 一致）；generic 无默认。 */
const KIND_DEFAULT_START_DETECT: Record<string, string> = {
  vanilla: 'Done (',
  paper: 'Done (',
  purpur: 'Done (',
  folia: 'Done (',
  fabric: 'Done (',
  forge: 'Done (',
  neoforge: 'Done (',
  quilt: 'Done (',
  velocity: 'Done (',
  bungee: 'Listening on',
  bedrock: 'Server started',
  generic: '',
}
const startDetectPlaceholder = computed(() => KIND_DEFAULT_START_DETECT[form.kind] ?? '')

/** M5 联动：子服绑定选项（提供方缺失时 available=false 并隐藏选择器）。 */
const linkAvailable = ref(false)
const linkServers = ref<Array<Record<string, unknown>>>([])
const mcServerOptions = computed(() => [
  { label: '不绑定', value: '' },
  ...linkServers.value.map(server => ({
    label: `${server.name}${server.enabled === false ? '（已停用）' : ''}`,
    value: String(server.id),
  })),
])

async function loadLinkOptions() {
  try {
    const result = await extra.linkOptions() as { available?: boolean, servers?: Array<Record<string, unknown>> }
    linkAvailable.value = Boolean(result?.available)
    linkServers.value = (result?.servers ?? []).filter(server => server.enabled !== false)
  }
  catch {
    linkAvailable.value = false
    linkServers.value = []
  }
}

watch(open, (visible) => {
  if (!visible) {
    return
  }
  formError.value = ''
  const source = props.instance
  form.id = source ? String(source.id ?? '') : ''
  form.name = source ? String(source.name ?? '') : ''
  form.nodeId = source ? String(source.nodeId ?? '') : (props.nodes[0]?.id ?? '')
  form.kind = source ? String(source.kind ?? 'paper') : 'paper'
  form.mcVersion = source ? String(source.mcVersion ?? '') : ''
  form.image = source ? String(source.image ?? '') : 'eclipse-temurin:21-jre'
  const command = source?.command
  form.command = Array.isArray(command) ? command.join(' ') : 'java -Xms512M -Xmx2G -jar server.jar nogui'
  form.memoryMb = source ? Number(source.memoryMb ?? 2048) : 2048
  form.cpuMillis = source ? Number(source.cpuMillis ?? 1000) : 1000
  form.diskMb = source ? Number(source.diskMb ?? 10240) : 10240
  form.remark = source ? String(source.remark ?? '') : ''
  form.mcServerId = source ? String(source.mcServerId ?? '') : ''
  form.startDetect = source ? String(source.startDetect ?? '') : ''
  if (visible) {
    void loadLinkOptions()
  }
})

const editing = computed(() => props.instance != null)

const nodeOptions = computed(() => props.nodes.map((node) => {
  const suffix = node.status === 'online' ? '在线' : '离线'
  return { label: node.name + '（' + suffix + '）', value: node.id }
}))

function validate(): string {
  if (!form.name.trim()) {
    return '请输入实例名称'
  }
  if (!form.nodeId) {
    return '请选择节点'
  }
  if (!form.image.trim()) {
    return '请输入容器镜像'
  }
  const parts = form.command.trim().split(/\s+/).filter(Boolean)
  if (parts.length === 0 || parts.length > 64) {
    return '启动命令不能为空且参数最多 64 项'
  }
  if (form.memoryMb < 64 || form.memoryMb > 1048576) {
    return '内存需在 64MB 至 1TB 之间'
  }
  if (form.cpuMillis < 100 || form.cpuMillis > 256000) {
    return 'CPU 需在 0.1 至 256 核之间'
  }
  return ''
}

function buildPayload() {
  return {
    id: form.id || `inst-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`,
    nodeId: form.nodeId,
    name: form.name.trim(),
    kind: form.kind,
    mcVersion: form.mcVersion.trim() || null,
    image: form.image.trim(),
    command: form.command.trim().split(/\s+/).filter(Boolean),
    env: {},
    mcServerId: form.mcServerId || null,
    memoryMb: form.memoryMb,
    cpuMillis: form.cpuMillis,
    diskMb: form.diskMb,
    config: {},
    remark: form.remark.trim() || null,
    startDetect: form.startDetect.trim(),
  }
}

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
    const payload = buildPayload()
    const saved = editing.value
      ? await extra.updateInstance(form.id, payload)
      : await extra.createInstance(payload)
    done()
    emit('saved', saved as Record<string, unknown>)
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
    :title="editing ? '编辑实例' : '新建实例'"
    :show-cancel-button="true"
    :confirm-button-text="editing ? '保存修改' : '创建实例'"
    :confirm-button-loading="submitting"
    class="max-w-[min(42rem,calc(100vw-2rem))]"
    :before-close="beforeClose"
  >
    <div class="mcp-form">
      <label class="mcp-form-item">
        <span class="mcp-form-label">实例名称</span>
        <FaInput v-model="form.name" clearable placeholder="例如：生存服-01" class="w-full" />
      </label>
      <label class="mcp-form-item">
        <span class="mcp-form-label">所属节点</span>
        <FaSelect v-model="form.nodeId" :options="nodeOptions" class="w-full" />
        <span class="mcp-form-hint">实例将调度到所选节点并由其 Docker 运行。</span>
      </label>
      <label class="mcp-form-item">
        <span class="mcp-form-label">服务端类型</span>
        <FaSelect v-model="form.kind" :options="KIND_OPTIONS" class="w-full" />
        <span class="mcp-form-hint">基岩版使用 UDP 端口；端口由面板按节点端口池自动分配。</span>
      </label>
      <label class="mcp-form-item">
        <span class="mcp-form-label">MC 版本（可选）</span>
        <FaInput v-model="form.mcVersion" clearable placeholder="例如 1.21.4" class="w-full" />
      </label>
      <label class="mcp-form-item">
        <span class="mcp-form-label">容器镜像</span>
        <FaInput v-model="form.image" clearable placeholder="eclipse-temurin:21-jre" class="w-full font-mono" />
      </label>
      <label class="mcp-form-item">
        <span class="mcp-form-label">启动命令</span>
        <FaTextarea v-model="form.command" :rows="2" placeholder="java -Xms512M -Xmx2G -jar server.jar nogui" class="w-full font-mono" />
        <span class="mcp-form-hint">空格分隔；工作目录固定为 /data（实例数据卷）。</span>
      </label>
      <div class="grid grid-cols-1 gap-3 md:grid-cols-3">
        <label class="mcp-form-item">
          <span class="mcp-form-label">内存（MB）</span>
          <FaInput v-model="form.memoryMb" type="number" class="w-full" />
        </label>
        <label class="mcp-form-item">
          <span class="mcp-form-label">CPU（毫核）</span>
          <FaInput v-model="form.cpuMillis" type="number" class="w-full" />
        </label>
        <label class="mcp-form-item">
          <span class="mcp-form-label">磁盘（MB）</span>
          <FaInput v-model="form.diskMb" type="number" class="w-full" />
        </label>
      </div>
      <label v-if="linkAvailable" class="mcp-form-item">
        <span class="mcp-form-label">绑定子服（minecraft-server 联动）</span>
        <FaSelect v-model="form.mcServerId" :options="mcServerOptions" class="w-full" />
        <span class="mcp-form-hint">绑定后创建/改配将注入 MCSERVER_ID 与 MCSERVER_TERM 环境变量（时长插件按子服记账）；留空即解绑，解绑不动实例数据。</span>
      </label>
      <label class="mcp-form-item">
        <span class="mcp-form-label">启动成功检测（可选）</span>
        <FaInput v-model="form.startDetect" clearable :placeholder="startDetectPlaceholder || '不启用检测'" class="w-full font-mono" />
        <span class="mcp-form-hint">控制台输出包含该内容即判定「启动成功」并从启动中转为运行中（需节点程序 0.6.2+）。留空 = 按类型默认{{ startDetectPlaceholder ? `（${startDetectPlaceholder}）` : '；通用应用无默认即不检测' }}。检测最长等待 5 分钟。</span>
      </label>
      <label class="mcp-form-item">
        <span class="mcp-form-label">备注</span>
        <FaTextarea v-model="form.remark" :rows="2" placeholder="选填" class="w-full" />
      </label>
      <FaAlert v-if="formError" variant="destructive" title="无法保存">
  <template #description>
        {{ formError }}
  </template>
      </FaAlert>
      <div v-else-if="!editing" class="mcp-form-hint">
        创建后可在实例详情页管理配置文件、控制台与备份。
      </div>
    </div>
  </FaModal>
</template>
