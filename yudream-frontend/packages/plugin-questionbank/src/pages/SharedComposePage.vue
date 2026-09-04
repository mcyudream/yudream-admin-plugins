<script setup lang="ts">
import type { QuestionBankPluginModel } from '../composables/useQuestionBankPlugin'
import type { QuestionView, SharedComposeView } from '../types'
import { FaIcon, FaTag } from '@yudream/components'
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import MarkdownPreview from '../components/MarkdownPreview.vue'
import { difficultyLabel, errorMessage, formatTime, optionKey } from '../composables/utils'

const props = defineProps<{ model: QuestionBankPluginModel }>()
const model = props.model
const route = useRoute()

const loading = ref(true)
const error = ref('')
const data = ref<SharedComposeView | null>(null)

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
  const token = String(route.query.token ?? '').trim()
  if (!token) {
    error.value = '分享链接缺少 token 参数'
    loading.value = false
    return
  }
  try {
    data.value = await model.api.fetchSharedCompose(token)
  }
  catch (cause) {
    error.value = errorMessage(cause, '分享链接不存在或已取消')
  }
  finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="qb-shared">
    <div v-if="loading" class="qb-shared-state">加载中…</div>
    <div v-else-if="error" class="qb-shared-state">
      <FaIcon name="i-ri:link-unlink" class="qb-shared-state-icon" />
      <div class="qb-shared-state-title">无法打开这份组卷</div>
      <div class="qb-muted">{{ error }}</div>
    </div>
    <template v-else-if="data">
      <div class="qb-print-head">
        <h1 class="qb-print-title">{{ data.title }}</h1>
        <div v-if="data.description" class="qb-print-desc">{{ data.description }}</div>
        <div class="qb-print-meta">
          <span>分享者：{{ data.ownerName }}</span>
          <span>题量：{{ data.questions.length }} 题</span>
          <span v-if="data.createdAt">组卷时间：{{ formatTime(data.createdAt) }}</span>
        </div>
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
      <div v-if="!data.questions.length" class="qb-muted text-center py-10">这份组卷的题目已全部失效</div>
    </template>
  </div>
</template>
