<script setup lang="ts">
import type { MaterialPluginModel } from '../composables/useMaterialPlugin'
import type { MaterialItemView, VersionView } from '../types'
import { FaButton, FaIcon, FaInput, FaModal, useFaModal } from '@yudream/components'
import { computed, ref, watch } from 'vue'
import MaterialItemModal from './MaterialItemModal.vue'
import MaterialItemsTable from './MaterialItemsTable.vue'
import MaterialVersionsTable from './MaterialVersionsTable.vue'

/**
 * 子物料管理区（详情页与管理端抽屉共用）：
 * 上方为子物料表格，下方展示当前选中子物料自己的版本链——每个子物料独立版本，互不影响。
 * activeId 为「当前查看版本链的子物料」，由父组件双向绑定（详情页同时用它驱动预览目标）。
 */
const props = defineProps<{
  model: MaterialPluginModel
  materialId: string
  /** 管理端：全部读写走 /admin 代操作接口 */
  admin?: boolean
  /** 只读：他人物料且当前用户无管理权限，隐藏全部写操作 */
  readonly?: boolean
  /** 父物料当前的预览主文件（子物料 id）；空串表示未指定 */
  previewItemId?: string
}>()
const activeId = defineModel<string>('activeId', { default: '' })
const emit = defineEmits<{ preview: [{ item: MaterialItemView, version: number }] }>()
const confirm = useFaModal()

/**
 * 预览主文件的本地镜像：以父组件传入的 prop 为准，设置成功后立即更新，
 * 免得等在途的详情刷新导致按钮状态滞后（服务端始终是唯一事实来源）。
 */
const previewItemId = ref(props.previewItemId || '')
watch(() => props.previewItemId, (value) => {
  previewItemId.value = value || ''
})

const itemModalOpen = ref(false)
const itemModalMode = ref<'create' | 'version'>('create')
const itemModalTarget = ref<MaterialItemView | null>(null)
const renameOpen = ref(false)
const renameTarget = ref<MaterialItemView | null>(null)
const renameName = ref('')
const saving = ref(false)

const isAdmin = computed(() => !!props.admin)
const canWrite = computed(() => !props.readonly)
const activeItem = computed(() => props.model.items.find(item => item.id === activeId.value) || null)

/** 切换目标即拉取该子物料的版本链；清空（切回主文件）时由 composable 清版本列表。 */
watch(activeId, (value) => {
  if (!props.materialId) {
    return
  }
  void props.model.loadItemVersions(props.materialId, value, isAdmin.value)
})

/** 换物料时清掉旧目标，避免沿用上一个物料的子物料 id。 */
watch(() => props.materialId, () => {
  activeId.value = ''
})

function openCreate() {
  itemModalMode.value = 'create'
  itemModalTarget.value = null
  itemModalOpen.value = true
}

function openNewVersion(item: MaterialItemView) {
  itemModalMode.value = 'version'
  itemModalTarget.value = item
  itemModalOpen.value = true
}

/** 版本区标题栏的「上传新版本」：目标即当前选中的子物料。 */
function openActiveVersion() {
  if (activeItem.value) {
    openNewVersion(activeItem.value)
  }
}

async function submitItem(payload: { fileId: string, filename: string, name: string, note: string }) {
  saving.value = true
  try {
    if (itemModalMode.value === 'create') {
      const created = await props.model.createItem(props.materialId, payload.fileId, payload.filename, payload.name, isAdmin.value)
      activeId.value = created.item.id
      await props.model.refreshItems(props.materialId, isAdmin.value, created.item.id)
    }
    else {
      const target = itemModalTarget.value
      if (!target) {
        return
      }
      await props.model.uploadItemVersion(props.materialId, target.id, payload.fileId, payload.filename, payload.note, isAdmin.value)
      await props.model.refreshItems(props.materialId, isAdmin.value, target.id)
    }
    itemModalOpen.value = false
  }
  finally {
    saving.value = false
  }
}

function openRename(item: MaterialItemView) {
  renameTarget.value = item
  renameName.value = item.name
  renameOpen.value = true
}

async function submitRename() {
  const target = renameTarget.value
  const name = renameName.value.trim()
  if (!target || !name) {
    return
  }
  saving.value = true
  try {
    await props.model.renameItem(props.materialId, target.id, name, isAdmin.value)
    renameOpen.value = false
    await props.model.loadItems(props.materialId, isAdmin.value)
  }
  finally {
    saving.value = false
  }
}

function confirmRemoveItem(item: MaterialItemView) {
  confirm.confirm({
    title: '删除子物料',
    content: `确认删除子物料「${item.name}」吗？它的全部 ${item.currentVersion} 个版本与文件都会删除，不可恢复；父物料与其他子物料不受影响。`,
    onConfirm: async () => {
      await props.model.removeItem(props.materialId, item.id, item.name, isAdmin.value)
      if (activeId.value === item.id) {
        activeId.value = ''
      }
      await props.model.refreshItems(props.materialId, isAdmin.value, activeId.value)
    },
  })
}

/** 表格里点「版本」：选中该子物料，同时把详情页预览切到它。 */
function selectItem(item: MaterialItemView) {
  activeId.value = item.id
  emit('preview', { item, version: item.currentVersion })
}

/** 指定预览主文件：组合物料本身没有主文件，用它决定父物料在线预览/下载时取哪个子物料。 */
async function setPreviewItem(item: MaterialItemView) {
  if (await props.model.setPreviewItem(props.materialId, item.id, isAdmin.value)) {
    previewItemId.value = item.id
    // 顺手把版本区与预览切到它，让「设为主文件」的结果立刻可见
    selectItem(item)
  }
}

async function clearPreviewItem() {
  if (await props.model.setPreviewItem(props.materialId, '', isAdmin.value)) {
    previewItemId.value = ''
  }
}

function previewVersion(version: VersionView) {
  if (activeItem.value) {
    emit('preview', { item: activeItem.value, version: version.version })
  }
}

function downloadVersion(version: VersionView) {
  if (activeItem.value) {
    void props.model.downloadItem(props.materialId, activeItem.value.id, `${activeItem.value.name}-v${version.version}`, version.version, isAdmin.value)
  }
}

function confirmRestore(version: VersionView) {
  const item = activeItem.value
  if (!item) {
    return
  }
  confirm.confirm({
    title: '回滚子物料版本',
    content: `确认把「${item.name}」的当前版本切换回 v${version.version} 吗？现有版本保留，只是当前指针移动；其他子物料不受影响。`,
    onConfirm: async () => {
      await props.model.restoreItemVersion(props.materialId, item.id, version.version, isAdmin.value)
      await props.model.refreshItems(props.materialId, isAdmin.value, item.id)
    },
  })
}

function confirmRemoveVersion(version: VersionView) {
  const item = activeItem.value
  if (!item) {
    return
  }
  confirm.confirm({
    title: '删除子物料版本',
    content: `确认删除「${item.name}」的 v${version.version}（${version.originalName || ''}）吗？该版本文件将一并删除，不可恢复。`,
    onConfirm: async () => {
      await props.model.removeItemVersion(props.materialId, item.id, version.version, isAdmin.value)
      await props.model.refreshItems(props.materialId, isAdmin.value, item.id)
    },
  })
}
</script>

<template>
  <div class="material-item-manager">
    <div class="mb-3 flex flex-wrap items-center justify-between gap-2">
      <div class="flex items-center gap-2">
        <strong class="text-sm">子物料</strong>
        <span class="text-xs text-secondary-foreground/70">共 {{ props.model.items.length }} 个，各自独立管理版本</span>
        <span v-if="props.model.items.length" class="text-xs text-secondary-foreground/60">行内「设为主文件」指定父物料预览与下载使用哪个子物料</span>
      </div>
      <FaButton v-if="canWrite" size="sm" variant="outline" @click="openCreate">
        <FaIcon name="i-ri:add-line" />新增子物料
      </FaButton>
    </div>
    <MaterialItemsTable
      :items="props.model.items"
      :loading="props.model.itemsLoading"
      :active-id="activeId"
      :readonly="props.readonly"
      :preview-item-id="previewItemId"
      @versions="selectItem"
      @new-version="openNewVersion"
      @rename="openRename"
      @remove="confirmRemoveItem"
      @set-preview="setPreviewItem"
      @clear-preview="clearPreviewItem"
      @download="item => props.model.downloadItem(props.materialId, item.id, item.name, item.currentVersion, isAdmin)"
    />
    <div v-if="!props.model.items.length && !props.model.itemsLoading" class="material-item-empty">
      <FaIcon name="i-ri:stack-line" class="material-empty-icon" />
      <p>还没有子物料</p>
      <p class="material-item-empty-hint">
        子物料是父物料下的具名文件槽，可分别上传与独立升级版本（例如「明信片」下挂原图 png、设计稿 psd、成图 png）。
      </p>
      <FaButton v-if="canWrite" variant="outline" @click="openCreate"><FaIcon name="i-ri:add-line" />新增子物料</FaButton>
    </div>

    <template v-if="activeItem">
      <div class="material-item-versions">
        <MaterialVersionsTable
          :versions="props.model.itemVersions"
          :can-write="canWrite"
          :can-upload="canWrite"
          @preview="previewVersion"
          @download="downloadVersion"
          @restore="confirmRestore"
          @remove="confirmRemoveVersion"
          @upload="openActiveVersion"
        >
          <template #title>
            <strong class="text-sm">「{{ activeItem.name }}」的版本历史</strong>
            <span class="material-item-versions-tag">独立版本链 · 当前 v{{ activeItem.currentVersion }}</span>
          </template>
        </MaterialVersionsTable>
      </div>
    </template>

    <MaterialItemModal
      v-model="itemModalOpen"
      :sdk="props.model.sdk"
      :mode="itemModalMode"
      :item-name="itemModalTarget?.name"
      :saving="saving"
      @submit="submitItem"
    />
    <FaModal
      v-model="renameOpen"
      title="重命名子物料"
      description="只修改展示名称，当前文件名与全部历史版本不受影响"
      :confirm-button-loading="saving"
      :confirm-button-disabled="!renameName.trim()"
      @confirm="submitRename"
    >
      <label class="flex flex-col gap-1 text-sm">
        <span class="text-secondary-foreground/80">子物料名称</span>
        <FaInput v-model="renameName" maxlength="60" placeholder="如「原图」「设计稿」「成图」" />
      </label>
    </FaModal>
  </div>
</template>
