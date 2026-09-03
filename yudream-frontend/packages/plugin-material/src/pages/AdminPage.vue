<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { MaterialPluginModel } from '../composables/useMaterialPlugin'
import type { MaterialSummary, PreviewInfo } from '../types'
import { FaButton, FaIcon, FaInput, FaModal, FaPageHeader, FaPageMain, FaPagination, FaResponsiveTable, FaSearchBar, FaSelect, FaTag, useFaModal } from '@yudream/components'
import { onMounted, ref } from 'vue'
import CategoryPicker from '../components/CategoryPicker.vue'
import PreviewFrame from '../components/PreviewFrame.vue'
import { formatSize, formatTime, MATERIAL_TYPES } from '../types'

const props = defineProps<{ model: MaterialPluginModel }>()
const model = props.model
const confirm = useFaModal()

const previewOpen = ref(false)
const previewInfo = ref<PreviewInfo | null>(null)
const previewTarget = ref<MaterialSummary | null>(null)
const previewing = ref(false)

const statusOptions = [
  { label: '全部状态', value: '' },
  { label: '正常使用', value: 'ACTIVE' },
  { label: '已归档', value: 'ARCHIVED' },
]

const columns: TableColumn<MaterialSummary>[] = [
  { accessorKey: 'name', header: '名称', minWidth: 180, fixed: 'left' },
  { id: 'type', header: '类型', width: 100 },
  { id: 'owner', header: '归属用户', width: 120 },
  { accessorKey: 'categoryName', header: '分类', width: 100 },
  { id: 'size', header: '大小', width: 100 },
  { id: 'version', header: '版本', width: 70 },
  { id: 'status', header: '状态', width: 90 },
  { accessorKey: 'updatedAt', header: '更新时间', width: 170 },
  { id: 'operation', header: '操作', width: 300 },
]

async function openPreview(row: MaterialSummary) {
  previewTarget.value = row
  previewInfo.value = null
  previewOpen.value = true
  previewing.value = true
  previewInfo.value = await model.adminPreview(row)
  previewing.value = false
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

onMounted(() => {
  void model.loadCategories()
  void model.loadAdmin()
})
</script>

<template>
  <FaPageHeader title="物料管理" description="跨用户查看全部物料，支持归档、恢复与删除" />
  <FaPageMain>
    <FaResponsiveTable
      v-loading="model.loading"
      :columns="columns"
      :data="model.adminList"
      row-key="id"
      table-root-class="max-w-full overflow-x-auto rounded-lg"
      table-class="min-w-[1240px]"
      border stripe column-visibility
      empty-text="暂无物料"
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
        <div class="flex-center gap-2">
          <FaButton size="sm" variant="outline" @click="openPreview(row.original)"><FaIcon name="i-ri:eye-line" />预览</FaButton>
          <FaButton size="sm" variant="outline" @click="model.adminDownload(row.original)"><FaIcon name="i-ri:download-line" />下载</FaButton>
          <FaButton v-if="row.original.status !== 'ARCHIVED'" size="sm" variant="outline" @click="confirmArchive(row.original)">归档</FaButton>
          <FaButton v-else size="sm" variant="outline" @click="model.adminSetStatus(row.original, 'ACTIVE')">恢复</FaButton>
          <FaButton size="sm" variant="destructive" @click="confirmDelete(row.original)">删除</FaButton>
        </div>
      </template>
      <template #card="{ row }">
        <div class="flex flex-col gap-3">
          <div class="flex items-center justify-between gap-2">
            <strong class="text-base">{{ row.name }}</strong>
            <FaTag :variant="row.status === 'ARCHIVED' ? 'outline' : 'default'">{{ row.status === 'ARCHIVED' ? '已归档' : '正常' }}</FaTag>
          </div>
          <div class="flex flex-col gap-1 text-sm">
            <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">归属</span><span>{{ row.ownerName || row.ownerId }}</span></div>
            <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">大小 / 版本</span><span>{{ formatSize(row.size) }} · v{{ row.currentVersion }}</span></div>
            <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">更新时间</span><span>{{ formatTime(row.updatedAt) }}</span></div>
          </div>
          <div class="flex flex-wrap gap-2">
            <FaButton size="sm" variant="outline" @click="openPreview(row)">预览</FaButton>
            <FaButton size="sm" variant="outline" @click="model.adminDownload(row)">下载</FaButton>
            <FaButton v-if="row.status !== 'ARCHIVED'" size="sm" variant="outline" @click="confirmArchive(row)">归档</FaButton>
            <FaButton v-else size="sm" variant="outline" @click="model.adminSetStatus(row, 'ACTIVE')">恢复</FaButton>
            <FaButton size="sm" variant="destructive" @click="confirmDelete(row)">删除</FaButton>
          </div>
        </div>
      </template>
    </FaResponsiveTable>
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
  </FaPageMain>
</template>
