<script setup lang="ts">
import type { MyParticipation, UserActivity } from '../types'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { computed, onMounted, ref } from 'vue'
import { FaButton, FaIcon, FaTag } from '@yudream/components'
import { createActivityProofApi } from '../api/activity-proof-api'
import { activityStage, activityStageTag } from '../composables/utils'

interface DashboardCardLike {
  actionPath?: string
}

const props = defineProps<{
  sdk: YuDreamPluginSdk
  card?: DashboardCardLike
  onOpen?: (card?: DashboardCardLike) => void
}>()

const api = createActivityProofApi(props.sdk)
const loading = ref(false)
const error = ref('')
const activities = ref<UserActivity[]>([])
const participations = ref<MyParticipation[]>([])
const participationTotal = ref(0)

const myParticipations = computed(() => participations.value.filter(row => row.status === 'JOINED'))

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [activityPage, participationPage] = await Promise.all([
      api.me.activities(1, 3),
      api.me.participations(1, 3),
    ])
    activities.value = activityPage.records
    participations.value = participationPage.records
    participationTotal.value = Number(participationPage.total ?? 0)
  }
  catch (cause: any) {
    error.value = cause?.message || '活动信息加载失败'
  }
  finally {
    loading.value = false
  }
}

function activityTag(activity: UserActivity) {
  const stage = activityStage(activity.status, activity.signupStart, activity.signupEnd, activity.activityEnd)
  if (stage === 'ENDED') {
    return activityStageTag(stage)
  }
  if (activity.participationStatus === 'JOINED') {
    return { variant: 'default' as const, text: '已参与' }
  }
  return activityStageTag(stage)
}

function verifyTag(row: MyParticipation) {
  if (row.status !== 'JOINED') {
    return { variant: 'secondary' as const, text: '已取消' }
  }
  if (row.verifyStatus === 'PASSED') {
    return { variant: 'default' as const, text: '已通过' }
  }
  if (row.verifyStatus === 'FAILED') {
    return { variant: 'destructive' as const, text: '未通过' }
  }
  return { variant: 'secondary' as const, text: '待核验' }
}

function openCard() {
  props.onOpen?.(props.card)
}
</script>

<template>
  <div class="dashboard-card__content activity-dashboard-card">
    <div v-if="loading" class="activity-dashboard-card__state">
      <FaIcon name="i-ri:loader-4-line" class="animate-spin" />
      正在读取活动
    </div>
    <div v-else-if="error" class="activity-dashboard-card__state error">
      <FaIcon name="i-ri:error-warning-line" />
      {{ error }}
    </div>
    <template v-else>
      <div class="activity-dashboard-card__body">
        <section class="activity-dashboard-card__section">
          <h4>最新活动</h4>
          <div v-if="activities.length" class="activity-dashboard-card__list">
            <div v-for="activity in activities" :key="activity.id" class="activity-dashboard-card__row">
              <FaTag :variant="activityTag(activity).variant">
                {{ activityTag(activity).text }}
              </FaTag>
              <span class="activity-dashboard-card__title" :title="activity.title">{{ activity.title }}</span>
            </div>
          </div>
          <p v-else class="activity-dashboard-card__empty">
            暂无发布的活动
          </p>
        </section>

        <section class="activity-dashboard-card__section">
          <h4>我的参与{{ participationTotal > 0 ? `（${participationTotal}）` : '' }}</h4>
          <div v-if="myParticipations.length" class="activity-dashboard-card__list">
            <div v-for="row in myParticipations" :key="row.activityId" class="activity-dashboard-card__row">
              <FaTag :variant="verifyTag(row).variant">
                {{ verifyTag(row).text }}
              </FaTag>
              <span class="activity-dashboard-card__title" :title="row.title">{{ row.title }}</span>
            </div>
          </div>
          <p v-else class="activity-dashboard-card__empty">
            暂未参与活动，去广场看看吧
          </p>
        </section>
      </div>

      <div class="activity-dashboard-card__actions">
        <FaButton v-if="card?.actionPath" size="sm" @click="openCard">
          <FaIcon name="i-ri:layout-masonry-line" />
          前往活动广场
        </FaButton>
      </div>
    </template>
  </div>
</template>
