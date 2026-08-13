<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { MemoryFact, MemoryProfile } from '../types'
import { computed, onMounted, reactive, ref } from 'vue'
import { FaButton, FaInput, FaPageHeader, FaPageMain, FaPagination, FaTextarea, useFaModal, useFaToast } from '@yudream/components'
import { createAiChatbotApi } from '../api/ai-chatbot-api'
import FactsEditor from '../components/FactsEditor.vue'
import { formatDateTime, formatPercent } from '../utils/format'

const SYSTEM_FACT_KEY = 'recent_message'
const MAX_TAGS = 20

type Profile = Required<MemoryProfile> & { facts: Required<MemoryFact>[] }
const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = createAiChatbotApi(props.sdk)
const toast = useFaToast()
const modal = useFaModal()
const rows = ref<Profile[]>([])
const loading = ref(false)
const pager = reactive({ page: 1, size: 12, total: 0 })
const view = ref<'list' | 'review'>('list')
const selected = ref<Profile | null>(null)
const draft = ref<Profile | null>(null)
const editableFacts = ref<Required<MemoryFact>[]>([])
const tagInput = ref('')
const saving = ref(false)
const analyzing = ref(false)
const avatarErrors = reactive<Record<string, boolean>>({})

const systemFacts = computed(() => (selected.value?.facts || []).filter(fact => fact.key === SYSTEM_FACT_KEY))

function normalizeFact(fact: MemoryFact): Required<MemoryFact> { return { key: fact.key || '', value: fact.value || '', confidence: fact.confidence ?? 1, approved: fact.approved ?? true, updatedAt: fact.updatedAt ?? 0 } }
function normalizeProfile(profile: MemoryProfile): Profile {
  return {
    id: profile.id, connectionId: profile.connectionId || '', channelId: profile.channelId || '', userId: profile.userId || '',
    platformUserId: profile.platformUserId || '', nickname: profile.nickname || '', avatar: profile.avatar || '', enabled: profile.enabled ?? true,
    summary: profile.summary || '', personality: profile.personality || '', interactionStyle: profile.interactionStyle || '',
    tags: profile.tags || [], facts: (profile.facts || []).map(normalizeFact),
    observedMessageCount: profile.observedMessageCount ?? 0, replyTriggeredCount: profile.replyTriggeredCount ?? 0,
    replyCompletedCount: profile.replyCompletedCount ?? 0, replyFailedCount: profile.replyFailedCount ?? 0,
    lastActivityAt: profile.lastActivityAt ?? 0, lastAnalyzedAt: profile.lastAnalyzedAt ?? 0, updatedAt: profile.updatedAt ?? 0,
  }
}
function displayName(profile: Profile) { return profile.nickname || profile.platformUserId || profile.userId || '未命名用户' }
function initial(profile: Profile) { return displayName(profile).slice(0, 1).toUpperCase() }
function hue(value: string) { let hash = 0; for (const char of value) hash = (hash * 31 + char.charCodeAt(0)) % 360; return hash }
function avatarStyle(profile: Profile) { const h = hue(profile.id || displayName(profile)); return { background: `hsl(${h} 62% 92%)`, color: `hsl(${h} 55% 38%)` } }
function tagStyle(tag: string) { const h = hue(tag); return { background: `hsl(${h} 70% 94%)`, color: `hsl(${h} 55% 35%)` } }

async function load() {
  loading.value = true
  try { const page = await api.memoryProfiles(pager.page, pager.size); rows.value = (page.records || []).map(normalizeProfile); pager.total = page.total || 0 }
  catch { rows.value = []; pager.total = 0; toast.error('加载记忆画像失败') }
  finally { loading.value = false }
}
async function openReview(row: Profile) {
  try {
    selected.value = normalizeProfile(await api.memoryProfile(row.id))
    draft.value = { ...selected.value, tags: [...selected.value.tags], facts: [...selected.value.facts] }
    editableFacts.value = selected.value.facts.filter(fact => fact.key !== SYSTEM_FACT_KEY).map(normalizeFact)
    tagInput.value = ''
    view.value = 'review'
  }
  catch { toast.error('加载画像详情失败') }
}
function backToList() { view.value = 'list'; selected.value = null; draft.value = null }

function addTag() {
  if (!draft.value) return
  const value = tagInput.value.trim().replace(/,$/, '')
  if (!value) return
  if (draft.value.tags.includes(value)) { toast.warning('标签已存在'); return }
  if (draft.value.tags.length >= MAX_TAGS) { toast.warning(`标签最多 ${MAX_TAGS} 个`); return }
  draft.value.tags.push(value)
  tagInput.value = ''
}
function removeTag(tag: string) { if (draft.value) draft.value.tags = draft.value.tags.filter(item => item !== tag) }
function onTagKeydown(event: KeyboardEvent) { if (event.key === 'Enter' || event.key === ',') { event.preventDefault(); addTag() } }

async function saveProfile() {
  if (!draft.value) return
  const facts = editableFacts.value.map(fact => ({ ...fact, value: (fact.value || '').trim() }))
  if (facts.some(fact => !fact.value)) { toast.error('事实内容不能为空'); return }
  try {
    saving.value = true
    await api.saveMemoryProfile({ id: draft.value.id, enabled: draft.value.enabled, summary: draft.value.summary, tags: draft.value.tags, facts })
    toast.success('画像已保存')
    await load()
    backToList()
  }
  catch { toast.error('保存画像失败，请检查摘要、标签和事实内容') }
  finally { saving.value = false }
}
async function analyze() {
  if (!selected.value) return
  analyzing.value = true
  try {
    selected.value = normalizeProfile(await api.analyzeMemoryProfile(selected.value.id))
    draft.value = { ...selected.value, tags: [...selected.value.tags], facts: [...selected.value.facts] }
    editableFacts.value = selected.value.facts.filter(fact => fact.key !== SYSTEM_FACT_KEY).map(normalizeFact)
    toast.success('AI 分析完成，请审阅后保存')
  }
  catch { toast.error('画像分析失败：该用户可能暂无可分析的发言证据，或 AI 服务不可用') }
  finally { analyzing.value = false }
}
async function toggle(row: Profile) {
  try { await api.setMemoryProfileEnabled(row.id, !row.enabled); toast.success(row.enabled ? '画像已停用' : '画像已启用'); await load() }
  catch { toast.error('更新画像状态失败') }
}
function remove(row: Profile) {
  modal.confirm({
    title: '删除记忆画像',
    content: `确认删除 ${displayName(row)} 的画像？发言证据将一并清除，此操作不可恢复。`,
    onConfirm: async () => {
      try { await api.deleteMemoryProfile(row.id); toast.success('画像已删除'); if (rows.value.length === 1 && pager.page > 1) pager.page--; if (view.value === 'review') backToList(); await load() }
      catch { toast.error('删除画像失败') }
    },
  })
}
onMounted(load)
</script>

<template>
  <section class="mp">
    <template v-if="view === 'list'">
      <FaPageHeader title="记忆画像管理" description="维护每位用户的画像内容：摘要、标签与事实档案。">
        <FaButton variant="outline" :loading="loading" @click="load">刷新</FaButton>
      </FaPageHeader>
      <FaPageMain>
        <div v-if="rows.length" v-loading="loading" class="mp-grid">
          <article v-for="row in rows" :key="row.id" class="mp-card">
            <header class="mp-card-head">
              <span class="mp-avatar" :style="avatarStyle(row)">{{ initial(row) }}<img v-if="row.avatar && !avatarErrors[row.id]" :src="row.avatar" alt="" @error="avatarErrors[row.id] = true"></span>
              <div class="mp-card-title">
                <strong>{{ displayName(row) }}</strong>
                <span class="mp-sub">QQ {{ row.platformUserId || '—' }}</span>
              </div>
              <span class="mp-pill" :class="row.enabled ? 'on' : 'off'">{{ row.enabled ? '已启用' : '已停用' }}</span>
            </header>
            <p class="mp-summary" :class="{ empty: !row.summary && !row.personality }">{{ row.personality || row.summary || '暂无画像内容，点击审阅后可由 AI 生成或人工维护。' }}</p>
            <div v-if="row.tags.length" class="mp-tags">
              <span v-for="tag in row.tags.slice(0, 4)" :key="tag" class="mp-tag" :style="tagStyle(tag)">{{ tag }}</span>
              <span v-if="row.tags.length > 4" class="mp-sub">+{{ row.tags.length - 4 }}</span>
            </div>
            <div class="mp-meta">
              <span>事实 {{ row.facts.filter(fact => fact.key !== SYSTEM_FACT_KEY).length }}</span>
              <span>观察 {{ row.observedMessageCount }}</span>
              <span>完成率 {{ formatPercent(row.replyCompletedCount, row.replyTriggeredCount) }}</span>
              <span>{{ formatDateTime(row.lastActivityAt) }}</span>
            </div>
            <footer class="mp-actions">
              <FaButton size="sm" @click="openReview(row)">审阅画像</FaButton>
              <FaButton size="sm" variant="outline" @click="toggle(row)">{{ row.enabled ? '停用' : '启用' }}</FaButton>
              <FaButton size="sm" variant="destructive" @click="remove(row)">删除</FaButton>
            </footer>
          </article>
        </div>
        <p v-else class="mp-empty">{{ loading ? '加载中…' : '暂无记忆画像' }}</p>
        <FaPagination v-model:page="pager.page" v-model:size="pager.size" :total="pager.total" class="mt-3" @page-change="load" @size-change="load" />
      </FaPageMain>
    </template>

    <template v-else-if="draft && selected">
      <FaPageHeader :title="`${displayName(selected)} 的画像审阅`" description="人工审阅与维护画像内容；AI 生成的内容需确认后再保存。">
        <div class="mp-head-actions">
          <FaButton variant="outline" :loading="analyzing" @click="analyze">AI 重新分析</FaButton>
          <FaButton variant="outline" @click="backToList">返回列表</FaButton>
        </div>
      </FaPageHeader>
      <FaPageMain>
        <div class="mp-review">
          <aside class="mp-side">
            <div class="mp-side-head">
              <span class="mp-avatar large" :style="avatarStyle(selected)">{{ initial(selected) }}<img v-if="selected.avatar && !avatarErrors[selected.id]" :src="selected.avatar" alt="" @error="avatarErrors[selected.id] = true"></span>
              <strong>{{ displayName(selected) }}</strong>
              <span class="mp-pill" :class="selected.enabled ? 'on' : 'off'">{{ selected.enabled ? '已启用' : '已停用' }}</span>
            </div>
            <dl class="mp-info">
              <div><dt>QQ</dt><dd>{{ selected.platformUserId || '—' }}</dd></div>
              <div><dt>群聊范围</dt><dd>{{ selected.channelId || '—' }}</dd></div>
              <div><dt>观察消息</dt><dd>{{ selected.observedMessageCount }}</dd></div>
              <div><dt>回复触发 / 完成 / 失败</dt><dd>{{ selected.replyTriggeredCount }} / {{ selected.replyCompletedCount }} / {{ selected.replyFailedCount }}</dd></div>
              <div><dt>回复完成率</dt><dd>{{ formatPercent(selected.replyCompletedCount, selected.replyTriggeredCount) }}</dd></div>
              <div><dt>最后活动</dt><dd>{{ formatDateTime(selected.lastActivityAt) }}</dd></div>
              <div><dt>上次 AI 分析</dt><dd>{{ formatDateTime(selected.lastAnalyzedAt) }}</dd></div>
            </dl>
            <div class="mp-side-actions">
              <FaButton size="sm" variant="outline" @click="toggle(selected).then(load)">{{ selected.enabled ? '停用画像' : '启用画像' }}</FaButton>
              <FaButton size="sm" variant="destructive" @click="remove(selected)">删除画像</FaButton>
            </div>
          </aside>

          <div class="mp-form">
            <section v-if="selected.personality || selected.interactionStyle" class="mp-panel ai">
              <h3>AI 分析结果</h3>
              <div v-if="selected.personality" class="mp-ai-block"><h4>人格画像</h4><p>{{ selected.personality }}</p></div>
              <div v-if="selected.interactionStyle" class="mp-ai-block"><h4>互动风格</h4><p>{{ selected.interactionStyle }}</p></div>
              <p class="mp-hint">AI 分析仅供参考，保存的画像内容以下方编辑区为准。</p>
            </section>

            <section class="mp-panel">
              <h3>人工摘要</h3>
              <FaTextarea v-model="draft.summary" placeholder="人工审阅后的用户摘要，将用于 AI 上下文。" maxlength="1000" />
            </section>

            <section class="mp-panel">
              <h3>标签 <span class="mp-hint">最多 {{ MAX_TAGS }} 个，回车添加</span></h3>
              <div class="mp-tag-editor">
                <span v-for="tag in draft.tags" :key="tag" class="mp-tag editable" :style="tagStyle(tag)">
                  {{ tag }}
                  <button type="button" class="mp-tag-remove" title="移除标签" @click="removeTag(tag)">×</button>
                </span>
                <FaInput v-model="tagInput" class="mp-tag-input" placeholder="输入标签后回车" @keydown="onTagKeydown" @blur="addTag" />
              </div>
            </section>

            <section class="mp-panel">
              <h3>事实档案 <span class="mp-hint">结构化维护；勾选“已批准”后才会作为可信事实使用</span></h3>
              <FactsEditor v-model="editableFacts" />
            </section>

            <section v-if="systemFacts.length" class="mp-panel readonly">
              <h3>系统记录 <span class="mp-hint">由插件自动维护，只读</span></h3>
              <div v-for="(fact, index) in systemFacts" :key="index" class="mp-system-fact">
                <p>{{ fact.value }}</p>
                <span>{{ formatDateTime(fact.updatedAt) }}</span>
              </div>
            </section>

            <div class="mp-savebar">
              <FaButton variant="outline" @click="backToList">取消</FaButton>
              <FaButton :loading="saving" @click="saveProfile">保存画像</FaButton>
            </div>
          </div>
        </div>
      </FaPageMain>
    </template>
  </section>
</template>

