<script setup lang="ts">
import type { SearchConfig, SearchFieldConfig, SearchOptionsSource, TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { CheckInRewardRecord } from '../types'
import { YdAlert, YdDynamicSearchForm, YdLoadingOverlay, YdPagination, YdResponsiveEnhancedTable, YdTablePageLayout } from '@yudream/components'
import { onMounted, ref } from 'vue'
import { createPlaytimePointsApi } from '../api/playtime-points-api'
import { useCheckInRewards } from '../composables/useCheckInRewards'
import { checkInRewardLabel, formatTime, sourceLabel } from '../composables/utils'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()

const api = createPlaytimePointsApi(props.sdk)
const model = useCheckInRewards(api)
const { loading, records, projectOptions, providerAvailable, pager, filters } = model

/** ControlType 是 components 内部的字符串枚举，桥接层只出类型，这里用字面量收敛一次 */
type SearchFieldInput = Partial<Omit<SearchFieldConfig, 'controlType'>> & Pick<SearchFieldConfig, 'fieldName' | 'label'> & { controlType?: 'TEXT' | 'SELECT' | 'DATE_RANGE' | 'NUMBER_RANGE' | 'CHECKBOX' | 'RADIO' }

function searchField(config: SearchFieldInput): SearchFieldConfig {
  const { controlType = 'TEXT', order = 0, showWhenFolded = false, width = '200px', ...rest } = config
  return { ...rest, fieldName: config.fieldName, label: config.label, controlType: controlType as SearchFieldConfig['controlType'], order, showWhenFolded, width }
}

const searchQuery = ref<Record<string, any>>({
  projectId: filters.projectId ?? '',
  userId: filters.userId ?? '',
})

const searchConfig: SearchConfig = {
  fields: [
    searchField({ fieldName: 'userId', label: '用户 ID', placeholder: '按用户 ID 精确筛选', order: 1, showWhenFolded: true, width: '220px' }),
    searchField({ fieldName: 'projectId', label: '项目', controlType: 'SELECT', order: 2, width: '220px' }),
  ],
}

const optionsSources: SearchOptionsSource[] = [
  { id: 'projectOptions', loader: () => [{ label: '全部项目', value: '' }, ...projectOptions.value] },
]

function applySearch() {
  filters.projectId = String(searchQuery.value.projectId ?? '')
  filters.userId = String(searchQuery.value.userId ?? '')
  model.search()
}

function resetSearch() {
  model.resetFilters()
  searchQuery.value = { projectId: '', userId: '' }
}

const columns: TableColumn<CheckInRewardRecord>[] = [
  { id: 'acceptedAt', header: '验收通过时间', width: 180 },
  { accessorKey: 'projectName', header: '项目', width: 180 },
  { accessorKey: 'detailTitle', header: '工作细节', width: 180 },
  { accessorKey: 'userId', header: '用户 ID', width: 160 },
  { id: 'checkInId', header: '打卡记录 ID', width: 300, defaultHidden: true },
  { id: 'points', header: '积分', width: 110 },
  { id: 'calc', header: '折算方式', width: 300 },
  { id: 'source', header: '来源', width: 110 },
  { accessorKey: 'createdAt', header: '发放时间', width: 180, defaultHidden: true },
]

onMounted(() => {
  void model.load()
  void model.loadProjectOptions()
})
</script>

<template>
  <YdTablePageLayout title="打卡积分">
    <template #stats>
      <YdAlert v-if="!providerAvailable" variant="destructive" title="项目管理插件不可用">
        <template #description>
          未检测到 project-progress 插件（或版本过旧），当前不会有新的打卡积分发放；已发放的历史流水仍可查看。
        </template>
      </YdAlert>
      <p class="ptp-hint">
        「折算方式」列说明每条打卡的积分怎么来的：固定金额模式显示每次金额；按时薪折算模式下，有有效在线时长的打卡显示
        「有效在线 XX 分钟 × 时薪 X = Y 积分」，没有时长的打卡（图片/文件/定位等非 MC 打卡）显示
        「非时长打卡 · 固定 N 积分」（按设置里的全局「非时长打卡每次积分」发放，不受按项目覆盖影响）。
        折算不足一个最小入账单位、或非时长打卡积分为 0 而未发放时，显示未发放的中文原因，这类打卡不会影响其它打卡的发放。
      </p>
    </template>

    <template #search="{ fold }">
      <YdDynamicSearchForm
        v-model="searchQuery"
        :config="searchConfig"
        :fold="fold"
        :options-sources="optionsSources"
        @search="applySearch"
        @reset="resetSearch"
      />
    </template>

    <template #table>
      <YdLoadingOverlay :loading="loading">
        <YdResponsiveEnhancedTable
          row-key="checkInId"
          table-root-class="rounded-lg overflow-hidden"
          table-class="min-w-[1480px]"
          border
          stripe
          column-visibility
          :columns="columns"
          :data="records"
          empty-text="暂无打卡积分流水：项目工作细节验收通过后，这里会出现发放记录。"
        >
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
            <span class="yd-status-tone" :data-tone="Number(row.original.credit) > 0 ? 'success' : 'neutral'">
              {{ Number(row.original.credit) > 0 ? `+${row.original.credit}` : '未发放' }}
            </span>
          </template>
          <template #cell-calc="{ row }">
            <span class="ptp-calc-text" :class="{ 'ptp-calc-skipped': !(Number(row.original.credit) > 0) }">
              {{ checkInRewardLabel(row.original) }}
            </span>
          </template>
          <template #cell-source="{ row }">
            <span class="yd-status-tone" :data-tone="row.original.source === 'REALTIME' ? 'info' : 'neutral'">
              {{ sourceLabel(row.original.source) }}
            </span>
          </template>
          <template #cell-createdAt="{ row }">{{ formatTime(row.original.createdAt) }}</template>
        </YdResponsiveEnhancedTable>
      </YdLoadingOverlay>
    </template>

    <template #footer>
      <YdPagination
        v-model:page="pager.page"
        v-model:size="pager.size"
        :total="pager.total"
        @page-change="model.onPageChange"
        @size-change="model.onSizeChange"
      />
    </template>
  </YdTablePageLayout>
</template>
