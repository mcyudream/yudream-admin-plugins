<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { Activity } from '../types'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { FaButton, FaCard, FaIcon, FaInput, FaPageHeader, FaPageMain, FaPagination, FaResponsiveTable, FaSearchBar, FaSelect, FaTag } from '@yudream/components'
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
  { id: 'operation', header: '操作', width: 440, align: 'center', fixed: 'right' },
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
</script>

<template>
  <section class="proof-page">
    <FaPageHeader title="活动管理" class="mb-0">
      <FaButton @click="openEdit()">
        <FaIcon name="i-ri:add-line" />发布活动
      </FaButton>
    </FaPageHeader>
    <FaPageMain>
      <FaResponsiveTable
        v-loading="loading"
        row-key="id"
        table-root-class="proof-table-scroll"
        table-class="proof-table-w1740"
        border
        stripe
        column-visibility
        :columns="columns"
        :data="activities"
      >
        <template #toolbar>
          <FaSearchBar class="w-full">
            <div class="activity-filter">
              <FaInput v-model="filters.keyword" placeholder="搜索活动标题" clearable @keyup.enter="model.search" />
              <FaSelect v-model="filters.status" :options="statusOptions" placeholder="状态" />
              <div class="flex justify-end gap-2">
                <FaButton variant="outline" @click="model.resetFilters">重置</FaButton>
                <FaButton :loading="loading" @click="model.search">
                  <FaIcon name="i-ri:search-line" />查询
                </FaButton>
              </div>
            </div>
          </FaSearchBar>
        </template>
        <template #cell-title="{ row }">
          <div class="grid gap-1">
            <strong>{{ row.original.title }}</strong>
            <span v-if="row.original.summary" class="text-xs text-muted-foreground">{{ row.original.summary }}</span>
          </div>
        </template>
        <template #cell-status="{ row }">
          <FaTag :variant="statusTag(row.original).variant">{{ statusTag(row.original).text }}</FaTag>
        </template>
        <template #cell-dept="{ row }">{{ deptText(row.original) }}</template>
        <template #cell-bindings="{ row }">
          <span class="text-sm">{{ bindingText(row.original) }}</span>
        </template>
        <template #cell-signup="{ row }">{{ formatTimeRange(row.original.signupStart, row.original.signupEnd) }}</template>
        <template #cell-activityTime="{ row }">{{ formatTimeRange(row.original.activityStart, row.original.activityEnd) }}</template>
        <template #cell-participants="{ row }">{{ row.original.participantCount }} / {{ row.original.verifiedCount }}</template>
        <template #cell-createdAt="{ row }">{{ formatTime(row.original.createdAt) }}</template>
        <template #cell-operation="{ row }">
          <div class="flex-center flex-wrap gap-2">
            <FaButton size="sm" variant="outline" @click="openDetail(row.original)">详情核验</FaButton>
            <FaButton v-if="row.original.status !== 'CLOSED'" size="sm" variant="outline" @click="openEdit(row.original)">编辑</FaButton>
            <FaButton
              v-if="row.original.status === 'DRAFT'"
              size="sm"
              :loading="actingId === row.original.id"
              @click="model.publish(row.original)"
            >
              发布
            </FaButton>
            <FaButton
              v-if="row.original.status === 'PUBLISHED'"
              size="sm"
              variant="outline"
              :loading="actingId === row.original.id"
              @click="model.close(row.original)"
            >
              结束
            </FaButton>
            <FaButton
              size="sm"
              variant="destructive"
              :loading="actingId === row.original.id"
              @click="model.remove(row.original)"
            >
              删除
            </FaButton>
          </div>
        </template>
        <template #card="{ row }">
          <FaCard class="w-full">
            <div class="flex flex-col gap-3">
              <div class="flex items-center justify-between gap-2">
                <span class="min-w-0 break-words text-base font-semibold">{{ row.title }}</span>
                <FaTag :variant="statusTag(row).variant">{{ statusTag(row).text }}</FaTag>
              </div>
              <div class="flex flex-col gap-1 text-sm">
                <div class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">参与范围</span>
                  <span>{{ deptText(row) }}</span>
                </div>
                <div class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">核验方式</span>
                  <span class="break-all">{{ bindingText(row) }}</span>
                </div>
                <div class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">报名时间</span>
                  <span>{{ formatTimeRange(row.signupStart, row.signupEnd) }}</span>
                </div>
                <div class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">活动时间</span>
                  <span>{{ formatTimeRange(row.activityStart, row.activityEnd) }}</span>
                </div>
                <div class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">参与/通过</span>
                  <span>{{ row.participantCount }} / {{ row.verifiedCount }}</span>
                </div>
              </div>
              <div class="flex flex-wrap gap-2 border-t pt-3">
                <FaButton size="sm" variant="outline" @click="openDetail(row)">详情核验</FaButton>
                <FaButton v-if="row.status !== 'CLOSED'" size="sm" variant="outline" @click="openEdit(row)">编辑</FaButton>
                <FaButton v-if="row.status === 'DRAFT'" size="sm" :loading="actingId === row.id" @click="model.publish(row)">发布</FaButton>
                <FaButton v-if="row.status === 'PUBLISHED'" size="sm" variant="outline" :loading="actingId === row.id" @click="model.close(row)">结束</FaButton>
                <FaButton size="sm" variant="destructive" :loading="actingId === row.id" @click="model.remove(row)">删除</FaButton>
              </div>
            </div>
          </FaCard>
        </template>
      </FaResponsiveTable>
      <FaPagination
        v-model:page="pager.page"
        v-model:size="pager.size"
        :total="pager.total"
        class="mt-3"
        @page-change="model.load"
        @size-change="model.load"
      />
    </FaPageMain>
  </section>
</template>
