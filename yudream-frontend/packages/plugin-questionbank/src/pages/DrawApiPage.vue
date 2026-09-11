<script setup lang="ts">
import type { QuestionBankPluginModel } from '../composables/useQuestionBankPlugin'
import type { DrawApiQuery, DrawApiResult } from '../types'
import { Select as ASelect } from '@arco-design/web-vue'
import { FaButton, FaCard, FaCheckboxGroup, FaIcon, FaInput, FaNumberField, FaPageHeader, FaPageMain, FaSelect, useFaToast } from '@yudream/components'
import { computed, onMounted, reactive, ref } from 'vue'
import CategorySelect from '../components/CategorySelect.vue'
import { difficultyLabel, errorMessage, QUESTION_TYPE_OPTIONS } from '../composables/utils'

const props = defineProps<{ model: QuestionBankPluginModel }>()
const model = props.model
const toast = useFaToast()

const filter = reactive({
  categoryId: '',
  tags: [] as string[],
  types: [] as string[],
  difficulty: undefined as number | undefined,
  seed: '',
  count: 1,
})
const drawing = ref(false)
const result = ref<DrawApiResult | null>(null)
const lastError = ref('')

const tagOptions = computed(() => model.drawOptions.tags.map(tag => ({
  label: `${tag.name}（${tag.count}）`,
  value: tag.name,
})))
const typeOptions = QUESTION_TYPE_OPTIONS.map(item => ({ label: item.label, value: item.value }))
const difficultyOptions = [
  { label: '不限难度', value: '' },
  ...[1, 2, 3, 4, 5].map(value => ({ label: difficultyLabel(value), value: String(value) })),
]
const difficultyValue = computed({
  get: () => (filter.difficulty == null ? '' : String(filter.difficulty)),
  set: (value: string) => {
    filter.difficulty = value === '' ? undefined : Number(value)
  },
})

const queryParams = computed<DrawApiQuery>(() => ({
  categoryId: filter.categoryId || undefined,
  tags: filter.tags.length ? filter.tags.join(',') : undefined,
  type: filter.types.length ? filter.types.join(',') : undefined,
  difficulty: filter.difficulty,
  seed: filter.seed.trim() || undefined,
  count: filter.count,
}))

const requestPath = computed(() => {
  const params = new URLSearchParams()
  const query = queryParams.value
  if (query.categoryId) {
    params.set('categoryId', query.categoryId)
  }
  if (query.tags) {
    params.set('tags', query.tags)
  }
  if (query.type) {
    params.set('type', query.type)
  }
  if (query.difficulty != null) {
    params.set('difficulty', String(query.difficulty))
  }
  if (query.seed) {
    params.set('seed', query.seed)
  }
  if (query.count != null) {
    params.set('count', String(query.count))
  }
  const text = params.toString()
  return `/api/plugins/questionbank/draw${text ? `?${text}` : ''}`
})

const requestJson = computed(() => JSON.stringify({
  method: 'GET',
  url: requestPath.value,
  query: queryParams.value,
}, null, 2))

const curlExample = computed(() => `curl "${window.location.origin}${requestPath.value}" \\
  -H "X-API-Key: yda_在此填入你的密钥"`)

const responseJson = computed(() => {
  if (lastError.value) {
    return lastError.value
  }
  if (!result.value) {
    return ''
  }
  return JSON.stringify(result.value, null, 2)
})

onMounted(() => {
  void model.loadDrawOptions()
})

async function draw() {
  if (filter.count < 1 || filter.count > 50) {
    toast.warning('抽题数量需为 1-50 的整数')
    return
  }
  drawing.value = true
  lastError.value = ''
  try {
    result.value = await model.api.draw(queryParams.value)
  }
  catch (error) {
    result.value = null
    lastError.value = JSON.stringify({ error: errorMessage(error) }, null, 2)
    toast.error(errorMessage(error))
  }
  finally {
    drawing.value = false
  }
}

async function copyText(text: string, success: string) {
  if (!text) {
    return
  }
  await model.copyText(text)
  toast.success(success)
}

function reuseSeed() {
  if (result.value?.seed) {
    filter.seed = String(result.value.seed)
  }
}
</script>

<template>
  <FaPageHeader title="抽题 API" description="按分类、标签、题型与难度随机抽取启用中的题目（含答案与解析）；页面本身即一次真实调用，也可复制 curl 给外部系统使用">
    <FaButton :loading="drawing" @click="draw">
      <FaIcon name="i-ri:dice-line" />抽题
    </FaButton>
  </FaPageHeader>
  <FaPageMain>
    <div class="qb-draw-api">
      <FaCard title="筛选条件" description="留空表示该维度不限制；仅抽取启用中的题目">
        <div class="qb-form">
          <div class="qb-form-row">
            <span class="qb-form-label">分类</span>
            <CategorySelect
              v-model="filter.categoryId"
              :categories="model.drawOptions.categories"
              empty-label="不限分类"
              placeholder="不限分类"
            />
          </div>
          <div class="qb-form-row">
            <span class="qb-form-label">标签（命中任一即可）</span>
            <ASelect
              v-model="filter.tags"
              :options="tagOptions"
              placeholder="不限标签"
              multiple
              allow-search
              allow-clear
            />
          </div>
          <div class="qb-form-row">
            <span class="qb-form-label">题型</span>
            <FaCheckboxGroup v-model="filter.types" :options="typeOptions" class="flex flex-wrap gap-3" />
          </div>
          <div class="qb-form-row">
            <span class="qb-form-label">难度</span>
            <FaSelect v-model="difficultyValue" :options="difficultyOptions" placeholder="不限难度" />
          </div>
          <div class="qb-form-row">
            <span class="qb-form-label">数量（1~50）</span>
            <FaNumberField v-model="filter.count" :min="1" :max="50" />
          </div>
          <div class="qb-form-row">
            <span class="qb-form-label">随机种子</span>
            <div class="flex flex-wrap items-center gap-2">
              <FaInput v-model="filter.seed" class="min-w-48 flex-1" placeholder="留空则自动生成并回传" />
              <FaButton v-if="result?.seed" size="sm" variant="outline" @click="reuseSeed">
                使用上次种子
              </FaButton>
            </div>
          </div>
          <p class="qb-muted text-sm">
            相同种子与筛选条件会抽出同一组题。创建 API Key 时勾选「题库 → 随机抽题」（plugin:questionbank:use），请求携带 X-API-Key 即可在外部调用。
          </p>
        </div>
      </FaCard>
      <div class="qb-draw-api-io">
        <FaCard title="请求 JSON">
          <div class="flex flex-col gap-2">
            <pre class="qb-api-example">{{ requestJson }}</pre>
            <FaButton size="sm" variant="outline" @click="copyText(requestJson, '请求 JSON 已复制')">
              <FaIcon name="i-ri:file-copy-line" />复制请求
            </FaButton>
          </div>
        </FaCard>
        <FaCard title="响应 JSON">
          <div class="flex flex-col gap-2">
            <pre v-if="responseJson" class="qb-api-example">{{ responseJson }}</pre>
            <p v-else class="qb-muted text-sm">点击右上角「抽题」后，这里会展示接口返回的题目、答案、解析与实际使用的种子。</p>
            <FaButton v-if="responseJson" size="sm" variant="outline" @click="copyText(responseJson, '响应 JSON 已复制')">
              <FaIcon name="i-ri:file-copy-line" />复制响应
            </FaButton>
          </div>
        </FaCard>
      </div>
      <FaCard title="curl 示例" description="把密钥换成系统里创建的 API Key；当前筛选条件会同步进 URL">
        <div class="flex flex-col gap-2">
          <pre class="qb-api-example">{{ curlExample }}</pre>
          <FaButton size="sm" variant="outline" @click="copyText(curlExample, 'curl 示例已复制')">
            <FaIcon name="i-ri:file-copy-line" />复制 curl
          </FaButton>
        </div>
      </FaCard>
    </div>
  </FaPageMain>
</template>
