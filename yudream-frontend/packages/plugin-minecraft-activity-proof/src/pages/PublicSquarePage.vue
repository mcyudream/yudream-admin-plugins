<script setup lang="ts">
// 公开活动广场（匿名访问）：与登录后的活动广场同一卡片样式，数据来自插件公开接口；
// 由插件注册公开路由 /activities，插件停用即不存在。站点主题（如网站工作台承载页）可直接嵌入本页。
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { PublicActivity } from '../composables/usePublicActivities'
import { YdAction, YdButton, YdIcon, YdLoadingOverlay, YdPageHeader, YdPageMain, YdTag } from '@yudream/components'
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import CoverThumb from '../components/CoverThumb.vue'
import { usePublicActivities } from '../composables/usePublicActivities'
import { activityStage, activityStageTag, formatTimeRange, isActivityEnded, resolveFileUrl, resolveThumbUrl } from '../composables/utils'
import ActivityCoverPlaceholder from '../components/ActivityCoverPlaceholder.vue'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()

const router = useRouter()
const model = usePublicActivities(props.sdk)
const loggedIn = computed(() => !!props.sdk.account?.userId)

onMounted(model.loadList)

function stageTag(activity: PublicActivity) {
  return activityStageTag(activityStage(activity.status, activity.signupStart, activity.signupEnd, activity.activityEnd))
}

function openDetail(activity: PublicActivity) {
  void router.push(`/activities/${encodeURIComponent(activity.id)}`)
}
</script>

<template>
  <section class="proof-page proof-public-page">
    <YdPageHeader title="活动广场" description="服务器与社区的公开活动，报名与参与在登录后进行。" class="mb-0">
      <YdButton v-if="loggedIn" variant="outline" @click="router.push('/platform/plugins/yudream-student-info/my-activities')">
        <YdIcon name="i-ri:user-star-line" />我的活动
      </YdButton>
      <YdButton v-else variant="outline" @click="router.push({ path: '/login', query: { redirect: '/activities' } })">
        <YdIcon name="i-ri:login-box-line" />登录参与
      </YdButton>
    </YdPageHeader>
    <YdPageMain>
      <YdLoadingOverlay :loading="model.loading.value">
        <div class="activity-square">
          <div v-if="!model.loading.value && !model.activities.value.length" class="activity-square-empty">
            <YdIcon name="i-ri:calendar-event-line" class="text-4xl text-muted-foreground" />
            <p>暂无公开活动</p>
          </div>
          <div v-else class="activity-square-grid">
            <YdAction
              v-for="activity in model.activities.value"
              :key="activity.id"
              class="activity-card"
              :class="{ 'activity-card-ended': isActivityEnded(activity.status, activity.activityEnd ?? '') }"
              @click="openDetail(activity)"
            >
              <div class="activity-card-cover">
                <CoverThumb v-if="activity.coverUrl" :src="resolveThumbUrl(sdk, activity.coverUrl)" :fallback-src="resolveFileUrl(sdk, activity.coverUrl)" :alt="activity.title" />
                <ActivityCoverPlaceholder v-else :title="activity.title" size="card" />
                <YdTag class="activity-card-tag" :variant="stageTag(activity).variant">
                  {{ stageTag(activity).text }}
                </YdTag>
              </div>
              <div class="activity-card-body">
                <h3>{{ activity.title }}</h3>
                <p v-if="activity.summary" class="activity-card-summary">
                  {{ activity.summary }}
                </p>
                <div class="activity-card-meta">
                  <span><YdIcon name="i-ri:time-line" />{{ formatTimeRange(activity.activityStart ?? '', activity.activityEnd ?? '') }}</span>
                </div>
              </div>
            </YdAction>
          </div>
        </div>
      </YdLoadingOverlay>
    </YdPageMain>
  </section>
</template>
