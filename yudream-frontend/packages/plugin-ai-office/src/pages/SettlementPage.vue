<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { SettlementBatch, SettlementOverview } from '../types'
import { YdButton, YdIcon, YdInput, YdPageHeader, YdPageMain, YdTag, useYdModal, useYdToast } from '@yudream/components'
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
const closing = ref(false)

onMounted(load)

async function load() {
  loading.value = true
  try {
    const [overviewData, batchData] = await Promise.all([
      api.settlementOverview(period.value),
      api.settlements(),
    ])
    overview.value = overviewData
    batches.value = batchData
  }
  catch (cause) {
    toast.error(errorMessage(cause, '结算数据加载失败'))
  }
  finally {
    loading.value = false
  }
}

function requestClose() {
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
</script>

<template>
  <section class="office-page">
    <YdPageHeader title="周期结算">
      <div class="flex flex-wrap items-center gap-2">
        <YdInput v-model="period" placeholder="yyyy-MM" class="w-36" />
        <YdButton variant="outline" :loading="loading" @click="load">
          <YdIcon name="i-ri:search-line" />查询
        </YdButton>
        <YdButton :loading="closing" @click="requestClose">
          <YdIcon name="i-ri:lock-line" />关账结算
        </YdButton>
      </div>
    </YdPageHeader>
    <YdPageMain>
      <div v-if="overview" class="office-kv max-w-lg">
        <dt>周期</dt><dd>{{ overview.period }}（{{ overview.status === 'closed' ? '已关账' : '进行中' }}）</dd>
        <dt>上期结余</dt><dd>¥{{ moneyText(overview.balanceCarryIn) }}</dd>
        <dt>本期收入</dt><dd>¥{{ moneyText(overview.incomeTotal) }}</dd>
        <dt>本期支出</dt><dd>¥{{ moneyText(overview.expenseTotal) }}</dd>
        <dt>本期结余</dt><dd><strong>¥{{ moneyText(overview.balanceCarryOut) }}</strong></dd>
        <dt>待审记录</dt><dd>{{ overview.pendingCount }} 条</dd>
      </div>
      <p v-else class="text-sm text-muted-foreground">输入周期后查询</p>

      <h4 class="mt-6 mb-2 font-medium">历史批次</h4>
      <table v-if="batches.length" class="office-preview-table">
        <thead>
          <tr><th>周期</th><th>状态</th><th>收入</th><th>支出</th><th>结转结余</th><th>关账时间</th></tr>
        </thead>
        <tbody>
          <tr v-for="batch in batches" :key="batch.id">
            <td>{{ batch.period }}</td>
            <td><YdTag :variant="batch.status === 'closed' ? 'default' : 'outline'">{{ batch.status === 'closed' ? '已关账' : '进行中' }}</YdTag></td>
            <td>¥{{ moneyText(batch.incomeTotal) }}</td>
            <td>¥{{ moneyText(batch.expenseTotal) }}</td>
            <td>¥{{ moneyText(batch.balanceCarryOut) }}</td>
            <td>{{ batch.closedAt ? new Date(batch.closedAt).toLocaleString('zh-CN', { hour12: false }) : '—' }}</td>
          </tr>
        </tbody>
      </table>
      <p v-else class="text-sm text-muted-foreground">还没有结算批次</p>
    </YdPageMain>
  </section>
</template>
