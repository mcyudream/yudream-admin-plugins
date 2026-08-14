<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { FaButton, FaCard, FaInput, FaPageHeader, FaPageMain, FaPagination, FaResponsiveTable, FaTag, useFaToast, type TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { createAiChatbotApi } from '../api/ai-chatbot-api'
import type { ActivityEvent, ActivityFilters, ActivityTimelinePoint, MemoryProfile, ProfileObservation } from '../types'
import { formatDateTime, formatPercent } from '../utils/format'

const FACT_LABELS: Record<string, string> = { preference: '偏好', interest: '兴趣', identity: '身份', note: '备注', habit: '习惯', topic: '话题', emotion: '情绪', recent_message: '最近消息' }
const FACT_COLORS: Record<string, string> = { preference: '#7c3aed', interest: '#0284c7', identity: '#059669', note: '#64748b', habit: '#d97706', topic: '#db2777', emotion: '#dc2626', recent_message: '#94a3b8' }
const HEAT_COLORS = ['#f1f5f9', '#dbeafe', '#93c5fd', '#3b82f6', '#1d4ed8']
const CALENDAR_WEEKS = 15

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = createAiChatbotApi(props.sdk)
const toast = useFaToast()
const avatarErrors = reactive<Record<string, boolean>>({})
const timezone = Intl.DateTimeFormat().resolvedOptions().timeZone

const users = ref<MemoryProfile[]>([])
const usersLoading = ref(false)
const keyword = ref('')
const userPager = reactive({ page: 1, size: 20, total: 0 })
const selectedId = ref('')
const profile = ref<MemoryProfile | null>(null)
const timeline = ref<ActivityTimelinePoint[]>([])
const events = ref<ActivityEvent[]>([])
const observations = ref<ProfileObservation[]>([])
const detailLoading = ref(false)
const analyzing = ref(false)
const eventPager = reactive({ page: 1, size: 8, total: 0 })

const filteredUsers = computed(() => {
  const value = keyword.value.trim().toLowerCase()
  if (!value) return users.value
  return users.value.filter(user => [user.nickname, user.platformUserId, user.userId].some(field => field?.toLowerCase().includes(value)))
})
const hasMore = computed(() => users.value.length < userPager.total)
const maxCalendarCount = computed(() => Math.max(1, ...timeline.value.map(point => point.total)))
const approvedFacts = computed(() => (profile.value?.facts || []).filter(fact => fact.approved))
const pendingFacts = computed(() => (profile.value?.facts || []).filter(fact => !fact.approved && fact.key !== 'recent_message'))
const eventColumns: TableColumn<ActivityEvent>[] = [
  { id: 'occurredAt', header: '时间', width: 180 },
  { accessorKey: 'type', header: '事件', minWidth: 170 },
  { accessorKey: 'mode', header: '触发', width: 90 },
  { id: 'result', header: '结果', width: 90 },
]

interface CalDay { date: string; count: number; future: boolean }

const calendarWeeks = computed<CalDay[][]>(() => {
  const counts = new Map(timeline.value.map(point => [point.bucket, point.total]))
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const start = new Date(today)
  start.setDate(start.getDate() - (CALENDAR_WEEKS * 7 - 1))
  start.setDate(start.getDate() - (start.getDay() + 6) % 7)
  const weeks: CalDay[][] = []
  const cursor = new Date(start)
  while (cursor <= today) {
    const week: CalDay[] = []
    for (let i = 0; i < 7; i++) {
      week.push({ date: toIsoDate(cursor), count: counts.get(toIsoDate(cursor)) ?? 0, future: cursor > today })
      cursor.setDate(cursor.getDate() + 1)
    }
    weeks.push(week)
  }
  return weeks
})
const calendarMonths = computed(() => {
  const labels: { index: number; label: string }[] = []
  calendarWeeks.value.forEach((week, index) => {
    const month = week[0].date.slice(0, 7)
    const previous = index > 0 ? calendarWeeks.value[index - 1][0].date.slice(0, 7) : ''
    if (month !== previous) labels.push({ index, label: `${Number(month.slice(5))}月` })
  })
  return labels
})

function toIsoDate(date: Date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}
function displayName(user: MemoryProfile) { return user.nickname || `用户 ${user.platformUserId || user.userId || ''}`.trim() || '未命名用户' }
function initial(user: MemoryProfile) { return displayName(user).slice(0, 1).toUpperCase() }
function hue(value: string) { let hash = 0; for (const char of value) hash = (hash * 31 + char.charCodeAt(0)) % 360; return hash }
function avatarStyle(user: MemoryProfile) { const h = hue(user.id || displayName(user)); return { background: `hsl(${h} 62% 92%)`, color: `hsl(${h} 55% 38%)` } }
function tagStyle(tag: string) { const h = hue(tag); return { background: `hsl(${h} 70% 94%)`, color: `hsl(${h} 55% 35%)` } }
function heatColor(count: number) {
  if (count <= 0) return HEAT_COLORS[0]
  const level = Math.min(4, 1 + Math.floor((count / maxCalendarCount.value) * 3))
  return HEAT_COLORS[level]
}
function relativeTime(value?: number) {
  if (!value || value <= 0) return '暂无'
  const diff = Date.now() - value
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`
  if (diff < 7 * 86_400_000) return `${Math.floor(diff / 86_400_000)} 天前`
  return formatDateTime(value)
}
function activityFilters(): ActivityFilters {
  const target = profile.value
  const start = new Date()
  start.setDate(start.getDate() - (CALENDAR_WEEKS * 7 - 1))
  start.setHours(0, 0, 0, 0)
  return { user: target?.userId || target?.platformUserId || undefined, bucket: 'day', timezone, from: start.getTime() }
}

async function loadUsers(page = 1) {
  usersLoading.value = true
  try {
    const result = await api.memoryProfiles(page, userPager.size)
    const records = result.records || []
    users.value = page === 1 ? records : [...users.value, ...records]
    userPager.page = page
    userPager.total = result.total || 0
    if (page === 1 && !selectedId.value && records.length) select(records[0])
  }
  catch { toast.error('加载用户列表失败') }
  finally { usersLoading.value = false }
}
async function select(user: MemoryProfile) {
  if (!user.id || (selectedId.value === user.id && profile.value)) return
  selectedId.value = user.id
  profile.value = null
  timeline.value = []
  events.value = []
  observations.value = []
  eventPager.page = 1
  eventPager.total = 0
  detailLoading.value = true
  try {
    profile.value = await api.memoryProfile(user.id)
    const results = await Promise.allSettled([
      api.activityTimeline(activityFilters()),
      api.activityEvents(activityFilters(), eventPager.page, eventPager.size),
      api.profileObservations(user.id),
    ])
    const [timelineResult, eventsResult, observationsResult] = results
    if (timelineResult.status === 'fulfilled') timeline.value = timelineResult.value
    if (eventsResult.status === 'fulfilled') { events.value = eventsResult.value.items; eventPager.total = eventsResult.value.total }
    if (observationsResult.status === 'fulfilled') observations.value = observationsResult.value
    if (results.some(result => result.status === 'rejected')) toast.error('部分互动数据加载失败，已显示可用数据')
  }
  catch { toast.error('加载用户画像失败') }
  finally { detailLoading.value = false }
}
async function loadEvents() {
  if (!profile.value) return
  try { const result = await api.activityEvents(activityFilters(), eventPager.page, eventPager.size); events.value = result.items; eventPager.total = result.total }
  catch { events.value = []; eventPager.total = 0; toast.error('加载互动事件失败') }
}
async function analyze() {
  const target = profile.value
  if (!target?.id) return
  analyzing.value = true
  try {
    profile.value = await api.analyzeMemoryProfile(target.id)
    toast.success('AI 画像分析已完成')
  }
  catch { toast.error('画像分析失败：该用户可能暂无可分析的发言证据，或 AI 服务不可用') }
  finally { analyzing.value = false }
}
onMounted(() => loadUsers())
</script>

<template>
  <section class="upw">
    <FaPageHeader title="用户画像工作台" description="面向单个用户的画像审阅：AI 人格分析、标签、事实、发言证据与活跃趋势。">
      <FaButton variant="outline" :loading="usersLoading" @click="loadUsers(1)">刷新</FaButton>
    </FaPageHeader>
    <FaPageMain>
      <div class="upw-layout">
        <aside class="upw-users">
          <div class="upw-search">
            <FaInput v-model="keyword" placeholder="搜索昵称 / QQ / 用户标识" class="w-full" />
          </div>
          <div class="upw-user-list">
            <button v-for="user in filteredUsers" :key="user.id" type="button" class="upw-user-card" :class="{ active: user.id === selectedId }" @click="select(user)">
              <span class="upw-avatar" :style="avatarStyle(user)">{{ initial(user) }}<img v-if="user.avatar && !avatarErrors[user.id]" :src="user.avatar" alt="" @error="avatarErrors[user.id] = true"></span>
              <span class="upw-user-main">
                <span class="upw-user-name">
                  <strong>{{ displayName(user) }}</strong>
                  <span v-if="user.enabled === false" class="upw-pill off">已停用</span>
                </span>
                <span class="upw-user-sub">{{ user.platformUserId || user.userId || '—' }}</span>
                <span v-if="user.tags?.length" class="upw-user-tags">
                  <span v-for="tag in user.tags.slice(0, 3)" :key="tag" class="upw-mini-tag" :style="tagStyle(tag)">{{ tag }}</span>
                  <span v-if="user.tags.length > 3" class="upw-user-sub">+{{ user.tags.length - 3 }}</span>
                </span>
              </span>
              <span class="upw-user-time">{{ relativeTime(user.lastActivityAt) }}</span>
            </button>
            <p v-if="!filteredUsers.length" class="upw-empty small">{{ usersLoading ? '加载中…' : '没有匹配的用户' }}</p>
          </div>
          <button v-if="hasMore" type="button" class="upw-more" :disabled="usersLoading" @click="loadUsers(userPager.page + 1)">
            加载更多（{{ users.length }} / {{ userPager.total }}）
          </button>
        </aside>

        <div v-if="profile" v-loading="detailLoading" class="upw-detail">
          <section class="upw-hero">
            <span class="upw-avatar large" :style="avatarStyle(profile)">{{ initial(profile) }}<img v-if="profile.avatar && !avatarErrors[profile.id]" :src="profile.avatar" alt="" @error="avatarErrors[profile.id] = true"></span>
            <div class="upw-hero-main">
              <div class="upw-hero-title">
                <h2>{{ displayName(profile) }}</h2>
                <span class="upw-pill" :class="profile.enabled === false ? 'off' : 'on'">{{ profile.enabled === false ? '画像已停用' : '画像已启用' }}</span>
              </div>
              <p class="upw-hero-sub">QQ {{ profile.platformUserId || '—' }} · 群 {{ profile.channelId || '—' }} · 最后活跃 {{ relativeTime(profile.lastActivityAt) }}</p>
              <div class="upw-hero-stats">
                <span>观察 <strong>{{ profile.observedMessageCount ?? 0 }}</strong></span>
                <span>触发 <strong>{{ profile.replyTriggeredCount ?? 0 }}</strong></span>
                <span>完成 <strong>{{ profile.replyCompletedCount ?? 0 }}</strong></span>
                <span>失败 <strong>{{ profile.replyFailedCount ?? 0 }}</strong></span>
                <span class="upw-rate">
                  回复完成率 <strong>{{ formatPercent(profile.replyCompletedCount, profile.replyTriggeredCount) }}</strong>
                  <i class="upw-rate-bar"><i :style="{ width: formatPercent(profile.replyCompletedCount, profile.replyTriggeredCount) === '—' ? '0%' : formatPercent(profile.replyCompletedCount, profile.replyTriggeredCount) }" /></i>
                </span>
              </div>
            </div>
            <div class="upw-hero-actions">
              <FaButton :loading="analyzing" @click="analyze">重新分析画像</FaButton>
              <a class="upw-link" :href="`/platform/plugins/ai-chatbot/admin/memory-profiles`">管理画像</a>
            </div>
          </section>

          <section class="upw-card analysis">
            <div class="upw-card-head">
              <h3>AI 人格分析</h3>
              <span class="upw-hint">{{ profile.lastAnalyzedAt ? `上次分析：${formatDateTime(profile.lastAnalyzedAt)}` : '尚未分析' }} · 基于最近发言证据生成，仅供管理员参考</span>
            </div>
            <div v-if="profile.personality || profile.interactionStyle" class="upw-analysis-body">
              <div v-if="profile.personality" class="upw-analysis-block">
                <h4>人格画像</h4>
                <p>{{ profile.personality }}</p>
              </div>
              <div v-if="profile.interactionStyle" class="upw-analysis-block">
                <h4>互动风格</h4>
                <p>{{ profile.interactionStyle }}</p>
              </div>
            </div>
            <div v-else class="upw-empty">
              <p>还没有生成人格分析。</p>
              <p class="upw-hint">点击右上角「重新分析画像」，将基于该用户最近的发言证据生成人格画像、互动风格与标签。</p>
            </div>
          </section>

          <section class="upw-card">
            <div class="upw-card-head"><h3>标签</h3><span class="upw-hint">由 AI 分析或人工维护</span></div>
            <div v-if="profile.tags?.length" class="upw-tags">
              <span v-for="tag in profile.tags" :key="tag" class="upw-tag" :style="tagStyle(tag)">{{ tag }}</span>
            </div>
            <p v-else class="upw-empty small">暂无标签</p>
          </section>

          <section class="upw-card">
            <div class="upw-card-head"><h3>活跃趋势</h3><span class="upw-hint">最近 {{ CALENDAR_WEEKS }} 周 · 每格一天，颜色越深互动越多</span></div>
            <div v-if="calendarWeeks.length" class="upw-cal-wrap">
              <div class="upw-cal-months">
                <span v-for="mark in calendarMonths" :key="mark.index" :style="{ gridColumnStart: mark.index + 2 }">{{ mark.label }}</span>
              </div>
              <div class="upw-cal">
                <div class="upw-cal-days"><span>一</span><span>三</span><span>五</span><span>日</span></div>
                <div class="upw-cal-weeks">
                  <div v-for="(week, index) in calendarWeeks" :key="index" class="upw-cal-week">
                    <span v-for="day in week" :key="day.date" class="upw-cal-cell" :class="{ future: day.future }" :style="{ background: day.future ? 'transparent' : heatColor(day.count) }" :title="`${day.date} · ${day.count} 次互动`" />
                  </div>
                </div>
              </div>
              <div class="upw-cal-legend"><span>少</span><i v-for="color in HEAT_COLORS" :key="color" :style="{ background: color }" /><span>多</span></div>
            </div>
            <p v-else class="upw-empty small">暂无活跃数据</p>
          </section>

          <section class="upw-card">
            <div class="upw-card-head"><h3>事实档案</h3><span class="upw-hint">已批准 {{ approvedFacts.length }} 条 · 待审阅 {{ pendingFacts.length }} 条</span></div>
            <div v-if="approvedFacts.length" class="upw-facts">
              <div v-for="(fact, index) in approvedFacts" :key="index" class="upw-fact">
                <span class="upw-fact-type" :style="{ color: FACT_COLORS[fact.key] || '#64748b', borderColor: FACT_COLORS[fact.key] || '#cbd5e1' }">{{ FACT_LABELS[fact.key] || fact.key }}</span>
                <span class="upw-fact-value">{{ fact.value }}</span>
                <span class="upw-fact-meta">
                  <i class="upw-confidence"><i :style="{ width: `${Math.round((fact.confidence ?? 1) * 100)}%` }" /></i>
                  {{ Math.round((fact.confidence ?? 1) * 100) }}% · {{ formatDateTime(fact.updatedAt) }}
                </span>
              </div>
            </div>
            <div v-if="pendingFacts.length" class="upw-pending">
              <h4>待审阅（AI 建议）</h4>
              <div class="upw-facts">
                <div v-for="(fact, index) in pendingFacts" :key="index" class="upw-fact pending">
                  <span class="upw-fact-type" :style="{ color: FACT_COLORS[fact.key] || '#64748b', borderColor: FACT_COLORS[fact.key] || '#cbd5e1' }">{{ FACT_LABELS[fact.key] || fact.key }}</span>
                  <span class="upw-fact-value">{{ fact.value }}</span>
                  <span class="upw-fact-meta">{{ Math.round((fact.confidence ?? 1) * 100) }}%</span>
                </div>
              </div>
              <p class="upw-hint">前往「记忆画像管理」审阅并批准这些事实。</p>
            </div>
            <p v-if="!approvedFacts.length && !pendingFacts.length" class="upw-empty small">暂无事实档案</p>
          </section>

          <section class="upw-card">
            <div class="upw-card-head"><h3>发言证据</h3><span class="upw-hint">该用户最近被截断保存的发言样本，用于画像分析</span></div>
            <div v-if="observations.length" class="upw-quotes">
              <blockquote v-for="(item, index) in observations" :key="index" class="upw-quote">
                <p>{{ item.content }}</p>
                <cite>{{ formatDateTime(item.occurredAt) }}</cite>
              </blockquote>
            </div>
            <p v-else class="upw-empty small">暂无发言证据</p>
          </section>

          <section class="upw-card">
            <div class="upw-card-head"><h3>最近互动事件</h3><span class="upw-hint">仅元数据，不含消息正文</span></div>
            <FaResponsiveTable row-key="id" table-root-class="overflow-x-auto" table-class="min-w-[560px]" :columns="eventColumns" :data="events" empty-text="暂无互动事件">
              <template #cell-occurredAt="{ row }">{{ formatDateTime(row.original.occurredAt) }}</template>
              <template #cell-mode="{ row }">{{ row.original.mode === 'MENTION' ? '@触发' : (row.original.mode === 'RANDOM' ? '随机' : '—') }}</template>
              <template #cell-result="{ row }"><FaTag :variant="row.original.success ? 'default' : 'destructive'">{{ row.original.success ? '成功' : '失败' }}</FaTag></template>
              <template #card="{ row }">
                <FaCard class="w-full">
                  <div class="flex flex-col gap-3">
                    <div class="flex items-center justify-between gap-2">
                      <span class="min-w-0 break-words text-base font-semibold">{{ formatDateTime(row.occurredAt) }}</span>
                      <div class="flex gap-1">
                        <FaTag :variant="row.success ? 'default' : 'destructive'">{{ row.success ? '成功' : '失败' }}</FaTag>
                      </div>
                    </div>
                    <div class="flex flex-col gap-1 text-sm">
                      <div class="flex gap-2">
                        <span class="shrink-0 text-secondary-foreground/60">事件</span>
                        <span class="break-all">{{ row.type }}</span>
                      </div>
                      <div class="flex gap-2">
                        <span class="shrink-0 text-secondary-foreground/60">触发</span>
                        <span>{{ row.mode === 'MENTION' ? '@触发' : (row.mode === 'RANDOM' ? '随机' : '—') }}</span>
                      </div>
                    </div>
                  </div>
                </FaCard>
              </template>
            </FaResponsiveTable>
            <FaPagination v-model:page="eventPager.page" v-model:size="eventPager.size" :total="eventPager.total" class="mt-3" @page-change="loadEvents" @size-change="loadEvents" />
          </section>
        </div>
        <div v-else class="upw-placeholder">{{ detailLoading ? '加载中…' : '从左侧选择一位用户查看画像分析' }}</div>
      </div>
    </FaPageMain>
  </section>
</template>

