<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { FeedbackSubmitPayload } from '../types'
import { YdButton, YdIcon, YdPageHeader, YdPageMain, useYdToast } from '@yudream/components'
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { createFeedbackUserApi } from '../api/feedback-api'
import FeedbackForm from '../components/FeedbackForm.vue'
import { errorMessage } from '../composables/utils'

/** 提交反馈：只服务当前登录者，归属由后端从登录身份推导。 */
const props = defineProps<{ sdk: YuDreamPluginSdk }>()

const api = createFeedbackUserApi(props.sdk)
const toast = useYdToast()
const router = useRouter()

const submitting = ref(false)

async function submit(payload: FeedbackSubmitPayload) {
  if (submitting.value) {
    return
  }
  submitting.value = true
  try {
    const created = await api.submit(payload)
    toast.success('反馈已提交，处理进度可在「我的反馈」中跟进')
    // 直接落到详情页，用户能立刻看到处理时间线与后续回复入口。
    void router.push({ path: `/platform/plugins/feedback/me/feedbacks/${created.id}` })
  }
  catch (cause: unknown) {
    toast.error(errorMessage(cause, '提交失败，请稍后重试'))
  }
  finally {
    submitting.value = false
  }
}

function back() {
  void router.push('/platform/plugins/feedback/me/feedbacks')
}
</script>

<template>
  <div class="fb-page">
    <YdPageHeader title="提交反馈" description="描述你遇到的问题或想法，提交后可在「我的反馈」中跟进处理进度" />

    <YdPageMain>
      <FeedbackForm
        :sdk="sdk"
        :submitting="submitting"
        submit-text="提交反馈"
        @submit="submit"
        @cancel="back"
      />

      <div class="fb-submit-tips">
        <p class="fb-submit-tips__title">
          <YdIcon name="i-ri:lightbulb-line" />
          怎样描述更容易被快速处理
        </p>
        <ul>
          <li>写清操作路径：从哪个页面、点了什么、期望结果与实际结果分别是什么。</li>
          <li>附上报错信息原文或截图，避免只写「打不开」「报错了」。</li>
          <li>如果能稳定复现，请说明触发条件与出现频率。</li>
        </ul>
        <p class="fb-submit-tips__foot">
          提交后管理员会受理并给出结论；你可以在详情页补充说明，并在管理员给出结论后归档。
        </p>
      </div>

      <div class="fb-submit-back">
        <YdButton variant="outline" @click="back">
          <YdIcon name="i-ri:arrow-left-line" />
          返回我的反馈
        </YdButton>
      </div>
    </YdPageMain>
  </div>
</template>

<style scoped>
.fb-submit-tips {
  max-width: 860px;
  margin-top: var(--fb-sp-5);
  padding: var(--fb-sp-4) var(--fb-sp-5);
  border: 1px solid var(--fb-border);
  border-radius: var(--fb-radius);
  background: var(--fb-surface-muted);
}

.fb-submit-tips__title {
  display: flex;
  align-items: center;
  gap: var(--fb-sp-2);
  margin: 0 0 var(--fb-sp-3);
  color: var(--fb-text-2);
  font-size: 13px;
  font-weight: 600;
}

.fb-submit-tips ul {
  display: grid;
  gap: var(--fb-sp-2);
  margin: 0;
  padding-left: var(--fb-sp-6);
  color: var(--fb-text-2);
  font-size: 12px;
  line-height: 1.7;
}

.fb-submit-tips__foot {
  margin: var(--fb-sp-3) 0 0;
  color: var(--fb-text-3);
  font-size: 12px;
}

.fb-submit-back {
  margin-top: var(--fb-sp-5);
}
</style>
