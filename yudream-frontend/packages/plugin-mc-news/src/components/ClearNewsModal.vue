<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { FaButton, FaCheckbox, FaModal, useFaToast } from '@yudream/components'
import { computed, ref, watch } from 'vue'
import { createMcNewsApi } from '../api/mc-news-api'
import { errorMessage } from '../composables/utils'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  open: boolean
  /** 当前动态条数 */
  total: number
  /** 去重记忆条数 */
  seen: number
  /** 未送达待补推条数 */
  pending: number
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
  'cleared': []
}>()

const api = createMcNewsApi(props.sdk)
const toast = useFaToast()
const clearing = ref(false)
/** 同时清空轮询缓存（默认否） */
const clearCache = ref(false)
/** 清空缓存后，下一次轮询是否重新推送现有内容（默认否） */
const pushOnNextPoll = ref(false)

const modalOpen = computed({
  get: () => props.open,
  set: value => emit('update:open', value),
})

/** 每次打开都回到默认值：两个选项默认均为否 */
watch(() => props.open, (open) => {
  if (!open)
    return
  clearCache.value = false
  pushOnNextPoll.value = false
})

/** 不勾「同时清空缓存」时，推送选项无从谈起：复位且置灰 */
watch(clearCache, (value) => {
  if (!value)
    pushOnNextPoll.value = false
})

const cacheSummary = computed(() => {
  const parts: string[] = []
  if (props.seen > 0)
    parts.push(`去重记录 ${props.seen} 条`)
  if (props.pending > 0)
    parts.push(`未送达待补推 ${props.pending} 条`)
  return parts.length ? parts.join('、') : '去重记录与未送达队列'
})

async function submit() {
  clearing.value = true
  try {
    const result = await api.clearNews({ clearCache: clearCache.value, pushOnNextPoll: pushOnNextPoll.value })
    const cleared = Number(result?.cleared ?? 0)
    if (result?.cacheCleared) {
      toast.success(result.pushOnNextPoll
        ? `已清空 ${cleared} 条动态与轮询缓存，下次轮询会重新推送现有内容`
        : `已清空 ${cleared} 条动态与轮询缓存，下次轮询只重建缓存、不推送`)
    }
    else {
      toast.success(`已清空 ${cleared} 条动态`)
    }
    modalOpen.value = false
    emit('cleared')
  }
  catch (error) {
    toast.error(errorMessage(error, '清空失败'))
  }
  finally {
    clearing.value = false
  }
}
</script>

<template>
  <FaModal
    v-model="modalOpen"
    class="mc-news-clear-modal"
    title="清空动态"
    :show-confirm-button="false"
    :close-on-click-overlay="false"
  >
    <div class="mc-news-form">
      <p class="mc-news-clear-lead">
        将清空全部 <strong>{{ props.total }}</strong> 条新闻动态。默认只清空列表显示：去重记录与轮询缓存原样保留，
        下次轮询会把源里仍存在的内容重新填回列表，已推送过的不会重复推送。若想让某条新闻不再推送，请改用单条删除。
      </p>

      <div class="mc-news-clear-option">
        <FaCheckbox v-model="clearCache" :disabled="clearing">
          同时清空轮询缓存
        </FaCheckbox>
        <span class="mc-news-form-hint">
          当前缓存：{{ cacheSummary }}。勾选后这些缓存一并清空（去重记录、未送达队列），
          源里现存内容会被视为新内容；忽略名单不受影响，被删除过的新闻不会回来。
        </span>
      </div>

      <div class="mc-news-clear-option" :class="{ 'mc-news-clear-option-muted': !clearCache }">
        <FaCheckbox v-model="pushOnNextPoll" :disabled="clearing || !clearCache">
          下次轮询重新推送现有内容
        </FaCheckbox>
        <span class="mc-news-form-hint">
          不勾（默认）＝下次轮询只回填列表、重建去重记录，一条都不推送；
          勾选＝把源里现有内容重新推送一轮。
        </span>
      </div>
    </div>
    <template #footer>
      <FaButton variant="outline" :disabled="clearing" @click="modalOpen = false">
        取消
      </FaButton>
      <FaButton variant="destructive" :loading="clearing" @click="submit">
        确认清空
      </FaButton>
    </template>
  </FaModal>
</template>
