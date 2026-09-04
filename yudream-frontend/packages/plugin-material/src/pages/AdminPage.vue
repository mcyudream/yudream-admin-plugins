<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { MaterialPluginModel } from '../composables/useMaterialPlugin'
import type { MaterialSummary, PreviewInfo } from '../types'
import { FaButton, FaIcon, FaInput, FaModal, FaPageHeader, FaPageMain, FaPagination, FaSearchBar, FaSelect, FaTable, FaTag, useFaModal } from '@yudream/components'
import { onMounted, ref, watch } from 'vue'
import CategoryPicker from '../components/CategoryPicker.vue'
import EditMaterialModal from '../components/EditMaterialModal.vue'
import NewVersionModal from '../components/NewVersionModal.vue'
import PreviewFrame from '../components/PreviewFrame.vue'
import ShareModal from '../components/ShareModal.vue'
import TagPicker from '../components/TagPicker.vue'
import { formatSize, formatTime, MATERIAL_TYPES } from '../types'

const props = defineProps<{ model: MaterialPluginModel }>()
const model = props.model
const confirm = useFaModal()

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

// 批量操作：FaResponsiveTable 会吞 selectionChange，批量勾选必须用 FaTable（questionbank 同款坑）
const selectedRows = ref<MaterialSummary[]>([])
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
  { id: 'version', header: '版本', width: 70 },
  { id: 'status', header: '状态', width: 90 },
  { accessorKey: 'updatedAt', header: '更新时间', width: 170 },
  { id: 'operation', header: '操作', width: 430 },
]

/** 翻页/筛选/刷新后行对象重建，勾选项按 id 修剪，避免对已不在当前页的行批量操作 */
watch(() => model.adminList, (list) => {
  if (!selectedRows.value.length) {
    return
  }
  const ids = new Set(list.map(item => item.id))
  selectedRows.value = selectedRows.value.filter(item => ids.has(item.id))
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

function confirmArchive(row: MaterialSummary) {
  confirm.confirm({
    title: '归档物料',
    content: `归档后「${row.name}」将从用户物料库默认列表隐藏，但文件与历史版本保留。`,
    onConfirm: () => model.adminSetStatus(row, 'ARCHIVED'),
  })
}

function confirmDelete(row: MaterialSummary) {
  confirm.confirm({
    title: '删除物料',
    content: `确认删除用户 ${row.ownerName || row.ownerId} 的「${row.name}」吗？全部 ${row.currentVersion} 个版本与文件都会删除，不可恢复。`,
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
  <FaPageHeader title="物料管理" description="跨用户查看全部物料，支持编辑、代传新版本、分享、归档/恢复、删除与批量操作" />
  <FaPageMain>
    <!-- FaResponsiveTable 的事件会落到包装 div 上无法透传（selectionChange 收不到），需要勾选事件的表格直接用 FaTable -->
    <FaTable
      v-loading="model.loading"
      :columns="columns"
      :data="model.adminList"
      row-key="id"
      selectable
      multiple
      table-root-class="max-w-full overflow-x-auto rounded-lg"
      table-class="min-w-[1400px]"
      border stripe column-visibility
      empty-text="暂无物料"
      @selection-change="onSelectionChange"
    >
      <template #toolbar>
        <FaSearchBar class="w-full">
          <div class="material-filter-bar">
            <FaInput v-model="model.adminFilters.keyword" placeholder="搜索名称或标签" clearable @keydown.enter="model.applyAdminFilters" @clear="model.applyAdminFilters" />
            <FaInput v-model="model.adminFilters.owner" placeholder="归属用户（昵称或 ID）" clearable @keydown.enter="model.applyAdminFilters" @clear="model.applyAdminFilters" />
            <FaSelect v-model="model.adminFilters.type" :options="MATERIAL_TYPES" @change="model.applyAdminFilters" />
            <CategoryPicker v-model="model.adminFilters.categoryId" :categories="model.categories" placeholder="全部分类" @update:model-value="model.applyAdminFilters" />
            <FaSelect v-model="model.adminFilters.status" :options="statusOptions" @change="model.applyAdminFilters" />
            <FaButton variant="outline" @click="model.applyAdminFilters"><FaIcon name="i-ri:search-line" />查询</FaButton>
          </div>
        </FaSearchBar>
        <div v-if="selectedRows.length" class="mt-2 flex flex-wrap items-center gap-2">
          <span class="text-sm text-secondary-foreground/70">已选 {{ selectedRows.length }} 项</span>
          <FaButton size="sm" variant="outline" @click="openBatchCategory"><FaIcon name="i-ri:folder-transfer-line" />移动分组</FaButton>
          <FaButton size="sm" variant="outline" @click="openBatchTags"><FaIcon name="i-ri:price-tag-3-line" />打标签</FaButton>
          <FaButton size="sm" variant="outline" @click="batchSetStatus('ARCHIVED')">批量归档</FaButton>
          <FaButton size="sm" variant="outline" @click="batchSetStatus('ACTIVE')">批量恢复</FaButton>
          <FaButton size="sm" variant="destructive" @click="confirmBatchDelete">批量删除</FaButton>
        </div>
      </template>
      <template #cell-name="{ row }">
        <div class="flex items-center gap-2">
          <strong>{{ row.original.name }}</strong>
          <FaTag variant="outline" class="shrink-0">.{{ row.original.ext || '?' }}</FaTag>
        </div>
      </template>
      <template #cell-type="{ row }"><FaTag variant="secondary">{{ row.original.typeLabel }}</FaTag></template>
      <template #cell-owner="{ row }">{{ row.original.ownerName || row.original.ownerId }}</template>
      <template #cell-categoryName="{ row }">{{ row.original.categoryName || '-' }}</template>
      <template #cell-size="{ row }">{{ formatSize(row.original.size) }}</template>
      <template #cell-version="{ row }">v{{ row.original.currentVersion }}</template>
      <template #cell-status="{ row }">
        <FaTag :variant="row.original.status === 'ARCHIVED' ? 'outline' : 'default'">{{ row.original.status === 'ARCHIVED' ? '已归档' : '正常' }}</FaTag>
      </template>
      <template #cell-updatedAt="{ row }">{{ formatTime(row.original.updatedAt) }}</template>
      <template #cell-operation="{ row }">
        <div class="flex-center flex-wrap gap-2">
          <FaButton size="sm" variant="outline" @click="openPreview(row.original)"><FaIcon name="i-ri:eye-line" />预览</FaButton>
          <FaButton size="sm" variant="outline" @click="openEdit(row.original)"><FaIcon name="i-ri:edit-line" />编辑</FaButton>
          <FaButton size="sm" variant="outline" @click="openNewVersion(row.original)"><FaIcon name="i-ri:upload-cloud-2-line" />新版本</FaButton>
          <FaButton size="sm" variant="outline" @click="openShare(row.original)"><FaIcon name="i-ri:share-forward-line" />分享</FaButton>
          <FaButton size="sm" variant="outline" @click="model.adminDownload(row.original)"><FaIcon name="i-ri:download-line" />下载</FaButton>
          <FaButton v-if="row.original.status !== 'ARCHIVED'" size="sm" variant="outline" @click="confirmArchive(row.original)">归档</FaButton>
          <FaButton v-else size="sm" variant="outline" @click="model.adminSetStatus(row.original, 'ACTIVE')">恢复</FaButton>
          <FaButton size="sm" variant="destructive" @click="confirmDelete(row.original)">删除</FaButton>
        </div>
      </template>
    </FaTable>
    <FaPagination
      v-model:page="model.adminPager.page"
      v-model:size="model.adminPager.size"
      :total="model.adminPager.total"
      class="mt-3"
      @page-change="model.loadAdmin"
      @size-change="model.applyAdminFilters"
    />
    <FaModal v-model="previewOpen" :title="previewTarget ? `预览：${previewTarget.name}` : '预览'" maximize :show-confirm-button="false" cancel-button-text="关闭">
      <PreviewFrame
        :sdk="model.sdk"
        :info="previewInfo"
        :loading="previewing"
        :material-type="previewTarget?.type"
        :ext="previewTarget?.ext"
      />
    </FaModal>
    <EditMaterialModal
      v-model="editOpen"
      v-model:target="editTarget"
      :categories="model.categories"
      :tags="model.tags"
      :dept-options="model.adminDeptOptions"
      :saving="saving"
      @submit="submitEdit"
    />
    <NewVersionModal v-model="newVersionOpen" :sdk="model.sdk" :saving="saving" @submit="submitNewVersion" />
    <ShareModal v-model="shareOpen" :material="shareTarget" :model="model" admin />
    <FaModal
      v-model="batchCategoryOpen"
      title="批量移动分组"
      :description="`将勾选的 ${selectedRows.length} 个物料移动到所选分类；不选分类则移出分组`"
      :confirm-button-loading="batchSaving"
      @confirm="submitBatchCategory"
    >
      <CategoryPicker v-model="batchCategoryId" :categories="model.categories" placeholder="选择目标分类（不选为移出分组）" />
    </FaModal>
    <FaModal
      v-model="batchTagsOpen"
      title="批量打标签"
      :description="`对勾选的 ${selectedRows.length} 个物料批量设置标签`"
      :confirm-button-loading="batchSaving"
      :confirm-button-disabled="!batchTags.length"
      @confirm="submitBatchTags"
    >
      <div class="flex flex-col gap-3">
        <label class="flex flex-col gap-1 text-sm">
          <span class="text-secondary-foreground/80">方式</span>
          <FaSelect v-model="batchTagMode" :options="batchTagModeOptions" />
        </label>
        <label class="flex flex-col gap-1 text-sm">
          <span class="text-secondary-foreground/80">标签（最多 8 个）</span>
          <TagPicker v-model="batchTags" :tags="model.tags" />
        </label>
      </div>
    </FaModal>
  </FaPageMain>
</template>
