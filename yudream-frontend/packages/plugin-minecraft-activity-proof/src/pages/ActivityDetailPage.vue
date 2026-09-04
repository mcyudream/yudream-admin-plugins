<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { FaButton, FaIcon, FaModal, FaPageHeader, FaPageMain, FaTag } from '@yudream/components'
import { computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useActivityDetail } from '../composables/useActivityDetail'
import { activityStage, activityStageTag, formatTime, formatTimeRange, isActivityEnded } from '../composables/utils'
import MarkdownPreview from '../components/MarkdownPreview.vue'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  route?: RouteLocationNormalizedLoaded
}>()

const router = useRouter()
const model = useActivityDetail(props.sdk)
const { loading, acting, activity, qrVisible, qrDataUrl, quiz, quizActing, joined, canVerify } = model

const activityId = computed(() => String(props.route?.query?.id || ''))

watch(activityId, id => model.load(id), { immediate: true })

const ended = computed(() => !!activity.value && isActivityEnded(activity.value.status, activity.value.activityEnd))

const statusTag = computed(() => {
  if (!activity.value) {
    return activityStageTag('SIGNUP_OPEN')
  }
  return activityStageTag(activityStage(activity.value.status, activity.value.signupStart, activity.value.signupEnd, activity.value.activityEnd))
})

const quizSubjectiveLabel = computed(() => {
  switch (quiz.value?.subjectiveMode) {
    case 'REVIEW':
      return '管理员审核'
    case 'AI':
      return 'AI 判分'
    default:
      return '自评'
  }
})

const quizStatusTag = computed(() => {
  const view = quiz.value
  if (!view) {
    return null
  }
  if (view.passed) {
    return { variant: 'default' as const, text: `答题已达标（${view.correctCount ?? 0}/${view.totalCount ?? view.count}）` }
  }
  if (view.sessionId && view.sessionStatus && view.sessionStatus !== 'FINISHED') {
    return { variant: 'outline' as const, text: '答题进行中' }
  }
  if (view.sessionStatus === 'FINISHED' && view.pendingReview) {
    return { variant: 'outline' as const, text: '已提交，待判分' }
  }
  if (view.sessionStatus === 'FINISHED') {
    return { variant: 'outline' as const, text: `未达标（${view.correctCount ?? 0}/${view.totalCount ?? view.count}），可重试` }
  }
  if (view.attempts > 0) {
    return { variant: 'outline' as const, text: `已尝试 ${view.attempts} 次` }
  }
  return { variant: 'outline' as const, text: '未开始' }
})

const quizActionText = computed(() => {
  const view = quiz.value
  if (!view) {
    return '开始答题'
  }
  if (view.sessionId && view.sessionStatus && view.sessionStatus !== 'FINISHED') {
    return '继续答题'
  }
  return '开始答题'
})

const canStartQuiz = computed(() => {
  const view = quiz.value
  return !!view?.enabled && view.available && view.joined && !view.passed
    && !(view.sessionStatus === 'FINISHED' && view.pendingReview)
})

// 达标条件优先用结构化的 requirementDetails（表单类可跳填写页），旧数据回退纯文案
const requirementItems = computed(() => {
  const value = activity.value
  if (!value) {
    return []
  }
  if (value.requirementDetails?.length) {
    return value.requirementDetails
  }
  return (value.requirements || []).map(text => ({ type: '', text, formCode: '', formName: '' }))
})

function goForm(formCode: string) {
  if (!formCode) {
    return
  }
  router.push({ path: `/forms/${encodeURIComponent(formCode)}` })
}

async function goQuiz() {
  const sessionId = await model.startQuiz()
  if (sessionId) {
    router.push({ path: '/platform/plugins/questionbank/session', query: { id: sessionId } })
  }
}

function back() {
  router.push({ path: '/platform/plugins/yudream-student-info/activity-square' })
}
</script>

<template>
  <section class="proof-page">
    <FaPageHeader :title="activity?.title || '活动详情'" class="mb-0">
      <div class="flex flex-wrap gap-2">
        <FaButton variant="outline" @click="back">
          <FaIcon name="i-ri:arrow-left-line" />返回广场
        </FaButton>
        <FaButton v-if="activity" variant="outline" @click="model.openQr">
          <FaIcon name="i-ri:qr-code-line" />参与二维码
        </FaButton>
      </div>
    </FaPageHeader>
    <FaPageMain>
      <div v-loading="loading" class="activity-detail">
        <div v-if="!loading && !activity" class="activity-square-empty">
          <FaIcon name="i-ri:error-warning-line" class="text-4xl text-muted-foreground" />
          <p>活动不存在或未发布</p>
        </div>
        <template v-else-if="activity">
          <div class="activity-detail-hero" :class="{ 'activity-detail-hero-empty': !activity.coverUrl }">
            <img v-if="activity.coverUrl" :src="model.coverOf(activity)" :alt="activity.title">
            <div v-else class="activity-card-cover-placeholder activity-detail-hero-placeholder">
              <FaIcon name="i-ri:image-line" class="text-4xl" />
            </div>
          </div>
          <div class="activity-detail-layout">
            <div class="activity-detail-main">
              <div class="flex flex-wrap items-center gap-2">
                <FaTag :variant="statusTag.variant">
                  {{ statusTag.text }}
                </FaTag>
                <FaTag v-if="joined" variant="outline">
                  已参与
                </FaTag>
                <FaTag v-if="activity.verifyStatus === 'PASSED'" variant="default">
                  核验通过
                </FaTag>
              </div>
              <h2 class="activity-detail-title">{{ activity.title }}</h2>
              <p v-if="activity.summary" class="activity-detail-summary">{{ activity.summary }}</p>
              <div class="activity-detail-meta">
                <div><FaIcon name="i-ri:time-line" /><span>活动时间：{{ formatTimeRange(activity.activityStart, activity.activityEnd) }}</span></div>
                <div><FaIcon name="i-ri:door-open-line" /><span>报名时间：{{ formatTimeRange(activity.signupStart, activity.signupEnd) }}</span></div>
                <div><FaIcon name="i-ri:user-line" /><span>{{ activity.participantCount }} 人已参与</span></div>
                <div v-if="activity.deptRestricted">
                  <FaIcon name="i-ri:group-line" /><span>仅限 {{ (activity.allowedDeptNames || []).join('、') || '指定部门' }} 成员参与</span>
                </div>
              </div>
            </div>
            <aside class="activity-detail-side">
              <div class="activity-detail-requirements">
                <h3>达标条件</h3>
                <ul v-if="requirementItems.length">
                  <li v-for="(requirement, index) in requirementItems" :key="index">
                    <FaIcon name="i-ri:checkbox-circle-line" />
                    <a
                      v-if="requirement.type === 'FORM' && requirement.formCode"
                      class="activity-requirement-link"
                      :href="`/forms/${encodeURIComponent(requirement.formCode)}`"
                      @click.prevent="goForm(requirement.formCode)"
                    >{{ requirement.text }}<FaIcon name="i-ri:arrow-right-up-line" /></a>
                    <template v-else>{{ requirement.text }}</template>
                  </li>
                </ul>
                <p v-else>参与活动即视为达标</p>
                <p v-if="requirementItems.length > 1" class="text-sm text-muted-foreground">
                  满足任意一项即通过核验
                </p>
              </div>
              <div v-if="quiz?.enabled" class="activity-detail-requirements">
                <h3>答题环节</h3>
                <div class="flex flex-wrap items-center gap-2">
                  <FaTag v-if="quizStatusTag" :variant="quizStatusTag.variant">
                    {{ quizStatusTag.text }}
                  </FaTag>
                </div>
                <ul>
                  <li><FaIcon name="i-ri:questionnaire-line" />随机抽取 {{ quiz.count }} 题，答对 {{ quiz.passCorrect }} 题即达标</li>
                  <li v-if="quiz.subjectiveMode"><FaIcon name="i-ri:edit-2-line" />简答题判分：{{ quizSubjectiveLabel }}</li>
                </ul>
                <template v-if="!quiz.available">
                  <p class="text-sm text-muted-foreground">题库插件暂不可用，答题环节暂时关闭</p>
                </template>
                <template v-else-if="!joined">
                  <p class="text-sm text-muted-foreground">请先参与活动后再进行答题</p>
                </template>
                <FaButton
                  v-if="canStartQuiz"
                  :loading="quizActing"
                  @click="goQuiz"
                >
                  <FaIcon name="i-ri:play-line" />{{ quizActionText }}
                </FaButton>
              </div>
              <div v-if="activity.verifyNote" class="activity-detail-note">
                <FaIcon name="i-ri:information-line" />{{ activity.verifyNote }}
              </div>
              <div class="activity-detail-actions">
                <template v-if="!joined">
                  <FaButton
                    size="lg"
                    :loading="acting"
                    :disabled="!!activity.joinDisabledReason"
                    @click="model.join"
                  >
                    <FaIcon name="i-ri:check-line" />立即参与
                  </FaButton>
                  <span v-if="activity.joinDisabledReason" class="text-sm text-muted-foreground">
                    {{ activity.joinDisabledReason }}
                  </span>
                </template>
                <template v-else>
                  <FaButton v-if="canVerify" size="lg" :loading="acting" @click="model.verify">
                    <FaIcon name="i-ri:shield-check-line" />自助核验
                  </FaButton>
                  <FaButton v-if="!ended" variant="outline" :loading="acting" @click="model.cancel">
                    取消参与
                  </FaButton>
                  <span v-if="activity.joinedAt" class="text-sm text-muted-foreground">
                    已于 {{ formatTime(activity.joinedAt) }} 参与
                  </span>
                </template>
              </div>
            </aside>
          </div>
          <div v-if="activity.description" class="activity-detail-description">
            <h3>活动详情</h3>
            <MarkdownPreview :sdk="sdk" :content="activity.description" />
          </div>
        </template>
      </div>
    </FaPageMain>
    <FaModal
      v-model="qrVisible"
      title="参与二维码"
      :description="activity ? `扫码打开「${activity.title}」并参与` : '扫码打开活动并参与'"
      :show-confirm-button="false"
      show-cancel-button
      cancel-button-text="关闭"
    >
      <div class="activity-qr">
        <img v-if="qrDataUrl" :src="qrDataUrl" alt="参与二维码">
        <p v-if="activity" class="activity-qr-url">{{ model.shareUrl(activity) }}</p>
        <FaButton variant="outline" @click="model.printQr">
          <FaIcon name="i-ri:printer-line" />打印二维码
        </FaButton>
      </div>
    </FaModal>
  </section>
</template>
