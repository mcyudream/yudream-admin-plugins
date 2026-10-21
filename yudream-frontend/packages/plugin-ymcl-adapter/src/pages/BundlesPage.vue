<script setup lang="ts">
import { YdLoadingOverlay } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { BundleDoc } from '../types'
import { YdAction, YdButton, YdCard, YdIcon, YdFilePicker, YdInput, YdPageHeader, YdPageMain, useYdToast, YdResponsiveEnhancedTable } from '@yudream/components'
import type { TableColumn, YdFilePickerInstance, ActionMenuItem } from '@yudream/components'
import { onMounted, reactive, ref } from 'vue'
import { useYmclAdapter } from '../composables/useYmclAdapter'
import { formatSize, formatTime, shortId } from '../composables/ymcl-protocol'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const model = useYmclAdapter(props.sdk)
const toast = useYdToast()

const uploadForm = reactive({ bundleId: '', version: '' })
const selectedFile = ref<File | null>(null)
const fileInput = ref<YdFilePickerInstance | null>(null)

const bundleColumns: TableColumn<BundleDoc>[] = [
  { accessorKey: 'bundleId', header: '包 ID', minWidth: 180 },
  { accessorKey: 'version', header: '版本', width: 120 },
  { id: 'sha256', header: 'sha256', width: 130 },
  { id: 'size', header: '大小', width: 100 },
  { id: 'uploadedAt', header: '上传时间', width: 170 },
]

function onFileSelect(files: File[]) {
  const file = files[0] || null
  if (file && !file.name.toLowerCase().endsWith('.zip')) {
    toast.error('扩展页面包必须是 .zip 文件')
    selectedFile.value = null
    fileInput.value?.reset()
    return
  }
  selectedFile.value = file
}

async function submitUpload() {
  if (!selectedFile.value) {
    toast.error('请选择 zip 包')
    return
  }
  const result = await model.uploadBundle(uploadForm.bundleId, uploadForm.version, selectedFile.value)
  if (result) {
    uploadForm.bundleId = ''
    uploadForm.version = ''
    selectedFile.value = null
    fileInput.value?.reset()
  }
}

function copyHash(row: BundleDoc) {
  void model.copy(row.sha256, 'sha256 已复制')
}

function copyDownloadUrl(row: BundleDoc) {
  void model.copy(model.downloadBundleUrl(row.bundleId, row.version), '下载地址已复制')
}

onMounted(() => {
  void model.loadBundles()
})

/** 行操作：复制 bundle 下载地址 */
const rowActions: ActionMenuItem<BundleDoc>[] = [
  { label: '下载地址', icon: 'morph:link', onClick: row => copyDownloadUrl(row) },
]

</script>

<template>
  <div class="ymcl-page">

    <YdPageHeader title="扩展页面包" />

    <YdPageMain>
      <div class="ymcl-bundle-grid">
        <YdCard title="上传包" description="服务端重算 sha256 并校验唯一性" content-class="ymcl-card-content">
          <div class="ymcl-binding-form">
            <div class="ymcl-form-row">
              <label class="ymcl-label">包 ID</label>
              <YdInput v-model="uploadForm.bundleId" placeholder="如 domain-welcome" />
            </div>
            <div class="ymcl-form-row">
              <label class="ymcl-label">版本</label>
              <YdInput v-model="uploadForm.version" placeholder="如 1.0.0（不可覆盖）" />
            </div>
            <div class="ymcl-form-row">
              <label class="ymcl-label">zip 文件</label>
              <div class="ymcl-action-row">
                <YdFilePicker ref="fileInput" accept=".zip,application/zip" aria-label="选择 zip 文件" @select="onFileSelect">
                  <template #default="{ open, triggerAttrs }">
                    <YdButton v-bind="triggerAttrs" size="sm" variant="outline" @click="open">
                  <YdIcon name="i-ri:file-zip-line" />
                  选择文件
                </YdButton>
                  </template>
                </YdFilePicker>
                <span v-if="selectedFile" class="ymcl-break">
                  {{ selectedFile.name }}（{{ formatSize(selectedFile.size) }}）
                </span>
                <span v-else class="ymcl-muted">未选择</span>
              </div>
            </div>
          </div>
          <div class="ymcl-action-row">
            <YdButton :disabled="!model.canPublish" :loading="model.saving" @click="submitUpload">
              {{ model.canPublish ? '上传注册' : '需要 publish 权限' }}
            </YdButton>
          </div>
          <p class="ymcl-muted">
            上传即注册 {bundleId}@{version}；同一版本不可覆盖，更新内容请新增版本号。单包上限 20MB。
          </p>
        </YdCard>

        <YdCard title="已注册包" description="启动器按 manifest 中的 sha256 校验后加载" content-class="ymcl-card-content ymcl-card-content--wide">
          <YdLoadingOverlay :loading="model.loading"><YdResponsiveEnhancedTable
              :actions="rowActions"

            table-root-class="max-w-full rounded-lg" table-class="min-w-[700px]"
            
            :columns="bundleColumns"
            :data="model.bundles"
            row-key="bundleId"
          >
            <template #empty>
              <span class="ymcl-muted">暂无扩展页面包。</span>
            </template>
            <template #cell-bundleId="{ row }">
              <code class="ymcl-hash ymcl-truncate" :title="row.original.bundleId">{{ shortId(row.original.bundleId) }}</code>
            </template>
            <template #cell-sha256="{ row }">
              <YdAction type="button" class="ymcl-hash ymcl-hash--button" title="点击复制完整 sha256" @click="copyHash(row.original)">
                {{ String(row.original.sha256 || '').slice(0, 12) }}…
              </YdAction>
            </template>
            <template #cell-size="{ row }">
              {{ formatSize(Number(row.original.size) || 0) }}
            </template>
            <template #cell-uploadedAt="{ row }">
              {{ formatTime(row.original.uploadedAt) }}
            </template>
</YdResponsiveEnhancedTable></YdLoadingOverlay>

        </YdCard>
      </div>
    </YdPageMain>
  </div>
</template>
