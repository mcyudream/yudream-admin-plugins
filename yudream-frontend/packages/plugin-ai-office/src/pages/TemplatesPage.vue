<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { DocTypeMapping, OfficeDocType, OfficePrompt, WordTemplateOption } from '../types'
import { YdButton, YdIcon, YdInput, YdModal, YdPageHeader, YdPageMain, YdSelect, YdTag, YdTextarea, useYdModal, useYdToast } from '@yudream/components'
import { computed, onMounted, ref } from 'vue'
import { createOfficeAdminApi, errorMessage } from '../api/office-api'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()

const toast = useYdToast()
const confirm = useYdModal()
const api = createOfficeAdminApi(props.sdk)

const docTypes = ref<OfficeDocType[]>([])
const prompts = ref<OfficePrompt[]>([])
const wordTemplates = ref<WordTemplateOption[]>([])
const loading = ref(false)
const saving = ref(false)

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

const promptModalVisible = ref(false)
const editingPrompt = ref<OfficePrompt | null>(null)
const promptForm = ref({ id: '', code: '', name: '', system: '', user: '' })

const stageOptions = [
  { label: '事前（报备/策划）', value: 'before' },
  { label: '事后（总结/展示）', value: 'after' },
  { label: '通用', value: 'general' },
]

const templateOptions = computed(() => [
  { label: '（未绑定，稍后选择）', value: '' },
  ...wordTemplates.value.map(item => ({ label: `${item.name}（${item.code}）`, value: item.code })),
])

onMounted(load)

async function load() {
  loading.value = true
  try {
    const [types, promptList, templates] = await Promise.all([
      api.docTypes(),
      api.prompts(),
      api.wordTemplates().catch(() => [] as WordTemplateOption[]),
    ])
    docTypes.value = types
    prompts.value = promptList
    wordTemplates.value = templates
  }
  catch (cause) {
    toast.error(errorMessage(cause, '文档类型加载失败'))
  }
  finally {
    loading.value = false
  }
}

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
  form.value.mappings = [...form.value.mappings,
    { var: '', source: 'ctx:', transform: '', itemMapping: {} }]
}

function removeMapping(index: number) {
  form.value.mappings = form.value.mappings.filter((_, i) => i !== index)
}

function tryParseItemMapping(mapping: DocTypeMapping, raw: string) {
  try {
    const parsed = JSON.parse(raw || '{}') as Record<string, string>
    mapping.itemMapping = parsed
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

// ---------- 提示词 ----------

function openPromptCreate() {
  editingPrompt.value = null
  promptForm.value = { id: '', code: '', name: '', system: '', user: '' }
  promptModalVisible.value = true
}

function openPromptEdit(prompt: OfficePrompt) {
  editingPrompt.value = prompt
  promptForm.value = { id: prompt.id, code: prompt.code, name: prompt.name, system: prompt.system, user: prompt.user }
  promptModalVisible.value = true
}

async function savePrompt() {
  if (!promptForm.value.code.trim() || !promptForm.value.name.trim() || !promptForm.value.user.trim()) {
    toast.warning('请填写编码、名称与正文')
    return
  }
  saving.value = true
  try {
    const payload = { ...promptForm.value, id: promptForm.value.id || undefined }
    if (editingPrompt.value) {
      await api.updatePrompt(editingPrompt.value.id, payload)
    }
    else {
      await api.savePrompt(payload)
    }
    toast.success('已保存')
    promptModalVisible.value = false
    await load()
  }
  catch (cause) {
    toast.error(errorMessage(cause, '保存失败'))
  }
  finally {
    saving.value = false
  }
}

function removePrompt(prompt: OfficePrompt) {
  confirm.confirm({
    title: '删除提示词',
    content: `确认删除「${prompt.name}」吗？引用它的映射将无法起草。`,
    onConfirm: async () => {
      try {
        await api.deletePrompt(prompt.id)
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
    <YdPageHeader title="文档类型与映射">
      <YdButton @click="openCreate">
        <YdIcon name="i-ri:add-line" />新建文档类型
      </YdButton>
    </YdPageHeader>
    <YdPageMain>
      <p class="text-sm text-muted-foreground">
        文档类型绑定宿主 Word 模板与占位符映射。数据源写法：ctx:点路径 ｜ list:点路径（表格循环）｜ ai:提示词编码 ｜ text:字面量；变换：date:yyyy年MM月dd日 ｜ money ｜ join:、
      </p>
      <table v-if="docTypes.length" class="office-preview-table mt-3">
        <thead>
          <tr><th>编码</th><th>名称</th><th>阶段</th><th>Word 模板</th><th>映射数</th><th>产物命名</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="docType in docTypes" :key="docType.id">
            <td>{{ docType.code }}</td>
            <td>{{ docType.name }}</td>
            <td><YdTag variant="outline">{{ docType.stage }}</YdTag></td>
            <td>{{ docType.wordTemplateCode || '—' }}</td>
            <td>{{ docType.mappings.length }}</td>
            <td>{{ docType.fileNamePattern || '—' }}</td>
            <td class="flex gap-2">
              <YdButton size="sm" variant="outline" @click="openEdit(docType)">编辑</YdButton>
              <YdButton size="sm" variant="outline" @click="remove(docType)">删除</YdButton>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else-if="!loading" class="text-sm text-muted-foreground">还没有文档类型，可在「办公设置」导入预设包</p>

      <div class="mt-6 flex items-center justify-between">
        <h4 class="font-medium">AI 提示词</h4>
        <YdButton size="sm" variant="outline" @click="openPromptCreate">
          <YdIcon name="i-ri:add-line" />新建提示词
        </YdButton>
      </div>
      <table v-if="prompts.length" class="office-preview-table mt-2">
        <thead>
          <tr><th>编码</th><th>名称</th><th>正文摘要</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="prompt in prompts" :key="prompt.id">
            <td>{{ prompt.code }}</td>
            <td>{{ prompt.name }}</td>
            <td class="max-w-100 truncate">{{ prompt.user }}</td>
            <td class="flex gap-2">
              <YdButton size="sm" variant="outline" @click="openPromptEdit(prompt)">编辑</YdButton>
              <YdButton size="sm" variant="outline" @click="removePrompt(prompt)">删除</YdButton>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else class="mt-2 text-sm text-muted-foreground">还没有提示词</p>
    </YdPageMain>

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
        <div class="grid grid-cols-2 gap-2">
          <label class="flex flex-col gap-1 text-sm">
            <span class="text-secondary-foreground/80">编码</span>
            <YdInput v-model="form.code" :disabled="!!editing" placeholder="activity-summary" />
          </label>
          <label class="flex flex-col gap-1 text-sm">
            <span class="text-secondary-foreground/80">名称</span>
            <YdInput v-model="form.name" placeholder="活动总结" />
          </label>
          <label class="flex flex-col gap-1 text-sm">
            <span class="text-secondary-foreground/80">阶段</span>
            <YdSelect v-model="form.stage" :options="stageOptions" />
          </label>
          <label class="flex flex-col gap-1 text-sm">
            <span class="text-secondary-foreground/80">宿主 Word 模板</span>
            <YdSelect v-model="form.wordTemplateCode" :options="templateOptions" />
          </label>
        </div>
        <label class="flex flex-col gap-1 text-sm">
          <span class="text-secondary-foreground/80">产物文件命名（可用 {target.title}、{org.name}、{month汉字}）</span>
          <YdInput v-model="form.fileNamePattern" placeholder="“{target.title}”活动总结" />
        </label>
        <div class="flex items-center justify-between">
          <span class="text-sm text-secondary-foreground/80">占位符映射</span>
          <YdButton size="sm" variant="outline" @click="addMapping">
            <YdIcon name="i-ri:add-line" />加映射
          </YdButton>
        </div>
        <div v-for="(mapping, index) in form.mappings" :key="index" class="rounded border p-2">
          <div class="grid grid-cols-3 gap-2">
            <label class="flex flex-col gap-1 text-sm">
              <span>变量（占位符名）</span>
              <YdInput v-model="mapping.var" placeholder="activityName" />
            </label>
            <label class="flex flex-col gap-1 text-sm">
              <span>数据源</span>
              <YdInput v-model="mapping.source" placeholder="ctx:target.title" />
            </label>
            <label class="flex flex-col gap-1 text-sm">
              <span>变换（选填）</span>
              <YdInput v-model="mapping.transform" placeholder="date:yyyy年MM月dd日" />
            </label>
          </div>
          <label class="mt-2 flex flex-col gap-1 text-sm">
            <span>列表项映射（仅 list: 数据源，JSON：{"行变量":"item.字段"}）</span>
            <YdTextarea
              :model-value="JSON.stringify(mapping.itemMapping)"
              :rows="2"
              @update:model-value="tryParseItemMapping(mapping, String($event))"
            />
          </label>
          <YdButton size="sm" variant="outline" class="mt-2" @click="removeMapping(index)">移除</YdButton>
        </div>
      </div>
    </YdModal>

    <YdModal
      v-model="promptModalVisible"
      :title="editingPrompt ? '编辑提示词' : '新建提示词'"
      confirm-button-text="保存"
      :confirm-button-loading="saving"
      :show-cancel-button="!saving"
      cancel-button-text="取消"
      @confirm="savePrompt"
    >
      <div class="flex flex-col gap-3">
        <div class="grid grid-cols-2 gap-2">
          <label class="flex flex-col gap-1 text-sm">
            <span class="text-secondary-foreground/80">编码</span>
            <YdInput v-model="promptForm.code" :disabled="!!editingPrompt" placeholder="activity-summary-body" />
          </label>
          <label class="flex flex-col gap-1 text-sm">
            <span class="text-secondary-foreground/80">名称</span>
            <YdInput v-model="promptForm.name" placeholder="活动总结正文起草" />
          </label>
        </div>
        <label class="flex flex-col gap-1 text-sm">
          <span class="text-secondary-foreground/80">System（可空，站点写作规范会自动追加）</span>
          <YdTextarea v-model="promptForm.system" :rows="3" />
        </label>
        <label class="flex flex-col gap-1 text-sm">
          <span class="text-secondary-foreground/80">User 正文（用 {target.title} 等点路径引用上下文）</span>
          <YdTextarea v-model="promptForm.user" :rows="6" />
        </label>
      </div>
    </YdModal>
  </section>
</template>
