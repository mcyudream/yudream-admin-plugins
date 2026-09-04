<script setup lang="ts">
import type { QuestionBankPluginModel } from '../composables/useQuestionBankPlugin'
import { Select as ASelect } from '@arco-design/web-vue'
import { FaButton, FaCard, FaCheckboxGroup, FaIcon, FaNumberField, FaPageHeader, FaPageMain } from '@yudream/components'
import { computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import CategorySelect from '../components/CategorySelect.vue'
import { difficultyLabel } from '../composables/utils'

const props = defineProps<{ model: QuestionBankPluginModel }>()
const model = props.model
const router = useRouter()

const tagOptions = computed(() => Object.entries(model.meta?.tags ?? {})
  .map(([name, count]) => ({ label: `${name}（${count}）`, value: name })))

const typeOptions = computed(() => (model.meta?.types ?? [])
  .map(item => ({ label: `${item.label}（${item.count}）`, value: item.type })))

const difficultyOptions = [1, 2, 3, 4, 5].map(value => ({ label: difficultyLabel(value), value }))

const starting = computed(() => model.loading)
const practiceClosed = computed(() => model.meta !== null && model.meta.practiceEnabled === false)
const startDisabled = computed(() => practiceClosed.value || model.poolCount === null || model.poolCount <= 0 || model.counting || starting.value)

async function start() {
  const created = await model.startPractice()
  if (created) {
    router.push({ path: '/platform/plugins/questionbank/session', query: { id: created.id } })
  }
}

watch(model.practiceFilter, () => {
  void model.refreshPoolCount()
}, { deep: true })

onMounted(async () => {
  await model.loadMeta()
  await model.refreshPoolCount()
})
</script>

<template>
  <FaPageHeader title="抽题练习" description="按分类、标签、题型与难度自由组合，随机抽题在线作答">
    <FaButton variant="outline" @click="router.push('/platform/plugins/questionbank/papers')">
      <FaIcon name="i-ri:file-paper-2-line" />题单作答
    </FaButton>
    <FaButton variant="outline" @click="router.push('/platform/plugins/questionbank/records')">
      <FaIcon name="i-ri:history-line" />成绩记录
    </FaButton>
  </FaPageHeader>
  <FaPageMain>
    <div v-if="practiceClosed" class="qb-practice">
      <FaCard>
        <div class="flex flex-col items-center gap-3 py-6">
          <span class="qb-muted">自由刷题功能已关闭，请通过题单进行作答</span>
          <FaButton @click="router.push('/platform/plugins/questionbank/papers')"><FaIcon name="i-ri:file-paper-2-line" />去题单作答</FaButton>
        </div>
      </FaCard>
    </div>
    <div v-else class="qb-practice">
      <FaCard>
        <div class="qb-practice-summary">
          <div>
            <div class="qb-practice-count">{{ model.meta?.total ?? 0 }}</div>
            <div class="qb-muted text-sm">题库中可用的题目</div>
          </div>
          <div class="qb-muted text-sm">
            {{ model.meta?.categories.length ?? 0 }} 个分类 · {{ Object.keys(model.meta?.tags ?? {}).length }} 个标签
          </div>
        </div>
      </FaCard>
      <FaCard title="抽题条件" description="不勾选即不限制该维度">
        <div class="qb-form">
          <div class="qb-form-row">
            <span class="qb-form-label">分类</span>
            <CategorySelect
              v-model="model.practiceFilter.categoryId"
              :categories="model.meta?.categories ?? []"
              empty-label="全部分类"
              placeholder="不限分类"
            />
          </div>
          <div class="qb-form-row">
            <span class="qb-form-label">标签（命中任一即可）</span>
            <ASelect
              v-model="model.practiceFilter.tags"
              :options="tagOptions"
              placeholder="不限标签"
              multiple
              allow-search
            />
          </div>
          <div class="qb-form-row">
            <span class="qb-form-label">题型</span>
            <FaCheckboxGroup v-model="model.practiceFilter.types" :options="typeOptions" class="flex flex-wrap gap-3" />
          </div>
          <div class="qb-form-row">
            <span class="qb-form-label">难度</span>
            <FaCheckboxGroup v-model="model.practiceFilter.difficulties" :options="difficultyOptions" class="flex flex-wrap gap-3" />
          </div>
          <div class="qb-form-row">
            <span class="qb-form-label">抽题数量（1~50）</span>
            <FaNumberField v-model="model.practiceFilter.count" :min="1" :max="50" />
          </div>
        </div>
      </FaCard>
      <FaCard>
        <div class="qb-practice-summary">
          <div class="text-sm">
            <span v-if="model.counting" class="qb-muted">正在计算题量…</span>
            <template v-else-if="model.poolCount !== null">
              当前条件下可抽 <strong>{{ model.poolCount }}</strong> 道题
              <span v-if="model.poolCount > 0 && model.poolCount < model.practiceFilter.count" class="qb-muted">
                （不足 {{ model.practiceFilter.count }} 道时将全部抽出）
              </span>
            </template>
            <span v-else class="qb-muted">调整条件后自动计算可抽题量</span>
          </div>
          <FaButton :disabled="startDisabled" :loading="starting" @click="start">
            <FaIcon name="i-ri:play-line" />开始练习
          </FaButton>
        </div>
      </FaCard>
    </div>
  </FaPageMain>
</template>
