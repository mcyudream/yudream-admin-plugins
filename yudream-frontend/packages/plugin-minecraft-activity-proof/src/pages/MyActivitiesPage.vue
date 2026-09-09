<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { MyParticipation } from '../types'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { FaButton, FaCard, FaIcon, FaPageHeader, FaPageMain, FaPagination, FaResponsiveTable, FaTag } from '@yudream/components'
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
  { id: 'operation', header: '操作', width: 260, align: 'center', fixed: 'right' },
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
</script>

<template>
  <section class="proof-page">
    <FaPageHeader title="我的参与" class="mb-0">
      <FaButton variant="outline" :loading="loading" @click="model.load">
        <FaIcon name="i-ri:refresh-line" />刷新
      </FaButton>
    </FaPageHeader>
    <FaPageMain>
      <FaResponsiveTable
        v-loading="loading"
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
              <FaTag v-if="ended(row.original)" variant="secondary" class="w-fit">已结束</FaTag>
            </div>
          </div>
        </template>
        <template #cell-activityTime="{ row }">{{ formatTimeRange(row.original.activityStart, row.original.activityEnd) }}</template>
        <template #cell-status="{ row }">
          <FaTag :variant="statusTag(row.original).variant">{{ statusTag(row.original).text }}</FaTag>
        </template>
        <template #cell-verifyStatus="{ row }">
          <FaTag :variant="verifyTag(row.original).variant">{{ verifyTag(row.original).text }}</FaTag>
        </template>
        <template #cell-verifyNote="{ row }">{{ row.original.verifyNote || '-' }}</template>
        <template #cell-joinedAt="{ row }">{{ formatTime(row.original.joinedAt) }}</template>
        <template #cell-operation="{ row }">
          <div class="flex-center flex-wrap gap-2">
            <FaButton size="sm" variant="outline" @click="openDetail(row.original)">查看活动</FaButton>
            <FaButton
              v-if="row.original.status === 'JOINED' && row.original.verifyStatus !== 'PASSED'"
              size="sm"
              variant="outline"
              :loading="actingId === row.original.activityId"
              @click="model.verify(row.original)"
            >
              自助核验
            </FaButton>
            <FaButton
              v-if="row.original.status === 'JOINED' && !ended(row.original)"
              size="sm"
              variant="destructive"
              :loading="actingId === row.original.activityId"
              @click="model.cancel(row.original)"
            >
              取消参与
            </FaButton>
          </div>
        </template>
        <template #card="{ row }">
          <FaCard class="w-full">
            <div class="flex flex-col gap-3">
              <div class="flex items-center justify-between gap-2">
                <span class="min-w-0 break-words text-base font-semibold">{{ row.title }}</span>
                <FaTag :variant="verifyTag(row).variant">{{ verifyTag(row).text }}</FaTag>
              </div>
              <div class="flex flex-col gap-1 text-sm">
                <div class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">活动时间</span>
                  <span>{{ formatTimeRange(row.activityStart, row.activityEnd) }}</span>
                </div>
                <div class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">参与状态</span>
                  <span>{{ statusTag(row).text }}</span>
                </div>
                <div v-if="row.verifyNote" class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">核验说明</span>
                  <span class="break-all">{{ row.verifyNote }}</span>
                </div>
                <div class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">参与时间</span>
                  <span>{{ formatTime(row.joinedAt) }}</span>
                </div>
              </div>
              <div class="flex flex-wrap gap-2 border-t pt-3">
                <FaButton size="sm" variant="outline" @click="openDetail(row)">查看活动</FaButton>
                <FaButton
                  v-if="row.status === 'JOINED' && row.verifyStatus !== 'PASSED'"
                  size="sm"
                  variant="outline"
                  :loading="actingId === row.activityId"
                  @click="model.verify(row)"
                >
                  自助核验
                </FaButton>
                <FaButton
                  v-if="row.status === 'JOINED' && !ended(row)"
                  size="sm"
                  variant="destructive"
                  :loading="actingId === row.activityId"
                  @click="model.cancel(row)"
                >
                  取消参与
                </FaButton>
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
