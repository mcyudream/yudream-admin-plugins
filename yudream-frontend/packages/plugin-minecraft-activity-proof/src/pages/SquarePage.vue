<script setup lang="ts">
import type { UserActivity } from '../types'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { FaButton, FaIcon, FaPageHeader, FaPageMain, FaPagination, FaTag } from '@yudream/components'
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useSquare } from '../composables/useSquare'
import { formatTimeRange, isActivityEnded } from '../composables/utils'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  route?: RouteLocationNormalizedLoaded
}>()

const router = useRouter()
const model = useSquare(props.sdk)
const { loading, activities, pager } = model

onMounted(model.load)

function statusTag(activity: UserActivity) {
  if (isActivityEnded(activity.status, activity.activityEnd)) {
    return { variant: 'secondary' as const, text: '已结束' }
  }
  if (activity.participationStatus === 'JOINED') {
    return { variant: 'default' as const, text: '已参与' }
  }
  return { variant: 'outline' as const, text: '报名中' }
}

function openDetail(activity: UserActivity) {
  router.push({ path: '/platform/plugins/yudream-student-info/activity-square/detail', query: { id: activity.id } })
}
</script>

<template>
  <section class="proof-page">
    <FaPageHeader title="活动广场" class="mb-0">
      <FaButton variant="outline" :loading="loading" @click="model.load">
        <FaIcon name="i-ri:refresh-line" />刷新
      </FaButton>
    </FaPageHeader>
    <FaPageMain>
      <div v-loading="loading" class="activity-square">
        <div v-if="!loading && !activities.length" class="activity-square-empty">
          <FaIcon name="i-ri:calendar-event-line" class="text-4xl text-muted-foreground" />
          <p>暂无进行中的活动</p>
        </div>
        <div v-else class="activity-square-grid">
          <article
            v-for="activity in activities"
            :key="activity.id"
            class="activity-card"
            :class="{ 'activity-card-ended': isActivityEnded(activity.status, activity.activityEnd) }"
            role="button"
            tabindex="0"
            @click="openDetail(activity)"
            @keydown.enter="openDetail(activity)"
          >
            <div class="activity-card-cover">
              <img v-if="activity.coverUrl" :src="model.coverOf(activity)" :alt="activity.title" loading="lazy">
              <div v-else class="activity-card-cover-placeholder">
                <FaIcon name="i-ri:image-line" class="text-3xl" />
              </div>
              <FaTag class="activity-card-tag" :variant="statusTag(activity).variant">
                {{ statusTag(activity).text }}
              </FaTag>
            </div>
            <div class="activity-card-body">
              <h3>{{ activity.title }}</h3>
              <p v-if="activity.summary" class="activity-card-summary">{{ activity.summary }}</p>
              <div class="activity-card-meta">
                <span><FaIcon name="i-ri:time-line" />{{ formatTimeRange(activity.activityStart, activity.activityEnd) }}</span>
                <span><FaIcon name="i-ri:user-line" />{{ activity.participantCount }} 人已参与</span>
              </div>
              <div v-if="activity.deptRestricted" class="activity-card-dept">
                <FaIcon name="i-ri:group-line" />限 {{ (activity.allowedDeptNames || []).join('、') || '指定部门' }} 参与
              </div>
            </div>
          </article>
        </div>
      </div>
      <FaPagination
        v-model:page="pager.page"
        v-model:size="pager.size"
        :total="pager.total"
        class="mt-3"
        @page-change="model.load"
        @size-change="model.load"
      />
    </FaPageMain>
  </section>
</template>
