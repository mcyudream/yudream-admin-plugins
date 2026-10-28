<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { DeploymentPreview } from '../../../api/deployment-api'
import { YdResponsiveTable, YdTag } from '@yudream/components'
import { formatSize } from '../../../composables/utils'
defineProps<{ preview: DeploymentPreview }>()
const columns: TableColumn<DeploymentPreview['files'][number]>[] = [
  { accessorKey: 'path', header: '目标文件', minWidth: 240 },
  { accessorKey: 'size', header: '大小', width: 110 },
  { accessorKey: 'action', header: '操作', width: 90 },
]
</script>
<template>
  <div class="space-y-3">
    <p>共 {{ preview.fileCount }} 个文件 · {{ formatSize(preview.totalBytes) }} · 新增 {{ preview.addCount }} · 覆盖 {{ preview.replaceCount }}</p>
    <YdResponsiveTable :columns="columns" :data="preview.files" row-key="path">
      <template #cell-size="{ row }">{{ formatSize(row.original.size) }}</template>
      <template #cell-action="{ row }"><YdTag :variant="row.original.action === 'replace' ? 'destructive' : 'secondary'">{{ row.original.action === 'replace' ? '覆盖' : '新增' }}</YdTag></template>
      <template #card="{ row }"><div class="space-y-1 p-3"><p class="break-all">{{ row.path }}</p><span>{{ formatSize(row.size) }} · {{ row.action === 'replace' ? '覆盖' : '新增' }}</span></div></template>
    </YdResponsiveTable>
    <p v-if="preview.fileCount > preview.files.length" class="text-xs text-muted-foreground">预览列出前 {{ preview.files.length }} 个文件；操作总数以摘要为准。安装包完整目标清单见 mcpanel-package.json。</p>
  </div>
</template>
