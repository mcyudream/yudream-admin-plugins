<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { InteropPackEntry } from '../types'
import { YdAlert, YdButton, YdLoadingOverlay, YdResponsiveEnhancedTable, YdTablePageLayout, YdTag, useYdToast } from '@yudream/components'
import { onMounted, reactive } from 'vue'
import { createMcInteropApi } from '../api/mc-interop-api'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = createMcInteropApi(props.sdk)
const toast = useYdToast()

const state = reactive({
  loading: false,
  error: '',
  packs: [] as InteropPackEntry[],
  updatedAt: '' as string | number | '',
  copied: '',
})

async function load() {
  state.loading = true
  state.error = ''
  try {
    const body = await api.mergedEntries()
    state.packs = body.packs ?? []
    state.updatedAt = body.updated_at ?? ''
  } catch (cause) {
    state.error = cause instanceof Error ? cause.message : String(cause)
  } finally {
    state.loading = false
  }
}

function sourceLabel(pack: InteropPackEntry): string {
  return pack.source ? `友站 ${pack.source.site_id}` : '本站'
}

function serverAddress(pack: InteropPackEntry): string {
  const server = pack.server
  if (!server) return '-'
  return server.port ? `${server.address}:${server.port}` : server.address
}

async function copyServer(pack: InteropPackEntry) {
  const address = serverAddress(pack)
  if (address === '-') return
  try {
    await navigator.clipboard.writeText(address)
    state.copied = pack.id
    setTimeout(() => {
      if (state.copied === pack.id) state.copied = ''
    }, 1500)
  } catch {
    toast.error('剪贴板不可用')
  }
}

onMounted(load)

const columns: TableColumn<InteropPackEntry>[] = [
  { accessorKey: 'name', header: '名称', minWidth: 150 },
  { accessorKey: 'id', header: 'ID', minWidth: 140 },
  { id: 'source', header: '来源', width: 110 },
  { id: 'version', header: '版本', width: 200 },
  { id: 'artifact', header: '整合包', width: 100 },
  { accessorKey: 'serverAddress', header: '服务器', minWidth: 180 },
  { id: 'copy', header: '', width: 80 },
]
</script>

<template>
  <YdTablePageLayout title="跨站目录" :show-search="false" stats-title="目录说明">
    <template #stats>
      <p class="text-sm text-muted-foreground">
        本站已发布条目 + peers 声明友站的互采条目（5 分钟缓存；id 撞名时友站条目以
        siteId.id 命名空间展示，artifact 仍指向友站源站）。
      </p>
    </template>
    <template #actions>
      <YdButton :loading="state.loading" @click="load">
        刷新
      </YdButton>
    </template>
    <template #table>
      <YdAlert v-if="state.error" class="mb-3" variant="destructive" title="加载失败" :description="state.error" />
      <YdLoadingOverlay :loading="state.loading">
        <YdResponsiveEnhancedTable
          :columns="columns"
          :data="state.packs"
          row-key="id"
          table-root-class="rounded-lg overflow-hidden"
          table-class="min-w-[820px]"
          border
          stripe
          empty-text="目录为空：请先在清单目录发布条目"
        >
          <template #cell-name="{ row }">
            <span class="font-semibold text-contrast">{{ row.original.name || row.original.id }}</span>
            <div v-if="row.original.name" class="text-xs text-muted-foreground">{{ row.original.id }}</div>
          </template>
          <template #cell-source="{ row }">
            <YdTag :variant="row.original.source ? 'secondary' : 'default'">
              {{ sourceLabel(row.original) }}
            </YdTag>
          </template>
          <template #cell-version="{ row }">
            MC {{ row.original.game_version || '?' }} · {{ row.original.loader || '?' }}{{ row.original.loader_version ? ` ${row.original.loader_version}` : '' }}
          </template>
          <template #cell-artifact="{ row }">
            <YdTag v-if="row.original.artifact" variant="secondary">
              {{ row.original.artifact.format }} · r{{ row.original.revision ?? '?' }}
            </YdTag>
            <span v-else class="text-muted-foreground">纯服务器</span>
          </template>
          <template #cell-serverAddress="{ row }">
            <span class="text-sm">{{ serverAddress(row.original) }}</span>
          </template>
          <template #cell-copy="{ row }">
            <YdButton variant="outline" size="sm" @click="copyServer(row.original)">
              {{ state.copied === row.original.id ? '已复制' : '复制' }}
            </YdButton>
          </template>
        </YdResponsiveEnhancedTable>
      </YdLoadingOverlay>
    </template>
  </YdTablePageLayout>
</template>
