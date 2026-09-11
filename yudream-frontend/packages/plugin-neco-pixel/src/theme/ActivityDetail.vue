<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { pluginAsset, themeAsset, useThemeSeo } from './use-theme-context'

interface PublicActivity {
  id: string
  title: string
  summary: string
  description: string
  coverUrl: string
  url: string
  signupStart: number | string
  signupEnd: number | string
  activityStart: number | string
  activityEnd: number | string
  status: string
  statusKey: string
  statusText: string
  meta: string
}

const props = defineProps<{ sdk: YuDreamPluginSdk, route?: { params?: Record<string, string | string[]>, path?: string } }>()

const route = useRoute()
const router = useRouter()
const loaded = ref(false)
const missing = ref(false)
const activity = ref<PublicActivity | null>(null)

const activityId = computed(() => {
  const fromRoute = route.params.id
  const fromProp = props.route?.params?.id
  const raw = Array.isArray(fromRoute) ? fromRoute[0] : fromRoute
    || (Array.isArray(fromProp) ? fromProp[0] : fromProp)
    || ''
  return String(raw)
})

useThemeSeo(props.sdk, {
  title: '活动详情',
  description: '服务器活动详情。',
  canonicalPath: `/activities/${activityId.value}`,
})

const headerBg = computed(() => pluginAsset(props.sdk, 'background/header-bg.jpg'))
const pageBg = computed(() => pluginAsset(props.sdk, 'background/bg.jpg'))
const fallbackCover = computed(() => pluginAsset(props.sdk, 'background/beidalou.webp'))
const cover = computed(() => {
  const raw = activity.value?.coverUrl
  return raw ? themeAsset(props.sdk, raw) : fallbackCover.value
})
const isActive = computed(() => {
  const key = activity.value?.statusKey
  return key === 'ongoing' || key === 'upcoming'
})
const loggedIn = computed(() => !!props.sdk.account?.userId)
const joinHref = computed(() => {
  const id = activityId.value
  if (!id) {
    return '/login'
  }
  if (!loggedIn.value) {
    return `/login?redirect=${encodeURIComponent(`/activities/${id}`)}`
  }
  return `/platform/plugins/yudream-student-info/activity-square/detail?id=${encodeURIComponent(id)}`
})

function toEpoch(value: number | string | null | undefined) {
  if (value == null || value === '') {
    return 0
  }
  if (typeof value === 'number') {
    return Number.isFinite(value) && value > 0 ? value : 0
  }
  const numeric = Number(value)
  if (Number.isFinite(numeric) && numeric > 0) {
    return numeric
  }
  const parsed = Date.parse(String(value).replace(' ', 'T'))
  return Number.isFinite(parsed) && parsed > 0 ? parsed : 0
}

function formatEpoch(value: number | string | null | undefined) {
  const millis = toEpoch(value)
  if (!millis) {
    return ''
  }
  const date = new Date(millis)
  if (Number.isNaN(date.getTime())) {
    return ''
  }
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function formatRange(start: number | string | null | undefined, end: number | string | null | undefined) {
  const from = formatEpoch(start)
  if (!from) {
    return ''
  }
  const to = formatEpoch(end)
  return to && toEpoch(end) > toEpoch(start) ? `${from} 至 ${to}` : `${from} 起`
}

async function load() {
  const id = activityId.value
  loaded.value = false
  missing.value = false
  activity.value = null
  if (!id) {
    missing.value = true
    loaded.value = true
    return
  }
  try {
    const url = props.sdk.site.assetUrl(`/api/plugins/minecraft-activity-proof/public/activities/${encodeURIComponent(id)}`)
    const res = await fetch(url, { credentials: 'include' })
    if (!res.ok) {
      missing.value = true
      return
    }
    const body = await res.json() as PublicActivity & { data?: PublicActivity }
    const data = body.data?.id ? body.data : body
    if (!data?.id) {
      missing.value = true
      return
    }
    activity.value = data
    props.sdk.site.applySeo({
      title: data.title || '活动详情',
      description: data.summary || '服务器活动详情。',
      canonicalPath: `/activities/${data.id}`,
      image: data.coverUrl ? themeAsset(props.sdk, data.coverUrl) : undefined,
    })
  }
  catch {
    missing.value = true
  }
  finally {
    loaded.value = true
  }
}

onMounted(load)
watch(activityId, load)

function goJoin() {
  void router.push(joinHref.value)
}
</script>

<template>
  <div
    class="activity-area"
    :style="{
      backgroundImage: `url('${headerBg}'), url('${pageBg}')`,
    }"
  >
    <p class="mcfont activity-title">活动详情</p>
    <div v-if="!loaded" class="activity-list-loading-container">
      <img class="activity-list-loading" :src="pluginAsset(props.sdk, 'loading.gif')" alt="">
    </div>
    <div v-else-if="missing || !activity" class="activity-detail-empty">
      <p>活动不存在或未发布。</p>
      <RouterLink class="minecraft-button activity-back" to="/activities">返回活动列表</RouterLink>
    </div>
    <article v-else class="activity-detail" :class="{ 'is-active': isActive }">
      <img class="activity-detail-cover" :src="cover" :alt="activity.title">
      <div class="activity-detail-body">
        <div class="activity-title-row">
          {{ activity.title }}
          <div class="activity-status" :class="{ 'is-active': isActive }">
            {{ activity.statusText }}
          </div>
        </div>
        <p v-if="activity.meta && !formatRange(activity.activityStart, activity.activityEnd)" class="activity-date">{{ activity.meta }}</p>
        <p v-if="activity.summary" class="activity-brief">{{ activity.summary }}</p>
        <dl class="activity-meta">
          <div v-if="formatRange(activity.activityStart, activity.activityEnd)">
            <dt>活动时间</dt>
            <dd>{{ formatRange(activity.activityStart, activity.activityEnd) }}</dd>
          </div>
          <div v-if="formatRange(activity.signupStart, activity.signupEnd)">
            <dt>报名时间</dt>
            <dd>{{ formatRange(activity.signupStart, activity.signupEnd) }}</dd>
          </div>
        </dl>
        <div v-if="activity.description" class="activity-description">{{ activity.description }}</div>
        <div class="activity-actions">
          <RouterLink class="minecraft-button activity-back" to="/activities">返回列表</RouterLink>
          <button type="button" class="minecraft-button activity-join" @click="goJoin">
            {{ loggedIn ? '立即参与' : '登录后参与' }}
          </button>
        </div>
      </div>
    </article>
  </div>
</template>

<style scoped>
.activity-area {
  min-height: 100vh;
  padding-top: 5rem;
  display: flex;
  flex-direction: column;
  background-color: #303030;
  background-repeat: repeat-x, repeat;
  background-position: top left, top left;
  background-size: auto 234px, 468px;
  color: rgba(255, 255, 255, 0.8);
}

.activity-title {
  user-select: none;
  color: #fff;
  font-size: 1.5rem;
  font-weight: bold;
  text-align: center;
  margin: 0;
}

.activity-list-loading-container {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 40rem;
}

.activity-list-loading {
  width: 16rem;
  height: 16rem;
}

.activity-detail-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1.5rem;
  padding: 4rem 1.5rem;
}

.activity-detail {
  margin: 1.5rem auto 3rem;
  width: 100%;
  max-width: 1024px;
  background-color: rgb(88, 46, 46);
  border: 2px solid rgba(0, 0, 0, 0.4);
  box-shadow: 4px 4px rgba(0, 0, 0, 0.7);
  position: relative;
}

.activity-detail.is-active {
  background-color: rgb(45, 72, 31);
}

.activity-detail::after {
  content: '';
  position: absolute;
  inset: 0;
  z-index: 1;
  pointer-events: none;
  box-shadow:
    0 -12px 0 0 rgb(104, 104, 104) inset,
    2px 2px 0 0 rgba(178, 178, 178, 0.5) inset,
    -2px -16px 0 0 rgba(153, 153, 153, 0.5) inset;
  mix-blend-mode: hard-light;
}

.activity-detail-cover {
  display: block;
  width: 100%;
  height: 18rem;
  object-fit: cover;
  position: relative;
  z-index: 2;
}

.activity-detail-body {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  padding: 1.5rem 2rem 2rem;
  position: relative;
  z-index: 2;
}

.activity-title-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  color: #fff;
  font-size: 1.5rem;
}

.activity-status {
  color: #f56c6c;
  margin-left: 0.5rem;
  padding: 3px 8px;
  background-color: rgb(88, 46, 46);
  font-size: 0.9rem;
}

.activity-status.is-active {
  color: #67c23a;
  background-color: rgb(45, 72, 31);
}

.activity-date {
  margin: 0;
  font-size: 0.8rem;
}

.activity-brief {
  margin: 0;
  font-size: 1rem;
  color: #eee;
}

.activity-meta {
  display: grid;
  gap: 0.5rem;
  margin: 0.5rem 0 0;
}

.activity-meta dt {
  font-size: 0.75rem;
  color: rgba(255, 255, 255, 0.55);
}

.activity-meta dd {
  margin: 0.15rem 0 0;
}

.activity-description {
  margin-top: 0.5rem;
  color: #eee;
  line-height: 1.7;
  word-break: break-word;
}

.activity-description :deep(img) {
  max-width: 100%;
  height: auto;
}

.activity-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem;
  margin-top: 1rem;
}

.minecraft-button {
  min-height: 2.25rem;
  padding: 0 1rem;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #000;
  background-color: #c6c6c6;
  border: 2px solid #333;
  cursor: pointer;
  font: inherit;
  text-decoration: none;
}

.minecraft-button:hover {
  color: #fff;
  background-color: #43a01c;
}

.activity-join {
  background-color: #43a01c;
  color: #fff;
}

.activity-join:hover {
  background-color: #3c8527;
}

@media screen and (max-width: 524px) {
  .activity-detail-cover {
    height: 12rem;
  }

  .activity-detail-body {
    padding: 1rem;
  }
}
</style>
