<script setup lang="ts">
// 公开活动详情（匿名访问）：/activities/:id。参与需登录，登录后跳转插件内的活动详情完成报名/参与。
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { YdButton, YdEmpty, YdIcon, YdLoadingOverlay, YdPageHeader, YdPageMain, YdTag } from '@yudream/components'
import { computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import CoverThumb from '../components/CoverThumb.vue'
import MarkdownPreview from '../components/MarkdownPreview.vue'
import { usePublicActivities } from '../composables/usePublicActivities'
import { activityStage, activityStageTag, formatTimeRange, resolveFileUrl, resolveThumbUrl } from '../composables/utils'

const props = defineProps<{ sdk: YuDreamPluginSdk, route?: RouteLocationNormalizedLoaded }>()

const router = useRouter()
const model = usePublicActivities(props.sdk)
const id = computed(() => String(props.route?.params.id || ''))
const activity = computed(() => model.activity.value)
const loggedIn = computed(() => !!props.sdk.account?.userId)
const stage = computed(() => activity.value ? activityStageTag(activityStage(activity.value.status, activity.value.signupStart, activity.value.signupEnd, activity.value.activityEnd)) : null)

watch(id, value => void model.loadOne(value), { immediate: true })

function join() {
  if (!loggedIn.value) {
    void router.push({ path: '/login', query: { redirect: `/activities/${encodeURIComponent(id.value)}` } })
    return
  }
  void router.push({ path: '/platform/plugins/yudream-student-info/activity-square/detail', query: { id: id.value } })
}
</script>

<template>
  <section class="proof-page proof-public-page">
    <YdPageHeader :title="activity?.title || '活动详情'" class="mb-0">
      <YdButton variant="outline" @click="router.push('/activities')">
        <YdIcon name="i-ri:arrow-left-line" />返回活动广场
      </YdButton>
    </YdPageHeader>
    <YdPageMain>
      <YdLoadingOverlay :loading="model.loading.value">
        <YdEmpty v-if="!model.loading.value && !activity" description="活动不存在或未公开" />
        <article v-else-if="activity" class="activity-public-detail">
          <div class="activity-card-cover activity-public-detail__cover">
            <CoverThumb v-if="activity.coverUrl" :src="resolveThumbUrl(sdk, activity.coverUrl)" :fallback-src="resolveFileUrl(sdk, activity.coverUrl)" :alt="activity.title" />
            <div v-else class="activity-card-cover-placeholder">
              <YdIcon name="i-ri:image-line" class="text-3xl" />
            </div>
            <YdTag v-if="stage" class="activity-card-tag" :variant="stage.variant">
              {{ stage.text }}
            </YdTag>
          </div>
          <div class="activity-public-detail__body">
            <p v-if="activity.summary" class="activity-card-summary">
              {{ activity.summary }}
            </p>
            <dl class="activity-public-detail__meta">
              <div>
                <dt>活动时间</dt>
                <dd>{{ formatTimeRange(activity.activityStart ?? '', activity.activityEnd ?? '') }}</dd>
              </div>
              <div>
                <dt>报名时间</dt>
                <dd>{{ formatTimeRange(activity.signupStart ?? '', activity.signupEnd ?? '') }}</dd>
              </div>
            </dl>
            <MarkdownPreview v-if="activity.description" :sdk="sdk" :content="activity.description" />
            <div class="activity-public-detail__actions">
              <YdButton @click="join">
                <YdIcon :name="loggedIn ? 'i-ri:flag-line' : 'i-ri:login-box-line'" />{{ loggedIn ? '立即参与' : '登录后参与' }}
              </YdButton>
            </div>
          </div>
        </article>
      </YdLoadingOverlay>
    </YdPageMain>
  </section>
</template>

<style scoped>
.activity-public-detail {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(0, 3fr);
  gap: 20px;
}
.activity-public-detail__cover {
  position: relative;
  aspect-ratio: 16 / 9;
  overflow: hidden;
}
.activity-public-detail__body {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}
.activity-public-detail__meta {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 8px;
  margin: 0;
}
.activity-public-detail__meta dt {
  font-size: 12px;
  color: var(--color-text-3);
}
.activity-public-detail__meta dd {
  margin: 2px 0 0;
  color: var(--color-text-1);
}
.activity-public-detail__actions {
  display: flex;
  gap: 8px;
}
@media (max-width: 768px) {
  .activity-public-detail {
    grid-template-columns: 1fr;
  }
}
</style>
