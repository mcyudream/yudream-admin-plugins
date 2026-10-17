<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import {
  YdButton,
  YdCard,
  YdIcon,
  YdPageHeader,
  YdPageMain,
  YdResponsiveTable,
  YdTag,
  useYdModal,
  useYdToast,
} from '@yudream/components'
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { createVoxelithMapAdminApi } from '../../api/voxelith-map-admin-api'
import { dimensionLabel, formatError, formatTime, MAP_STATE_META } from '../../composables/useVoxelithAdmin'
import type { MapAdmin, MapAdminState } from '../../types'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  route?: RouteLocationNormalizedLoaded
}>()

const router = useRouter()
const toast = useYdToast()
const modal = useYdModal()
const api = createVoxelithMapAdminApi(props.sdk)

const maps = ref<MapAdmin[]>([])
const loading = ref(false)
const operating = ref('')

const columns = [
  { id: 'name', header: '名称', width: 180 },
  { id: 'dimension', header: '维度', width: 110 },
  { id: 'state', header: '状态', width: 100 },
  { id: 'updatedAt', header: '更新时间', width: 170 },
  { id: 'operation', header: '操作', width: 200 },
]

const total = computed(() => maps.value.length)

async function load(): Promise<void> {
  loading.value = true
  try {
    const res = await api.archivedMaps()
    maps.value = res.maps ?? []
  }
  catch (e) {
    toast.error(formatError(e, '归档列表加载失败'))
  }
  finally {
    loading.value = false
  }
}

function goBack(): void {
  router.back()
}

function restore(map: MapAdmin): void {
  if (operating.value) return
  operating.value = map.id
  void api.unarchiveMap(map.id).then(async () => {
    toast.success(`「${map.name}」已恢复`)
    await load()
  }).catch((e: unknown) => toast.error(formatError(e, '恢复失败'))).finally(() => { operating.value = '' })
}

function removeForever(map: MapAdmin): void {
  if (operating.value) return
  modal.confirm({
    title: '彻底删除地图',
    content: `确认彻底删除「${map.name}」吗？该地图的全部瓦片数据将一并删除，且不可恢复。`
      + '\n删除将在服务端后台执行，可在「任务中心」查看结果与日志。',
    onConfirm: () => {
      operating.value = map.id
      // 删除作业在服务端执行，可在任务中心看结果与日志
      void api.startDeleteJob(map.id).then(async () => {
        toast.success(`「${map.name}」的删除任务已开始（服务端执行）`)
        await load()
      }).catch((e: unknown) => toast.error(formatError(e, '删除失败'))).finally(() => { operating.value = '' })
    },
  })
}

onMounted(() => {
  void load()
})
</script>

<template>
  <YdPageHeader title="归档" class="mb-0">
    <YdButton size="sm" variant="outline" @click="goBack">
      <YdIcon name="i-mdi:arrow-left" />
      返回
    </YdButton>
  </YdPageHeader>

  <YdPageMain>
    <YdCard class="p-4">
      <div class="mb-3 flex items-center justify-between">
        <h3 class="text-base font-semibold">已归档地图</h3>
        <span class="text-xs text-muted-foreground">共 {{ total }} 张；归档只是隐藏，可恢复或彻底删除</span>
      </div>
      <YdResponsiveTable
        row-key="id"
        border
        stripe
        :columns="columns"
        :data="maps"
      >
        <template #cell-name="{ row }">
          <span class="min-w-0 break-words">{{ row.original.name }}</span>
        </template>
        <template #cell-dimension="{ row }">
          {{ dimensionLabel(row.original.dimension) }}
        </template>
        <template #cell-state="{ row }">
          <YdTag :variant="MAP_STATE_META[row.original.state as MapAdminState].variant">
            {{ MAP_STATE_META[row.original.state as MapAdminState].label }}
          </YdTag>
        </template>
        <template #cell-updatedAt="{ row }">
          {{ formatTime(row.original.updatedAt) }}
        </template>
        <template #cell-operation="{ row }">
          <div class="flex flex-wrap gap-2">
            <YdButton
              size="sm"
              variant="outline"
              :disabled="operating === row.original.id"
              @click="restore(row.original)"
            >
              恢复
            </YdButton>
            <YdButton
              size="sm"
              variant="destructive"
              :disabled="operating === row.original.id"
              @click="removeForever(row.original)"
            >
              彻底删除
            </YdButton>
          </div>
        </template>
        <template #card="{ row }">
          <div class="flex flex-col gap-2">
            <div class="flex items-center justify-between gap-2">
              <strong class="min-w-0 truncate">{{ row.name }}</strong>
              <YdTag :variant="MAP_STATE_META[row.state as MapAdminState].variant">
                {{ MAP_STATE_META[row.state as MapAdminState].label }}
              </YdTag>
            </div>
            <div class="text-xs text-muted-foreground">
              {{ dimensionLabel(row.dimension) }} · {{ formatTime(row.updatedAt) }}
            </div>
            <div class="flex flex-wrap gap-2">
              <YdButton size="sm" variant="outline" :disabled="operating === row.id" @click="restore(row)">
                恢复
              </YdButton>
              <YdButton size="sm" variant="destructive" :disabled="operating === row.id" @click="removeForever(row)">
                彻底删除
              </YdButton>
            </div>
          </div>
        </template>
        <template #empty>
          <span class="text-muted-foreground">暂无归档地图</span>
        </template>
      </YdResponsiveTable>
    </YdCard>
  </YdPageMain>
</template>
