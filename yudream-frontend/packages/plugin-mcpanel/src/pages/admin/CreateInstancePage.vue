<script setup lang="ts">
import type { FileItem, FileUploadRequestOptions, TableColumn, YdTablePickerQuery, YdTablePickerResult } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { McpNode } from '../../types'
import { FaAlert, FaButton, FaDescriptions, FaFileUpload, FaIcon, FaInput, FaPageHeader, FaPageMain, FaProgress, FaSelect, FaSwitch, FaTabs, FaTag, FaTextarea, YdTablePicker, useFaToast } from '@yudream/components'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { createMcPanelApi } from '../../api/mcpanel-api'
import { createMcPanelExtra } from '../../api/api-extra'
import { errorMessage } from '../../composables/utils'
import { encodeUtf8ToBase64 } from '../../utils/fileContent'
import { sha256HexOfFile } from '../../utils/sha256'

/**
 * 引导式创建：节点 / 核心类型 / MC 版本 / 运行镜像 / 资源。
 * 可从应用市场带 query 预填；可选字段一律选择器。
 */
const props = defineProps<{ sdk: YuDreamPluginSdk }>()

const api = createMcPanelApi(props.sdk)
const toast = useFaToast()
const extra = createMcPanelExtra(props.sdk)
const router = useRouter()
const route = useRoute()

const steps = [
  { key: 'node', title: '选择节点' },
  { key: 'core', title: '核心与版本' },
  { key: 'image', title: 'Java 运行时' },
  { key: 'resource', title: '资源与信息' },
  { key: 'confirm', title: '确认创建' },
] as const

const modeTabs = [
  { label: '核心自动下载', value: 'core', icon: 'i-ri:download-cloud-2-line' },
  { label: '服务端模板', value: 'template', icon: 'i-ri:stack-line' },
  { label: '上传核心', value: 'jar', icon: 'i-ri:upload-2-line' },
  { label: '整合包', value: 'modpack', icon: 'i-ri:archive-2-line' },
  { label: 'ZIP 导入', value: 'zip', icon: 'i-ri:file-zip-line' },
]

const stepIndex = ref(0)
const loading = ref(false)
const submitting = ref(false)
const pageError = ref('')
const formError = ref('')
const resolving = ref(false)
const resolveInfo = ref<{ url?: string, source?: string, note?: string, fileName?: string } | null>(null)
const zipFile = ref<File | null>(null)
const zipFileList = ref<FileItem[]>([])
const jarFile = ref<File | null>(null)
const jarFileList = ref<FileItem[]>([])
const modpackFile = ref<File | null>(null)
const modpackFileList = ref<FileItem[]>([])
/** 整合包分片上传状态机：hashing（本地 sha256）→ uploading（4MiB 分片）→ parsing（面板解析）。 */
const modpackUpload = reactive({
  active: false,
  phase: 'hashing' as 'hashing' | 'uploading' | 'parsing',
  uploaded: 0,
  size: 0,
})
const modpackAbort = ref(false)
const modpackPercent = computed(() => modpackUpload.size > 0
  ? Math.min(100, Math.round((modpackUpload.uploaded / modpackUpload.size) * 100))
  : 0)
const MODPACK_PHASE_LABEL: Record<string, string> = {
  hashing: '正在校验文件（sha256）…',
  uploading: '正在上传整合包…',
  parsing: '正在解析整合包（CurseForge 整合包需逐项目解析文件名，请勿关闭页面）…',
}
function modpackMb(bytes: number): string {
  return `${(bytes / 1024 / 1024).toFixed(1)}MB`
}
/** ZIP 导入 / 自备核心 JAR 的大文件分片上传状态（创建成功后经实例分片通道直传）。 */
const bigUpload = reactive({
  active: false,
  phase: 'hashing' as 'hashing' | 'uploading',
  label: '上传文件',
  uploaded: 0,
  size: 0,
})
const bigUploadPercent = computed(() => bigUpload.size > 0
  ? Math.min(100, Math.round((bigUpload.uploaded / bigUpload.size) * 100))
  : 0)
const BIG_PHASE_LABEL: Record<'hashing' | 'uploading', string> = {
  hashing: '正在校验文件（sha256）…',
  uploading: '正在上传…',
}
function bigMb(bytes: number): string {
  return `${(bytes / 1024 / 1024).toFixed(1)}MB`
}
/** 实例维度大文件分片直传：本地流式 sha256 → begin → 4MiB 分片 → commit。 */
async function uploadBigToInstance(id: string, file: File, path: string, label: string) {
  bigUpload.active = true
  bigUpload.phase = 'hashing'
  bigUpload.label = label
  bigUpload.uploaded = 0
  bigUpload.size = file.size
  try {
    const sha256 = await sha256HexOfFile(file, (read, total) => {
      if (bigUpload.phase === 'hashing') {
        bigUpload.uploaded = read
        bigUpload.size = total
      }
    })
    bigUpload.phase = 'uploading'
    bigUpload.uploaded = 0
    bigUpload.size = file.size
    const begin = await extra.beginUploadTask(id, path, file.size, sha256, file.name) as { taskId?: string }
    const taskId = String(begin?.taskId ?? '')
    if (!taskId) {
      throw new Error('上传任务创建失败')
    }
    const chunkSize = 4 * 1024 * 1024
    for (let offset = 0; offset < file.size; offset += chunkSize) {
      await extra.uploadTaskChunk(id, taskId, offset, file.slice(offset, Math.min(offset + chunkSize, file.size)))
      bigUpload.uploaded = Math.min(offset + chunkSize, file.size)
    }
    await extra.commitUploadTask(id, taskId)
  }
  finally {
    bigUpload.active = false
  }
}
const modpackInfo = ref<{
  token?: string
  name?: string
  mcVersion?: string
  loader?: string
  loaderVersion?: string
  fileCount?: number | string
  overrideCount?: number | string
  skippedCount?: number | string
  coreChain?: string[]
  coreSupported?: boolean
  resolution?: string
} | null>(null)

const RESOLUTION_LABEL: Record<string, string> = {
  mrpack: 'Modrinth 索引（CDN 直链）',
  official: 'CurseForge 官方 API',
  cfwidget: 'cfwidget 公开解析（未配 API key）',
  embedded: '包内文件名（无需联网解析）',
}
const importProgress = ref('')

const nodeLabelCache = ref<Record<string, string>>({})
const cores = ref<Array<{ id: string, label: string }>>([])
const imageOptions = ref<Array<{ id: string, name: string, primaryImage: string, tags: string[], javaVersion?: string }>>([])
const templates = ref<Record<string, unknown>[]>([])
const linkAvailable = ref(false)
const linkServers = ref<Array<Record<string, unknown>>>([])

const form = reactive({
  nodeId: '',
  nodeKeys: [] as string[],
  name: '',
  mode: 'core' as string,
  templateKeys: [] as string[],
  coreKind: 'paper',
  versionKeys: [] as string[],
  mcVersion: '',
  autoDownloadCore: true,
  imageKeys: [] as string[],
  dockerImageId: '',
  imageTag: '',
  command: 'java -Xms512M -Xmx2G -jar server.jar nogui',
  memoryMb: 2048,
  cpuMillis: 1000,
  diskMb: 10240,
  remark: '',
  mcServerKeys: [] as string[],
  agreeEula: true,
  proxyKeys: [] as string[],
  dataMode: 'independent' as 'independent' | 'softlink',
  syncSourceKeys: [] as string[],
  domainEnabled: false,
  domainSlug: '',
})

/** 已纳管代理（「挂到代理」选择器）。 */
const proxyGroups = ref<Array<{ proxyInstanceId?: string, proxyName?: string, kind?: string, serverCount?: number }>>([])

/** 面板云解析是否可用（决定是否显示「自动分配域名」开关与域后缀预览）。 */
const domainAvailable = ref(false)
const domainSuffix = ref('')

/** 创建后将得到的域名预览（slug 推导规则与后端一致：小写字母/数字/连字符）。 */
const domainPreview = computed(() => {
  const raw = (form.domainSlug || form.name || 'server').toLowerCase()
  const slug = raw.replace(/[^a-z0-9-]+/g, '-').replace(/^-+|-+$/g, '') || 'server'
  return domainSuffix.value ? `${slug}.${domainSuffix.value}` : slug
})

const DATA_MODES = [
  { label: '独立（互不同步）', value: 'independent' },
  { label: '软链接（从源实例同步固定文件）', value: 'softlink' },
]

const nodeColumns: TableColumn<Record<string, unknown>>[] = [
  { accessorKey: 'name', header: '节点名称', minWidth: 140 },
  { accessorKey: 'status', header: '状态', width: 100 },
  { accessorKey: 'endpoint', header: '控制信道', minWidth: 200 },
  { accessorKey: 'agentVersion', header: 'Agent', width: 100 },
]

const versionColumns: TableColumn<Record<string, unknown>>[] = [
  { accessorKey: 'version', header: 'MC 版本', minWidth: 120 },
  { accessorKey: 'stable', header: '通道', width: 90 },
]

const imageColumns: TableColumn<Record<string, unknown>>[] = [
  { accessorKey: 'name', header: '内部名称（Java 版本）', minWidth: 160 },
  { accessorKey: 'javaVersion', header: 'Java', width: 80 },
  { accessorKey: 'primaryImage', header: 'JRE 镜像', minWidth: 240 },
]

const templateColumns: TableColumn<Record<string, unknown>>[] = [
  { accessorKey: 'name', header: '模板名称', minWidth: 160 },
  { accessorKey: 'kind', header: '类型', width: 100 },
  { accessorKey: 'mcVersion', header: '默认版本', width: 100 },
]

const linkColumns: TableColumn<Record<string, unknown>>[] = [
  { accessorKey: 'name', header: '子服', minWidth: 140 },
]

const proxyColumns: TableColumn<Record<string, unknown>>[] = [
  { accessorKey: 'proxyName', header: '代理', minWidth: 140 },
  { accessorKey: 'kind', header: '类型', width: 110, align: 'center' },
  { accessorKey: 'serverCount', header: '已挂子服', width: 100, align: 'center' },
]

async function proxyFetcher(query: YdTablePickerQuery): Promise<YdTablePickerResult<Record<string, unknown>>> {
  const rows = proxyGroups.value.filter(item =>
    !query.keyword || String(item.proxyName ?? '').includes(query.keyword))
  return { list: rows as unknown as Record<string, unknown>[], total: rows.length }
}

async function syncSourceFetcher(query: YdTablePickerQuery): Promise<YdTablePickerResult<Record<string, unknown>>> {
  const page = await extra.pageInstances(query.page, query.size,
    query.keyword || undefined) as { records?: Array<Record<string, unknown>>, total?: number }
  return { list: page.records ?? [], total: Number(page.total ?? 0) }
}

const selectedNodeName = computed(() => (form.nodeId ? nodeLabelCache.value[form.nodeId] ?? '' : ''))
const selectedImage = computed(() => imageOptions.value.find(i => i.id === form.dockerImageId) ?? null)
const selectedTemplate = computed(() =>
  templates.value.find(t => String(t.key) === form.templateKeys[0]) ?? null)

const resolvedImage = computed(() => {
  if (selectedImage.value) {
    return form.imageTag || selectedImage.value.primaryImage
  }
  return ''
})

const reviewItems = computed(() => [
  { key: 'node', label: '节点', value: selectedNodeName.value || '-' },
  { key: 'name', label: '实例名', value: form.name.trim() || '-' },
  { key: 'mode', label: '创建方式', value: ({
    core: '核心自动下载',
    template: '服务端模板',
    jar: '上传核心',
    modpack: '整合包',
    zip: 'ZIP 导入',
  } as Record<string, string>)[form.mode] ?? form.mode },
  { key: 'core', label: '核心', value: form.mode === 'zip' || form.mode === 'jar'
    ? (form.mode === 'jar' ? '自备 JAR（统一为 server.jar）' : '-')
    : form.mode === 'modpack'
      ? `${modpackInfo.value?.loader || modpackInfo.value?.coreChain?.[0] || '-'}（${modpackInfo.value?.mcVersion || '-'}）`
      : cores.value.find(c => c.id === form.coreKind)?.label ?? form.coreKind },
  { key: 'version', label: 'MC 版本', value: form.mode === 'core' || form.mode === 'template' || form.mode === 'modpack' ? (form.mcVersion || '-') : '-' },
  ...(form.mode === 'core'
    ? [{ key: 'source', label: '下载来源', value: resolveInfo.value?.source || (form.autoDownloadCore ? '待解析（FastMirror→官方）' : '不自动下载') }]
    : []),
  ...(form.mode === 'jar'
    ? [{ key: 'jar', label: '核心 JAR', value: jarFile.value?.name ?? '未选择' }]
    : []),
  ...(form.mode === 'modpack'
    ? [{ key: 'modpack', label: '整合包', value: `${modpackInfo.value?.name ?? '-'} · ${modpackInfo.value?.fileCount ?? 0} 文件` }]
    : []),
  ...(form.mode === 'zip'
    ? [{ key: 'zip', label: 'ZIP 文件', value: zipFile.value?.name ?? '未选择' }]
    : []),
  { key: 'image', label: '镜像', value: selectedImage.value ? `${selectedImage.value.name} → ${resolvedImage.value}` : '-' },
  { key: 'command', label: '启动命令', value: form.command || '-' },
  { key: 'resource', label: '资源', value: `${form.memoryMb} MB · ${form.cpuMillis} mCPU · ${form.diskMb} MB` },
  ...(showEula.value
    ? [{ key: 'eula', label: 'EULA', value: form.agreeEula ? '创建后自动写入 eula=true' : '暂不同意（启动前需手动同意）' }]
    : []),
  ...(form.proxyKeys[0]
    ? [{ key: 'proxy', label: '挂到代理', value: proxyGroups.value.find(g => g.proxyInstanceId === form.proxyKeys[0])?.proxyName ?? form.proxyKeys[0] }]
    : []),
  { key: 'dataMode', label: '数据模式', value: form.dataMode === 'softlink' ? '软链接（同步 plugins 与配置文件）' : '独立' },
])

async function nodeFetcher(query: YdTablePickerQuery): Promise<YdTablePickerResult<Record<string, unknown>>> {
  const page = await api.pageNodes(
    query.page,
    query.size,
    query.keyword || undefined,
  ) as { records?: McpNode[], total?: number }
  const records = page.records ?? []
  for (const node of records) {
    nodeLabelCache.value[node.id] = node.name
  }
  return { list: records as unknown as Record<string, unknown>[], total: Number(page.total ?? 0) }
}

async function versionFetcher(query: YdTablePickerQuery): Promise<YdTablePickerResult<Record<string, unknown>>> {
  if (!form.coreKind) {
    return { list: [], total: 0 }
  }
  const page = await extra.coreVersions(form.coreKind, query.page, query.size, query.keyword || undefined) as {
    records?: Array<Record<string, unknown>>, total?: number
  }
  return { list: page.records ?? [], total: Number(page.total ?? 0) }
}

async function imageFetcher(query: YdTablePickerQuery): Promise<YdTablePickerResult<Record<string, unknown>>> {
  const rows = imageOptions.value.filter(item =>
    !query.keyword
    || item.name.toLowerCase().includes(query.keyword.toLowerCase())
    || item.primaryImage.toLowerCase().includes(query.keyword.toLowerCase()),
  )
  const start = (query.page - 1) * query.size
  return { list: rows.slice(start, start + query.size), total: rows.length }
}

async function templateFetcher(query: YdTablePickerQuery): Promise<YdTablePickerResult<Record<string, unknown>>> {
  const page = await extra.pageTemplates(query.page, query.size, undefined, query.keyword || undefined) as {
    records?: Record<string, unknown>[], total?: number
  }
  return { list: page.records ?? [], total: Number(page.total ?? 0) }
}

async function linkFetcher(query: YdTablePickerQuery): Promise<YdTablePickerResult<Record<string, unknown>>> {
  const rows = linkServers.value.filter(item =>
    !query.keyword || String(item.name ?? '').includes(query.keyword))
  return { list: rows, total: rows.length }
}

async function loadMeta() {
  loading.value = true
  pageError.value = ''
  try {
    const [coreRes, imageRes, templateRes, link, groups, settings] = await Promise.all([
      extra.listCores().catch(() => ({ records: [] })),
      extra.dockerImageOptions().catch(() => ({ records: [] })),
      extra.pageTemplates(1, 100).catch(() => ({ records: [] })),
      extra.linkOptions().catch(() => ({ available: false, servers: [] as unknown[] })),
      extra.listProxyGroups().catch(() => ({ records: [] })),
      extra.viewSettings().catch(() => ({ dns: {} })),
    ]) as [
      { records?: Array<{ id: string, label: string, kind: string }> },
      { records?: Array<{ id: string, name: string, primaryImage: string, tags: string[], javaVersion?: string }> },
      { records?: Record<string, unknown>[] },
      { available?: boolean, servers?: Array<Record<string, unknown>> },
      { records?: Array<{ proxyInstanceId?: string, proxyName?: string, kind?: string, serverCount?: number }> },
      { dns?: { mode?: string, suffix?: string } },
    ]
    proxyGroups.value = groups.records ?? []
    // 面板已启用云解析时默认勾选自动分配域名（创建成功即写入 A/SRV）。
    const dns = settings?.dns ?? {}
    domainSuffix.value = String(dns.suffix ?? '')
    domainAvailable.value = String(dns.mode ?? 'off') !== 'off' && Boolean(dns.suffix)
    if (domainAvailable.value) {
      form.domainEnabled = true
    }
    cores.value = (coreRes.records ?? []).map(item => ({ id: item.id || item.kind, label: item.label || item.kind }))
    imageOptions.value = (imageRes.records ?? []).map(item => ({
      id: String(item.id),
      name: String(item.name ?? item.id),
      primaryImage: String(item.primaryImage ?? ''),
      tags: Array.isArray(item.tags) ? item.tags.map(String) : [],
      javaVersion: String(item.javaVersion ?? ''),
    }))
    templates.value = (templateRes.records ?? []).filter(t => String(t.kind ?? '') !== 'image')
    linkAvailable.value = Boolean(link?.available)
    linkServers.value = (link?.servers ?? []).filter(s => s.enabled !== false)
    if (!form.coreKind && cores.value[0]) {
      form.coreKind = cores.value[0].id
    }
  }
  catch (error) {
    pageError.value = errorMessage(error, '加载创建向导数据失败')
  }
  finally {
    loading.value = false
  }
}

watch(() => form.nodeKeys, (keys) => {
  form.nodeId = keys[0] ?? ''
}, { deep: true })
watch(() => form.templateKeys, (keys) => {
  const key = keys[0] ?? ''
  const template = templates.value.find(t => String(t.key) === key)
  if (template) {
    const kind = String(template.kind ?? '')
    if (kind && kind !== 'server' && kind !== 'image' && cores.value.some(c => c.id === kind)) {
      form.coreKind = kind
    }
    if (template.mcVersion) {
      form.mcVersion = String(template.mcVersion)
      form.versionKeys = [form.mcVersion]
    }
    const startup = (template.startup ?? {}) as { jarGlob?: string, jvmOpts?: string }
    const jar = startup.jarGlob || 'server.jar'
    const jvm = startup.jvmOpts || '-Xms512M -Xmx2G'
    form.command = `${jvm} -jar ${jar} nogui`
  }
}, { deep: true })
watch(() => form.versionKeys, (keys) => {
  form.mcVersion = keys[0] ?? ''
  if (form.mcVersion) {
    void resolveDownload()
  }
}, { deep: true })
watch(() => form.imageKeys, (keys) => {
  form.dockerImageId = keys[0] ?? ''
  const image = imageOptions.value.find(i => i.id === form.dockerImageId)
  form.imageTag = image?.primaryImage ?? ''
}, { deep: true })
watch(() => form.coreKind, () => {
  form.versionKeys = []
  form.mcVersion = ''
  resolveInfo.value = null
})
watch(() => form.mode, (mode) => {
  if (mode === 'zip' || mode === 'jar') {
    form.autoDownloadCore = false
  }
})
watch(() => form.mcServerKeys, () => {
  // 单选绑定，payload 取 [0]
})
watch(zipFileList, (list) => {
  if (!list.length) {
    zipFile.value = null
  }
})
watch(jarFileList, (list) => {
  if (!list.length) {
    jarFile.value = null
  }
})
watch(modpackFileList, (list) => {
  if (!list.length) {
    if (modpackUpload.active) {
      // 上传中移除文件：通知分片循环中止（循环内会调用面板取消接口）。
      modpackAbort.value = true
      return
    }
    modpackFile.value = null
    modpackInfo.value = null
  }
})

async function resolveDownload() {
  if (!form.coreKind || !form.mcVersion || !form.autoDownloadCore) {
    resolveInfo.value = null
    return
  }
  resolving.value = true
  try {
    const plan = await extra.resolveCoreDownload(form.coreKind, form.mcVersion) as {
      url?: string, source?: string, note?: string, fileName?: string
    }
    resolveInfo.value = plan
  }
  catch (error) {
    resolveInfo.value = null
    formError.value = errorMessage(error, '核心下载地址解析失败')
  }
  finally {
    resolving.value = false
  }
}

function checkZip(file: File): boolean {
  if (!file.name.toLowerCase().endsWith('.zip')) {
    formError.value = '仅支持 .zip 服务端压缩包'
    return false
  }
  return true
}

/** 本地暂存文件，真正的上传在创建成功后由 submit 触发 */
async function captureZip(options: FileUploadRequestOptions) {
  zipFile.value = options.file
  return { name: options.file.name }
}

function checkJar(file: File): boolean {
  if (!file.name.toLowerCase().endsWith('.jar')) {
    formError.value = '仅支持 .jar 服务端核心文件'
    return false
  }
  return true
}

/** 自备核心：创建成功后上传并统一改名为 server.jar（与默认启动命令一致） */
async function captureJar(options: FileUploadRequestOptions) {
  jarFile.value = options.file
  return { name: options.file.name }
}

function checkModpack(file: File): boolean {
  const lower = file.name.toLowerCase()
  if (!lower.endsWith('.mrpack') && !lower.endsWith('.zip')) {
    formError.value = '仅支持 .mrpack（Modrinth）或 .zip（CurseForge）整合包'
    return false
  }
  return true
}

/**
 * 上传解析（分片直传 + 进度）：本地流式 sha256 → 4MiB 分片 → 面板校验后解析。
 * CF manifest 需外呼 cfwidget，解析阶段可能数十秒。
 */
async function captureModpack(options: FileUploadRequestOptions) {
  const file = options.file
  modpackFile.value = file
  modpackInfo.value = null
  modpackAbort.value = false
  modpackUpload.active = true
  modpackUpload.phase = 'hashing'
  modpackUpload.uploaded = 0
  modpackUpload.size = file.size
  formError.value = ''
  let taskId = ''
  try {
    const sha256 = await sha256HexOfFile(file, (read, total) => {
      if (modpackUpload.phase === 'hashing') {
        modpackUpload.uploaded = read
        modpackUpload.size = total
      }
    })
    if (modpackAbort.value) {
      throw new Error('__aborted__')
    }
    modpackUpload.phase = 'uploading'
    modpackUpload.uploaded = 0
    modpackUpload.size = file.size
    const begin = await extra.beginModpackUpload(file.name, file.size, sha256) as { taskId?: string }
    taskId = String(begin?.taskId ?? '')
    if (!taskId) {
      throw new Error('上传任务创建失败')
    }
    const chunkSize = 4 * 1024 * 1024
    for (let offset = 0; offset < file.size; offset += chunkSize) {
      if (modpackAbort.value) {
        await extra.cancelModpackUpload(taskId).catch(() => {})
        throw new Error('__aborted__')
      }
      const blob = file.slice(offset, Math.min(offset + chunkSize, file.size))
      await extra.modpackUploadChunk(taskId, offset, blob)
      modpackUpload.uploaded = Math.min(offset + chunkSize, file.size)
    }
    modpackUpload.phase = 'parsing'
    const result = await extra.commitModpackUpload(taskId) as NonNullable<typeof modpackInfo.value>
    modpackInfo.value = result
    taskId = ''
  }
  catch (error) {
    if (taskId) {
      await extra.cancelModpackUpload(taskId).catch(() => {})
    }
    modpackInfo.value = null
    modpackFile.value = null
    modpackFileList.value = []
    if (!modpackAbort.value && (error as Error)?.message !== '__aborted__') {
      formError.value = errorMessage(error, '整合包解析失败')
    }
  }
  finally {
    modpackAbort.value = false
    modpackUpload.active = false
  }
  return { name: file.name }
}

/** 上传中主动取消（进度条上的取消按钮）。 */
async function cancelModpackUploadAction() {
  modpackAbort.value = true
}

function goStep(index: number) {
  formError.value = ''
  stepIndex.value = Math.max(0, Math.min(steps.length - 1, index))
}

function validateStep(index: number): string {
  if (index === 0 && !form.nodeId) {
    return '请选择运行节点'
  }
  if (index === 1) {
    if (form.mode === 'core') {
      if (!form.coreKind) {
        return '请选择核心类型'
      }
      if (!form.mcVersion) {
        return '请选择 MC 版本'
      }
      if (form.autoDownloadCore && !resolveInfo.value?.url) {
        return '请等待核心下载地址解析完成（或关闭自动下载）'
      }
    }
    if (form.mode === 'template' && !form.templateKeys[0]) {
      return '请选择服务端模板'
    }
    if (form.mode === 'jar' && !jarFile.value) {
      return '请选择服务端核心 JAR 文件'
    }
    if (form.mode === 'modpack') {
      if (!modpackInfo.value?.token) {
        return '请上传并解析整合包'
      }
      if (modpackInfo.value.coreSupported === false) {
        return '该整合包为 forge / neoforge 服务端，暂不支持自动开服（请改用 Fabric/Quilt/Paper 系整合包或 ZIP 导入）'
      }
    }
    if (form.mode === 'zip' && !zipFile.value) {
      return '请选择服务端 ZIP 文件'
    }
  }
  if (index === 3) {
    if (!form.name.trim()) {
      return '请输入实例名称'
    }
    if (form.memoryMb < 64) {
      return '内存需 ≥ 64MB'
    }
    if (form.dataMode === 'softlink' && !form.syncSourceKeys[0]) {
      return '软链接模式需要选择源实例'
    }
  }
  return ''
}

function next() {
  const problem = validateStep(stepIndex.value)
  if (problem) {
    formError.value = problem
    return
  }
  if (stepIndex.value < steps.length - 1) {
    goStep(stepIndex.value + 1)
  }
}

function prev() {
  goStep(stepIndex.value - 1)
}

/** 生效核心类型（决定 EULA 开关是否显示：velocity/bungee 代理没有 EULA）。 */
const effectiveKind = computed(() => {
  if (form.mode === 'jar') {
    return 'generic'
  }
  if (form.mode === 'modpack') {
    return modpackInfo.value?.loader || modpackInfo.value?.coreChain?.[0] || 'generic'
  }
  return form.coreKind
})
const showEula = computed(() => !['velocity', 'bungee'].includes(effectiveKind.value))

function buildPayload() {
  const id = `inst-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`
  const command = form.command.trim().split(/\s+/).filter(Boolean)
  const payload: Record<string, unknown> = {
    id,
    nodeId: form.nodeId,
    name: form.name.trim(),
    kind: form.mode === 'jar'
      ? 'generic'
      : form.mode === 'modpack'
        ? (modpackInfo.value?.loader || modpackInfo.value?.coreChain?.[0] || 'generic')
        : form.mode === 'template'
          ? String(selectedTemplate.value?.kind ?? form.coreKind)
          : form.coreKind,
    mcVersion: form.mode === 'modpack'
      ? (modpackInfo.value?.mcVersion || null)
      : (form.mcVersion || null),
    image: resolvedImage.value,
    command,
    env: {},
    memoryMb: Number(form.memoryMb),
    cpuMillis: Number(form.cpuMillis),
    diskMb: Number(form.diskMb),
    config: {},
    remark: form.remark.trim() || null,
    mcServerId: form.mcServerKeys[0] || null,
  }
  if (form.mode === 'core' && form.autoDownloadCore && resolveInfo.value?.url) {
    // 命令 -jar 与下载文件名统一 server.jar，避免 Unable to access jarfile
    const command = form.command.trim().split(/\s+/).filter(Boolean).map((part, index, arr) =>
      arr[index - 1] === '-jar' ? 'server.jar' : part)
    payload.command = command
    payload.config = {
      installType: 'download',
      installUrl: resolveInfo.value.url,
      installFileName: 'server.jar',
      installSource: resolveInfo.value.source || '',
      autoDownloadCore: 'true',
    }
  }
  if (form.mode === 'template' && form.templateKeys[0]) {
    payload.templateKey = form.templateKeys[0]
    payload.autoDownloadCore = form.autoDownloadCore
  }
  if (form.dockerImageId) {
    payload.dockerImageId = form.dockerImageId
  }
  if (form.domainEnabled && domainAvailable.value) {
    payload.domainEnabled = true
    if (form.domainSlug.trim()) {
      payload.domainSlug = form.domainSlug.trim()
    }
  }
  return payload
}

async function submit() {
  const problem = validateStep(3) || validateStep(1)
  if (problem) {
    formError.value = problem
    return
  }
  if (submitting.value) {
    return
  }
  submitting.value = true
  formError.value = ''
  importProgress.value = ''
  try {
    const created = await extra.createInstance(buildPayload()) as { id?: string }
    const id = String(created?.id ?? '')
    if (id && form.mode === 'jar' && jarFile.value) {
      // 分片直传（含进度），避免大核心整份 multipart 撞请求超时
      await uploadBigToInstance(id, jarFile.value, 'server.jar', '上传核心')
    }
    if (id && form.mode === 'modpack' && modpackInfo.value?.token) {
      importProgress.value = `正在下发整合包「${modpackInfo.value?.name ?? ''}」（核心 + ${modpackInfo.value?.fileCount ?? 0} 文件）…`
      try {
        await extra.applyModpack(id, modpackInfo.value.token)
        importProgress.value = ''
      }
      catch (error) {
        // 下发已受理部分仍有效：跳转实例页看进度条，失败详情在此给出。
        formError.value = `整合包下发失败：${errorMessage(error, '请到实例页重试或手动安装')}`
      }
    }
    if (id && form.mode === 'zip' && zipFile.value) {
      // 分片直传（含进度），避免大 zip 整份 multipart 撞请求超时
      await uploadBigToInstance(id, zipFile.value, zipFile.value.name, '上传服务端 ZIP')
      importProgress.value = '解压到实例数据根目录…'
      try {
        await extra.unzipFile(id, zipFile.value.name, '')
      }
      catch {
        importProgress.value = '上传成功，但解压失败（节点需支持 file.unzip）；可到文件页手动处理'
      }
    }
    // 创建后联动（各自独立失败提示，不阻断跳转）
    if (id && form.agreeEula && showEula.value) {
      try {
        await extra.writeFile(id, 'eula.txt', encodeUtf8ToBase64('eula=true\n# 由 YuDream 面板创建向导写入\n'), 'base64')
      }
      catch { /* EULA 写入失败：配置页仍有一键同意 */ }
    }
    if (id && form.proxyKeys[0]) {
      importProgress.value = '正在把子服注册进代理配置…'
      try {
        const attached = await extra.attachToProxy(form.proxyKeys[0], id) as { serverName?: string, address?: string, configWritten?: boolean }
        toast.success(`已挂到代理（子服 ${attached.serverName} → ${attached.address}）${attached.configWritten ? '，代理重启后生效' : '；代理配置尚未生成，启动代理后重新识别即可'}`)
      }
      catch (error) {
        toast.error(`挂到代理失败：${errorMessage(error, '可在代理实例的「代理与子服」页手动绑定')}`)
      }
    }
    if (id && form.dataMode === 'softlink' && form.syncSourceKeys[0]) {
      importProgress.value = '正在从源实例同步固定文件（软链接首同步）…'
      try {
        await extra.saveSyncLink(id, form.syncSourceKeys[0])
        await extra.runSyncLink(id)
        toast.success('软链接已建立并完成首次同步（plugins 与配置文件已就位）')
      }
      catch (error) {
        toast.error(`软链接建立/同步失败：${errorMessage(error, '可到实例详情页重试')}`)
      }
    }
    importProgress.value = ''
    void router.push(id
      ? `/platform/plugins/mcpanel/admin/instances/${encodeURIComponent(id)}`
      : '/platform/plugins/mcpanel/admin/instances')
  }
  catch (error) {
    formError.value = errorMessage(error, '创建实例失败')
  }
  finally {
    submitting.value = false
  }
}

const coreOptions = computed(() => cores.value.map(item => ({ label: item.label, value: item.id })))

/** MC 版本 → 常用 Java 提示（仅建议，镜像目录仍按 Java 版本选，与核心无关）。 */
const suggestJava = computed(() => {
  const v = form.mcVersion
  if (!v) {
    return ''
  }
  const parts = v.split('.').map(n => Number.parseInt(n, 10))
  const major = parts[0] ?? 0
  const minor = parts[1] ?? 0
  if (major >= 26 || (major === 1 && minor >= 20 && parts[2] != null && parts[2] >= 5) || (major === 1 && minor > 20)) {
    return 'Java 21'
  }
  if (major === 1 && minor >= 18) {
    return 'Java 17'
  }
  if (major === 1 && minor >= 17) {
    return 'Java 17'
  }
  return 'Java 8 / 11'
})

onMounted(() => {
  const q = route.query
  if (q.mode === 'template' && q.templateKey) {
    form.mode = 'template'
    form.templateKeys = [String(q.templateKey)]
  }
  if (q.mode === 'core' && q.coreKind) {
    form.mode = 'core'
    form.coreKind = String(q.coreKind)
  }
  if (q.mode === 'zip') {
    form.mode = 'zip'
  }
  if (q.kind) {
    form.coreKind = String(q.kind)
  }
  if (q.mcVersion) {
    form.mcVersion = String(q.mcVersion)
    form.versionKeys = [form.mcVersion]
  }
  void loadMeta()
})
</script>

<template>
  <FaPageHeader title="创建服务器" description="引导式：节点 → 核心/版本（选择器）→ 镜像（内部名+标签）→ 资源；核心 FastMirror 优先、官方兜底">
    <FaButton variant="outline" @click="router.push('/platform/plugins/mcpanel/admin/instances')">
      <FaIcon name="i-ri:arrow-left-line" />
      返回实例
    </FaButton>
  </FaPageHeader>

  <FaPageMain v-loading="loading">
    <FaAlert v-if="pageError" variant="destructive" title="无法加载向导" class="mb-4">
  <template #description>
      {{ pageError }}
  </template>
    </FaAlert>

    <div class="rounded-xl border bg-card p-4 sm:p-6">
      <nav aria-label="创建步骤">
        <ol class="flex flex-wrap items-center gap-x-2 gap-y-3">
          <li v-for="(step, index) in steps" :key="step.key" class="flex items-center gap-2">
            <button
              type="button"
              class="flex items-center gap-2 rounded-lg px-2 py-1 text-sm transition-colors"
              :class="[
                index <= stepIndex ? 'text-foreground' : 'text-muted-foreground',
                submitting ? 'cursor-not-allowed opacity-60' : 'hover:text-primary',
              ]"
              :disabled="submitting"
              @click="goStep(index)"
            >
              <span
                class="flex h-6 w-6 items-center justify-center rounded-full border text-xs font-semibold"
                :class="index < stepIndex
                  ? 'border-primary bg-primary text-primary-foreground'
                  : index === stepIndex
                    ? 'border-primary text-primary'
                    : 'border-border text-muted-foreground'"
              >
                <FaIcon v-if="index < stepIndex" name="i-ri:check-line" />
                <template v-else>
                  {{ index + 1 }}
                </template>
              </span>
              <span :class="index === stepIndex ? 'font-medium' : ''">{{ step.title }}</span>
            </button>
            <span v-if="index < steps.length - 1" class="hidden h-px w-8 bg-border sm:block" />
          </li>
        </ol>
      </nav>

      <section class="mt-6 space-y-5">
        <template v-if="stepIndex === 0">
          <div>
            <h2 class="text-base font-semibold">
              选择运行节点
            </h2>
            <p class="mt-1 text-sm text-muted-foreground">
              从已接入的节点中选择运行机器，弹窗内支持搜索与分页。
            </p>
          </div>
          <div class="grid gap-2">
            <span class="text-sm font-medium">运行节点</span>
            <YdTablePicker
              v-model="form.nodeKeys"
              :columns="nodeColumns"
              :fetcher="nodeFetcher"
              row-key="id"
              label-key="name"
              :multiple="false"
              title="选择运行节点"
              placeholder="点击选择节点"
              :initial-labels="form.nodeId && nodeLabelCache[form.nodeId] ? { [form.nodeId]: nodeLabelCache[form.nodeId] } : {}"
            >
              <template #cell-status="{ row }">
                <FaTag :variant="row.original.status === 'online' ? 'default' : row.original.status === 'offline' ? 'destructive' : 'secondary'">
                  {{ row.original.status === 'online' ? '在线' : row.original.status === 'offline' ? '离线' : '连接中' }}
                </FaTag>
              </template>
            </YdTablePicker>
          </div>
        </template>

        <template v-else-if="stepIndex === 1">
          <div>
            <h2 class="text-base font-semibold">
              核心与版本
            </h2>
            <p class="mt-1 text-sm text-muted-foreground">
              创建方式、核心、版本均为选择器；下载源 FastMirror 优先，失败自动官方。
            </p>
          </div>

          <FaTabs v-model="form.mode" :list="modeTabs" class="w-full" content-class="pt-4">
            <template #core>
              <div class="grid gap-4">
                <label class="grid gap-2">
                  <span class="text-sm font-medium">核心类型</span>
                  <FaSelect v-model="form.coreKind" :options="coreOptions" class="w-full" />
                </label>
                <div class="grid gap-2">
                  <span class="text-sm font-medium">MC 版本</span>
                  <YdTablePicker
                    v-model="form.versionKeys"
                    :columns="versionColumns"
                    :fetcher="versionFetcher"
                    row-key="version"
                    label-key="version"
                    :multiple="false"
                    title="选择 MC 版本"
                    placeholder="点击选择版本"
                    :disabled="!form.coreKind"
                    :initial-labels="form.mcVersion ? { [form.mcVersion]: form.mcVersion } : {}"
                  />
                  <p class="text-xs text-muted-foreground">
                    版本来自核心官方/镜像元数据，禁止手输。
                  </p>
                </div>
                <label class="flex items-center gap-3 text-sm">
                  <FaSwitch v-model="form.autoDownloadCore" @update:model-value="resolveDownload" />
                  创建后自动下载核心（FastMirror → 官方兜底）
                </label>
                <p v-if="resolving" class="text-xs text-muted-foreground">
                  正在解析下载地址…
                </p>
                <p v-else-if="resolveInfo?.url" class="text-xs text-muted-foreground">
                  来源：{{ resolveInfo.source || '-' }} · {{ resolveInfo.note || '' }}<br>
                  <code class="mcp-inline-code">{{ resolveInfo.url }}</code>
                </p>
              </div>
            </template>

            <template #template>
              <div class="grid gap-4">
                <div class="grid gap-2">
                  <span class="text-sm font-medium">服务端模板</span>
                  <YdTablePicker
                    v-model="form.templateKeys"
                    :columns="templateColumns"
                    :fetcher="templateFetcher"
                    row-key="key"
                    label-key="name"
                    :multiple="false"
                    title="选择模板"
                    placeholder="点击选择模板"
                  />
                </div>
                <label class="flex items-center gap-3 text-sm">
                  <FaSwitch v-model="form.autoDownloadCore" />
                  按模板 installer 自动下载核心
                </label>
              </div>
            </template>

            <template #jar>
              <div class="grid gap-2">
                <span class="text-sm font-medium">服务端核心 JAR</span>
                <FaFileUpload
                  v-model="jarFileList"
                  :max="1"
                  accept=".jar"
                  :before-upload="checkJar"
                  :http-request="captureJar"
                  description="拖放或点击选择 .jar（Paper / Velocity / BungeeCord 等任意核心）；创建成功后自动上传并统一命名为 server.jar"
                />
                <p v-if="jarFile" class="text-xs text-muted-foreground">
                  已选择：<code class="mcp-inline-code">{{ jarFile.name }}</code> → 将以 <code class="mcp-inline-code">server.jar</code> 落盘，与默认启动命令一致。
                </p>
              </div>
            </template>

            <template #modpack>
              <div class="grid gap-3">
                <div class="grid gap-2">
                  <span class="text-sm font-medium">整合包（mrpack / CurseForge zip）</span>
                  <FaFileUpload
                    v-model="modpackFileList"
                    :max="1"
                    accept=".mrpack,.zip"
                    :before-upload="checkModpack"
                    :http-request="captureModpack"
                    description="Modrinth .mrpack 或 CurseForge 导出 zip；上传后自动解析（CurseForge 需联网解析文件名，可能需要约一分钟）"
                  />
                </div>
                <div v-if="modpackUpload.active" class="grid gap-2 rounded-lg border p-3 text-sm">
                  <div class="flex flex-wrap items-center justify-between gap-2 text-xs text-muted-foreground">
                    <span class="flex items-center gap-1">
                      <FaIcon name="i-ri:loader-4-line" class="animate-spin" />
                      {{ MODPACK_PHASE_LABEL[modpackUpload.phase] }}
                    </span>
                    <span v-if="modpackUpload.phase !== 'parsing'">
                      {{ modpackPercent }}%（{{ modpackMb(modpackUpload.uploaded) }} / {{ modpackMb(modpackUpload.size) }}）
                    </span>
                  </div>
                  <FaProgress v-if="modpackUpload.phase !== 'parsing'" :model-value="modpackPercent" />
                  <div class="flex justify-end">
                    <FaButton size="sm" variant="outline" @click="cancelModpackUploadAction">取消</FaButton>
                  </div>
                </div>
                <div v-else-if="modpackInfo" class="grid gap-2 rounded-lg border p-3 text-sm">
                  <div class="flex flex-wrap items-center gap-2">
                    <span class="font-medium">{{ modpackInfo.name || '整合包' }}</span>
                    <FaTag variant="outline">{{ modpackInfo.loader || '未知加载器' }}</FaTag>
                    <FaTag variant="secondary">MC {{ modpackInfo.mcVersion || '?' }}</FaTag>
                    <FaTag :variant="modpackInfo.coreSupported === false ? 'destructive' : 'default'">
                      {{ modpackInfo.coreSupported === false ? '不支持自动开服' : '可自动开服' }}
                    </FaTag>
                  </div>
                  <p class="text-xs text-muted-foreground">
                    服务端文件 {{ modpackInfo.fileCount ?? 0 }} 个 · 配置覆盖 {{ modpackInfo.overrideCount ?? 0 }} 个 ·
                    跳过客户端专属 {{ modpackInfo.skippedCount ?? 0 }} 个 · 核心链 {{ (modpackInfo.coreChain ?? []).join(' → ') || '-' }}
                  </p>
                  <p class="text-xs text-muted-foreground">
                    解析来源：{{ RESOLUTION_LABEL[modpackInfo.resolution ?? ''] ?? modpackInfo.resolution ?? '-' }}
                    <template v-if="modpackInfo.resolution === 'cfwidget'">
                      ；可在「面板设置 → 整合包」配置 CurseForge API key，改用官方 API 解析（更稳定、不惧限流）
                    </template>
                  </p>
                  <p v-if="modpackInfo.coreSupported === false" class="text-xs text-destructive">
                    forge / neoforge 服务端需要交互式安装器，暂不支持自动开服；请改用 Fabric / Quilt / Paper 系整合包或 ZIP 导入。
                  </p>
                </div>
              </div>
            </template>

            <template #zip>
              <div class="grid gap-2">
                <span class="text-sm font-medium">服务端 ZIP</span>
                <FaFileUpload
                  v-model="zipFileList"
                  :max="1"
                  :before-upload="checkZip"
                  :http-request="captureZip"
                  description="拖放或点击选择 .zip 文件；创建成功后自动上传并解压到实例数据根目录"
                />
              </div>
            </template>
          </FaTabs>
        </template>

        <template v-else-if="stepIndex === 2">
          <div>
            <h2 class="text-base font-semibold">
              Java 运行时镜像
            </h2>
            <p class="mt-1 text-sm text-muted-foreground">
              镜像对应 <strong>Java / JRE 版本</strong>，与 Paper 等服务端核心无关。
              核心是核心软件，镜像只是跑它的 JVM 环境；选核心后可按 MC 版本参考推荐 Java，再在目录里选。
            </p>
          </div>
          <div class="grid gap-2">
            <span class="text-sm font-medium">Java 运行时（镜像目录）</span>
            <YdTablePicker
              v-model="form.imageKeys"
              :columns="imageColumns"
              :fetcher="imageFetcher"
              row-key="id"
              label-key="name"
              :multiple="false"
              title="选择 Java 运行时镜像"
              placeholder="点击选择 Java 版本对应的 JRE 镜像"
            />
            <p v-if="suggestJava" class="text-xs text-muted-foreground">
              参考：当前 MC 版本通常使用 {{ suggestJava }}（仅供提示，仍以你选的镜像为准）。
            </p>
          </div>
          <label v-if="selectedImage?.tags?.length" class="grid gap-2">
            <span class="text-sm font-medium">标签 / 拉取地址</span>
            <FaSelect
              v-model="form.imageTag"
              :options="selectedImage.tags.map(tag => ({ label: tag, value: tag }))"
              class="w-full"
            />
            <span class="text-xs text-muted-foreground">当前：<code class="mcp-inline-code">{{ resolvedImage || '-' }}</code></span>
          </label>
        </template>

        <template v-else-if="stepIndex === 3">
          <div>
            <h2 class="text-base font-semibold">
              资源与实例信息
            </h2>
          </div>
          <label class="grid gap-2">
            <span class="text-sm font-medium">实例名称</span>
            <FaInput v-model="form.name" clearable placeholder="例如：生存服-01" class="w-full" />
          </label>
          <div class="grid gap-4 sm:grid-cols-3">
            <label class="grid gap-2">
              <span class="text-sm font-medium">内存（MB）</span>
              <FaInput v-model="form.memoryMb" type="number" class="w-full" />
            </label>
            <label class="grid gap-2">
              <span class="text-sm font-medium">CPU（毫核）</span>
              <FaInput v-model="form.cpuMillis" type="number" class="w-full" />
            </label>
            <label class="grid gap-2">
              <span class="text-sm font-medium">磁盘（MB）</span>
              <FaInput v-model="form.diskMb" type="number" class="w-full" />
            </label>
          </div>
          <label class="grid gap-2">
            <span class="text-sm font-medium">启动命令（按核心预填，可改）</span>
            <FaTextarea v-model="form.command" :rows="2" class="w-full font-mono" />
          </label>
          <div v-if="linkAvailable" class="grid gap-2">
            <span class="text-sm font-medium">绑定子服（可选）</span>
            <YdTablePicker
              v-model="form.mcServerKeys"
              :columns="linkColumns"
              :fetcher="linkFetcher"
              row-key="id"
              label-key="name"
              :multiple="false"
              title="绑定子服"
              placeholder="不绑定"
              clearable
            />
          </div>
          <div v-if="proxyGroups.length" class="grid gap-2">
            <span class="text-sm font-medium">挂到代理（可选，自动注册进代理配置）</span>
            <YdTablePicker
              v-model="form.proxyKeys"
              :columns="proxyColumns"
              :fetcher="proxyFetcher"
              row-key="proxyInstanceId"
              label-key="proxyName"
              :multiple="false"
              title="选择代理"
              placeholder="不挂代理"
              clearable
            />
            <p class="text-xs text-muted-foreground">
              创建成功后自动写入代理的子服列表（velocity.toml / config.yml）并建立绑定；代理重启后生效。
            </p>
          </div>
          <div class="grid gap-2">
            <span class="text-sm font-medium">数据模式</span>
            <FaSelect v-model="form.dataMode" :options="DATA_MODES" class="w-full" />
            <template v-if="form.dataMode === 'softlink'">
              <YdTablePicker
                v-model="form.syncSourceKeys"
                :columns="nodeColumns.map(col => ({ ...col, header: '源实例' }))"
                :fetcher="syncSourceFetcher"
                row-key="id"
                label-key="name"
                :multiple="false"
                title="选择源实例"
                placeholder="选择要同步的源实例"
              />
              <p class="text-xs text-muted-foreground">
                软链接 = 从源实例单向同步固定文件（plugins 目录与 server.properties / *.yml 等配置），适合批量开同配置子服；
                创建后立即执行首次同步，之后可随时在实例详情页手动同步。世界数据不同步。
              </p>
            </template>
          </div>
          <label v-if="domainAvailable" class="flex items-start gap-3 text-sm">
            <FaSwitch v-model="form.domainEnabled" />
            <span class="grid gap-2">
              <span>
                自动分配域名（创建成功即写入 A 记录{{ showEula ? ' 与 SRV，玩家免端口直连' : '' }}）
              </span>
              <FaInput
                v-if="form.domainEnabled"
                v-model="form.domainSlug"
                placeholder="子域名前缀（留空按实例名推导）"
                clearable
                class="w-full sm:w-80"
              />
              <span v-if="form.domainEnabled" class="text-xs text-muted-foreground">
                将得到 <code class="rounded bg-muted px-1 font-mono">{{ domainPreview }}</code>
                ；创建后可在实例的「实例域名」页重新同步或解除。
              </span>
            </span>
          </label>
          <label v-if="showEula" class="flex items-start gap-3 text-sm">
            <FaSwitch v-model="form.agreeEula" />
            <span>
              同意 <a href="https://aka.ms/MinecraftEULA" target="_blank" rel="noopener" class="text-primary hover:underline">Minecraft EULA</a>（创建后自动写入 eula=true，服务端才能启动）
            </span>
          </label>
          <label class="grid gap-2">
            <span class="text-sm font-medium">备注</span>
            <FaTextarea v-model="form.remark" :rows="2" class="w-full" />
          </label>
        </template>

        <template v-else>
          <div>
            <h2 class="text-base font-semibold">
              确认创建
            </h2>
            <p class="mt-1 text-sm text-muted-foreground">
              全部关键项来自选择器；创建后可进入终端与文件管理。
            </p>
          </div>
          <FaDescriptions :items="reviewItems" :column="2" border size="sm" />
        </template>

        <FaAlert v-if="formError" variant="destructive" title="无法继续">
  <template #description>
          {{ formError }}
  </template>
        </FaAlert>
        <div v-if="bigUpload.active" class="mb-2 grid gap-2 rounded-lg border p-3 text-sm">
          <div class="flex flex-wrap items-center justify-between gap-2 text-xs text-muted-foreground">
            <span class="flex items-center gap-1">
              <FaIcon name="i-ri:loader-4-line" class="animate-spin" />
              {{ bigUpload.label }}：{{ BIG_PHASE_LABEL[bigUpload.phase] }}
            </span>
            <span>{{ bigUploadPercent }}%（{{ bigMb(bigUpload.uploaded) }} / {{ bigMb(bigUpload.size) }}）</span>
          </div>
          <FaProgress :model-value="bigUploadPercent" />
        </div>
        <p v-if="importProgress" class="text-xs text-muted-foreground">
          {{ importProgress }}
        </p>

        <footer class="flex items-center justify-between border-t pt-4">
          <FaButton variant="outline" :disabled="stepIndex === 0 || submitting" @click="prev">
            上一步
          </FaButton>
          <FaButton v-if="stepIndex < steps.length - 1" @click="next">
            下一步
          </FaButton>
          <FaButton v-else :loading="submitting" @click="submit">
            <FaIcon name="i-ri:rocket-2-line" />
            创建实例
          </FaButton>
        </footer>
      </section>
    </div>
  </FaPageMain>
</template>
