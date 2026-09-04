<script setup lang="ts">
import type { QuestionBankPluginModel } from '../composables/useQuestionBankPlugin'
import type { QuestionPayload } from '../types'
import { FaButton, FaCheckboxGroup, FaIcon, FaInput, FaNumberField, FaPageHeader, FaPageMain, FaRadioGroup, FaSelect, useFaToast } from '@yudream/components'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import CategorySelect from '../components/CategorySelect.vue'
import MarkdownEditor from '../components/MarkdownEditor.vue'
import TagPicker from '../components/TagPicker.vue'
import { optionKey, QUESTION_TYPE_OPTIONS } from '../composables/utils'

const props = defineProps<{ model: QuestionBankPluginModel }>()
const model = props.model
const route = useRoute()
const router = useRouter()
const toast = useFaToast()

const questionId = computed(() => String(route.query.id || ''))
const loading = ref(false)
const saving = ref(false)

const form = reactive({
  type: 'SINGLE',
  categoryId: '',
  tags: [] as string[],
  content: '',
  options: ['', ''] as string[],
  answer: '',
  answers: [] as string[],
  blanks: [['']] as string[][],
  referenceAnswer: '',
  analysis: '',
  difficulty: 3,
  status: 'ENABLED',
})

const isChoice = computed(() => form.type === 'SINGLE' || form.type === 'MULTIPLE')

const answerOptions = computed(() =>
  form.options.map((text, index) => ({ label: `${optionKey(index)}. ${text || '（空选项）'}`, value: optionKey(index) })),
)

const statusOptions = [
  { label: '启用', value: 'ENABLED' },
  { label: '停用（不参与抽题）', value: 'DISABLED' },
]

const trueFalseOptions = [
  { label: '正确', value: 'TRUE' },
  { label: '错误', value: 'FALSE' },
]

function addOption() {
  if (form.options.length >= 26) {
    toast.warning('最多 26 个选项')
    return
  }
  form.options.push('')
}

function removeOption(index: number) {
  if (form.options.length <= 2) {
    toast.warning('至少保留 2 个选项')
    return
  }
  const key = optionKey(index)
  form.options.splice(index, 1)
  // 选项删除后键字母前移，超出范围的已选答案清掉，由管理员重新选择
  form.answer = form.answer === key || form.answer >= optionKey(form.options.length) ? '' : form.answer
  form.answers = form.answers.filter(item => item !== key && item < optionKey(form.options.length))
}

function addBlank() {
  if (form.blanks.length >= 20) {
    toast.warning('最多 20 个空')
    return
  }
  form.blanks.push([''])
}

function removeBlank(index: number) {
  if (form.blanks.length <= 1) {
    toast.warning('至少保留 1 个空')
    return
  }
  form.blanks.splice(index, 1)
}

function addAccepted(blankIndex: number) {
  form.blanks[blankIndex].push('')
}

function removeAccepted(blankIndex: number, answerIndex: number) {
  if (form.blanks[blankIndex].length <= 1) {
    return
  }
  form.blanks[blankIndex].splice(answerIndex, 1)
}

async function loadQuestion() {
  if (!questionId.value) {
    return
  }
  loading.value = true
  try {
    const question = await model.api.adminQuestion(questionId.value)
    form.type = question.type
    form.categoryId = question.categoryId || ''
    form.tags = [...question.tags]
    form.content = question.content
    form.options = question.options.length ? [...question.options] : ['', '']
    form.answer = question.answer || ''
    form.answers = question.answers ? [...question.answers] : []
    form.blanks = question.blanks?.length ? question.blanks.map(list => [...list]) : [['']]
    form.referenceAnswer = question.referenceAnswer || ''
    form.analysis = question.analysis || ''
    form.difficulty = question.difficulty || 3
    form.status = question.status || 'ENABLED'
  }
  catch (error) {
    toast.error(error instanceof Error ? error.message : '题目加载失败')
  }
  finally {
    loading.value = false
  }
}

function validate(): string {
  if (!form.content.trim()) {
    return '题干不能为空'
  }
  if (isChoice.value) {
    if (form.options.some(item => !item.trim())) {
      return '选项内容不能为空'
    }
    if (form.type === 'SINGLE' && !form.answer) {
      return '请选择单选题答案'
    }
    if (form.type === 'MULTIPLE' && !form.answers.length) {
      return '请勾选多选题答案'
    }
  }
  if (form.type === 'TRUE_FALSE' && !form.answer) {
    return '请选择判断题答案'
  }
  if (form.type === 'FILL') {
    if (form.blanks.some(blank => blank.every(item => !item.trim()))) {
      return '每个空至少需要一个有效答案'
    }
  }
  if (form.type === 'SHORT' && !form.referenceAnswer.trim()) {
    return '简答题需要参考答案'
  }
  return ''
}

function buildPayload(): QuestionPayload {
  const payload: QuestionPayload = {
    type: form.type,
    categoryId: form.categoryId || null,
    tags: [...form.tags],
    content: form.content,
    difficulty: form.difficulty,
    status: form.status,
    analysis: form.analysis || undefined,
  }
  if (isChoice.value) {
    payload.options = form.options.map(item => item.trim())
  }
  if (form.type === 'SINGLE') {
    payload.answer = form.answer
  }
  else if (form.type === 'MULTIPLE') {
    payload.answers = [...form.answers]
  }
  else if (form.type === 'TRUE_FALSE') {
    payload.answer = form.answer
  }
  else if (form.type === 'FILL') {
    payload.blanks = form.blanks.map(blank => blank.map(item => item.trim()).filter(Boolean))
  }
  else if (form.type === 'SHORT') {
    payload.referenceAnswer = form.referenceAnswer
  }
  return payload
}

async function save() {
  const message = validate()
  if (message) {
    toast.warning(message)
    return
  }
  saving.value = true
  try {
    if (questionId.value) {
      await model.api.updateQuestion(questionId.value, buildPayload())
      toast.success('题目已更新')
    }
    else {
      await model.api.createQuestion(buildPayload())
      toast.success('题目已创建')
    }
    void router.push({ path: '/platform/plugins/questionbank/admin' })
  }
  catch (error) {
    toast.error(error instanceof Error ? error.message : '保存失败')
  }
  finally {
    saving.value = false
  }
}

function back() {
  void router.push({ path: '/platform/plugins/questionbank/admin' })
}

onMounted(async () => {
  await model.loadAdminCategories()
  await model.loadAdminTags()
  await loadQuestion()
})
</script>

<template>
  <FaPageHeader :title="questionId ? '编辑题目' : '新建题目'" description="题干、解析、参考答案均支持 Markdown 与图片">
    <FaButton variant="outline" @click="back"><FaIcon name="i-ri:arrow-left-line" />返回题目管理</FaButton>
  </FaPageHeader>
  <FaPageMain>
    <div v-loading="loading" class="qb-form">
      <div class="qb-form-row">
        <span class="qb-form-label">题型 <em class="qb-required">*</em></span>
        <FaRadioGroup v-model="form.type" :options="QUESTION_TYPE_OPTIONS" />
      </div>
      <div class="grid grid-cols-1 gap-4 md:grid-cols-2">
        <div class="qb-form-row">
          <span class="qb-form-label">分类</span>
          <CategorySelect v-model="form.categoryId" :categories="model.adminCategories" />
        </div>
        <div class="qb-form-row">
          <span class="qb-form-label">难度（1 最易，5 最难）</span>
          <FaNumberField v-model="form.difficulty" :min="1" :max="5" />
        </div>
      </div>
      <div class="qb-form-row">
        <span class="qb-form-label">标签</span>
        <TagPicker v-model="form.tags" :tags="model.adminTags" />
      </div>
      <div class="qb-form-row">
        <span class="qb-form-label">题干（Markdown） <em class="qb-required">*</em></span>
        <MarkdownEditor v-model="form.content" placeholder="输入题干，支持 Markdown 与图片" :upload-image="model.uploadMarkdownImage" />
      </div>

      <template v-if="isChoice">
        <div class="qb-form-row">
          <span class="qb-form-label">选项 <em class="qb-required">*</em></span>
          <div class="flex flex-col gap-2">
            <div v-for="(option, index) in form.options" :key="index" class="flex items-center gap-2">
              <span class="qb-option-key shrink-0">{{ optionKey(index) }}.</span>
              <FaInput v-model="form.options[index]" :placeholder="`选项 ${optionKey(index)} 内容`" clearable />
              <FaButton size="sm" variant="outline" class="shrink-0" :disabled="form.options.length <= 2" @click="removeOption(index)">
                <FaIcon name="i-ri:delete-bin-line" />
              </FaButton>
            </div>
            <div>
              <FaButton size="sm" variant="outline" @click="addOption"><FaIcon name="i-ri:add-line" />添加选项</FaButton>
            </div>
          </div>
        </div>
        <div class="qb-form-row">
          <span class="qb-form-label">答案 <em class="qb-required">*</em></span>
          <FaRadioGroup v-if="form.type === 'SINGLE'" v-model="form.answer" :options="answerOptions" />
          <FaCheckboxGroup v-else v-model="form.answers" :options="answerOptions" />
        </div>
      </template>

      <div v-else-if="form.type === 'TRUE_FALSE'" class="qb-form-row">
        <span class="qb-form-label">答案 <em class="qb-required">*</em></span>
        <FaRadioGroup v-model="form.answer" :options="trueFalseOptions" />
      </div>

      <div v-else-if="form.type === 'FILL'" class="qb-form-row">
        <span class="qb-form-label">填空答案 <em class="qb-required">*</em>（每空可填多个同义答案，判分时忽略大小写与首尾空格）</span>
        <div class="qb-blank-editor">
          <div v-for="(blank, blankIndex) in form.blanks" :key="blankIndex" class="flex flex-col gap-2 rounded-md border p-3">
            <div class="flex items-center justify-between">
              <span class="qb-form-label">第 {{ blankIndex + 1 }} 空</span>
              <FaButton size="sm" variant="outline" :disabled="form.blanks.length <= 1" @click="removeBlank(blankIndex)">删除此空</FaButton>
            </div>
            <div v-for="(accepted, answerIndex) in blank" :key="answerIndex" class="qb-blank-accepted">
              <FaInput v-model="blank[answerIndex]" placeholder="可接受答案" clearable />
              <FaButton size="sm" variant="outline" class="shrink-0" :disabled="blank.length <= 1" @click="removeAccepted(blankIndex, answerIndex)">
                <FaIcon name="i-ri:delete-bin-line" />
              </FaButton>
            </div>
            <div>
              <FaButton size="sm" variant="outline" @click="addAccepted(blankIndex)"><FaIcon name="i-ri:add-line" />添加同义答案</FaButton>
            </div>
          </div>
          <div>
            <FaButton size="sm" variant="outline" @click="addBlank"><FaIcon name="i-ri:add-line" />添加一个空</FaButton>
          </div>
        </div>
      </div>

      <div v-else-if="form.type === 'SHORT'" class="qb-form-row">
        <span class="qb-form-label">参考答案（Markdown） <em class="qb-required">*</em></span>
        <MarkdownEditor v-model="form.referenceAnswer" placeholder="简答题由作答者对照参考答案自评对错" :upload-image="model.uploadMarkdownImage" />
      </div>

      <div class="qb-form-row">
        <span class="qb-form-label">解析（Markdown，可选）</span>
        <MarkdownEditor v-model="form.analysis" placeholder="提交后展示给作答者" :upload-image="model.uploadMarkdownImage" />
      </div>
      <div class="qb-form-row">
        <span class="qb-form-label">状态</span>
        <FaSelect v-model="form.status" :options="statusOptions" />
      </div>
      <div class="flex gap-2">
        <FaButton :loading="saving" @click="save"><FaIcon name="i-ri:save-line" />保存题目</FaButton>
        <FaButton variant="outline" @click="back">取消</FaButton>
      </div>
    </div>
  </FaPageMain>
</template>
