<script setup lang="ts">
import type { QuestionBankPluginModel } from '../composables/useQuestionBankPlugin'
import type { AnswerPayload, SessionQuestionView, SessionView } from '../types'
import { FaButton, FaCard, FaIcon, FaInput, FaPageHeader, FaPageMain, FaTag, FaTextarea, useFaModal } from '@yudream/components'
import { computed, reactive, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import MarkdownPreview from '../components/MarkdownPreview.vue'
import { difficultyLabel, formatTime, optionKey } from '../composables/utils'

const props = defineProps<{ model: QuestionBankPluginModel }>()
const model = props.model
const route = useRoute()
const router = useRouter()
const confirm = useFaModal()

const sessionId = String(route.query.id ?? '')

interface DraftAnswer {
  choice: string
  choices: string[]
  blanks: string[]
  text: string
}

const drafts = reactive<Record<string, DraftAnswer>>({})

function initDrafts(view: SessionView) {
  for (const question of view.questions) {
    drafts[question.questionId] = {
      choice: '',
      choices: [],
      blanks: Array.from({ length: question.blankCount ?? 0 }, () => ''),
      text: '',
    }
  }
}

watch(() => model.session, (view) => {
  if (view && view.status === 'ONGOING' && Object.keys(drafts).length === 0) {
    initDrafts(view)
  }
}, { immediate: true })

if (sessionId) {
  void model.loadSession(sessionId)
}

const finished = computed(() => model.session?.status === 'FINISHED')
const reviewMode = computed(() => model.session?.subjectiveMode === 'REVIEW')
const aiMode = computed(() => model.session?.subjectiveMode === 'AI')
/** 简答判分不由用户完成（人工审核或 AI）时隐藏自评入口 */
const externalGrading = computed(() => reviewMode.value || aiMode.value)

/** 固定题型展示顺序：单选→多选→判断→填空→简答，与后端抽题排序一致。 */
const TYPE_ORDER = ['SINGLE', 'MULTIPLE', 'TRUE_FALSE', 'FILL', 'SHORT']
const GROUP_NUMERALS = ['一', '二', '三', '四', '五', '六', '七', '八', '九', '十']

interface QuestionGroup {
  type: string
  label: string
  items: { question: SessionQuestionView, no: number }[]
}

/** 按题型分大标题展示并连续编号；历史会话的乱序快照也在此归拢，无需后端迁移。 */
const groupedQuestions = computed<QuestionGroup[]>(() => {
  const view = model.session
  if (!view) {
    return []
  }
  const byType = new Map<string, SessionQuestionView[]>()
  for (const question of view.questions) {
    const bucket = byType.get(question.type)
    if (bucket) {
      bucket.push(question)
    }
    else {
      byType.set(question.type, [question])
    }
  }
  const orderedTypes = TYPE_ORDER.filter(type => byType.has(type))
  for (const type of byType.keys()) {
    if (!orderedTypes.includes(type)) {
      orderedTypes.push(type)
    }
  }
  let no = 0
  return orderedTypes.map((type) => {
    const questions = byType.get(type) ?? []
    return {
      type,
      label: questions[0]?.typeLabel ?? type,
      items: questions.map(question => ({ question, no: ++no })),
    }
  })
})

const answeredCount = computed(() => {
  const view = model.session
  if (!view) {
    return 0
  }
  return view.questions.filter(question => isAnswered(question)).length
})

function isAnswered(question: SessionQuestionView) {
  const draft = drafts[question.questionId]
  if (!draft) {
    return false
  }
  switch (question.type) {
    case 'SINGLE':
    case 'TRUE_FALSE':
      return !!draft.choice
    case 'MULTIPLE':
      return draft.choices.length > 0
    case 'FILL':
      return draft.blanks.some(blank => blank.trim() !== '')
    case 'SHORT':
      return draft.text.trim() !== ''
    default:
      return false
  }
}

function toggleSingle(question: SessionQuestionView, key: string) {
  const draft = drafts[question.questionId]
  if (draft) {
    draft.choice = draft.choice === key ? '' : key
  }
}

function toggleMultiple(question: SessionQuestionView, key: string) {
  const draft = drafts[question.questionId]
  if (!draft) {
    return
  }
  const index = draft.choices.indexOf(key)
  if (index >= 0) {
    draft.choices.splice(index, 1)
  }
  else {
    draft.choices.push(key)
  }
}

function buildPayloads(): AnswerPayload[] {
  return (model.session?.questions ?? []).map((question) => {
    const draft = drafts[question.questionId] ?? { choice: '', choices: [], blanks: [], text: '' }
    return {
      questionId: question.questionId,
      choice: draft.choice || undefined,
      choices: draft.choices.length ? [...draft.choices] : undefined,
      blanks: draft.blanks.length ? [...draft.blanks] : undefined,
      text: draft.text || undefined,
    }
  })
}

/** 题号列表过长时截断展示，避免确认框被刷爆。 */
function formatNumbers(numbers: number[]) {
  const shown = numbers.slice(0, 20).join('、')
  return numbers.length > 20 ? `${shown} 等` : shown
}

function confirmSubmit() {
  const flat = groupedQuestions.value.flatMap(group => group.items)
  const multipleNumbers = flat.filter(item => item.question.type === 'MULTIPLE').map(item => item.no)
  const unansweredNumbers = flat.filter(item => !isAnswered(item.question)).map(item => item.no)
  const hints: string[] = []
  if (multipleNumbers.length) {
    hints.push(`本卷含 ${multipleNumbers.length} 道多选题（第 ${formatNumbers(multipleNumbers)} 题），多选题需选全所有正确选项才得分`)
  }
  if (unansweredNumbers.length) {
    hints.push(`还有 ${unansweredNumbers.length} 道题未作答（第 ${formatNumbers(unansweredNumbers)} 题）`)
  }
  hints.push('提交后不能修改，确认提交吗？')
  confirm.confirm({
    title: '提交作答',
    content: hints.join('；'),
    onConfirm: () => model.submitSession(sessionId, buildPayloads()),
  })
}

/** 选项在结果态的样式：正确答案标绿，选错的标红。 */
function optionResultClass(question: SessionQuestionView, key: string) {
  const userAnswer = question.userAnswer
  if (question.type === 'MULTIPLE') {
    const inAnswer = (question.answers ?? []).includes(key)
    const picked = (userAnswer?.choices ?? []).includes(key)
    if (inAnswer) {
      return 'qb-option qb-option-correct'
    }
    if (picked) {
      return 'qb-option qb-option-wrong'
    }
    return 'qb-option'
  }
  const correctKey = question.answer ?? ''
  const picked = userAnswer?.choice === key
  if (key === correctKey) {
    return 'qb-option qb-option-correct'
  }
  if (picked) {
    return 'qb-option qb-option-wrong'
  }
  return 'qb-option'
}

function trueFalseOptions(question: SessionQuestionView) {
  return [
    { key: 'TRUE', label: '正确' },
    { key: 'FALSE', label: '错误' },
  ].map(item => ({ ...item, className: finished.value ? optionResultClass(question, item.key) : '' }))
}

function correctAnswerText(question: SessionQuestionView) {
  switch (question.type) {
    case 'SINGLE': {
      const index = (question.answer ?? '').charCodeAt(0) - 65
      const option = question.options[index]
      return option ? `${question.answer}. ${plainText(option)}` : (question.answer ?? '-')
    }
    case 'TRUE_FALSE':
      return question.answer === 'TRUE' ? '正确' : '错误'
    case 'MULTIPLE':
      return (question.answers ?? []).join('、')
    default:
      return ''
  }
}

function plainText(markdown: string) {
  return markdown.replace(/!\[[^\]]*\]\([^)]*\)/g, '[图片]').replace(/[#*>`\-]/g, '').trim()
}

function userAnswerSummary(question: SessionQuestionView) {
  const userAnswer = question.userAnswer
  if (!userAnswer) {
    return '未作答'
  }
  switch (question.type) {
    case 'SINGLE':
      return userAnswer.choice || '未作答'
    case 'TRUE_FALSE':
      return userAnswer.choice === 'TRUE' ? '正确' : userAnswer.choice === 'FALSE' ? '错误' : '未作答'
    case 'MULTIPLE':
      return (userAnswer.choices ?? []).length ? (userAnswer.choices ?? []).join('、') : '未作答'
    default:
      return ''
  }
}

function resultTag(question: SessionQuestionView) {
  const correct = question.userAnswer?.correct
  if (correct === true) {
    return { text: '答对了', variant: 'default' as const }
  }
  if (correct === false) {
    return { text: '答错了', variant: 'destructive' as const }
  }
  return { text: aiMode.value ? 'AI 判分中' : reviewMode.value ? '待审核' : '待自评', variant: 'outline' as const }
}

function selfMark(question: SessionQuestionView, correct: boolean) {
  void model.selfMark(sessionId, question.questionId, correct)
}
</script>

<template>
  <FaPageHeader title="在线作答" description="答题过程中可随时离开，从成绩记录继续作答">
    <FaButton variant="outline" @click="router.push('/platform/plugins/questionbank/records')">
      <FaIcon name="i-ri:history-line" />成绩记录
    </FaButton>
  </FaPageHeader>
  <FaPageMain>
    <div v-if="model.sessionLoading" class="qb-session">
      <FaCard>加载中…</FaCard>
    </div>
    <div v-else-if="!sessionId || !model.session" class="qb-session">
      <FaCard>
        <div class="flex flex-col items-center gap-3 py-6">
          <span class="qb-muted">练习不存在或已被删除</span>
          <FaButton variant="outline" @click="router.push('/platform/plugins/questionbank')">返回抽题</FaButton>
        </div>
      </FaCard>
    </div>
    <div v-else class="qb-session">
      <FaCard v-if="finished" class="qb-result-card">
        <div class="qb-result-banner">
          <div>
            <div class="qb-result-score">{{ model.session.correctCount }} / {{ model.session.totalCount }}</div>
            <div class="qb-muted text-sm">答对题数 / 总题数</div>
          </div>
          <div class="text-sm">
            <div v-if="model.session.paperName" class="font-medium">题单：{{ model.session.paperName }}</div>
            <div>提交时间：{{ formatTime(model.session.submittedAt) }}</div>
            <div v-if="reviewMode" class="qb-muted">简答题由管理员人工审核后计入成绩</div>
            <div v-else-if="aiMode" class="qb-muted">简答题由 AI 自动判分；判分失败将转管理员人工审核</div>
          </div>
          <div class="flex gap-2">
            <FaButton @click="router.push(model.session?.paperId ? '/platform/plugins/questionbank/papers' : '/platform/plugins/questionbank')">
              <FaIcon name="i-ri:refresh-line" />{{ model.session?.paperId ? '返回题单' : '再来一组' }}
            </FaButton>
          </div>
        </div>
      </FaCard>
      <FaCard v-else>
        <div class="qb-session-head">
          <span class="text-sm">共 {{ model.session.questions.length }} 题，已作答 {{ answeredCount }} 题</span>
          <FaButton :loading="model.submitting" @click="confirmSubmit"><FaIcon name="i-ri:check-line" />提交作答</FaButton>
        </div>
      </FaCard>
      <template v-for="(group, groupIndex) in groupedQuestions" :key="group.type">
        <div class="qb-type-heading">
          {{ GROUP_NUMERALS[groupIndex] ?? groupIndex + 1 }}、{{ group.label }}<span class="qb-type-heading-count">（共 {{ group.items.length }} 题）</span>
        </div>
        <FaCard v-for="({ question, no }) in group.items" :key="question.questionId">
          <div class="qb-question-card">
            <div class="qb-question-meta">
              <strong>{{ no }}.</strong>
            <FaTag variant="secondary">{{ question.typeLabel }}</FaTag>
            <FaTag variant="outline">{{ difficultyLabel(question.difficulty) }}</FaTag>
            <FaTag v-if="finished" :variant="resultTag(question).variant">{{ resultTag(question).text }}</FaTag>
          </div>
          <MarkdownPreview :sdk="model.sdk" :content="question.content" />
          <template v-if="!finished">
            <div v-if="question.type === 'SINGLE'" class="qb-options">
              <div
                v-for="(option, optionIndex) in question.options"
                :key="optionKey(optionIndex)"
                class="qb-option"
                :class="{ 'qb-option-selected': drafts[question.questionId]?.choice === optionKey(optionIndex) }"
                @click="toggleSingle(question, optionKey(optionIndex))"
              >
                <span class="qb-option-key">{{ optionKey(optionIndex) }}</span>
                <MarkdownPreview :sdk="model.sdk" :content="option" />
              </div>
            </div>
            <div v-else-if="question.type === 'TRUE_FALSE'" class="qb-options">
              <div
                v-for="item in trueFalseOptions(question)"
                :key="item.key"
                class="qb-option"
                :class="{ 'qb-option-selected': drafts[question.questionId]?.choice === item.key }"
                @click="toggleSingle(question, item.key)"
              >
                <span class="qb-option-key">{{ item.label }}</span>
              </div>
            </div>
            <div v-else-if="question.type === 'MULTIPLE'" class="qb-options">
              <div
                v-for="(option, optionIndex) in question.options"
                :key="optionKey(optionIndex)"
                class="qb-option"
                :class="{ 'qb-option-selected': drafts[question.questionId]?.choices.includes(optionKey(optionIndex)) }"
                @click="toggleMultiple(question, optionKey(optionIndex))"
              >
                <span class="qb-option-key">{{ optionKey(optionIndex) }}</span>
                <MarkdownPreview :sdk="model.sdk" :content="option" />
              </div>
            </div>
            <div v-else-if="question.type === 'FILL'" class="qb-options">
              <div v-for="blankIndex in question.blankCount ?? 0" :key="blankIndex" class="qb-blank-row">
                <span class="qb-option-key">第 {{ blankIndex }} 空</span>
                <FaInput
                  v-model="drafts[question.questionId].blanks[blankIndex - 1]"
                  placeholder="填写答案"
                  clearable
                />
              </div>
            </div>
            <FaTextarea
              v-else-if="question.type === 'SHORT'"
              v-model="drafts[question.questionId].text"
              placeholder="写下你的作答，提交后对照参考答案自评"
              :rows="4"
            />
          </template>
          <template v-else>
            <div v-if="question.type === 'SINGLE' || question.type === 'MULTIPLE'" class="qb-options">
              <div
                v-for="(option, optionIndex) in question.options"
                :key="optionKey(optionIndex)"
                :class="optionResultClass(question, optionKey(optionIndex))"
              >
                <span class="qb-option-key">{{ optionKey(optionIndex) }}</span>
                <MarkdownPreview :sdk="model.sdk" :content="option" />
              </div>
            </div>
            <div v-else-if="question.type === 'TRUE_FALSE'" class="qb-options">
              <div v-for="item in trueFalseOptions(question)" :key="item.key" :class="item.className">
                <span class="qb-option-key">{{ item.label }}</span>
              </div>
            </div>
            <div class="qb-answer-panel">
              <div v-if="question.type === 'FILL'">
                <div v-for="(blank, blankIndex) in question.blanks ?? []" :key="blankIndex" class="text-sm">
                  第 {{ blankIndex + 1 }} 空：你的答案「{{ question.userAnswer?.blanks?.[blankIndex] || '（空）' }}」，
                  可接受答案 {{ blank.join(' / ') }}
                </div>
              </div>
              <template v-else-if="question.type === 'SHORT'">
                <div class="text-sm">你的作答：</div>
                <div class="text-sm" style="white-space: pre-wrap">{{ question.userAnswer?.text || '未作答' }}</div>
                <div class="text-sm">参考答案：</div>
                <MarkdownPreview :sdk="model.sdk" :content="question.referenceAnswer ?? ''" />
                <div v-if="question.userAnswer?.correct == null && !externalGrading" class="flex gap-2">
                  <FaButton size="sm" variant="outline" @click="selfMark(question, true)">我答对了</FaButton>
                  <FaButton size="sm" variant="outline" @click="selfMark(question, false)">我答错了</FaButton>
                </div>
                <div v-else-if="question.userAnswer?.correct == null && aiMode" class="qb-muted text-sm">
                  AI 判分中，稍后刷新查看；判分失败将转管理员人工审核
                </div>
                <div v-else-if="question.userAnswer?.correct == null" class="qb-muted text-sm">已提交，等待管理员审核判分</div>
              </template>
              <div v-else class="text-sm">
                你的答案：{{ userAnswerSummary(question) }}；正确答案：{{ correctAnswerText(question) }}
              </div>
            </div>
            <div v-if="question.analysis" class="qb-answer-panel">
              <div class="text-sm font-medium">解析</div>
              <MarkdownPreview :sdk="model.sdk" :content="question.analysis" />
            </div>
          </template>
        </div>
        </FaCard>
      </template>
      <FaCard v-if="!finished">
        <div class="qb-session-head">
          <span class="text-sm">已作答 {{ answeredCount }} / {{ model.session.questions.length }} 题</span>
          <FaButton :loading="model.submitting" @click="confirmSubmit"><FaIcon name="i-ri:check-line" />提交作答</FaButton>
        </div>
      </FaCard>
    </div>
  </FaPageMain>
</template>
