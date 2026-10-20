<script setup lang="ts">
import { YdLoadingOverlay, YdTablePageLayout } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { TableColumn, ActionMenuItem } from '@yudream/components'
import { YdAlert, YdButton, YdIcon, YdInput, YdModal, YdPageMain, YdPagination, YdProgress, YdTag, useYdModal, useYdToast, YdResponsiveEnhancedTable } from '@yudream/components'
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { createNodeOpsApi } from '../../api/node-ops-api.ts'
import { createMcPanelApi } from '../../api/mcpanel-api.ts'
import FileBrowserPanel from '../../components/FileBrowserPanel.vue'
import FileEditorModal from '../../components/FileEditorModal.vue'
import { useChunkedDownload } from '../../composables/useChunkedDownload.ts'
import { MCPANEL_PERMISSION, accountHasPermission } from '../../composables/permissions.ts'
import { errorMessage, formatDateTime, formatSize } from '../../composables/utils.ts'
import { withTimeout } from '../../utils/fileContent.ts'
import { relativeFilePath, createTextFileAccess } from '../../utils/textFileAccess.ts'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = createNodeOpsApi(props.sdk)
const panel = createMcPanelApi(props.sdk)
const route = useRoute()
const router = useRouter()
const toast = useYdToast()
const modal = useYdModal()
const nodeId = computed(() => String(route.params.id ?? ''))
const canManage = computed(() => accountHasPermission(props.sdk.account, MCPANEL_PERMISSION.manage))
const name = ref('')
const root = ref('')
const directory = ref('')
const keyword = ref('')
const rows = ref<Record<string, unknown>[]>([])
const loading = ref(false)
const error = ref('')
const sideOpen = ref(false)
const pager = reactive({ page: 1, size: 50, total: 0 })
const download = useChunkedDownload()
const editorOpen = ref(false)
const editorPath = ref('')
const editorDirectory = ref('')
const editorAccess = computed(() => createTextFileAccess({
  readChunk: (path, offset, length) => api.nodeFileReadChunk(nodeId.value, root.value, path, offset, length),
  write: (path, content, charset) => api.nodeFileWrite(nodeId.value, root.value, path, content, charset),
}))
let generation = 0
let request = 0
const columns: TableColumn<Record<string, unknown>>[] = [
  { accessorKey: 'name', header: '名称', minWidth: 240, fixed: 'left' },
  { id: 'type', header: '类型', width: 90 },
  { accessorKey: 'size', header: '大小', width: 100 },
  { accessorKey: 'modTime', header: '修改时间', width: 170 },
]
const crumbs = computed(() => {
  const result = [{ label: '根目录', path: '' }]
  let path = ''
  for (const part of directory.value.split('/').filter(Boolean)) {
    path = path ? `${path}/${part}` : part
    result.push({ label: part, path })
  }
  return result
})
function join(path: string, file: string) { return path ? `${path}/${file}` : file }
function filePath(row: Record<string, unknown>) { return join(directory.value, String(row.name)) }

async function load(path = directory.value) {
  const seq = ++request
  const epoch = generation
  const id = nodeId.value
  directory.value = path
  loading.value = true
  error.value = ''
  try {
    const result = await withTimeout(api.nodeFileList(id, root.value, path, pager.page, pager.size, keyword.value.trim() || undefined), 12_000, '目录读取超时，请检查节点连接后重试') as {
      entries?: Record<string, unknown>[], total?: number | string, root?: string
    }
    if (seq !== request || epoch !== generation) return
    root.value = result.root ?? root.value
    rows.value = result.entries ?? []
    pager.total = Number(result.total ?? rows.value.length)
    const last = Math.max(1, Math.ceil(pager.total / pager.size))
    if (pager.page > last) { pager.page = last; await load(path) }
  }
  catch (cause) {
    if (seq !== request || epoch !== generation) return
    rows.value = []
    pager.total = 0
    error.value = errorMessage(cause, '目录加载失败')
  }
  finally { if (seq === request) loading.value = false }
}
function navigate(path: string) { pager.page = 1; keyword.value = ''; void load(path) }
function search() { pager.page = 1; void load() }
function edit(path: string) {
  editorPath.value = path
  editorDirectory.value = directory.value
  editorOpen.value = true
}
function enter(row: Record<string, unknown>) { row.isDir ? navigate(filePath(row)) : edit(filePath(row)) }
async function listSide(path: string, filter?: string) {
  const result = await withTimeout(api.nodeFileList(nodeId.value, root.value, path, 1, 200, filter), 12_000, '目录加载超时') as {
    entries?: Array<{ name: string, isDir: boolean, size?: number }>, total?: number | string
  }
  return { entries: result.entries ?? [], total: Number(result.total ?? result.entries?.length ?? 0) }
}
async function downloadRow(row: Record<string, unknown>) {
  if (row.isDir || download.active.value) return
  const id = nodeId.value
  const scopeRoot = root.value
  const path = filePath(row)
  try { await download.start(String(row.name), (offset, length) => api.nodeFileReadChunk(id, scopeRoot, path, offset, length)) }
  catch (cause) { toast.error(errorMessage(cause, '下载失败')) }
}

const formOpen = ref(false)
const formKind = ref<'mkdir' | 'rename'>('mkdir')
const fromPath = ref('')
const target = ref('')
const busy = ref(false)
const formError = ref('')
function openForm(row?: Record<string, unknown>) {
  formKind.value = row ? 'rename' : 'mkdir'
  fromPath.value = row ? filePath(row) : ''
  target.value = row ? fromPath.value : ''
  formError.value = ''
  formOpen.value = true
}
async function submit(done: () => void) {
  if (busy.value || !canManage.value) return
  const value = target.value.trim()
  if (!value || relativeFilePath(value) !== value) { formError.value = '请填写有效的相对路径，不能包含 .. 或反斜杠'; return }
  const id = nodeId.value
  const selectedRoot = root.value
  const epoch = generation
  const path = formKind.value === 'mkdir' ? join(directory.value, value) : value
  busy.value = true
  formError.value = ''
  try {
    await withTimeout(formKind.value === 'mkdir' ? api.nodeFileMkdir(id, selectedRoot, path) : api.nodeFileRename(id, selectedRoot, fromPath.value, path), 40_000, '操作结果未确认，请刷新目录核对，不要重复提交')
    if (epoch !== generation) return
    done(); toast.success(formKind.value === 'mkdir' ? '目录已创建' : '文件已重命名'); await load()
  }
  catch (cause) { if (epoch === generation) formError.value = errorMessage(cause, '操作失败') }
  finally { if (epoch === generation) busy.value = false }
}
function remove(row: Record<string, unknown>) {
  if (!canManage.value || busy.value) return
  const id = nodeId.value, selectedRoot = root.value, path = filePath(row), epoch = generation
  modal.confirm({ title: `删除${row.isDir ? '目录' : '文件'}`, content: `确认删除「${path}」？${row.isDir ? '目录内容将一并删除。' : ''}此操作不可恢复。`, confirmButtonText: '删除', onConfirm: async () => {
    if (busy.value) return
    busy.value = true
    try {
      await withTimeout(api.nodeFileDelete(id, selectedRoot, path), 40_000, '删除结果未确认，请刷新目录核对')
      if (epoch === generation) { toast.success('已删除'); await load() }
    }
    catch (cause) { if (epoch === generation) toast.error(errorMessage(cause, '删除失败')) }
    finally { if (epoch === generation) busy.value = false }
  } })
}

const ftpOpen = ref(false)
const ftpBusy = ref(false)
const ftp = ref<Record<string, unknown> | null>(null)
async function enableFtp() {
  if (ftpBusy.value || !canManage.value) return
  ftpBusy.value = true
  const epoch = generation
  try {
    const result = await withTimeout(api.nodeFtpOpen(nodeId.value, root.value, 60), 70_000, 'SFTP 开通结果未确认，请检查节点状态') as Record<string, unknown>
    if (epoch === generation) { ftp.value = result; ftpOpen.value = true }
  }
  catch (cause) { if (epoch === generation) toast.error(errorMessage(cause, 'SFTP 开通失败')) }
  finally { if (epoch === generation) ftpBusy.value = false }
}
async function disableFtp() {
  if (ftpBusy.value || !ftp.value) return
  ftpBusy.value = true
  const epoch = generation
  try {
    await withTimeout(api.nodeFtpClose(nodeId.value, String(ftp.value.root ?? root.value)), 40_000, '关闭结果未确认，请检查节点状态')
    if (epoch === generation) { ftp.value = null; ftpOpen.value = false; toast.success('SFTP 会话已关闭') }
  }
  catch (cause) { if (epoch === generation) toast.error(errorMessage(cause, '关闭失败')) }
  finally { if (epoch === generation) ftpBusy.value = false }
}
watch(nodeId, async (id) => {
  generation++; request++; download.cancel()
  root.value = typeof route.query.root === 'string' ? route.query.root : ''
  directory.value = relativeFilePath(route.query.dir)
  pager.page = 1; keyword.value = ''; rows.value = []; name.value = ''
  ftp.value = null; ftpOpen.value = false; formOpen.value = false; busy.value = false; ftpBusy.value = false
  void load()
  try { const result = await panel.nodeDetail(id); if (id === nodeId.value) name.value = result.name }
  catch { /* 文件接口独立显示连接错误。 */ }
}, { immediate: true })
onBeforeUnmount(() => { generation++; request++; download.cancel() })

/** 节点文件行操作：打开 / 下载 / 重命名 / 删除 */
const rowActions: ActionMenuItem<unknown>[] = [
  { label: '打开', icon: 'morph:file-edit', visible: row => !(row as { isDir?: boolean }).isDir, onClick: row => edit(filePath(row as never)) },
  { label: '下载', icon: 'morph:download', visible: row => !(row as { isDir?: boolean }).isDir, disabled: () => Boolean(download.active.value), onClick: row => downloadRow(row as never) },
  { label: '重命名 / 移动', icon: 'morph:pencil', visible: () => canManage.value, disabled: () => busy.value, onClick: row => openForm(row as never) },
  { label: '删除', icon: 'morph:trash-2', danger: true, visible: () => canManage.value, disabled: () => busy.value, onClick: row => remove(row as never) },
]

</script>

<template>
  
  <YdPageMain class="mcp-file-page">
    <div class="mcp-file-location">
      <YdButton size="sm" variant="outline" :aria-expanded="sideOpen" @click="sideOpen = !sideOpen"><YdIcon name="i-ri:side-bar-line" />目录</YdButton>
      <nav class="mcp-file-path" aria-label="当前文件目录">
        <template v-for="(crumb, index) in crumbs" :key="crumb.path"><span v-if="index" class="mcp-muted">/</span><YdButton size="sm" variant="ghost" @click="navigate(crumb.path)">{{ crumb.label }}</YdButton></template>
      </nav>
      <YdTag variant="outline">{{ root || '默认根目录' }}</YdTag>
    </div>
    <div class="mcp-files-split" :class="{ 'is-side-open': sideOpen }">
      <aside v-if="sideOpen" class="mcp-files-side"><FileBrowserPanel :key="`${nodeId}:${root}`" :list="listSide" :open-file="edit" title="文件浏览" /></aside>
      <div class="mcp-files-main">
        <div class="mcp-file-search"><YdInput v-model="keyword" clearable placeholder="搜索当前目录" @keydown.enter="search" @clear="search" /><YdButton variant="outline" :loading="loading" @click="search"><YdIcon name="i-ri:refresh-line" />刷新</YdButton></div>
        <YdAlert v-if="error" variant="destructive" title="目录加载失败" class="mb-4">{{ error }}</YdAlert>
        <div v-if="download.active.value" class="mcp-download-status"><YdIcon name="i-ri:download-line" /><span>{{ download.fileName.value }}</span><YdProgress :model-value="download.progress.value" /><span>{{ download.progress.value }}%</span><YdButton size="sm" variant="outline" @click="download.cancel">取消</YdButton></div>
                <YdTablePageLayout title="`${name || '节点'} · 文件管理`" :show-search="false">
            <template #actions>
      <YdButton variant="outline" @click="router.push(`/platform/plugins/mcpanel/admin/nodes/${nodeId}`)"><YdIcon name="i-ri:arrow-left-line" />节点详情</YdButton>
    <YdButton v-if="canManage" variant="outline" :loading="ftpBusy" @click="enableFtp"><YdIcon name="i-ri:key-2-line" />SFTP 连接</YdButton>
    <YdButton v-if="canManage" @click="openForm()"><YdIcon name="i-ri:folder-add-line" />新建目录</YdButton>
            </template>
            <template #table>
<YdLoadingOverlay :loading="loading"><YdResponsiveEnhancedTable
            :actions="rowActions"
  :data="rows" :columns="columns" row-key="name" table-root-class="rounded-lg overflow-hidden" table-class="min-w-[800px]" border stripe column-visibility :empty-text="error ? '未能读取目录' : keyword ? '没有匹配的文件' : '此目录为空'">
          <template #cell-name="{ row }"><YdButton variant="ghost" class="mcp-file-name" @click="enter(row.original)"><YdIcon :name="row.original.isDir ? 'i-ri:folder-3-line' : 'i-ri:file-text-line'" />{{ row.original.name }}</YdButton></template>
          <template #cell-type="{ row }">{{ row.original.isDir ? '目录' : '文件' }}</template>
          <template #cell-size="{ row }">{{ row.original.isDir ? '—' : formatSize(row.original.size) }}</template>
          <template #cell-modTime="{ row }">{{ formatDateTime(row.original.modTime as number | string) }}</template>
        </YdResponsiveEnhancedTable></YdLoadingOverlay>
            </template>
            <template #footer>
      <YdPagination v-model:page="pager.page" v-model:size="pager.size" :sizes="[10, 20, 50, 100]" :total="pager.total" @page-change="() => load()" @size-change="search" />
            </template>
          </YdTablePageLayout>

      </div>
    </div>
    <YdModal v-model="formOpen" :title="formKind === 'mkdir' ? '新建目录' : '重命名 / 移动'" :confirm-button-loading="busy" :before-close="(action, done) => action === 'confirm' ? submit(done) : busy ? undefined : done()">
      <div class="mcp-form"><label class="mcp-form-item"><span>{{ formKind === 'mkdir' ? `在 /${directory} 下创建` : '目标路径（相对根目录）' }}</span><YdInput v-model="target" placeholder="请输入名称或相对路径" /></label><YdAlert v-if="formError" title="无法保存" variant="destructive">{{ formError }}</YdAlert></div>
    </YdModal>
    <YdModal v-model="ftpOpen" title="SFTP 临时连接" :footer="false">
      <template v-if="ftp"><p class="mcp-form-hint">凭据仅用于当前白名单目录，到期自动失效，请勿外传。</p><div class="mcp-ftp-box"><div>主机：{{ ftp.host || '节点地址' }}</div><div>端口：{{ ftp.port }}</div><div>用户：{{ ftp.user }}</div><div>密码：{{ ftp.password }}</div><div>到期：{{ formatDateTime(ftp.expiresAt as number | string) }}</div></div><YdButton variant="destructive" :loading="ftpBusy" class="mt-4" @click="disableFtp">关闭 SFTP 会话</YdButton></template>
    </YdModal>
    <FileEditorModal
      v-model="editorOpen"
      title="节点文件编辑"
      :load="editorAccess.load"
      :save="editorAccess.save"
      :list-dir="listSide"
      :can-save="canManage"
      :initial-path="editorPath"
      :initial-directory="editorDirectory"
      @saved="() => load()"
    />
  </YdPageMain>
</template>
