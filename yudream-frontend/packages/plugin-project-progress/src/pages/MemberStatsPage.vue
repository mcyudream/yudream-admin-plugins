<script setup lang="ts">
import { YdLoadingOverlay, YdTablePageLayout } from '@yudream/components'
import type { TableColumn } from '@yudream/components'
import type { ProjectProgressModel } from '../composables/useProjectProgress'
import type { ProjectMemberStats } from '../types'
import { computed } from 'vue'
import { YdResponsiveEnhancedTable, YdSelect } from '@yudream/components'

const props = defineProps<{
  model: ProjectProgressModel
}>()

const projectOptions = computed(() => props.model.projects.map(project => ({
  label: project.name,
  value: project.id,
})))

const tableColumns = computed<TableColumn<ProjectMemberStats>[]>(() => [
  { id: 'user', header: '成员', width: 260, fixed: 'left' },
  { accessorKey: 'assignedDetails', header: '分配细分', width: 120, align: 'center' },
  { accessorKey: 'completedDetails', header: '已完成', width: 110, align: 'center' },
  { accessorKey: 'pendingAcceptanceDetails', header: '待验收', width: 110, align: 'center' },
  { accessorKey: 'acceptedReviews', header: '通过', width: 96, align: 'center' },
  { accessorKey: 'rejectedReviews', header: '退回', width: 96, align: 'center' },
  { accessorKey: 'checkIns', header: '打卡', width: 96, align: 'center' },
  { id: 'lastActivityAt', header: '最近活动', width: 180 },
])

async function selectProject(projectId: unknown) {
  const value = String(projectId || '')
  await props.model.selectProjectById(value)
  await props.model.loadMemberStats(value)
}

function userFor(userId: string) {
  return props.model.userOptionsForIds([userId])[0]
}
</script>

<template>
  <YdTablePageLayout title="成员统计" :stats-default-expanded="true">
    <template #search>
      <YdSelect
        :model-value="model.selectedProjectId"
        :options="projectOptions"
        placeholder="选择项目"
        class="pp-project-select"
        @update:model-value="selectProject"
      />
    </template>

    <template #stats>
      <p class="pp-muted">统计口径：完成按细分当前完成状态，通过/退回按验收处理记录计数</p>
      <div class="pp-stat-grid">
        <article class="pp-stat-card">
          <span>项目成员</span>
          <strong>{{ model.memberStats.length }}</strong>
        </article>
        <article class="pp-stat-card">
          <span>分配细分</span>
          <strong>{{ model.memberStats.reduce((sum, item) => sum + item.assignedDetails, 0) }}</strong>
        </article>
        <article class="pp-stat-card">
          <span>已完成细分</span>
          <strong>{{ model.memberStats.reduce((sum, item) => sum + item.completedDetails, 0) }}</strong>
        </article>
        <article class="pp-stat-card">
          <span>验收通过</span>
          <strong>{{ model.memberStats.reduce((sum, item) => sum + item.acceptedReviews, 0) }}</strong>
        </article>
        <article class="pp-stat-card">
          <span>退回返工</span>
          <strong>{{ model.memberStats.reduce((sum, item) => sum + item.rejectedReviews, 0) }}</strong>
        </article>
        <article class="pp-stat-card">
          <span>打卡次数</span>
          <strong>{{ model.memberStats.reduce((sum, item) => sum + item.checkIns, 0) }}</strong>
        </article>
      </div>
    </template>

    <template #table>
      <YdLoadingOverlay :loading="model.loading">
        <YdResponsiveEnhancedTable
          row-key="userId"
          table-root-class="rounded-lg overflow-hidden"
          table-class="min-w-[940px]"
          border
          stripe
          :columns="tableColumns"
          :data="model.memberStats"
          empty-text="暂无成员统计"
        >
          <template #cell-user="{ row }">
            <strong>{{ model.userLabel(userFor(row.original.userId)) }}</strong>
            <div class="pp-table-sub">{{ model.userMeta(userFor(row.original.userId)) }}</div>
          </template>
          <template #cell-lastActivityAt="{ row }">
            {{ model.formatTime(row.original.lastActivityAt) }}
          </template>
        </YdResponsiveEnhancedTable>
      </YdLoadingOverlay>
    </template>
  </YdTablePageLayout>
</template>
