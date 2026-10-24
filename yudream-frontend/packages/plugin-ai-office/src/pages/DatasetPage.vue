<script setup lang="ts">
import type { ActionMenuItem, SearchConfig, SearchFieldConfig, SearchOptionsSource, TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { DatasetRecord, OfficeSchema } from '../types'
import { YdAlert, YdButton, YdDynamicSearchForm, YdInput, YdLabel, YdLoadingOverlay, YdModal, YdPagination, YdResponsiveEnhancedTable, YdSelect, YdTablePageLayout, YdTextarea, useYdModal, useYdToast } from '@yudream/components'
import { computed, onMounted, ref } from 'vue'
import { createOfficeAdminApi, errorMessage } from '../api/office-api'
import { formatTime, moneyText } from '../types'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()

const toast = useYdToast()
const confirm = useYdModal()
const api = createOfficeAdminApi(props.sdk)

const schemas = ref<OfficeSchema[]>([])
const schemaCode = ref('')
const records = ref<DatasetRecord[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const pageSizes = [10, 20, 50]
const loading = ref(false)
const error = ref('')

const activeSchema = computed(() => schemas.value.find(schema => schema.code === schemaCode.value))
const columns = computed<TableColumn<DatasetRecord>[]>(() => [
  ...(activeSchema.value?.fields.filter(field => field.type !== 'attachments').map(field => ({
    id: field.key,
    header: field.label,
    minWidth: 140,
    enableSorting: false,
  })) ?? []),
  { id: '_createdAt', header: '创建时间', width: 170, enableSorting: false },
])

const searchQuery = ref<Record<string, any>>({ schemaCode: '' })

const searchConfig: SearchConfig = {
  fields: [
    {
      fieldName: 'schemaCode',
      label: '数据模型',
      controlType: 'SELECT' as SearchFieldConfig['controlType'],
      order: 1,
      showWhenFolded: true,
      width: '220px',
    } as SearchFieldConfig,
  ],
}

const schemaOptions = () => schemas.value.map(schema => ({ label: schema.name, value: schema.code }))

const optionsSources: SearchOptionsSource[] = [
  { id: 'schemaCodeOptions', loader: schemaOptions },
]

const rowActions: ActionMenuItem<DatasetRecord>[] = [
  { label: '编辑', icon: 'morph:pencil', onClick: row => openEdit(row) },
  { label: '删除', icon: 'morph:trash-2', danger: true, onClick: row => remove(row) },
]

function applySearch() {
  schemaCode.value = String(searchQuery.value.schemaCode ?? '')
  page.value = 1
  load()
}

function resetSearch() {
  schemaCode.value = schemas.value.length ? schemas.value[0].code : ''
  searchQuery.value = { schemaCode: schemaCode.value }
  page.value = 1
  load()
}

onMounted(async () => {
  try {
    schemas.value = await api.schemas()
    if (schemas.value.length) {
      schemaCode.value = schemas.value[0].code
      searchQuery.value = { schemaCode: schemaCode.value }
    }
  }
  catch (cause) {
    toast.error(errorMessage(cause, '数据模型加载失败'))
  }
  await load()
})

async function load() {
  if (!schemaCode.value) {
    records.value = []
    total.value = 0
    return
  }
  loading.value = true
  error.value = ''
  try {
    const data = await api.dataset(schemaCode.value, page.value, size.value)
    records.value = data.records ?? []
    total.value = data.total ?? 0
  }
  catch (cause) {
    error.value = errorMessage(cause, '数据记录加载失败')
  }
  finally {
    loading.value = false
  }
}

// ---------- 编辑 ----------

const modalVisible = ref(false)
const editing = ref<DatasetRecord | null>(null)
const formData = ref<Record<string, string>>({})
const saving = ref(false)

function openCreate() {
  if (!activeSchema.value) {
    toast.warning('请先选择数据模型')
    return
  }
  editing.value = null
  formData.value = Object.fromEntries(activeSchema.value.fields.map(field => [field.key, '']))
  modalVisible.value = true
}

function openEdit(record: DatasetRecord) {
  editing.value = record
  formData.value = Object.fromEntries(
    (activeSchema.value?.fields ?? []).map((field) => {
      const value = record.data[field.key]
      return [field.key, value == null ? '' : String(value)]
    }))
  modalVisible.value = true
}

async function submit() {
  if (!activeSchema.value) {
    return
  }
  const missing = activeSchema.value.fields
    .filter(field => field.required && !String(formData.value[field.key] ?? '').trim())
    .map(field => field.label)
  if (missing.length) {
    toast.warning(`必填字段未填写：${missing.join('、')}`)
    return
  }
  saving.value = true
  try {
    if (editing.value) {
      await api.updateDataset(editing.value.id, { data: { ...formData.value } })
    }
    else {
      await api.saveDataset({ schemaCode: schemaCode.value, data: { ...formData.value } })
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

function remove(record: DatasetRecord) {
  confirm.confirm({
    title: '删除数据记录',
    content: '确认删除该记录吗？',
    onConfirm: async () => {
      try {
        await api.deleteDataset(record.id)
        toast.success('已删除')
        await load()
      }
      catch (cause) {
        toast.error(errorMessage(cause, '删除失败'))
      }
    },
  })
}

function displayValue(record: DatasetRecord, key: string) {
  const field = activeSchema.value?.fields.find(item => item.key === key)
  const value = record.data[key]
  if (value == null || value === '') {
    return '—'
  }
  if (field?.type === 'money') {
    return `¥${moneyText(Number(value))}`
  }
  return String(value)
}
</script>

<template>
  <YdTablePageLayout title="数据集">
    <template #search="{ fold }">
      <YdDynamicSearchForm
        v-model="searchQuery"
        :config="searchConfig"
        :fold="fold"
        :options-sources="optionsSources"
        @search="applySearch"
        @reset="resetSearch"
      />
    </template>
    <template #actions>
      <YdButton :disabled="!schemaCode" @click="openCreate">
        新建记录
      </YdButton>
    </template>
    <template #table>
      <YdAlert v-if="error" class="mb-3" variant="destructive" title="加载失败" :description="error" />
      <YdLoadingOverlay :loading="loading">
        <YdResponsiveEnhancedTable
          :columns="columns"
          :data="records"
          row-key="id"
          table-root-class="rounded-lg overflow-hidden"
          table-class="min-w-[900px]"
          border
          stripe
          column-visibility
          :actions="rowActions"
          empty-text="还没有数据记录"
        >
          <template v-for="field in activeSchema?.fields.filter(item => item.type !== 'attachments') ?? []" :key="field.key" #[`cell-${field.key}`]="{ row }">
            <span class="truncate" :title="displayValue(row.original, field.key)">{{ displayValue(row.original, field.key) }}</span>
          </template>
          <template #cell-_createdAt="{ row }">{{ formatTime(row.original.createdAt) }}</template>
        </YdResponsiveEnhancedTable>
      </YdLoadingOverlay>
    </template>
    <template #footer>
      <YdPagination
        v-model:page="page"
        v-model:size="size"
        :total="total"
        :sizes="pageSizes"
        @page-change="load"
        @size-change="load"
      />
    </template>
  </YdTablePageLayout>

  <YdModal
    v-model="modalVisible"
    :title="editing ? '编辑记录' : '新建记录'"
    confirm-button-text="保存"
    :confirm-button-loading="saving"
    :show-cancel-button="!saving"
    cancel-button-text="取消"
    @confirm="submit"
  >
    <div class="flex flex-col gap-3">
      <template v-for="field in activeSchema?.fields ?? []" :key="field.key">
        <YdLabel :label="`${field.label}（${field.key}）${field.required ? ' *' : ''}`">
          <YdTextarea v-if="field.type === 'longtext'" v-model="formData[field.key]" :rows="3" />
          <YdSelect
            v-else-if="field.type === 'select' && field.options.length"
            v-model="formData[field.key]"
            :options="field.options.map(option => ({ label: option, value: option }))"
          />
          <YdInput v-else v-model="formData[field.key]" :placeholder="field.type" />
        </YdLabel>
      </template>
      <p v-if="!activeSchema?.fields.length" class="text-sm text-muted-foreground">
        该数据模型没有字段定义。
      </p>
    </div>
  </YdModal>
</template>
