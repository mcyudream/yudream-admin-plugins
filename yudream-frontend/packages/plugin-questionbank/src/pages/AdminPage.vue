<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { QuestionBankPluginModel } from '../composables/useQuestionBankPlugin'
import type { ImportResult, QuestionPayload, QuestionView } from '../types'
import { FaButton, FaCard, FaCheckbox, FaIcon, FaInput, FaModal, FaPageHeader, FaPageMain, FaPagination, FaSearchBar, FaSelect, FaTable, FaTag, FaTextarea, useFaModal, useFaToast } from '@yudream/components'
import { onMounted, ref, shallowRef } from 'vue'
import { useRouter } from 'vue-router'
import { saveBlob } from '../api/questionbank-api'
import CategorySelect from '../components/CategorySelect.vue'
import { difficultyLabel, formatTime, QUESTION_TYPE_OPTIONS } from '../composables/utils'

const props = defineProps<{ model: QuestionBankPluginModel }>()
const model = props.model
const router = useRouter()
const confirm = useFaModal()
const toast = useFaToast()

const selectedRows = shallowRef<QuestionView[]>([])
const exporting = ref(false)
const importOpen = ref(false)
const importing = ref(false)
const importResult = ref<ImportResult | null>(null)
const importFileName = ref('')

const aiImportOpen = ref(false)
const aiImportText = ref('')
const aiImportFileName = ref('')
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

/** 与 JSON 导入同款：CI 禁 .vue 原生 input，文件选择用 JS 动态创建。 */
function pickAiImportFile() {
  const input = document.createElement('input')
  input.type = 'file'
  input.accept = '.md,.markdown,.txt,text/markdown,text/plain'
  input.onchange = async () => {
    const file = input.files?.[0]
    if (!file) {
      return
    }
    try {
      aiImportText.value = await file.text()
      aiImportFileName.value = file.name
    }
    catch {
      toast.error('读取文件失败，请重试')
    }
  }
  input.click()
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

function isSelected(row: QuestionView) {
  return selectedRows.value.some(item => item.id === row.id)
}

/** 移动端卡片没有表格勾选事件，用 FaCheckbox 直接维护同一份 selectedRows。 */
function toggleSelect(row: QuestionView, checked: boolean | 'indeterminate' | null | undefined) {
  if (checked === true) {
    if (!isSelected(row)) {
      selectedRows.value = [...selectedRows.value, row]
    }
  }
  else {
    selectedRows.value = selectedRows.value.filter(item => item.id !== row.id)
  }
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
function pickImportFile() {
  const input = document.createElement('input')
  input.type = 'file'
  input.accept = '.json,application/json'
  input.onchange = async () => {
    const file = input.files?.[0]
    if (!file) {
      return
    }
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
      if (result.failures.length) {
        toast.warning(`已导入 ${result.imported} 道，${result.failures.length} 道失败`)
      }
      else {
        toast.success(`已导入 ${result.imported} 道题`)
      }
      await model.loadAdminQuestions()
      await model.loadAdminTags()
      await model.loadAdminCategories()
    }
    catch (error) {
      toast.error(error instanceof SyntaxError ? 'JSON 解析失败，请检查文件格式' : (error instanceof Error ? error.message : '导入失败'))
    }
    finally {
      importing.value = false
    }
  }
  input.click()
}

onMounted(async () => {
  await model.loadAdminCategories()
  await model.loadAdminTags()
  tagOptions.value = [{ label: '全部标签', value: '' }, ...model.adminTags.map(tag => ({ label: `${tag.name}（${tag.count}）`, value: tag.name }))]
  await model.loadAdminQuestions()
})
</script>

<template>
  <FaPageHeader title="题目管理" description="维护题库题目：单选/多选/判断/填空/简答，支持标签、分类、导入导出">
    <FaButton variant="outline" :disabled="importing" @click="openImport"><FaIcon name="i-ri:upload-2-line" />导入</FaButton>
    <FaButton variant="outline" @click="openAiImport"><FaIcon name="i-ri:sparkling-line" />AI 导入</FaButton>
    <FaButton variant="outline" :loading="exporting" @click="doExport('json')"><FaIcon name="i-ri:download-2-line" />导出 JSON{{ selectedRows.length ? `（${selectedRows.length}）` : '（全部）' }}</FaButton>
    <FaButton variant="outline" :loading="exporting" @click="doExport('markdown')"><FaIcon name="i-ri:markdown-line" />导出 Markdown{{ selectedRows.length ? `（${selectedRows.length}）` : '（全部）' }}</FaButton>
    <FaButton @click="openCreate"><FaIcon name="i-ri:add-line" />新建题目</FaButton>
  </FaPageHeader>
  <FaPageMain>
    <!-- 工具栏提到表格外面：移动端卡片列表也要共用同一套筛选与批量删除 -->
    <FaSearchBar class="w-full">
      <div class="qb-toolbar">
        <FaInput v-model="model.adminFilters.keyword" placeholder="搜索题干或标签" clearable @keydown.enter="model.applyAdminFilters" @clear="model.applyAdminFilters" />
        <CategorySelect v-model="model.adminFilters.categoryId" :categories="model.adminCategories" empty-label="全部分类" @update:model-value="model.applyAdminFilters" />
        <FaSelect v-model="model.adminFilters.tag" :options="tagOptions" @change="model.applyAdminFilters" />
        <FaSelect v-model="model.adminFilters.type" :options="typeOptions" @change="model.applyAdminFilters" />
        <FaSelect v-model="model.adminFilters.status" :options="statusOptions" @change="model.applyAdminFilters" />
        <FaButton variant="outline" @click="model.applyAdminFilters"><FaIcon name="i-ri:search-line" />查询</FaButton>
        <FaButton variant="destructive" :disabled="!selectedRows.length" @click="confirmBatchDelete">
          批量删除{{ selectedRows.length ? `（${selectedRows.length}）` : '' }}
        </FaButton>
      </div>
    </FaSearchBar>
    <!-- FaResponsiveTable 的事件会落到包装 div 上无法透传（selectionChange 收不到），需要勾选事件的表格直接用 FaTable -->
    <div class="qb-desktop-only">
      <FaTable
        v-loading="model.loading"
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
        <template #cell-type="{ row }"><FaTag variant="secondary">{{ row.original.typeLabel }}</FaTag></template>
        <template #cell-category="{ row }">{{ row.original.categoryName || '-' }}</template>
        <template #cell-tags="{ row }">
          <div class="flex flex-wrap gap-1">
            <FaTag v-for="tag in row.original.tags" :key="tag" variant="outline">{{ tag }}</FaTag>
            <span v-if="!row.original.tags.length" class="qb-muted">-</span>
          </div>
        </template>
        <template #cell-difficulty="{ row }"><span style="color: var(--color-warning-6, #ff7d00)">{{ difficultyLabel(row.original.difficulty) }}</span></template>
        <template #cell-status="{ row }">
          <FaTag :variant="row.original.status === 'DISABLED' ? 'outline' : 'default'">{{ row.original.status === 'DISABLED' ? '停用' : '启用' }}</FaTag>
        </template>
        <template #cell-updatedAt="{ row }">{{ formatTime(row.original.updatedAt) }}</template>
        <template #cell-operation="{ row }">
          <div class="flex-center gap-2">
            <FaButton size="sm" variant="outline" @click="openEdit(row.original)">编辑</FaButton>
            <FaButton size="sm" variant="destructive" @click="confirmDelete(row.original)">删除</FaButton>
          </div>
        </template>
      </FaTable>
    </div>
    <div class="qb-mobile-only">
      <div v-loading="model.loading" class="qb-mobile-list">
        <FaCard v-for="row in model.adminQuestions" :key="row.id">
          <div class="qb-mobile-card">
            <div class="qb-mobile-card-head">
              <FaCheckbox :model-value="isSelected(row)" @change="toggleSelect(row, $event)" />
              <div class="qb-mobile-card-title">{{ plainContent(row) || '（空题干）' }}</div>
            </div>
            <div class="qb-mobile-card-meta">
              <FaTag variant="secondary">{{ row.typeLabel }}</FaTag>
              <span>{{ row.categoryName || '未分类' }}</span>
              <span style="color: var(--color-warning-6, #ff7d00)">{{ difficultyLabel(row.difficulty) }}</span>
              <FaTag :variant="row.status === 'DISABLED' ? 'outline' : 'default'">{{ row.status === 'DISABLED' ? '停用' : '启用' }}</FaTag>
              <span>{{ formatTime(row.updatedAt) }}</span>
            </div>
            <div v-if="row.tags.length" class="flex flex-wrap gap-1">
              <FaTag v-for="tag in row.tags" :key="tag" variant="outline">{{ tag }}</FaTag>
            </div>
            <div class="qb-mobile-card-actions">
              <FaButton size="sm" variant="outline" @click="openEdit(row)">编辑</FaButton>
              <FaButton size="sm" variant="destructive" @click="confirmDelete(row)">删除</FaButton>
            </div>
          </div>
        </FaCard>
        <div v-if="!model.loading && model.adminQuestions.length === 0" class="qb-mobile-empty">
          暂无题目，点击右上角新建或导入
        </div>
      </div>
    </div>
    <FaPagination
      v-model:page="model.adminPager.page"
      v-model:size="model.adminPager.size"
      :total="model.adminPager.total"
      class="mt-3"
      @page-change="model.loadAdminQuestions"
      @size-change="model.applyAdminFilters"
    />
    <FaModal v-model="importOpen" title="导入题目" :show-confirm-button="false" cancel-button-text="关闭">
      <div class="flex flex-col gap-3 text-sm">
        <p class="qb-muted">
          选择 JSON 文件导入，格式为 <code>{ "questions": [...] }</code> 或题目数组；字段与导出 JSON 一致。
          分类按名称自动复用或创建，单次最多 500 道。
        </p>
        <div class="flex items-center gap-2">
          <FaButton variant="outline" :loading="importing" @click="pickImportFile"><FaIcon name="i-ri:file-add-line" />选择 JSON 文件</FaButton>
          <span v-if="importFileName" class="qb-muted">{{ importFileName }}</span>
        </div>
        <template v-if="importResult">
          <FaTag :variant="importResult.failures.length ? 'outline' : 'default'">
            已导入 {{ importResult.imported }} 道<template v-if="importResult.failures.length">，{{ importResult.failures.length }} 道失败</template>
          </FaTag>
          <div v-if="importResult.failures.length" class="flex flex-col gap-1">
            <div v-for="failure in importResult.failures" :key="failure.index" class="text-secondary-foreground/80">
              第 {{ failure.index }} 道：{{ failure.reason }}
            </div>
          </div>
        </template>
      </div>
    </FaModal>
    <FaModal v-model="aiImportOpen" title="AI 一键导入题目" :show-confirm-button="false" cancel-button-text="关闭" @update:model-value="!$event && stopAiStream()">
      <div class="flex flex-col gap-3 text-sm">
        <p class="qb-muted">
          粘贴从别处复制的题目文本（可多道混合），或上传 Markdown 文件；AI 流式识别并<strong>逐题调用创建工具直接入库</strong>，
          实时输出、每题成败都展示在下方。单次最多 20000 字、50 道题。
        </p>
        <FaTextarea
          v-model="aiImportText"
          :rows="8"
          placeholder="在此粘贴题目内容…"
          :disabled="aiStreaming"
        />
        <div class="flex items-center gap-2">
          <FaButton variant="outline" :disabled="aiStreaming" @click="pickAiImportFile">
            <FaIcon name="i-ri:markdown-line" />上传 Markdown 文件
          </FaButton>
          <span v-if="aiImportFileName" class="qb-muted">{{ aiImportFileName }}</span>
          <FaButton class="ml-auto" :loading="aiStreaming" :disabled="!!aiDone" @click="startAiImport">
            <FaIcon name="i-ri:sparkling-line" />{{ aiLogs.length ? '重新导入' : '开始导入' }}
          </FaButton>
        </div>
        <div v-if="aiStreamText" class="rounded-md border p-2">
          <div class="qb-muted mb-1 flex items-center gap-1 text-xs">
            <FaIcon name="i-ri:robot-2-line" />AI 实时输出
            <FaIcon v-if="aiStreaming" name="i-ri:loader-4-line" class="animate-spin" />
          </div>
          <pre class="qb-muted max-h-40 overflow-y-auto whitespace-pre-wrap break-all text-xs">{{ aiStreamText }}</pre>
        </div>
        <template v-if="aiLogs.length || aiQuestions.length">
          <div class="flex max-h-72 flex-col gap-1 overflow-y-auto rounded-md border p-2">
            <div v-for="(log, index) in aiLogs" :key="`log-${index}`" class="qb-muted text-xs" :class="{ 'text-destructive': log.level === 'ERROR', 'text-warning-6': log.level === 'WARN' }">
              {{ log.message }}
            </div>
            <div v-for="question in aiQuestions" :key="question.seq" class="flex items-start gap-2 border-t pt-1">
              <FaIcon :name="question.ok ? 'i-ri:check-line' : 'i-ri:close-line'" :class="question.ok ? 'text-success-6' : 'text-destructive'" />
              <div class="min-w-0 flex-1">
                <div class="truncate" :title="question.summary">第 {{ question.seq }} 道：{{ question.summary || '（空题干）' }}</div>
                <div v-if="!question.ok" class="text-destructive text-xs">{{ question.reason }}</div>
              </div>
            </div>
          </div>
          <FaTag v-if="aiDone" :variant="aiDone.failed ? 'outline' : 'default'">
            导入完成：成功 {{ aiDone.imported }} 道<template v-if="aiDone.failed">，失败 {{ aiDone.failed }} 道</template>
          </FaTag>
          <p v-else-if="aiStreaming" class="qb-muted text-xs">正在导入，可关闭弹窗稍后回来查看题目列表（任务在后台继续）。</p>
        </template>
      </div>
    </FaModal>
  </FaPageMain>
</template>
