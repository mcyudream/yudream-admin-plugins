<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { DatasetRecord, OfficeSchema, SchemaFieldDef } from '../types'
import { YdButton, YdIcon, YdInput, YdModal, YdPageHeader, YdPageMain, YdPagination, YdSelect, useYdModal, useYdToast } from '@yudream/components'
import { computed, onMounted, reactive, ref } from 'vue'
import { createOfficeAdminApi, errorMessage } from '../api/office-api'
import { formatTime } from '../types'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()

const toast = useYdToast()
const confirm = useYdModal()
const api = createOfficeAdminApi(props.sdk)

const schemas = ref<OfficeSchema[]>([])
const schemaCode = ref('')
const records = ref<DatasetRecord[]>([])
const pager = reactive({ page: 1, size: 10, total: 0 })
const loading = ref(false)

const modalVisible = ref(false)
const editing = ref<DatasetRecord | null>(null)
const formData = ref<Record<string, string>>({})
const saving = ref(false)

const activeSchema = computed(() => schemas.value.find(schema => schema.code === schemaCode.value))
const columns = computed(() => activeSchema.value?.fields.filter(field => field.type !== 'attachments') ?? [])
const schemaOptions = computed(() => [
  { label: '选择数据模型', value: '' },
  ...schemas.value.map(schema => ({ label: schema.name, value: schema.code })),
])

onMounted(load)

async function load() {
  loading.value = true
  try {
    schemas.value = await api.schemas()
    if (!schemaCode.value && schemas.value.length) {
      schemaCode.value = schemas.value[0].code
    }
    if (schemaCode.value) {
      const data = await api.dataset(schemaCode.value, pager.page, pager.size)
      records.value = data.records ?? []
      pager.total = data.total ?? 0
    }
    else {
      records.value = []
      pager.total = 0
    }
  }
  catch (cause) {
    toast.error(errorMessage(cause, '数据记录加载失败'))
  }
  finally {
    loading.value = false
  }
}

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

function displayValue(record: DatasetRecord, field: SchemaFieldDef) {
  const value = record.data[field.key]
  if (value == null || value === '') {
    return '—'
  }
  if (field.type === 'money') {
    const cents = Number(value)
    return `¥${Number.isNaN(cents) ? String(value) : `${Math.trunc(cents / 100)}.${String(Math.abs(cents % 100)).padStart(2, '0')}`}`
  }
  return String(value)
}
</script>

<template>
  <section class="office-page">
    <YdPageHeader title="数据集">
      <div class="flex items-center gap-2">
        <YdSelect v-model="schemaCode" class="w-52" :options="schemaOptions" @change="pager.page = 1; load()" />
        <YdButton :disabled="!schemaCode" @click="openCreate">
          <YdIcon name="i-ri:add-line" />新建记录
        </YdButton>
      </div>
    </YdPageHeader>
    <YdPageMain>
      <table v-if="records.length" class="office-preview-table">
        <thead>
          <tr>
            <th v-for="field in columns" :key="field.key">{{ field.label }}</th>
            <th>创建时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="record in records" :key="record.id">
            <td v-for="field in columns" :key="field.key">{{ displayValue(record, field) }}</td>
            <td>{{ formatTime(record.createdAt) }}</td>
            <td class="flex gap-1">
              <YdButton size="sm" variant="outline" @click="openEdit(record)">编辑</YdButton>
              <YdButton size="sm" variant="outline" @click="remove(record)">删除</YdButton>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else-if="!loading" class="text-sm text-muted-foreground">
        {{ schemaCode ? '还没有数据记录' : '请先选择数据模型' }}
      </p>
    </YdPageMain>
    <div v-if="pager.total > pager.size" class="flex justify-end">
      <YdPagination
        v-model:page="pager.page"
        v-model:size="pager.size"
        :total="pager.total"
        @page-change="load"
        @size-change="load"
      />
    </div>

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
        <label v-for="field in activeSchema?.fields ?? []" :key="field.key" class="flex flex-col gap-1 text-sm">
          <span class="text-secondary-foreground/80">{{ field.label }}（{{ field.key }}）<span v-if="field.required" class="text-red-500">*</span></span>
          <YdTextarea
            v-if="field.type === 'longtext'"
            v-model="formData[field.key]"
            :rows="3"
          />
          <YdSelect
            v-else-if="field.type === 'select' && field.options.length"
            v-model="formData[field.key]"
            :options="field.options.map(option => ({ label: option, value: option }))"
          />
          <YdInput v-else v-model="formData[field.key]" :placeholder="field.type" />
          <span v-if="field.type === 'select' && field.options.length" class="text-xs text-muted-foreground">
            可选：{{ field.options.join(' / ') }}
          </span>
        </label>
      </div>
    </YdModal>
  </section>
</template>
