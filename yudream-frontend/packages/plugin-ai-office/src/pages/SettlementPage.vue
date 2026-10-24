<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { SettlementBatch, SettlementOverview } from '../types'
import { YdAlert, YdButton, YdInput, YdLoadingOverlay, YdResponsiveEnhancedTable, YdTablePageLayout, YdTag, useYdModal, useYdToast } from '@yudream/components'
import { onMounted, ref } from 'vue'
import { createOfficeAdminApi, errorMessage } from '../api/office-api'
import { moneyText } from '../types'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()

const toast = useYdToast()
const confirm = useYdModal()
const api = createOfficeAdminApi(props.sdk)

const period = ref(new Date().toISOString().slice(0, 7))
const overview = ref<SettlementOverview | null>(null)
const batches = ref<SettlementBatch[]>([])
const loading = ref(false)
const error = ref('')
const closing = ref(false)

const columns: TableColumn<SettlementBatch>[] = [
  { accessorKey: 'period', header: '周期', width: 130 },
  { id: 'status', header: '状态', width: 110, enableSorting: false },
  { id: 'income', header: '收入', width: 130, enableSorting: false },
  { id: 'expense', header: '支出', width: 130, enableSorting: false },
  { id: 'balance', header: '结转结余', width: 140, enableSorting: false },
  { accessorKey: 'closedAt', header: '关账时间', width: 180 },
]

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [overviewData, batchData] = await Promise.all([
      api.settlementOverview(period.value),
      api.settlements(),
    ])
    overview.value = overviewData
    batches.value = batchData
  }
  catch (cause) {
    error.value = errorMessage(cause, '结算数据加载失败')
  }
  finally {
    loading.value = false
  }
}

function requestClose() {
  if (!/^\d{4}-\d{2}$/.test(period.value)) {
    toast.warning('周期格式应为 yyyy-MM')
    return
  }
  confirm.confirm({
    title: '关账结算',
    content: `确认对 ${period.value} 关账吗？当期已采纳记录将纳入结算并锁定，结余结转下期，不可撤销。`,
    onConfirm: async () => {
      closing.value = true
      try {
        await api.closeSettlement(period.value)
        toast.success('已关账')
        await load()
      }
      catch (cause) {
        toast.error(errorMessage(cause, '关账失败'))
      }
      finally {
        closing.value = false
      }
    },
  })
}

function closeTime(batch: SettlementBatch) {
  return batch.closedAt ? new Date(batch.closedAt).toLocaleString('zh-CN', { hour12: false }) : '—'
}
</script>

<template>
  <YdTablePageLayout title="周期结算">
    <template #stats>
      <YdAlert v-if="error" variant="destructive" title="加载失败" :description="error" />
      <dl v-if="overview" class="office-kv">
        <dt>周期</dt><dd>{{ overview.period }}（{{ overview.status === 'closed' ? '已关账' : '进行中' }}）</dd>
        <dt>上期结余</dt><dd>¥{{ moneyText(overview.balanceCarryIn) }}</dd>
        <dt>本期收入</dt><dd>¥{{ moneyText(overview.incomeTotal) }}</dd>
        <dt>本期支出</dt><dd>¥{{ moneyText(overview.expenseTotal) }}</dd>
        <dt>本期结余</dt><dd><strong>¥{{ moneyText(overview.balanceCarryOut) }}</strong></dd>
        <dt>待审记录</dt><dd>{{ overview.pendingCount }} 条</dd>
      </dl>
    </template>
    <template #actions>
      <div class="flex flex-wrap items-center gap-2">
        <YdInput v-model="period" placeholder="yyyy-MM" class="w-36" />
        <YdButton variant="outline" :loading="loading" @click="load">查询</YdButton>
        <YdButton :loading="closing" @click="requestClose">关账结算</YdButton>
      </div>
    </template>
    <template #table>
      <YdLoadingOverlay :loading="loading">
        <YdResponsiveEnhancedTable
          :columns="columns"
          :data="batches"
          row-key="id"
          table-root-class="rounded-lg overflow-hidden"
          table-class="min-w-[860px]"
          border
          stripe
          empty-text="还没有结算批次"
        >
          <template #cell-status="{ row }">
            <YdTag :variant="row.original.status === 'closed' ? 'default' : 'outline'">
              {{ row.original.status === 'closed' ? '已关账' : '进行中' }}
            </YdTag>
          </template>
          <template #cell-income="{ row }">¥{{ moneyText(row.original.incomeTotal) }}</template>
          <template #cell-expense="{ row }">¥{{ moneyText(row.original.expenseTotal) }}</template>
          <template #cell-balance="{ row }">¥{{ moneyText(row.original.balanceCarryOut) }}</template>
          <template #cell-closedAt="{ row }">{{ closeTime(row.original) }}</template>
        </YdResponsiveEnhancedTable>
      </YdLoadingOverlay>
    </template>
  </YdTablePageLayout>
</template>
