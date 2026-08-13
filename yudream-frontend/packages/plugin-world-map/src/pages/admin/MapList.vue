<script setup lang="ts">
import type { FileItem, FileUploadRequestOptions, TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import {
  FaButton,
  FaCard,
  FaFileUpload,
  FaIcon,
  FaInput,
  FaModal,
  FaPageHeader,
  FaPageMain,
  FaProgress,
  FaResponsiveTable,
  FaSelect,
  FaSwitch,
  FaTag,
  useFaModal,
} from '@yudream/components'
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  DIMENSION_OPTIONS,
  dimensionLabel,
  formatTime,
  MAP_STATE_META,
  RENDER_PHASE_LABEL,
  useWorldMapAdmin,
} from '../../composables/useWorldMapAdmin'
import type { MapAdmin } from '../../types'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  route?: RouteLocationNormalizedLoaded
}>()

const router = useRouter()
const modal = useFaModal()
const model = useWorldMapAdmin(props.sdk)

const columns: TableColumn<MapAdmin>[] = [
  { id: 'name', header: '名称', width: 200, fixed: 'left' },
  { id: 'dimension', header: '维度', width: 100 },
  { id: 'state', header: '状态', width: 110 },
  { id: 'tiles', header: 'Tile 数', width: 180 },
  { id: 'renderedAt', header: '渲染时间', width: 160 },
  { id: 'operation', header: '操作', width: 230, fixed: 'right' },
]

const worldFiles = ref<FileItem[]>([])
const clientJarFiles = ref<FileItem[]>([])

function isZipFile(file: File) {
  return file.type === 'application/zip' || file.name.toLowerCase().endsWith('.zip')
}

function isJarFile(file: File) {
  return file.type === 'application/java-archive' || file.name.toLowerCase().endsWith('.jar')
}

function selectWorldFile(options: FileUploadRequestOptions) {
  model.worldFile.value = options.file
  options.onProgress(100)
  return { selected: true }
}

function selectClientJar(options: FileUploadRequestOptions) {
  model.clientJarFile.value = options.file
  options.onProgress(100)
  return { selected: true }
}

function viewMap() {
  void router.push('/world-map')
}

function openDetail(map: MapAdmin) {
  void router.push({ path: '/world-map/admin/map-detail', query: { id: map.id } })
}

function activeTaskFor(mapId: string) {
  return model.activeTasksByMapId.value.get(mapId)
}

function confirmDelete(map: MapAdmin) {
  modal.confirm({
    title: '删除地图',
    content: `确认删除「${map.name}」吗？该地图的全部 tile 数据将一并删除，且不可恢复。`,
    onConfirm: () => model.removeMap(map),
  })
}

function mapStateTag(state: MapAdmin['state']) {
  return MAP_STATE_META[state]
}
</script>

<template>
  <FaPageHeader title="世界地图管理" class="mb-0">
    <FaButton size="sm" @click="model.openCreate">
      <FaIcon name="i-mdi:plus" />
      新建地图
    </FaButton>
  </FaPageHeader>

  <FaPageMain>
    <FaResponsiveTable
      row-key="id"
      table-root-class="max-w-full overflow-x-auto rounded-lg"
      table-class="min-w-[960px]"
      border
      stripe
      :columns="columns"
      :data="model.maps.value"
    >
      <template #cell-name="{ row }">
        <FaButton variant="link" class="h-auto px-0" @click="openDetail(row.original)">
          {{ row.original.name }}
        </FaButton>
      </template>
      <template #cell-dimension="{ row }">
        {{ dimensionLabel(row.original.dimension) }}
      </template>
      <template #cell-state="{ row }">
        <div class="grid min-w-[180px] gap-1">
          <div class="flex items-center gap-2">
            <FaTag :variant="mapStateTag(row.original.state).variant">
              {{ mapStateTag(row.original.state).label }}
            </FaTag>
            <span v-if="row.original.state === 'FAILED' && row.original.message" class="min-w-0 truncate text-xs text-muted-foreground" :title="row.original.message">
              {{ row.original.message }}
            </span>
          </div>
          <template v-if="activeTaskFor(row.original.id)">
            <div class="flex items-center justify-between gap-2 text-xs text-muted-foreground">
              <span>{{ RENDER_PHASE_LABEL[activeTaskFor(row.original.id)!.phase || 'IMPORT'] }}</span>
              <span>{{ model.renderTaskProgress(activeTaskFor(row.original.id)!) }}%</span>
            </div>
            <FaProgress :model-value="model.renderTaskProgress(activeTaskFor(row.original.id)!)" />
            <span class="truncate text-xs text-muted-foreground" :title="activeTaskFor(row.original.id)!.message">
              {{ activeTaskFor(row.original.id)!.doneTiles }} / {{ activeTaskFor(row.original.id)!.totalTiles }}<template v-if="activeTaskFor(row.original.id)!.message"> · {{ activeTaskFor(row.original.id)!.message }}</template>
            </span>
          </template>
        </div>
      </template>
      <template #cell-tiles="{ row }">
        <span class="text-sm text-muted-foreground">
          高精 {{ row.original.hiresTiles }} / 低清 {{ row.original.lowresTiles }}
        </span>
      </template>
      <template #cell-renderedAt="{ row }">
        {{ formatTime(row.original.renderedAt) }}
      </template>
      <template #cell-operation="{ row }">
        <div class="flex items-center gap-2">
          <FaButton
            size="sm"
            variant="outline"
            :disabled="row.original.state === 'RENDERING'"
            :loading="model.operating.value === row.original.id"
            @click="model.triggerRender(row.original)"
          >
            渲染
          </FaButton>
          <FaButton
            size="sm"
            variant="outline"
            :disabled="row.original.state !== 'READY'"
            @click="viewMap()"
          >
            查看
          </FaButton>
          <FaButton
            size="sm"
            variant="destructive"
            :disabled="row.original.state === 'RENDERING'"
            @click="confirmDelete(row.original)"
          >
            删除
          </FaButton>
        </div>
      </template>
      <template #empty>
        <span class="text-muted-foreground">
          {{ model.loading.value ? '加载中…' : '暂无地图，点击右上角「新建地图」开始' }}
        </span>
      </template>
      <template #card="{ row }">
        <FaCard class="w-full">
          <div class="flex flex-col gap-3">
            <div class="flex items-center justify-between gap-2">
              <span class="text-base font-semibold">{{ row.name }}</span>
              <div class="flex gap-1">
                <FaTag :variant="mapStateTag(row.state).variant">
                  {{ mapStateTag(row.state).label }}
                </FaTag>
              </div>
            </div>
            <div class="flex flex-col gap-1 text-sm">
              <div class="flex gap-2">
                <span class="shrink-0 text-secondary-foreground/60">维度</span>
                <span class="break-all">{{ dimensionLabel(row.dimension) }}</span>
              </div>
              <div class="flex gap-2">
                <span class="shrink-0 text-secondary-foreground/60">Tile 数</span>
                <span class="break-all">高精 {{ row.hiresTiles }} / 低清 {{ row.lowresTiles }}</span>
              </div>
              <div class="flex gap-2">
                <span class="shrink-0 text-secondary-foreground/60">渲染时间</span>
                <span>{{ formatTime(row.renderedAt) }}</span>
              </div>
            </div>
            <div class="flex flex-wrap gap-2 border-t pt-3">
              <FaButton
                size="sm"
                variant="outline"
                :disabled="row.state === 'RENDERING'"
                :loading="model.operating.value === row.id"
                @click="model.triggerRender(row)"
              >
                渲染
              </FaButton>
              <FaButton
                size="sm"
                variant="outline"
                :disabled="row.state !== 'READY'"
                @click="viewMap()"
              >
                查看
              </FaButton>
              <FaButton
                size="sm"
                variant="destructive"
                :disabled="row.state === 'RENDERING'"
                @click="confirmDelete(row)"
              >
                删除
              </FaButton>
            </div>
          </div>
        </FaCard>
      </template>
    </FaResponsiveTable>

    <FaModal v-model="model.createOpen.value" title="新建地图">
      <div class="grid gap-4">
        <label class="grid gap-1.5">
          <span class="text-sm">地图名称</span>
          <FaInput v-model="model.createForm.name" placeholder="例如：生存世界" />
        </label>

        <label class="grid gap-1.5">
          <span class="text-sm">维度</span>
          <FaSelect v-model="model.createForm.dimension" :options="DIMENSION_OPTIONS" />
        </label>

        <div class="grid gap-1.5">
          <span class="text-sm">存档 zip</span>
          <FaFileUpload
            v-model="worldFiles"
            :max="1"
            :disabled="model.creating.value"
            :before-upload="isZipFile"
            :http-request="selectWorldFile"
            description="拖放或点击选择 ZIP 压缩包（必填）"
          />
        </div>

        <div class="grid gap-1.5">
          <span class="text-sm">客户端 jar（可选，留空则从镜像下载）</span>
          <FaFileUpload
            v-model="clientJarFiles"
            :max="1"
            :disabled="model.creating.value"
            :before-upload="isJarFile"
            :http-request="selectClientJar"
            description="拖放或点击选择客户端 JAR（可选）"
          />
        </div>

        <label class="flex items-center gap-2">
          <FaSwitch v-model="model.createForm.stripNetherCeiling" :disabled="model.creating.value" />
          <span class="text-sm">剥离下界基岩顶（仅下界生效）</span>
        </label>

        <p v-if="model.createStep.value" class="text-sm text-muted-foreground">
          {{ model.createStep.value }}
        </p>
      </div>

      <template #footer>
        <div class="flex justify-end gap-2">
          <FaButton variant="outline" :disabled="model.creating.value" @click="model.createOpen.value = false">
            取消
          </FaButton>
          <FaButton
            :disabled="!model.createValid.value"
            :loading="model.creating.value"
            @click="model.submitCreate"
          >
            创建
          </FaButton>
        </div>
      </template>
    </FaModal>
  </FaPageMain>
</template>
