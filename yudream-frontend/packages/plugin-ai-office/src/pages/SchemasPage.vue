<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { OfficeSchema, SchemaFieldDef } from '../types'
import { YdButton, YdIcon, YdInput, YdModal, YdPageHeader, YdPageMain, YdSelect, YdSwitch, useYdModal, useYdToast } from '@yudream/components'
import { onMounted, ref } from 'vue'
import { createOfficeAdminApi, errorMessage } from '../api/office-api'
import { formatTime } from '../types'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()

const toast = useYdToast()
const confirm = useYdModal()
const api = createOfficeAdminApi(props.sdk)

const schemas = ref<OfficeSchema[]>([])
const loading = ref(false)
const drawerVisible = ref(false)
const saving = ref(false)
const editing = ref<OfficeSchema | null>(null)
const form = ref({
  id: '',
  code: '',
  name: '',
  fields: [] as SchemaFieldDef[],
})

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

function openCreate() {
  editing.value = null
  form.value = { id: '', code: '', name: '', fields: [] }
  drawerVisible.value = true
}

function openEdit(schema: OfficeSchema) {
  if (schema.builtin) {
    toast.warning('内置数据模型不可编辑')
    return
  }
  editing.value = schema
  form.value = {
    id: schema.id,
    code: schema.code,
    name: schema.name,
    fields: schema.fields.map(field => ({ ...field, options: [...field.options] })),
  }
  drawerVisible.value = true
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
    drawerVisible.value = false
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
  <section class="office-page">
    <YdPageHeader title="数据模型">
      <YdButton @click="openCreate">
        <YdIcon name="i-ri:add-line" />新建数据模型
      </YdButton>
    </YdPageHeader>
    <YdPageMain>
      <table v-if="schemas.length" class="office-preview-table">
        <thead>
          <tr><th>编码</th><th>名称</th><th>字段</th><th>更新时间</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="schema in schemas" :key="schema.id">
            <td>{{ schema.code }} <YdTag v-if="schema.builtin" variant="outline">内置</YdTag></td>
            <td>{{ schema.name }}</td>
            <td>{{ schema.fields.map(field => `${field.label}(${field.key})`).join('、') }}</td>
            <td>{{ formatTime(schema.updatedAt) }}</td>
            <td class="flex gap-2">
              <YdButton v-if="!schema.builtin" size="sm" variant="outline" @click="openEdit(schema)">编辑</YdButton>
              <YdButton v-if="!schema.builtin" size="sm" variant="outline" @click="remove(schema)">删除</YdButton>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else-if="!loading" class="text-sm text-muted-foreground">
        还没有数据模型，可在「办公设置」导入预设包获得内置结构
      </p>
    </YdPageMain>

    <YdModal
      v-model="drawerVisible"
      :title="editing ? '编辑数据模型' : '新建数据模型'"
      confirm-button-text="保存"
      :confirm-button-loading="saving"
      :show-cancel-button="!saving"
      cancel-button-text="取消"
      @confirm="save"
    >
      <div class="flex flex-col gap-3">
        <div class="grid grid-cols-2 gap-2">
          <label class="flex flex-col gap-1 text-sm">
            <span class="text-secondary-foreground/80">编码（小写字母开头）</span>
            <YdInput v-model="form.code" :disabled="!!editing" placeholder="activity" />
          </label>
          <label class="flex flex-col gap-1 text-sm">
            <span class="text-secondary-foreground/80">名称</span>
            <YdInput v-model="form.name" placeholder="活动" />
          </label>
        </div>
        <div class="flex items-center justify-between">
          <span class="text-sm text-secondary-foreground/80">字段定义</span>
          <YdButton size="sm" variant="outline" @click="addField">
            <YdIcon name="i-ri:add-line" />加字段
          </YdButton>
        </div>
        <div v-for="(field, index) in form.fields" :key="index" class="rounded border p-2">
          <div class="grid grid-cols-2 gap-2">
            <label class="flex flex-col gap-1 text-sm">
              <span>key（小写）</span>
              <YdInput v-model="field.key" placeholder="title" />
            </label>
            <label class="flex flex-col gap-1 text-sm">
              <span>名称</span>
              <YdInput v-model="field.label" placeholder="活动名称" />
            </label>
            <label class="flex flex-col gap-1 text-sm">
              <span>类型</span>
              <YdSelect v-model="field.type" :options="fieldTypes" />
            </label>
            <label v-if="field.type === 'select'" class="flex flex-col gap-1 text-sm">
              <span>选项（逗号分隔）</span>
              <YdInput :model-value="field.options.join('，')" @update:model-value="field.options = String($event).split(/[,，]/).map(item => item.trim()).filter(Boolean)" />
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
  </section>
</template>
