<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { FileItem, FileUploadRequestOptions, TableColumn } from '@yudream/components'
import {
  FaAlert,
  FaButton,
  FaCard,
  FaCheckboxGroup,
  FaFileUpload,
  FaInput,
  FaModal,
  FaPageHeader,
  FaPageMain,
  FaRadioGroup,
  FaSelect,
  FaSwitch,
  FaTable,
  FaTag,
  useFaModal,
  useFaToast,
} from '@yudream/components'
import { computed, onMounted, reactive, ref } from 'vue'
import type { McpInjectArtifact } from '../../types'
import { createMcPanelExtra } from '../../api/api-extra'
import { errorMessage } from '../../composables/utils'

/**
 * 面板设置：注入下载源（authlib / 时长统计制品矩阵）、下载与导入、域名自动解析（DNS 驱动）、
 * 多租户、节点贡献、P2P。制品支持平台文件库上传（fileId）与外部 URL 两种来源；
 * 上传即走 /admin/artifacts，设置保存只携带 fileId 引用。
 * 密钥（整合包 CF key 与各家云解析凭据）只写不读：输入留空 = 保持不变。
 */
const props = defineProps<{ sdk: YuDreamPluginSdk }>()

const extra = createMcPanelExtra(props.sdk)
const toast = useFaToast()
const modal = useFaModal()

const loading = ref(false)
const loadError = ref('')
const saving = ref(false)

const cfConfigured = ref(false)
/** 云解析凭据「已配置」标记（真值只在宿主 SecretStore，接口不回显）。 */
const dnsCloudflareConfigured = ref(false)
const dnsAliyunConfigured = ref(false)
const dnsTencentConfigured = ref(false)

/** authlib 验证服 API 根：authlib-injector 插件联动自动值（只读展示，不回传）。 */
const authlibApiRootAuto = ref('')
const authlibApiRootAvailable = ref(false)

interface UploadedArtifact {
  fileId: string
  name: string
  size: number
  sha256: string
}

const form = reactive({
  authlibEnabled: false,
  authlibSource: 'file' as 'file' | 'url',
  authlibJarUrl: '',
  authlibApiRoot: '',
  playtimeEnabled: false,
  modpackMirror: '',
  modpackAllowCurseforge: false,
  cfApiKey: '',
  dnsMode: 'off',
  dnsSuffix: '',
  dnsTtl: 120,
  dnsZone: '',
  dnsApiBase: '',
  dnsCloudflareToken: '',
  dnsAliyunAk: '',
  dnsAliyunSk: '',
  dnsTencentSid: '',
  dnsTencentSkey: '',
  entryApiBase: '',
  entryHost: '',
  entryPort: 25565,
  tenancyEnabled: false,
  tenancyBilling: false,
  tenancyPlatformRoles: '',
  contributionEnabled: false,
  contributionReviewRequired: true,
  contributionMaxPerUser: 2,
  contributionMaxInstances: 3,
  contributionMaxCpuMillis: 4000,
  contributionMaxMemoryMb: 8192,
  p2pEnabled: false,
  p2pDefaultRateKbps: 2048,
  p2pMaxSessions: 2,
  fastMirrorBase: 'https://download.fastmirror.net',
  sftpGatewayEnabled: false,
  sftpGatewayPort: 22022,
  sftpGatewayHost: '',
})

/** authlib jar 当前生效的制品（设置载入或新上传）。 */
const authlibFile = ref<UploadedArtifact | null>(null)
const authlibFileList = ref<FileItem[]>([])
const authlibUploading = ref(false)

/** 时长统计制品矩阵（本地编辑，保存设置时随 payload 提交）。 */
const artifacts = ref<McpInjectArtifact[]>([])

/** 资源源行（Modrinth / CurseForge 换源）：按顺序回退，可增删与上下移。 */
interface SourceRow {
  name: string
  apiBase: string
  cdnBase: string
}

const modrinthSources = ref<SourceRow[]>([])
const curseforgeSources = ref<SourceRow[]>([])

function toSourceRows(value: unknown): SourceRow[] {
  return Array.isArray(value)
    ? value.map((item) => ({
        name: String(item?.name ?? ''),
        apiBase: String(item?.apiBase ?? ''),
        cdnBase: String(item?.cdnBase ?? ''),
      }))
    : []
}

function addSourceRow(list: SourceRow[]) {
  list.push({ name: '自定义源', apiBase: '', cdnBase: '' })
}

function removeSourceRow(list: SourceRow[], index: number) {
  list.splice(index, 1)
}

function moveSourceRow(list: SourceRow[], index: number, delta: number) {
  const target = index + delta
  if (target < 0 || target >= list.length) return
  const [row] = list.splice(index, 1)
  list.splice(target, 0, row)
}

function cleanSources(list: SourceRow[]) {
  return list
    .filter((row) => row.apiBase.trim() || row.cdnBase.trim())
    .map((row) => ({
      name: row.name.trim() || '自定义源',
      apiBase: row.apiBase.trim().replace(/\/+$/, ''),
      cdnBase: row.cdnBase.trim().replace(/\/+$/, ''),
    }))
}

const DNS_MODES = [
  { label: '关闭（不分配域名）', value: 'off' },
  { label: 'Cloudflare', value: 'cloudflare' },
  { label: '阿里云云解析', value: 'aliyun' },
  { label: '腾讯云 DNSPod', value: 'dnspod' },
]

/** 驱动差异：区域标识字段名、TTL 免费档下限、凭据说明。 */
const DNS_META: Record<string, { label: string, zoneLabel: string, zoneHint: string, minTtl: number, credentialHint: string }> = {
  cloudflare: {
    label: 'Cloudflare',
    zoneLabel: 'Zone ID',
    zoneHint: 'Cloudflare 概览页的 Zone ID；A/SRV 记录写在该区域下',
    minTtl: 60,
    credentialHint: 'API Token 建议只授予 Zone.DNS:Edit（Cloudflare → 我的个人资料 → API 令牌）',
  },
  aliyun: {
    label: '阿里云云解析',
    zoneLabel: '根域名（如 example.com）',
    zoneHint: '域名须托管在阿里云云解析；建议用 RAM 子账号（AliyunDNSFullAccess）',
    minTtl: 600,
    credentialHint: 'AccessKeyId / AccessKeySecret 建议用 RAM 子账号且只授予云解析权限；免费档 TTL 下限 600 秒',
  },
  dnspod: {
    label: '腾讯云 DNSPod',
    zoneLabel: '根域名（如 example.com）',
    zoneHint: '域名须托管在 DNSPod；建议用 CAM 子账号（DNSPod 记录读写权限）',
    minTtl: 600,
    credentialHint: 'SecretId / SecretKey 建议用 CAM 子账号且只授予 DNSPod 权限；免费档 TTL 下限 600 秒',
  },
}

const dnsMeta = computed(() => DNS_META[form.dnsMode] ?? DNS_META.cloudflare)

type DnsCredentialKey = 'dnsCloudflareToken' | 'dnsAliyunAk' | 'dnsAliyunSk' | 'dnsTencentSid' | 'dnsTencentSkey'

/** 当前驱动需要的凭据输入项（已配置只回显「已配置」标记，真值不回显）。 */
const dnsCredentialFields = computed<Array<{ key: DnsCredentialKey, label: string, configured: boolean }>>(() => {
  if (form.dnsMode === 'aliyun') {
    return [
      { key: 'dnsAliyunAk', label: 'AccessKeyId', configured: dnsAliyunConfigured.value },
      { key: 'dnsAliyunSk', label: 'AccessKeySecret', configured: dnsAliyunConfigured.value },
    ]
  }
  if (form.dnsMode === 'dnspod') {
    return [
      { key: 'dnsTencentSid', label: 'SecretId', configured: dnsTencentConfigured.value },
      { key: 'dnsTencentSkey', label: 'SecretKey', configured: dnsTencentConfigured.value },
    ]
  }
  if (form.dnsMode === 'cloudflare') {
    return [
      { key: 'dnsCloudflareToken', label: 'API Token', configured: dnsCloudflareConfigured.value },
    ]
  }
  return []
})

const dnsAnyCredentialConfigured = computed(() =>
  dnsCloudflareConfigured.value || dnsAliyunConfigured.value || dnsTencentConfigured.value)

const SOURCE_OPTIONS = [
  { label: '平台制品库（上传 jar）', value: 'file' },
  { label: '外部下载地址', value: 'url' },
]

const KIND_OPTIONS = [
  { label: '服务端插件（Paper 等，按 MC 版本区分）', value: 'plugin' },
  { label: '模组（按加载器细分）', value: 'mod' },
]

const LOADER_OPTIONS = [
  { label: 'Fabric', value: 'fabric' },
  { label: 'Forge', value: 'forge' },
  { label: 'NeoForge', value: 'neoforge' },
  { label: 'Quilt', value: 'quilt' },
]

const MC_VERSION_PATTERN = /^\d+\.\d+(\.\d+)?$/

/** fileId = mcpanel/artifacts/<32hex>-<name>，反推出展示名。 */
function artifactNameFromFileId(fileId: string): string {
  return fileId.replace(/^mcpanel\/artifacts\//, '').replace(/^[0-9a-f]{32}-/, '') || fileId
}

function formatSize(size: number): string {
  if (!size || size <= 0) {
    return ''
  }
  if (size < 1024) {
    return `${size} B`
  }
  if (size < 1024 * 1024) {
    return `${(size / 1024).toFixed(1)} KB`
  }
  return `${(size / 1024 / 1024).toFixed(2)} MB`
}

function shortSha(sha256: string): string {
  return sha256 ? `${sha256.slice(0, 12)}…` : ''
}

function checkJar(file: File): boolean {
  if (!file.name.toLowerCase().endsWith('.jar')) {
    toast.error('仅支持 .jar 制品文件')
    return false
  }
  return true
}

/** authlib jar：选中即上传到制品库，成功后面板展示制品信息，选择器复位。 */
async function captureAuthlibJar(options: FileUploadRequestOptions) {
  authlibUploading.value = true
  try {
    const uploaded = await extra.uploadArtifact(options.file) as UploadedArtifact
    authlibFile.value = uploaded
    toast.success(`已上传 ${uploaded.name}`)
  }
  catch (error) {
    toast.error(errorMessage(error, '制品上传失败'))
  }
  finally {
    authlibUploading.value = false
    authlibFileList.value = []
  }
  return { name: options.file.name }
}

function removeAuthlibFile() {
  authlibFile.value = null
}

// ---------- 时长统计制品矩阵 ----------

const artifactOpen = ref(false)
const artifactError = ref('')
const artifactUploading = ref(false)
const artifactFileList = ref<FileItem[]>([])
/** null = 新增；否则为编辑的行下标。 */
const artifactEditingIndex = ref<number | null>(null)
const artifactForm = reactive({
  name: '',
  kind: 'plugin' as 'plugin' | 'mod',
  loaders: [] as string[],
  mcMin: '',
  mcMax: '',
  source: 'file' as 'file' | 'url',
  url: '',
  file: null as UploadedArtifact | null,
})

function openArtifactCreate() {
  artifactEditingIndex.value = null
  artifactError.value = ''
  artifactForm.name = ''
  artifactForm.kind = 'plugin'
  artifactForm.loaders = []
  artifactForm.mcMin = ''
  artifactForm.mcMax = ''
  artifactForm.source = 'file'
  artifactForm.url = ''
  artifactForm.file = null
  artifactFileList.value = []
  artifactOpen.value = true
}

function openArtifactEdit(index: number) {
  const artifact = artifacts.value[index]
  if (!artifact) {
    return
  }
  artifactEditingIndex.value = index
  artifactError.value = ''
  artifactForm.name = artifact.name
  artifactForm.kind = artifact.kind === 'mod' ? 'mod' : 'plugin'
  artifactForm.loaders = [...(artifact.loaders ?? [])]
  artifactForm.mcMin = artifact.mcMin ?? ''
  artifactForm.mcMax = artifact.mcMax ?? ''
  artifactForm.source = artifact.fileId ? 'file' : 'url'
  artifactForm.url = artifact.url ?? ''
  artifactForm.file = artifact.fileId
    ? { fileId: artifact.fileId, name: artifactNameFromFileId(artifact.fileId), size: 0, sha256: artifact.sha256 ?? '' }
    : null
  artifactFileList.value = []
  artifactOpen.value = true
}

// FaTable 的行模型按 data 数组引用缓存，必须整组替换而非原地增删，否则表格不刷新
function removeArtifact(index: number) {
  artifacts.value = artifacts.value.filter((_, i) => i !== index)
}

async function captureArtifactJar(options: FileUploadRequestOptions) {
  artifactUploading.value = true
  try {
    const uploaded = await extra.uploadArtifact(options.file) as UploadedArtifact
    artifactForm.file = uploaded
    if (!artifactForm.name.trim()) {
      artifactForm.name = uploaded.name.replace(/\.jar$/i, '')
    }
    toast.success(`已上传 ${uploaded.name}`)
  }
  catch (error) {
    artifactError.value = errorMessage(error, '制品上传失败')
  }
  finally {
    artifactUploading.value = false
    artifactFileList.value = []
  }
  return { name: options.file.name }
}

function validateArtifact(): string {
  if (!artifactForm.name.trim()) {
    return '请输入制品名称'
  }
  if (artifactForm.kind === 'mod' && artifactForm.loaders.length === 0) {
    return 'mod 制品必须至少选择一个加载器'
  }
  if (artifactForm.source === 'file' && !artifactForm.file) {
    return '请上传制品 jar 文件'
  }
  if (artifactForm.source === 'url' && !artifactForm.url.trim()) {
    return '请输入外部下载地址'
  }
  for (const [field, label] of [['mcMin', 'MC 版本下限'], ['mcMax', 'MC 版本上限']] as const) {
    const value = artifactForm[field].trim()
    if (value && !MC_VERSION_PATTERN.test(value)) {
      return `${label}必须是 MC 版本号（如 1.20.1），留空表示不限`
    }
  }
  return ''
}

function applyArtifact() {
  const artifact: McpInjectArtifact = {
    name: artifactForm.name.trim(),
    kind: artifactForm.kind,
    loaders: artifactForm.kind === 'mod' ? [...artifactForm.loaders] : [],
    mcMin: artifactForm.mcMin.trim(),
    mcMax: artifactForm.mcMax.trim(),
    fileId: artifactForm.source === 'file' ? (artifactForm.file?.fileId ?? '') : '',
    url: artifactForm.source === 'url' ? artifactForm.url.trim() : '',
    sha256: artifactForm.source === 'file' ? (artifactForm.file?.sha256 ?? '') : '',
  }
  if (artifactEditingIndex.value === null) {
    artifacts.value = [...artifacts.value, artifact]
  }
  else {
    artifacts.value = artifacts.value.map((row, i) => (i === artifactEditingIndex.value ? artifact : row))
  }
}

function artifactBeforeClose(action: 'confirm' | 'cancel' | 'close', done: () => void) {
  if (action !== 'confirm') {
    done()
    return
  }
  artifactError.value = validateArtifact()
  if (artifactError.value) {
    return
  }
  applyArtifact()
  done()
}

const artifactColumns: TableColumn<McpInjectArtifact>[] = [
  { accessorKey: 'name', header: '名称', minWidth: 160 },
  { accessorKey: 'kind', header: '类型', width: 80, align: 'center' },
  { accessorKey: 'loaders', header: '加载器', minWidth: 130 },
  { id: 'mcRange', header: 'MC 版本', minWidth: 120 },
  { id: 'source', header: '来源', minWidth: 200 },
  { id: 'operation', header: '操作', width: 170, align: 'center' },
]

function mcRangeOf(artifact: McpInjectArtifact): string {
  const min = artifact.mcMin?.trim()
  const max = artifact.mcMax?.trim()
  if (!min && !max) {
    return '不限'
  }
  return `${min || '…'} ~ ${max || '…'}`
}

// ---------- 载入与保存 ----------

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const settings = await extra.viewSettings() as Record<string, any>
    const authlib = settings.authlib ?? {}
    form.authlibEnabled = Boolean(authlib.enabled)
    form.authlibJarUrl = String(authlib.jarUrl ?? '')
    form.authlibApiRoot = String(authlib.apiRoot ?? '')
    authlibApiRootAuto.value = String(authlib.apiRootAuto ?? '')
    authlibApiRootAvailable.value = Boolean(authlib.apiRootAutoAvailable)
    const jarFileId = String(authlib.jarFileId ?? '')
    if (jarFileId) {
      form.authlibSource = 'file'
      authlibFile.value = {
        fileId: jarFileId,
        name: artifactNameFromFileId(jarFileId),
        size: 0,
        sha256: String(authlib.sha256 ?? ''),
      }
    }
    else {
      form.authlibSource = form.authlibJarUrl ? 'url' : 'file'
      authlibFile.value = null
    }
    authlibFileList.value = []
    const playtime = settings.playtime ?? {}
    form.playtimeEnabled = Boolean(playtime.enabled)
    artifacts.value = Array.isArray(playtime.artifacts)
      ? playtime.artifacts.map((item: Record<string, any>) => ({
          name: String(item.name ?? ''),
          kind: item.kind === 'mod' ? 'mod' : 'plugin',
          loaders: Array.isArray(item.loaders) ? item.loaders.map(String) : [],
          mcMin: String(item.mcMin ?? ''),
          mcMax: String(item.mcMax ?? ''),
          fileId: String(item.fileId ?? ''),
          url: String(item.url ?? ''),
          sha256: String(item.sha256 ?? ''),
        }))
      : []
    const modpack = settings.modpack ?? {}
    form.modpackMirror = String(modpack.mirror ?? '')
    form.modpackAllowCurseforge = Boolean(modpack.allowCurseforge)
    modrinthSources.value = toSourceRows(modpack.modrinthSources)
    curseforgeSources.value = toSourceRows(modpack.curseforgeSources)
    cfConfigured.value = Boolean(modpack.cfApiKeyConfigured)
    const dns = settings.dns ?? {}
    form.dnsMode = String(dns.mode ?? 'off')
    form.dnsSuffix = String(dns.suffix ?? '')
    form.dnsTtl = Number(dns.ttlSeconds ?? 120)
    const provider = dns.provider ?? {}
    form.dnsZone = String(provider.zone ?? '')
    form.dnsApiBase = String(provider.apiBase ?? '')
    const entry = settings.entry ?? {}
    form.entryApiBase = String(entry.apiBase ?? '')
    form.entryHost = String(entry.host ?? '')
    form.entryPort = Number(entry.port ?? 25565) || 25565
    const dnsCredentials = dns.credentials ?? {}
    dnsCloudflareConfigured.value = Boolean(dnsCredentials.cloudflareTokenConfigured)
    dnsAliyunConfigured.value = Boolean(dnsCredentials.aliyunConfigured)
    dnsTencentConfigured.value = Boolean(dnsCredentials.tencentConfigured)
    const tenancy = settings.tenancy ?? {}
    form.tenancyEnabled = Boolean(tenancy.enabled)
    form.tenancyBilling = Boolean(tenancy.billing)
    form.tenancyPlatformRoles = Array.isArray(tenancy.platformRoleIds) ? tenancy.platformRoleIds.join(',') : ''
    const contribution = settings.contribution ?? {}
    form.contributionEnabled = Boolean(contribution.enabled)
    form.contributionReviewRequired = contribution.reviewRequired !== false
    form.contributionMaxPerUser = Number(contribution.maxPerUser ?? 2)
    form.contributionMaxInstances = Number(contribution.maxInstances ?? 3)
    form.contributionMaxCpuMillis = Number(contribution.maxCpuMillis ?? 4000)
    form.contributionMaxMemoryMb = Number(contribution.maxMemoryMb ?? 8192)
    const p2p = settings.p2p ?? {}
    form.p2pEnabled = Boolean(p2p.enabled)
    form.p2pDefaultRateKbps = Number(p2p.defaultRateKbps ?? 2048)
    form.p2pMaxSessions = Number(p2p.maxSessionsPerUser ?? 2)
    const coreDownload = settings.coreDownload ?? {}
    form.fastMirrorBase = String(coreDownload.fastMirrorBase ?? 'https://download.fastmirror.net')
    const sftpGateway = settings.sftpGateway ?? {}
    form.sftpGatewayEnabled = Boolean(sftpGateway.enabled)
    form.sftpGatewayPort = Number(sftpGateway.port ?? 22022) || 22022
    form.sftpGatewayHost = String(sftpGateway.advertisedHost ?? '')
  }
  catch (error) {
    loadError.value = errorMessage(error, '加载设置失败')
  }
  finally {
    loading.value = false
  }
}

function buildPayload() {
  return {
    authlib: {
      enabled: form.authlibEnabled,
      jarFileId: form.authlibSource === 'file' ? (authlibFile.value?.fileId ?? '') : '',
      jarUrl: form.authlibSource === 'url' ? form.authlibJarUrl.trim() : '',
      apiRoot: form.authlibApiRoot.trim(),
      sha256: form.authlibSource === 'file' ? (authlibFile.value?.sha256 ?? '') : '',
    },
    playtime: {
      enabled: form.playtimeEnabled,
      artifacts: artifacts.value.map(artifact => ({
        name: artifact.name,
        kind: artifact.kind,
        loaders: artifact.kind === 'mod' ? (artifact.loaders ?? []) : [],
        mcMin: artifact.mcMin ?? '',
        mcMax: artifact.mcMax ?? '',
        fileId: artifact.fileId ?? '',
        url: artifact.url ?? '',
        sha256: artifact.sha256 ?? '',
      })),
    },
    modpack: {
      mirror: form.modpackMirror.trim(),
      allowCurseforge: form.modpackAllowCurseforge,
      modrinthSources: cleanSources(modrinthSources.value),
      curseforgeSources: cleanSources(curseforgeSources.value),
    },
    dns: {
      mode: form.dnsMode,
      suffix: form.dnsSuffix.trim(),
      ttlSeconds: Number(form.dnsTtl) || 120,
      provider: {
        zone: form.dnsZone.trim(),
        apiBase: form.dnsApiBase.trim(),
      },
    },
    entry: {
      apiBase: form.entryApiBase.trim(),
      host: form.entryHost.trim(),
      port: Number(form.entryPort) || 25565,
    },
    tenancy: {
      enabled: form.tenancyEnabled,
      billing: form.tenancyBilling,
      platformRoleIds: form.tenancyPlatformRoles.split(',').map(s => s.trim()).filter(Boolean),
    },
    contribution: {
      enabled: form.contributionEnabled,
      reviewRequired: form.contributionReviewRequired,
      maxPerUser: form.contributionMaxPerUser,
      maxInstances: form.contributionMaxInstances,
      maxCpuMillis: form.contributionMaxCpuMillis,
      maxMemoryMb: form.contributionMaxMemoryMb,
    },
    p2p: {
      enabled: form.p2pEnabled,
      defaultRateKbps: form.p2pDefaultRateKbps,
      maxSessionsPerUser: form.p2pMaxSessions,
    },
    coreDownload: {
      fastMirrorBase: form.fastMirrorBase.trim() || 'https://download.fastmirror.net',
    },
    sftpGateway: {
      enabled: form.sftpGatewayEnabled,
      port: Number(form.sftpGatewayPort) || 0,
      advertisedHost: form.sftpGatewayHost.trim(),
    },
  }
}

function validateBeforeSave(): string {
  if (form.authlibEnabled && form.authlibSource === 'file' && !authlibFile.value) {
    return '启用 authlib 注入后需要上传 jar 制品（或切换为外部 URL）'
  }
  if (form.authlibEnabled && form.authlibSource === 'url' && !form.authlibJarUrl.trim()) {
    return '启用 authlib 注入后需要填写外部 jar 下载地址'
  }
  if (form.playtimeEnabled && artifacts.value.length === 0) {
    return '启用时长统计注入后至少需要添加一个制品'
  }
  if (form.dnsMode !== 'off') {
    if (!/^([A-Za-z0-9-]+\.)+[A-Za-z]{2,}$/.test(form.dnsSuffix.trim())) {
      return '域名自动解析需要填游戏域后缀（如 mc.example.com）'
    }
    if (!form.dnsZone.trim()) {
      return `域名自动解析需要填 ${dnsMeta.value.zoneLabel}`
    }
    if (form.dnsMode !== 'cloudflare'
      && !form.dnsSuffix.trim().toLowerCase().endsWith(`.${form.dnsZone.trim().toLowerCase()}`)
      && form.dnsSuffix.trim().toLowerCase() !== form.dnsZone.trim().toLowerCase()) {
      return `游戏域后缀必须落在根域名 ${form.dnsZone.trim()} 之内`
    }
    const missingConfiguration = dnsCredentialFields.value.some(field => !field.configured && !form[field.key].trim())
    if (missingConfiguration) {
      return `域名自动解析需要填 ${dnsMeta.value.label} 凭据（${dnsCredentialFields.value.map(field => field.label).join(' / ')}）`
    }
  }
  if (form.entryApiBase.trim() && !/^https?:\/\/[^\s/]+/i.test(form.entryApiBase.trim())) {
    return '入口 API 地址必须以 http:// 或 https:// 开头（如 http://127.0.0.1:8080）'
  }
  if (form.entryHost.trim() && !isValidHostName(form.entryHost.trim())) {
    return '入口对外地址只能是 IP（IPv4/IPv6）或主机名，不含协议与端口'
  }
  if (!Number.isInteger(Number(form.entryPort)) || Number(form.entryPort) < 1 || Number(form.entryPort) > 65535) {
    return '入口端口必须是 1-65535'
  }
  if (form.sftpGatewayEnabled && (!Number.isInteger(Number(form.sftpGatewayPort)) || Number(form.sftpGatewayPort) < 1 || Number(form.sftpGatewayPort) > 65535)) {
    return '启用 SFTP 网关后需要填写 1-65535 的监听端口'
  }
  if (form.sftpGatewayEnabled && form.sftpGatewayHost.trim() && !/^[A-Za-z0-9_.-]{1,255}$/.test(form.sftpGatewayHost.trim())) {
    return 'SFTP 网关展示地址只能是主机名或 IP（不含协议与端口）'
  }
  return ''
}

/** 密钥更新载荷：留空表示保持不变（清除需先切到「关闭」驱动再用下方按钮）。 */
function buildSecretUpdates(): Record<string, string> {
  const secrets: Record<string, string> = {}
  const cfKey = form.cfApiKey.trim()
  if (cfKey) {
    secrets.modpackCfKey = cfKey
  }
  const candidates: Array<[string, string]> = [
    ['dnsCloudflareToken', form.dnsCloudflareToken],
    ['dnsAliyunAccessKeyId', form.dnsAliyunAk],
    ['dnsAliyunAccessKeySecret', form.dnsAliyunSk],
    ['dnsTencentSecretId', form.dnsTencentSid],
    ['dnsTencentSecretKey', form.dnsTencentSkey],
  ]
  candidates.forEach(([name, value]) => {
    const text = value.trim()
    if (text) {
      secrets[name] = text
    }
  })
  return secrets
}

function resetSecretInputs() {
  form.cfApiKey = ''
  form.dnsCloudflareToken = ''
  form.dnsAliyunAk = ''
  form.dnsAliyunSk = ''
  form.dnsTencentSid = ''
  form.dnsTencentSkey = ''
}

// ---------- 单端口入口（mc-router） ----------

interface EntryStatus {
  configured?: boolean
  reason?: string
  apiBase?: string
  host?: string
  port?: number
  suffix?: string
  expectedRoutes?: number
  routerRoutes?: number
  reachable?: boolean
  lastError?: string
  message?: string
  drift?: Array<{ serverAddress?: string, expectedBackend?: string, currentBackend?: string }>
}

const entryStatus = ref<EntryStatus | null>(null)
const entryBusy = ref(false)

/** 载入入口状态：只在管理员填了入口配置时才会外呼（未填 = 面板不碰 router）。 */
async function loadEntryStatus() {
  entryBusy.value = true
  try {
    entryStatus.value = await extra.entryStatus() as EntryStatus
  }
  catch (error) {
    entryStatus.value = { configured: false, reason: errorMessage(error, '入口状态读取失败') }
  }
  finally {
    entryBusy.value = false
  }
}

/** 手动对账：补推缺失/不一致、清理本面板域后缀下的多余路由。 */
async function reconcileEntry() {
  entryBusy.value = true
  try {
    const result = await extra.entryReconcile() as { expected?: number, pushed?: number, removed?: number, skipped?: boolean }
    if (result?.skipped) {
      toast.info('当前没有使用「单端口入口」模式的实例，未做任何改动')
    }
    else {
      toast.success(`对账完成：期望 ${result?.expected ?? 0} 条，补推 ${result?.pushed ?? 0} 条，清理 ${result?.removed ?? 0} 条`)
    }
    await loadEntryStatus()
  }
  catch (error) {
    toast.error(errorMessage(error, '对账失败（检查入口 API 地址与可达性）'))
  }
  finally {
    entryBusy.value = false
  }
}

/** 入口对外地址：IP（v4/v6）或主机名。 */
function isValidHostName(host: string): boolean {
  if (host.includes('://') || host.includes('/') || host.includes('\\') || /\s/.test(host)) {
    return false
  }
  const isIpv4 = /^(\d{1,3}\.){3}\d{1,3}$/.test(host) && host.split('.').every(part => Number(part) <= 255)
  const isIpv6 = host.includes(':') && /^[0-9a-fA-F:]+$/.test(host)
  const isHostname = !host.includes(':') && !/^[0-9.]+$/.test(host)
    && /^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$/.test(host)
  return isIpv4 || isIpv6 || isHostname
}

/** 清除已保存的云解析凭据：需先切到「关闭」驱动（否则保存校验会拦下）。 */
function clearDnsCredentials() {
  const secrets: Record<string, string> = {}
  if (dnsCloudflareConfigured.value) {
    secrets.dnsCloudflareToken = ''
  }
  if (dnsAliyunConfigured.value) {
    secrets.dnsAliyunAccessKeyId = ''
    secrets.dnsAliyunAccessKeySecret = ''
  }
  if (dnsTencentConfigured.value) {
    secrets.dnsTencentSecretId = ''
    secrets.dnsTencentSecretKey = ''
  }
  if (!Object.keys(secrets).length) {
    toast.info('当前没有已保存的 DNS 凭据')
    return
  }
  modal.confirm({
    title: '清除 DNS 凭据',
    content: '清除后已启用的驱动将无法写入解析记录，需要重新填写凭据才能恢复。确认清除？',
    confirmButtonText: '清除',
    onConfirm: async () => {
      saving.value = true
      try {
        await extra.saveSettings(buildPayload(), secrets)
        toast.success('已清除 DNS 凭据')
        resetSecretInputs()
        void load()
      }
      catch (error) {
        toast.error(errorMessage(error, '清除失败（请先把驱动切换为「关闭」再保存）'))
      }
      finally {
        saving.value = false
      }
    },
  })
}

async function save() {
  if (saving.value) {
    return
  }
  const invalid = validateBeforeSave()
  if (invalid) {
    toast.error(invalid)
    return
  }
  saving.value = true
  try {
    await extra.saveSettings(buildPayload(), buildSecretUpdates())
    toast.success('设置已保存')
    resetSecretInputs()
    void load()
  }
  catch (error) {
    toast.error(errorMessage(error, '保存设置失败'))
  }
  finally {
    saving.value = false
  }
}

onMounted(() => void load())
</script>

<template>
  <FaPageHeader title="面板设置" description="注入下载源、下载与导入、DNS、租户、节点贡献与 P2P 全局配置" />
  <FaPageMain v-loading="loading">
    <FaAlert v-if="loadError" variant="destructive" title="无法加载设置" class="mb-4">
  <template #description>
      {{ loadError }}
  </template>
    </FaAlert>

    <div class="grid grid-cols-1 items-start gap-4 xl:grid-cols-2">
      <FaCard title="注入下载源（authlib-injector / 时长统计）" class="xl:col-span-2">
        <div class="mcp-form">
          <label class="flex items-center gap-3 text-sm">
            <FaSwitch v-model="form.authlibEnabled" />
            启用 authlib-injector 注入（Java 系核心以 -javaagent 注入）
          </label>
          <template v-if="form.authlibEnabled">
            <div class="mcp-form-item">
              <span class="mcp-form-label">authlib jar 来源</span>
              <FaRadioGroup v-model="form.authlibSource" :options="SOURCE_OPTIONS" class="flex flex-wrap gap-3" />
            </div>
            <div v-if="form.authlibSource === 'file'" class="mcp-form-item">
              <span class="mcp-form-label">authlib jar 制品</span>
              <div v-if="authlibFile" class="mcp-artifact">
                <div class="min-w-0 flex-1">
                  <div class="truncate text-sm font-medium">
                    {{ authlibFile.name }}
                  </div>
                  <div class="mcp-mono truncate text-xs opacity-70">
                    {{ formatSize(authlibFile.size) }}{{ authlibFile.size ? ' · ' : '' }}sha256 {{ shortSha(authlibFile.sha256) || '（历史制品未记录）' }}
                  </div>
                </div>
                <div class="flex shrink-0 items-center gap-2">
                  <FaButton size="sm" variant="outline" as="a" :href="extra.artifactDownloadUrl(authlibFile.fileId)" target="_blank">
                    下载
                  </FaButton>
                  <FaButton size="sm" variant="ghost" @click="removeAuthlibFile">
                    移除
                  </FaButton>
                </div>
              </div>
              <FaFileUpload
                v-else
                v-model="authlibFileList"
                :max="1"
                :before-upload="checkJar"
                :http-request="captureAuthlibJar"
                :description="authlibUploading ? '正在上传制品…' : '拖放或点击选择 authlib-injector .jar，选中即上传到平台制品库'"
              />
              <span class="mcp-form-hint">制品统一存放于平台文件库，节点端经面板代理下载，不需要平台凭据。</span>
            </div>
            <label v-else class="mcp-form-item">
              <span class="mcp-form-label">authlib jar 外部下载地址</span>
              <FaInput v-model="form.authlibJarUrl" clearable placeholder="https://…/authlib-injector.jar" class="mcp-w-full mcp-mono" />
            </label>
            <div class="mcp-form-item">
              <span class="mcp-form-label">验证服 API 根</span>
              <FaInput
                v-model="form.authlibApiRoot"
                clearable
                :placeholder="authlibApiRootAvailable ? `留空使用联动值：${authlibApiRootAuto}` : 'https://authlib 验证服地址'"
                class="mcp-w-full mcp-mono"
              />
              <span v-if="authlibApiRootAvailable" class="mcp-form-hint">
                已从 authlib-injector 插件自动获取：{{ authlibApiRootAuto }}；留空即使用该值，手填则覆盖。
              </span>
              <FaAlert v-else variant="destructive" title="未检测到 authlib-injector 插件" class="mt-1">
  <template #description>
                无法自动获取验证服 API 根，请手动填写；或先在宿主安装并启用 authlib-injector 插件。
  </template>
              </FaAlert>
            </div>
          </template>

          <div class="mcp-divider" />

          <label class="flex items-center gap-3 text-sm">
            <FaSwitch v-model="form.playtimeEnabled" />
            启用时长统计注入（Bukkit 系插件 / 模组两种形态）
          </label>
          <template v-if="form.playtimeEnabled">
            <div class="mcp-form-item">
              <div class="mb-2 flex items-center justify-between gap-2">
                <span class="mcp-form-label">制品矩阵（按实例核心类型与 MC 版本匹配）</span>
                <FaButton size="sm" variant="outline" @click="openArtifactCreate">
                  添加制品
                </FaButton>
              </div>
              <FaTable
                :columns="artifactColumns"
                :data="artifacts"
                row-key="name"
                table-root-class="rounded-lg overflow-hidden"
                border
                stripe
                empty-text="尚未添加制品；启用后至少需要一个制品"
              >
                <template #cell-kind="{ row }">
                  <FaTag :variant="row.original.kind === 'mod' ? 'secondary' : 'default'">
                    {{ row.original.kind === 'mod' ? '模组' : '插件' }}
                  </FaTag>
                </template>
                <template #cell-loaders="{ row }">
                  <span v-if="row.original.kind === 'mod'">{{ (row.original.loaders ?? []).join(' / ') || '—' }}</span>
                  <span v-else class="opacity-60">—</span>
                </template>
                <template #cell-mcRange="{ row }">
                  {{ mcRangeOf(row.original) }}
                </template>
                <template #cell-source="{ row }">
                  <div v-if="row.original.fileId" class="flex min-w-0 items-center gap-2">
                    <span class="truncate text-sm">{{ artifactNameFromFileId(row.original.fileId) }}</span>
                    <FaButton size="sm" variant="ghost" as="a" :href="extra.artifactDownloadUrl(row.original.fileId)" target="_blank" class="shrink-0">
                      下载
                    </FaButton>
                  </div>
                  <span v-else class="mcp-mono block truncate text-xs" :title="row.original.url">{{ row.original.url }}</span>
                </template>
                <template #cell-operation="{ row }">
                  <div class="mcp-op-cell">
                    <FaButton size="sm" variant="outline" @click="openArtifactEdit(row.index)">
                      编辑
                    </FaButton>
                    <FaButton size="sm" variant="destructive" @click="removeArtifact(row.index)">
                      删除
                    </FaButton>
                  </div>
                </template>
              </FaTable>
              <span class="mcp-form-hint">插件形态按 MC 版本区分（如 Paper 多版本）；模组形态按加载器（Fabric/Forge/NeoForge/Quilt）与 MC 版本细分。注入链路上线后按实例环境自动挑选匹配制品。</span>
            </div>
          </template>
        </div>
      </FaCard>

      <FaCard title="下载与导入">
        <div class="mcp-form">
          <label class="mcp-form-item">
            <span class="mcp-form-label">FastMirror 基址</span>
            <FaInput v-model="form.fastMirrorBase" clearable placeholder="https://download.fastmirror.net" class="mcp-w-full mcp-mono" />
            <span class="mcp-form-hint">创建服务器自动下载核心时优先走该镜像站；失败自动回退官方 Fill / Purpur / Piston / Fabric。</span>
          </label>
          <div class="mcp-form-item">
            <span class="mcp-form-label">Modrinth 源（按顺序回退，MCIM 优先）</span>
            <div class="space-y-2">
              <div
                v-for="(row, index) in modrinthSources"
                :key="`mr-${index}`"
                class="flex flex-wrap items-center gap-2"
              >
                <FaInput v-model="row.name" placeholder="名称" class="w-28 shrink-0" />
                <FaInput v-model="row.apiBase" placeholder="API 前缀（如 https://mod.mcimirror.top/modrinth/v2）" class="min-w-0 flex-1 mcp-mono" />
                <FaInput v-model="row.cdnBase" placeholder="文件前缀（如 https://mod.mcimirror.top）" class="min-w-0 flex-1 mcp-mono" />
                <FaButton size="sm" variant="outline" :disabled="index === 0" @click="moveSourceRow(modrinthSources, index, -1)">↑</FaButton>
                <FaButton size="sm" variant="outline" :disabled="index === modrinthSources.length - 1" @click="moveSourceRow(modrinthSources, index, 1)">↓</FaButton>
                <FaButton size="sm" variant="outline" @click="removeSourceRow(modrinthSources, index)">删除</FaButton>
              </div>
              <FaButton size="sm" variant="outline" @click="addSourceRow(modrinthSources)">添加 Modrinth 源</FaButton>
            </div>
            <span class="mcp-form-hint">检索与文件下载按此顺序尝试、失败自动切换下一个；文件前缀替换官方 CDN 主机、路径保持原样。</span>
          </div>
          <div class="mcp-form-item">
            <span class="mcp-form-label">CurseForge 源（按顺序回退，MCIM 优先）</span>
            <div class="space-y-2">
              <div
                v-for="(row, index) in curseforgeSources"
                :key="`cf-${index}`"
                class="flex flex-wrap items-center gap-2"
              >
                <FaInput v-model="row.name" placeholder="名称" class="w-28 shrink-0" />
                <FaInput v-model="row.apiBase" placeholder="API 前缀（如 https://mod.mcimirror.top/curseforge/v1，可留空）" class="min-w-0 flex-1 mcp-mono" />
                <FaInput v-model="row.cdnBase" placeholder="文件前缀（如 https://mod.mcimirror.top）" class="min-w-0 flex-1 mcp-mono" />
                <FaButton size="sm" variant="outline" :disabled="index === 0" @click="moveSourceRow(curseforgeSources, index, -1)">↑</FaButton>
                <FaButton size="sm" variant="outline" :disabled="index === curseforgeSources.length - 1" @click="moveSourceRow(curseforgeSources, index, 1)">↓</FaButton>
                <FaButton size="sm" variant="outline" @click="removeSourceRow(curseforgeSources, index)">删除</FaButton>
              </div>
              <FaButton size="sm" variant="outline" @click="addSourceRow(curseforgeSources)">添加 CurseForge 源</FaButton>
            </div>
            <span class="mcp-form-hint">文件前缀替换 edge.forgecdn.net 主机（mediafilez 原链保留为末位兜底）；API 前缀用于持有 API key 时的文件解析。</span>
          </div>
          <label class="flex items-center gap-3 text-sm">
            <FaSwitch v-model="form.modpackAllowCurseforge" />
            允许 CurseForge 整合包导入（需下方 API key）
          </label>
          <label class="mcp-form-item">
            <span class="mcp-form-label">CurseForge API Key（只写不读{{ cfConfigured ? '，已配置，留空保持不变' : '，未配置' }}）</span>
            <FaInput v-model="form.cfApiKey" type="password" :disabled="!form.modpackAllowCurseforge" clearable class="mcp-w-full mcp-mono" />
          </label>
        </div>
      </FaCard>

      <FaCard title="实例域名（DNS 自动解析）">
        <div class="mcp-form">
          <label class="mcp-form-item">
            <span class="mcp-form-label">驱动</span>
            <FaSelect v-model="form.dnsMode" :options="DNS_MODES" class="mcp-w-full" />
          </label>
          <template v-if="form.dnsMode !== 'off'">
            <div class="grid grid-cols-1 gap-3 md:grid-cols-2">
              <label class="mcp-form-item">
                <span class="mcp-form-label">游戏域后缀（如 mc.example.com）</span>
                <FaInput v-model="form.dnsSuffix" clearable class="mcp-w-full mcp-mono" />
              </label>
              <label class="mcp-form-item">
                <span class="mcp-form-label">{{ dnsMeta.zoneLabel }}</span>
                <FaInput v-model="form.dnsZone" clearable class="mcp-w-full mcp-mono" />
              </label>
            </div>
            <span class="mcp-form-hint">{{ dnsMeta.zoneHint }}</span>
            <div class="grid grid-cols-1 gap-3 md:grid-cols-2">
              <label class="mcp-form-item">
                <span class="mcp-form-label">TTL（秒，本驱动下限 {{ dnsMeta.minTtl }}）</span>
                <FaInput v-model="form.dnsTtl" type="number" class="mcp-w-full" />
              </label>
              <label class="mcp-form-item">
                <span class="mcp-form-label">API Base（留空默认）</span>
                <FaInput v-model="form.dnsApiBase" clearable class="mcp-w-full mcp-mono" />
              </label>
            </div>

            <label v-for="field in dnsCredentialFields" :key="field.key" class="mcp-form-item">
              <span class="mcp-form-label">
                {{ field.label }}
                <FaTag v-if="field.configured" variant="default" class="ml-2">
                  已配置
                </FaTag>
              </span>
              <FaInput
                v-model="form[field.key]"
                type="password"
                :placeholder="field.configured ? '留空 = 保持不变' : '必填'"
                clearable
                class="mcp-w-full mcp-mono"
              />
            </label>
            <span class="mcp-form-hint">{{ dnsMeta.credentialHint }}</span>
            <span class="mcp-form-hint">
              A 记录随实例创建写入、实例删除时清理；SRV 记录让玩家免端口直连（仅 Java 版）。
              换节点或换端口后可在实例的「实例域名」页重新同步。
            </span>
          </template>
          <template v-else>
            <span class="mcp-form-hint">
              关闭后不再分配域名，实例页也不显示域名入口（已写入的解析记录不会被删除）。
            </span>
            <div v-if="dnsAnyCredentialConfigured" class="mcp-row">
              <FaButton size="sm" variant="outline" :disabled="saving" @click="clearDnsCredentials">
                清除已保存的 DNS 凭据
              </FaButton>
            </div>
          </template>
        </div>
      </FaCard>

      <FaCard title="单端口入口（mc-router）">
        <div class="mcp-form">
          <label class="mcp-form-item">
            <span class="mcp-form-label">入口 API 地址</span>
            <FaInput v-model="form.entryApiBase" clearable placeholder="http://127.0.0.1:8080" class="mcp-w-full mcp-mono" />
            <span class="mcp-form-hint">
              mc-router 的 REST API（容器启动参数 API_BINDING）。<b>该 API 无鉴权</b>：只在内网/防火墙后暴露，
              面板与它同机或同内网可达即可。留空 = 不启用入口（面板完全不碰 router）。
            </span>
          </label>
          <div class="grid grid-cols-1 gap-3 md:grid-cols-2">
            <label class="mcp-form-item">
              <span class="mcp-form-label">入口对外地址（域名解析指向它）</span>
              <FaInput v-model="form.entryHost" clearable placeholder="如 1.2.3.4 / entry.example.com" class="mcp-w-full mcp-mono" />
            </label>
            <label class="mcp-form-item">
              <span class="mcp-form-label">入口端口</span>
              <FaInput v-model="form.entryPort" type="number" class="mcp-w-full" />
            </label>
          </div>
          <span class="mcp-form-hint">
            仅当节点「玩家接入方式」选「单端口入口」时才生效：解析 A 记录指向入口地址，面板把
            「域名 → 实例」路由推给 mc-router，玩家用各自域名连同一个入口端口。入口端口为 25565 时不写 SRV（默认端口直连），
            非默认端口会自动补 SRV。入口侧记得开 PROXY protocol 并把实例配置里的
            <code class="rounded bg-muted px-1 font-mono">proxies.proxy-protocol</code> 打开，否则玩家 IP 都是入口机地址。
          </span>

          <div class="mcp-row">
            <FaButton size="sm" variant="outline" :loading="entryBusy" @click="loadEntryStatus">
              测试连接
            </FaButton>
            <FaButton size="sm" variant="outline" :loading="entryBusy" :disabled="!form.entryApiBase.trim()" @click="reconcileEntry">
              立即对账
            </FaButton>
          </div>

          <template v-if="entryStatus">
            <div class="flex flex-wrap items-center gap-2 text-sm">
              <FaTag :variant="entryStatus.reachable ? 'default' : 'secondary'">
                {{ entryStatus.reachable ? '入口可达' : '未连通' }}
              </FaTag>
              <span v-if="entryStatus.reachable" class="text-xs text-muted-foreground">
                期望路由 {{ entryStatus.expectedRoutes ?? 0 }} 条 · 入口现有 {{ entryStatus.routerRoutes ?? 0 }} 条 · 差异 {{ (entryStatus.drift ?? []).length }} 条
              </span>
              <span v-else class="text-xs text-muted-foreground">
                {{ entryStatus.reason || entryStatus.message || entryStatus.lastError || '未配置或不可达' }}
              </span>
            </div>
            <div v-if="(entryStatus.drift ?? []).length" class="text-xs text-muted-foreground">
              差异（点「立即对账」补齐）：
              <span v-for="item in entryStatus.drift" :key="item.serverAddress" class="mr-2 font-mono">
                {{ item.serverAddress }} → 期望 {{ item.expectedBackend }}，当前 {{ item.currentBackend || '（无）' }}
              </span>
            </div>
          </template>
        </div>
      </FaCard>

      <FaCard title="多租户与配额">
        <div class="mcp-form">
          <label class="flex items-center gap-3 text-sm">
            <FaSwitch v-model="form.tenancyEnabled" />
            启用租户数据范围（按部门/角色绑定；关闭 = 全局管理员直管）
          </label>
          <label class="mcp-form-item">
            <span class="mcp-form-label">平台管理角色 ID（逗号分隔，命中即不受租户约束）</span>
            <FaInput v-model="form.tenancyPlatformRoles" :disabled="!form.tenancyEnabled" clearable class="mcp-w-full mcp-mono" />
          </label>
          <label class="flex items-center gap-3 text-sm">
            <FaSwitch v-model="form.tenancyBilling" :disabled="!form.tenancyEnabled" />
            配额计费（钱包软联动；钱包插件未安装时仅记账）
          </label>
        </div>
      </FaCard>

      <FaCard title="节点贡献（玩家侧）">
        <div class="mcp-form">
          <label class="flex items-center gap-3 text-sm">
            <FaSwitch v-model="form.contributionEnabled" />
            开放玩家贡献节点申请（/me/contributions）
          </label>
          <label class="flex items-center gap-3 text-sm">
            <FaSwitch v-model="form.contributionReviewRequired" :disabled="!form.contributionEnabled" />
            申请需管理员审核
          </label>
          <div class="grid grid-cols-1 gap-3 md:grid-cols-4">
            <label class="mcp-form-item">
              <span class="mcp-form-label">每人上限</span>
              <FaInput v-model="form.contributionMaxPerUser" type="number" :disabled="!form.contributionEnabled" class="mcp-w-full" />
            </label>
            <label class="mcp-form-item">
              <span class="mcp-form-label">实例数上限</span>
              <FaInput v-model="form.contributionMaxInstances" type="number" :disabled="!form.contributionEnabled" class="mcp-w-full" />
            </label>
            <label class="mcp-form-item">
              <span class="mcp-form-label">CPU 上限（毫核）</span>
              <FaInput v-model="form.contributionMaxCpuMillis" type="number" :disabled="!form.contributionEnabled" class="mcp-w-full" />
            </label>
            <label class="mcp-form-item">
              <span class="mcp-form-label">内存上限（MB）</span>
              <FaInput v-model="form.contributionMaxMemoryMb" type="number" :disabled="!form.contributionEnabled" class="mcp-w-full" />
            </label>
          </div>
          <span class="mcp-form-hint">贡献节点为半可信：正式实例默认不调度其上，平台级秘密从不下发。</span>
        </div>
      </FaCard>

      <FaCard title="启动器 P2P 直连">
        <div class="mcp-form">
          <label class="flex items-center gap-3 text-sm">
            <FaSwitch v-model="form.p2pEnabled" />
            开启 P2P 信令端点（启动器域插件据此为玩家开「无感直连」会话；玩家流量不经面板）
          </label>
          <div class="grid grid-cols-1 gap-3 md:grid-cols-2">
            <label class="mcp-form-item">
              <span class="mcp-form-label">默认带宽档（KB/s）</span>
              <FaInput v-model="form.p2pDefaultRateKbps" type="number" :disabled="!form.p2pEnabled" class="mcp-w-full" />
            </label>
            <label class="mcp-form-item">
              <span class="mcp-form-label">每人并发会话上限</span>
              <FaInput v-model="form.p2pMaxSessions" type="number" :disabled="!form.p2pEnabled" class="mcp-w-full" />
            </label>
          </div>
          <span class="mcp-form-hint">
            开关只管信令与票据；每个实例还要在「实例设置 → P2P 直连」单独开启（可配玩家白名单）。
            玩家侧无感：启动器（YMCL 适配器）在点「连接」时自动开会话，由随启动器的 sidecar 与节点握手转发；
            节点程序需支持 p2p.* 能力（面板会如实显示能力缺口）。当前传输为 TCP 直连，节点需公网可达或已做端口映射。
          </span>
        </div>
      </FaCard>

      <FaCard title="SFTP 网关">
        <div class="mcp-form">
          <label class="flex items-center gap-3 text-sm">
            <FaSwitch v-model="form.sftpGatewayEnabled" />
            开启后桌面 SFTP 客户端（WinSCP 等）经面板单端口中转，节点端口不再对使用方暴露
          </label>
          <div class="grid grid-cols-1 gap-3 md:grid-cols-2">
            <label class="mcp-form-item">
              <span class="mcp-form-label">监听端口</span>
              <FaInput v-model="form.sftpGatewayPort" type="number" :disabled="!form.sftpGatewayEnabled" class="mcp-w-full" />
            </label>
            <label class="mcp-form-item">
              <span class="mcp-form-label">对外展示主机名/IP（留空按站点主机名展示）</span>
              <FaInput v-model="form.sftpGatewayHost" clearable placeholder="panel.example.com" :disabled="!form.sftpGatewayEnabled" class="mcp-w-full" />
            </label>
          </div>
          <p class="text-xs opacity-60">
            需在防火墙/容器映射中放行该端口；凭据为面板签发的 mc-短别名 + 随机密码，TTL 与节点侧一致（≤2 小时）。
          </p>
        </div>
      </FaCard>
    </div>

    <div class="mt-4 flex justify-end">
      <FaButton :loading="saving" @click="save">
        保存设置
      </FaButton>
    </div>

    <FaModal
      v-model="artifactOpen"
      :title="artifactEditingIndex === null ? '添加制品' : '编辑制品'"
      :show-cancel-button="true"
      :confirm-button-text="artifactEditingIndex === null ? '添加' : '保存'"
      class="max-w-[min(42rem,calc(100vw-2rem))]"
      :before-close="artifactBeforeClose"
    >
      <div class="mcp-form">
        <label class="mcp-form-item">
          <span class="mcp-form-label">制品名称</span>
          <FaInput v-model="artifactForm.name" clearable placeholder="如 playtime-paper-1.20" class="mcp-w-full" />
        </label>
        <div class="mcp-form-item">
          <span class="mcp-form-label">形态</span>
          <FaRadioGroup v-model="artifactForm.kind" :options="KIND_OPTIONS" class="flex flex-col gap-2" />
        </div>
        <div v-if="artifactForm.kind === 'mod'" class="mcp-form-item">
          <span class="mcp-form-label">适用加载器（可多选）</span>
          <FaCheckboxGroup v-model="artifactForm.loaders" :options="LOADER_OPTIONS" class="flex flex-wrap gap-3" />
        </div>
        <div class="mcp-form-item">
          <span class="mcp-form-label">适用 MC 版本范围（留空不限）</span>
          <div class="flex items-center gap-2">
            <FaInput v-model="artifactForm.mcMin" clearable placeholder="下限，如 1.20" class="mcp-w-full" />
            <span class="shrink-0 text-sm opacity-60">–</span>
            <FaInput v-model="artifactForm.mcMax" clearable placeholder="上限，如 1.21.1" class="mcp-w-full" />
          </div>
        </div>
        <div class="mcp-form-item">
          <span class="mcp-form-label">制品来源</span>
          <FaRadioGroup v-model="artifactForm.source" :options="SOURCE_OPTIONS" class="flex flex-wrap gap-3" />
        </div>
        <div v-if="artifactForm.source === 'file'" class="mcp-form-item">
          <span class="mcp-form-label">制品 jar</span>
          <div v-if="artifactForm.file" class="mcp-artifact">
            <div class="min-w-0 flex-1">
              <div class="truncate text-sm font-medium">
                {{ artifactForm.file.name }}
              </div>
              <div class="mcp-mono truncate text-xs opacity-70">
                {{ formatSize(artifactForm.file.size) }}{{ artifactForm.file.size ? ' · ' : '' }}sha256 {{ shortSha(artifactForm.file.sha256) || '（历史制品未记录）' }}
              </div>
            </div>
            <FaButton size="sm" variant="ghost" class="shrink-0" @click="artifactForm.file = null">
              重新选择
            </FaButton>
          </div>
          <FaFileUpload
            v-else
            v-model="artifactFileList"
            :max="1"
            :before-upload="checkJar"
            :http-request="captureArtifactJar"
            :description="artifactUploading ? '正在上传制品…' : '拖放或点击选择 .jar，选中即上传到平台制品库'"
          />
        </div>
        <label v-else class="mcp-form-item">
          <span class="mcp-form-label">外部下载地址</span>
          <FaInput v-model="artifactForm.url" clearable placeholder="https://…/playtime.jar" class="mcp-w-full mcp-mono" />
        </label>
        <FaAlert v-if="artifactError" variant="destructive" title="无法保存制品">
  <template #description>
          {{ artifactError }}
  </template>
        </FaAlert>
      </div>
    </FaModal>
  </FaPageMain>
</template>
