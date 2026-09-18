<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { VersionView } from '../types'
import { FaButton, FaResponsiveTable, FaTag } from '@yudream/components'
import { formatSize, formatTime } from '../types'

/**
 * 版本历史表格：父物料主文件版本与子物料版本结构一致，共用同一套渲染。
 * 父组件负责标题（当前目标的名称）与「上传新版本」入口。
 * canWrite=false（他人物料且无管理权限）时隐藏回滚与删除。
 */
const props = defineProps<{
  versions: VersionView[]
  loading?: boolean
  /** 是否可回滚/删除版本（删除版本仅属主，管理端亦然） */
  canWrite?: boolean
  /** 是否可上传新版本（属主或有管理权限） */
  canUpload?: boolean
}>()
const emit = defineEmits<{
  preview: [version: VersionView]
  download: [version: VersionView]
  restore: [version: VersionView]
  remove: [version: VersionView]
  upload: []
}>()

const columns: TableColumn<VersionView>[] = [
  { id: 'version', header: '版本', width: 96 },
  { accessorKey: 'originalName', header: '文件名', minWidth: 180 },
  { id: 'size', header: '大小', width: 100 },
  { accessorKey: 'note', header: '备注', minWidth: 140 },
  { accessorKey: 'uploaderName', header: '上传者', width: 110 },
  { accessorKey: 'createdAt', header: '上传时间', width: 170 },
  { id: 'operation', header: '操作', width: 250 },
]
</script>

<template>
  <div class="mb-3 flex flex-wrap items-center justify-between gap-2">
    <slot name="title" />
    <div class="flex items-center gap-2">
      <span class="text-xs text-secondary-foreground/70">共 {{ props.versions.length }} 个版本</span>
      <FaButton v-if="props.canUpload" size="sm" variant="outline" @click="emit('upload')">上传新版本</FaButton>
    </div>
  </div>
  <FaResponsiveTable
    v-loading="props.loading"
    :columns="columns"
    :data="props.versions"
    row-key="version"
    table-root-class="max-w-full overflow-x-auto rounded-lg"
    table-class="min-w-[980px]"
    border stripe
    empty-text="暂无版本"
  >
    <template #cell-version="{ row }">
      <strong>v{{ row.original.version }}</strong>
      <FaTag v-if="row.original.current" class="ml-2" variant="secondary">当前</FaTag>
    </template>
    <template #cell-originalName="{ row }">{{ row.original.originalName || '-' }}</template>
    <template #cell-size="{ row }">{{ formatSize(row.original.size) }}</template>
    <template #cell-note="{ row }">{{ row.original.note || '-' }}</template>
    <template #cell-uploaderName="{ row }">{{ row.original.uploaderName || row.original.uploaderId }}</template>
    <template #cell-createdAt="{ row }">{{ formatTime(row.original.createdAt) }}</template>
    <template #cell-operation="{ row }">
      <div class="flex-center gap-2">
        <FaButton size="sm" variant="outline" @click="emit('preview', row.original)">预览</FaButton>
        <FaButton size="sm" variant="outline" @click="emit('download', row.original)">下载</FaButton>
        <FaButton v-if="props.canWrite && !row.original.current" size="sm" variant="outline" @click="emit('restore', row.original)">回滚到此</FaButton>
        <FaButton v-if="props.canWrite && !row.original.current" size="sm" variant="destructive" @click="emit('remove', row.original)">删除</FaButton>
      </div>
    </template>
    <template #card="{ row }">
      <div class="flex flex-col gap-2 text-sm">
        <div class="flex items-center justify-between gap-2">
          <strong>v{{ row.version }}</strong>
          <FaTag v-if="row.current" variant="secondary">当前</FaTag>
        </div>
        <div class="text-secondary-foreground/80">{{ row.originalName || '-' }} · {{ formatSize(row.size) }}</div>
        <div v-if="row.note" class="text-secondary-foreground/80">备注：{{ row.note }}</div>
        <div class="text-secondary-foreground/60">{{ row.uploaderName || row.uploaderId }} · {{ formatTime(row.createdAt) }}</div>
        <div class="flex flex-wrap gap-2">
          <FaButton size="sm" variant="outline" @click="emit('preview', row)">预览</FaButton>
          <FaButton size="sm" variant="outline" @click="emit('download', row)">下载</FaButton>
          <FaButton v-if="props.canWrite && !row.current" size="sm" variant="outline" @click="emit('restore', row)">回滚到此</FaButton>
          <FaButton v-if="props.canWrite && !row.current" size="sm" variant="destructive" @click="emit('remove', row)">删除</FaButton>
        </div>
      </div>
    </template>
  </FaResponsiveTable>
</template>
