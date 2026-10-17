<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import {
  YdButton,
  YdCard,
  YdCheckbox,
  YdIcon,
  YdInput,
  YdModal,
  YdNumberField,
  YdPageHeader,
  YdPageMain,
  YdResponsiveTable,
  YdSelect,
  YdTag,
  useYdModal,
  useYdToast,
} from '@yudream/components'
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { createVoxelithMapAdminApi } from '../../api/voxelith-map-admin-api'
import { markerSetSchema, type Marker, type MarkerSet } from '../../vendor/voxelith/core/index'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  route?: RouteLocationNormalizedLoaded
}>()

const router = useRouter()
const toast = useYdToast()
const modal = useYdModal()
const api = createVoxelithMapAdminApi(props.sdk)

const mapId = computed(() => String(props.route?.query?.id ?? ''))
const mapName = ref('')
const loading = ref(false)
const saving = ref(false)
const error = ref('')

/** 整表工作副本：所有操作直接改这里并立即写回服务端 */
const sets = ref<MarkerSet[]>([])

/**
 * 立即整表写回。失败时重新加载服务端数据回滚，保证工作副本与服务端一致。
 * @returns 是否写回成功
 */
async function persistNow(): Promise<boolean> {
  if (!mapId.value) return false
  saving.value = true
  try {
    // 后端要求 { sets: [...] } 对象
    await api.saveMarkers(mapId.value, { sets: sets.value })
    return true
  }
  catch (e) {
    toast.error(e instanceof Error ? e.message : '保存标注失败')
    await load()
    return false
  }
  finally {
    saving.value = false
  }
}

/** 行内文本编辑（如标注集重命名）逐键触发，去抖后写回 */
let persistTimer: ReturnType<typeof setTimeout> | null = null

function schedulePersist(): void {
  if (persistTimer) clearTimeout(persistTimer)
  persistTimer = setTimeout(() => {
    persistTimer = null
    void persistNow()
  }, 600)
}

onBeforeUnmount(() => {
  // 离开页面时还有未写回的行内修改：立即落盘
  if (persistTimer) {
    clearTimeout(persistTimer)
    persistTimer = null
    void persistNow()
  }
})

const MARKER_TYPE_META: Record<string, { label: string, variant: 'default' | 'secondary' | 'outline' }> = {
  poi: { label: '点位', variant: 'default' },
  line: { label: '线', variant: 'secondary' },
  shape: { label: '范围', variant: 'secondary' },
  extrude: { label: '拉伸体', variant: 'secondary' },
  box: { label: '盒', variant: 'secondary' },
}

interface Row {
  key: string
  setId: string
  setLabel: string
  marker: Marker
}

const rows = computed<Row[]>(() => sets.value.flatMap(set =>
  set.markers.map((marker, index) => ({
    key: `${set.id}:${marker.id}:${index}`,
    setId: set.id,
    setLabel: set.label,
    marker,
  })),
))

const setOptions = computed(() => sets.value.map(s => ({ label: s.label, value: s.id })))

// ---------------------------------------------------------------------------
// 标注列表筛选：按名称/ID 关键词、类型、归属标注集过滤（客户端过滤，数据整表加载）
// ---------------------------------------------------------------------------

const markerSearch = ref('')
const typeFilter = ref('all')
const setFilter = ref('all')

const typeFilterOptions = [
  { label: '类型：全部', value: 'all' },
  ...Object.entries(MARKER_TYPE_META).map(([value, meta]) => ({ label: meta.label, value })),
]

const setFilterOptions = computed(() => [
  { label: '标注集：全部', value: 'all' },
  ...sets.value.map(s => ({ label: s.label, value: s.id })),
])

const filtersActive = computed(() =>
  markerSearch.value.trim() !== '' || typeFilter.value !== 'all' || setFilter.value !== 'all')

const filteredRows = computed(() => {
  const kw = markerSearch.value.trim().toLowerCase()
  return rows.value.filter((r) => {
    if (kw && !(r.marker.label || '').toLowerCase().includes(kw) && !r.marker.id.toLowerCase().includes(kw)) return false
    if (typeFilter.value !== 'all' && r.marker.type !== typeFilter.value) return false
    if (setFilter.value !== 'all' && r.setId !== setFilter.value) return false
    return true
  })
})

function resetFilters(): void {
  markerSearch.value = ''
  typeFilter.value = 'all'
  setFilter.value = 'all'
}

// ---------------------------------------------------------------------------
// 标注集管理：各地图的标注集相互独立（存于各自地图的标注文件），此处增删改，
// 与标注一样先进工作副本、点「保存」整表写回
// ---------------------------------------------------------------------------

const newSetName = ref('')

function addSet(): void {
  const label = newSetName.value.trim()
  if (!label) {
    toast.warning('请填写标注集名称')
    return
  }
  if (sets.value.some(s => s.label === label)) {
    toast.warning('已存在同名标注集')
    return
  }
  sets.value.push({ id: `set-${Date.now()}`, label, toggleable: true, defaultHidden: false, sorting: 100, markers: [] })
  newSetName.value = ''
  void persistNow().then(ok => { if (ok) toast.success(`已添加标注集「${label}」`) })
}

function renameSet(set: MarkerSet, label: string): void {
  const value = label.trim()
  if (!value || value === set.label) return
  set.label = value
  schedulePersist()
}

function deleteSet(set: MarkerSet): void {
  modal.confirm({
    title: '删除标注集',
    content: `确认删除标注集「${set.label}」吗？其中 ${set.markers.length} 条标注将一并删除。`,
    onConfirm: () => {
      sets.value = sets.value.filter(s => s.id !== set.id)
      void persistNow().then(ok => { if (ok) toast.success(`已删除标注集「${set.label}」`) })
    },
  })
}

/** 弹窗内快捷新建：选择「新建标注集…」后填名称，确定时创建 */
function ensureDialogSet(): MarkerSet | null {
  if (dialog.setId !== '__new__') {
    return sets.value.find(s => s.id === dialog.setId) ?? null
  }
  const label = dialog.newSetName.trim()
  if (!label) {
    toast.warning('请填写新标注集名称')
    return null
  }
  let created = sets.value.find(s => s.label === label)
  if (!created) {
    created = { id: `set-${Date.now()}`, label, toggleable: true, defaultHidden: false, sorting: 100, markers: [] }
    sets.value.push(created)
  }
  dialog.setId = created.id
  dialog.newSetName = ''
  return created
}

// ---------------------------------------------------------------------------
// 加载 / 保存
// ---------------------------------------------------------------------------

async function load(): Promise<void> {
  if (!mapId.value) {
    error.value = '缺少地图 id'
    return
  }
  loading.value = true
  error.value = ''
  try {
    const map = await api.map(mapId.value)
    mapName.value = map.name
    const res = await api.markers(mapId.value)
    const valid: MarkerSet[] = []
    let invalid = 0
    for (const item of res.sets ?? []) {
      const parsed = markerSetSchema.safeParse(item)
      if (parsed.success) valid.push(parsed.data)
      else invalid++
    }
    sets.value = valid
    if (invalid > 0) {
      toast.warning(`${invalid} 组标注格式不合法已跳过；保存后将覆盖整个标注文件`)
    }
  }
  catch (e) {
    error.value = e instanceof Error ? e.message : '标注加载失败'
  }
  finally {
    loading.value = false
  }
}

/** 返回上一页（查看页 / 列表页 / 详情页均可进入本页） */
function goBack(): void {
  router.back()
}

// ---------------------------------------------------------------------------
// 新增 / 编辑弹窗：名称、标注集、XYZ 坐标、是否忽略 Y 轴
// 仅点位（poi）可量化编辑 XYZ；范围/线/盒的几何在查看页绘制，弹窗内只改名称与归属
// ---------------------------------------------------------------------------

const dialogOpen = ref(false)
const dialog = reactive({
  mode: 'create' as 'create' | 'edit',
  /** 编辑态：原归属集与标注 id（换集时从旧集摘除） */
  originSetId: '',
  markerId: '',
  markerType: 'poi',
  label: '',
  setId: 'admin-annotations',
  /** 快捷新建标注集的名称（setId === '__new__' 时生效） */
  newSetName: '',
  x: 0,
  y: 64,
  z: 0,
  /** 忽略 Y 轴：不展示/不修改 Y，落盘时新标注用 64，已有标注保留原 Y */
  ignoreY: true,
  /** 范围（shape）标注的多边形顶点，弹窗内可增删改 */
  verts: [] as Array<{ x: number, z: number }>,
  /** 范围（shape）标注的贴地高度 shapeY */
  shapeY: 64,
})

const dialogIsPoi = computed(() => dialog.markerType === 'poi')

/** 新增范围标注：直接在弹窗内填多边形顶点（无需打开查看器） */
function openCreateShapeDialog(): void {
  dialog.mode = 'create'
  dialog.originSetId = ''
  dialog.markerId = ''
  dialog.markerType = 'shape'
  dialog.label = ''
  dialog.setId = sets.value.find(s => s.id === 'admin-annotations')?.id ?? sets.value[0]?.id ?? ''
  dialog.newSetName = ''
  dialog.ignoreY = true
  dialog.shapeY = 64
  dialog.verts = [
    { x: 0, z: 0 },
    { x: 0, z: 0 },
    { x: 0, z: 0 },
  ]
  dialogOpen.value = true
}

function openCreateDialog(): void {
  dialog.mode = 'create'
  dialog.originSetId = ''
  dialog.markerId = ''
  dialog.markerType = 'poi'
  dialog.label = ''
  dialog.setId = sets.value.find(s => s.id === 'admin-annotations')?.id ?? sets.value[0]?.id ?? ''
  dialog.newSetName = ''
  dialog.x = 0
  dialog.y = 64
  dialog.z = 0
  dialog.ignoreY = true
  dialog.verts = []
  dialog.shapeY = 64
  dialogOpen.value = true
}

function openEditDialog(row: Row): void {
  dialog.mode = 'edit'
  dialog.originSetId = row.setId
  dialog.markerId = row.marker.id
  dialog.markerType = row.marker.type
  dialog.label = row.marker.label
  dialog.setId = row.setId
  if (row.marker.type === 'poi') {
    dialog.x = row.marker.position.x
    dialog.y = row.marker.position.y
    dialog.z = row.marker.position.z
  }
  else {
    dialog.x = 0
    dialog.y = 64
    dialog.z = 0
  }
  dialog.ignoreY = true
  dialog.verts = row.marker.type === 'shape'
    ? row.marker.shape.map(p => ({ ...p }))
    : []
  dialog.shapeY = row.marker.type === 'shape' ? row.marker.shapeY : 64
  dialogOpen.value = true
}

function addDialogVertex(): void {
  const last = dialog.verts[dialog.verts.length - 1]
  dialog.verts.push({ x: last?.x ?? 0, z: last?.z ?? 0 })
}

function saveDialog(): void {
  const label = dialog.label.trim()
  if (!label) {
    toast.warning('请填写标注名称')
    return
  }
  const target = ensureDialogSet()
  if (!target) return
  if (dialog.markerType === 'shape' && dialog.verts.length < 3) {
    toast.warning('多边形至少需要 3 个顶点')
    return
  }
  if (dialog.mode === 'create') {
    if (dialog.markerType !== 'shape') {
      toast.warning('点位用「新增标注」，线 / 盒标注请到查看页绘制')
      return
    }
    target.markers.push({
      id: `range-${Date.now()}`,
      type: 'shape',
      label,
      shape: dialog.verts.map(p => ({ x: Math.round(p.x), z: Math.round(p.z) })),
      holes: [],
      shapeY: dialog.shapeY,
      minDistance: 0,
      maxDistance: Number.MAX_SAFE_INTEGER,
      style: { fillColor: '#2f6fd0', lineColor: '#2f6fd0', opacity: 0.3 },
    })
  }
  else {
    // 编辑：改名；点位改坐标；范围改顶点；换集则从旧集摘除再挂到新集
    const origin = sets.value.find(s => s.id === dialog.originSetId)
    const marker = origin?.markers.find(m => m.id === dialog.markerId)
    if (!origin || !marker) {
      toast.warning('标注不存在或已被删除')
      return
    }
    marker.label = label
    if (marker.type === 'poi') {
      marker.position.x = dialog.x
      marker.position.z = dialog.z
      if (!dialog.ignoreY) marker.position.y = dialog.y
    }
    if (marker.type === 'shape') {
      marker.shape = dialog.verts.map(p => ({ x: Math.round(p.x), z: Math.round(p.z) }))
      marker.shapeY = dialog.shapeY
    }
    if (dialog.setId !== dialog.originSetId) {
      origin.markers = origin.markers.filter(m => m.id !== dialog.markerId)
      target.markers.push(marker)
    }
  }
  dialogOpen.value = false
  void persistNow().then((ok) => {
    if (ok) toast.success(dialog.mode === 'create' ? `已添加「${label}」` : `已修改「${label}」`)
  })
}

function isPoi(marker: Marker): marker is Extract<Marker, { type: 'poi' }> {
  return marker.type === 'poi'
}

function isBox(marker: Marker): marker is Extract<Marker, { type: 'box' }> {
  return marker.type === 'box'
}

function markerCoordsText(marker: Marker): string {
  if (isPoi(marker)) {
    return `${marker.position.x}, ${marker.position.y}, ${marker.position.z}`
  }
  if (isBox(marker)) {
    const { min, max } = marker
    return `(${min.x}, ${min.y}, ${min.z}) ~ (${max.x}, ${max.y}, ${max.z})`
  }
  if (marker.type === 'line') {
    return marker.points.map(p => `(${p.x}, ${p.y}, ${p.z})`).join(' → ')
  }
  return marker.shape.map(p => `(${p.x}, ${p.z})`).join(' → ')
}

function removeMarker(row: Row): void {
  modal.confirm({
    title: '删除标注',
    content: `确认删除标注「${row.marker.label || row.marker.id}」吗？`,
    onConfirm: () => {
      const set = sets.value.find(s => s.id === row.setId)
      if (!set) return
      const index = Number(row.key.split(':')[2])
      set.markers = set.markers.filter((m, i) => !(m.id === row.marker.id && i === index))
      void persistNow().then((ok) => {
        if (ok) toast.success(`已删除「${row.marker.label || row.marker.id}」`)
      })
    },
  })
}

onMounted(() => {
  void load()
})
</script>

<template>
  <YdPageHeader :title="mapName ? `标注管理：${mapName}` : '标注管理'" class="mb-0">
    <div class="flex gap-2">
      <YdButton size="sm" variant="outline" @click="goBack">
        <YdIcon name="i-mdi:arrow-left" />
        返回
      </YdButton>
      <YdButton size="sm" variant="outline" @click="openCreateDialog">
        <YdIcon name="i-mdi:map-marker-plus" />
        新增点位
      </YdButton>
      <YdButton size="sm" variant="outline" @click="openCreateShapeDialog">
        <YdIcon name="i-mdi:vector-square" />
        新增范围
      </YdButton>

    </div>
  </YdPageHeader>

  <YdPageMain>
    <div v-if="error" class="py-12 text-center text-muted-foreground">{{ error }}</div>
    <div v-else-if="loading" class="py-12 text-center text-muted-foreground">加载中…</div>

    <template v-else>
      <!-- 标注集管理：各地图独立 -->
      <YdCard class="mb-4 p-4">
        <div class="mb-3 flex items-center justify-between">
          <h3 class="text-base font-semibold">标注集管理</h3>
          <span class="text-xs text-muted-foreground">各地图的标注集相互独立</span>
        </div>
        <div class="mb-3 flex flex-wrap items-center gap-2">
          <YdInput v-model="newSetName" placeholder="新标注集名称，例如：建筑组" class="w-56" @keydown.enter="addSet" />
          <YdButton size="sm" variant="outline" @click="addSet">
            <YdIcon name="i-mdi:plus" />
            添加标注集
          </YdButton>
        </div>
        <div class="grid gap-1.5">
          <div
            v-for="set in sets"
            :key="set.id"
            class="flex items-center gap-2 text-sm"
          >
            <YdInput
              :model-value="set.label"
              class="w-56"
              @update:model-value="(v: string | undefined) => renameSet(set, v ?? '')"
            />
            <span class="text-xs text-muted-foreground">{{ set.markers.length }} 条标注</span>
            <span class="font-mono text-xs text-muted-foreground/60">{{ set.id }}</span>
            <YdButton size="sm" variant="ghost" title="删除该标注集（连同其中标注）" @click="deleteSet(set)">
              <YdIcon name="i-mdi:delete-outline" />
            </YdButton>
          </div>
          <span v-if="!sets.length" class="text-xs text-muted-foreground">暂无标注集，先添加一个</span>
        </div>
      </YdCard>

      <YdCard class="p-4">
        <div class="mb-3 flex items-center justify-between">
          <h3 class="text-base font-semibold">标注列表</h3>
          <span class="text-xs text-muted-foreground">
            共 {{ filteredRows.length }} 条{{ filtersActive ? `（筛选自 ${rows.length} 条）` : '' }}，操作即时生效
          </span>
        </div>
        <!-- YdSelect 内置 w-[200px]，窄轨道里会溢出压到相邻控件：包 min-w-0 容器并传 w-full 收缩 -->
        <div class="mb-3 grid grid-cols-1 gap-3 md:grid-cols-[minmax(200px,1fr)_130px_150px_auto]">
          <div class="min-w-0">
            <YdInput v-model="markerSearch" placeholder="搜索标注名称 / ID" clearable class="w-full" />
          </div>
          <div class="min-w-0">
            <YdSelect v-model="typeFilter" :options="typeFilterOptions" class="w-full" />
          </div>
          <div class="min-w-0">
            <YdSelect v-model="setFilter" :options="setFilterOptions" class="w-full" />
          </div>
          <div class="flex items-center justify-start gap-2 md:justify-end">
            <YdButton size="sm" variant="outline" :disabled="!filtersActive" @click="resetFilters">
              <YdIcon name="i-mdi:filter-off-outline" />
              重置筛选
            </YdButton>
          </div>
        </div>
        <YdResponsiveTable
          row-key="key"
          border
          stripe
          :data="filteredRows"
          :columns="[
            { id: 'setLabel', header: '标注集', width: 130 },
            { id: 'type', header: '类型', width: 80 },
            { id: 'label', header: '名称', width: 200 },
            { id: 'coords', header: '坐标 (X, Y, Z)', width: 280 },
            { id: 'operation', header: '操作', width: 120 },
          ]"
        >
          <template #cell-setLabel="{ row }">
            <span class="text-sm text-muted-foreground">{{ row.original.setLabel }}</span>
          </template>
          <template #cell-type="{ row }">
            <YdTag :variant="MARKER_TYPE_META[row.original.marker.type]?.variant ?? 'secondary'">
              {{ MARKER_TYPE_META[row.original.marker.type]?.label ?? row.original.marker.type }}
            </YdTag>
          </template>
          <template #cell-label="{ row }">
            <span class="block max-w-[190px] truncate" :title="row.original.marker.label">{{ row.original.marker.label || row.original.marker.id }}</span>
          </template>
          <template #cell-coords="{ row }">
            <span class="block max-w-[270px] truncate font-mono text-xs text-muted-foreground" :title="markerCoordsText(row.original.marker)">
              {{ markerCoordsText(row.original.marker) }}
            </span>
          </template>
          <template #cell-operation="{ row }">
            <div class="flex gap-1">
              <YdButton size="sm" variant="ghost" title="编辑" @click="openEditDialog(row.original)">
                <YdIcon name="i-mdi:pencil" />
              </YdButton>
              <YdButton size="sm" variant="ghost" title="删除" @click="removeMarker(row.original)">
                <YdIcon name="i-mdi:delete-outline" />
              </YdButton>
            </div>
          </template>
          <template #card="{ row }">
            <div class="flex flex-col gap-2">
              <div class="flex items-center justify-between gap-2">
                <strong class="min-w-0 truncate">{{ row.marker.label || row.marker.id }}</strong>
                <YdTag :variant="MARKER_TYPE_META[row.marker.type]?.variant ?? 'secondary'">
                  {{ MARKER_TYPE_META[row.marker.type]?.label ?? row.marker.type }}
                </YdTag>
              </div>
              <div class="text-xs text-muted-foreground">
                {{ row.setLabel }} · {{ markerCoordsText(row.marker) }}
              </div>
              <div class="flex flex-wrap gap-2">
                <YdButton size="sm" variant="outline" @click="openEditDialog(row)">编辑</YdButton>
                <YdButton size="sm" variant="destructive" @click="removeMarker(row)">删除</YdButton>
              </div>
            </div>
          </template>
          <template #empty>
            <span v-if="filtersActive" class="text-muted-foreground">没有符合筛选条件的标注，可调整或重置筛选</span>
            <span v-else class="text-muted-foreground">暂无标注，点右上角「新增点位 / 新增范围」添加，或到查看页添加点位 / 范围标注</span>
          </template>
        </YdResponsiveTable>
      </YdCard>

      <!-- 新增 / 编辑弹窗 -->
      <YdModal v-model="dialogOpen" :title="dialog.mode === 'create' ? '新增标注' : '编辑标注'">
        <div class="grid gap-4">
          <label class="grid gap-1.5">
            <span class="text-sm">名称</span>
            <YdInput v-model="dialog.label" placeholder="例如：村庄入口" />
          </label>
          <label class="grid gap-1.5">
            <span class="text-sm">标注集</span>
            <YdSelect
              v-model="dialog.setId"
              :options="[...setOptions, { label: '＋ 新建标注集…', value: '__new__' }]"
            />
          </label>
          <label v-if="dialog.setId === '__new__'" class="grid gap-1.5">
            <span class="text-sm">新标注集名称</span>
            <YdInput v-model="dialog.newSetName" placeholder="例如：建筑组" />
          </label>

          <template v-if="dialog.markerType === 'shape'">
            <div class="grid gap-2">
              <label class="grid gap-1.5">
                <span class="text-sm">贴地高度 Y（标注面悬挂的世界高度）</span>
                <YdNumberField v-model="dialog.shapeY" :step="1" />
              </label>
            </div>
          </template>
          <template v-if="dialogIsPoi">
            <!-- 忽略 Y 轴时只有 X/Z 两列。YdNumberField 内置 w-[200px]，且网格子项默认 min-width:auto
                 会沿 min-content 链拒绝收缩压到相邻列：逐层 min-w-0 断开 -->
            <div class="grid gap-3" :class="dialog.ignoreY ? 'grid-cols-2' : 'grid-cols-3'">
              <label class="grid min-w-0 gap-1.5">
                <span class="text-sm">X</span>
                <YdNumberField v-model="dialog.x" :step="1" class="w-full min-w-0" />
              </label>
              <label v-if="!dialog.ignoreY" class="grid min-w-0 gap-1.5">
                <span class="text-sm">Y</span>
                <YdNumberField v-model="dialog.y" :step="1" class="w-full min-w-0" />
              </label>
              <label class="grid min-w-0 gap-1.5">
                <span class="text-sm">Z</span>
                <YdNumberField v-model="dialog.z" :step="1" class="w-full min-w-0" />
              </label>
            </div>
            <label class="flex items-center gap-2 text-sm">
              <YdCheckbox v-model="dialog.ignoreY" />
              忽略 Y 轴{{ dialog.mode === 'edit' ? '（保留原 Y 值）' : '（按 Y = 64 落盘）' }}
            </label>
          </template>
          <template v-else-if="dialog.markerType === 'shape'">
            <div class="grid gap-2">
              <span class="text-sm">多边形顶点（{{ dialog.verts.length }} 个）</span>
              <div
                v-for="(vert, index) in dialog.verts"
                :key="index"
                class="flex items-center gap-2"
              >
                <span class="w-8 shrink-0 text-xs text-muted-foreground">#{{ index + 1 }}</span>
                <YdNumberField
                  :model-value="vert.x"
                  :step="1"
                  class="w-full min-w-0"
                  @update:model-value="(v: number | undefined) => { vert.x = v ?? 0 }"
                />
                <YdNumberField
                  :model-value="vert.z"
                  :step="1"
                  class="w-full min-w-0"
                  @update:model-value="(v: number | undefined) => { vert.z = v ?? 0 }"
                />
                <YdButton
                  size="sm"
                  variant="ghost"
                  title="删除该顶点"
                  :disabled="dialog.verts.length <= 3"
                  @click="dialog.verts.splice(index, 1)"
                >
                  <YdIcon name="i-mdi:close" />
                </YdButton>
              </div>
              <YdButton size="sm" variant="outline" class="justify-self-start" @click="addDialogVertex">
                <YdIcon name="i-mdi:plus" />
                添加顶点
              </YdButton>
            </div>
          </template>
          <p v-else class="rounded-md border border-border bg-muted/40 p-2 text-xs text-muted-foreground">
            该标注为{{ MARKER_TYPE_META[dialog.markerType]?.label ?? dialog.markerType }}类型，几何坐标在查看页绘制，这里只能改名称与标注集。
          </p>
        </div>

        <template #footer>
          <div class="flex justify-end gap-2">
            <YdButton variant="outline" @click="dialogOpen = false">
              取消
            </YdButton>
            <YdButton @click="saveDialog">
              确定
            </YdButton>
          </div>
        </template>
      </YdModal>
    </template>
  </YdPageMain>
</template>
