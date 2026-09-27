<script setup lang="ts">
import type { TableColumn, YdTablePickerQuery, YdTablePickerResult } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { McpNode } from '../../types'
import { FaAlert, FaButton, FaCard, FaDescriptions, FaIcon, FaInput, FaModal, FaPageHeader, FaPageMain, FaPagination, FaResponsiveTable, FaSelect, FaSwitch, FaTag, FaTextarea, YdTablePicker, useFaModal, useFaToast } from '@yudream/components'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { createMcPanelApi } from '../../api/mcpanel-api'
import { createMcPanelExtra } from '../../api/api-extra'
import { MCPANEL_PERMISSION, accountHasPermission } from '../../composables/permissions'
import { errorMessage, formatDateTime, formatSize } from '../../composables/utils'

/**
 * 镜像管理（对齐 MCSM「管理面板 / 节点 / 镜像管理」）：
 * 远程主机镜像列表（ID / 名称 / 占用空间 / 详情·删除）
 * + 远程主机容器列表 + 镜像目录（内部名称 + 多标签）
 */
const props = defineProps<{ sdk: YuDreamPluginSdk }>()

const api = createMcPanelApi(props.sdk)
const extra = createMcPanelExtra(props.sdk)
const toast = useFaToast()
const modal = useFaModal()
const router = useRouter()

const canManage = computed(() => accountHasPermission(props.sdk.account, MCPANEL_PERMISSION.manage))
const canDelete = computed(() => accountHasPermission(props.sdk.account, MCPANEL_PERMISSION.delete))

const loading = ref(false)
const nodes = ref<McpNode[]>([])
const nodeKeys = ref<string[]>([])
const nodeId = ref('')
const allImages = ref<Array<Record<string, unknown>>>([])
const imagePager = reactive({ page: 1, size: 10, total: 0 })

const containerLoading = ref(false)
const containerRows = ref<Record<string, unknown>[]>([])
const allContainers = ref<Record<string, unknown>[]>([])

const catalogLoading = ref(false)
const catalogRows = ref<Record<string, unknown>[]>([])
const catalogPager = reactive({ page: 1, size: 10, total: 0 })
const catalogKeyword = ref('')

const pullOpen = ref(false)
const pullImage = ref('')
const pulling = ref(false)
const pullStatus = ref('')

const detailOpen = ref(false)
const detailRow = ref<Record<string, unknown> | null>(null)

const catalogFormOpen = ref(false)
const catalogEditing = ref<Record<string, unknown> | null>(null)
const catalogSubmitting = ref(false)
const catalogError = ref('')
const catalogForm = reactive({
  id: '',
  name: '',
  javaVersion: '',
  tagsText: '',
  primaryImage: '',
  note: '',
  enabled: true,
})

const selectedNode = computed(() => nodes.value.find(n => n.id === nodeId.value) ?? null)

const imageColumns: TableColumn<Record<string, unknown>>[] = [
  { accessorKey: 'idText', header: 'ID', minWidth: 320 },
  { accessorKey: 'nameText', header: '名称', minWidth: 280 },
  { accessorKey: 'sizeText', header: '占用空间', width: 110, align: 'center' },
  { id: 'operation', header: '操作', width: 160, align: 'center' },
]

const containerColumns: TableColumn<Record<string, unknown>>[] = [
  { accessorKey: 'name', header: '容器 / 实例', minWidth: 160 },
  { accessorKey: 'state', header: '状态', width: 100, align: 'center' },
  { accessorKey: 'image', header: '镜像', minWidth: 220 },
  { accessorKey: 'status', header: 'Docker 状态', minWidth: 140 },
  { accessorKey: 'id', header: 'ID', minWidth: 120 },
  { id: 'operation', header: '操作', width: 120, align: 'center' },
]

const catalogColumns: TableColumn<Record<string, unknown>>[] = [
  { accessorKey: 'name', header: '内部名称（Java）', minWidth: 140 },
  { accessorKey: 'javaVersion', header: 'Java', width: 80, align: 'center' },
  { accessorKey: 'primaryImage', header: 'JRE 镜像', minWidth: 240 },
  { id: 'tags', header: '标签', minWidth: 220 },
  { id: 'flags', header: '状态', width: 120 },
  { id: 'operation', header: '操作', width: 200 },
]

const nodeColumns: TableColumn<Record<string, unknown>>[] = [
  { accessorKey: 'name', header: '节点', minWidth: 140 },
  { accessorKey: 'endpoint', header: '地址', minWidth: 180 },
  { accessorKey: 'statusText', header: '状态', width: 90 },
]

const pagedImages = computed(() => {
  const start = (imagePager.page - 1) * imagePager.size
  return allImages.value.slice(start, start + imagePager.size)
})

function resolveTags(row: Record<string, unknown>): string[] {
  if (Array.isArray(row.tags) && row.tags.length) {
    return row.tags.map(String)
  }
  const image = row.primaryImage ?? row.image
  return image ? [String(image)] : []
}

function guessJava(row: Record<string, unknown>): string {
  const text = `${String(row.name ?? '')} ${String(row.label ?? '')} ${String(row.primaryImage ?? row.image ?? '')}`
  const match = text.match(/(?:java|openjdk|jdk|jre)\s*(\d{1,2})/i)
  return match?.[1] ?? ''
}

function idText(id: unknown) {
  return String(id ?? '')
}

function nameText(row: Record<string, unknown>) {
  const tags = Array.isArray(row.repoTags) ? (row.repoTags as string[]).filter(Boolean) : []
  return tags.length ? tags.join('，') : '<none>'
}

function shortId(id: unknown) {
  const text = String(id ?? '')
  return text.length > 28 ? `${text.slice(0, 28)}…` : text
}

async function nodeFetcher(query: YdTablePickerQuery): Promise<YdTablePickerResult<Record<string, unknown>>> {
  const page = await api.pageNodes(query.page, query.size, query.keyword || undefined) as { records?: McpNode[], total?: number }
  nodes.value = page.records ?? []
  return {
    list: nodes.value.map(node => ({
      id: node.id,
      name: node.name,
      endpoint: node.endpoint || '-',
      statusText: node.status || '-',
    })),
    total: Number(page.total ?? nodes.value.length),
  }
}

watch(nodeKeys, (keys) => {
  nodeId.value = keys[0] ?? ''
  imagePager.page = 1
  if (nodeId.value) {
    void loadNodeImages()
    void loadContainers()
  }
  else {
    allImages.value = []
    containerRows.value = []
  }
})

function onImageSizeChange() {
  imagePager.page = 1
}

async function loadNodeImages() {
  if (!nodeId.value) {
    return
  }
  loading.value = true
  try {
    const result = await extra.listNodeImages(nodeId.value) as { images?: Array<Record<string, unknown>> }
    allImages.value = (result?.images ?? []).map(item => ({
      ...item,
      idText: idText(item.id),
      nameText: nameText(item),
      sizeText: formatSize(item.size),
    }))
    imagePager.total = allImages.value.length
  }
  catch (error) {
    toast.error(errorMessage(error, '加载远程主机镜像失败（节点 Agent 需已部署支持 image.list 的版本）'))
    allImages.value = []
    imagePager.total = 0
  }
  finally {
    loading.value = false
  }
}

/** 远程主机容器：面板侧该节点实例 + 节点 stats 容器状态。 */
async function loadContainers() {
  if (!nodeId.value) {
    return
  }
  containerLoading.value = true
  try {
    const results = await Promise.all([
      extra.pageInstances(1, 50, undefined, undefined, nodeId.value),
      extra.listNodeContainers(nodeId.value).catch(() => ({ containers: [] as Array<Record<string, unknown>> })),
    ])
    const managed = (results[0] ?? {}) as { records?: Record<string, unknown>[] }
    const all = (results[1] ?? {}) as { containers?: Array<Record<string, unknown>> }
    const live = new Map<string, Record<string, unknown>>()
    for (const item of (all?.containers as Array<Record<string, unknown>> | undefined) ?? []) {
      if (item.instanceId) {
        live.set(String(item.instanceId), item)
      }
    }
    allContainers.value = (all?.containers as Array<Record<string, unknown>> | undefined) ?? []
    containerRows.value = (managed.records ?? []).map(row => {
      const stat = live.get(String(row.id))
      return {
        ...row,
        state: String(stat?.state ?? row.state ?? '-'),
        image: String(stat?.image ?? row.image ?? '-'),
      }
    })
  }
  catch (error) {
    toast.error(errorMessage(error, '加载容器列表失败'))
    containerRows.value = []
  }
  finally {
    containerLoading.value = false
  }
}

async function loadCatalog() {
  catalogLoading.value = true
  try {
    const page = await extra.pageDockerImages(
      catalogPager.page,
      catalogPager.size,
      catalogKeyword.value.trim() || undefined,
    ) as { records?: Record<string, unknown>[], total?: number }
    catalogRows.value = page.records ?? []
    catalogPager.total = Number(page.total ?? 0)
  }
  catch (error) {
    toast.error(errorMessage(error, '加载镜像目录失败'))
  }
  finally {
    catalogLoading.value = false
  }
}

function refreshAll() {
  if (nodeId.value) {
    void loadNodeImages()
    void loadContainers()
  }
  void loadCatalog()
}

function openPull(image = '') {
  if (!nodeId.value) {
    toast.warning('请先选择目标节点')
    return
  }
  pullImage.value = image
  pullStatus.value = ''
  pullOpen.value = true
}

async function submitPull(done: () => void) {
  const image = pullImage.value.trim()
  if (!image || pulling.value || !nodeId.value) {
    return
  }
  pulling.value = true
  pullStatus.value = '提交拉取任务…'
  try {
    const result = await extra.pullNodeImage(nodeId.value, image) as { taskId?: string }
    done()
    if (result?.taskId) {
      pullStatus.value = `任务 ${result.taskId} 进行中…`
      void pollPullTask(String(result.taskId), image)
    }
    else {
      toast.success('拉取任务已提交')
    }
  }
  catch (error) {
    const message = errorMessage(error, '拉取镜像失败')
    pullStatus.value = message
    toast.error(message)
  }
  finally {
    pulling.value = false
  }
}

async function pollPullTask(taskId: string, image: string) {
  for (let i = 0; i < 120; i++) {
    await new Promise(resolve => setTimeout(resolve, 2000))
    try {
      const state = await extra.nodeTask(nodeId.value, taskId) as { state?: string, error?: string }
      const status = String(state?.state ?? '')
      if (status === 'done') {
        pullStatus.value = `已拉取：${image}`
        toast.success('镜像拉取完成')
        void loadNodeImages()
        return
      }
      if (status === 'failed' || status === 'canceled') {
        pullStatus.value = state?.error || status
        toast.error(state?.error || '镜像拉取失败')
        return
      }
      pullStatus.value = status || 'running'
    }
    catch {
      // 继续轮询
    }
  }
}

function openDetail(row: Record<string, unknown>) {
  detailRow.value = row
  detailOpen.value = true
}

async function copyId(row: Record<string, unknown>) {
  const text = idText(row.id)
  try {
    await navigator.clipboard.writeText(text)
    toast.success('镜像 ID 已复制')
  }
  catch {
    toast.warning(`请手动复制：${text}`)
  }
}

function confirmRemoveImage(row: Record<string, unknown>) {
  const name = nameText(row)
  const target = name === '<none>' ? idText(row.id) : name.split('，')[0]
  modal.confirm({
    title: '删除远程主机镜像',
    content: `确认从节点「${selectedNode.value?.name}」删除镜像「${target}」吗？使用该镜像的容器将无法新建。`,
    confirmButtonText: '删除',
    onConfirm: async () => {
      try {
        await extra.removeNodeImage(nodeId.value, target)
        toast.success('镜像已删除')
        void loadNodeImages()
      }
      catch (error) {
        toast.error(errorMessage(error, '删除镜像失败（节点 Agent 需支持 image.remove）'))
      }
    },
  })
}

function openContainer(row: Record<string, unknown>) {
  const id = row.instanceId || row.id
  if (row.instanceId) {
    void router.push(`/platform/plugins/mcpanel/admin/instances/${encodeURIComponent(String(row.instanceId))}`)
    return
  }
  void router.push(`/platform/plugins/mcpanel/admin/instances?keyword=${encodeURIComponent(String(id ?? ''))}`)
}

function pullFromCatalogRow(row: Record<string, unknown>) {
  openPull(String(row.primaryImage ?? ''))
}

function openCatalogCreate() {
  catalogEditing.value = null
  catalogForm.id = ''
  catalogForm.name = ''
  catalogForm.javaVersion = ''
  catalogForm.tagsText = ''
  catalogForm.primaryImage = ''
  catalogForm.note = ''
  catalogForm.enabled = true
  catalogError.value = ''
  catalogFormOpen.value = true
}

function openCatalogEdit(row: Record<string, unknown>) {
  catalogEditing.value = row
  catalogForm.id = String(row.id ?? '')
  catalogForm.name = String(row.name ?? '')
  catalogForm.javaVersion = String(row.javaVersion ?? '')
  catalogForm.tagsText = (Array.isArray(row.tags) ? (row.tags as string[]) : []).join('\n')
  catalogForm.primaryImage = String(row.primaryImage ?? '')
  catalogForm.note = String(row.note ?? '')
  catalogForm.enabled = row.enabled !== false
  catalogError.value = ''
  catalogFormOpen.value = true
}

async function submitCatalog(done: () => void) {
  if (!catalogForm.name.trim() || !catalogForm.tagsText.trim()) {
    catalogError.value = '请填写内部名称，并至少一个镜像标签'
    return
  }
  catalogSubmitting.value = true
  catalogError.value = ''
  try {
    const tags = catalogForm.tagsText.split(/[\n,]/).map(s => s.trim()).filter(Boolean)
    await extra.saveDockerImage({
      id: catalogForm.id || undefined,
      name: catalogForm.name.trim(),
      javaVersion: catalogForm.javaVersion.trim(),
      tags,
      primaryImage: catalogForm.primaryImage.trim() || tags[0],
      note: catalogForm.note.trim(),
      enabled: catalogForm.enabled,
    })
    done()
    toast.success('镜像目录已保存')
    void loadCatalog()
  }
  catch (error) {
    catalogError.value = errorMessage(error, '保存失败')
  }
  finally {
    catalogSubmitting.value = false
  }
}

function confirmCatalogDelete(row: Record<string, unknown>) {
  modal.confirm({
    title: '删除镜像目录项',
    content: `确认删除「${row.name}」吗？已创建实例不受影响。`,
    confirmButtonText: '删除',
    onConfirm: async () => {
      try {
        await extra.deleteDockerImage(String(row.id))
        toast.success('已删除')
        if (catalogRows.value.length === 1 && catalogPager.page > 1) {
          catalogPager.page -= 1
        }
        void loadCatalog()
      }
      catch (error) {
        toast.error(errorMessage(error, '删除失败'))
      }
    },
  })
}

const detailItems = computed(() => {
  const row = detailRow.value
  if (!row) {
    return []
  }
  return [
    { label: '镜像 ID', value: idText(row.id) },
    { label: '名称 / 标签', value: nameText(row) },
    { label: '占用空间', value: formatSize(row.size as number | string | undefined) },
    { label: '创建时间', value: row.created ? formatDateTime(Number(row.created) * 1000) : '-' },
  ]
})

onMounted(() => {
  void loadCatalog()
})
</script>

<template>
  <FaPageHeader title="镜像管理" description="管理面板 / 节点 / 镜像管理">
    <FaButton variant="outline" @click="refreshAll">
      <FaIcon name="i-ri:refresh-line" />
      刷新
    </FaButton>
    <FaButton v-if="canManage" :disabled="!nodeId" @click="openPull()">
      <FaIcon name="i-ri:add-line" />
      新增镜像
    </FaButton>
  </FaPageHeader>

  <FaPageMain class="mcp-col">
    <FaCard title="远程主机镜像列表" description="镜像构建与容器运行依赖于 Docker 软件，物理主机上所有远程节点将共享所有镜像。">
      <div class="mcp-form-item mb-3">
        <span class="mcp-form-label">目标节点</span>
        <YdTablePicker
          v-model="nodeKeys"
          :columns="nodeColumns"
          :fetcher="nodeFetcher"
          row-key="id"
          label-key="name"
          :multiple="false"
          title="选择节点"
          placeholder="点击选择节点"
          :initial-labels="selectedNode ? { [selectedNode.id]: selectedNode.name } : {}"
        />
      </div>

      <FaResponsiveTable
        v-loading="loading"
        :columns="imageColumns"
        :data="pagedImages"
        row-key="id"
        table-root-class="rounded-lg overflow-hidden"
        table-class="min-w-[900px]"
        border
        stripe
        :empty-text="nodeId ? '该节点暂无镜像，点击右上角「新增镜像」拉取' : '请先选择目标节点'"
      >
        <template #cell-idText="{ row }">
          <div class="mcp-row">
            <code class="mcp-inline-code" :title="String(row.original.idText ?? row.original.id ?? '')">{{ shortId(row.original.id) }}</code>
            <FaButton variant="ghost" size="icon-sm" title="复制 ID" @click="copyId(row.original)">
              <FaIcon name="i-ri:file-copy-line" />
            </FaButton>
          </div>
        </template>
        <template #cell-nameText="{ row }">
          <span class="mcp-mono">{{ row.original.nameText }}</span>
        </template>
        <template #cell-operation="{ row }">
          <div class="mcp-op-cell">
            <FaButton size="sm" variant="outline" @click="openDetail(row.original)">
              详情
            </FaButton>
            <FaButton v-if="canDelete" size="sm" variant="destructive" @click="confirmRemoveImage(row.original)">
              删除
            </FaButton>
          </div>
        </template>
        <template #card="{ row }">
          <FaCard class="w-full">
            <div class="flex flex-col gap-3">
              <div class="flex items-center justify-between gap-2">
                <span class="min-w-0 break-words text-base font-semibold">{{ row.nameText }}</span>
                <FaTag variant="secondary">{{ row.sizeText }}</FaTag>
              </div>
              <div class="flex flex-col gap-1 text-sm">
                <div class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">镜像 ID</span>
                  <span class="mcp-mono break-all">{{ shortId(row.id) }}</span>
                </div>
              </div>
              <div class="flex flex-wrap gap-2 border-t pt-3">
                <FaButton size="sm" variant="outline" @click="copyId(row)">
                  <FaIcon name="i-ri:file-copy-line" />
                  复制 ID
                </FaButton>
                <FaButton size="sm" variant="outline" @click="openDetail(row)">
                  详情
                </FaButton>
                <FaButton v-if="canDelete" size="sm" variant="destructive" @click="confirmRemoveImage(row)">
                  删除
                </FaButton>
              </div>
            </div>
          </FaCard>
        </template>
      </FaResponsiveTable>

      <FaPagination
        v-model:page="imagePager.page"
        v-model:size="imagePager.size"
        :total="imagePager.total"
        class="mt-3"
        @size-change="onImageSizeChange"
      />

      <p v-if="pullStatus" class="mcp-status-meta mt-3">
        拉取状态：{{ pullStatus }}
      </p>
    </FaCard>

    <FaCard title="远程主机容器列表" description="节点上全部 Docker 容器（含非面板启动），MCSM 同款列表。" class="mcp-page-gap">
      <FaResponsiveTable
        v-loading="containerLoading"
        :columns="containerColumns"
        :data="allContainers.length ? allContainers : containerRows"
        row-key="id"
        table-root-class="rounded-lg overflow-hidden"
        table-class="min-w-[820px]"
        border
        stripe
        :empty-text="nodeId ? '该节点暂无容器' : '请先选择目标节点'"
      >
        <template #cell-operation="{ row }">
          <FaButton v-if="row.original.instanceId" size="sm" variant="outline" @click="openContainer(row.original)">
            打开实例
          </FaButton>
        </template>
        <template #card="{ row }">
          <FaCard class="w-full">
            <div class="flex flex-col gap-3">
              <div class="flex items-center justify-between gap-2">
                <span class="min-w-0 break-words text-base font-semibold">{{ row.name }}</span>
                <FaTag :variant="String(row.state) === 'running' ? 'default' : 'secondary'">
                  {{ row.state || '-' }}
                </FaTag>
              </div>
              <div class="flex flex-col gap-1 text-sm">
                <div class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">镜像</span>
                  <span class="break-all">{{ row.image || '-' }}</span>
                </div>
                <div class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">Docker 状态</span>
                  <span class="break-all">{{ row.status || '-' }}</span>
                </div>
                <div class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">ID</span>
                  <span class="mcp-mono break-all">{{ row.id }}</span>
                </div>
              </div>
              <div v-if="row.instanceId" class="flex flex-wrap gap-2 border-t pt-3">
                <FaButton size="sm" variant="outline" @click="openContainer(row)">
                  打开实例
                </FaButton>
              </div>
            </div>
          </FaCard>
        </template>
      </FaResponsiveTable>
    </FaCard>

    <FaCard title="Java 运行时镜像目录" description="按 Java/JRE 版本维护的内部名称 + 标签，与 Paper 等服务端核心无关；创建服务器时选 Java 运行时。" class="mcp-page-gap">
      <div class="mcp-toolbar-row mb-3">
        <FaInput
          v-model="catalogKeyword"
          clearable
          placeholder="搜索名称 / 标签"
          class="mcp-search-input"
          @keydown.enter="() => { catalogPager.page = 1; loadCatalog() }"
          @clear="() => { catalogPager.page = 1; loadCatalog() }"
        />
        <div class="mcp-spacer mcp-row">
          <FaButton v-if="canManage" size="sm" @click="openCatalogCreate">
            <FaIcon name="i-ri:add-line" />
            新增目录项
          </FaButton>
        </div>
      </div>

      <FaResponsiveTable
        v-loading="catalogLoading"
        :columns="catalogColumns"
        :data="catalogRows"
        row-key="id"
        table-root-class="rounded-lg overflow-hidden"
        table-class="min-w-[960px]"
        border
        stripe
        empty-text="暂无目录项，内置 JRE 会在插件首次启用时写入"
      >
        <template #cell-name="{ row }">
          {{ row.original.name || row.original.label || row.original.id || '-' }}
        </template>
        <template #cell-javaVersion="{ row }">
          {{ row.original.javaVersion || guessJava(row.original) || '-' }}
        </template>
        <template #cell-primaryImage="{ row }">
          <span class="mcp-mono" :title="String(row.original.primaryImage || row.original.image || '')">
            {{ row.original.primaryImage || row.original.image || (Array.isArray(row.original.tags) && row.original.tags.length ? row.original.tags[0] : '-') }}
          </span>
        </template>
        <template #cell-tags="{ row }">
          <div class="mcp-col" style="gap: 4px;">
            <FaTag
              v-for="tag in resolveTags(row.original)"
              :key="tag"
              variant="secondary"
              class="font-mono"
              :title="tag"
            >
              {{ tag.length > 42 ? `${tag.slice(0, 42)}…` : tag }}
            </FaTag>
          </div>
        </template>
        <template #cell-flags="{ row }">
          <div class="mcp-row">
            <FaTag v-if="row.original.builtin" variant="default">内置</FaTag>
            <FaTag :variant="row.original.enabled === false ? 'secondary' : 'outline'">
              {{ row.original.enabled === false ? '停用' : '启用' }}
            </FaTag>
          </div>
        </template>
        <template #cell-operation="{ row }">
          <div class="mcp-op-cell">
            <FaButton v-if="canManage && nodeId" size="sm" variant="outline" @click="pullFromCatalogRow(row.original)">
              拉取到节点
            </FaButton>
            <FaButton v-if="canManage" size="sm" variant="outline" @click="openCatalogEdit(row.original)">
              编辑
            </FaButton>
            <FaButton v-if="canDelete" size="sm" variant="destructive" @click="confirmCatalogDelete(row.original)">
              删除
            </FaButton>
          </div>
        </template>
        <template #card="{ row }">
          <FaCard class="w-full">
            <div class="flex flex-col gap-3">
              <div class="flex items-center justify-between gap-2">
                <span class="min-w-0 break-words text-base font-semibold">{{ row.name || row.label || row.id || '-' }}</span>
                <div class="flex gap-1">
                  <FaTag v-if="row.builtin" variant="default">内置</FaTag>
                  <FaTag :variant="row.enabled === false ? 'secondary' : 'outline'">
                    {{ row.enabled === false ? '停用' : '启用' }}
                  </FaTag>
                </div>
              </div>
              <div class="flex flex-col gap-1 text-sm">
                <div class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">Java</span>
                  <span class="break-all">{{ row.javaVersion || guessJava(row) || '-' }}</span>
                </div>
                <div class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">JRE 镜像</span>
                  <span class="mcp-mono break-all">{{ row.primaryImage || row.image || (Array.isArray(row.tags) && row.tags.length ? row.tags[0] : '-') }}</span>
                </div>
                <div class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">标签</span>
                  <span class="break-all">{{ resolveTags(row).join('、') || '-' }}</span>
                </div>
              </div>
              <div class="flex flex-wrap gap-2 border-t pt-3">
                <FaButton v-if="canManage && nodeId" size="sm" variant="outline" @click="pullFromCatalogRow(row)">
                  拉取到节点
                </FaButton>
                <FaButton v-if="canManage" size="sm" variant="outline" @click="openCatalogEdit(row)">
                  编辑
                </FaButton>
                <FaButton v-if="canDelete" size="sm" variant="destructive" @click="confirmCatalogDelete(row)">
                  删除
                </FaButton>
              </div>
            </div>
          </FaCard>
        </template>
      </FaResponsiveTable>
      <FaPagination
        v-model:page="catalogPager.page"
        v-model:size="catalogPager.size"
        :total="catalogPager.total"
        class="mt-3"
        @page-change="() => loadCatalog()"
        @size-change="() => { catalogPager.page = 1; loadCatalog() }"
      />
    </FaCard>

    <FaModal
      v-model="detailOpen"
      title="镜像详情"
      :show-cancel-button="false"
      confirm-button-text="关闭"
      class="max-w-[min(36rem,calc(100vw-2rem))]"
      :before-close="(_action: 'confirm' | 'cancel' | 'close', done: () => void) => done()"
    >
      <FaDescriptions :items="detailItems" :column="1" border />
    </FaModal>

    <FaModal
      v-model="pullOpen"
      title="新增镜像（拉取到远程主机）"
      :show-cancel-button="true"
      confirm-button-text="开始拉取"
      :confirm-button-loading="pulling"
      class="max-w-[min(36rem,calc(100vw-2rem))]"
      :before-close="(action: 'confirm' | 'cancel' | 'close', done: () => void) => action === 'confirm' ? submitPull(done) : done()"
    >
      <div class="mcp-form">
        <label class="mcp-form-item">
          <span class="mcp-form-label">目标节点</span>
          <FaInput :model-value="selectedNode?.name || ''" disabled class="mcp-w-full" />
        </label>
        <label class="mcp-form-item">
          <span class="mcp-form-label">镜像地址（名称:标签）</span>
          <FaInput v-model="pullImage" class="mcp-w-full mcp-mono" placeholder="swr.cn-north-4.myhuaweicloud.com/..." />
          <span class="mcp-form-hint">
            建议从镜像目录选择后「拉取到节点」；内置 JRE 使用华为云 SWR 源。Docker 拉取已按 tag 正确拆分。
          </span>
        </label>
      </div>
    </FaModal>

    <FaModal
      v-model="catalogFormOpen"
      :title="catalogEditing ? '编辑镜像目录项' : '新增镜像目录项'"
      :show-cancel-button="true"
      :confirm-button-text="catalogEditing ? '保存' : '添加'"
      :confirm-button-loading="catalogSubmitting"
      class="max-w-[min(36rem,calc(100vw-2rem))]"
      :before-close="(action: 'confirm' | 'cancel' | 'close', done: () => void) => action === 'confirm' ? submitCatalog(done) : done()"
    >
      <div class="mcp-form">
        <label class="mcp-form-item">
          <span class="mcp-form-label">内部使用名称（按 Java 版本）</span>
          <FaInput v-model="catalogForm.name" class="mcp-w-full" placeholder="例如 Java 21（JRE）" />
        </label>
        <label class="mcp-form-item">
          <span class="mcp-form-label">Java 版本</span>
          <FaInput v-model="catalogForm.javaVersion" class="mcp-w-full" placeholder="21" />
          <span class="mcp-form-hint">这里是 JVM 版本，不是 Paper/Fabric 等核心版本。</span>
        </label>
        <label class="mcp-form-item">
          <span class="mcp-form-label">镜像标签（每行一个，可多个）</span>
          <FaTextarea v-model="catalogForm.tagsText" :rows="4" class="mcp-w-full mcp-mono" placeholder="registry/repo:tag" />
        </label>
        <label class="mcp-form-item">
          <span class="mcp-form-label">默认标签（主镜像）</span>
          <FaSelect
            v-model="catalogForm.primaryImage"
            :options="catalogForm.tagsText.split(/[\n,]/).map(s => s.trim()).filter(Boolean).map(tag => ({ label: tag, value: tag }))"
            class="mcp-w-full"
            allow-clear
          />
        </label>
        <label class="mcp-form-item">
          <span class="mcp-form-label">备注</span>
          <FaTextarea v-model="catalogForm.note" :rows="2" class="mcp-w-full" />
        </label>
        <label class="flex items-center gap-2 text-sm">
          <FaSwitch v-model="catalogForm.enabled" />
          启用（创建向导可见）
        </label>
        <FaAlert v-if="catalogError" variant="destructive" title="无法保存">
  <template #description>
          {{ catalogError }}
  </template>
        </FaAlert>
      </div>
    </FaModal>
  </FaPageMain>
</template>
