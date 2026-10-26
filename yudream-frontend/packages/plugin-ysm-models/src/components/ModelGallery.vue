<script setup lang="ts">
import { YdAction, YdButton, YdCard, YdDropdown, YdEmpty, YdIcon, YdSkeleton, YdTag, type ActionMenuItem } from '@yudream/components'
import ModelCover from './ModelCover.vue'
import type { ModelRow } from '../types'
defineProps<{ rows: ModelRow[]; loading: boolean; failed: boolean; cover: (row: ModelRow) => Promise<string>; actions?: ActionMenuItem<ModelRow>[]; emptyTitle: string; emptyDescription: string }>()
defineEmits<{ preview: [row: ModelRow] }>()
function menu(actions: ActionMenuItem<ModelRow>[], row: ModelRow) {
  return [actions.filter(a => !a.visible || a.visible(row)).map(a => ({
    label: typeof a.label === 'function' ? a.label(row) : a.label,
    disabled: typeof a.disabled === 'function' ? a.disabled(row) : a.disabled,
    variant: a.danger ? 'destructive' as const : 'default' as const,
    icon: a.icon, handle: () => a.onClick(row),
  }))]
}
</script>
<template>
  <div v-if="loading && !rows.length" class="ysm-gallery" aria-label="正在加载模型"><YdSkeleton v-for="n in 4" :key="n" variant="rect" height="310px" /></div>
  <div v-else-if="rows.length" class="ysm-gallery" :aria-busy="loading">
    <YdCard v-for="row in rows" :key="row.id" class="ysm-model-card gap-0 py-0" content-class="p-0">
      <YdAction class="ysm-card-open" :aria-label="`预览 ${row.name}`" @click="$emit('preview', row)">
        <ModelCover :name="row.name" :revision="row.hash" :load="() => cover(row)" passive />
        <span class="ysm-card-title"><strong>{{ row.name }}</strong><span>查看模型与贴图</span></span>
      </YdAction>
      <div class="ysm-card-footer">
        <YdTag :variant="!row.enabled ? 'outline' : row.serverEnabled ? 'secondary' : 'outline'">{{ !row.enabled ? '已停用' : row.serverEnabled ? '服务器共享' : '仅本人' }}</YdTag>
        <YdDropdown v-if="actions?.length" :items="menu(actions, row)" align="end"><YdButton size="icon-sm" variant="ghost" :aria-label="`${row.name} 的更多操作`"><YdIcon name="i-ri:more-2-fill" /></YdButton></YdDropdown>
        <YdButton v-else size="sm" variant="outline" @click="$emit('preview', row)">预览 / 装备</YdButton>
      </div>
    </YdCard>
  </div>
  <YdEmpty v-else-if="!failed" :title="emptyTitle" :description="emptyDescription" illustration="empty" />
</template>
