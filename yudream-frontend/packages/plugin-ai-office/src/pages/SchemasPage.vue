<script setup lang="ts">
import type { ActionMenuItem, TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { OfficeSchema, SchemaFieldDef } from '../types'
import { YdAlert, YdButton, YdIcon, YdInput, YdLoadingOverlay, YdModal, YdResponsiveEnhancedTable, YdSelect, YdSwitch, YdTablePageLayout, YdTag, useYdModal, useYdToast } from "@yudream/components"
import { onMounted, ref } from 'vue'
import { createOfficeAdminApi, errorMessage } from '../api/office-api'
import { formatTime } from '../types'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()

const toast = useYdToast()
const confirm = useYdModal()
const api = createOfficeAdminApi(props.sdk)

const loading = ref(false)
const saving = ref(false)
const schemas = ref<OfficeSchema[]>([])

const roleLabel = (role: string) => (role === 'profile' ? '站点档案' : '业务对象')

const columns: TableColumn<OfficeSchema>[] = [
  { accessorKey: 'code', header: '编码', width: 180 },
  { accessorKey: 'name', header: '名称', width: 160 },
  { id: 'role', header: '角色', width: 100, enableSorting: false },
  { id: 'fields', header: '字段', minWidth: 320, enableSorting: false },
  { accessorKey: 'updatedAt', header: '更新时间', width: 170 },
]

const rowActions: ActionMenuItem<OfficeSchema>[] = [
  { label: '编辑', icon: 'morph:pencil', visible: row => !row.builtin, onClick: row => openEdit(row) },
  { label: '删除', icon: 'morph:trash-2', danger: true, visible: row => !row.builtin, onClick: row => remove(row) },
]

const fieldTypes = [
  { label: '短文本', value: 'text' },
  { label: '长文本', value: 'longtext' },
  { label: '数字', value: 'number' },
  { label: '金额（分）', value: 'money' },
  { label: '日期', value: 'date' },
  { label: '单选', value: 'select' },
  { label: '附件', value: 'attachments' },
]

onMounted(load)

async function load() {
  loading.value = true
  try {
    schemas.value = await api.schemas()
  }
  catch (cause) {
    toast.error(errorMessage(cause, '数据模型加载失败'))
  }
  finally {
    loading.value = false
  }
}

// ---------- 编辑 ----------

const modalVisible = ref(false)
const editing = ref<OfficeSchema | null>(null)
const form = ref<{ id: string, code: string, name: string, role: string, fields: SchemaFieldDef[] }>({
  id: '', code: '', name: '', role: 'entity', fields: [],
})

const roleOptions = [
  { label: '业务对象（可多实例，作为生成目标/周期聚合）', value: 'entity' },
  { label: '站点档案（单实例，映射用 ctx:org.字段）', value: 'profile' },
]

function openCreate() {
  editing.value = null
  form.value = { id: '', code: '', name: '', role: 'entity', fields: [] }
  modalVisible.value = true
}

function openEdit(schema: OfficeSchema) {
  editing.value = schema
  form.value = {
    id: schema.id,
    code: schema.code,
    name: schema.name,
    role: schema.role,
    fields: schema.fields.map(field => ({ ...field, options: [...field.options] })),
  }
  modalVisible.value = true
}

function addField() {
  form.value.fields = [...form.value.fields, { key: '', label: '', type: 'text', required: false, options: [] }]
}

function removeField(index: number) {
  form.value.fields = form.value.fields.filter((_, i) => i !== index)
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
      code: form.value.code.trim(),
      name: form.value.name.trim(),
      role: form.value.role as 'profile' | 'entity',
      builtin: false,
      fields: form.value.fields.filter(field => field.key.trim() && field.label.trim()),
    }
    if (editing.value) {
      await api.updateSchema(editing.value.id, payload)
    }
    else {
      await api.saveSchema(payload)
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

function remove(schema: OfficeSchema) {
  confirm.confirm({
    title: '删除数据模型',
    content: `确认删除「${schema.name}」吗？已引用它的文档类型需自行调整。`,
    onConfirm: async () => {
      try {
        await api.deleteSchema(schema.id)
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
  <YdTablePageLayout title="数据模型" :show-search="false">
    <template #actions>
      <YdButton @click="openCreate">
        <YdIcon name="i-ri:add-line" />新建数据模型
      </YdButton>
    </template>
    <template #table>
      <YdAlert v-if="false" variant="destructive" />
      <YdLoadingOverlay :loading="loading">
        <YdResponsiveEnhancedTable
          :columns="columns"
          :data="schemas"
          row-key="id"
          table-root-class="rounded-lg overflow-hidden"
          table-class="min-w-[900px]"
          border
          stripe
          column-visibility
          :actions="rowActions"
          empty-text="还没有数据模型，可在「办公设置」导入预设包获得内置结构"
        >
          <template #cell-code="{ row }">
            <strong>{{ row.original.code }}</strong>
            <YdTag v-if="row.original.builtin" variant="outline">内置</YdTag>
          </template>
          <template #cell-role="{ row }">{{ roleLabel(row.original.role) }}</template>
          <template #cell-fields="{ row }">
            <span class="line-clamp-2 text-xs">{{ row.original.fields.map(field => `${field.label}(${field.key})`).join('、') }}</span>
          </template>
          <template #cell-updatedAt="{ row }">{{ formatTime(row.original.updatedAt) }}</template>
        </YdResponsiveEnhancedTable>
      </YdLoadingOverlay>
    </template>
  </YdTablePageLayout>

  <YdModal
    v-model="modalVisible"
    :title="editing ? '编辑数据模型' : '新建数据模型'"
    confirm-button-text="保存"
    :confirm-button-loading="saving"
    :show-cancel-button="!saving"
    cancel-button-text="取消"
    @confirm="save"
  >
    <div class="flex flex-col gap-3">
      <div class="grid grid-cols-[repeat(2,minmax(0,1fr))] gap-3">
        <label class="flex flex-col gap-1 text-sm"><span class="text-secondary-foreground/80">编码（字母开头，可用中划线）</span>
          <YdInput v-model="form.code" :disabled="!!editing" placeholder="activity" />
        </label>
        <label class="flex flex-col gap-1 text-sm"><span class="text-secondary-foreground/80">名称</span>
          <YdInput v-model="form.name" placeholder="活动" />
        </label>
        <label class="flex flex-col gap-1 text-sm"><span class="text-secondary-foreground/80">角色</span>
          <YdSelect v-model="form.role" :options="roleOptions" />
        </label>
      </div>
      <div class="flex items-center justify-between">
        <span class="text-sm text-secondary-foreground/80">字段定义</span>
        <YdButton size="sm" variant="outline" @click="addField">
          <YdIcon name="i-ri:add-line" />加字段
        </YdButton>
      </div>
      <div v-for="(field, index) in form.fields" :key="index" class="rounded border p-2">
        <div class="grid grid-cols-[repeat(2,minmax(0,1fr))] gap-2">
          <label class="flex flex-col gap-1 text-sm"><span class="text-secondary-foreground/80">key（字母开头）</span>
            <YdInput v-model="field.key" placeholder="title" />
          </label>
          <label class="flex flex-col gap-1 text-sm"><span class="text-secondary-foreground/80">名称</span>
            <YdInput v-model="field.label" placeholder="活动名称" />
          </label>
        </div>
        <div class="mt-2 grid grid-cols-[repeat(2,minmax(0,1fr))] gap-2">
          <label class="flex flex-col gap-1 text-sm"><span class="text-secondary-foreground/80">类型</span>
            <YdSelect v-model="field.type" :options="fieldTypes" />
          </label>
          <label class="flex flex-col gap-1 text-sm"><span class="text-secondary-foreground/80">选项（逗号分隔）</span>
            <YdInput
              :model-value="field.options.join('，')"
              @update:model-value="field.options = String($event).split(/[,，]/).map(item => item.trim()).filter(Boolean)"
            />
          </label>
        </div>
        <div class="mt-2 flex items-center gap-3">
          <span class="text-sm">必填</span>
          <YdSwitch v-model="field.required" size="sm" />
          <YdButton size="sm" variant="outline" class="ml-auto" @click="removeField(index)">移除</YdButton>
        </div>
      </div>
    </div>
  </YdModal>
</template>
