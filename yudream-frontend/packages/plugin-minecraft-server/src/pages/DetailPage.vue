<template>
  <section class="mc-page">
    <section class="mc-hero">
      <div><div class="mc-title-row"><h2>{{ server?.name || '服务器详情' }}</h2><span v-if="server && !server.enabled" class="mc-closed-badge">已关闭</span></div><p>{{ server?.currentSeason?.name || '当前周目未设置' }}</p></div>
      <div class="mc-actions"><FaButton as="a" variant="outline" :href="model.closedSurface ? '/platform/plugins/minecraft-server?closed=true' : '/platform/plugins/minecraft-server'"><FaIcon name="i-ri:list-check" />列表</FaButton></div>
    </section>
    <div v-if="server" class="mc-detail-layout">
      <FaCard v-if="!server.enabled" title="服务器状态" description="Closed"><div class="mc-closed-notice">该服务器已关闭，仅保留历史信息供浏览。</div></FaCard>
      <FaCard title="在线状态" description="Status"><div class="mc-status-board"><FaTag :variant="server.status?.status === 'ONLINE' ? 'default' : 'secondary'">{{ model.statusText(server.status?.status) }}</FaTag><strong>{{ server.status?.onlinePlayers ?? 0 }} / {{ server.status?.maxPlayers ?? 0 }}</strong><span>最后检查：{{ model.formatTime(server.status?.checkedAt) }}</span></div><div class="mc-line-list"><div v-for="endpoint in server.endpoints" :key="endpoint.id || endpoint.host" class="mc-line detail"><div class="mc-line-main"><strong>{{ endpoint.name }}</strong><code>{{ model.endpointAddress(endpoint) }}</code></div><FaTag :variant="endpointStatus(endpoint.id)?.status === 'ONLINE' ? 'default' : 'secondary'">{{ model.statusText(endpointStatus(endpoint.id)?.status) }}</FaTag><span>{{ endpoint.edition }}</span><span>{{ endpointStatus(endpoint.id)?.ping ?? '-' }} ms</span></div></div></FaCard>
      <FaCard v-if="server.map?.publicAccess" title="公开地图" description="Map"><div class="mc-map-action"><span>{{ server.map.originalName || '地图 ZIP' }}</span><FaButton :loading="model.mapOperating" @click="model.downloadMap()"><FaIcon name="i-ri:download-line" />下载地图 ZIP</FaButton></div></FaCard>
      <FaCard title="在线人数趋势" description="Trend"><OnlineTrendChart :items="model.statusHistory" :max-players="server.status?.maxPlayers" :format-time="model.formatTime" /></FaCard>
      <FaCard title="服务器说明" description="Markdown"><MarkdownPreview :content="server.descriptionMarkdown" /></FaCard>
      <FaCard v-if="model.walletEnabled" title="我的操作记录" description="Records"><FaResponsiveTable row-key="id" table-root-class="max-w-full overflow-x-auto rounded-lg" border stripe :columns="recordColumns" :data="model.records"><template #cell-createdAt="{ row }">{{ model.formatTime(row.original.createdAt) }}</template><template #cell-source="{ row }">{{ row.original.source || row.original.type }}</template><template #cell-amount="{ row }">{{ model.formatAmount(row.original.amount) }}</template><template #cell-remark="{ row }">{{ row.original.remark || row.original.businessNo || '-' }}</template><template #card="{ row }"><FaCard class="w-full"><div class="flex flex-col gap-3"><div class="flex items-center justify-between gap-2"><span class="min-w-0 break-words text-base font-semibold">{{ row.source || row.type }}</span></div><div class="flex flex-col gap-1 text-sm"><div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">币种</span><span class="break-all">{{ row.assetCode }}</span></div><div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">金额</span><span class="break-all">{{ model.formatAmount(row.amount) }}</span></div><div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">时间</span><span>{{ model.formatTime(row.createdAt) }}</span></div><div v-if="row.remark || row.businessNo" class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">备注</span><span class="break-all">{{ row.remark || row.businessNo }}</span></div></div></div></FaCard></template></FaResponsiveTable><FaPagination v-model:page="model.recordsPager.page" v-model:size="model.recordsPager.size" :total="model.recordsPager.total" class="mt-3" @page-change="reloadRecords" @size-change="reloadRecords" /></FaCard>
    </div>
    <FaCard v-else title="未找到服务器" description="Empty"><div class="mc-empty">请从服务器列表重新进入。</div></FaCard>
  </section>
</template>
<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { EconomyRecord } from '../types'
import { computed } from 'vue'
import { FaButton, FaCard, FaIcon, FaPagination, FaResponsiveTable, FaTag } from '@yudream/components'
import MarkdownPreview from '../components/MarkdownPreview.vue'
import OnlineTrendChart from '../components/OnlineTrendChart.vue'
import type { MinecraftServerPluginModel } from '../composables/useMinecraftServerPlugin'
const props = defineProps<{ model: MinecraftServerPluginModel }>()
const server = computed(() => props.model.selectedServer)
async function reloadRecords() { await props.model.loadRecords() }
const recordColumns: TableColumn<EconomyRecord>[] = [{ id: 'createdAt', header: '时间', width: 180 }, { id: 'source', header: '类型', width: 140 }, { accessorKey: 'assetCode', header: '币种', width: 100 }, { id: 'amount', header: '金额', width: 120, align: 'right' }, { id: 'remark', header: '备注', minWidth: 240 }]
function endpointStatus(endpointId?: string) { return server.value?.status?.endpoints.find(item => item.endpointId === endpointId) }
</script>
