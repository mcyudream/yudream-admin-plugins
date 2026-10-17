<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { Feedback, FeedbackSubmitPayload } from '../types'
import { YdButton, YdIcon, YdPageHeader, YdPageMain, YdTag, useYdToast } from '@yudream/components'
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { createFeedbackUserApi } from '../api/feedback-api'
import FeedbackForm from '../components/FeedbackForm.vue'
import { errorMessage } from '../composables/utils'

/**
 * 修改反馈：只在未完结时可改，后端会再次校验，前端仅作为入口控制。
 *
 * <p>修改不改变受理进度，也不写入评论，只在处理时间线上留一条「修改内容」的痕迹。
 */
const props = defineProps<{ sdk: YuDreamPluginSdk }>()

const api = createFeedbackUserApi(props.sdk)
const route = useRoute()
const router = useRouter()
const toast = useYdToast()

const feedbackId = String(route.params.id ?? '')

const loading = ref(true)
const loadError = ref('')
const feedback = ref<Feedback | null>(null)
const submitting = ref(false)

onMounted(() => {
  void load()
})

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    feedback.value = await api.detail(feedbackId)
  }
  catch (cause: unknown) {
    feedback.value = null
    loadError.value = errorMessage(cause, '反馈加载失败或已被撤回')
  }
  finally {
    loading.value = false
  }
}

async function submit(payload: FeedbackSubmitPayload) {
  if (submitting.value || !feedback.value) {
    return
  }
  submitting.value = true
  try {
    await api.update(feedback.value.id, payload)
    toast.success('反馈内容已更新')
    void router.push({ path: `/platform/plugins/feedback/me/feedbacks/${feedback.value.id}` })
  }
  catch (cause: unknown) {
    toast.error(errorMessage(cause, '更新失败，请稍后重试'))
  }
  finally {
    submitting.value = false
  }
}

function back() {
  void router.push(feedback.value
    ? `/platform/plugins/feedback/me/feedbacks/${feedback.value.id}`
    : '/platform/plugins/feedback/me/feedbacks')
}
</script>

<template>
  <div class="fb-page">
    <YdPageHeader title="修改反馈" description="仅未完结的反馈可以修改；修改不会改变当前处理进度" />

    <YdPageMain>
      <div v-if="loading" class="fb-state">
        正在加载反馈内容…
      </div>

      <div v-else-if="loadError" class="fb-state is-error">
        <YdIcon name="i-ri:error-warning-line" />
        <p>{{ loadError }}</p>
        <YdButton variant="outline" @click="back">
          返回我的反馈
        </YdButton>
      </div>

      <div v-else-if="feedback" class="fb-edit">
        <!-- 已完结的反馈正常情况下进不到这里，但直接输网址或从旧标签页进入仍可能命中，需给出明确解释。 -->
        <div v-if="!feedback.canEdit" class="fb-edit__locked">
          <YdIcon name="i-ri:lock-line" />
          <div>
            <p class="fb-edit__locked-title">该反馈已完结，无法再修改内容</p>
            <p class="fb-muted">
              当前状态：<YdTag variant="secondary">{{ feedback.statusLabel }}</YdTag>
              如需继续处理，请在详情页追加说明。
            </p>
          </div>
          <YdButton variant="outline" @click="back">
            返回详情
          </YdButton>
        </div>

        <template v-else>
          <p class="fb-edit__meta">
            正在修改「{{ feedback.title }}」 · 提交于 {{ feedback.createdAtLabel }}
          </p>
          <FeedbackForm
            :sdk="sdk"
            :source="feedback"
            :submitting="submitting"
            submit-text="保存修改"
            @submit="submit"
            @cancel="back"
          />
        </template>
      </div>
    </YdPageMain>
  </div>
</template>

<style scoped>
.fb-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--fb-sp-3);
  min-height: 200px;
  padding: var(--fb-sp-6);
  border: 1px dashed var(--fb-border);
  border-radius: var(--fb-radius);
  background: var(--fb-surface);
  color: var(--fb-text-3);
  font-size: 13px;
}

.fb-state.is-error {
  color: rgb(var(--danger-6));
}

.fb-state p {
  margin: 0;
}

.fb-edit {
  display: grid;
  gap: var(--fb-sp-4);
}

.fb-edit__meta {
  margin: 0;
  color: var(--fb-text-3);
  font-size: 12px;
}

.fb-edit__locked {
  display: flex;
  align-items: center;
  gap: var(--fb-sp-4);
  padding: var(--fb-sp-5);
  border: 1px solid var(--fb-border);
  border-radius: var(--fb-radius);
  background: var(--fb-surface-muted);
  color: var(--fb-text-3);
}

.fb-edit__locked > div {
  flex: 1;
  min-width: 0;
}

.fb-edit__locked-title {
  margin: 0 0 var(--fb-sp-2);
  color: var(--fb-text-1);
  font-size: 13px;
  font-weight: 600;
}
</style>
