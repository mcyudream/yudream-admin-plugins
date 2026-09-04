<script setup lang="ts">
import type { QuestionBankPluginModel } from '../composables/useQuestionBankPlugin'
import type { PaperView, QuestionView } from '../types'
import { FaButton, FaIcon, FaInput, useFaToast } from '@yudream/components'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import MarkdownPreview from '../components/MarkdownPreview.vue'
import { difficultyLabel, optionKey } from '../composables/utils'

const props = defineProps<{ model: QuestionBankPluginModel }>()
const model = props.model
const toast = useFaToast()
const route = useRoute()

const phase = ref<'setup' | 'present'>('setup')
const papers = ref<PaperView[]>([])
const papersLoading = ref(false)
const setupError = ref('')
/** 组卷中心带 ?record= 进入时记住记录 id，退出后可在准备台一键重播。 */
const entryRecordId = ref('')
/** 从组卷中心 window.open 进入（带 record/draft 参数）时，退出优先关闭当前页回到来源页。 */
const fromCompose = ref(false)
const drawCount = ref('10')
const drawing = ref(false)

const paperName = ref('')
const questions = ref<QuestionView[]>([])
const current = ref(0)
const revealed = ref(false)

const question = computed(() => questions.value[current.value] ?? null)

const answerText = computed(() => {
  const item = question.value
  if (!item) {
    return ''
  }
  switch (item.type) {
    case 'SINGLE': {
      const index = (item.answer ?? '').charCodeAt(0) - 65
      const text = index >= 0 ? item.options[index] : undefined
      return text ? `${item.answer}. ${text}` : (item.answer ?? '')
    }
    case 'MULTIPLE':
      return (item.answers ?? []).join('、')
    case 'TRUE_FALSE':
      return item.answer === 'TRUE' ? '✓ 对' : '✗ 错'
    case 'FILL':
      return (item.blanks ?? []).map((blank, index) => `第${index + 1}空：${blank.join(' / ')}`).join('；')
    default:
      return item.referenceAnswer ?? ''
  }
})

async function loadPapers() {
  papersLoading.value = true
  setupError.value = ''
  try {
    papers.value = await model.api.screenPapers()
  }
  catch (error) {
    setupError.value = error instanceof Error ? error.message : '加载题单失败'
  }
  finally {
    papersLoading.value = false
  }
}

function enterPresent(result: { paperName?: string, questions: QuestionView[] }) {
  if (!result.questions.length) {
    toast.warning('一道题都没有抽到，请检查题单或题库')
    return
  }
  paperName.value = result.paperName ?? '随机抽题'
  questions.value = result.questions
  current.value = 0
  revealed.value = false
  phase.value = 'present'
}

async function startPaper(row: PaperView) {
  drawing.value = true
  try {
    enterPresent(await model.api.screenDraw({ paperId: row.id }))
  }
  catch (error) {
    toast.error(error instanceof Error ? error.message : '抽题失败')
  }
  finally {
    drawing.value = false
  }
}

async function startRandom() {
  const count = Number.parseInt(drawCount.value, 10)
  if (!Number.isFinite(count) || count < 1 || count > 100) {
    toast.warning('抽题数量需为 1-100 的整数')
    return
  }
  drawing.value = true
  try {
    enterPresent(await model.api.screenDraw({ rule: { count } }))
  }
  catch (error) {
    toast.error(error instanceof Error ? error.message : '抽题失败')
  }
  finally {
    drawing.value = false
  }
}

function gotoQuestion(index: number) {
  if (index < 0 || index >= questions.value.length) {
    return
  }
  current.value = index
  revealed.value = false
}

function toggleReveal() {
  if (question.value) {
    revealed.value = !revealed.value
  }
}

function exitPresent() {
  if (fromCompose.value) {
    window.close()
    if (window.closed) {
      return
    }
  }
  phase.value = 'setup'
  questions.value = []
  revealed.value = false
  void loadPapers()
}

function onKeydown(event: KeyboardEvent) {
  if (phase.value !== 'present') {
    return
  }
  if (event.key === 'ArrowLeft') {
    gotoQuestion(current.value - 1)
  }
  else if (event.key === 'ArrowRight') {
    gotoQuestion(current.value + 1)
  }
  else if (event.key === ' ' || event.key === 'Enter') {
    event.preventDefault()
    toggleReveal()
  }
  else if (event.key === 'Escape') {
    exitPresent()
  }
}

/** 组卷中心带 ?record= 打开时，直接放映该记录。 */
async function startRecord(recordId: string) {
  drawing.value = true
  try {
    enterPresent(await model.api.screenDraw({ recordId }))
  }
  catch (error) {
    setupError.value = error instanceof Error ? error.message : '加载组卷记录失败'
  }
  finally {
    drawing.value = false
  }
}

/** 组卷工作台「大屏放映」经 sessionStorage 交接题目 id 列表。 */
async function startDraft() {
  const raw = sessionStorage.getItem('qb-screen-draft')
  sessionStorage.removeItem('qb-screen-draft')
  let ids: string[] = []
  try {
    ids = raw ? JSON.parse(raw) : []
  }
  catch {
    ids = []
  }
  if (!ids.length) {
    setupError.value = '没有可放映的组卷列表，请回到组卷中心重新操作'
    return
  }
  drawing.value = true
  try {
    enterPresent(await model.api.screenDraw({ questionIds: ids }))
  }
  catch (error) {
    setupError.value = error instanceof Error ? error.message : '加载组卷列表失败'
  }
  finally {
    drawing.value = false
  }
}

onMounted(() => {
  const recordId = String(route.query.record ?? '').trim()
  if (recordId) {
    entryRecordId.value = recordId
    fromCompose.value = true
    void startRecord(recordId)
  }
  else if (String(route.query.draft ?? '') === '1') {
    fromCompose.value = true
    void startDraft()
  }
  void loadPapers()
  window.addEventListener('keydown', onKeydown)
})

onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <div class="qb-screen">
    <!-- 准备台：选题单或随机抽题 -->
    <div v-if="phase === 'setup'" class="qb-screen-setup">
      <div class="qb-screen-setup-panel">
        <h1 class="qb-screen-setup-title">
          <FaIcon name="i-ri:presentation-line" />抢答大屏
        </h1>
        <p class="qb-screen-setup-desc">
          选择已发布的题单现场抽题，或直接从题库随机抽题。放映中 ←/→ 切题，空格/回车揭晓答案，Esc 退出。
        </p>
        <p v-if="setupError" class="qb-screen-setup-error">{{ setupError }}</p>
        <div v-if="entryRecordId" class="qb-screen-setup-random">
          <FaButton variant="outline" :loading="drawing" @click="startRecord(entryRecordId)">
            <FaIcon name="i-ri:restart-line" />重新放映组卷记录
          </FaButton>
        </div>
        <div v-loading="papersLoading" class="qb-screen-setup-papers">
          <button
            v-for="paper in papers"
            :key="paper.id"
            type="button"
            class="qb-screen-paper"
            :disabled="drawing"
            @click="startPaper(paper)"
          >
            <span class="qb-screen-paper-name">{{ paper.name }}</span>
            <span class="qb-screen-paper-meta">{{ paper.questionCount }} 题 · {{ paper.mode === 'RULE' ? '随机抽题' : '固定题目' }}</span>
          </button>
          <p v-if="!papersLoading && !papers.length && !setupError" class="qb-screen-setup-desc">暂无已发布的题单。</p>
        </div>
        <div class="qb-screen-setup-random">
          <FaInput v-model="drawCount" class="qb-screen-count" placeholder="题数（1-100）" />
          <FaButton :loading="drawing" @click="startRandom"><FaIcon name="i-ri:shuffle-line" />随机抽题开演</FaButton>
        </div>
      </div>
    </div>

    <!-- 放映态：全屏题目 + 答案揭晓动画 -->
    <div v-else-if="question" class="qb-screen-stage">
      <header class="qb-screen-bar">
        <div class="qb-screen-bar-title">
          <FaIcon name="i-ri:presentation-line" />
          {{ paperName }}
        </div>
        <div class="qb-screen-bar-right">
          <span class="qb-screen-progress">{{ current + 1 }} / {{ questions.length }}</span>
          <button type="button" class="qb-screen-exit" @click="exitPresent"><FaIcon name="i-ri:close-line" />退出</button>
        </div>
      </header>

      <main class="qb-screen-body">
        <Transition name="qb-screen-swap" mode="out-in">
          <div :key="question.id" class="qb-screen-card">
            <div class="qb-screen-tags">
              <span class="qb-screen-tag">{{ question.typeLabel }}</span>
              <span class="qb-screen-tag qb-screen-tag-difficulty">{{ difficultyLabel(question.difficulty) }}</span>
              <span v-if="question.categoryName" class="qb-screen-tag">{{ question.categoryName }}</span>
            </div>
            <div class="qb-screen-content">
              <MarkdownPreview :sdk="model.sdk" :content="question.content" />
            </div>
            <ul v-if="question.type === 'SINGLE' || question.type === 'MULTIPLE'" class="qb-screen-options">
              <li v-for="(option, index) in question.options" :key="index" class="qb-screen-option">
                <span class="qb-screen-option-key">{{ optionKey(index) }}</span>
                <MarkdownPreview :sdk="model.sdk" :content="option" />
              </li>
            </ul>
            <p v-else-if="question.type === 'FILL'" class="qb-screen-hint">共 {{ question.blanks?.length || 0 }} 个空，请作答</p>
            <p v-else-if="question.type === 'TRUE_FALSE'" class="qb-screen-hint">判断对错</p>
            <p v-else class="qb-screen-hint">简答题，请作答</p>
          </div>
        </Transition>

        <!-- 答案揭晓：翻转入场 + 光晕扫过 -->
        <Transition name="qb-screen-answer">
          <div v-if="revealed" key="answer" class="qb-screen-answer">
            <div class="qb-screen-answer-badge"><FaIcon name="i-ri:key-2-line" />答案揭晓</div>
            <div class="qb-screen-answer-text">{{ answerText || '（未配置答案）' }}</div>
            <div v-if="question.analysis" class="qb-screen-answer-analysis">
              <MarkdownPreview :sdk="model.sdk" :content="question.analysis" />
            </div>
          </div>
        </Transition>
      </main>

      <footer class="qb-screen-controls">
        <button type="button" class="qb-screen-btn" :disabled="current <= 0" @click="gotoQuestion(current - 1)">
          <FaIcon name="i-ri:arrow-left-line" />上一题
        </button>
        <button type="button" class="qb-screen-btn qb-screen-btn-primary" @click="toggleReveal">
          <FaIcon :name="revealed ? 'i-ri:eye-off-line' : 'i-ri:eye-line'" />{{ revealed ? '收起答案' : '揭晓答案' }}
        </button>
        <button type="button" class="qb-screen-btn" :disabled="current >= questions.length - 1" @click="gotoQuestion(current + 1)">
          下一题<FaIcon name="i-ri:arrow-right-line" />
        </button>
      </footer>
    </div>
  </div>
</template>
