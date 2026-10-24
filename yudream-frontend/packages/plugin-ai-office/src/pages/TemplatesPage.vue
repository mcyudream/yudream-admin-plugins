<script setup lang="ts">
import type { ActionMenuItem, TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { DocTypeMapping, OfficeDocType, WordTemplateOption } from '../types'
import { YdButton, YdIcon, YdInput, YdLabel, YdLoadingOverlay, YdModal, YdResponsiveEnhancedTable, YdSelect, YdTablePageLayout, YdTag, YdTextarea, useYdModal, useYdToast } from '@yudream/components'
import { computed, onMounted, ref } from 'vue'
import { createOfficeAdminApi, errorMessage } from '../api/office-api'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()

const toast = useYdToast()
const confirm = useYdModal()
const api = createOfficeAdminApi(props.sdk)

const loading = ref(false)
const saving = ref(false)
const docTypes = ref<OfficeDocType[]>([])
const wordTemplates = ref<WordTemplateOption[]>([])

const stageOptions = [
  { label: '事前（报备/策划）', value: 'before' },
  { label: '事后（总结/展示）', value: 'after' },
  { label: '通用', value: 'general' },
]

const stageLabel = (stage: string) => stageOptions.find(option => option.value === stage)?.label ?? stage

const columns: TableColumn<OfficeDocType>[] = [
  { accessorKey: 'code', header: '编码', width: 190 },
  { accessorKey: 'name', header: '名称', width: 160 },
  { id: 'stage', header: '阶段', width: 140, enableSorting: false },
  { id: 'template', header: 'Word 模板', width: 200, enableSorting: false },
  { id: 'mappings', header: '映射数', width: 90, enableSorting: false },
  { id: 'fileNamePattern', header: '产物命名', minWidth: 220, enableSorting: false },
]

const rowActions: ActionMenuItem<OfficeDocType>[] = [
  { label: '编辑', icon: 'morph:pencil', onClick: row => openEdit(row) },
  { label: '删除', icon: 'morph:trash-2', danger: true, onClick: row => remove(row) },
]

onMounted(load)

async function load() {
  loading.value = true
  try {
    docTypes.value = await api.docTypes()
    wordTemplates.value = await api.wordTemplates().catch(() => [] as WordTemplateOption[])
  }
  catch (cause) {
    toast.error(errorMessage(cause, '文档类型加载失败'))
  }
  finally {
    loading.value = false
  }
}

const templateOptions = computed(() => [
  { label: '（未绑定，稍后选择）', value: '' },
  ...wordTemplates.value.map(item => ({ label: `${item.name}（${item.code}）`, value: item.code })),
])

// ---------- 编辑 ----------

const modalVisible = ref(false)
const editing = ref<OfficeDocType | null>(null)
const form = ref({
  id: '',
  code: '',
  name: '',
  stage: 'general',
  wordTemplateCode: '',
  fileNamePattern: '',
  mappings: [] as DocTypeMapping[],
})

function openCreate() {
  editing.value = null
  form.value = { id: '', code: '', name: '', stage: 'general', wordTemplateCode: '', fileNamePattern: '', mappings: [] }
  modalVisible.value = true
}

function openEdit(docType: OfficeDocType) {
  editing.value = docType
  form.value = {
    id: docType.id,
    code: docType.code,
    name: docType.name,
    stage: docType.stage,
    wordTemplateCode: docType.wordTemplateCode,
    fileNamePattern: docType.fileNamePattern,
    mappings: docType.mappings.map(mapping => ({ ...mapping, itemMapping: { ...mapping.itemMapping } })),
  }
  modalVisible.value = true
}

function addMapping() {
  form.value.mappings = [...form.value.mappings, { var: '', source: 'ctx:', transform: '', itemMapping: {} }]
}

function removeMapping(index: number) {
  form.value.mappings = form.value.mappings.filter((_, i) => i !== index)
}

function tryParseItemMapping(mapping: DocTypeMapping, raw: string) {
  try {
    mapping.itemMapping = JSON.parse(raw || '{}') as Record<string, string>
  }
  catch {
    // 输入未完成时保持原值，保存前再校验
  }
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
      stage: form.value.stage as 'before' | 'after' | 'general',
      wordTemplateCode: form.value.wordTemplateCode,
      schemaCodes: [],
      mappings: form.value.mappings.filter(mapping => mapping.var.trim() && mapping.source.trim()),
      fileNamePattern: form.value.fileNamePattern.trim(),
      enabled: true,
    }
    if (editing.value) {
      await api.updateDocType(editing.value.id, payload)
    }
    else {
      await api.saveDocType(payload)
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

function remove(docType: OfficeDocType) {
  confirm.confirm({
    title: '删除文档类型',
    content: `确认删除「${docType.name}」吗？历史生成任务保留。`,
    onConfirm: async () => {
      try {
        await api.deleteDocType(docType.id)
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
  <YdTablePageLayout title="文档类型" :show-search="false">
    <template #stats>
      <div class="text-sm text-muted-foreground">
        文档类型绑定宿主 Word 模板与占位符映射。数据源：<code>ctx:点路径</code> ｜ <code>list:点路径</code>（表格循环）｜ <code>image:点路径</code>（嵌图）｜ <code>ai:提示词编码</code> ｜ <code>text:字面量</code>；变换：<code>date:yyyy年MM月dd日</code> ｜ <code>money</code> ｜ <code>join:、</code> ｜ <code>image:宽px</code>
      </div>
    </template>
    <template #actions>
      <YdButton @click="openCreate">
        <YdIcon name="i-ri:add-line" />新建文档类型
      </YdButton>
    </template>
    <template #table>
      <YdLoadingOverlay :loading="loading">
        <YdResponsiveEnhancedTable
          :columns="columns"
          :data="docTypes"
          row-key="id"
          table-root-class="rounded-lg overflow-hidden"
          table-class="min-w-[1100px]"
          border
          stripe
          column-visibility
          :actions="rowActions"
          empty-text="还没有文档类型，可在「办公设置」导入预设包"
        >
          <template #cell-stage="{ row }">
            <YdTag variant="outline">{{ stageLabel(row.original.stage) }}</YdTag>
          </template>
          <template #cell-template="{ row }">{{ row.original.wordTemplateCode || '—' }}</template>
          <template #cell-mappings="{ row }">{{ row.original.mappings.length }}</template>
          <template #cell-fileNamePattern="{ row }">
            <span class="truncate" :title="row.original.fileNamePattern">{{ row.original.fileNamePattern || '—' }}</span>
          </template>
        </YdResponsiveEnhancedTable>
      </YdLoadingOverlay>
    </template>
  </YdTablePageLayout>

  <YdModal
    v-model="modalVisible"
    :title="editing ? '编辑文档类型' : '新建文档类型'"
    confirm-button-text="保存"
    :confirm-button-loading="saving"
    :show-cancel-button="!saving"
    cancel-button-text="取消"
    @confirm="save"
  >
    <div class="flex flex-col gap-3">
      <div class="grid grid-cols-[repeat(2,minmax(0,1fr))] gap-3">
        <YdLabel label="编码" required>
          <YdInput v-model="form.code" :disabled="!!editing" placeholder="activity-summary" />
        </YdLabel>
        <YdLabel label="名称" required>
          <YdInput v-model="form.name" placeholder="活动总结" />
        </YdLabel>
        <YdLabel label="阶段">
          <YdSelect v-model="form.stage" :options="stageOptions" />
        </YdLabel>
        <YdLabel label="宿主 Word 模板">
          <YdSelect v-model="form.wordTemplateCode" :options="templateOptions" />
        </YdLabel>
      </div>
      <YdLabel label="产物文件命名（可用 {target.title}、{org.name}、{month汉字}）">
        <YdInput v-model="form.fileNamePattern" placeholder="“{target.title}”活动总结" />
      </YdLabel>
      <div class="flex items-center justify-between">
        <span class="text-sm text-secondary-foreground/80">占位符映射</span>
        <YdButton size="sm" variant="outline" @click="addMapping">
          <YdIcon name="i-ri:add-line" />加映射
        </YdButton>
      </div>
      <div v-for="(mapping, index) in form.mappings" :key="index" class="rounded border p-2">
        <div class="grid grid-cols-[repeat(3,minmax(0,1fr))] gap-2">
          <YdLabel label="变量（占位符名）" required>
            <YdInput v-model="mapping.var" placeholder="activityName" />
          </YdLabel>
          <YdLabel label="数据源" required>
            <YdInput v-model="mapping.source" placeholder="ctx:target.title" />
          </YdLabel>
          <YdLabel label="变换（选填）">
            <YdInput v-model="mapping.transform" placeholder="date:yyyy年MM月dd日" />
          </YdLabel>
        </div>
        <YdLabel label="列表项映射（仅 list: 数据源，JSON：{&quot;行变量&quot;:&quot;item.字段&quot;}；图片列写 image:item.fileKey）">
          <YdTextarea
            :model-value="JSON.stringify(mapping.itemMapping)"
            :rows="2"
            @update:model-value="tryParseItemMapping(mapping, String($event))"
          />
        </YdLabel>
        <YdButton size="sm" variant="outline" class="mt-2" @click="removeMapping(index)">移除</YdButton>
      </div>
    </div>
  </YdModal>
</template>
