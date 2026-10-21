<script setup lang="ts">
import { YdLoadingOverlay } from '@yudream/components'
import type { UserActivity } from '../types'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { YdButton, YdIcon, YdPageHeader, YdPageMain, YdPagination, YdTag, YdAction } from '@yudream/components'
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import CoverThumb from '../components/CoverThumb.vue'
import { useSquare } from '../composables/useSquare'
import { activityStage, activityStageTag, formatTimeRange, isActivityEnded } from '../composables/utils'
import ActivityCoverPlaceholder from '../components/ActivityCoverPlaceholder.vue'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  route?: RouteLocationNormalizedLoaded
}>()

const router = useRouter()
const model = useSquare(props.sdk)
const { loading, activities, pager } = model

onMounted(model.load)

function statusTag(activity: UserActivity) {
  const stage = activityStage(activity.status, activity.signupStart, activity.signupEnd, activity.activityEnd)
  if (stage === 'ENDED') {
    return activityStageTag(stage)
  }
  if (activity.participationStatus === 'JOINED') {
    return { variant: 'default' as const, text: '已参与' }
  }
  return activityStageTag(stage)
}

function openDetail(activity: UserActivity) {
  router.push({ path: '/platform/plugins/yudream-student-info/activity-square/detail', query: { id: activity.id } })
}
</script>

<template>
  <section class="proof-page">
    <YdPageHeader title="活动广场" class="mb-0">
      <YdButton variant="outline" :loading="loading" @click="model.load">
        <YdIcon name="i-ri:refresh-line" />刷新
      </YdButton>
    </YdPageHeader>
    <YdPageMain>
      <YdLoadingOverlay :loading="loading"><div  class="activity-square">
        <div v-if="!loading && !activities.length" class="activity-square-empty">
          <YdIcon name="i-ri:calendar-event-line" class="text-4xl text-muted-foreground" />
          <p>暂无进行中的活动</p>
        </div>
        <div v-else class="activity-square-grid">
          <YdAction
            v-for="activity in activities"
            :key="activity.id"
            class="activity-card"
            :class="{ 'activity-card-ended': isActivityEnded(activity.status, activity.activityEnd) }"
            @click="openDetail(activity)"
          >
            <div class="activity-card-cover">
              <CoverThumb
                v-if="activity.coverUrl"
                :src="model.coverThumbOf(activity)"
                :fallback-src="model.coverOf(activity)"
                :alt="activity.title"
              />
              <ActivityCoverPlaceholder v-else :title="activity.title" size="card" />
              <YdTag class="activity-card-tag" :variant="statusTag(activity).variant">
                {{ statusTag(activity).text }}
              </YdTag>
            </div>
            <div class="activity-card-body">
              <h3>{{ activity.title }}</h3>
              <p v-if="activity.summary" class="activity-card-summary">{{ activity.summary }}</p>
              <div class="activity-card-meta">
                <span><YdIcon name="i-ri:time-line" />{{ formatTimeRange(activity.activityStart, activity.activityEnd) }}</span>
                <span><YdIcon name="i-ri:user-line" />{{ activity.participantCount }} 人已参与</span>
              </div>
              <div v-if="activity.deptRestricted" class="activity-card-dept">
                <YdIcon name="i-ri:group-line" />限 {{ (activity.allowedDeptNames || []).join('、') || '指定部门' }} 参与
              </div>
            </div>
          </YdAction>
        </div>
      </div></YdLoadingOverlay>
      <YdPagination
        v-model:page="pager.page"
        v-model:size="pager.size"
        :total="pager.total"
        class="mt-3"
        @page-change="model.load"
        @size-change="model.load"
      />
    </YdPageMain>
  </section>
</template>
