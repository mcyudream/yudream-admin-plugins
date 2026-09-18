<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { NewsArticleView, PushLogResult, PushTargetOption } from '../types'
import { FaButton, FaCheckbox, FaCheckboxGroup, FaModal, FaTag, useFaToast } from '@yudream/components'
import { computed, ref, watch } from 'vue'
import { createMcNewsApi } from '../api/mc-news-api'
import { errorMessage, PUSH_STATE_META } from '../composables/utils'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  open: boolean
  /** 待推送的动态（弹窗打开时由列表页传入） */
  article: NewsArticleView | null
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
  'pushed': []
}>()

const api = createMcNewsApi(props.sdk)
const toast = useFaToast()
const pushing = ref(false)
const loadingTargets = ref(false)
/** 可推送目标（已按端点去重）与私信订阅人数，弹窗打开时实时拉取 */
const targets = ref<PushTargetOption[]>([])
const subscribers = ref(0)
/** 勾选的目标 id，默认全选 */
const selected = ref<string[]>([])
/** 同时发送给私信订阅用户（默认否：手动补推多为历史内容，避免打扰订阅者） */
const includeSubscribers = ref(false)

const modalOpen = computed({
  get: () => props.open,
  set: value => emit('update:open', value),
})

const stateMeta = computed(() => PUSH_STATE_META[props.article?.pushState ?? ''] ?? PUSH_STATE_META[''])

const targetOptions = computed(() => targets.value.map(item => ({
  label: item.name,
  value: item.id,
  description: [
    item.owner === 'global' ? '全局' : '个人',
    item.type === 'messaging' ? '群聊' : 'Webhook',
    item.endpointLabel,
  ].filter(Boolean).join(' · '),
})))

/** 被折叠掉的同端点目标总数（同一端点只投一条） */
const mergedTotal = computed(() => targets.value.reduce((sum, item) => sum + (item.mergedCount > 0 ? item.mergedCount : 0), 0))

const allSelected = computed(() => targets.value.length > 0 && selected.value.length === targets.value.length)
const hasTarget = computed(() => selected.value.length > 0 || includeSubscribers.value)
const canSubmit = computed(() => !pushing.value && !loadingTargets.value && hasTarget.value)

/** 每次打开都重新拉取目标并回到默认值：全部勾选 + 不附带私信订阅用户。 */
watch(() => props.open, async (open) => {
  if (!open)
    return
  includeSubscribers.value = false
  selected.value = []
  targets.value = []
  subscribers.value = 0
  loadingTargets.value = true
  try {
    const view = await api.pushTargets()
    targets.value = view?.targets ?? []
    subscribers.value = Number(view?.subscribers ?? 0)
    selected.value = targets.value.map(item => item.id)
  }
  catch (error) {
    toast.error(errorMessage(error, '加载推送目标失败'))
  }
  finally {
    loadingTargets.value = false
  }
})

function toggleAll() {
  selected.value = allSelected.value ? [] : targets.value.map(item => item.id)
}

/** 失败目标的名称与原因，拼成一句可直接读的提示。 */
function failureText(results: PushLogResult[]): string {
  const failed = results.filter(item => !item.ok)
  if (!failed.length)
    return '所有目标均失败'
  return failed.map(item => `${item.targetName || item.targetId || '目标'}：${item.error || '未知原因'}`).join('；')
}

async function submit() {
  const article = props.article
  if (!article)
    return
  if (!hasTarget.value) {
    toast.warning('请至少选择一个推送目标')
    return
  }
  pushing.value = true
  try {
    const result = await api.pushNews(article.id, {
      targetIds: selected.value,
      includeSubscribers: includeSubscribers.value,
    })
    const okCount = Number(result?.okCount ?? 0)
    const total = Number(result?.total ?? 0)
    if (okCount <= 0)
      toast.error(`推送失败：${failureText(result?.results ?? [])}`)
    else if (okCount < total)
      toast.warning(`部分成功 ${okCount}/${total}，失败：${failureText(result?.results ?? [])}`)
    else
      toast.success(`推送成功 ${okCount}/${total}`)
    modalOpen.value = false
    emit('pushed')
  }
  catch (error) {
    toast.error(errorMessage(error, '推送失败'))
  }
  finally {
    pushing.value = false
  }
}
</script>

<template>
  <FaModal
    v-model="modalOpen"
    class="mc-news-push-modal"
    title="手动推送"
    :show-confirm-button="false"
    :close-on-click-overlay="false"
  >
    <div class="mc-news-form">
      <div class="mc-news-push-intro">
        <div class="mc-news-push-head">
          <span class="mc-news-push-title" :title="props.article?.title">{{ props.article?.title }}</span>
          <FaTag variant="secondary">{{ stateMeta.label }}</FaTag>
        </div>
        <p class="mc-news-form-hint">推送结果写回本条状态，不计为新内容，去重记录与列表顺序不变。</p>
      </div>

      <div class="mc-news-clear-option">
        <div class="mc-news-target-actions">
          <span>推送目标</span>
          <span class="mc-news-target-actions-side">
            <span v-if="mergedTotal > 0" class="mc-news-form-hint">同一端点已合并 {{ mergedTotal }} 个目标</span>
            <FaButton size="sm" variant="outline" :disabled="pushing || loadingTargets || !targets.length" @click="toggleAll">
              {{ allSelected ? '全不选' : '全选' }}
            </FaButton>
          </span>
        </div>
        <div v-if="loadingTargets" class="mc-news-form-hint">
          加载推送目标…
        </div>
        <div v-else-if="!targets.length" class="mc-news-form-hint">
          没有启用的推送目标，请先到「推送目标」启用。
        </div>
        <div v-else class="mc-news-target-picker">
          <FaCheckboxGroup v-model="selected" :options="targetOptions" :disabled="pushing" />
        </div>
        <span class="mc-news-form-hint">未勾选的目标本次不会收到；自动推送不受影响。</span>
      </div>

      <div class="mc-news-clear-option" :class="{ 'mc-news-clear-option-muted': subscribers <= 0 }">
        <FaCheckbox v-model="includeSubscribers" :disabled="pushing || subscribers <= 0">
          同时发送给私信订阅用户
        </FaCheckbox>
        <span class="mc-news-form-hint">
          {{ subscribers > 0 ? `${subscribers} 人已开启私信订阅` : '暂无用户开启私信订阅' }}
        </span>
      </div>
    </div>
    <template #footer>
      <FaButton variant="outline" :disabled="pushing" @click="modalOpen = false">
        取消
      </FaButton>
      <FaButton :disabled="!canSubmit" :loading="pushing" @click="submit">
        确认推送
      </FaButton>
    </template>
  </FaModal>
</template>
