<script setup lang="ts">
import { YdLoadingOverlay } from '@yudream/components'
import type { ActionMenuItem, TableColumn } from '@yudream/components'
import type { MaterialPluginModel } from '../composables/useMaterialPlugin'
import type { MaterialItemView, MaterialSummary, PreviewInfo } from '../types'
import { YdTablePageLayout, YdButton, YdDrawer, YdIcon, YdInput, YdListToolbar, YdModal, YdPagination, YdSelect, YdResponsiveEnhancedTable, YdCheckbox, YdTag, useYdModal } from '@yudream/components'
import { onMounted, ref, watch } from 'vue'
import CategoryPicker from '../components/CategoryPicker.vue'
import EditMaterialModal from '../components/EditMaterialModal.vue'
import MaterialItemManager from '../components/MaterialItemManager.vue'
import NewVersionModal from '../components/NewVersionModal.vue'
import PreviewFrame from '../components/PreviewFrame.vue'
import ShareModal from '../components/ShareModal.vue'
import TagPicker from '../components/TagPicker.vue'
import { formatSize, formatTime, isBundle, MATERIAL_TYPES } from '../types'

const props = defineProps<{ model: MaterialPluginModel }>()
const model = props.model
const confirm = useYdModal()

const previewOpen = ref(false)
const previewInfo = ref<PreviewInfo | null>(null)
const previewTarget = ref<MaterialSummary | null>(null)
const previewing = ref(false)

// 行操作：编辑元数据 / 代传新版本 / 代管分享
const saving = ref(false)
const editOpen = ref(false)
const editTarget = ref<MaterialSummary | null>(null)
const newVersionOpen = ref(false)
const newVersionTarget = ref<MaterialSummary | null>(null)
const shareOpen = ref(false)
const shareTarget = ref<MaterialSummary | null>(null)

// 子物料抽屉：管理员对任意用户的物料增删改子物料并独立管理各子物料版本（走 /admin 代操作接口）
const itemsOpen = ref(false)
const itemsTarget = ref<MaterialSummary | null>(null)
const itemsActiveId = ref('')
const itemPreviewOpen = ref(false)
const itemPreviewLabel = ref('')

// 批量选择由 YdResponsiveTable 在桌面表格与窄屏卡片间共享。
const selectedRows = ref<MaterialSummary[]>([])
const rowSelection = ref<Record<string, boolean>>({})
const batchCategoryOpen = ref(false)
const batchCategoryId = ref('')
const batchTagsOpen = ref(false)
const batchTags = ref<string[]>([])
const batchTagMode = ref<'APPEND' | 'REPLACE'>('APPEND')
const batchSaving = ref(false)

const statusOptions = [
  { label: '全部状态', value: '' },
  { label: '正常使用', value: 'ACTIVE' },
  { label: '已归档', value: 'ARCHIVED' },
]

const batchTagModeOptions = [
  { label: '追加（与原有标签合并去重）', value: 'APPEND' },
  { label: '覆盖（替换为所选标签）', value: 'REPLACE' },
]

const columns: TableColumn<MaterialSummary>[] = [
  { type: 'selection', fixed: 'left', width: 46 },
  { accessorKey: 'name', header: '名称', minWidth: 180, fixed: 'left' },
  { id: 'type', header: '类型', width: 100 },
  { id: 'owner', header: '归属用户', width: 120 },
  { accessorKey: 'categoryName', header: '分类', width: 100 },
  { id: 'size', header: '大小', width: 100 },
  { id: 'version', header: '版本', width: 90 },
  { id: 'status', header: '状态', width: 90 },
  { accessorKey: 'updatedAt', header: '更新时间', width: 170 },
]

/** 翻页/筛选/刷新后行对象重建，勾选项按 id 修剪，避免对已不在当前页的行批量操作 */
watch(() => model.adminList, (list) => {
  if (!selectedRows.value.length) {
    return
  }
  const ids = new Set(list.map(item => item.id))
  selectedRows.value = selectedRows.value.filter(item => ids.has(item.id))
  rowSelection.value = Object.fromEntries(Object.entries(rowSelection.value).filter(([id, selected]) => selected && ids.has(id)))
})

function onSelectionChange(rows: MaterialSummary[]) {
  selectedRows.value = rows
}

async function openPreview(row: MaterialSummary) {
  previewTarget.value = row
  previewInfo.value = null
  previewOpen.value = true
  previewing.value = true
  previewInfo.value = await model.adminPreview(row)
  previewing.value = false
}

function openEdit(row: MaterialSummary) {
  editTarget.value = row
  void model.loadAdminDepartments()
  editOpen.value = true
}

async function submitEdit(payload: { materialId: string, name: string, categoryId: string, tags: string[], visibility: string, deptIds: string[] }) {
  saving.value = true
  try {
    await model.adminEditMaterial(payload.materialId, {
      name: payload.name,
      categoryId: payload.categoryId || null,
      tags: payload.tags,
      visibility: payload.visibility || undefined,
      deptIds: payload.deptIds,
    })
    editOpen.value = false
    await model.loadAdmin()
  }
  finally {
    saving.value = false
  }
}

function openNewVersion(row: MaterialSummary) {
  newVersionTarget.value = row
  newVersionOpen.value = true
}

async function submitNewVersion(payload: { fileId: string, filename: string, note: string }) {
  if (!newVersionTarget.value) {
    return
  }
  saving.value = true
  try {
    await model.adminUploadNewVersion(newVersionTarget.value.id, payload.fileId, payload.filename, payload.note, false)
    newVersionOpen.value = false
  }
  finally {
    saving.value = false
  }
}

function openShare(row: MaterialSummary) {
  shareTarget.value = row
  shareOpen.value = true
}

/** 打开子物料抽屉：立即拉取该物料的子物料列表（管理端可见任意用户物料的子物料）。 */
function openItems(row: MaterialSummary) {
  itemsTarget.value = row
  itemsActiveId.value = ''
  itemsOpen.value = true
  void model.loadItems(row.id, true)
}

/**
 * 抽屉内的子物料预览：复用插件的登录态预览端点（管理端走 /admin），
 * 以 model.preview 为数据源；层级设在抽屉之上，避免被抽屉遮住。
 */
function previewItem(payload: { item: MaterialItemView, version: number }) {
  if (!itemsTarget.value) {
    return
  }
  itemPreviewLabel.value = `${payload.item.name} · v${payload.version}`
  itemPreviewOpen.value = true
  void model.loadItemPreview(itemsTarget.value.id, payload.item.id, payload.version, true)
}

/** 组合物料没有主文件，主文件预览/下载/新版本对它无意义，只在有主文件时开放。 */
/** 行操作统一收进操作菜单，危险操作保持确认流程 */
const rowActions: ActionMenuItem<MaterialSummary>[] = [
  { label: '预览', icon: 'morph:eye', onClick: row => openPreview(row) },
  { label: '子物料', icon: 'morph:layers', onClick: row => openItems(row) },
  { label: '编辑', icon: 'morph:pencil', onClick: row => openEdit(row) },
  { label: '新版本', icon: 'morph:cloud-upload', visible: row => hasMainFile(row), onClick: row => openNewVersion(row) },
  { label: '分享', icon: 'morph:share', visible: row => hasMainFile(row), onClick: row => openShare(row) },
  { label: '下载', icon: 'morph:download', visible: row => hasMainFile(row), onClick: row => model.adminDownload(row) },
  { label: row => (row.status !== 'ARCHIVED' ? '归档' : '恢复'), icon: 'morph:archive', onClick: row => (row.status !== 'ARCHIVED' ? confirmArchive(row) : model.adminSetStatus(row, 'ACTIVE')) },
  { label: '删除', icon: 'morph:trash-2', danger: true, onClick: row => confirmDelete(row) },
]

function hasMainFile(row: MaterialSummary) {
  return !isBundle(row)
}

function confirmArchive(row: MaterialSummary) {
  confirm.confirm({
    title: '归档物料',
    content: `归档后「${row.name}」将从用户物料库默认列表隐藏，但文件与历史版本保留。`,
    onConfirm: () => model.adminSetStatus(row, 'ARCHIVED'),
  })
}

function confirmDelete(row: MaterialSummary) {
  const scope = hasMainFile(row)
    ? `全部 ${row.currentVersion} 个版本与文件都会删除`
    : `其全部 ${row.itemCount} 个子物料（含各自的版本与文件）都会删除`
  confirm.confirm({
    title: '删除物料',
    content: `确认删除用户 ${row.ownerName || row.ownerId} 的「${row.name}」吗？${scope}，不可恢复。`,
    onConfirm: () => model.adminRemove(row),
  })
}

// ---------- 批量操作 ----------

function selectedIds() {
  return selectedRows.value.map(item => item.id)
}

function openBatchCategory() {
  batchCategoryId.value = ''
  batchCategoryOpen.value = true
}

async function submitBatchCategory() {
  batchSaving.value = true
  try {
    await model.adminBatchCategory(selectedIds(), batchCategoryId.value)
    batchCategoryOpen.value = false
    selectedRows.value = []
    rowSelection.value = {}
  }
  finally {
    batchSaving.value = false
  }
}

function openBatchTags() {
  batchTags.value = []
  batchTagMode.value = 'APPEND'
  batchTagsOpen.value = true
}

async function submitBatchTags() {
  batchSaving.value = true
  try {
    await model.adminBatchTags(selectedIds(), batchTags.value, batchTagMode.value)
    batchTagsOpen.value = false
    selectedRows.value = []
  }
  finally {
    batchSaving.value = false
  }
}

async function batchSetStatus(status: 'ACTIVE' | 'ARCHIVED') {
  await model.adminBatchStatus(selectedIds(), status)
  selectedRows.value = []
}

function confirmBatchDelete() {
  const count = selectedRows.value.length
  confirm.confirm({
    title: '批量删除物料',
    content: `确认删除勾选的 ${count} 个物料吗？全部版本与文件都会删除，不可恢复。`,
    onConfirm: async () => {
      await model.adminBatchDelete(selectedIds())
      selectedRows.value = []
    },
  })
}

onMounted(() => {
  void model.loadCategories()
  void model.loadAdmin()
  void model.loadAdminDepartments()
})
</script>

<template>
  <YdTablePageLayout title="物料管理">
    <template #search>
          <YdListToolbar
            v-model:keyword="model.adminFilters.keyword"
            placeholder="搜索名称或标签"
            @search="model.applyAdminFilters"
            @clear="model.applyAdminFilters"
          >
            <template #filters>
              <YdInput v-model="model.adminFilters.owner" placeholder="归属用户（昵称或 ID）" clearable @keydown.enter="model.applyAdminFilters" @clear="model.applyAdminFilters" />
              <YdSelect v-model="model.adminFilters.type" :options="MATERIAL_TYPES" @change="model.applyAdminFilters" />
              <CategoryPicker v-model="model.adminFilters.categoryId" :categories="model.categories" placeholder="全部分类" @update:model-value="model.applyAdminFilters" />
              <YdSelect v-model="model.adminFilters.status" :options="statusOptions" @change="model.applyAdminFilters" />
            </template>
            <template #actions>
              <YdButton variant="outline" @click="model.applyAdminFilters">查询</YdButton>
            </template>
          </YdListToolbar>
    </template>
    <template #table>
        <div v-if="selectedRows.length" class="mt-2 flex flex-wrap items-center gap-2">
          <span class="text-sm text-secondary-foreground/70">已选 {{ selectedRows.length }} 项</span>
          <YdButton size="sm" variant="outline" @click="openBatchCategory"><YdIcon name="i-ri:folder-transfer-line" />移动分组</YdButton>
          <YdButton size="sm" variant="outline" @click="openBatchTags"><YdIcon name="i-ri:price-tag-3-line" />打标签</YdButton>
          <YdButton size="sm" variant="outline" @click="batchSetStatus('ARCHIVED')">批量归档</YdButton>
          <YdButton size="sm" variant="outline" @click="batchSetStatus('ACTIVE')">批量恢复</YdButton>
          <YdButton size="sm" variant="destructive" @click="confirmBatchDelete">批量删除</YdButton>
        </div>
    <YdLoadingOverlay :loading="model.loading"><YdResponsiveEnhancedTable v-model:row-selection="rowSelection"
      :columns="columns"
      :data="model.adminList"
      row-key="id"
      selectable
      multiple
      table-root-class="rounded-lg overflow-hidden"
      table-class="min-w-[1240px]"
      border stripe column-visibility
      :actions="rowActions"
      empty-text="暂无物料"
      @selection-change="onSelectionChange"
    >
      <template #cell-name="{ row }">
        <div class="flex items-center gap-2">
          <strong>{{ row.original.name }}</strong>
          <YdTag v-if="hasMainFile(row.original)" variant="outline" class="shrink-0">.{{ row.original.ext || '?' }}</YdTag>
          <YdTag v-else variant="secondary" class="shrink-0">组合物料</YdTag>
        </div>
      </template>
      <template #cell-type="{ row }">
        <YdTag variant="secondary">{{ hasMainFile(row.original) ? row.original.typeLabel : '组合物料' }}</YdTag>
      </template>
      <template #cell-owner="{ row }">{{ row.original.ownerName || row.original.ownerId }}</template>
      <template #cell-categoryName="{ row }">{{ row.original.categoryName || '-' }}</template>
      <template #cell-size="{ row }">{{ hasMainFile(row.original) ? formatSize(row.original.size) : '-' }}</template>
      <template #cell-version="{ row }">
        <span v-if="hasMainFile(row.original)">v{{ row.original.currentVersion }}</span>
        <span v-else>{{ row.original.itemCount }} 项</span>
      </template>
      <template #cell-status="{ row }">
        <span class="yd-status-tone" :data-tone="row.original.status === 'ARCHIVED' ? 'neutral' : 'success'">{{ row.original.status === 'ARCHIVED' ? '已归档' : '正常' }}</span>
      </template>
      <template #cell-updatedAt="{ row }">{{ formatTime(row.original.updatedAt) }}</template>
    </YdResponsiveEnhancedTable></YdLoadingOverlay>
    </template>
    <template #footer>
  <YdPagination
  v-model:page="model.adminPager.page"
  v-model:size="model.adminPager.size"
  :total="model.adminPager.total"
  class="mt-3"
  @page-change="model.loadAdmin"
  @size-change="model.applyAdminFilters"
  />
    </template>
  </YdTablePageLayout>
</template>

