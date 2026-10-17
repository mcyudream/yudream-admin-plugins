<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import {
  YdAction,
  YdButton,
  YdCard,
  YdIcon,
  YdPageHeader,
  YdPageMain,
  YdPagination,
  YdProgress,
  YdTag,
  useYdToast,
} from '@yudream/components'
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import CoverThumb from '../../components/CoverThumb.vue'
import {
  dimensionLabel,
  formatError,
  formatTime,
  MAP_STATE_META,
  renderPhaseLabel,
  TASK_STATE_META,
  useVoxelithAdmin,
} from '../../composables/useVoxelithAdmin'
import { createVoxelithMapAdminApi } from '../../api/voxelith-map-admin-api'
import { createVoxelithMapApi } from '../../api/voxelith-map-api'
import { renderTaskProgress } from '../../composables/useRenderTaskProgress'
import type { MapAdmin, RenderTask } from '../../types'
import { groupMapsByArchive } from '../../composables/archiveGroups'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  route?: RouteLocationNormalizedLoaded
}>()

const router = useRouter()
const toast = useYdToast()
const api = createVoxelithMapAdminApi(props.sdk)
const model = useVoxelithAdmin(props.sdk)
const publicApi = createVoxelithMapApi(props.sdk)

function openDetail(map: MapAdmin): void {
  void router.push({ path: '/platform/plugins/voxelith-map/map-detail', query: { id: map.id } })
}

function openMapManager(): void {
  void router.push({ path: '/platform/plugins/voxelith-map/maps' })
}

function openViewer(map: MapAdmin): void {
  window.open(`/platform/plugins/voxelith-map/viewer?map=${encodeURIComponent(map.id)}`, '_blank')
}

function openMarkers(map: MapAdmin): void {
  void router.push({ path: '/platform/plugins/voxelith-map/markers', query: { id: map.id } })
}

// ---------------------------------------------------------------------------
// 展示图：有封面（默认视角截图）用封面，没有就用插件自带的默认图。
// 两者同为 16:9 比例、铺满卡片宽度，列表观感统一；不再使用俯视预览底图。
// ---------------------------------------------------------------------------

/** 未保存封面时的系统默认展示图（随插件资源分发，与封面截图同比例） */
const DEFAULT_COVER_URL = props.sdk.assets.url('assets/map-cover-default.svg')

function coverUrlOf(map: MapAdmin): string {
  // 缩略图而非原封面：卡片几百像素宽，拉长边 1280 的 PNG 截屏会占满同源连接、把页面卡住。
  // 版本号用封面自己的版本（coverRevision），改动地图名称/参数不会让封面重新下载。
  const revision = map.coverRevision ?? 0
  return revision > 0 ? publicApi.coverThumbUrl(map.id, revision) : DEFAULT_COVER_URL
}

// ---------------------------------------------------------------------------
// 分页：画廊渲染的是卡片，一屏十几张封面就足以把同源连接占满（站点是 HTTP/1.1）。
// 只渲染当前页，配合 CoverThumb 的排队取图，页面其它请求始终有连接可用。
// ---------------------------------------------------------------------------

const page = ref(1)
const pageSize = ref(12)

/** 按存档收纳：一个存档一张卡片，维度收在卡片内切换（与地图管理页同一套分组口径）。 */
const archiveGroups = computed(() => groupMapsByArchive(model.maps.value))

const pagedGroups = computed(() => {
  const start = (page.value - 1) * pageSize.value
  return archiveGroups.value.slice(start, start + pageSize.value)
})

/** 卡片内当前维度：按分组键记住选择，默认组内第一张（主世界）。 */
const dimensionPick = reactive<Record<string, string>>({})

function currentDimensionOf(group: { key: string, dimensions: MapAdmin[] }): MapAdmin {
  const picked = dimensionPick[group.key]
  return group.dimensions.find(item => item.id === picked) ?? group.dimensions[0]!
}

function switchDimension(groupKey: string, mapId: string): void {
  dimensionPick[groupKey] = mapId
}

function dimensionOptionsOf(group: { dimensions: MapAdmin[] }) {
  return group.dimensions.map(item => ({ label: dimensionLabel(item.dimension), value: item.id }))
}

/** 卡片数据：当前维度的地图本身 + 同组维度（既有模板里的 map.x 全部照旧）。 */
const pagedMaps = computed(() => pagedGroups.value.map((group) => {
  const current = currentDimensionOf(group)
  return {
    ...current,
    archiveKey: group.key,
    archiveName: group.archiveName,
    archiveDimensions: group.dimensions,
  }
}))

/** 数据量或每页条数变化后，把页码收回存在的页：否则会停在没有内容的空页上 */
function clampPage(): void {
  const lastPage = Math.max(1, Math.ceil(archiveGroups.value.length / pageSize.value))
  if (page.value > lastPage) page.value = lastPage
}

watch(() => archiveGroups.value.length, clampPage)
watch(pageSize, clampPage)

// ---------------------------------------------------------------------------
// 停止渲染
// ---------------------------------------------------------------------------

const stopping = ref('')

function activeTaskOf(map: MapAdmin): RenderTask | null {
  return model.activeTasksByMapId.value.get(map.id) ?? null
}

function tileText(task: RenderTask): string {
  // 老任务没有瓦片计数（一直 0/0），只显百分比，别把假的 0/0 亮出来
  return task.totalTiles > 0 ? ` · ${task.completedTiles} / ${task.totalTiles}` : ''
}

async function stopRender(task: RenderTask): Promise<void> {
  if (stopping.value) return
  stopping.value = task.id
  try {
    const res = await api.cancelTask(task.id)
    if (res.canceled) {
      toast.success(`已请求停止「${task.mapName}」的渲染`)
    }
    else {
      // 后端返回 canceled=false：任务其实已经结束（或服务重启过），如实告知
      toast.warning('任务已结束或服务已重启，无需停止')
    }
    await model.loadMaps(true)
  }
  catch (e) {
    toast.error(formatError(e, '停止渲染失败'))
  }
  finally {
    stopping.value = ''
  }
}
</script>

<template>
  <YdPageHeader title="地图画廊" class="mb-0">
    <YdButton size="sm" variant="outline" @click="openMapManager">
      <YdIcon name="i-mdi:table" />
      地图管理
    </YdButton>
  </YdPageHeader>

  <YdPageMain>
    <!-- 有地图：卡片网格（只渲染当前页）+ 分页。用 template 包一层，
         否则后面的 v-else-if（加载中/空态）会因为中间夹了分页器而断开 -->
    <template v-if="model.maps.value.length">
      <div class="grid grid-cols-2 gap-3 md:grid-cols-4">
          <YdCard v-for="map in pagedMaps" :key="map.id" class="overflow-hidden p-3">
          <!-- 展示图：封面缩略图或系统默认图，同一比例铺满卡片宽度；状态标签叠在封面右上角 -->
          <YdAction
            type="button"
            class="relative mb-2 block w-full cursor-pointer overflow-hidden rounded-md border-0 bg-[#0b0f1a] p-0"
            @click="openDetail(map)"
          >
            <CoverThumb :src="coverUrlOf(map)" :alt="map.name" />
            <YdTag :variant="MAP_STATE_META[map.state].variant" class="absolute right-2 top-2 shadow-sm">
              {{ MAP_STATE_META[map.state].label }}
            </YdTag>
          </YdAction>

          <div class="grid gap-2">
            <YdButton variant="link" class="h-auto min-w-0 px-0" @click="openDetail(map)">
              <span class="min-w-0 truncate">{{ map.name }}</span>
            </YdButton>

            <div class="flex items-center gap-1.5 text-xs text-muted-foreground">
              <!-- 多维度存档：卡片内直接切维度，标题/封面/状态跟着切到该维度 -->
              <YdSelect
                v-if="map.archiveDimensions.length > 1"
                :model-value="map.id"
                :options="dimensionOptionsOf({ dimensions: map.archiveDimensions })"
                class="min-w-[128px]"
                @update:model-value="(value: string | number) => switchDimension(map.archiveKey, String(value))"
              />
              <span v-else class="shrink-0">{{ dimensionLabel(map.dimension) }}</span>
              <span v-if="map.scaleDescription" class="min-w-0 truncate" :title="map.scaleDescription">
                · {{ map.scaleDescription }}
              </span>
            </div>

            <!-- 进行中任务内联（有活跃任务才出现） -->
            <div
              v-if="activeTaskOf(map)"
              class="grid gap-1 rounded-md border border-border bg-muted/40 p-2"
            >
              <div class="flex items-center gap-1.5 text-xs">
                <YdTag :variant="TASK_STATE_META[activeTaskOf(map)!.state]?.variant || 'secondary'">
                  {{ TASK_STATE_META[activeTaskOf(map)!.state]?.label || activeTaskOf(map)!.state }}
                </YdTag>
                <span class="min-w-0 flex-1 truncate text-muted-foreground" :title="activeTaskOf(map)!.message || ''">
                  {{ renderPhaseLabel(activeTaskOf(map)!.phase) }}{{ activeTaskOf(map)!.message ? ` · ${activeTaskOf(map)!.message}` : '' }}
                </span>
                <YdButton
                  size="sm"
                  variant="ghost"
                  :loading="stopping === activeTaskOf(map)!.id"
                  title="停止渲染"
                  @click="() => { void stopRender(activeTaskOf(map)!) }"
                >
                  <YdIcon name="i-mdi:stop" />
                </YdButton>
              </div>
              <YdProgress :model-value="renderTaskProgress(activeTaskOf(map)!)" />
              <span class="text-xs text-muted-foreground">
                {{ renderTaskProgress(activeTaskOf(map)!) }}%{{ tileText(activeTaskOf(map)!) }}
              </span>
            </div>

            <div
              v-else-if="map.state === 'FAILED' && map.lastError"
              class="min-w-0 truncate rounded-md border border-destructive/30 bg-destructive/5 p-2 text-xs text-destructive"
              :title="map.lastError"
            >
              {{ map.lastError }}
            </div>

            <div class="flex items-center justify-between gap-2 border-t pt-2">
              <span class="min-w-0 shrink-0 text-xs text-muted-foreground">
                {{ formatTime(map.updatedAt) }}
              </span>
              <div class="flex shrink-0 gap-1.5">
                <YdButton
                  size="sm"
                  variant="outline"
                  :disabled="map.state !== 'READY'"
                  @click="openViewer(map)"
                >
                  查看
                </YdButton>
                <YdButton size="sm" variant="outline" @click="openMarkers(map)">
                  标注
                </YdButton>
              </div>
            </div>
          </div>
        </YdCard>
    </div>

      <YdPagination
        v-if="archiveGroups.length > pageSize"
        v-model:page="page"
        v-model:size="pageSize"
        :total="archiveGroups.length"
        class="mt-3"
      />
    </template>

    <div v-else-if="model.loading.value" class="py-16 text-center text-sm text-muted-foreground">
      正在加载地图…
    </div>

    <div v-else class="py-16 text-center">
      <p class="mb-3 text-sm text-muted-foreground">还没有地图</p>
      <YdButton @click="openMapManager">去新建地图</YdButton>
    </div>
  </YdPageMain>
</template>
