<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import {
  FaButton,
  FaCard,
  FaIcon,
  FaPageHeader,
  FaPageMain,
  FaProgress,
  FaTable,
  FaTag,
} from '@yudream/components'
import { useRouter } from 'vue-router'
import {
  dimensionLabel,
  formatTime,
  MAP_STATE_META,
  RENDER_PHASE_LABEL,
  TASK_STATE_META,
  useWorldMapMapDetail,
} from '../../composables/useWorldMapAdmin'
import type { RenderTask } from '../../types'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  route?: RouteLocationNormalizedLoaded
}>()

const router = useRouter()
const model = useWorldMapMapDetail(props.sdk, props.route)

const columns: TableColumn<RenderTask>[] = [
  { id: 'id', header: '任务 ID', width: 200, fixed: 'left' },
  { id: 'state', header: '状态', width: 110 },
  { id: 'phase', header: '阶段', width: 140 },
  { id: 'progress', header: '进度', width: 260 },
  { id: 'createdAt', header: '创建时间', width: 160 },
  { id: 'finishedAt', header: '完成时间', width: 160 },
]

function backToList() {
  void router.push('/world-map/admin/maps')
}
</script>

<template>
  <FaPageHeader :title="model.map.value ? `地图详情：${model.map.value.name}` : '地图详情'" class="mb-0">
    <div class="flex items-center gap-2">
      <FaButton size="sm" variant="outline" @click="backToList">
        <FaIcon name="i-mdi:arrow-left" />
        返回列表
      </FaButton>
      <FaButton
        v-if="model.map.value"
        size="sm"
        :disabled="model.map.value.state === 'RENDERING'"
        @click="model.triggerRender"
      >
        触发渲染
      </FaButton>
    </div>
  </FaPageHeader>

  <FaPageMain>
    <div v-if="model.error.value" class="py-12 text-center text-muted-foreground">
      {{ model.error.value }}
    </div>
    <div v-else-if="model.loading.value && !model.map.value" class="py-12 text-center text-muted-foreground">
      加载中…
    </div>

    <template v-else-if="model.map.value">
      <FaCard class="mb-4 p-4">
        <div class="grid grid-cols-2 gap-x-8 gap-y-2 text-sm md:grid-cols-4">
          <div>
            <span class="text-muted-foreground">地图 ID：</span>{{ model.map.value.id }}
          </div>
          <div>
            <span class="text-muted-foreground">维度：</span>{{ dimensionLabel(model.map.value.dimension) }}
          </div>
          <div>
            <span class="text-muted-foreground">状态：</span>
            <FaTag :variant="MAP_STATE_META[model.map.value.state].variant">
              {{ MAP_STATE_META[model.map.value.state].label }}
            </FaTag>
          </div>
          <div>
            <span class="text-muted-foreground">Tile：</span>
            高精 {{ model.map.value.hiresTiles }} / 低清 {{ model.map.value.lowresTiles }}
          </div>
          <div>
            <span class="text-muted-foreground">创建时间：</span>{{ formatTime(model.map.value.createdAt) }}
          </div>
          <div>
            <span class="text-muted-foreground">渲染时间：</span>{{ formatTime(model.map.value.renderedAt) }}
          </div>
          <div v-if="model.map.value.message" class="col-span-2">
            <span class="text-muted-foreground">备注：</span>{{ model.map.value.message }}
          </div>
        </div>
      </FaCard>

      <h3 class="mb-2 text-base font-semibold">
        渲染任务历史
      </h3>
      <FaTable
        row-key="id"
        table-root-class="max-w-full overflow-x-auto rounded-lg"
        table-class="min-w-[860px]"
        border
        stripe
        :columns="columns"
        :data="model.tasks.value"
      >
        <template #cell-id="{ row }">
          <span class="font-mono text-xs">{{ row.original.id }}</span>
        </template>
        <template #cell-state="{ row }">
          <FaTag :variant="TASK_STATE_META[row.original.state].variant">
            {{ TASK_STATE_META[row.original.state].label }}
          </FaTag>
        </template>
        <template #cell-phase="{ row }">
          <span v-if="row.original.phase" class="text-sm text-muted-foreground">
            {{ RENDER_PHASE_LABEL[row.original.phase] }}
          </span>
          <span v-else class="text-sm text-muted-foreground">-</span>
        </template>
        <template #cell-progress="{ row }">
          <div v-if="row.original.state === 'RUNNING'" class="grid gap-1">
            <FaProgress :model-value="model.taskProgress(row.original)" />
            <span class="text-xs text-muted-foreground">
              {{ model.taskProgress(row.original) }}% · {{ row.original.doneTiles }} / {{ row.original.totalTiles }}
              <template v-if="row.original.message">· {{ row.original.message }}</template>
            </span>
          </div>
          <span v-else-if="row.original.state === 'FAILED' || row.original.state === 'CANCELLED'" class="text-xs text-destructive">
            {{ row.original.error || row.original.message || '渲染失败' }}
          </span>
          <span v-else class="text-sm text-muted-foreground">
            {{ model.taskProgress(row.original) }}% · {{ row.original.doneTiles }} / {{ row.original.totalTiles }}
          </span>
        </template>
        <template #cell-createdAt="{ row }">
          {{ formatTime(row.original.createdAt) }}
        </template>
        <template #cell-finishedAt="{ row }">
          {{ formatTime(row.original.finishedAt) }}
        </template>
        <template #empty>
          <span class="text-muted-foreground">暂无渲染任务，点击右上角「触发渲染」开始</span>
        </template>
      </FaTable>
    </template>
  </FaPageMain>
</template>
