<script setup lang="ts">
import type { MaterialPluginModel } from '../composables/useMaterialPlugin'
import type { MaterialItemView, VersionView } from '../types'
import { FaButton, FaCard, FaIcon, FaPageHeader, FaPageMain, FaSelect, useFaModal } from '@yudream/components'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import MaterialItemManager from '../components/MaterialItemManager.vue'
import MaterialVersionsTable from '../components/MaterialVersionsTable.vue'
import NewVersionModal from '../components/NewVersionModal.vue'
import PreviewFrame from '../components/PreviewFrame.vue'
import ShareModal from '../components/ShareModal.vue'
import { formatSize, visibilityLabel } from '../types'

const props = defineProps<{ model: MaterialPluginModel }>()
const model = props.model
const route = useRoute()
const router = useRouter()
const confirm = useFaModal()

const materialId = computed(() => String(route.query.id || ''))
const newVersionOpen = ref(false)
const shareOpen = ref(false)
const saving = ref(false)
/**
 * 预览目标：'' 为父物料主文件（仅单文件物料有），否则为子物料 id。
 * 与子物料管理区的 v-model:active-id 同源——在预览区切目标会同步选中版本区，反之亦然。
 */
const previewTarget = ref('')
/** 预览版本；空串表示当前版本 */
const previewVersion = ref('')

/** 他人物料默认只读：可预览/下载；有管理权限者可代传新版本、代管分享（回滚/删版本仍仅属主） */
const isOwner = computed(() => !!model.detail && model.isOwner(model.detail.material))
/** 上传新版本/分享入口：属主走 /me，管理者走 /admin 代操作（命名避开 CI 的 canManage 数据集切换启发式——这里不切换数据集，只是对已可见物料开放管理操作） */
const canOperateEntry = computed(() => isOwner.value || model.hasManage)
/** 是否有主文件：false 即组合物料，父物料自身没有版本链；在线预览与下载改由「预览主文件」所指子物料承载 */
const hasMainFile = computed(() => !!model.detail?.material.mainFilePresent)
/** 组合物料指定的预览主文件（子物料 id）；空串表示未指定 */
const previewItemId = computed(() => model.detail?.material.previewItemId || '')
/** 预览主文件的子物料名，用于文案展示（指针失效或未指定时为空） */
const designatedName = computed(() => model.items.find(item => item.id === previewItemId.value)?.name || '')
const activeItem = computed(() => model.items.find(item => item.id === previewTarget.value) || null)
/** 有可下载的内容：带主文件的物料下载当前主版本，组合物料下载预览主文件（未指定时第一个子物料） */
const downloadable = computed(() => hasMainFile.value || model.items.length > 0)
const downloadLabel = computed(() => hasMainFile.value ? '下载当前版本' : '下载预览主文件')

const previewTargetOptions = computed(() => [
  ...(hasMainFile.value ? [{ label: '主文件', value: '' }] : []),
  ...model.items.map(item => ({
    label: item.id === previewItemId.value ? `预览主文件：${item.name}` : `子物料：${item.name}`,
    value: item.id,
  })),
])

const previewVersionOptions = computed(() => {
  const list = previewTarget.value ? model.itemVersions : model.versions
  return list.map(version => ({
    label: `v${version.version}${version.current ? '（当前）' : ''}`,
    value: String(version.version),
  }))
})

/** 预览区的类型/扩展名跟随目标，否则子物料的 PSD/PNG 会按父物料类型渲染。 */
const previewType = computed(() => activeItem.value ? activeItem.value.type : model.detail?.material.type)
const previewExt = computed(() => activeItem.value ? activeItem.value.ext : model.detail?.material.ext)

const headerDescription = computed(() => {
  const material = model.detail?.material
  if (!material) {
    return ''
  }
  const designated = model.items.find(item => item.id === previewItemId.value)
  const parts = [
    hasMainFile.value
      ? `${material.typeLabel} · .${material.ext} · ${formatSize(material.size)} · 当前 v${material.currentVersion}`
      : `组合物料 · ${model.items.length} 个子物料 · 各自独立版本${designated ? ` · 预览主文件：${designated.name}` : ' · 未指定预览主文件'}`,
    `${visibilityLabel(material.visibility)}可见`,
    `上传者 ${material.ownerName || '-'}`,
  ]
  return parts.join(' · ')
})

async function reloadPreview() {
  if (!materialId.value) {
    return
  }
  const version = previewVersion.value ? Number(previewVersion.value) : undefined
  if (previewTarget.value) {
    await model.loadItemPreview(materialId.value, previewTarget.value, version)
  }
  else {
    await model.loadPreview(materialId.value, version)
  }
}

/**
 * 同步 watcher：子组件先改 v-model 再 emit 版本号，异步 watcher 的刷新顺序无法保证，
 * 会出现「点了 v2 却显示当前版本」的错配，故让两次赋值按调用顺序立即生效。
 */
watch(previewTarget, () => {
  previewVersion.value = ''
  void reloadPreview()
}, { flush: 'sync' })

watch(previewVersion, () => {
  void reloadPreview()
}, { flush: 'sync' })

/** 子物料表格/版本区发来的预览请求：activeId 已由 v-model 同步，这里补上版本号。 */
function onItemPreview(payload: { item: MaterialItemView, version: number }) {
  previewVersion.value = String(payload.version)
}

async function submitNewVersion(payload: { fileId: string, filename: string, note: string }) {
  saving.value = true
  try {
    if (isOwner.value) {
      await model.uploadNewVersion(materialId.value, payload.fileId, payload.filename, payload.note)
    }
    else {
      await model.adminUploadNewVersion(materialId.value, payload.fileId, payload.filename, payload.note)
    }
    newVersionOpen.value = false
    previewVersion.value = ''
  }
  finally {
    saving.value = false
  }
}

function previewMainVersion(row: VersionView) {
  previewTarget.value = ''
  previewVersion.value = String(row.version)
}

function downloadMainVersion(row: VersionView) {
  if (model.detail) {
    void model.downloadMine(model.detail.material, row.version)
  }
}

function confirmRestore(row: VersionView) {
  confirm.confirm({
    title: '回滚版本',
    content: `确认把当前版本切换回 v${row.version} 吗？现有版本保留，只是当前指针移动。`,
    onConfirm: () => model.restoreVersion(materialId.value, row.version),
  })
}

function confirmDeleteVersion(row: VersionView) {
  confirm.confirm({
    title: '删除版本',
    content: `确认删除 v${row.version}（${row.originalName || ''}）吗？该版本文件将一并删除，不可恢复。`,
    onConfirm: () => model.removeVersion(materialId.value, row.version),
  })
}

onMounted(async () => {
  if (!materialId.value) {
    void router.replace('/platform/plugins/material')
    return
  }
  await model.loadDetail(materialId.value)
  await model.loadItems(materialId.value)
  // 组合物料没有主文件：默认预览指定的预览主文件，未指定时退到第一个子物料，避免预览区空白
  if (!hasMainFile.value && model.items.length) {
    const designated = previewItemId.value
    previewTarget.value = designated && model.items.some(item => item.id === designated)
      ? designated
      : model.items[0].id
  }
  await reloadPreview()
})
</script>

<template>
  <template v-if="model.detail">
    <FaPageHeader :title="model.detail.material.name" :description="headerDescription">
      <FaButton variant="outline" @click="router.push('/platform/plugins/material')"><FaIcon name="i-ri:arrow-left-line" />返回物料库</FaButton>
      <FaButton v-if="downloadable" variant="outline" @click="model.downloadMine(model.detail.material)"><FaIcon name="i-ri:download-line" />{{ downloadLabel }}</FaButton>
      <FaButton v-if="canOperateEntry && hasMainFile" variant="outline" @click="shareOpen = true"><FaIcon name="i-ri:share-forward-line" />分享</FaButton>
      <FaButton v-if="canOperateEntry && hasMainFile" @click="newVersionOpen = true"><FaIcon name="i-ri:upload-cloud-2-line" />上传新版本</FaButton>
    </FaPageHeader>
    <FaPageMain>
      <div class="material-detail-layout">
        <FaCard class="material-preview-card">
          <div class="mb-3 flex flex-wrap items-center justify-between gap-2">
            <strong class="text-sm">在线预览</strong>
            <div class="flex flex-wrap items-center gap-2">
              <FaSelect v-if="previewTargetOptions.length > 1" v-model="previewTarget" :options="previewTargetOptions" class="w-52" />
              <FaSelect v-model="previewVersion" :options="previewVersionOptions" class="w-40" placeholder="当前版本" />
            </div>
          </div>
          <PreviewFrame
            :sdk="model.sdk"
            :info="model.preview"
            :loading="model.previewLoading"
            :material-type="previewType"
            :ext="previewExt"
          />
        </FaCard>
        <FaCard v-if="hasMainFile">
          <MaterialVersionsTable
            :versions="model.versions"
            :can-write="isOwner"
            :can-upload="canOperateEntry"
            @preview="previewMainVersion"
            @download="downloadMainVersion"
            @restore="confirmRestore"
            @remove="confirmDeleteVersion"
            @upload="newVersionOpen = true"
          >
            <template #title>
              <strong class="text-sm">主文件版本历史</strong>
              <span class="material-item-versions-tag">只影响父物料主文件</span>
            </template>
          </MaterialVersionsTable>
        </FaCard>
        <FaCard v-else class="material-bundle-card">
          <div class="flex flex-col gap-2">
            <strong class="text-sm">组合物料</strong>
            <p class="text-sm text-secondary-foreground/80">
              这个物料本身不带文件，全部内容由下方子物料承载。子物料各自拥有独立的版本链，
              升级其中一个（如「设计稿」）不会影响其他子物料（如「原图」「成图」）。
            </p>
            <p class="text-sm text-secondary-foreground/80">
              父物料的在线预览与「{{ downloadLabel }}」使用<strong>预览主文件</strong>：
              {{ designatedName ? `当前为「${designatedName}」` : '当前未指定，暂用第一个子物料' }}。
              在下方子物料行点「设为主文件」即可改指定；主文件始终跟随该子物料的当前版本，子物料升级后自动同步。
            </p>
            <div class="flex flex-wrap gap-2">
              <span class="material-hint">子物料的新增、上传新版本、回滚与删除都在下方「子物料」区完成；分享链接仍只支持带主文件的物料，暂不支持组合物料。</span>
            </div>
          </div>
        </FaCard>
      </div>
      <FaCard class="material-detail-items">
        <MaterialItemManager
          v-model:active-id="previewTarget"
          :model="model"
          :material-id="materialId"
          :readonly="!canOperateEntry"
          :preview-item-id="previewItemId"
          @preview="onItemPreview"
        />
      </FaCard>
    </FaPageMain>
    <NewVersionModal v-if="hasMainFile" v-model="newVersionOpen" :sdk="model.sdk" :saving="saving" @submit="submitNewVersion" />
    <ShareModal v-model="shareOpen" :material="model.detail.material" :model="model" :admin="!isOwner" />
  </template>
  <template v-else>
    <FaPageHeader title="物料详情" />
    <FaPageMain>
      <div class="flex flex-col items-center gap-3 py-16 text-secondary-foreground/70">
        <FaIcon name="i-ri:loader-4-line" class="animate-spin text-2xl" />
        <span v-if="model.loading">正在加载…</span>
        <template v-else>
          <span>物料不存在或没有权限</span>
          <FaButton variant="outline" @click="router.push('/platform/plugins/material')">返回物料库</FaButton>
        </template>
      </div>
    </FaPageMain>
  </template>
</template>
