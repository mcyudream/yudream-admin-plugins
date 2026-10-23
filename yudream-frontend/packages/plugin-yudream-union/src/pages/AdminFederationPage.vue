<script setup lang="ts">
import type { ActionMenuItem, SearchConfig, SearchFieldConfig, TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { UnionJoinEvent } from '../types'
import { ref } from 'vue'
import { YdAlert, YdDynamicSearchForm, YdLoadingOverlay, YdModal, YdPagination, YdResponsiveEnhancedTable, YdTablePageLayout, useYdModal, useYdToast } from '@yudream/components'
import { formatDateTime } from '../composables/useUnionApiShell'
import { useUnionFederation } from '../composables/useUnionFederation'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const model = useUnionFederation(props.sdk)
const toast = useYdToast()
const confirm = useYdModal()

const detailOpen = ref(false)
const detail = ref<UnionJoinEvent | null>(null)

/** ControlType 是 components 内部字符串枚举，桥接层只出类型，这里用字面量收敛一次 */
type SearchFieldInput = Partial<Omit<SearchFieldConfig, 'controlType'>> & Pick<SearchFieldConfig, 'fieldName' | 'label'> & { controlType?: 'TEXT' | 'SELECT' | 'DATE_RANGE' | 'NUMBER_RANGE' | 'CHECKBOX' | 'RADIO' }

function searchField(config: SearchFieldInput): SearchFieldConfig {
  const { controlType = 'TEXT', order = 0, showWhenFolded = false, width = '200px', ...rest } = config
  return { ...rest, fieldName: config.fieldName, label: config.label, controlType: controlType as SearchFieldConfig['controlType'], order, showWhenFolded, width }
}

const searchQuery = ref<Record<string, any>>({ site: '', name: '' })

const searchConfig: SearchConfig = {
  fields: [
    searchField({ fieldName: 'site', label: '站点', controlType: 'TEXT', order: 1, showWhenFolded: true, width: '200px' }),
    searchField({ fieldName: 'name', label: '角色名前缀', controlType: 'TEXT', order: 2, showWhenFolded: true, width: '200px' }),
  ],
}

function applySearch() {
  model.filterSite = String(searchQuery.value.site ?? '')
  model.filterName = String(searchQuery.value.name ?? '')
  void model.filter()
}

const columns: TableColumn<UnionJoinEvent>[] = [
  { accessorKey: 'recordedAt', header: '时间', width: 170 },
  { accessorKey: 'direction', header: '方向', width: 110 },
  { accessorKey: 'siteCode', header: '对端站点', minWidth: 140 },
  { accessorKey: 'profileName', header: '角色', minWidth: 140 },
  { id: 'server', header: 'serverId', minWidth: 200, defaultHidden: true, enableSorting: false },
]

const rowActions: ActionMenuItem<UnionJoinEvent>[] = [
  { label: '溯源详情', icon: 'morph:magnify', onClick: event => showDetail(event) },
  { label: '一键封禁', icon: 'morph:shield-ban', danger: true, onClick: event => quickBan(event) },
]

function showDetail(event: UnionJoinEvent) {
  detail.value = event
  detailOpen.value = true
}

function quickBan(event: UnionJoinEvent) {
  confirm.confirm({
    title: '一键封禁',
    content: `确认封禁来自「${event.siteCode}」的角色「${event.profileName}」吗？封禁将写入本站账本并同步给直连受信站。`,
    onConfirm: async () => {
      try {
        await model.quickBan(event)
        toast.success(`已封禁「${event.profileName}」`)
      }
      catch (cause) {
        toast.error(cause instanceof Error ? cause.message : '封禁失败')
      }
    },
  })
}
</script>

<template>
  <YdTablePageLayout title="跨站登录" stats-title="登录统计">
    <template #search="{ fold }">
      <YdDynamicSearchForm
        v-model="searchQuery"
        :config="searchConfig"
        :fold="fold"
        @search="applySearch"
        @reset="applySearch"
      />
    </template>
    <template #stats>
      <div class="grid grid-cols-2 gap-3 md:grid-cols-5">
        <div class="rounded-lg border p-3"><div class="text-2xl font-bold">{{ model.stats.inboundTotal ?? 0 }}</div><span class="text-sm text-muted-foreground">外站登录总数</span></div>
        <div class="rounded-lg border p-3"><div class="text-2xl font-bold">{{ model.stats.today ?? 0 }}</div><span class="text-sm text-muted-foreground">今日</span></div>
        <div class="rounded-lg border p-3"><div class="text-2xl font-bold">{{ model.stats.last7 ?? 0 }}</div><span class="text-sm text-muted-foreground">近 7 天</span></div>
        <div class="rounded-lg border p-3"><div class="text-2xl font-bold">{{ model.stats.last30 ?? 0 }}</div><span class="text-sm text-muted-foreground">近 30 天</span></div>
        <div class="rounded-lg border p-3"><div class="text-2xl font-bold">{{ model.stats.distinctPlayers ?? 0 }}</div><span class="text-sm text-muted-foreground">涉及角色</span></div>
      </div>
      <div v-if="model.stats.bySite?.length" class="mt-3 flex flex-wrap gap-2">
        <span v-for="site in model.stats.bySite" :key="site.siteCode" class="rounded-md border px-2 py-1 text-xs text-muted-foreground">
          {{ site.siteCode }}：{{ site.count }} 次
        </span>
      </div>
      <p class="mt-2 text-sm text-muted-foreground">外站登录 = 本站服务器验证通过的受信站断言；点击列头可按时间、方向、站点、角色排序（服务端排序），每条记录可溯源到签发站点、断言 jti 与目标 serverId。</p>
    </template>

    <template #table>
      <YdAlert v-if="model.error" class="mb-3" variant="destructive" title="加载失败" :description="model.error" />
      <YdLoadingOverlay :loading="model.loading">
        <YdResponsiveEnhancedTable
          :columns="columns"
          :data="model.events"
          row-key="id"
          table-root-class="rounded-lg overflow-hidden"
          table-class="min-w-[760px]"
          border
          stripe
          sortable
          manual-sorting
          :sorting="model.sorting"
          column-visibility
          :actions="rowActions"
          empty-text="暂无跨站登录记录"
          @sorting-change="model.changeSorting"
        >
          <template #cell-recordedAt="{ row }">{{ formatDateTime(row.original.recordedAt) }}</template>
          <template #cell-direction="{ row }">{{ row.original.direction === 'inbound' ? '外站进站' : '本站签发' }}</template>
          <template #cell-siteCode="{ row }">{{ row.original.siteCode }}</template>
          <template #cell-profileName="{ row }">{{ row.original.profileName }}</template>
          <template #cell-server="{ row }"><code class="text-xs">{{ row.original.serverId.slice(0, 16) }}…</code></template>
        </YdResponsiveEnhancedTable>
      </YdLoadingOverlay>
    </template>
    <template #footer>
      <YdPagination
        v-model:page="model.page"
        v-model:size="model.size"
        :total="model.total"
        @page-change="page => model.changePage(page)"
        @size-change="size => model.changePage(1, size)"
      />
    </template>
  </YdTablePageLayout>

  <YdModal v-model="detailOpen" title="跨站登录溯源">
    <div v-if="detail" class="grid gap-2 text-sm">
      <div><span class="text-muted-foreground">事件 ID（jti）：</span><code class="break-all">{{ detail.id }}</code></div>
      <div><span class="text-muted-foreground">角色：</span>{{ detail.profileName }}（<code class="break-all">{{ detail.profileId }}</code>）</div>
      <div><span class="text-muted-foreground">签发站点：</span>{{ detail.siteCode }}</div>
      <div><span class="text-muted-foreground">目标 serverId：</span><code class="break-all">{{ detail.serverId }}</code></div>
      <div><span class="text-muted-foreground">签发方指纹：</span><code class="break-all">{{ detail.issuerKeyId }}</code></div>
      <div><span class="text-muted-foreground">验证时间：</span>{{ formatDateTime(detail.recordedAt) }}</div>
    </div>
  </YdModal>
</template>
