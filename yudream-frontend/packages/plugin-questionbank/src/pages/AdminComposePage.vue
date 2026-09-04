<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { QuestionBankPluginModel } from '../composables/useQuestionBankPlugin'
import type { ComposeRecordView, QuestionView } from '../types'
import { FaButton, FaCard, FaDrawer, FaIcon, FaInput, FaModal, FaPageHeader, FaPageMain, FaPagination, FaResponsiveTable, FaSearchBar, FaSelect, FaSwitch, FaTabs, FaTag, FaTextarea, useFaModal, useFaToast } from '@yudream/components'
import { computed, onMounted, ref } from 'vue'
import CategorySelect from '../components/CategorySelect.vue'
import MarkdownPreview from '../components/MarkdownPreview.vue'
import { difficultyLabel, formatTime, optionKey, QUESTION_TYPE_OPTIONS } from '../composables/utils'

const props = defineProps<{ model: QuestionBankPluginModel }>()
const model = props.model
const toast = useFaToast()
const confirm = useFaModal()

const tab = ref<'workbench' | 'records'>('workbench')
const tabs = [
  { label: '组卷工作台', value: 'workbench', icon: 'i-ri:tools-line' },
  { label: '组卷记录', value: 'records', icon: 'i-ri:folder-history-line' },
]

const title = ref('')
const description = ref('')
const withAnswers = ref(true)
const saveOpen = ref(false)
const detailOpen = ref(false)

const drawRule = ref({ categoryId: '', tag: '', type: '', count: '10' })

const columns: TableColumn<QuestionView>[] = [
  { id: 'content', header: '题干', minWidth: 260 },
  { id: 'type', header: '题型', width: 90 },
  { id: 'category', header: '分类', width: 110 },
  { id: 'tags', header: '标签', minWidth: 120 },
  { id: 'difficulty', header: '难度', width: 100 },
  { id: 'operation', header: '操作', width: 90 },
]

const recordColumns = computed<TableColumn<ComposeRecordView>[]>(() => [
  { accessorKey: 'title', header: '标题', minWidth: 160 },
  ...(model.questionbankManager ? [{ accessorKey: 'ownerName', header: '创建者', width: 110 } as TableColumn<ComposeRecordView>] : []),
  { accessorKey: 'questionCount', header: '题数', width: 70 },
  { id: 'withAnswers', header: '答案', width: 90 },
  { id: 'shared', header: '分享', width: 90 },
  { accessorKey: 'createdAt', header: '创建时间', width: 160 },
  { id: 'operation', header: '操作', width: 300 },
])

const scopeOptions = [
  { label: '我的组卷', value: 'mine' },
  { label: '全部组卷', value: 'all' },
]
const recordScope = computed({
  get: () => model.composeRecordsFilters.all ? 'all' : 'mine',
  set: value => model.composeRecordsFilters.all = value === 'all',
})

const tagOptions = computed(() => [
  { label: '全部标签', value: '' },
  ...model.composeOptions.tags.map(tag => ({ label: `${tag.name}（${tag.count}）`, value: tag.name })),
])

const drawTagOptions = computed(() => [
  { label: '不限标签', value: '' },
  ...model.composeOptions.tags.map(tag => ({ label: `${tag.name}（${tag.count}）`, value: tag.name })),
])

const typeOptions = [{ label: '全部题型', value: '' }, ...QUESTION_TYPE_OPTIONS]
const drawTypeOptions = [{ label: '不限题型', value: '' }, ...QUESTION_TYPE_OPTIONS]

/** 题干列表摘要：去 markdown 标记，防干扰。 */
function plainContent(row: QuestionView) {
  const text = row.content
    .replace(/!\[[^\]]*\]\([^)]*\)/g, '[图片]')
    .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/[#>*`_~-]+/g, '')
    .replace(/\s+/g, ' ')
    .trim()
  return text.length > 80 ? `${text.slice(0, 80)}…` : text
}

function isSelected(row: QuestionView) {
  return model.composeSelected.some(item => item.id === row.id)
}

function doDraw() {
  const count = Number.parseInt(drawRule.value.count, 10)
  if (!Number.isFinite(count) || count < 1 || count > 100) {
    toast.warning('抽题数量需为 1-100 的整数')
    return
  }
  void model.drawCompose({
    categoryId: drawRule.value.categoryId || undefined,
    tags: drawRule.value.tag ? [drawRule.value.tag] : undefined,
    types: drawRule.value.type ? [drawRule.value.type] : undefined,
    count,
  })
}

function doExport() {
  void model.exportComposeWord(title.value, description.value, withAnswers.value)
}

/** 大屏放映当前组卷列表：题目 id 经 sessionStorage 交接给大屏页。 */
function presentCurrentList() {
  if (!model.composeSelected.length) {
    toast.warning('请先手动选题或随机抽题')
    return
  }
  sessionStorage.setItem('qb-screen-draft', JSON.stringify(model.composeSelected.map(item => item.id)))
  window.open('/platform/plugins/questionbank/screen?draft=1', '_blank')
}

async function doSaveRecord() {
  const saved = await model.saveComposeRecord(title.value, description.value, withAnswers.value)
  if (saved) {
    saveOpen.value = false
  }
}

function viewRecord(row: ComposeRecordView) {
  detailOpen.value = true
  void model.loadComposeRecordDetail(row.id)
}

function presentRecord(row: ComposeRecordView) {
  window.open(`/platform/plugins/questionbank/screen?record=${encodeURIComponent(row.id)}`, '_blank')
}

async function toggleShare(row: ComposeRecordView) {
  await model.shareComposeRecord(row)
}

function confirmDeleteRecord(row: ComposeRecordView) {
  confirm.confirm({
    title: '删除组卷记录',
    content: `确认删除组卷记录「${row.title}」吗？删除后其分享链接将立即失效。`,
    onConfirm: () => model.removeComposeRecord(row.id),
  })
}

function correctAnswerText(question: QuestionView) {
  switch (question.type) {
    case 'SINGLE':
      return question.answer || '-'
    case 'TRUE_FALSE':
      return question.answer === 'TRUE' ? '正确' : '错误'
    case 'MULTIPLE':
      return question.answers?.join('、') || '-'
    case 'FILL':
      return question.blanks?.map((list, index) => `第${index + 1}空：${list.join(' / ')}`).join('；') || '-'
    default:
      return ''
  }
}

onMounted(async () => {
  await model.loadComposeOptions()
  await model.loadComposeQuestions()
  await model.loadComposeRecords()
})
</script>

<template>
  <FaPageHeader title="组卷中心" description="手动选题或按规则随机抽题；可大屏放映、导出 Word，也可保存为组卷记录并分享在线查看">
    <template v-if="tab === 'workbench'">
      <FaButton variant="outline" :disabled="!model.composeSelected.length" @click="presentCurrentList">
        <FaIcon name="i-ri:slideshow-2-line" />大屏放映
      </FaButton>
      <FaButton variant="outline" :disabled="!model.composeSelected.length" @click="saveOpen = true">
        <FaIcon name="i-ri:save-3-line" />保存为记录
      </FaButton>
      <FaButton :loading="model.composeExporting" :disabled="!model.composeSelected.length" @click="doExport">
        <FaIcon name="i-ri:file-word-2-line" />导出 Word{{ model.composeSelected.length ? `（${model.composeSelected.length} 题）` : '' }}
      </FaButton>
    </template>
  </FaPageHeader>
  <FaPageMain>
    <FaTabs v-model="tab" :list="tabs" class="mb-4" />

    <!-- 组卷工作台：宽屏左右两栏（右栏吸附），窄屏单栏堆叠 -->
    <div v-if="tab === 'workbench'" class="qb-compose-layout">
      <FaCard title="题目拾取" description="仅显示启用中的题目，点击「加入」进入右侧组卷列表">
        <FaResponsiveTable
          v-loading="model.loading"
          :columns="columns"
          :data="model.composeQuestions"
          row-key="id"
          table-root-class="qb-table-scroll"
          table-class="qb-table-w860"
          border stripe
          empty-text="暂无符合条件的启用题目"
        >
          <template #toolbar>
            <FaSearchBar class="w-full">
              <div class="qb-toolbar">
                <FaInput v-model="model.composeFilters.keyword" placeholder="搜索题干或标签" clearable @keydown.enter="model.applyComposeFilters" @clear="model.applyComposeFilters" />
                <CategorySelect v-model="model.composeFilters.categoryId" :categories="model.composeOptions.categories" empty-label="全部分类" @update:model-value="model.applyComposeFilters" />
                <FaSelect v-model="model.composeFilters.tag" :options="tagOptions" @change="model.applyComposeFilters" />
                <FaSelect v-model="model.composeFilters.type" :options="typeOptions" @change="model.applyComposeFilters" />
                <FaButton variant="outline" @click="model.applyComposeFilters"><FaIcon name="i-ri:search-line" />查询</FaButton>
              </div>
            </FaSearchBar>
          </template>
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
          <template #cell-operation="{ row }">
            <FaButton v-if="!isSelected(row.original)" size="sm" variant="outline" @click="model.addComposeQuestion(row.original)">
              <FaIcon name="i-ri:add-line" />加入
            </FaButton>
            <FaTag v-else variant="default">已加入</FaTag>
          </template>
          <template #card="{ row }">
            <FaCard>
              <div class="qb-mobile-card">
                <div class="qb-mobile-card-title">{{ plainContent(row) || '（空题干）' }}</div>
                <div class="qb-mobile-card-meta">
                  <FaTag variant="secondary">{{ row.typeLabel }}</FaTag>
                  <span>{{ row.categoryName || '未分类' }}</span>
                  <span style="color: var(--color-warning-6, #ff7d00)">{{ difficultyLabel(row.difficulty) }}</span>
                </div>
                <div v-if="row.tags.length" class="flex flex-wrap gap-1">
                  <FaTag v-for="tag in row.tags" :key="tag" variant="outline">{{ tag }}</FaTag>
                </div>
                <div class="qb-mobile-card-actions">
                  <FaButton v-if="!isSelected(row)" size="sm" variant="outline" @click="model.addComposeQuestion(row)">
                    <FaIcon name="i-ri:add-line" />加入
                  </FaButton>
                  <FaTag v-else variant="default">已加入</FaTag>
                </div>
              </div>
            </FaCard>
          </template>
        </FaResponsiveTable>
        <FaPagination
          v-model:page="model.composePager.page"
          v-model:size="model.composePager.size"
          :total="model.composePager.total"
          class="mt-3"
          @page-change="model.loadComposeQuestions"
          @size-change="model.applyComposeFilters"
        />
      </FaCard>
      <div class="qb-compose-rail">
        <FaCard title="试卷信息" description="导出 Word / 保存记录时的标题与说明，可自定义">
          <div class="qb-form">
            <div class="qb-form-row">
              <span class="qb-form-label">试卷标题</span>
              <FaInput v-model="title" placeholder="默认：试题卷" clearable />
            </div>
            <div class="qb-form-row items-start">
              <span class="qb-form-label pt-2">试卷说明</span>
              <FaTextarea v-model="description" :rows="3" placeholder="考试时间、注意事项等（可选）" />
            </div>
            <div class="qb-form-row">
              <span class="qb-form-label">附带答案与解析</span>
              <div class="flex items-center gap-3">
                <FaSwitch v-model="withAnswers" />
                <FaTag :variant="withAnswers ? 'default' : 'outline'">{{ withAnswers ? '附答案页' : '纯试卷' }}</FaTag>
              </div>
            </div>
          </div>
        </FaCard>
        <FaCard title="随机抽题" description="按条件随机抽题追加到组卷列表，自动去重">
          <div class="qb-form">
            <div class="qb-form-row">
              <span class="qb-form-label">分类</span>
              <CategorySelect v-model="drawRule.categoryId" :categories="model.composeOptions.categories" empty-label="不限分类" />
            </div>
            <div class="qb-form-row">
              <span class="qb-form-label">标签</span>
              <FaSelect v-model="drawRule.tag" :options="drawTagOptions" />
            </div>
            <div class="qb-form-row">
              <span class="qb-form-label">题型</span>
              <FaSelect v-model="drawRule.type" :options="drawTypeOptions" />
            </div>
            <div class="qb-form-row">
              <span class="qb-form-label">数量</span>
              <FaInput v-model="drawRule.count" placeholder="1-100" />
            </div>
            <FaButton variant="outline" :loading="model.composeDrawing" @click="doDraw">
              <FaIcon name="i-ri:shuffle-line" />随机抽题
            </FaButton>
          </div>
        </FaCard>
        <FaCard :title="`组卷列表（${model.composeSelected.length}）`" description="按加入顺序出卷，可逐题移除">
          <div v-if="model.composeSelected.length" class="qb-compose-selected-list">
            <div v-for="(item, index) in model.composeSelected" :key="item.id" class="flex items-start gap-2 rounded-md border p-2 text-sm">
              <span class="qb-muted shrink-0">{{ index + 1 }}.</span>
              <div class="min-w-0 flex-1">
                <div class="truncate" :title="plainContent(item)">{{ plainContent(item) || '（空题干）' }}</div>
                <div class="qb-muted text-xs">{{ item.typeLabel }}<template v-if="item.categoryName"> · {{ item.categoryName }}</template></div>
              </div>
              <FaButton size="sm" variant="ghost" @click="model.removeComposeQuestion(item.id)">
                <FaIcon name="i-ri:close-line" />
              </FaButton>
            </div>
          </div>
          <p v-else class="qb-muted text-sm">还没有题目，从左侧表格加入或使用随机抽题。</p>
          <div v-if="model.composeSelected.length" class="mt-3 flex justify-end">
            <FaButton size="sm" variant="outline" @click="model.composeSelected = []">清空列表</FaButton>
          </div>
        </FaCard>
      </div>
    </div>

    <!-- 组卷记录 -->
    <FaCard v-else title="组卷记录" description="记录只保存题目引用，打开时实时解析；已删除或停用的题目会自动跳过">
      <FaResponsiveTable
        v-loading="model.composeRecordsLoading"
        :columns="recordColumns"
        :data="model.composeRecords"
        row-key="id"
        table-root-class="qb-table-scroll"
        table-class="qb-table-w860"
        border stripe
        empty-text="还没有组卷记录，去工作台保存一份吧"
      >
        <template #toolbar>
          <FaSearchBar class="w-full">
            <div class="qb-toolbar">
              <FaInput v-model="model.composeRecordsFilters.keyword" placeholder="搜索标题或说明" clearable @keydown.enter="model.applyComposeRecordsFilters" @clear="model.applyComposeRecordsFilters" />
              <FaSelect v-if="model.questionbankManager" v-model="recordScope" :options="scopeOptions" @change="model.applyComposeRecordsFilters" />
              <FaButton variant="outline" @click="model.applyComposeRecordsFilters"><FaIcon name="i-ri:search-line" />查询</FaButton>
              <FaButton variant="outline" @click="model.loadComposeRecords"><FaIcon name="i-ri:refresh-line" />刷新</FaButton>
            </div>
          </FaSearchBar>
        </template>
        <template #cell-withAnswers="{ row }">
          <FaTag :variant="row.original.withAnswers ? 'default' : 'outline'">{{ row.original.withAnswers ? '附答案' : '纯试卷' }}</FaTag>
        </template>
        <template #cell-shared="{ row }">
          <FaTag :variant="row.original.shared ? 'default' : 'secondary'">{{ row.original.shared ? '分享中' : '未分享' }}</FaTag>
        </template>
        <template #cell-createdAt="{ row }">{{ formatTime(row.original.createdAt) }}</template>
        <template #cell-operation="{ row }">
          <div class="flex flex-wrap gap-1">
            <FaButton size="sm" variant="outline" @click="viewRecord(row.original)"><FaIcon name="i-ri:eye-line" />查看</FaButton>
            <FaButton size="sm" variant="outline" @click="presentRecord(row.original)"><FaIcon name="i-ri:slideshow-2-line" />大屏</FaButton>
            <FaButton size="sm" variant="outline" @click="model.exportComposeRecordWord(row.original)"><FaIcon name="i-ri:file-word-2-line" />Word</FaButton>
            <FaButton size="sm" :variant="row.original.shared ? 'ghost' : 'outline'" @click="toggleShare(row.original)">
              <FaIcon :name="row.original.shared ? 'i-ri:link-unlink' : 'i-ri:share-line'" />{{ row.original.shared ? '取消分享' : '分享' }}
            </FaButton>
            <FaButton size="sm" variant="destructive" @click="confirmDeleteRecord(row.original)"><FaIcon name="i-ri:delete-bin-line" />删除</FaButton>
          </div>
        </template>
        <template #card="{ row }">
          <FaCard>
            <div class="qb-mobile-card">
              <div class="qb-mobile-card-title">{{ row.title }}</div>
              <div class="qb-mobile-card-meta">
                <span v-if="model.questionbankManager">{{ row.ownerName }}</span>
                <span>{{ row.questionCount }} 题</span>
                <FaTag :variant="row.withAnswers ? 'default' : 'outline'">{{ row.withAnswers ? '附答案' : '纯试卷' }}</FaTag>
                <FaTag :variant="row.shared ? 'default' : 'secondary'">{{ row.shared ? '分享中' : '未分享' }}</FaTag>
                <span>{{ formatTime(row.createdAt) }}</span>
              </div>
              <div class="qb-mobile-card-actions">
                <FaButton size="sm" variant="outline" @click="viewRecord(row)">查看</FaButton>
                <FaButton size="sm" variant="outline" @click="presentRecord(row)">大屏</FaButton>
                <FaButton size="sm" variant="outline" @click="model.exportComposeRecordWord(row)">Word</FaButton>
                <FaButton size="sm" :variant="row.shared ? 'ghost' : 'outline'" @click="toggleShare(row)">
                  {{ row.shared ? '取消分享' : '分享' }}
                </FaButton>
                <FaButton size="sm" variant="destructive" @click="confirmDeleteRecord(row)">删除</FaButton>
              </div>
            </div>
          </FaCard>
        </template>
      </FaResponsiveTable>
      <FaPagination
        v-model:page="model.composeRecordsPager.page"
        v-model:size="model.composeRecordsPager.size"
        :total="model.composeRecordsPager.total"
        class="mt-3"
        @page-change="model.loadComposeRecords"
        @size-change="model.applyComposeRecordsFilters"
      />
    </FaCard>

    <!-- 保存为记录 -->
    <FaModal v-model="saveOpen" title="保存为组卷记录" :confirm-button-loading="model.composeRecordSaving" @confirm="doSaveRecord">
      <div class="qb-form">
        <div class="qb-form-row">
          <span class="qb-form-label">标题</span>
          <FaInput v-model="title" placeholder="默认：未命名组卷" clearable />
        </div>
        <div class="qb-form-row items-start">
          <span class="qb-form-label pt-2">说明</span>
          <FaTextarea v-model="description" :rows="3" placeholder="用途、场景等（可选）" />
        </div>
        <div class="qb-form-row">
          <span class="qb-form-label">附带答案与解析</span>
          <div class="flex items-center gap-3">
            <FaSwitch v-model="withAnswers" />
            <FaTag :variant="withAnswers ? 'default' : 'outline'">{{ withAnswers ? '附答案' : '纯试卷' }}</FaTag>
          </div>
        </div>
        <p class="qb-muted text-xs">记录将保存当前组卷列表的 {{ model.composeSelected.length }} 道题目引用，可在「组卷记录」页签管理、分享与再次导出。</p>
      </div>
    </FaModal>

    <!-- 在线查看记录；FaDrawer 没有 size prop，宽度用 content-class 走插件 CSS（移动端满宽） -->
    <FaDrawer v-model="detailOpen" :title="model.composeRecordDetail?.title ?? '组卷详情'" content-class="qb-record-drawer">
      <div v-if="model.composeRecordDetailLoading" class="qb-muted py-10 text-center">加载中…</div>
      <div v-else-if="!model.composeRecordDetail" class="qb-muted py-10 text-center">记录不存在或已删除</div>
      <div v-else class="flex flex-col gap-4">
        <div v-if="model.composeRecordDetail.description" class="qb-muted text-sm">{{ model.composeRecordDetail.description }}</div>
        <div class="qb-muted text-xs">
          创建者：{{ model.composeRecordDetail.ownerName }} · {{ model.composeRecordDetail.questions?.length ?? 0 }} 题
          <template v-if="model.composeRecordDetail.shared"> · 分享中</template>
        </div>
        <div v-for="(question, index) in model.composeRecordDetail.questions ?? []" :key="question.id ?? index" class="rounded-md border p-3">
          <div class="qb-question-meta">
            <strong>{{ index + 1 }}.</strong>
            <FaTag variant="secondary">{{ question.typeLabel }}</FaTag>
            <FaTag variant="outline">{{ difficultyLabel(question.difficulty) }}</FaTag>
          </div>
          <MarkdownPreview :sdk="model.sdk" :content="question.content" />
          <div v-if="question.type === 'SINGLE' || question.type === 'MULTIPLE'" class="qb-options">
            <div v-for="(option, optionIndex) in question.options" :key="optionIndex" class="qb-option">
              <span class="qb-option-key">{{ optionKey(optionIndex) }}</span>
              <MarkdownPreview :sdk="model.sdk" :content="option" />
            </div>
          </div>
          <div v-else-if="question.type === 'TRUE_FALSE'" class="qb-options">
            <div class="qb-option"><span class="qb-option-key">正确</span></div>
            <div class="qb-option"><span class="qb-option-key">错误</span></div>
          </div>
          <div class="qb-print-answer-key">
            <div v-if="question.type === 'SHORT'">
              <strong>参考答案：</strong>
              <MarkdownPreview :sdk="model.sdk" :content="question.referenceAnswer ?? ''" />
            </div>
            <div v-else><strong>答案：</strong>{{ correctAnswerText(question) }}</div>
            <template v-if="question.analysis">
              <strong>解析：</strong>
              <MarkdownPreview :sdk="model.sdk" :content="question.analysis" />
            </template>
          </div>
        </div>
        <div v-if="!(model.composeRecordDetail.questions ?? []).length" class="qb-muted py-6 text-center text-sm">这份记录的题目已全部失效</div>
      </div>
    </FaDrawer>
  </FaPageMain>
</template>
