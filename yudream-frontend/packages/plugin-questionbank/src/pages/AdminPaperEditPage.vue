<script setup lang="ts">
import type { QuestionBankPluginModel } from '../composables/useQuestionBankPlugin'
import type { PaperPayload, QuestionView } from '../types'
import { Select as ASelect } from '@arco-design/web-vue'
import { FaButton, FaCard, FaCheckboxGroup, FaDrawer, FaIcon, FaInput, FaNumberField, FaPageHeader, FaPageMain, FaPagination, FaSelect, FaTag, FaTextarea, useFaToast } from '@yudream/components'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import CategorySelect from '../components/CategorySelect.vue'
import { difficultyLabel, errorMessage, formatTime, QUESTION_TYPE_OPTIONS } from '../composables/utils'

const props = defineProps<{ model: QuestionBankPluginModel }>()
const model = props.model
const route = useRoute()
const router = useRouter()
const toast = useFaToast()

const paperId = String(route.query.id ?? '')
const editing = computed(() => !!paperId)

const form = reactive<Required<Omit<PaperPayload, 'categoryId' | 'description' | 'count' | 'questionIds'>> & {
  categoryId: string
  description: string
  count: number
  questionIds: string[]
}>({
  name: '',
  description: '',
  mode: 'RULE',
  categoryId: '',
  tags: [],
  types: [],
  difficulties: [],
  count: 10,
  questionIds: [],
  subjectiveMode: 'SELF',
  status: 'DRAFT',
})

const pageLoading = ref(false)

const modeOptions = [
  { label: '随机抽题（每次作答/打印现场随机）', value: 'RULE' },
  { label: '固定题目（手动选题，顺序固定）', value: 'MANUAL' },
]
const subjectiveOptions = [
  { label: '用户自评（对照参考答案自判对错）', value: 'SELF' },
  { label: '人工审核（提交后由管理员判分）', value: 'REVIEW' },
  { label: 'AI 判分（提交后自动判分，失败转人工审核）', value: 'AI' },
]
const statusOptions = [
  { label: '草稿（仅管理员可见）', value: 'DRAFT' },
  { label: '发布（用户可作答）', value: 'PUBLISHED' },
  { label: '归档（停止作答，保留记录）', value: 'ARCHIVED' },
]

const tagOptions = computed(() => model.adminTags.map(tag => ({ label: `${tag.name}（${tag.count}）`, value: tag.name })))
const typeOptions = QUESTION_TYPE_OPTIONS.map(item => ({ label: item.label, value: item.value }))
const difficultyOptions = [1, 2, 3, 4, 5].map(value => ({ label: difficultyLabel(value), value }))

// ---------- 手动选题 ----------
const pickerOpen = ref(false)
const pickerSelected = ref<QuestionView[]>([])
const pickerKeyword = ref('')
const pickerList = ref<QuestionView[]>([])
const pickerPager = reactive({ page: 1, size: 10, total: 0 })
const pickerLoading = ref(false)

async function loadPickerList() {
  pickerLoading.value = true
  try {
    const page = await model.api.adminQuestions(pickerKeyword.value, '', '', '', undefined, 'ENABLED', pickerPager.page, pickerPager.size)
    pickerList.value = page.records
    pickerPager.total = Number(page.total)
  }
  catch (error) {
    toast.error(errorMessage(error))
  }
  finally {
    pickerLoading.value = false
  }
}

function pickerSearch() {
  pickerPager.page = 1
  void loadPickerList()
}

function isPicked(row: QuestionView) {
  return pickerSelected.value.some(item => item.id === row.id)
}

function togglePick(row: QuestionView) {
  if (isPicked(row)) {
    pickerSelected.value = pickerSelected.value.filter(item => item.id !== row.id)
  }
  else {
    pickerSelected.value = [...pickerSelected.value, row]
  }
}

function movePick(index: number, offset: number) {
  const target = index + offset
  if (target < 0 || target >= pickerSelected.value.length) {
    return
  }
  const list = [...pickerSelected.value]
  const [item] = list.splice(index, 1)
  list.splice(target, 0, item)
  pickerSelected.value = list
}

function confirmPicker() {
  form.questionIds = pickerSelected.value.map(item => item.id)
  pickerOpen.value = false
}

function openPicker() {
  pickerSelected.value = []
  pickerKeyword.value = ''
  pickerPager.page = 1
  pickerOpen.value = true
  // 已选题目逐条取详情回填（可能已被筛出当前列表）
  void (async () => {
    const resolved = await Promise.all(form.questionIds.map(idValue =>
      model.api.adminQuestion(idValue).catch(() => null)))
    pickerSelected.value = resolved.filter((item): item is QuestionView => !!item && item.status !== 'DISABLED')
    await loadPickerList()
  })()
}

function plainContent(row: QuestionView) {
  const text = row.content
    .replace(/!\[[^\]]*\]\([^)]*\)/g, '[图片]')
    .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/[#>*`_~-]+/g, '')
    .replace(/\s+/g, ' ')
    .trim()
  return text.length > 60 ? `${text.slice(0, 60)}…` : text
}

const pickedSummary = computed(() => {
  if (!form.questionIds.length) {
    return '尚未选题'
  }
  const names = pickerSelected.value.length === form.questionIds.length
    ? pickerSelected.value.slice(0, 3).map(item => plainContent(item) || item.id)
    : []
  return names.length ? `已选 ${form.questionIds.length} 题：${names.join('；')}${form.questionIds.length > 3 ? '…' : ''}` : `已选 ${form.questionIds.length} 题`
})

// ---------- 加载与保存 ----------

async function loadPaper() {
  if (!editing.value) {
    return
  }
  pageLoading.value = true
  try {
    const paper = await model.api.adminPaper(paperId)
    form.name = paper.name
    form.description = paper.description ?? ''
    form.mode = paper.mode
    form.categoryId = paper.categoryId ?? ''
    form.tags = [...(paper.tags ?? [])]
    form.types = [...(paper.types ?? [])]
    form.difficulties = [...(paper.difficulties ?? [])]
    form.count = paper.count || 10
    form.questionIds = [...(paper.questionIds ?? [])]
    form.subjectiveMode = paper.subjectiveMode
    form.status = paper.status
  }
  catch (error) {
    toast.error(errorMessage(error))
    router.push('/platform/plugins/questionbank/admin/papers')
  }
  finally {
    pageLoading.value = false
  }
}

async function save() {
  if (!form.name.trim()) {
    toast.warning('请填写题单名称')
    return
  }
  if (form.mode === 'MANUAL' && !form.questionIds.length) {
    toast.warning('固定题目模式至少需要选择 1 道题')
    return
  }
  const payload: PaperPayload = {
    name: form.name.trim(),
    description: form.description.trim() || undefined,
    mode: form.mode,
    categoryId: form.categoryId || null,
    tags: form.mode === 'RULE' && form.tags.length ? [...form.tags] : [],
    types: form.mode === 'RULE' && form.types.length ? [...form.types] : [],
    difficulties: form.mode === 'RULE' && form.difficulties.length ? [...form.difficulties] : [],
    count: form.mode === 'RULE' ? form.count : undefined,
    questionIds: form.mode === 'MANUAL' ? [...form.questionIds] : [],
    subjectiveMode: form.subjectiveMode,
    status: form.status,
  }
  const saved = await model.savePaper(editing.value ? paperId : null, payload)
  if (saved) {
    router.push('/platform/plugins/questionbank/admin/papers')
  }
}

onMounted(async () => {
  await model.loadAdminCategories()
  await model.loadAdminTags()
  await loadPaper()
})
</script>

<template>
  <FaPageHeader :title="editing ? '编辑题单' : '新建题单'" description="随机抽题每次作答/打印现场随机；固定题目顺序固定，题目删除或停用后自动跳过">
    <FaButton variant="outline" @click="router.push('/platform/plugins/questionbank/admin/papers')">
      <FaIcon name="i-ri:arrow-left-line" />返回列表
    </FaButton>
  </FaPageHeader>
  <FaPageMain>
    <div v-loading="pageLoading" class="qb-form">
      <FaCard title="基本信息">
        <div class="qb-form">
          <div class="qb-form-row">
            <span class="qb-form-label"><span class="qb-required">*</span>题单名称</span>
            <FaInput v-model="form.name" placeholder="例如：2026 春季招新笔试" clearable />
          </div>
          <div class="qb-form-row">
            <span class="qb-form-label">说明</span>
            <FaTextarea v-model="form.description" placeholder="给作答者看的题单说明（可选）" :rows="3" />
          </div>
          <div class="qb-form-row">
            <span class="qb-form-label">抽题方式</span>
            <FaSelect v-model="form.mode" :options="modeOptions" />
          </div>
          <div class="qb-form-row">
            <span class="qb-form-label">简答题判分</span>
            <FaSelect v-model="form.subjectiveMode" :options="subjectiveOptions" />
          </div>
          <div class="qb-form-row">
            <span class="qb-form-label">状态</span>
            <FaSelect v-model="form.status" :options="statusOptions" />
          </div>
        </div>
      </FaCard>

      <FaCard v-if="form.mode === 'RULE'" title="抽题规则" description="不勾选即不限制该维度；每次作答与每次打印都会按规则现场重新随机抽题">
        <div class="qb-form">
          <div class="qb-form-row">
            <span class="qb-form-label">分类</span>
            <CategorySelect
              v-model="form.categoryId"
              :categories="model.adminCategories"
              empty-label="全部分类"
              placeholder="不限分类"
            />
          </div>
          <div class="qb-form-row">
            <span class="qb-form-label">标签（命中任一即可）</span>
            <ASelect v-model="form.tags" :options="tagOptions" placeholder="不限标签" multiple allow-search />
          </div>
          <div class="qb-form-row">
            <span class="qb-form-label">题型</span>
            <FaCheckboxGroup v-model="form.types" :options="typeOptions" class="flex flex-wrap gap-3" />
          </div>
          <div class="qb-form-row">
            <span class="qb-form-label">难度</span>
            <FaCheckboxGroup v-model="form.difficulties" :options="difficultyOptions" class="flex flex-wrap gap-3" />
          </div>
          <div class="qb-form-row">
            <span class="qb-form-label"><span class="qb-required">*</span>抽题数量（1~100）</span>
            <FaNumberField v-model="form.count" :min="1" :max="100" />
          </div>
        </div>
      </FaCard>

      <FaCard v-else title="固定选题" description="按添加顺序出题；已删除或停用的题目在作答/打印时自动跳过">
        <div class="flex flex-col gap-3">
          <div class="flex items-center justify-between gap-3">
            <span class="text-sm qb-muted">{{ pickedSummary }}</span>
            <FaButton variant="outline" @click="openPicker"><FaIcon name="i-ri:list-check-2" />选择题目</FaButton>
          </div>
        </div>
      </FaCard>

      <FaCard>
        <div class="flex justify-end gap-2">
          <FaButton variant="outline" @click="router.push('/platform/plugins/questionbank/admin/papers')">取消</FaButton>
          <FaButton :loading="model.loading" @click="save"><FaIcon name="i-ri:save-line" />保存题单</FaButton>
        </div>
      </FaCard>
    </div>

    <FaDrawer v-model="pickerOpen" title="选择题目（勾选并排序）" size="720px">
      <div class="flex flex-col gap-4">
        <div class="qb-toolbar">
          <FaInput v-model="pickerKeyword" placeholder="搜索题干或标签" clearable @keydown.enter="pickerSearch" @clear="pickerSearch" />
          <FaButton variant="outline" @click="pickerSearch"><FaIcon name="i-ri:search-line" />查询</FaButton>
        </div>
        <div v-loading="pickerLoading" class="flex flex-col gap-2">
          <div
            v-for="row in pickerList"
            :key="row.id"
            class="flex items-center justify-between gap-2 rounded-lg border p-3 text-sm"
          >
            <div class="flex flex-col gap-1">
              <div class="flex items-center gap-2">
                <FaTag variant="secondary">{{ row.typeLabel }}</FaTag>
                <span style="color: var(--color-warning-6, #ff7d00)">{{ difficultyLabel(row.difficulty) }}</span>
                <span class="qb-muted">{{ row.categoryName || '未分类' }}</span>
              </div>
              <div>{{ plainContent(row) || '（空题干）' }}</div>
            </div>
            <FaButton size="sm" :variant="isPicked(row) ? 'destructive' : 'outline'" @click="togglePick(row)">
              {{ isPicked(row) ? '移除' : '添加' }}
            </FaButton>
          </div>
          <div v-if="!pickerLoading && !pickerList.length" class="qb-muted text-center py-4">没有匹配的启用题目</div>
        </div>
        <FaPagination
          v-model:page="pickerPager.page"
          v-model:size="pickerPager.size"
          :total="pickerPager.total"
          @page-change="loadPickerList"
          @size-change="pickerSearch"
        />
        <div class="flex flex-col gap-2">
          <div class="text-sm font-medium">已选 {{ pickerSelected.length }} 题（顺序即出题顺序）</div>
          <div
            v-for="(item, index) in pickerSelected"
            :key="item.id"
            class="flex items-center justify-between gap-2 rounded-lg border p-2 text-sm"
          >
            <span>{{ index + 1 }}. {{ plainContent(item) || '（空题干）' }}</span>
            <div class="flex gap-1">
              <FaButton size="sm" variant="outline" :disabled="index === 0" @click="movePick(index, -1)"><FaIcon name="i-ri:arrow-up-line" /></FaButton>
              <FaButton size="sm" variant="outline" :disabled="index === pickerSelected.length - 1" @click="movePick(index, 1)"><FaIcon name="i-ri:arrow-down-line" /></FaButton>
              <FaButton size="sm" variant="destructive" @click="togglePick(item)"><FaIcon name="i-ri:close-line" /></FaButton>
            </div>
          </div>
        </div>
        <div class="flex justify-end gap-2">
          <FaButton variant="outline" @click="pickerOpen = false">取消</FaButton>
          <FaButton @click="confirmPicker"><FaIcon name="i-ri:check-line" />确定选题（{{ pickerSelected.length }}）</FaButton>
        </div>
      </div>
    </FaDrawer>
  </FaPageMain>
</template>
