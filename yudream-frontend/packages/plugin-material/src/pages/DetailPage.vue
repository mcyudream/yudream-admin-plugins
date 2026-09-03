<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { MaterialPluginModel } from '../composables/useMaterialPlugin'
import type { VersionView } from '../types'
import { FaButton, FaCard, FaIcon, FaPageHeader, FaPageMain, FaResponsiveTable, FaSelect, FaTag, useFaModal } from '@yudream/components'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import NewVersionModal from '../components/NewVersionModal.vue'
import PreviewFrame from '../components/PreviewFrame.vue'
import ShareModal from '../components/ShareModal.vue'
import { formatSize, formatTime, visibilityLabel } from '../types'

const props = defineProps<{ model: MaterialPluginModel }>()
const model = props.model
const route = useRoute()
const router = useRouter()
const confirm = useFaModal()

const materialId = computed(() => String(route.query.id || ''))
const newVersionOpen = ref(false)
const shareOpen = ref(false)
const saving = ref(false)
/** 预览中的版本；空串表示当前版本 */
const previewVersion = ref('')
/** 他人物料只读：可预览/下载，变更操作仅属主可用 */
const isOwner = computed(() => !!model.detail && model.isOwner(model.detail.material))

const previewVersionOptions = computed(() => {
  const options = model.versions.map(version => ({
    label: `v${version.version}${version.current ? '（当前）' : ''}`,
    value: String(version.version),
  }))
  return options
})

const versionColumns: TableColumn<VersionView>[] = [
  { id: 'version', header: '版本', width: 90 },
  { accessorKey: 'originalName', header: '文件名', minWidth: 180 },
  { id: 'size', header: '大小', width: 100 },
  { accessorKey: 'note', header: '备注', minWidth: 140 },
  { accessorKey: 'uploaderName', header: '上传者', width: 110 },
  { accessorKey: 'createdAt', header: '上传时间', width: 170 },
  { id: 'operation', header: '操作', width: 250 },
]

watch(previewVersion, (value) => {
  if (!materialId.value) {
    return
  }
  void model.loadPreview(materialId.value, value ? Number(value) : undefined)
})

async function submitNewVersion(payload: { fileId: string, filename: string, note: string }) {
  saving.value = true
  try {
    await model.uploadNewVersion(materialId.value, payload.fileId, payload.filename, payload.note)
    newVersionOpen.value = false
    previewVersion.value = ''
  }
  finally {
    saving.value = false
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
  await model.loadPreview(materialId.value)
})
</script>

<template>
  <template v-if="model.detail">
    <FaPageHeader :title="model.detail.material.name" :description="`${model.detail.material.typeLabel} · .${model.detail.material.ext} · ${formatSize(model.detail.material.size)} · 当前 v${model.detail.material.currentVersion} · ${visibilityLabel(model.detail.material.visibility)}可见 · 上传者 ${model.detail.material.ownerName || '-'}`">
      <FaButton variant="outline" @click="router.push('/platform/plugins/material')"><FaIcon name="i-ri:arrow-left-line" />返回物料库</FaButton>
      <FaButton variant="outline" @click="model.downloadMine(model.detail.material)"><FaIcon name="i-ri:download-line" />下载当前版本</FaButton>
      <FaButton v-if="isOwner" variant="outline" @click="shareOpen = true"><FaIcon name="i-ri:share-forward-line" />分享</FaButton>
      <FaButton v-if="isOwner" @click="newVersionOpen = true"><FaIcon name="i-ri:upload-cloud-2-line" />上传新版本</FaButton>
    </FaPageHeader>
    <FaPageMain>
      <div class="material-detail-layout">
        <FaCard class="material-preview-card">
          <div class="mb-3 flex flex-wrap items-center justify-between gap-2">
            <strong class="text-sm">在线预览</strong>
            <FaSelect v-model="previewVersion" :options="previewVersionOptions" class="w-44" placeholder="当前版本" />
          </div>
          <PreviewFrame
            :sdk="model.sdk"
            :info="model.preview"
            :loading="model.previewLoading"
            :material-type="model.detail.material.type"
            :ext="model.detail.material.ext"
          />
        </FaCard>
        <FaCard>
          <div class="mb-3 flex items-center justify-between gap-2">
            <strong class="text-sm">版本历史</strong>
            <span class="text-xs text-secondary-foreground/70">共 {{ model.versions.length }} 个版本</span>
          </div>
          <FaResponsiveTable
            :columns="versionColumns"
            :data="model.versions"
            row-key="version"
            table-root-class="max-w-full overflow-x-auto rounded-lg"
            table-class="min-w-[980px]"
            border stripe
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
                <FaButton size="sm" variant="outline" @click="previewVersion = String(row.original.version)">预览</FaButton>
                <FaButton size="sm" variant="outline" @click="model.downloadMine(model.detail!.material, row.original.version)">下载</FaButton>
                <FaButton v-if="isOwner && !row.original.current" size="sm" variant="outline" @click="confirmRestore(row.original)">回滚到此</FaButton>
                <FaButton v-if="isOwner && !row.original.current" size="sm" variant="destructive" @click="confirmDeleteVersion(row.original)">删除</FaButton>
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
                  <FaButton size="sm" variant="outline" @click="previewVersion = String(row.version)">预览</FaButton>
                  <FaButton size="sm" variant="outline" @click="model.downloadMine(model.detail!.material, row.version)">下载</FaButton>
                  <FaButton v-if="isOwner && !row.current" size="sm" variant="outline" @click="confirmRestore(row)">回滚到此</FaButton>
                  <FaButton v-if="isOwner && !row.current" size="sm" variant="destructive" @click="confirmDeleteVersion(row)">删除</FaButton>
                </div>
              </div>
            </template>
          </FaResponsiveTable>
        </FaCard>
      </div>
    </FaPageMain>
    <NewVersionModal v-model="newVersionOpen" :sdk="model.sdk" :saving="saving" @submit="submitNewVersion" />
    <ShareModal v-model="shareOpen" :material="model.detail.material" :model="model" />
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
