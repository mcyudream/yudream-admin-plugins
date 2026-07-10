<template>
  <section class="mc-page">
    <section class="mc-hero">
      <div>
        <h2>{{ server?.name || '服务器详情' }}</h2>
        <p>{{ server?.currentSeason?.name || '当前周目未设置' }}</p>
      </div>
      <div class="mc-actions">
        <a class="mc-button" href="/platform/plugins/minecraft-server">
          <FaIcon name="i-ri:list-check" />
          列表
        </a>
      </div>
    </section>

    <div v-if="server" class="mc-detail-layout">
      <McPanel title="在线状态" eyebrow="Status">
        <div class="mc-status-board">
          <StatusPill :status="server.status?.status" />
          <strong>{{ server.status?.onlinePlayers ?? 0 }} / {{ server.status?.maxPlayers ?? 0 }}</strong>
          <span>最后检查：{{ model.formatTime(server.status?.checkedAt) }}</span>
        </div>
        <div class="mc-line-list">
          <div v-for="endpoint in server.endpoints" :key="endpoint.id || endpoint.host" class="mc-line detail">
            <div class="mc-line-main">
              <strong>{{ endpoint.name }}</strong>
              <code>{{ model.endpointAddress(endpoint) }}</code>
            </div>
            <StatusPill :status="endpointStatus(endpoint.id)?.status" />
            <span>{{ endpoint.edition }}</span>
            <span>{{ endpointStatus(endpoint.id)?.ping ?? '-' }} ms</span>
          </div>
        </div>
      </McPanel>

      <McPanel title="在线人数趋势" eyebrow="Trend">
        <OnlineTrendChart :items="model.statusHistory" :max-players="server.status?.maxPlayers" :format-time="model.formatTime" />
      </McPanel>

      <McPanel title="服务器说明" eyebrow="Markdown">
        <MarkdownPreview :content="server.descriptionMarkdown" />
      </McPanel>

      <McPanel v-if="model.walletEnabled" title="我的操作记录" eyebrow="Records">
        <FaTable row-key="id" table-root-class="rounded-lg overflow-hidden" border stripe :columns="recordColumns" :data="model.records">
          <template #cell-createdAt="{ row }">{{ model.formatTime(row.original.createdAt) }}</template>
          <template #cell-source="{ row }">{{ row.original.source || row.original.type }}</template>
          <template #cell-amount="{ row }">{{ model.formatAmount(row.original.amount) }}</template>
          <template #cell-remark="{ row }">{{ row.original.remark || row.original.businessNo || '-' }}</template>
        </FaTable>
        <FaPagination v-model:page="model.recordsPager.page" v-model:size="model.recordsPager.size" :total="model.recordsPager.total" class="mt-3" @page-change="reloadRecords" @size-change="reloadRecords" />
      </McPanel>
    </div>
    <McPanel v-else title="未找到服务器" eyebrow="Empty">
      <div class="mc-empty">请从服务器列表重新进入。</div>
    </McPanel>
  </section>
</template>

<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { EconomyRecord } from '../types'
import { computed } from 'vue'
import { FaPagination, FaTable } from '@yudream/components'
import MarkdownPreview from '../components/MarkdownPreview.vue'
import McPanel from '../components/McPanel.vue'
import OnlineTrendChart from '../components/OnlineTrendChart.vue'
import StatusPill from '../components/StatusPill.vue'
import type { MinecraftServerPluginModel } from '../composables/useMinecraftServerPlugin'

const props = defineProps<{
  model: MinecraftServerPluginModel
}>()

const server = computed(() => props.model.selectedServer)
async function reloadRecords() { await props.model.loadRecords() }
const recordColumns: TableColumn<EconomyRecord>[] = [
  { id: 'createdAt', header: '时间', width: 180 },
  { id: 'source', header: '类型', width: 140 },
  { accessorKey: 'assetCode', header: '币种', width: 100 },
  { id: 'amount', header: '金额', width: 120, align: 'right' },
  { id: 'remark', header: '备注', minWidth: 240 },
]

function endpointStatus(endpointId?: string) {
  return server.value?.status?.endpoints.find(item => item.endpointId === endpointId)
}
</script>
