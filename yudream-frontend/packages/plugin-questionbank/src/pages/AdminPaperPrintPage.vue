<script setup lang="ts">
import type { QuestionBankPluginModel } from '../composables/useQuestionBankPlugin'
import type { QuestionView } from '../types'
import { FaButton, FaCheckbox, FaIcon, FaPageHeader, FaPageMain, FaTag } from '@yudream/components'
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import MarkdownPreview from '../components/MarkdownPreview.vue'
import { difficultyLabel, formatTime, optionKey } from '../composables/utils'

const props = defineProps<{ model: QuestionBankPluginModel }>()
const model = props.model
const route = useRoute()

const paperId = String(route.query.id ?? '')
const showAnswers = ref(false)

const data = computed(() => model.printData)

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

function redraw() {
  if (paperId) {
    void model.loadPrint(paperId)
  }
}

function doPrint() {
  window.print()
}

onMounted(() => {
  if (paperId) {
    void model.loadPrint(paperId)
  }
})
</script>

<template>
  <FaPageHeader class="qb-no-print" title="打印题单" description="随机抽题题单每次打开/重新抽题都会换题；浏览器打印对话框中可另存为 PDF">
    <template v-if="data">
      <FaCheckbox v-if="data.paper.mode === 'RULE'" v-model="showAnswers">附答案与解析</FaCheckbox>
      <FaButton v-if="data.paper.mode === 'RULE'" variant="outline" :loading="model.printLoading" @click="redraw">
        <FaIcon name="i-ri:shuffle-line" />重新抽题
      </FaButton>
      <FaButton :disabled="model.printLoading" @click="doPrint"><FaIcon name="i-ri:printer-line" />打印 / 存为 PDF</FaButton>
    </template>
  </FaPageHeader>
  <FaPageMain>
    <div v-if="model.printLoading && !data" class="qb-print">加载中…</div>
    <div v-else-if="!data" class="qb-print">
      <div class="qb-muted text-center py-10">题单不存在或没有可打印的题目</div>
    </div>
    <div v-else class="qb-print">
      <div class="qb-print-head">
        <h1 class="qb-print-title">{{ data.paper.name }}</h1>
        <div v-if="data.paper.description" class="qb-print-desc">{{ data.paper.description }}</div>
        <div class="qb-print-meta">
          <span>题量：{{ data.questions.length }} 题</span>
          <span v-if="data.categoryName">分类：{{ data.categoryName }}</span>
          <span>抽题时间：{{ formatTime(data.drawnAt) }}</span>
          <span v-if="data.paper.mode === 'RULE'" class="qb-no-print">（随机抽题，重新抽题将更换题目）</span>
        </div>
        <div class="qb-print-name">姓名：________________　得分：________________</div>
      </div>
      <div v-for="(question, index) in data.questions" :key="question.id ?? index" class="qb-print-question">
        <div class="qb-question-meta">
          <strong>{{ index + 1 }}.</strong>
          <FaTag variant="secondary">{{ question.typeLabel }}</FaTag>
          <FaTag variant="outline">{{ difficultyLabel(question.difficulty) }}</FaTag>
        </div>
        <MarkdownPreview :sdk="model.sdk" :content="question.content" />
        <div v-if="question.type === 'SINGLE' || question.type === 'MULTIPLE'" class="qb-options">
          <div v-for="(option, optionIndex) in question.options" :key="optionIndex" class="qb-option qb-print-option">
            <span class="qb-option-key">{{ optionKey(optionIndex) }}</span>
            <MarkdownPreview :sdk="model.sdk" :content="option" />
          </div>
        </div>
        <div v-else-if="question.type === 'TRUE_FALSE'" class="qb-options">
          <div class="qb-option qb-print-option"><span class="qb-option-key">正确</span></div>
          <div class="qb-option qb-print-option"><span class="qb-option-key">错误</span></div>
        </div>
        <div v-else-if="question.type === 'FILL'" class="qb-print-fill">（共 {{ question.blanks?.length ?? 0 }} 空，请写在题后横线上）</div>
        <div v-else class="qb-print-short">
          <div v-for="line in 4" :key="line" class="qb-print-answer-line" />
        </div>
        <template v-if="showAnswers">
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
        </template>
      </div>
    </div>
  </FaPageMain>
</template>
