<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { FaButton, FaIcon, FaModal, FaPageHeader, FaPageMain, FaTag } from '@yudream/components'
import { computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useActivityDetail } from '../composables/useActivityDetail'
import { formatTime, formatTimeRange, isActivityEnded } from '../composables/utils'
import MarkdownPreview from '../components/MarkdownPreview.vue'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  route?: RouteLocationNormalizedLoaded
}>()

const router = useRouter()
const model = useActivityDetail(props.sdk)
const { loading, acting, activity, qrVisible, qrDataUrl, joined, canVerify } = model

const activityId = computed(() => String(props.route?.query?.id || ''))

watch(activityId, id => model.load(id), { immediate: true })

const ended = computed(() => !!activity.value && isActivityEnded(activity.value.status, activity.value.activityEnd))

const statusText = computed(() => {
  if (!activity.value) return ''
  return ended.value ? '已结束' : '报名中'
})

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
                <FaTag :variant="ended ? 'secondary' : 'default'">
                  {{ statusText }}
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
                <ul v-if="activity.requirements?.length">
                  <li v-for="(requirement, index) in activity.requirements" :key="index">
                    <FaIcon name="i-ri:checkbox-circle-line" />{{ requirement }}
                  </li>
                </ul>
                <p v-else>参与活动即视为达标</p>
                <p v-if="activity.requirements && activity.requirements.length > 1" class="text-sm text-muted-foreground">
                  满足任意一项即通过核验
                </p>
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
            <MarkdownPreview :content="activity.description" />
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
