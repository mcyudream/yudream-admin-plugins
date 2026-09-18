<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { ManifestFileEntry, PackDoc, PackVersionSummary } from '../types'
import { FaButton, FaCard, FaDrawer, FaIcon, FaInput, FaModal, FaPageHeader, FaPageMain, FaTable, FaTag, FaTextarea, useFaModal, useFaToast } from '@yudream/components'
import type { TableColumn } from '@yudream/components'
import { computed, onMounted, reactive, ref } from 'vue'
import FilePreviewDrawer from '../components/FilePreviewDrawer.vue'
import JsonViewDrawer from '../components/JsonViewDrawer.vue'
import { useYmclAdapter } from '../composables/useYmclAdapter'
import { formatSize, formatTime } from '../composables/ymcl-protocol'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const model = useYmclAdapter(props.sdk)
const toast = useFaToast()
const confirm = useFaModal()

const versionsDrawer = ref(false)
const manifestDrawer = ref(false)
const jsonDrawer = ref(false)
const editOpen = ref(false)
const previewOpen = ref(false)
const previewTarget = ref<{ sha512: string, path: string }>({ sha512: '', path: '' })
const activePack = ref<PackDoc | null>(null)
const activeVersions = ref<PackVersionSummary[]>([])
const versionsLoading = ref(false)
const activeVersion = ref<{ packId: string, version: string } | null>(null)
const versionDetail = ref<Record<string, unknown> | null>(null)
const detailLoading = ref(false)

const editForm = reactive({ packId: '', name: '', description: '', icon: '', defaultChannel: '' })

const packColumns: TableColumn<PackDoc>[] = [
  { accessorKey: 'packId', header: '整合包 ID', minWidth: 180 },
  { accessorKey: 'name', header: '名称', minWidth: 160 },
  { id: 'versionCount', header: '版本数', width: 90 },
  { id: 'latest', header: '最新版本', minWidth: 140 },
  { id: 'operations', header: '操作', width: 280, fixed: 'right' },
]

const versionColumns: TableColumn<PackVersionSummary>[] = [
  { accessorKey: 'version', header: '版本', minWidth: 140 },
  { accessorKey: 'channel', header: '渠道', width: 100 },
  { id: 'releasedAt', header: '发布时间', width: 180 },
  { id: 'operations', header: '操作', width: 200, fixed: 'right' },
]

const fileColumns: TableColumn<ManifestFileEntry>[] = [
  { accessorKey: 'path', header: '文件路径', minWidth: 240 },
  { accessorKey: 'sha512', header: 'sha512', width: 130 },
  { accessorKey: 'size', header: '大小', width: 100 },
  { accessorKey: 'policy', header: '策略', width: 100 },
  { id: 'operations', header: '操作', width: 120, fixed: 'right' },
]

function packVersionCount(row: PackDoc) {
  return row.versions?.length || 0
}

function packLatest(row: PackDoc) {
  const versions = row.versions || []
  return versions.length ? versions[versions.length - 1].version : '-'
}

function openEdit(row: PackDoc) {
  editForm.packId = row.packId
  editForm.name = row.name || ''
  editForm.description = row.description || ''
  editForm.icon = row.icon || ''
  editForm.defaultChannel = row.defaultChannel || ''
  editOpen.value = true
}

async function saveEdit() {
  if (!editForm.packId) {
    return
  }
  if (await model.updatePack(editForm.packId, { ...editForm })) {
    editOpen.value = false
  }
}

function confirmDeletePack(row: PackDoc) {
  confirm.confirm({
    title: '删除整合包',
    content: `确认删除「${row.packId}」吗？将同时删除其全部版本文档，并解除引用该包的服务器绑定。CAS 对象因可能被其他版本共享，删除后不会回收。此操作不可撤销。`,
    onConfirm: () => model.deletePack(row.packId),
  })
}

function confirmDeleteVersion(row: PackVersionSummary) {
  if (!activePack.value) {
    return
  }
  const packId = activePack.value.packId
  confirm.confirm({
    title: '删除版本',
    content: `确认删除「${packId}」的版本 ${row.version} 吗？若有服务器仍 pin 在该版本会拒绝删除。`,
    onConfirm: async () => {
      const versions = await model.deletePackVersion(packId, row.version)
      if (versions) {
        activeVersions.value = versions
        await model.loadPacks()
      }
    },
  })
}

async function openVersions(row: PackDoc) {
  activePack.value = row
  versionsDrawer.value = true
  versionsLoading.value = true
  try {
    const result = await model.rawGet<{ versions: PackVersionSummary[] }>(
      `/mip/api/packs/${encodeURIComponent(row.packId)}/versions`,
    )
    activeVersions.value = result.versions || []
  }
  catch (error) {
    activeVersions.value = []
    toast.error(`版本清单加载失败：${error instanceof Error ? error.message : '未知错误'}`)
  }
  finally {
    versionsLoading.value = false
  }
}

async function openManifest(row: PackVersionSummary) {
  if (!activePack.value) {
    return
  }
  activeVersion.value = { packId: activePack.value.packId, version: row.version }
  manifestDrawer.value = true
  detailLoading.value = true
  try {
    const doc = await model.rawGet<Record<string, unknown>>(
      `/mip/api/packs/${encodeURIComponent(activePack.value.packId)}/manifest/${encodeURIComponent(row.version)}`,
    )
    versionDetail.value = doc
  }
  catch (error) {
    versionDetail.value = null
    toast.error(`manifest 加载失败：${error instanceof Error ? error.message : '未知错误'}`)
  }
  finally {
    detailLoading.value = false
  }
}

const versionFiles = computed<ManifestFileEntry[]>(
  () => (versionDetail.value?.manifest as { files?: ManifestFileEntry[] } | undefined)?.files || [],
)

function openFilePreview(file: ManifestFileEntry) {
  if (!file.sha512) {
    toast.warning('该条目缺少 sha512，无法预览')
    return
  }
  previewTarget.value = { sha512: String(file.sha512), path: file.path || '' }
  previewOpen.value = true
}

function fileCount() {
  return versionFiles.value.length
}

function totalSize() {
  return versionFiles.value.reduce((sum, file) => sum + (Number(file.size) || 0), 0)
}

onMounted(() => {
  void model.loadPacks()
})
</script>

<template>
  <div class="ymcl-page">
    <FaPageHeader title="整合包分发" description="MIP 分发面的 pack 与版本清单。版本发布后不可变；此处可维护展示信息、清理版本并预览包内文件。" />

    <FaPageMain>
      <FaCard content-class="ymcl-card-content">
        <FaTable
          table-root-class="max-w-full overflow-x-auto rounded-lg overflow-hidden"
          v-loading="model.loading"
          :columns="packColumns"
          :data="model.packs"
          row-key="packId"
        >
          <template #empty>
            <span class="ymcl-muted">暂无整合包。通过 YMCL 启动器发布控制台推送后在此可见。</span>
          </template>
          <template #cell-name="{ row }">
            <div class="ymcl-pack-name">
              <span>{{ row.original.name || '-' }}</span>
              <span v-if="row.original.description" class="ymcl-muted">{{ row.original.description }}</span>
            </div>
          </template>
          <template #cell-versionCount="{ row }">
            {{ packVersionCount(row.original) }}
          </template>
          <template #cell-latest="{ row }">
            <FaTag v-if="packLatest(row.original) !== '-'" variant="secondary">{{ packLatest(row.original) }}</FaTag>
            <span v-else>-</span>
          </template>
          <template #cell-operations="{ row }">
            <div class="ymcl-row-actions">
              <FaButton size="sm" variant="outline" @click="openVersions(row.original)">
                <FaIcon name="i-ri:history-line" />
                版本
              </FaButton>
              <FaButton size="sm" variant="outline" @click="openEdit(row.original)">
                <FaIcon name="i-ri:pencil-line" />
                编辑
              </FaButton>
              <FaButton
                size="sm"
                variant="destructive"
                :disabled="!model.canPublish || model.saving"
                @click="confirmDeletePack(row.original)"
              >
                <FaIcon name="i-ri:delete-bin-line" />
                删除
              </FaButton>
            </div>
          </template>
        </FaTable>
      </FaCard>
    </FaPageMain>

    <FaModal
      v-model="editOpen"
      title="编辑整合包"
      class="sm:max-w-xl"
      :show-confirm-button="false"
      show-cancel-button
    >
      <form class="ymcl-form" @submit.prevent>
        <div class="ymcl-form-row">
          <label class="ymcl-label">整合包 ID</label>
          <FaInput :model-value="editForm.packId" disabled />
        </div>
        <div class="ymcl-form-row">
          <label class="ymcl-label">显示名称</label>
          <FaInput v-model="editForm.name" placeholder="展示给启动器的名称（可选）" clearable />
        </div>
        <div class="ymcl-form-row">
          <label class="ymcl-label">简介</label>
          <FaTextarea v-model="editForm.description" :rows="3" placeholder="一句话说明这个包（可选）" />
        </div>
        <div class="ymcl-form-row">
          <label class="ymcl-label">图标 URL</label>
          <FaInput v-model="editForm.icon" placeholder="https://…（可选）" clearable />
        </div>
        <div class="ymcl-form-row">
          <label class="ymcl-label">默认渠道</label>
          <FaInput v-model="editForm.defaultChannel" placeholder="stable / beta（可选）" clearable />
        </div>
      </form>
      <template #footer>
        <FaButton variant="outline" @click="editOpen = false">
          取消
        </FaButton>
        <FaButton :disabled="!model.canPublish" :loading="model.saving" @click="saveEdit">
          保存
        </FaButton>
      </template>
    </FaModal>

    <FaDrawer
      v-model="versionsDrawer"
      :title="`版本清单 · ${activePack?.packId || ''}`"
      side="right"
      :show-confirm-button="false"
      :footer="false"
      content-class="ymcl-versions-drawer"
    >
      <div v-loading="versionsLoading" class="ymcl-drawer-body">
        <FaTable
          table-root-class="max-w-full overflow-x-auto rounded-lg overflow-hidden"
          v-if="activeVersions.length"
          :columns="versionColumns"
          :data="activeVersions"
          row-key="version"
        >
          <template #empty>
            <span class="ymcl-muted">暂无版本</span>
          </template>
          <template #cell-releasedAt="{ row }">
            {{ formatTime(row.original.releasedAt) }}
          </template>
          <template #cell-operations="{ row }">
            <div class="ymcl-row-actions">
              <FaButton size="sm" variant="outline" @click="openManifest(row.original)">
                manifest
              </FaButton>
              <FaButton
                size="sm"
                variant="destructive"
                :disabled="!model.canPublish || model.saving"
                @click="confirmDeleteVersion(row.original)"
              >
                删除
              </FaButton>
            </div>
          </template>
        </FaTable>
        <p v-else-if="!versionsLoading" class="ymcl-muted">
          该整合包还没有版本。
        </p>
      </div>
    </FaDrawer>

    <FaDrawer
      v-model="manifestDrawer"
      :title="`manifest · ${activeVersion?.version || ''}`"
      side="right"
      :show-confirm-button="false"
      :footer="false"
      content-class="ymcl-manifest-drawer"
    >
      <div v-loading="detailLoading" class="ymcl-drawer-body">
        <template v-if="versionDetail">
          <div class="ymcl-action-row">
            <FaTag variant="secondary">不可变版本</FaTag>
            <FaTag variant="outline">{{ fileCount() }} 个文件 · {{ formatSize(totalSize()) }}</FaTag>
            <FaButton size="sm" variant="outline" class="ml-auto" @click="jsonDrawer = true">
              <FaIcon name="i-ri:code-s-slash-line" />
              JSON 全文
            </FaButton>
          </div>
          <FaTable
            table-root-class="max-w-full overflow-x-auto rounded-lg overflow-hidden"
            :columns="fileColumns"
            :data="versionFiles"
            row-key="path"
          >
            <template #empty>
              <span class="ymcl-muted">manifest 无文件条目</span>
            </template>
            <template #cell-sha512="{ row }">
              <code class="ymcl-hash">{{ String(row.original.sha512 || '').slice(0, 12) }}</code>
            </template>
            <template #cell-size="{ row }">
              {{ formatSize(Number(row.original.size) || 0) }}
            </template>
            <template #cell-operations="{ row }">
              <FaButton size="sm" variant="outline" @click="openFilePreview(row.original)">
                <FaIcon name="i-ri:eye-line" />
                预览
              </FaButton>
            </template>
          </FaTable>
        </template>
        <p v-else-if="!detailLoading" class="ymcl-muted">
          manifest 加载失败或不存在。
        </p>
      </div>
    </FaDrawer>

    <JsonViewDrawer v-model="jsonDrawer" :title="`manifest JSON · ${activeVersion?.version || ''}`" :payload="versionDetail" />

    <FilePreviewDrawer
      v-model="previewOpen"
      :sdk="props.sdk"
      :sha512="previewTarget.sha512"
      :path="previewTarget.path"
    />
  </div>
</template>
