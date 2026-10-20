<script setup lang="ts">
import type { EntityPeekChip, EntityPeekFact, StatusTone, TableColumn } from '@yudream/components'
import type { PlayerSubServerDetail } from '../types'
import type { MinecraftServerPluginModel } from '../composables/useMinecraftServerPlugin'
import { YdCard, YdEntityPeek, YdPagination, YdResponsiveEnhancedTable, YdTablePageLayout, YdTag } from '@yudream/components'
import { computed } from 'vue'
const props = defineProps<{ model: MinecraftServerPluginModel }>()
/**
 * 一行数据。玩家行是各子服的汇总，子服行只承载一个子服的明细；两级共用同一套列，所以子服的累计在线、
 * 累计挂机与进出服时间直接落在与玩家行相同的列里，不需要为子服另造一套布局。
 */
interface PlayerActivityRow {
  rowKey: string
  title: string
  /** 次要说明：玩家行是玩家 ID，子服行是该子服的进出服与本次在线/挂机起点。 */
  notes: string[]
  isSubServer: boolean
  online: boolean
  afk: boolean
  onlineText: string
  afkText: string
  timeText: string
  /** 玩家行的分服摘要；子服行留空。 */
  subServers: PlayerSubServerDetail[]
  children: PlayerActivityRow[]
}
/** 子服行的次要说明：把「完整信息」里除两个累计值以外的部分都摊出来。 */
function subServerNotes(detail: PlayerSubServerDetail) {
  const notes = [`最近进服 ${props.model.formatTime(detail.lastJoinedAt)}`, `最近退服 ${props.model.formatTime(detail.lastQuitAt)}`]
  if (detail.online) {
    notes.push(`本次在线起 ${props.model.formatTime(detail.currentOnlineSince)}`)
  }
  if (detail.afk) {
    notes.push(`本次挂机起 ${props.model.formatTime(detail.currentAfkSince)}`)
  }
  return notes
}
const rows = computed<PlayerActivityRow[]>(() => props.model.playerActivities.map((record) => {
  const details = props.model.subServerBreakdown(record)
  const expanded = props.model.hasSubServerDimension(record)
  return {
    rowKey: `player:${record.playerId}`,
    title: record.playerName || record.playerId,
    notes: [record.playerId],
    isSubServer: false,
    online: record.online,
    afk: record.afk,
    onlineText: props.model.formatDuration(record.totalOnlineMillis),
    afkText: props.model.formatDuration(record.totalAfkMillis),
    timeText: props.model.formatTime(record.updatedAt),
    subServers: details,
    children: expanded
      ? details.map(detail => ({
          rowKey: `player:${record.playerId}:${detail.name}`,
          title: detail.label,
          notes: subServerNotes(detail),
          isSubServer: true,
          online: detail.online,
          afk: detail.afk,
          onlineText: detail.duration,
          afkText: detail.afkDuration,
          timeText: props.model.formatTime(detail.lastQuitAt ?? detail.lastJoinedAt),
          subServers: [],
          children: [],
        }))
      : [],
  }
}))
const subRows = (row: PlayerActivityRow) => row.children
const columns: TableColumn<PlayerActivityRow>[] = [{ id: 'player', header: '玩家 / 子服', width: 260, fixed: 'left' }, { id: 'status', header: '状态', width: 130 }, { id: 'online', header: '累计在线', width: 130 }, { id: 'afk', header: '累计挂机', width: 130 }, { id: 'subServers', header: '分服明细', width: 320 }, { id: 'updatedAt', header: '最近活跃', width: 180 }]

/** 玩家速览卡状态：与状态列同源的语义色 */
function playerPeekStatus(row: PlayerActivityRow): { label: string, tone: StatusTone } {
  return { label: row.online ? '在线' : '离线', tone: row.online ? 'success' : 'neutral' }
}

/** 玩家速览卡事实清单：全部来自本页已有的行数据，无额外请求 */
function playerPeekFacts(row: PlayerActivityRow): EntityPeekFact[] {
  const facts: EntityPeekFact[] = [{ label: '玩家 ID', value: row.notes[0] ?? '', mono: true }]
  if (row.afk) {
    facts.push({ label: '挂机中', value: '是', tone: 'warning' })
  }
  facts.push(
    { label: '累计在线', value: row.onlineText },
    { label: '累计挂机', value: row.afkText },
    { label: '最近活跃', value: row.timeText },
  )
  return facts
}

/** 玩家速览卡的分服芯片：每个子服一条，聚合在线/挂机时长 */
function playerPeekChips(row: PlayerActivityRow): Array<{ label: string, items: EntityPeekChip[] }> {
  if (!row.subServers.length) {
    return []
  }
  return [{
    label: '分服明细',
    items: row.subServers.map(sub => ({ label: `${sub.label} · 在线 ${sub.duration} · 挂机 ${sub.afkDuration}` })),
  }]
}

async function reload() { await props.model.loadPlayerActivities() }
</script>
<template>
  <YdTablePageLayout title="玩家时长统计" :show-search="false">
      <template #table>
        <YdResponsiveEnhancedTable
          row-key="rowKey"
          :get-sub-rows="subRows"
          tree
          table-root-class="max-w-full overflow-x-auto rounded-lg"
          table-class="min-w-[1180px]"
          border
          stripe
          column-visibility
          :columns="columns"
          :data="rows"
        >
          <template #cell-player="{ row }">
            <div class="grid gap-0.5">
              <!-- 玩家行：名称做成实体速览卡，事实来自本行汇总数据；子服行保持纯文本 -->
              <YdEntityPeek
                v-if="!row.original.isSubServer"
                :title="row.original.title"
                :status="playerPeekStatus(row.original)"
                :facts="playerPeekFacts(row.original)"
                :chips="playerPeekChips(row.original)"
                :width="300"
              />
              <strong v-else class="text-sm font-medium">{{ row.original.title }}</strong>
              <span v-for="note in row.original.notes" :key="note" class="break-all text-sm text-muted-foreground">{{ note }}</span>
            </div>
          </template>
          <template #cell-status="{ row }">
            <div class="flex flex-wrap gap-1">
              <YdTag :variant="row.original.online ? 'default' : 'secondary'">{{ row.original.online ? '在线' : '离线' }}</YdTag>
              <YdTag v-if="row.original.afk" variant="secondary">挂机中</YdTag>
            </div>
          </template>
          <template #cell-online="{ row }">{{ row.original.onlineText }}</template>
          <template #cell-afk="{ row }">
            <span :class="row.original.isSubServer ? 'text-secondary-foreground/80' : ''">{{ row.original.afkText }}</span>
          </template>
          <template #cell-subServers="{ row }">
            <div v-if="row.original.subServers.length" class="flex flex-col gap-1">
              <div v-for="sub in row.original.subServers" :key="sub.name" class="flex flex-wrap items-center gap-1 text-sm">
                <YdTag :variant="sub.online ? 'default' : 'secondary'">{{ sub.label }}</YdTag>
                <span>在线 {{ sub.duration }}</span>
                <span class="text-secondary-foreground/60">挂机 {{ sub.afkDuration }}</span>
              </div>
            </div>
            <span v-else class="text-muted-foreground">-</span>
          </template>
          <template #cell-updatedAt="{ row }">{{ row.original.timeText }}</template>
          <template #card="{ row, depth }">
            <YdCard class="w-full" :class="depth > 0 ? 'border-dashed' : ''">
              <div class="flex flex-col gap-3">
                <div class="flex items-center justify-between gap-2">
                  <span class="min-w-0 break-words text-base font-semibold">{{ row.title }}</span>
                  <div class="flex flex-wrap gap-1">
                    <YdTag :variant="row.online ? 'default' : 'secondary'">{{ row.online ? '在线' : '离线' }}</YdTag>
                    <YdTag v-if="row.afk" variant="secondary">挂机中</YdTag>
                  </div>
                </div>
                <div class="flex flex-col gap-1 text-sm">
                  <div v-for="note in row.notes" :key="note" class="break-all text-muted-foreground">{{ note }}</div>
                  <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">累计在线</span><span>{{ row.onlineText }}</span></div>
                  <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">累计挂机</span><span>{{ row.afkText }}</span></div>
                  <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">最近活跃</span><span>{{ row.timeText }}</span></div>
                  <div v-if="row.subServers.length" class="flex flex-col gap-1">
                    <span class="text-secondary-foreground/60">分服明细</span>
                    <div v-for="sub in row.subServers" :key="sub.name" class="flex flex-wrap items-center gap-1">
                      <YdTag :variant="sub.online ? 'default' : 'secondary'">{{ sub.label }}</YdTag>
                      <span>在线 {{ sub.duration }}</span>
                      <span class="text-secondary-foreground/60">挂机 {{ sub.afkDuration }}</span>
                    </div>
                  </div>
                </div>
              </div>
            </YdCard>
          </template>
        </YdResponsiveEnhancedTable>
      </template>
      <template #footer>
        <YdPagination
          v-model:page="model.playerActivitiesPager.page"
          v-model:size="model.playerActivitiesPager.size"
          :total="model.playerActivitiesPager.total"
          @page-change="reload"
          @size-change="reload"
        />
      </template>
  </YdTablePageLayout>
</template>
