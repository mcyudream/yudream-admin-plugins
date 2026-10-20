<script setup lang="ts">
import { YdLoadingOverlay, YdTablePageLayout } from '@yudream/components'
import type { TableColumn, YdFilePickerInstance, ActionMenuItem } from '@yudream/components'
import type { QuestionBankPluginModel } from '../composables/useQuestionBankPlugin'
import type { ImportResult, QuestionPayload, QuestionView } from '../types'
import { YdButton, YdCheckbox, YdIcon, YdInput, YdModal, YdPagination, YdSelect, YdTag, YdTextarea, YdFilePicker, useYdModal, useYdToast, YdResponsiveEnhancedTable } from '@yudream/components'
import { onMounted, ref, shallowRef } from 'vue'
import { useRouter } from 'vue-router'
import { saveBlob } from '../api/questionbank-api'
import CategorySelect from '../components/CategorySelect.vue'
import { difficultyLabel, formatTime, QUESTION_TYPE_OPTIONS } from '../composables/utils'

const props = defineProps<{ model: QuestionBankPluginModel }>()
const model = props.model
const router = useRouter()
const confirm = useYdModal()
const toast = useYdToast()

// 根布局为 YdTablePageLayout，导入/AI 导入弹窗是其兄弟节点（多根模板），宿主传入的多余属性无需透传
defineOptions({ inheritAttrs: false })

const selectedRows = shallowRef<QuestionView[]>([])
const rowSelection = shallowRef<Record<string, boolean>>({})
const exporting = ref(false)
const importOpen = ref(false)
const importing = ref(false)
const importResult = ref<ImportResult | null>(null)
const importFileName = ref('')
const importFilePicker = ref<YdFilePickerInstance | null>(null)

const aiImportOpen = ref(false)
const aiImportText = ref('')
const aiImportFileName = ref('')
const aiImportFilePicker = ref<YdFilePickerInstance | null>(null)
const aiStreaming = ref(false)
const aiLogs = shallowRef<{ level?: string, message: string }[]>([])
const aiQuestions = shallowRef<{ seq: number, ok: boolean, summary: string, reason?: string }[]>([])
const aiStreamText = ref('')
const aiDone = ref<{ imported: number, failed: number, total: number, error?: boolean } | null>(null)
let aiAbort: AbortController | null = null

function openAiImport() {
  stopAiStream()
  aiImportText.value = ''
  aiImportFileName.value = ''
  aiLogs.value = []
  aiQuestions.value = []
  aiStreamText.value = ''
  aiDone.value = null
  aiImportOpen.value = true
}

function stopAiStream() {
  aiAbort?.abort()
  aiAbort = null
  aiStreaming.value = false
}

async function onAiImportFileSelect(files: File[]) {
  const file = files[0]
  if (!file) return
  try {
    aiImportText.value = await file.text()
    aiImportFileName.value = file.name
  }
  catch {
    toast.error('读取文件失败，请重试')
  }
  finally {
    aiImportFilePicker.value?.reset()
  }
}

/** 启动 AI 导入任务并订阅 SSE 事件流：分段解析日志 + 逐题入库结果实时推送。 */
async function startAiImport() {
  if (!aiImportText.value.trim()) {
    toast.warning('请先粘贴题目内容或上传 Markdown 文件')
    return
  }
  aiLogs.value = []
  aiQuestions.value = []
  aiStreamText.value = ''
  aiDone.value = null
  aiStreaming.value = true
  try {
    const { jobId } = await model.api.aiImportStart(aiImportText.value)
    await streamAiImport(jobId)
  }
  catch (error) {
    aiStreaming.value = false
    toast.error(error instanceof Error ? error.message : 'AI 导入启动失败')
  }
}

async function streamAiImport(jobId: string) {
  stopAiStream()
  aiStreaming.value = true
  const controller = new AbortController()
  aiAbort = controller
  try {
    const token = localStorage.getItem('token')
    const response = await fetch(model.api.aiImportEventsUrl(jobId), {
      signal: controller.signal,
      headers: { Accept: 'text/event-stream', ...(token ? { Authorization: token } : {}) },
    })
    if (!response.ok || !response.body) {
      throw new Error(`事件流连接失败（${response.status}）`)
    }
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    while (!controller.signal.aborted) {
      const next = await reader.read()
      if (next.done) {
        break
      }
      buffer += decoder.decode(next.value, { stream: true })
      const chunks = buffer.split(/\r?\n\r?\n/)
      buffer = chunks.pop() || ''
      for (const chunk of chunks) {
        const text = chunk.split(/\r?\n/).filter(line => line.startsWith('data:')).map(line => line.slice(5).trimStart()).join('\n')
        if (!text) {
          continue
        }
        handleAiEvent(JSON.parse(text))
      }
    }
  }
  catch (error) {
    if (!controller.signal.aborted) {
      aiLogs.value = [...aiLogs.value, { level: 'ERROR', message: error instanceof Error ? error.message : '事件流中断' }]
    }
  }
  finally {
    if (aiAbort === controller) {
      aiAbort = null
      aiStreaming.value = false
    }
  }
}

function handleAiEvent(event: { type?: string, message?: string, level?: string, text?: string, seq?: number, ok?: boolean, summary?: string, reason?: string, imported?: number, failed?: number, total?: number, error?: boolean }) {
  if (event.type === 'log') {
    aiLogs.value = [...aiLogs.value, { level: event.level, message: event.message ?? '' }]
  }
  else if (event.type === 'delta') {
    aiStreamText.value += event.text ?? ''
  }
  else if (event.type === 'question') {
    aiQuestions.value = [...aiQuestions.value, { seq: event.seq ?? 0, ok: !!event.ok, summary: event.summary ?? '', reason: event.reason }]
  }
  else if (event.type === 'done') {
    aiDone.value = { imported: event.imported ?? 0, failed: event.failed ?? 0, total: event.total ?? 0, error: event.error }
    void model.loadAdminQuestions()
    void model.loadAdminTags()
    void model.loadAdminCategories()
    if (event.failed) {
      toast.warning(`导入完成：成功 ${event.imported ?? 0} 道，失败 ${event.failed ?? 0} 道`)
    }
    else {
      toast.success(`导入完成：成功 ${event.imported ?? 0} 道题`)
    }
  }
}

const statusOptions = [
  { label: '全部状态', value: '' },
  { label: '启用', value: 'ENABLED' },
  { label: '停用', value: 'DISABLED' },
]

const difficultyOptions = [
  { label: '全部难度', value: '' },
  { label: '★ 最简单', value: '1' },
  { label: '★★', value: '2' },
  { label: '★★★', value: '3' },
  { label: '★★★★', value: '4' },
  { label: '★★★★★ 最难', value: '5' },
]

const typeOptions = [{ label: '全部题型', value: '' }, ...QUESTION_TYPE_OPTIONS]

const tagOptions = ref<{ label: string, value: string }[]>([])

const columns: TableColumn<QuestionView>[] = [
  { type: 'selection', fixed: 'left', width: 46 },
  { id: 'content', header: '题干', minWidth: 260, fixed: 'left' },
  { id: 'type', header: '题型', width: 90 },
  { id: 'category', header: '分类', width: 110 },
  { id: 'tags', header: '标签', minWidth: 140 },
  { id: 'difficulty', header: '难度', width: 110 },
  { id: 'status', header: '状态', width: 80 },
  { accessorKey: 'updatedAt', header: '更新时间', width: 170 },
  { id: 'operation', header: '操作', width: 150 },
]

/** 题干在列表里只展示纯文本摘要，去掉 markdown 标记防干扰。 */
function plainContent(row: QuestionView) {
  const text = row.content
    .replace(/!\[[^\]]*\]\([^)]*\)/g, '[图片]')
    .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/[#>*`_~-]+/g, '')
    .replace(/\s+/g, ' ')
    .trim()
  return text.length > 80 ? `${text.slice(0, 80)}…` : text
}

function onSelectionChange(rows: QuestionView[]) {
  selectedRows.value = rows
}


function openCreate() {
  void router.push({ path: '/platform/plugins/questionbank/admin/questions/edit' })
}

function openEdit(row: QuestionView) {
  void router.push({ path: '/platform/plugins/questionbank/admin/questions/edit', query: { id: row.id } })
}

function confirmDelete(row: QuestionView) {
  confirm.confirm({
    title: '删除题目',
    content: `确认删除这道题吗？「${plainContent(row) || row.id}」删除后不可恢复，已产生的练习记录不受影响。`,
    onConfirm: () => model.removeQuestion(row),
  })
}

function confirmBatchDelete() {
  const rows = selectedRows.value
  if (!rows.length) {
    toast.warning('请先勾选要删除的题目')
    return
  }
  confirm.confirm({
    title: '批量删除题目',
    content: `确认删除选中的 ${rows.length} 道题吗？删除后不可恢复，已产生的练习记录不受影响。`,
    onConfirm: () => model.batchRemoveQuestions(rows.map(row => row.id)),
  })
}

async function doExport(format: 'json' | 'markdown') {
  exporting.value = true
  try {
    const ids = selectedRows.value.map(row => row.id)
    const blob = await model.api.exportQuestions(format, model.adminFilters.categoryId, model.adminFilters.tag, model.adminFilters.type, ids)
    saveBlob(blob.data, blob.headers, format === 'json' ? '题库导出.json' : '题库导出.md')
    if (ids.length)
      toast.success(`已导出勾选的 ${ids.length} 道题`)
  }
  catch (error) {
    toast.error(error instanceof Error ? error.message : '导出失败')
  }
  finally {
    exporting.value = false
  }
}

function openImport() {
  importResult.value = null
  importFileName.value = ''
  importOpen.value = true
}

/** 目录选择同款规避：CI 禁 .vue 原生 input，文件选择用 JS 动态创建。 */
async function onImportFileSelect(files: File[]) {
  const file = files[0]
  if (!file) return
  importFileName.value = file.name
  importing.value = true
  try {
    const text = await file.text()
    const parsed = JSON.parse(text) as { questions?: QuestionPayload[] } | QuestionPayload[]
    const questions = Array.isArray(parsed) ? parsed : parsed.questions
    if (!Array.isArray(questions) || !questions.length) {
      toast.warning('文件里没有可导入的题目（应为 { questions: [...] } JSON）')
      return
    }
    const result = await model.api.importQuestions(questions)
    importResult.value = result
    if (result.failures.length) toast.warning(`已导入 ${result.imported} 道，${result.failures.length} 道失败`)
    else toast.success(`已导入 ${result.imported} 道题`)
    await model.loadAdminQuestions()
    await model.loadAdminTags()
    await model.loadAdminCategories()
  }
  catch (error) {
    toast.error(error instanceof SyntaxError ? 'JSON 解析失败，请检查文件格式' : (error instanceof Error ? error.message : '导入失败'))
  }
  finally {
    importing.value = false
    importFilePicker.value?.reset()
  }
}

onMounted(async () => {
  await model.loadAdminCategories()
  await model.loadAdminTags()
  tagOptions.value = [{ label: '全部标签', value: '' }, ...model.adminTags.map(tag => ({ label: `${tag.name}（${tag.count}）`, value: tag.name }))]
  await model.loadAdminQuestions()
})

/** 行操作统一收进操作菜单，删除保持确认流程 */
const rowActions: ActionMenuItem<unknown>[] = [
  { label: '编辑', icon: 'morph:pencil', onClick: row => openEdit(row as never) },
  { label: '删除', icon: 'morph:trash-2', danger: true, onClick: row => confirmDelete(row as never) },
]

</script>

<template>
  <YdTablePageLayout title="题目管理">
    <template #search>
      <!-- 筛选与受控批量删除收进搜索卡：桌面表格与移动端卡片共用同一套 -->
      <div class="qb-toolbar">
        <YdInput v-model="model.adminFilters.keyword" placeholder="搜索题干或标签" clearable @keydown.enter="model.applyAdminFilters" @clear="model.applyAdminFilters" />
        <CategorySelect v-model="model.adminFilters.categoryId" :categories="model.adminCategories" empty-label="全部分类" @update:model-value="model.applyAdminFilters" />
        <YdSelect v-model="model.adminFilters.tag" :options="tagOptions" @change="model.applyAdminFilters" />
        <YdSelect v-model="model.adminFilters.type" :options="typeOptions" @change="model.applyAdminFilters" />
        <YdSelect v-model="model.adminFilters.status" :options="statusOptions" @change="model.applyAdminFilters" />
        <YdButton variant="outline" @click="model.applyAdminFilters"><YdIcon name="i-ri:search-line" />查询</YdButton>
        <YdButton variant="destructive" :disabled="!selectedRows.length" @click="confirmBatchDelete">
          批量删除{{ selectedRows.length ? `（${selectedRows.length}）` : '' }}
        </YdButton>
      </div>
    </template>
    <template #actions>
      <YdButton variant="outline" :disabled="importing" @click="openImport"><YdIcon name="i-ri:upload-2-line" />导入</YdButton>
    <YdButton variant="outline" @click="openAiImport"><YdIcon name="i-ri:sparkling-line" />AI 导入</YdButton>
    <YdButton variant="outline" :loading="exporting" @click="doExport('json')"><YdIcon name="i-ri:download-2-line" />导出 JSON{{ selectedRows.length ? `（${selectedRows.length}）` : '（全部）' }}</YdButton>
    <YdButton variant="outline" :loading="exporting" @click="doExport('markdown')"><YdIcon name="i-ri:markdown-line" />导出 Markdown{{ selectedRows.length ? `（${selectedRows.length}）` : '（全部）' }}</YdButton>
    <YdButton @click="openCreate"><YdIcon name="i-ri:add-line" />新建题目</YdButton>
    </template>
    <template #table>
<YdLoadingOverlay :loading="model.loading"><YdResponsiveEnhancedTable
        :actions="rowActions"
 v-model:row-selection="rowSelection"
        
        :columns="columns"
        :data="model.adminQuestions"
        row-key="id"
        selectable
        multiple
        table-root-class="qb-table-scroll"
        table-class="qb-table-w1100"
        border stripe column-visibility
        empty-text="暂无题目，点击右上角新建或导入"
        @selection-change="onSelectionChange"
      >
        <template #cell-content="{ row }">
          <span class="qb-question-content-cell" :title="plainContent(row.original)">{{ plainContent(row.original) || '（空题干）' }}</span>
        </template>
        <template #cell-type="{ row }"><YdTag variant="secondary">{{ row.original.typeLabel }}</YdTag></template>
        <template #cell-category="{ row }">{{ row.original.categoryName || '-' }}</template>
        <template #cell-tags="{ row }">
          <div class="flex flex-wrap gap-1">
            <YdTag v-for="tag in row.original.tags" :key="tag" variant="outline">{{ tag }}</YdTag>
            <span v-if="!row.original.tags.length" class="qb-muted">-</span>
          </div>
        </template>
        <template #cell-difficulty="{ row }"><span style="color: var(--color-warning-6, #ff7d00)">{{ difficultyLabel(row.original.difficulty) }}</span></template>
        <template #cell-status="{ row }">
          <YdTag :variant="row.original.status === 'DISABLED' ? 'outline' : 'default'">{{ row.original.status === 'DISABLED' ? '停用' : '启用' }}</YdTag>
        </template>
        <template #cell-updatedAt="{ row }">{{ formatTime(row.original.updatedAt) }}</template>
      <template #card="{ row, selected, selectDisabled, toggleSelected }">
            <YdCard class="w-full">
              <div class="qb-mobile-card">
                <div class="qb-mobile-card-head"><YdCheckbox :model-value="selected" :disabled="selectDisabled" @change="value => toggleSelected(value === true)" /><div class="qb-mobile-card-title">{{ plainContent(row) || '（空题干）' }}</div></div>
                <div class="qb-mobile-card-meta"><YdTag variant="secondary">{{ row.typeLabel }}</YdTag><span>{{ row.categoryName || '未分类' }}</span><span style="color: var(--color-warning-6, #ff7d00)">{{ difficultyLabel(row.difficulty) }}</span><YdTag :variant="row.status === 'DISABLED' ? 'outline' : 'default'">{{ row.status === 'DISABLED' ? '停用' : '启用' }}</YdTag><span>{{ formatTime(row.updatedAt) }}</span></div>
                <div v-if="row.tags.length" class="flex flex-wrap gap-1"><YdTag v-for="tag in row.tags" :key="tag" variant="outline">{{ tag }}</YdTag></div>
                <div class="qb-mobile-card-actions"><YdButton size="sm" variant="outline" @click="openEdit(row)">编辑</YdButton><YdButton size="sm" variant="destructive" @click="confirmDelete(row)">删除</YdButton></div>
              </div>
            </YdCard>
          </template>
          </YdResponsiveEnhancedTable></YdLoadingOverlay>
    </template>
    <template #footer>
      <YdPagination
      v-model:page="model.adminPager.page"
      v-model:size="model.adminPager.size"
      :total="model.adminPager.total"
      @page-change="model.loadAdminQuestions"
      @size-change="model.applyAdminFilters"
    />
    </template>
  </YdTablePageLayout>

  <!-- 导入 / AI 导入弹窗：布局组件只渲染具名插槽，弹窗作为兄弟节点存在 -->
  <YdModal v-model="importOpen" title="导入题目" :show-confirm-button="false" cancel-button-text="关闭">
      <div class="flex flex-col gap-3 text-sm">
        <p class="qb-muted">
          选择 JSON 文件导入，格式为 <code>{ "questions": [...] }</code> 或题目数组；字段与导出 JSON 一致。
          分类按名称自动复用或创建，单次最多 500 道。
        </p>
        <div class="flex items-center gap-2">
          <YdFilePicker ref="importFilePicker" accept=".json,application/json" aria-label="选择 JSON 文件" :disabled="importing" @select="onImportFileSelect"><template #default="{ open, triggerAttrs }"><YdButton v-bind="triggerAttrs" variant="outline" :loading="importing" @click="open"><YdIcon name="i-ri:file-add-line" />选择 JSON 文件</YdButton></template></YdFilePicker>
          <span v-if="importFileName" class="qb-muted">{{ importFileName }}</span>
        </div>
        <template v-if="importResult">
          <YdTag :variant="importResult.failures.length ? 'outline' : 'default'">
            已导入 {{ importResult.imported }} 道<template v-if="importResult.failures.length">，{{ importResult.failures.length }} 道失败</template>
          </YdTag>
          <div v-if="importResult.failures.length" class="flex flex-col gap-1">
            <div v-for="failure in importResult.failures" :key="failure.index" class="text-secondary-foreground/80">
              第 {{ failure.index }} 道：{{ failure.reason }}
            </div>
          </div>
        </template>
      </div>
    </YdModal>
    <YdModal v-model="aiImportOpen" title="AI 一键导入题目" :show-confirm-button="false" cancel-button-text="关闭" @update:model-value="!$event && stopAiStream()">
      <div class="flex flex-col gap-3 text-sm">
        <p class="qb-muted">
          粘贴从别处复制的题目文本（可多道混合），或上传 Markdown 文件；AI 流式识别并<strong>逐题调用创建工具直接入库</strong>，
          实时输出、每题成败都展示在下方。单次最多 20000 字、50 道题。
        </p>
        <YdTextarea
          v-model="aiImportText"
          :rows="8"
          placeholder="在此粘贴题目内容…"
          :disabled="aiStreaming"
        />
        <div class="flex items-center gap-2">
          <YdFilePicker ref="aiImportFilePicker" accept=".md,.markdown,.txt,text/markdown,text/plain" aria-label="上传 Markdown 文件" :disabled="aiStreaming" @select="onAiImportFileSelect">
            <template #default="{ open, triggerAttrs }">
              <YdButton v-bind="triggerAttrs" variant="outline" :disabled="aiStreaming" @click="open">
                <YdIcon name="i-ri:markdown-line" />上传 Markdown 文件
              </YdButton>
            </template>
          </YdFilePicker>
          <span v-if="aiImportFileName" class="qb-muted">{{ aiImportFileName }}</span>
          <YdButton class="ml-auto" :loading="aiStreaming" :disabled="!!aiDone" @click="startAiImport">
            <YdIcon name="i-ri:sparkling-line" />{{ aiLogs.length ? '重新导入' : '开始导入' }}
          </YdButton>
        </div>
        <div v-if="aiStreamText" class="rounded-md border p-2">
          <div class="qb-muted mb-1 flex items-center gap-1 text-xs">
            <YdIcon name="i-ri:robot-2-line" />AI 实时输出
            <YdIcon v-if="aiStreaming" name="i-ri:loader-4-line" class="animate-spin" />
          </div>
          <pre class="qb-muted max-h-40 overflow-y-auto whitespace-pre-wrap break-all text-xs">{{ aiStreamText }}</pre>
        </div>
        <template v-if="aiLogs.length || aiQuestions.length">
          <div class="flex max-h-72 flex-col gap-1 overflow-y-auto rounded-md border p-2">
            <div v-for="(log, index) in aiLogs" :key="`log-${index}`" class="qb-muted text-xs" :class="{ 'text-destructive': log.level === 'ERROR', 'text-warning-6': log.level === 'WARN' }">
              {{ log.message }}
            </div>
            <div v-for="question in aiQuestions" :key="question.seq" class="flex items-start gap-2 border-t pt-1">
              <YdIcon :name="question.ok ? 'i-ri:check-line' : 'i-ri:close-line'" :class="question.ok ? 'text-success-6' : 'text-destructive'" />
              <div class="min-w-0 flex-1">
                <div class="truncate" :title="question.summary">第 {{ question.seq }} 道：{{ question.summary || '（空题干）' }}</div>
                <div v-if="!question.ok" class="text-destructive text-xs">{{ question.reason }}</div>
              </div>
            </div>
          </div>
          <YdTag v-if="aiDone" :variant="aiDone.failed ? 'outline' : 'default'">
            导入完成：成功 {{ aiDone.imported }} 道<template v-if="aiDone.failed">，失败 {{ aiDone.failed }} 道</template>
          </YdTag>
          <p v-else-if="aiStreaming" class="qb-muted text-xs">正在导入，可关闭弹窗稍后回来查看题目列表（任务在后台继续）。</p>
        </template>
      </div>
  </YdModal>
</template>
