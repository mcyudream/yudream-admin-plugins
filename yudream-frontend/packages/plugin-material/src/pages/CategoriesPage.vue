<script setup lang="ts">
import type { ActionMenuItem, TableColumn, YdTablePageAction } from '@yudream/components'
import type { MaterialPluginModel } from '../composables/useMaterialPlugin'
import type { CategoryView } from '../types'
import { YdForm, YdFormItem, YdInput, YdLoadingOverlay, YdModal, YdNumberField, YdResponsiveEnhancedTable, YdTablePageLayout, useYdModal } from '@yudream/components'
import { onMounted, ref } from 'vue'
import { formatTime } from '../types'

const props = defineProps<{ model: MaterialPluginModel }>()
const model = props.model
const confirm = useYdModal()

const editOpen = ref(false)
const editTarget = ref<CategoryView | null>(null)
const editName = ref('')
const editSort = ref(0)
const saving = ref(false)

const columns: TableColumn<CategoryView>[] = [
  { accessorKey: 'name', header: '分类名称', minWidth: 180 },
  { accessorKey: 'sort', header: '排序', width: 90 },
  { id: 'materials', header: '物料数', width: 100 },
  { accessorKey: 'createdAt', header: '创建时间', width: 180 },
]

/** 行操作统一收进操作菜单，删除保持确认流程 */
const rowActions: ActionMenuItem<CategoryView>[] = [
  { label: '编辑', icon: 'morph:pencil', onClick: row => openEdit(row) },
  { label: '删除', icon: 'morph:trash-2', danger: true, onClick: row => confirmDelete(row) },
]

const pageActions: YdTablePageAction[] = [
  { label: '新建分类', icon: 'morph:plus', onClick: openCreate },
]

function openCreate() {
  editTarget.value = null
  editName.value = ''
  editSort.value = 0
  editOpen.value = true
}

function openEdit(row: CategoryView) {
  editTarget.value = row
  editName.value = row.name
  editSort.value = Number(row.sort) || 0
  editOpen.value = true
}

async function submit() {
  if (!editName.value.trim()) {
    return
  }
  saving.value = true
  try {
    await model.saveCategory(editTarget.value?.id ?? null, editName.value.trim(), editSort.value)
    editOpen.value = false
  }
  finally {
    saving.value = false
  }
}

function confirmDelete(row: CategoryView) {
  confirm.confirm({
    title: '删除分类',
    content: `确认删除分类「${row.name}」吗？该分类下有 ${row.materials} 个物料时必须先把它们移出。`,
    onConfirm: () => model.removeCategory(row),
  })
}

onMounted(() => model.loadCategories())
</script>

<template>
  <YdTablePageLayout title="物料分类" :show-search="false" :actions="pageActions">
    <template #table>
      <YdLoadingOverlay :loading="model.loading">
        <YdResponsiveEnhancedTable
          :columns="columns"
          :data="model.categories"
          row-key="id"
          table-root-class="rounded-lg overflow-hidden"
          table-class="min-w-[620px]"
          border
          stripe
          column-visibility
          :actions="rowActions"
          empty-text="还没有分类"
        >
          <template #cell-materials="{ row }">{{ row.original.materials }}</template>
          <template #cell-createdAt="{ row }">{{ formatTime(row.original.createdAt) }}</template>
        </YdResponsiveEnhancedTable>
      </YdLoadingOverlay>
    </template>
  </YdTablePageLayout>

  <YdModal v-model="editOpen" :title="editTarget ? '编辑分类' : '新建分类'" :confirm-button-loading="saving" :confirm-button-disabled="!editName.trim()" @confirm="submit">
    <YdForm :model="{ name: editName, sort: editSort }" layout="vertical">
      <div class="flex flex-col gap-4">
        <YdFormItem label="分类名称" required>
          <YdInput v-model="editName" maxlength="30" placeholder="如：宣传物料" />
        </YdFormItem>
        <YdFormItem label="排序" extra="越小越靠前。">
          <YdNumberField v-model="editSort" :min="0" :max="9999" />
        </YdFormItem>
      </div>
    </YdForm>
  </YdModal>
</template>
