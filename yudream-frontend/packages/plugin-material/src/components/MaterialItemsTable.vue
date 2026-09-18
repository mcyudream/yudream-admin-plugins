<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { MaterialItemView } from '../types'
import { FaButton, FaIcon, FaResponsiveTable, FaTag } from '@yudream/components'
import { formatSize, formatTime, TYPE_ICONS } from '../types'

/**
 * 子物料表格：详情页与管理工作台（抽屉）共用。
 * 版本管理入口集中在行内「版本」动作，父组件负责打开该子物料的版本链。
 * 组合物料没有主文件，行内「设为主文件」把某个子物料指定为父物料的预览主文件（父物料在线预览/下载跟随它）。
 */
const props = defineProps<{
  items: MaterialItemView[]
  loading?: boolean
  /** 当前在版本区展开的子物料 id，用于高亮 */
  activeId?: string
  /** 只读：他人物料且无管理权限时隐藏全部写操作 */
  readonly?: boolean
  /** 父物料当前的预览主文件（子物料 id）；空串表示未指定 */
  previewItemId?: string
}>()
const emit = defineEmits<{
  versions: [item: MaterialItemView]
  'new-version': [item: MaterialItemView]
  rename: [item: MaterialItemView]
  remove: [item: MaterialItemView]
  download: [item: MaterialItemView]
  /** 把该子物料设为父物料的预览主文件 */
  'set-preview': [item: MaterialItemView]
  /** 取消预览主文件指定，预览回退第一个子物料 */
  'clear-preview': []
}>()

const columns: TableColumn<MaterialItemView>[] = [
  { accessorKey: 'name', header: '子物料', minWidth: 180 },
  { id: 'type', header: '类型', width: 96 },
  { id: 'fileName', header: '当前文件', minWidth: 160 },
  { id: 'currentVersion', header: '版本', width: 76 },
  { id: 'size', header: '大小', width: 96 },
  { accessorKey: 'updatedAt', header: '更新时间', width: 168 },
  { id: 'operation', header: '操作', width: 380 },
]

function typeIcon(item: MaterialItemView) {
  return TYPE_ICONS[item.type] || TYPE_ICONS.OTHER
}

function isPreviewItem(item: MaterialItemView) {
  return !!props.previewItemId && item.id === props.previewItemId
}
</script>

<template>
  <FaResponsiveTable
    v-loading="props.loading"
    :columns="columns"
    :data="props.items"
    row-key="id"
    table-root-class="max-w-full overflow-x-auto rounded-lg"
    table-class="min-w-[960px]"
    border stripe
    empty-text="暂无子物料"
  >
    <template #cell-name="{ row }">
      <div class="flex items-center gap-2">
        <FaIcon :name="typeIcon(row.original)" class="shrink-0 text-secondary-foreground/70" />
        <strong class="truncate">{{ row.original.name }}</strong>
        <FaTag v-if="isPreviewItem(row.original)" variant="secondary" class="shrink-0">预览主文件</FaTag>
        <FaTag v-if="row.original.id === props.activeId" variant="outline" class="shrink-0">版本区</FaTag>
      </div>
    </template>
    <template #cell-type="{ row }">
      <FaTag variant="secondary">{{ row.original.typeLabel }} · .{{ row.original.ext || '?' }}</FaTag>
    </template>
    <template #cell-fileName="{ row }">{{ row.original.name }}</template>
    <template #cell-currentVersion="{ row }">v{{ row.original.currentVersion }}</template>
    <template #cell-size="{ row }">{{ formatSize(row.original.size) }}</template>
    <template #cell-updatedAt="{ row }">{{ formatTime(row.original.updatedAt) }}</template>
    <template #cell-operation="{ row }">
      <div class="flex-center flex-wrap gap-2">
        <FaButton size="sm" variant="outline" @click="emit('versions', row.original)"><FaIcon name="i-ri:git-branch-line" />版本</FaButton>
        <FaButton v-if="!props.readonly && !isPreviewItem(row.original)" size="sm" variant="ghost" @click="emit('set-preview', row.original)"><FaIcon name="i-ri:star-line" />设为主文件</FaButton>
        <FaButton v-else-if="!props.readonly" size="sm" variant="ghost" @click="emit('clear-preview')"><FaIcon name="i-ri:star-fill" />取消主文件</FaButton>
        <FaButton v-if="!props.readonly" size="sm" variant="outline" @click="emit('new-version', row.original)"><FaIcon name="i-ri:upload-cloud-2-line" />新版本</FaButton>
        <FaButton size="sm" variant="outline" @click="emit('download', row.original)"><FaIcon name="i-ri:download-line" />下载</FaButton>
        <FaButton v-if="!props.readonly" size="sm" variant="outline" @click="emit('rename', row.original)"><FaIcon name="i-ri:edit-line" />重命名</FaButton>
        <FaButton v-if="!props.readonly" size="sm" variant="destructive" @click="emit('remove', row.original)"><FaIcon name="i-ri:delete-bin-line" />删除</FaButton>
      </div>
    </template>
    <template #card="{ row }">
      <div class="flex flex-col gap-2 text-sm">
        <div class="flex items-center justify-between gap-2">
          <strong class="truncate">{{ row.name }}</strong>
          <FaTag variant="secondary">{{ row.typeLabel }}</FaTag>
        </div>
        <div class="text-secondary-foreground/80">v{{ row.currentVersion }} · {{ formatSize(row.size) }} · {{ formatTime(row.updatedAt) }}</div>
        <FaTag v-if="isPreviewItem(row)" variant="secondary">预览主文件</FaTag>
        <div class="flex flex-wrap gap-2">
          <FaButton size="sm" variant="outline" @click="emit('versions', row)">版本</FaButton>
          <FaButton v-if="!props.readonly && !isPreviewItem(row)" size="sm" variant="ghost" @click="emit('set-preview', row)">设为主文件</FaButton>
          <FaButton v-else-if="!props.readonly" size="sm" variant="ghost" @click="emit('clear-preview')">取消主文件</FaButton>
          <FaButton v-if="!props.readonly" size="sm" variant="outline" @click="emit('new-version', row)">新版本</FaButton>
          <FaButton size="sm" variant="outline" @click="emit('download', row)">下载</FaButton>
          <FaButton v-if="!props.readonly" size="sm" variant="outline" @click="emit('rename', row)">重命名</FaButton>
          <FaButton v-if="!props.readonly" size="sm" variant="destructive" @click="emit('remove', row)">删除</FaButton>
        </div>
      </div>
    </template>
  </FaResponsiveTable>
</template>
