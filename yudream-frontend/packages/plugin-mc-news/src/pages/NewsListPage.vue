<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { TableColumn } from '@yudream/components'
import type { NewsArticleView, NewsSourceView, PollStatusView } from '../types'
import { Tag as ArcoTag } from '@arco-design/web-vue'
import { FaButton, FaIcon, FaInput, FaPageHeader, FaPageMain, FaPagination, FaSearchBar, FaSelect, FaTable, FaTag, useFaModal, useFaToast } from '@yudream/components'
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import { createMcNewsApi } from '../api/mc-news-api'
import { errorMessage, nextPollText, PUSH_STATE_META } from '../composables/utils'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = createMcNewsApi(props.sdk)
const toast = useFaToast()
const confirm = useFaModal()

const loading = ref(false)
const pollBusy = ref(false)
const clearing = ref(false)
const clearingIgnored = ref(false)
const deletingId = ref('')
const rows = ref<NewsArticleView[]>([])
const sources = ref<NewsSourceView[]>([])
const pager = reactive({ page: 1, size: 10, total: 0 })
const filters = reactive({ sourceId: '', keyword: '' })
const pollStatus = ref<PollStatusView | null>(null)
const remainSeconds = ref(0)
const ignoredCount = ref(0)
let statusTimer: number | undefined

const columns: TableColumn<NewsArticleView>[] = [
  { accessorKey: 'discoveredAtLabel', header: '发现时间', width: 150 },
  { id: 'title', header: '新闻', minWidth: 320 },
  { accessorKey: 'sourceName', header: '来源', width: 170 },
  { accessorKey: 'category', header: '分类', width: 100 },
  { id: 'pushState', header: '推送状态', width: 100 },
  { accessorKey: 'pushedAtLabel', header: '推送时间', width: 150 },
  { id: 'operation', header: '操作', width: 90, fixed: 'right' },
]

const sourceOptions = ref<{ label: string, value: string }[]>([])

function stateMeta(state: string) {
  return PUSH_STATE_META[state] ?? PUSH_STATE_META['']
}

async function load() {
  loading.value = true
  try {
    const page = await api.news(pager.page, pager.size, filters.sourceId, filters.keyword)
    rows.value = page.records ?? []
    pager.total = Number(page.total ?? 0)
    ignoredCount.value = Number(page.ignored ?? 0)
  }
  catch (error) {
    toast.error(errorMessage(error, '加载新闻动态失败'))
  }
  finally {
    loading.value = false
  }
}

async function loadSources() {
  try {
    sources.value = await api.sources()
    sourceOptions.value = [{ label: '全部来源', value: '' }, ...sources.value.map(item => ({ label: item.name, value: item.id }))]
  }
  catch (error) {
    toast.error(errorMessage(error, '加载新闻源失败'))
  }
}

async function loadPollStatus() {
  try {
    pollStatus.value = await api.pollStatus()
    remainSeconds.value = pollStatus.value.nextPollInSeconds > 0 ? pollStatus.value.nextPollInSeconds : 0
  }
  catch {
    // 状态条加载失败不打断主列表
  }
}

/** 每 30 秒本地递减倒计时；到点后重取状态（轮询应已发生）。 */
function startStatusTimer() {
  statusTimer = window.setInterval(() => {
    if (remainSeconds.value > 0) {
      remainSeconds.value = Math.max(0, remainSeconds.value - 30)
      return
    }
    void loadPollStatus()
  }, 30000)
}

function applyFilters() {
  pager.page = 1
  void load()
}

function onPageChange() {
  void load()
}

function onSizeChange() {
  pager.page = 1
  void load()
}

/** 删除后重载当前页；页被删空时回退一页，避免停留在空页。 */
async function reloadAfterDelete() {
  if (!rows.value.length && pager.page > 1) {
    pager.page -= 1
  }
  await load()
}

function confirmDelete(row: NewsArticleView) {
  confirm.confirm({
    title: '删除新闻',
    content: `确认从动态中删除「${row.title}」吗？已推送的消息不受影响；删除后该新闻不会再推送，也不会因下轮轮询重新出现。`,
    onConfirm: async () => {
      deletingId.value = row.id
      try {
        await api.deleteNews(row.id)
        toast.success('已删除')
        await reloadAfterDelete()
      }
      catch (error) {
        toast.error(errorMessage(error, '删除失败'))
      }
      finally {
        deletingId.value = ''
      }
    },
  })
}

function confirmClear() {
  confirm.confirm({
    title: '清空动态',
    content: `确认清空全部 ${pager.total} 条新闻动态吗？仅清空列表显示，下轮轮询会重建缓存基线（默认不重新推送）。若想让某条新闻不再推送，请使用单条删除。`,
    onConfirm: async () => {
      clearing.value = true
      try {
        const result = await api.clearNews()
        toast.success(`已清空 ${result.cleared} 条动态`)
        pager.page = 1
        await load()
      }
      catch (error) {
        toast.error(errorMessage(error, '清空失败'))
      }
      finally {
        clearing.value = false
      }
    },
  })
}

function confirmClearIgnored() {
  confirm.confirm({
    title: '清空忽略名单',
    content: `忽略名单中有 ${ignoredCount.value} 条被删除过的新闻，确认清空吗？它们会重新参与下轮轮询：若不在缓存中，将被视为新新闻推送。`,
    onConfirm: async () => {
      clearingIgnored.value = true
      try {
        const result = await api.clearNewsTombstones()
        toast.success(`已清空 ${result.cleared} 条忽略名单`)
        await load()
      }
      catch (error) {
        toast.error(errorMessage(error, '清空忽略名单失败'))
      }
      finally {
        clearingIgnored.value = false
      }
    },
  })
}

async function triggerPoll() {
  pollBusy.value = true
  try {
    await api.triggerPoll()
    toast.success('已触发手动轮询，稍后可在推送记录中查看结果')
    setTimeout(() => {
      void load()
      void loadPollStatus()
    }, 3000)
  }
  catch (error) {
    toast.error(errorMessage(error, '触发轮询失败'))
  }
  finally {
    pollBusy.value = false
  }
}

onMounted(() => {
  void load()
  void loadSources()
  void loadPollStatus()
  startStatusTimer()
})

onUnmounted(() => {
  if (statusTimer !== undefined) {
    window.clearInterval(statusTimer)
  }
})
</script>

<template>
  <FaPageHeader title="新闻动态" description="已发现的 Minecraft 新闻与版本文章缓存，仅展示最近缓存的条目">
    <FaButton variant="destructive" :disabled="!pager.total" :loading="clearing" @click="confirmClear">
      <FaIcon name="i-ri:delete-bin-line" />清空动态
    </FaButton>
    <FaButton :loading="pollBusy || (pollStatus?.polling ?? false)" @click="triggerPoll">
      <FaIcon name="i-ri:refresh-line" />立即轮询
    </FaButton>
  </FaPageHeader>
  <FaPageMain>
    <div class="mc-news-poll-status">
      <span>上次轮询：{{ pollStatus?.lastPollAtLabel || '尚未轮询' }}</span>
      <span v-if="pollStatus?.lastPollSummary">{{ pollStatus.lastPollSummary }}</span>
      <span>下次轮询：{{ nextPollText(pollStatus, remainSeconds) || '加载中' }}</span>
      <span v-if="ignoredCount > 0" class="mc-news-status-warn">忽略名单：{{ ignoredCount }} 条（被删除的新闻不再推送）</span>
      <FaButton v-if="ignoredCount > 0" size="sm" variant="outline" :loading="clearingIgnored" @click="confirmClearIgnored">
        清空忽略名单
      </FaButton>
      <ArcoTag v-if="pollStatus?.polling" color="arcoblue">轮询进行中</ArcoTag>
      <ArcoTag v-else-if="pollStatus && !pollStatus.enabled" color="gray">已暂停</ArcoTag>
    </div>

    <FaTable
      v-loading="loading"
      :columns="columns"
      :data="rows"
      row-key="id"
      table-root-class="mc-news-table-scroll"
      table-class="mc-news-table-w1000"
      border
      stripe
      column-visibility
      empty-text="暂无新闻，点击右上角立即轮询或等待定时轮询"
    >
      <template #toolbar>
        <FaSearchBar class="w-full">
          <div class="mc-news-toolbar">
            <FaInput
              v-model="filters.keyword"
              class="mc-news-filter-input"
              placeholder="搜索标题、摘要或 AI 正文"
              clearable
              @keydown.enter="applyFilters"
              @clear="applyFilters"
            />
            <FaSelect v-model="filters.sourceId" class="mc-news-filter-select" :options="sourceOptions" @change="applyFilters" />
            <FaButton variant="outline" @click="applyFilters">
              <FaIcon name="i-ri:search-line" />查询
            </FaButton>
          </div>
        </FaSearchBar>
      </template>
      <template #cell-title="{ row }">
        <div class="mc-news-title-cell">
          <a class="mc-news-link mc-news-title" :href="row.original.url" target="_blank" rel="noopener">{{ row.original.title }}</a>
          <span v-if="row.original.summary" class="mc-news-subtitle">{{ row.original.summary }}</span>
          <span v-else-if="row.original.aiSummary" class="mc-news-subtitle">AI：{{ row.original.aiSummary }}</span>
        </div>
      </template>
      <template #cell-pushState="{ row }">
        <FaTag :color="stateMeta(row.original.pushState).color">{{ stateMeta(row.original.pushState).label }}</FaTag>
      </template>
      <template #cell-operation="{ row }">
        <div class="flex-center gap-2">
          <FaButton size="sm" variant="destructive" :loading="deletingId === row.original.id" @click="confirmDelete(row.original)">删除</FaButton>
        </div>
      </template>
    </FaTable>

    <FaPagination
      v-model:page="pager.page"
      v-model:size="pager.size"
      :total="pager.total"
      class="mt-3"
      @page-change="onPageChange"
      @size-change="onSizeChange"
    />
  </FaPageMain>
</template>
