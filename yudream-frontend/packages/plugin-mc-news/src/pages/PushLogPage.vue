<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { TableColumn } from '@yudream/components'
import type { PushLogView } from '../types'
import { FaButton, FaDrawer, FaPageHeader, FaPageMain, FaPagination, FaTable, FaTag, useFaToast } from '@yudream/components'
import { computed, onMounted, reactive, ref } from 'vue'
import { createMcNewsApi } from '../api/mc-news-api'
import { errorMessage, LOG_MODE_META } from '../composables/utils'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = createMcNewsApi(props.sdk)
const toast = useFaToast()

const loading = ref(false)
const rows = ref<PushLogView[]>([])
const pager = reactive({ page: 1, size: 10, total: 0 })
const detail = ref<PushLogView | null>(null)
const detailOpen = ref(false)

const columns: TableColumn<PushLogView>[] = [
  { accessorKey: 'createdAtLabel', header: '时间', width: 150 },
  { id: 'mode', header: '类型', width: 100 },
  { id: 'title', header: '新闻', minWidth: 280 },
  { accessorKey: 'sourceName', header: '来源', width: 160 },
  { id: 'result', header: '结果', width: 110 },
  { id: 'operation', header: '操作', width: 90, fixed: 'right' },
]

const detailResults = computed(() => detail.value?.results ?? [])

function modeMeta(mode: string) {
  return LOG_MODE_META[mode] ?? { label: mode, color: 'gray' }
}

async function load() {
  loading.value = true
  try {
    const page = await api.logs(pager.page, pager.size)
    rows.value = page.records ?? []
    pager.total = Number(page.total ?? 0)
  }
  catch (error) {
    toast.error(errorMessage(error, '加载推送记录失败'))
  }
  finally {
    loading.value = false
  }
}

function onPageChange() {
  void load()
}

function onSizeChange() {
  pager.page = 1
  void load()
}

function openDetail(row: PushLogView) {
  detail.value = row
  detailOpen.value = true
}

onMounted(() => {
  void load()
})
</script>

<template>
  <FaPageHeader title="推送记录" description="每条新新闻的推送明细：目标、成功与失败原因" />
  <FaPageMain>
    <FaTable
      v-loading="loading"
      :columns="columns"
      :data="rows"
      row-key="id"
      table-root-class="mc-news-table-scroll"
      table-class="mc-news-table-w900"
      border
      stripe
      column-visibility
      empty-text="暂无推送记录"
    >
      <template #cell-mode="{ row }">
        <FaTag :color="modeMeta(row.original.mode).color">{{ modeMeta(row.original.mode).label }}</FaTag>
      </template>
      <template #cell-title="{ row }">
        <a v-if="row.original.url" class="mc-news-link" :href="row.original.url" target="_blank" rel="noopener">{{ row.original.title }}</a>
        <span v-else>{{ row.original.title }}</span>
      </template>
      <template #cell-result="{ row }">
        <FaTag :color="row.original.okCount > 0 ? 'green' : 'red'">{{ row.original.okCount }}/{{ row.original.total }}</FaTag>
      </template>
      <template #cell-operation="{ row }">
        <div class="flex-center gap-2">
          <FaButton size="sm" variant="outline" @click="openDetail(row.original)">详情</FaButton>
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

    <FaDrawer v-model="detailOpen" title="推送明细" side="right" :show-confirm-button="false" :footer="false" content-class="mc-news-drawer-body">
      <div v-if="detail" class="mc-news-form">
        <div class="mc-news-form-item">
          <span class="mc-news-form-label">{{ detail.title }}</span>
          <span class="mc-news-form-hint">{{ detail.createdAtLabel }}｜{{ modeMeta(detail.mode).label }}｜{{ detail.sourceName }}</span>
        </div>
        <div class="mc-news-result-list">
          <div v-for="result in detailResults" :key="result.targetId" class="mc-news-result-row">
            <FaTag :color="result.ok ? 'green' : 'red'">{{ result.ok ? '成功' : '失败' }}</FaTag>
            <span>{{ result.targetName }}</span>
            <span class="mc-news-form-hint">{{ result.targetType }}</span>
            <span v-if="result.error" class="mc-news-form-hint">{{ result.error }}</span>
          </div>
          <span v-if="!detailResults.length" class="mc-news-form-hint">无推送目标</span>
        </div>
      </div>
    </FaDrawer>
  </FaPageMain>
</template>
