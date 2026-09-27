<script setup lang="ts">
import type { FileItem, TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { FaAlert, FaButton, FaCard, FaContextMenu, FaFileUpload, FaIcon, FaInput, FaModal, FaPageHeader, FaPageMain, FaPagination, FaProgress, FaResponsiveTable, FaSelect, FaTag, FaTooltip, useFaModal, useFaToast } from '@yudream/components'
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { withTimeout } from '../../utils/fileContent.ts'
import { relativeFilePath } from '../../utils/textFileAccess.ts'
import { useChunkedDownload } from '../../composables/useChunkedDownload.ts'
import { useRoute, useRouter } from 'vue-router'
import { createMcPanelApi } from '../../api/mcpanel-api.ts'
import { createMcPanelExtra } from '../../api/api-extra.ts'
import DirTreePanel from '../../components/DirTreePanel.vue'
import FileEditorModal from '../../components/FileEditorModal.vue'
import UploadTasksPanel from '../../components/UploadTasksPanel.vue'
import { createTextFileAccess } from '../../utils/textFileAccess.ts'
import { useInstanceUploads } from '../../composables/useInstanceUploads.ts'
import { MCPANEL_PERMISSION, accountHasPermission } from '../../composables/permissions.ts'
import { errorMessage, formatDateTime, formatGib, formatSize, toNumberOr } from '../../composables/utils.ts'

/**
 * 实例文件 / SFTP 通道（对标 MCSM 文件管理 + 1Panel 文件工作区）：
 * 大屏双栏（左目录树 / 右当前目录表格）+ 磁盘监控条 + 1Panel 风格多标签编辑器
 * + 临时 SFTP 开通（节点 ftp.open）。目录浏览、上传/下载/压缩/解压/重命名/删除。
 */
const props = defineProps<{ sdk: YuDreamPluginSdk }>()

const api = createMcPanelApi(props.sdk)
const extra = createMcPanelExtra(props.sdk)
const toast = useFaToast()
const modal = useFaModal()
const router = useRouter()
const route = useRoute()

const canUse = computed(() => accountHasPermission(props.sdk.account, MCPANEL_PERMISSION.use))
const canManage = computed(() => accountHasPermission(props.sdk.account, MCPANEL_PERMISSION.manage))

const instanceId = computed(() => String(route.params.id ?? ''))
const detailPath = computed(() => `/platform/plugins/mcpanel/admin/instances/${instanceId.value}`)

const instanceName = ref('')
const instanceState = ref('')
const instanceMemoryMb = ref(0)
const nodeStats = ref<{ diskUsedGb?: number, diskTotalGb?: number } | null>(null)
const currentPath = ref('')
const loading = ref(false)
const loadError = ref('')
const rows = ref<Record<string, unknown>[]>([])
/** 目录分页：目录优先排序由节点保证；keyword 为名称子串过滤（回车触发）。 */
const pager = reactive({ page: 1, size: 50, total: 0 })
const keyword = ref('')

const download = useChunkedDownload()
const treeOpen = ref(false)
const mkdirBusy = ref(false)
const deleteBusy = ref(false)

const editorOpen = ref(false)
const editorPath = ref('')
const editorDirectory = ref('')
const editorAccess = createTextFileAccess({
  readChunk: (path, offset, length) => extra.readFileChunk(instanceId.value, path, offset, length),
  write: (path, content, charset) => extra.writeFile(instanceId.value, path, content, 'base64', charset),
})

const uploadOpen = ref(false)
const uploadPath = ref('')
const uploadBusy = ref(false)
const uploadItems = ref<FileItem[]>([])
const uploadFileRef = ref<File | null>(null)

/** 异步分片上传任务：上传不阻塞页面，进度面板实时可见，重进页面自动恢复。 */
const { tasks: uploadTaskItems, startUpload, cancelTask: cancelUploadTask, isHashing: uploadHashing } =
  useInstanceUploads(props.sdk, instanceId)

const newName = ref('')
const renameOpen = ref(false)
const renameFrom = ref('')
const renameTo = ref('')
const renameBusy = ref(false)

const moveOpen = ref(false)
const moveTarget = ref('')
const moveBusy = ref(false)

const zipMultiOpen = ref(false)
const zipMultiName = ref('')
const zipMultiBusy = ref(false)

const ftpOpen = ref(false)
const ftpBusy = ref(false)
const ftpTtl = ref(60)
const ftpInfo = ref<Record<string, unknown> | null>(null)

const columns: TableColumn<Record<string, unknown>>[] = [
  { type: 'selection', fixed: 'left', width: 50 },
  { accessorKey: 'name', header: '名称', minWidth: 240, fixed: 'left' },
  { id: 'type', header: '类型', width: 90, align: 'center' },
  { accessorKey: 'size', header: '大小', width: 110, align: 'center' },
  { accessorKey: 'modTime', header: '修改时间', width: 170 },
  { id: 'operation', header: '操作', width: 220, align: 'center', fixed: 'right' },
]

const diskPercent = computed(() => {
  const total = toNumberOr(nodeStats.value?.diskTotalGb)
  if (!total) {
    return 0
  }
  return Math.min(100, Math.max(0, Math.round((toNumberOr(nodeStats.value?.diskUsedGb) / total) * 100)))
})

const STATE_LABEL: Record<string, string> = {
  running: '运行中',
  starting: '启动中',
  exited: '已退出',
  created: '已创建',
  installing: '安装中',
  unknown: '未知',
}

function joinPath(base: string, name: string) {
  return base ? `${base}/${name}` : name
}

async function loadInstanceMeta() {
  try {
    const detail = await extra.instanceDetail(instanceId.value) as { name?: string, nodeId?: string, memoryMb?: number, state?: string }
    instanceName.value = String(detail?.name ?? instanceId.value)
    instanceState.value = String(detail?.state ?? '')
    instanceMemoryMb.value = toNumberOr(detail?.memoryMb)
    if (detail?.nodeId) {
      const node = await api.nodeDetail(String(detail.nodeId)) as { stats?: { diskUsedGb?: number, diskTotalGb?: number } }
      nodeStats.value = node?.stats ?? null
    }
  }
  catch {
    instanceName.value = instanceId.value
  }
}

let listRequest = 0

async function fetchList(path = currentPath.value) {
  const request = ++listRequest
  const id = instanceId.value
  currentPath.value = path
  loading.value = true
  loadError.value = ''
  try {
    const result = await withTimeout(extra.listFiles(id, path, pager.page, pager.size, keyword.value.trim() || undefined), 12_000, '目录读取超时，请检查节点连接后重试') as {
      entries?: Array<Record<string, unknown>>
      total?: number | string
    }
    if (request !== listRequest || id !== instanceId.value) return
    const entries = result?.entries
    rows.value = Array.isArray(entries)
      ? entries.map(entry => ({ ...entry, path: String(entry.path ?? joinPath(path, String(entry.name))) }))
      : []
    pager.total = Number(result?.total ?? rows.value.length) || 0
    clearSelection()
    const lastPage = Math.max(1, Math.ceil(pager.total / pager.size))
    if (pager.page > lastPage) { pager.page = lastPage; await fetchList(path) }
  }
  catch (error) {
    if (request !== listRequest || id !== instanceId.value) return
    loadError.value = errorMessage(error, '目录加载失败，请检查节点状态后重试')
    rows.value = []
    pager.total = 0
    clearSelection()
  }
  finally {
    if (request === listRequest) loading.value = false
  }
}

/** 导航语义：换目录/刷新时页码回到 1。 */
async function load(path = currentPath.value) {
  if (!instanceId.value) {
    loadError.value = '缺少实例 ID'
    return
  }
  pager.page = 1
  await fetchList(path)
}

/** 目录树数据源：只消费目录项。 */
async function treeList(path: string) {
  const result = await extra.listFiles(instanceId.value, path, 1, 500) as {
    entries?: Array<{ name?: string, isDir?: boolean }>
  }
  return (result?.entries ?? []).map(entry => ({
    name: String(entry.name ?? ''),
    isDir: Boolean(entry.isDir),
  }))
}

function enter(row: Record<string, unknown>) {
  const path = String(row.path ?? joinPath(currentPath.value, String(row.name)))
  if (row.isDir) {
    void load(path)
  }
  else {
    openFileFromBrowser(path)
  }
}

function openFileFromBrowser(path: string) {
  editorPath.value = path
  editorDirectory.value = currentPath.value
  editorOpen.value = true
}

async function doMkdir() {
  const name = newName.value.trim()
  if (!name || mkdirBusy.value || !canManage.value) return
  if (name !== relativeFilePath(name)) { toast.error('目录名不能包含 ..、绝对路径或反斜杠'); return }
  mkdirBusy.value = true
  const id = instanceId.value
  try {
    await withTimeout(extra.mkdir(id, joinPath(currentPath.value, name)), 55_000, '创建结果未确认，请刷新目录核对')
    if (id !== instanceId.value) return
    toast.success('目录已创建')
    newName.value = ''
    void fetchList()
  }
  catch (error) { toast.error(errorMessage(error, '创建目录失败')) }
  finally { mkdirBusy.value = false }
}

function promptRename(row: Record<string, unknown>) {
  renameFrom.value = String(row.path ?? joinPath(currentPath.value, String(row.name)))
  renameTo.value = renameFrom.value
  renameOpen.value = true
}

function submitRename(done: () => void) {
  const from = renameFrom.value
  const to = renameTo.value.trim()
  if (!to || to === from) {
    done()
    return
  }
  renameBusy.value = true
  void (async () => {
    try {
      await extra.renameFile(instanceId.value, from, to)
      toast.success('已重命名')
      done()
      void fetchList()
    }
    catch (error) {
      toast.error(errorMessage(error, '重命名失败'))
    }
    finally {
      renameBusy.value = false
    }
  })()
}

function confirmDelete(row: Record<string, unknown>) {
  const target = String(row.path ?? joinPath(currentPath.value, String(row.name)))
  const isDir = Boolean(row.isDir)
  modal.confirm({
    title: isDir ? '删除目录' : '删除文件',
    content: `确认删除${isDir ? '目录' : '文件'}「${target}」吗？`,
    confirmButtonText: '删除',
    onConfirm: async () => {
      try {
        await extra.deleteFile(instanceId.value, target)
        toast.success('已删除')
        void fetchList()
      }
      catch (error) {
        toast.error(errorMessage(error, '删除失败'))
      }
    },
  })
}

function customUpload(options: { file?: File }) {
  const file = options.file
  if (file) {
    uploadFileRef.value = file
    if (!uploadPath.value) {
      uploadPath.value = file.name
    }
  }
  return Promise.resolve()
}

function doUpload(done: () => void) {
  const file = uploadFileRef.value
  if (!file || uploadBusy.value) {
    return
  }
  const target = uploadPath.value.trim() || file.name
  uploadBusy.value = true
  try {
    // 异步任务：立即入列返回，进度见「上传任务」面板；完成后刷新目录
    startUpload(file, joinPath(currentPath.value, target), (ok) => {
      if (ok) {
        toast.success(`${file.name} 上传完成`)
        void fetchList()
      }
    })
    toast.success('已加入上传队列，可在下方任务面板查看进度')
    done()
    uploadFileRef.value = null
    uploadItems.value = []
    uploadPath.value = ''
  }
  catch (error) {
    toast.error(errorMessage(error, '上传失败'))
  }
  finally {
    uploadBusy.value = false
  }
}

async function openFtp() {
  ftpBusy.value = true
  try {
    const result = await extra.ftpOpen(instanceId.value, ftpTtl.value) as Record<string, unknown>
    ftpInfo.value = result
    ftpOpen.value = true
    toast.success('临时 SFTP 已开通')
  }
  catch (error) {
    toast.error(errorMessage(error, '开通 SFTP 失败（节点需启用 ftp 能力；网关模式下还需网关端口放行）'))
  }
  finally {
    ftpBusy.value = false
  }
}

/** 网关模式下 host 可能为空（面板未配置广告地址），回退到当前站点主机名。 */
const ftpDisplayHost = computed(() => {
  const info = ftpInfo.value
  if (info && info.host) {
    return String(info.host)
  }
  return window.location.hostname || '面板地址'
})

async function closeFtp() {
  try {
    await extra.ftpClose(instanceId.value)
    ftpInfo.value = null
    ftpOpen.value = false
    toast.success('SFTP 已关闭')
  }
  catch (error) {
    toast.error(errorMessage(error, '关闭 SFTP 失败'))
  }
}

async function copyText(text: string, okMessage: string) {
  try {
    await navigator.clipboard.writeText(text)
    toast.success(okMessage)
  }
  catch {
    toast.warning('复制失败，请手动复制')
  }
}

/** 带 @ 的临时口令经 URL 编码后再拼进 sftp:// 链接，避免破坏 URI 结构。 */
const sftpUri = computed(() => {
  const info = ftpInfo.value
  if (!info) {
    return ''
  }
  return `sftp://${encodeURIComponent(String(info.user))}:${encodeURIComponent(String(info.password))}@${ftpDisplayHost.value}:${info.port}/`
})

/** 唤起系统里注册了 sftp:// 协议的客户端（WinSCP/Cyberduck 等；FileZilla 不支持该协议）。 */
function openSftpClient() {
  if (!sftpUri.value) {
    return
  }
  window.location.href = sftpUri.value
}

async function copyFtp() {
  const info = ftpInfo.value
  if (!info) {
    return
  }
  const text = [
    `sftp://${info.user}@${ftpDisplayHost.value}:${info.port}`,
    `user=${info.user}`,
    `password=${info.password}`,
  ].join('\n')
  await copyText(text, 'SFTP 连接信息已复制')
}

async function copyWinScpCommand() {
  const info = ftpInfo.value
  if (!info) {
    return
  }
  await copyText(`winscp.exe sftp://${encodeURIComponent(String(info.user))}:${encodeURIComponent(String(info.password))}@${ftpDisplayHost.value}:${info.port}/`, 'WinSCP 命令已复制')
}

async function copyPsftpCommand() {
  const info = ftpInfo.value
  if (!info) {
    return
  }
  await copyText(`psftp -P ${info.port} -pw ${info.password} ${info.user}@${ftpDisplayHost.value}`, 'psftp 命令已复制')
}

async function doZip(row: Record<string, unknown>) {
  const path = String(row.path ?? joinPath(currentPath.value, String(row.name)))
  try {
    const result = await extra.zipFile(instanceId.value, path, `${path}.zip`) as { zip?: string }
    toast.success(`已压缩：${result?.zip || path + '.zip'}`)
    void fetchList()
  }
  catch (error) {
    toast.error(errorMessage(error, '压缩失败（节点需支持 file.zip）'))
  }
}

async function doUnzip(row: Record<string, unknown>) {
  const path = String(row.path ?? joinPath(currentPath.value, String(row.name)))
  const dest = path.replace(/\.zip$/i, '')
  try {
    const result = await extra.unzipFile(instanceId.value, path, dest) as { files?: number }
    toast.success(`已解压 ${result?.files ?? 0} 个文件 → ${dest}`)
    void fetchList()
  }
  catch (error) {
    toast.error(errorMessage(error, '解压失败（节点需支持 file.unzip）'))
  }
}

const tableRef = ref<{ table?: { resetRowSelection?: () => void } } | null>(null)
const selectedRows = ref<Record<string, unknown>[]>([])

function onSelectionChange(sel: Record<string, unknown>[]) {
  selectedRows.value = sel
}

function clearSelection() {
  selectedRows.value = []
  tableRef.value?.table?.resetRowSelection?.()
}

interface CtxMenuItem {
  label: string
  icon?: string
  variant?: 'default' | 'destructive'
  disabled?: boolean
  handle?: () => void
}

function rowPath(row: Record<string, unknown>) {
  return String(row.path ?? joinPath(currentPath.value, String(row.name)))
}

function downloadRow(row: Record<string, unknown>) {
  if (row.isDir || download.active.value) return
    const path = rowPath(row)
  const id = instanceId.value
  void download.start(String(row.name), (offset, length) => extra.readFileChunk(id, path, offset, length)).catch(error => toast.error(errorMessage(error, '下载失败')))
}

async function copyRowPath(row: Record<string, unknown>) {
  const path = rowPath(row)
  try {
    await navigator.clipboard.writeText(path)
    toast.success('路径已复制')
  }
  catch {
    toast.warning(path)
  }
}

function ctxGroups(row: Record<string, unknown>): CtxMenuItem[][] {
  const isDir = Boolean(row.isDir)
  const isZip = String(row.name ?? '').toLowerCase().endsWith('.zip')
  return [
    [
      {
        label: isDir ? '进入目录' : (canManage.value ? '打开/编辑' : '下载'),
        icon: isDir ? 'i-ri:folder-open-line' : 'i-ri:file-edit-line',
        handle: () => (!canManage.value && !isDir ? downloadRow(row) : enter(row)),
      },
      {
        label: '下载',
        icon: 'i-ri:download-2-line',
        disabled: isDir,
        handle: () => downloadRow(row),
      },
      {
        label: '复制路径',
        icon: 'i-ri:link',
        handle: () => void copyRowPath(row),
      },
    ],
    [
      {
        label: '重命名/移动',
        icon: 'i-ri:edit-2-line',
        disabled: !canManage.value,
        handle: () => promptRename(row),
      },
      {
        label: isDir ? '压缩目录为 zip' : '压缩为 zip',
        icon: 'i-ri:file-zip-line',
        disabled: !canManage.value,
        handle: () => void doZip(row),
      },
      {
        label: '解压到同名目录',
        icon: 'i-ri:file-reduce-line',
        disabled: !canManage.value || !isZip,
        handle: () => void doUnzip(row),
      },
    ],
    [
      {
        label: '删除',
        icon: 'i-ri:delete-bin-line',
        variant: 'destructive',
        disabled: !canManage.value,
        handle: () => confirmDelete(row),
      },
    ],
  ]
}

async function batchDelete() {
  if (!selectedRows.value.length || deleteBusy.value || !canManage.value) return
  const id = instanceId.value
  const targets = selectedRows.value.map(rowPath)
  modal.confirm({
    title: '批量删除', content: `确认删除选中的 ${targets.length} 项？目录将递归删除，此操作不可恢复。`, confirmButtonText: '删除',
    onConfirm: async () => {
      if (deleteBusy.value) return
      deleteBusy.value = true
      let ok = 0
      const failed: string[] = []
      try {
        for (const target of targets) {
          try { await withTimeout(extra.deleteFile(id, target), 55_000, '删除结果未确认'); ok++ }
          catch (cause) { failed.push(`${target}：${errorMessage(cause, '删除失败')}`) }
        }
        if (id !== instanceId.value) return
        if (failed.length) toast.warning(`已删除 ${ok}/${targets.length} 项。${failed.slice(0, 3).join('；')}`)
        else toast.success(`已删除 ${ok} 项`)
        clearSelection()
        await fetchList()
      }
      finally { deleteBusy.value = false }
    },
  })
}

function promptBatchMove() {
  if (!selectedRows.value.length) {
    return
  }
  moveTarget.value = ''
  moveOpen.value = true
}

/** 批量移动：对每个选中项做 rename 到目标目录（目标为实例根内相对路径，空 = 根目录）。 */
function submitBatchMove(done: () => void) {
  const raw = moveTarget.value.trim().replace(/^\/+|\/+$/g, '')
  if (raw.split('/').includes('..')) {
    toast.error('目标目录不能包含 ..')
    return
  }
  const targets = selectedRows.value.map(row => ({
    from: rowPath(row),
    name: String(row.name),
  }))
  if (!targets.length) {
    done()
    return
  }
  moveBusy.value = true
  void (async () => {
    let ok = 0
    const failed: string[] = []
    for (const target of targets) {
      const to = raw ? `${raw}/${target.name}` : target.name
      if (to === target.from) {
        ok++
        continue
      }
      try {
        await extra.renameFile(instanceId.value, target.from, to)
        ok++
      }
      catch {
        failed.push(target.name)
      }
    }
    moveBusy.value = false
    if (failed.length) {
      toast.warning(`已移动 ${ok}/${targets.length}，失败：${failed.slice(0, 5).join('、')}${failed.length > 5 ? ' 等' : ''}`)
    }
    else {
      toast.success(`已移动 ${ok} 项到 ${raw ? `/${raw}` : '/（根目录）'}`)
    }
    done()
    clearSelection()
    void fetchList()
  })()
}

function promptBatchZip() {
  if (!selectedRows.value.length) {
    return
  }
  zipMultiName.value = `批量压缩_${selectedRows.value.length}项.zip`
  zipMultiOpen.value = true
}

/** 批量压缩：选中项打包为当前目录下的一个 zip（节点 file.zip paths 通道）。 */
function submitBatchZip(done: () => void) {
  const raw = zipMultiName.value.trim()
  if (!raw) {
    toast.error('请填写 zip 文件名')
    return
  }
  const name = raw.endsWith('.zip') ? raw : `${raw}.zip`
  const dest = joinPath(currentPath.value, name)
  const paths = selectedRows.value.map(rowPath)
  if (paths.includes(dest)) {
    toast.error('目标 zip 与选中项重名，请换个文件名')
    return
  }
  zipMultiBusy.value = true
  void (async () => {
    try {
      const result = await extra.zipFiles(instanceId.value, paths, dest) as { zip?: string }
      toast.success(`已压缩 ${paths.length} 项：${result?.zip || name}`)
      done()
      zipMultiOpen.value = false
      void fetchList()
    }
    catch (error) {
      toast.error(errorMessage(error, '批量压缩失败（节点需升级到 0.3.0 并重启）'))
    }
    finally {
      zipMultiBusy.value = false
    }
  })()
}

function breadcrumbs() {
  const parts = currentPath.value ? currentPath.value.split('/') : []
  const items = [{ label: '根目录', path: '' }]
  let acc = ''
  for (const part of parts) {
    acc = acc ? `${acc}/${part}` : part
    items.push({ label: part, path: acc })
  }
  return items
}

watch(instanceId, () => {
  listRequest++
  download.cancel()
  rows.value = []
  nodeStats.value = null
  instanceName.value = ''
  uploadOpen.value = false
  renameOpen.value = false
  moveOpen.value = false
  ftpOpen.value = false
  ftpInfo.value = null
  keyword.value = ''
  void loadInstanceMeta()
  void load(relativeFilePath(route.query.dir))
}, { immediate: true })
onBeforeUnmount(() => { listRequest++; download.cancel() })
</script>

<template>
  <FaPageHeader
    :title="`文件 / SFTP：${instanceName || instanceId}`"
    description="面板代理的实例数据通道 + 可选临时 SFTP（随机口令，TTL 自动关闭）"
    class="mb-0"
  >
    <FaButton variant="outline" @click="router.push(detailPath)">
      <FaIcon name="i-ri:terminal-line" />
      返回终端
    </FaButton>
    <FaButton v-if="canManage" variant="outline" @click="uploadOpen = true">
      <FaIcon name="i-ri:upload-line" />
      上传
    </FaButton>
    <FaButton v-if="canUse" variant="outline" :loading="ftpBusy" @click="openFtp">
      <FaIcon name="i-ri:key-2-line" />
      开通 SFTP
    </FaButton>
    <FaButton variant="outline" @click="() => load(currentPath)">
      <FaIcon name="i-ri:refresh-line" />
      刷新
    </FaButton>
  </FaPageHeader>

  <FaPageMain class="mt-4">
    <div v-if="uploadTaskItems.length" class="mb-4">
      <UploadTasksPanel
        :tasks="uploadTaskItems"
        :hashing="uploadHashing"
        :can-cancel="canManage"
        @cancel="taskId => void cancelUploadTask(taskId)"
      />
    </div>
    <FaAlert
      v-if="!canManage"
      title="当前账号为只读模式"
      class="mb-4"
    >
  <template #description>
      缺少 plugin:mcpanel:manage 权限：编辑 / 压缩 / 移动 / 删除 / 上传入口不可用（部分以 toast 提示拦截）。请在「角色管理」中为当前角色勾选该权限后刷新页面。
  </template>
    </FaAlert>
    <FaAlert v-if="loadError" variant="destructive" title="无法加载文件列表" class="mb-4">
  <template #description>
      {{ loadError }}
  </template>
    </FaAlert>
    <FaAlert v-else-if="rows.length === 0 && !loading" class="mb-4" title="目录为空">
  <template #description>
      实例数据根目录下还没有文件。若刚创建且勾选了自动下载核心，请稍等节点完成 server.jar 下载；
      也可在终端启动一次，或手动上传服务端文件。大文件建议用「开通 SFTP」。
  </template>
    </FaAlert>

    <div class="mcp-file-location"><FaButton size="sm" variant="outline" :aria-expanded="treeOpen" @click="treeOpen = !treeOpen"><FaIcon name="i-ri:side-bar-line" />目录浏览</FaButton><span class="mcp-muted">{{ pager.total }} 项 · 文件仅在点击保存后写入</span></div>
    <div v-if="download.active.value" class="mcp-download-status"><FaIcon name="i-ri:download-line" /><span>{{ download.fileName.value }}</span><FaProgress :model-value="download.progress.value" /><span>{{ download.progress.value }}%</span><FaButton size="sm" variant="outline" @click="download.cancel">取消</FaButton></div>
    <div class="mcp-files-split" :class="{ 'is-side-open': treeOpen }">
      <div v-if="treeOpen" class="mcp-files-side">
        <DirTreePanel
          :key="instanceId"
          :model-value="currentPath"
          :list="treeList"
          @update:model-value="path => load(path)"
        />
      </div>

      <!-- 右栏：磁盘监控条 + 当前目录表格 -->
      <div class="mcp-files-main">
        <FaCard title="远程主机文件" :description="`实例数据根：/${currentPath || ''}（路径钉死在实例卷内）`">
          <div class="mcp-disk-strip">
            <FaTag :variant="instanceState === 'running' ? 'default' : 'secondary'">
              {{ STATE_LABEL[instanceState] || instanceState || '未知状态' }}
            </FaTag>
            <span>实例内存 {{ instanceMemoryMb ? `${instanceMemoryMb} MB` : '-' }}</span>
            <div class="mcp-disk-bar">
              <span>节点磁盘</span>
              <FaProgress :model-value="diskPercent" class="min-w-0 flex-1" />
              <span>{{ nodeStats ? `${formatGib(nodeStats.diskUsedGb)} / ${formatGib(nodeStats.diskTotalGb)}（${diskPercent}%）` : '-' }}</span>
            </div>
          </div>

          <div class="mcp-file-toolbar">
            <div class="mcp-file-path">
              <template v-for="(crumb, index) in breadcrumbs()" :key="crumb.path">
                <a class="mcp-link" @click.prevent="load(crumb.path)">{{ crumb.label }}</a>
                <span v-if="index < breadcrumbs().length - 1" class="mcp-muted">/</span>
              </template>
            </div>
            <div class="mcp-spacer mcp-row">
              <FaInput v-model="keyword" placeholder="按名称过滤（回车）" clearable class="mcp-search-input" @keydown.enter="() => load(currentPath)" />
              <FaInput v-model="newName" placeholder="新目录名" class="mcp-search-input" @keydown.enter="doMkdir" />
              <FaButton v-if="canManage" size="sm" variant="outline" :disabled="!selectedRows.length" @click="promptBatchZip">
                批量压缩{{ selectedRows.length ? `（${selectedRows.length}）` : '' }}
              </FaButton>
              <FaButton v-if="canManage" size="sm" variant="outline" :disabled="!selectedRows.length" @click="promptBatchMove">
                批量移动{{ selectedRows.length ? `（${selectedRows.length}）` : '' }}
              </FaButton>
              <FaButton v-if="canManage" size="sm" variant="destructive" :loading="deleteBusy" :disabled="!selectedRows.length" @click="batchDelete">
                批量删除{{ selectedRows.length ? `（${selectedRows.length}）` : '' }}
              </FaButton>
              <FaButton v-if="canManage" size="sm" variant="outline" :loading="mkdirBusy" :disabled="!newName.trim()" @click="doMkdir">
                新建目录
              </FaButton>
              <FaButton size="sm" variant="outline" @click="() => fetchList()">
                刷新
              </FaButton>
            </div>
          </div>

          <FaResponsiveTable
            ref="tableRef"
            v-loading="loading"
            :columns="columns"
            :data="rows"
            row-key="path"
            selectable
            multiple
            table-root-class="rounded-lg overflow-hidden"
            table-class="min-w-[820px]"
            border
            stripe
            empty-text="空目录"
            @selection-change="onSelectionChange"
          >
            <template #cell-name="{ row }">
              <FaContextMenu :items="ctxGroups(row.original)">
                <a class="mcp-link" @click.prevent="enter(row.original)">
                  <FaIcon :name="row.original.isDir ? 'i-ri:folder-line' : 'i-ri:file-line'" class="mr-1" />
                  {{ row.original.name }}
                </a>
              </FaContextMenu>
            </template>
            <template #cell-type="{ row }">
              {{ row.original.isDir ? '目录' : (row.original.mode === 'symlink' ? '链接' : '文件') }}
            </template>
            <template #cell-size="{ row }">
              {{ row.original.isDir ? '-' : formatSize(row.original.size) }}
            </template>
            <template #cell-modTime="{ row }">
              {{ row.original.modTime ? formatDateTime(Number(row.original.modTime)) : '-' }}
            </template>
            <template #cell-operation="{ row }">
              <div class="mcp-op-cell">
                <FaTooltip v-if="canManage && !row.original.isDir" text="编辑">
                  <FaButton size="icon-sm" variant="outline" @click="openFileFromBrowser(String(row.original.path ?? row.original.name))">
                    <FaIcon name="i-ri:file-edit-line" />
                  </FaButton>
                </FaTooltip>
                <FaTooltip v-if="!row.original.isDir" text="下载">
                  <FaButton size="icon-sm" variant="outline" :disabled="download.active.value" @click="downloadRow(row.original)"><FaIcon name="i-ri:download-2-line" /></FaButton>
                </FaTooltip>
                <FaTooltip v-if="canManage" text="重命名 / 移动">
                  <FaButton size="icon-sm" variant="outline" @click="promptRename(row.original)">
                    <FaIcon name="i-ri:edit-2-line" />
                  </FaButton>
                </FaTooltip>
                <FaTooltip v-if="canManage && String(row.original.name).toLowerCase().endsWith('.zip')" text="解压">
                  <FaButton size="icon-sm" variant="outline" @click="doUnzip(row.original)">
                    <FaIcon name="i-ri:file-reduce-line" />
                  </FaButton>
                </FaTooltip>
                <FaTooltip v-if="canManage" :text="row.original.isDir ? '压缩目录为 zip' : '压缩为 zip'">
                  <FaButton size="icon-sm" variant="outline" @click="doZip(row.original)">
                    <FaIcon name="i-ri:file-zip-line" />
                  </FaButton>
                </FaTooltip>
                <FaTooltip v-if="canManage" text="删除">
                  <FaButton size="icon-sm" variant="destructive" @click="confirmDelete(row.original)">
                    <FaIcon name="i-ri:delete-bin-line" />
                  </FaButton>
                </FaTooltip>
              </div>
            </template>
            <template #card="{ row }">
              <FaCard class="w-full">
                <div class="flex flex-col gap-3">
                  <div class="flex items-center justify-between gap-2">
                    <button type="button" class="mcp-link flex min-w-0 items-center gap-1" @click.prevent="enter(row)">
                      <FaIcon :name="row.isDir ? 'i-ri:folder-line' : 'i-ri:file-line'" class="shrink-0" />
                      <span class="min-w-0 break-all text-left text-base font-semibold">{{ row.name }}</span>
                    </button>
                    <FaTag :variant="row.isDir ? 'secondary' : 'outline'">
                      {{ row.isDir ? '目录' : (row.mode === 'symlink' ? '链接' : '文件') }}
                    </FaTag>
                  </div>
                  <div class="flex flex-col gap-1 text-sm">
                    <div class="flex gap-2">
                      <span class="shrink-0 text-secondary-foreground/60">大小</span>
                      <span class="break-all">{{ row.isDir ? '-' : formatSize(row.size) }}</span>
                    </div>
                    <div class="flex gap-2">
                      <span class="shrink-0 text-secondary-foreground/60">修改时间</span>
                      <span class="break-all">{{ row.modTime ? formatDateTime(Number(row.modTime)) : '-' }}</span>
                    </div>
                  </div>
                  <div class="flex flex-wrap gap-2 border-t pt-3">
                    <FaButton v-if="canManage && !row.isDir" size="sm" variant="outline" @click="openFileFromBrowser(String(row.path ?? row.name))">
                      编辑
                    </FaButton>
                    <FaButton v-if="!row.isDir" size="sm" variant="outline" :disabled="download.active.value" @click="downloadRow(row)">
                      下载
                    </FaButton>
                    <FaButton v-if="canManage" size="sm" variant="outline" @click="promptRename(row)">
                      重命名
                    </FaButton>
                    <FaButton v-if="canManage && String(row.name).toLowerCase().endsWith('.zip')" size="sm" variant="outline" @click="doUnzip(row)">
                      解压
                    </FaButton>
                    <FaButton v-if="canManage" size="sm" variant="outline" @click="doZip(row)">
                      压缩
                    </FaButton>
                    <FaButton v-if="canManage" size="sm" variant="destructive" @click="confirmDelete(row)">
                      删除
                    </FaButton>
                  </div>
                </div>
              </FaCard>
            </template>
          </FaResponsiveTable>
          <FaPagination
            v-model:page="pager.page"
            v-model:size="pager.size"
            :sizes="[10, 20, 50, 100]"
            :total="pager.total"
            class="mt-3"
            @page-change="() => fetchList()"
            @size-change="() => { pager.page = 1; fetchList() }"
          />
        </FaCard>
      </div>
    </div>

    <FaCard title="SFTP 临时通道" description="随机账号/密码、根目录钉死实例数据内、到期自动关闭；启用网关后经面板单端口中转，节点无需对外可达。" class="mcp-page-gap">
      <div class="mcp-toolbar-row mb-3">
        <span class="mcp-status-meta">有效期（分钟）</span>
        <FaSelect
          v-model="ftpTtl"
          :options="[{ label: '30 分钟', value: 30 }, { label: '60 分钟', value: 60 }, { label: '120 分钟', value: 120 }]"
          class="mcp-search-select"
        />
        <div class="mcp-spacer mcp-row">
          <FaButton v-if="canUse" size="sm" :loading="ftpBusy" @click="openFtp">
            开通 / 重置
          </FaButton>
          <FaButton v-if="ftpInfo && canUse" size="sm" variant="outline" @click="closeFtp">
            关闭
          </FaButton>
        </div>
      </div>
      <div v-if="ftpInfo" class="mcp-ftp-box">
        <div>协议：SFTP（仅文件子系统）</div>
        <div v-if="ftpInfo.gateway">通道：经面板 SFTP 网关中转（节点无需对外可达）</div>
        <div>主机：{{ ftpDisplayHost }}</div>
        <div>端口：{{ ftpInfo.port }}</div>
        <div>用户：{{ ftpInfo.user }}</div>
        <div>密码：{{ ftpInfo.password }}</div>
        <div>到期：{{ formatDateTime(ftpInfo.expiresAt as number) }}</div>
        <div class="mcp-row mt-2">
          <FaButton size="sm" @click="openSftpClient">
            一键打开客户端
          </FaButton>
          <FaButton size="sm" variant="outline" @click="copyWinScpCommand">
            复制 WinSCP 命令
          </FaButton>
          <FaButton size="sm" variant="outline" @click="copyPsftpCommand">
            复制 psftp 命令
          </FaButton>
        </div>
        <FaButton size="sm" variant="outline" class="mt-2" @click="copyFtp">
          复制连接信息
        </FaButton>
        <div class="mcp-empty-hint mt-1">
          「一键打开」依赖本机已注册 sftp:// 协议的客户端（WinSCP 安装时勾选协议注册；FileZilla 不支持，请手动填入上方凭据）。命令需本机装有对应工具，含临时口令，请勿外传。
        </div>
      </div>
      <div v-else class="mcp-empty-hint">
        尚未开通。大文件传输、批量备份建议使用 SFTP；网页端适合小文件与配置编辑。
      </div>
    </FaCard>

    <FileEditorModal
      v-model="editorOpen"
      title="实例文件编辑"
      :load="editorAccess.load"
      :save="editorAccess.save"
      :list-dir="async (path, filter) => {
        const result = await extra.listFiles(instanceId, path, 1, 200, filter) as { entries?: Array<{ name: string, isDir: boolean, size?: number }>, total?: number | string }
        return { entries: result.entries ?? [], total: result.total === undefined ? undefined : Number(result.total) }
      }"
      :can-save="canManage"
      :initial-path="editorPath"
      :initial-directory="editorDirectory"
      @saved="() => fetchList()"
    />

    <FaModal
      v-model="uploadOpen"
      title="上传文件"
      :show-cancel-button="true"
      confirm-button-text="上传"
      :confirm-button-loading="uploadBusy"
      class="max-w-[min(32rem,calc(100vw-2rem))]"
      :before-close="(action: 'confirm' | 'cancel' | 'close', done: () => void) => action === 'confirm' ? doUpload(done) : done()"
    >
      <div class="mcp-form">
        <label class="mcp-form-item">
          <span class="mcp-form-label">保存到：/{{ currentPath || '根目录' }} 下的文件名</span>
          <FaInput v-model="uploadPath" :placeholder="uploadItems[0]?.name ?? 'server.jar'" class="mcp-w-full" />
        </label>
        <label class="mcp-form-item">
          <span class="mcp-form-label">选择文件（≤ 2GB；异步分片上传，完成后自动出现在目录）</span>
          <FaFileUpload v-model="uploadItems" :max="1" :http-request="customUpload" description="拖放或点击选择" />
        </label>
      </div>
    </FaModal>

    <FaModal
      v-model="renameOpen"
      title="重命名 / 移动"
      :show-cancel-button="true"
      confirm-button-text="保存"
      :confirm-button-loading="renameBusy"
      class="max-w-[min(32rem,calc(100vw-2rem))]"
      :before-close="(action: 'confirm' | 'cancel' | 'close', done: () => void) => action === 'confirm' ? submitRename(done) : done()"
    >
      <div class="mcp-form">
        <label class="mcp-form-item">
          <span class="mcp-form-label">当前路径</span>
          <FaInput :model-value="renameFrom" disabled class="mcp-w-full mcp-mono" />
        </label>
        <label class="mcp-form-item">
          <span class="mcp-form-label">目标路径</span>
          <FaInput v-model="renameTo" class="mcp-w-full mcp-mono" />
        </label>
      </div>
    </FaModal>

    <FaModal
      v-model="moveOpen"
      :title="`批量移动（${selectedRows.length} 项）`"
      :show-cancel-button="true"
      confirm-button-text="移动"
      :confirm-button-loading="moveBusy"
      class="max-w-[min(32rem,calc(100vw-2rem))]"
      :before-close="(action: 'confirm' | 'cancel' | 'close', done: () => void) => action === 'confirm' ? submitBatchMove(done) : done()"
    >
      <div class="mcp-form">
        <label class="mcp-form-item">
          <span class="mcp-form-label">移动项</span>
          <div class="mcp-mono text-sm opacity-80">
            {{ selectedRows.map(row => row.name).join('、') }}
          </div>
        </label>
        <label class="mcp-form-item">
          <span class="mcp-form-label">目标目录（实例根内相对路径，留空 = 根目录；不存在会报错）</span>
          <FaInput v-model="moveTarget" placeholder="如 world/datapacks" class="mcp-w-full mcp-mono" />
        </label>
      </div>
    </FaModal>

    <FaModal
      v-model="zipMultiOpen"
      :title="`批量压缩（${selectedRows.length} 项）`"
      :show-cancel-button="true"
      confirm-button-text="压缩"
      :confirm-button-loading="zipMultiBusy"
      class="max-w-[min(32rem,calc(100vw-2rem))]"
      :before-close="(action: 'confirm' | 'cancel' | 'close', done: () => void) => action === 'confirm' ? submitBatchZip(done) : done()"
    >
      <div class="mcp-form">
        <label class="mcp-form-item">
          <span class="mcp-form-label">压缩项（目录会递归打包）</span>
          <div class="mcp-mono text-sm opacity-80">
            {{ selectedRows.map(row => row.name).join('、') }}
          </div>
        </label>
        <label class="mcp-form-item">
          <span class="mcp-form-label">zip 文件名（生成在当前目录）</span>
          <FaInput v-model="zipMultiName" class="mcp-w-full mcp-mono" />
        </label>
      </div>
    </FaModal>

  </FaPageMain>
</template>
