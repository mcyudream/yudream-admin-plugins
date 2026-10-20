<script setup lang="ts">
import { YdLoadingOverlay } from '@yudream/components'
import type { TableColumn, ActionMenuItem } from '@yudream/components'
import type { ActivityProofExportRecord } from '../types'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { YdButton, YdIcon, YdPagination, YdResponsiveEnhancedTable, YdTablePageLayout } from '@yudream/components'
import { onMounted } from 'vue'
import { useMyProofs } from '../composables/useMyProofs'
import { formatTime } from '../composables/utils'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  route?: RouteLocationNormalizedLoaded
}>()

const model = useMyProofs(props.sdk)
const { loading, downloading, records, pager } = model

onMounted(model.load)

const columns: TableColumn<ActivityProofExportRecord>[] = [
  { accessorKey: 'activityName', header: '活动', width: 240, fixed: 'left' },
  { accessorKey: 'serverName', header: '服务器', width: 180 },
  { id: 'uploadedAt', header: '盖章时间', width: 180 },
]

/** 行操作：下载盖章证明 PDF */
const rowActions: ActionMenuItem<unknown>[] = [
  { label: '下载 PDF', icon: 'morph:download', disabled: () => downloading.value, onClick: row => model.downloadStamped(row as never) },
]

</script>

<template>
  <YdTablePageLayout title="我的活动证明">
    <template #actions>
      <YdButton variant="outline" :loading="loading" @click="model.load">
        <YdIcon name="i-ri:refresh-line" />刷新
      </YdButton>
    </template>
    <template #table>
      <YdLoadingOverlay :loading="loading"><YdResponsiveEnhancedTable
          :actions="rowActions"
        
        
        row-key="id"
        table-root-class="proof-table-scroll"
        table-class="proof-table-w760"
        border
        stripe
        column-visibility
        :columns="columns"
        :data="records"
      >
        <template #cell-uploadedAt="{ row }">{{ formatTime(row.original.stampedPdfUploadedAt) }}</template>
</YdResponsiveEnhancedTable></YdLoadingOverlay>
    </template>
    <template #footer>
      <YdPagination
        v-model:page="pager.page"
        v-model:size="pager.size"
        :total="pager.total"
        @page-change="model.load"
        @size-change="model.load"
      />
    </template>
  </YdTablePageLayout>
</template>
