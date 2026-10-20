<script setup lang="ts">
import { YdLoadingOverlay, YdTablePageLayout } from '@yudream/components'
import type { TableColumn, ActionMenuItem } from '@yudream/components'
import type { MyParticipation } from '../types'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { YdPagination, YdTag, YdResponsiveEnhancedTable } from '@yudream/components'
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import CoverThumb from '../components/CoverThumb.vue'
import { useMyActivities } from '../composables/useMyActivities'
import { formatTime, formatTimeRange, isActivityEnded } from '../composables/utils'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  route?: RouteLocationNormalizedLoaded
}>()

const router = useRouter()
const model = useMyActivities(props.sdk)
const { loading, actingId, participations, pager } = model

onMounted(model.load)

const columns: TableColumn<MyParticipation>[] = [
  { id: 'activity', header: '活动', width: 260, fixed: 'left' },
  { id: 'activityTime', header: '活动时间', width: 220 },
  { id: 'status', header: '参与状态', width: 100, align: 'center' },
  { id: 'verifyStatus', header: '核验状态', width: 110, align: 'center' },
  { id: 'verifyNote', header: '核验说明', width: 240 },
  { id: 'joinedAt', header: '参与时间', width: 170 },
]

function statusTag(row: MyParticipation) {
  if (row.status === 'CANCELLED') {
    return { variant: 'secondary' as const, text: '已取消' }
  }
  return { variant: 'default' as const, text: '已参与' }
}

function verifyTag(row: MyParticipation) {
  if (row.verifyStatus === 'PASSED') {
    return { variant: 'default' as const, text: '已通过' }
  }
  if (row.verifyStatus === 'FAILED') {
    return { variant: 'destructive' as const, text: '未通过' }
  }
  return { variant: 'secondary' as const, text: '待核验' }
}

function ended(row: MyParticipation) {
  return isActivityEnded(row.activityStatus, row.activityEnd)
}

function openDetail(row: MyParticipation) {
  router.push({ path: '/platform/plugins/yudream-student-info/activity-square/detail', query: { id: row.activityId } })
}

/** 行操作：查看活动 / 自助核验 / 退出报名 */
const rowActions: ActionMenuItem<unknown>[] = [
  { label: '查看活动', icon: 'morph:eye', onClick: row => openDetail(row as never) },
  { label: '自助核验', icon: 'morph:check', visible: row => (row as { status: string }).status === 'JOINED' && (row as { verifyStatus: string }).verifyStatus !== 'PASSED', disabled: () => actingId.value !== '', onClick: row => model.verify(row as never) },
]

</script>

<template>
  <YdTablePageLayout title="我的参与" :show-search="false">
    <template #table>
<YdLoadingOverlay :loading="loading"><YdResponsiveEnhancedTable
          :actions="rowActions"
        
        
        row-key="activityId"
        table-root-class="proof-table-scroll"
        table-class="proof-table-w1240"
        border
        stripe
        column-visibility
        :columns="columns"
        :data="participations"
      >
        <template #cell-activity="{ row }">
          <div class="flex items-center gap-2">
            <CoverThumb
              v-if="row.original.coverUrl"
              :src="model.coverThumbOf(row.original)"
              :fallback-src="model.coverOf(row.original)"
              img-class="activity-table-cover"
            />
            <div class="grid gap-1">
              <strong>{{ row.original.title }}</strong>
              <YdTag v-if="ended(row.original)" variant="secondary" class="w-fit">已结束</YdTag>
            </div>
          </div>
        </template>
        <template #cell-activityTime="{ row }">{{ formatTimeRange(row.original.activityStart, row.original.activityEnd) }}</template>
        <template #cell-status="{ row }">
          <YdTag :variant="statusTag(row.original).variant">{{ statusTag(row.original).text }}</YdTag>
        </template>
        <template #cell-verifyStatus="{ row }">
          <YdTag :variant="verifyTag(row.original).variant">{{ verifyTag(row.original).text }}</YdTag>
        </template>
        <template #cell-verifyNote="{ row }">{{ row.original.verifyNote || '-' }}</template>
        <template #cell-joinedAt="{ row }">{{ formatTime(row.original.joinedAt) }}</template>
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
