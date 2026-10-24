<script setup lang="ts">
import type { ActionMenuItem, TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { OfficeCategory, OfficeSchema } from '../types'
import { YdButton, YdIcon, YdInput, YdLabel, YdLoadingOverlay, YdModal, YdResponsiveEnhancedTable, YdSelect, YdSwitch, YdTablePageLayout, useYdModal, useYdToast } from '@yudream/components'
import { computed, onMounted, ref } from 'vue'
import { createOfficeAdminApi, errorMessage } from '../api/office-api'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()

const toast = useYdToast()
const confirm = useYdModal()
const api = createOfficeAdminApi(props.sdk)

const loading = ref(false)
const saving = ref(false)
const categories = ref<OfficeCategory[]>([])
const schemas = ref<OfficeSchema[]>([])

const directionLabel = (direction: string) => direction === 'income' ? '收入' : '支出'

const columns: TableColumn<OfficeCategory>[] = [
  { id: 'direction', header: '方向', width: 90, enableSorting: false },
  { accessorKey: 'code', header: '编码', width: 170 },
  { accessorKey: 'name', header: '名称', width: 180 },
  { id: 'fields', header: '登记字段', minWidth: 240, enableSorting: false },
  { id: 'schemaCode', header: '扩展字段模型', width: 150, enableSorting: false },
  { accessorKey: 'sort', header: '排序', width: 80 },
  { id: 'enabled', header: '启用', width: 80, enableSorting: false },
]

const rowActions: ActionMenuItem<OfficeCategory>[] = [
  { label: '编辑', icon: 'morph:pencil', onClick: row => openEdit(row) },
  { label: '删除', icon: 'morph:trash-2', danger: true, onClick: row => remove(row) },
]

// 编辑形态
const modalVisible = ref(false)
const editing = ref<OfficeCategory | null>(null)
const form = ref({
  id: '',
  direction: 'expense',
  code: '',
  name: '',
  fields: '',
  schemaCode: '',
  sort: 0,
  enabled: true,
})

const directionOptions = [
  { label: '支出', value: 'expense' },
  { label: '收入', value: 'income' },
]

const entitySchemaOptions = computed(() => [
  { label: '（无扩展字段）', value: '' },
  ...schemas.value
    .filter(schema => schema.role !== 'profile')
    .map(schema => ({ label: `${schema.name}（${schema.code}）`, value: schema.code })),
])

onMounted(load)

async function load() {
  loading.value = true
  try {
    const [categoryData, schemaData] = await Promise.all([api.categories(), api.schemas()])
    categories.value = categoryData
    schemas.value = schemaData
  }
  catch (cause) {
    toast.error(errorMessage(cause, '类别加载失败'))
  }
  finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  form.value = { id: '', direction: 'expense', code: '', name: '', fields: '', schemaCode: '', sort: 0, enabled: true }
  modalVisible.value = true
}

function openEdit(category: OfficeCategory) {
  editing.value = category
  form.value = {
    id: category.id,
    direction: category.direction,
    code: category.code,
    name: category.name,
    fields: category.fields.join('，'),
    schemaCode: category.schemaCode,
    sort: category.sort,
    enabled: category.enabled,
  }
  modalVisible.value = true
}

async function save() {
  if (!form.value.code.trim() || !form.value.name.trim()) {
    toast.warning('请填写编码与名称')
    return
  }
  saving.value = true
  try {
    const payload = {
      id: form.value.id || undefined,
      direction: form.value.direction as 'income' | 'expense',
      code: form.value.code.trim(),
      name: form.value.name.trim(),
      fields: form.value.fields.split(/[,，]/).map(item => item.trim()).filter(Boolean),
      schemaCode: form.value.schemaCode,
      sort: form.value.sort,
      enabled: form.value.enabled,
    }
    if (editing.value) {
      await api.updateCategory(editing.value.id, payload)
    }
    else {
      await api.saveCategory(payload)
    }
    toast.success('已保存')
    modalVisible.value = false
    await load()
  }
  catch (cause) {
    toast.error(errorMessage(cause, '保存失败'))
  }
  finally {
    saving.value = false
  }
}

function remove(category: OfficeCategory) {
  confirm.confirm({
    title: '删除经费类别',
    content: `确认删除「${category.name}」吗？已有记录保留，但不再可选。`,
    onConfirm: async () => {
      try {
        await api.deleteCategory(category.id)
        toast.success('已删除')
        await load()
      }
      catch (cause) {
        toast.error(errorMessage(cause, '删除失败'))
      }
    },
  })
}
</script>

<template>
  <YdTablePageLayout title="经费类别" :show-search="false">
    <template #stats>
      <div class="text-sm text-muted-foreground">
        类别是站点自定的字典（数据驱动）：登记字段列声明该类别展开哪些基础字段；「扩展字段模型」可挂一个数据模型，登记时按模型字段填写并进入映射（item.data.字段key）。适合不同站点自定「项目编号」「经手部门」等维度。
      </div>
    </template>
    <template #actions>
      <YdButton @click="openCreate">
        <YdIcon name="i-ri:add-line" />新建类别
      </YdButton>
    </template>
    <template #table>
      <YdLoadingOverlay :loading="loading">
        <YdResponsiveEnhancedTable
          :columns="columns"
          :data="categories"
          row-key="id"
          table-root-class="rounded-lg overflow-hidden"
          table-class="min-w-[1040px]"
          border
          stripe
          column-visibility
          :actions="rowActions"
          empty-text="还没有经费类别，可导入预设包或手工新建"
        >
          <template #cell-direction="{ row }">{{ directionLabel(row.original.direction) }}</template>
          <template #cell-fields="{ row }">
            <span class="line-clamp-2 text-xs">{{ row.original.fields.join('、') || '仅基础字段' }}</span>
          </template>
          <template #cell-schemaCode="{ row }">{{ row.original.schemaCode || '—' }}</template>
          <template #cell-enabled="{ row }">
            <YdTag v-if="false" variant="outline" />
            {{ row.original.enabled ? '启用' : '停用' }}
          </template>
        </YdResponsiveEnhancedTable>
      </YdLoadingOverlay>
    </template>
  </YdTablePageLayout>

  <YdModal
    v-model="modalVisible"
    :title="editing ? '编辑类别' : '新建类别'"
    confirm-button-text="保存"
    :confirm-button-loading="saving"
    :show-cancel-button="!saving"
    cancel-button-text="取消"
    @confirm="save"
  >
    <div class="flex flex-col gap-3">
      <div class="grid grid-cols-[repeat(2,minmax(0,1fr))] gap-3">
        <YdLabel label="方向" required>
          <YdSelect v-model="form.direction" :options="directionOptions" />
        </YdLabel>
        <YdLabel label="编码" required>
          <YdInput v-model="form.code" :disabled="!!editing" placeholder="校级活动" />
        </YdLabel>
      </div>
      <YdLabel label="名称" required>
        <YdInput v-model="form.name" placeholder="校级活动支出" />
      </YdLabel>
      <YdLabel label="登记字段（逗号分隔：quantity,unitPriceCents,buyerName,purpose,fundSource 的子集）">
        <YdInput v-model="form.fields" placeholder="quantity,unitPriceCents,buyerName,purpose,fundSource" />
      </YdLabel>
      <YdLabel label="扩展字段模型（可选：按数据模型增加自定义维度，映射用 item.data.字段key 取值）">
        <YdSelect v-model="form.schemaCode" :options="entitySchemaOptions" />
      </YdLabel>
      <div class="grid grid-cols-[repeat(2,minmax(0,1fr))] gap-3">
        <YdLabel label="排序">
          <YdInput v-model="form.sort" />
        </YdLabel>
        <div class="flex items-center gap-2 text-sm">
          <span>启用</span>
          <YdSwitch v-model="form.enabled" size="sm" />
        </div>
      </div>
    </div>
  </YdModal>
</template>
