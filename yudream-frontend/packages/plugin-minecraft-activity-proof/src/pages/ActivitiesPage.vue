<script setup lang="ts">
import { YdLoadingOverlay, YdTablePageLayout } from '@yudream/components'
import type { TableColumn, ActionMenuItem } from '@yudream/components'
import type { Activity } from '../types'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { YdButton, YdIcon, YdInput, YdPagination, YdSelect, YdTag, YdResponsiveEnhancedTable } from '@yudream/components'
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAdminActivities } from '../composables/useAdminActivities'
import { formatTime, formatTimeRange } from '../composables/utils'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  route?: RouteLocationNormalizedLoaded
}>()

const router = useRouter()
const model = useAdminActivities(props.sdk)
const { loading, actingId, activities, pager, filters } = model

onMounted(model.load)

const statusOptions = [
  { label: '全部状态', value: '' },
  { label: '草稿', value: 'DRAFT' },
  { label: '已发布', value: 'PUBLISHED' },
  { label: '已结束', value: 'CLOSED' },
]

const columns: TableColumn<Activity>[] = [
  { id: 'title', header: '活动', width: 260, fixed: 'left' },
  { id: 'status', header: '状态', width: 100, align: 'center' },
  { id: 'dept', header: '参与范围', width: 200 },
  { id: 'bindings', header: '核验方式', width: 240 },
  { id: 'signup', header: '报名时间', width: 210 },
  { id: 'activityTime', header: '活动时间', width: 210 },
  { id: 'participants', header: '参与/通过', width: 110, align: 'center' },
  { id: 'createdAt', header: '创建时间', width: 170 },
]

function statusTag(row: Activity) {
  if (row.status === 'PUBLISHED') {
    return { variant: 'default' as const, text: '已发布' }
  }
  if (row.status === 'CLOSED') {
    return { variant: 'secondary' as const, text: '已结束' }
  }
  return { variant: 'outline' as const, text: '草稿' }
}

function deptText(row: Activity) {
  if (row.deptMode !== 'DEPTS') {
    return '全体成员'
  }
  return (row.allowedDeptNames || []).join('、') || '指定部门'
}

function bindingText(row: Activity) {
  if (!row.bindings?.length) {
    return '参与即达标'
  }
  return row.bindings.map(item => item.requirementText).join('；')
}

function openEdit(row?: Activity) {
  router.push({ path: '/platform/plugins/yudream-student-info/activity-proof/activities/edit', query: row ? { id: row.id } : {} })
}

function openDetail(row: Activity) {
  router.push({ path: '/platform/plugins/yudream-student-info/activity-proof/activities/detail', query: { id: row.id } })
}

/** 行操作统一收进操作菜单，按活动状态出现 */
const rowActions: ActionMenuItem<unknown>[] = [
  { label: '详情核验', icon: 'morph:eye', onClick: row => openDetail(row as never) },
  { label: '编辑', icon: 'morph:pencil', visible: row => (row as { status: string }).status !== 'CLOSED', onClick: row => openEdit(row as never) },
  { label: '发布', icon: 'morph:send', visible: row => (row as { status: string }).status === 'DRAFT', disabled: () => actingId.value !== '', onClick: row => model.publish(row as never) },
  { label: '结束', icon: 'morph:flag', visible: row => (row as { status: string }).status === 'PUBLISHED', disabled: () => actingId.value !== '', onClick: row => model.close(row as never) },
  { label: '删除', icon: 'morph:trash-2', danger: true, disabled: () => actingId.value !== '', onClick: row => model.remove(row as never) },
]

</script>

<template>
  <YdTablePageLayout title="活动管理">
    <template #actions>
      <YdButton @click="openEdit()">
        <YdIcon name="i-ri:add-line" />发布活动
      </YdButton>
    </template>
    <template #search>
      <div class="yd-filter-grid" style="--yd-filter-columns: 2">
        <YdInput v-model="filters.keyword" placeholder="搜索活动标题" clearable class="w-full" @keyup.enter="model.search" />
        <YdSelect v-model="filters.status" :options="statusOptions" placeholder="状态" class="w-full" />
        <div class="flex items-center justify-end gap-2">
          <YdButton variant="outline" @click="model.resetFilters">重置</YdButton>
          <YdButton :loading="loading" @click="model.search">
            <YdIcon name="i-ri:search-line" />查询
          </YdButton>
        </div>
      </div>
    </template>
    <template #table>
<YdLoadingOverlay :loading="loading"><YdResponsiveEnhancedTable
          :actions="rowActions"
        
        
        row-key="id"
        table-root-class="proof-table-scroll"
        table-class="proof-table-w1740"
        border
        stripe
        column-visibility
        :columns="columns"
        :data="activities"
      >
        <template #cell-title="{ row }">
          <div class="grid gap-1">
            <strong>{{ row.original.title }}</strong>
            <span v-if="row.original.summary" class="text-xs text-muted-foreground">{{ row.original.summary }}</span>
          </div>
        </template>
        <template #cell-status="{ row }">
          <YdTag :variant="statusTag(row.original).variant">{{ statusTag(row.original).text }}</YdTag>
        </template>
        <template #cell-dept="{ row }">{{ deptText(row.original) }}</template>
        <template #cell-bindings="{ row }">
          <span class="text-sm">{{ bindingText(row.original) }}</span>
        </template>
        <template #cell-signup="{ row }">{{ formatTimeRange(row.original.signupStart, row.original.signupEnd) }}</template>
        <template #cell-activityTime="{ row }">{{ formatTimeRange(row.original.activityStart, row.original.activityEnd) }}</template>
        <template #cell-participants="{ row }">{{ row.original.participantCount }} / {{ row.original.verifiedCount }}</template>
        <template #cell-createdAt="{ row }">{{ formatTime(row.original.createdAt) }}</template>
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
