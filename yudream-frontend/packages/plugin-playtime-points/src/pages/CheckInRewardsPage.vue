<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { TableColumn } from '@yudream/components'
import type { CheckInRewardRecord } from '../types'
import { FaAlert, FaButton, FaIcon, FaInput, FaPageHeader, FaPageMain, FaPagination, FaSearchBar, FaSelect, FaTable, FaTag } from '@yudream/components'
import { onMounted } from 'vue'
import { createPlaytimePointsApi } from '../api/playtime-points-api'
import { useCheckInRewards } from '../composables/useCheckInRewards'
import { checkInRewardLabel, formatTime, sourceLabel } from '../composables/utils'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()

const api = createPlaytimePointsApi(props.sdk)
const model = useCheckInRewards(api)
const { loading, records, projectOptions, providerAvailable, pager, filters } = model

const columns: TableColumn<CheckInRewardRecord>[] = [
  { id: 'acceptedAt', header: '验收通过时间', width: 180 },
  { accessorKey: 'projectName', header: '项目', width: 180 },
  { accessorKey: 'detailTitle', header: '工作细节', width: 180 },
  { accessorKey: 'userId', header: '用户 ID', width: 160 },
  { id: 'checkInId', header: '打卡记录 ID', width: 300 },
  { id: 'points', header: '积分', width: 100, align: 'center' },
  { id: 'calc', header: '折算方式', width: 300 },
  { id: 'source', header: '来源', width: 110, align: 'center' },
  { id: 'createdAt', header: '发放时间', width: 180 },
]

onMounted(() => {
  void model.load()
  void model.loadProjectOptions()
})
</script>

<template>
  <FaPageHeader title="打卡积分">
    <FaButton :loading="loading" @click="model.load()">
      <FaIcon name="i-ri:refresh-line" />
      刷新
    </FaButton>
  </FaPageHeader>

  <FaPageMain>
    <FaAlert v-if="!providerAvailable" class="mb-3" variant="destructive" title="项目管理插件不可用">
      <template #description>
        未检测到 project-progress 插件（或版本过旧），当前不会有新的打卡积分发放；已发放的历史流水仍可查看。
      </template>
    </FaAlert>

    <p class="ptp-hint mb-3">
      「折算方式」列说明每条打卡的积分怎么来的：固定金额模式显示每次金额；按时薪折算模式下，有有效在线时长的打卡显示
      「有效在线 XX 分钟 × 时薪 X = Y 积分」，没有时长的打卡（图片/文件/定位等非 MC 打卡）显示
      「非时长打卡 · 固定 N 积分」（按设置里的全局「非时长打卡每次积分」发放，不受按项目覆盖影响）。
      折算不足一个最小入账单位、或非时长打卡积分为 0 而未发放时，显示未发放的中文原因，这类打卡不会影响其它打卡的发放。
    </p>

    <FaTable
      v-loading="loading"
      row-key="checkInId"
      table-root-class="rounded-lg overflow-hidden"
      table-class="ptp-table-w1700"
      border
      stripe
      column-visibility
      :columns="columns"
      :data="records"
    >
      <template #toolbar>
        <FaSearchBar class="w-full">
          <div class="ptp-filters">
            <FaSelect
              v-model="filters.projectId"
              :options="projectOptions"
              placeholder="全部项目"
              clearable
              class="ptp-filter-control"
              @change="model.search()"
            />
            <FaInput
              v-model="filters.userId"
              placeholder="按用户 ID 精确筛选"
              clearable
              class="ptp-filter-control"
              @keyup.enter="model.search()"
            />
            <div class="flex items-center gap-2">
              <FaButton size="sm" @click="model.search()">
                <FaIcon name="i-ri:search-line" />
                查询
              </FaButton>
              <FaButton size="sm" variant="outline" @click="model.resetFilters()">
                重置
              </FaButton>
            </div>
          </div>
        </FaSearchBar>
      </template>
      <template #cell-acceptedAt="{ row }">{{ formatTime(row.original.acceptedAt) }}</template>
      <template #cell-projectName="{ row }">
        <div class="ptp-name-cell">
          <span class="ptp-name-text">{{ row.original.projectName || row.original.projectId }}</span>
        </div>
      </template>
      <template #cell-detailTitle="{ row }">{{ row.original.detailTitle || row.original.detailId }}</template>
      <template #cell-checkInId="{ row }">
        <span class="ptp-key-text">{{ row.original.checkInId }}</span>
      </template>
      <template #cell-points="{ row }">
        <FaTag :variant="Number(row.original.credit) > 0 ? 'secondary' : 'outline'">
          {{ Number(row.original.credit) > 0 ? `+${row.original.credit}` : '未发放' }}
        </FaTag>
      </template>
      <template #cell-calc="{ row }">
        <span class="ptp-calc-text" :class="{ 'ptp-calc-skipped': !(Number(row.original.credit) > 0) }">
          {{ checkInRewardLabel(row.original) }}
        </span>
      </template>
      <template #cell-source="{ row }">
        <FaTag :variant="row.original.source === 'REALTIME' ? 'default' : 'outline'">
          {{ sourceLabel(row.original.source) }}
        </FaTag>
      </template>
      <template #cell-createdAt="{ row }">{{ formatTime(row.original.createdAt) }}</template>
      <template #empty>
        <div class="ptp-empty">
          <FaIcon name="i-ri:map-pin-time-line" />
          <span>暂无打卡积分流水：项目工作细节验收通过后，这里会出现发放记录。</span>
        </div>
      </template>
    </FaTable>

    <FaPagination
      v-model:page="pager.page"
      v-model:size="pager.size"
      :total="pager.total"
      class="mt-3"
      @page-change="model.onPageChange"
      @size-change="model.onSizeChange"
    />
  </FaPageMain>
</template>
