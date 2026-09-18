<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { BundleDoc } from '../types'
import {
  FaButton,
  FaCard,
  FaIcon,
  FaInput,
  FaPageHeader,
  FaPageMain,
  FaTable,
  useFaToast,
} from '@yudream/components'
import type { TableColumn } from '@yudream/components'
import { onMounted, reactive, ref } from 'vue'
import { useYmclAdapter } from '../composables/useYmclAdapter'
import { formatSize, formatTime } from '../composables/ymcl-protocol'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const model = useYmclAdapter(props.sdk)
const toast = useFaToast()

const uploadForm = reactive({ bundleId: '', version: '' })
const selectedFile = ref<File | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)

const bundleColumns: TableColumn<BundleDoc>[] = [
  { accessorKey: 'bundleId', header: '包 ID', minWidth: 180 },
  { accessorKey: 'version', header: '版本', width: 120 },
  { id: 'sha256', header: 'sha256', width: 130 },
  { id: 'size', header: '大小', width: 100 },
  { id: 'uploadedAt', header: '上传时间', width: 170 },
  { id: 'operations', header: '操作', width: 130, fixed: 'right' },
]

function pickFile() {
  fileInput.value?.click()
}

function onFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0] || null
  if (file && !file.name.toLowerCase().endsWith('.zip')) {
    toast.error('扩展页面包必须是 .zip 文件')
    selectedFile.value = null
    input.value = ''
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
    if (fileInput.value) {
      fileInput.value.value = ''
    }
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
</script>

<template>
  <div class="ymcl-page">
    <FaPageHeader title="扩展页面包" description="给 YMCL 启动器下发的自定义页面 zip（ESM 入口 + ymcl-bundle.json）。版本不可变，重复上传同版本会返回冲突。" />

    <FaPageMain>
      <div class="ymcl-bundle-grid">
        <FaCard title="上传包" description="服务端重算 sha256 并校验唯一性" content-class="ymcl-card-content">
          <div class="ymcl-binding-form">
            <div class="ymcl-form-row">
              <label class="ymcl-label">包 ID</label>
              <FaInput v-model="uploadForm.bundleId" placeholder="如 domain-welcome" />
            </div>
            <div class="ymcl-form-row">
              <label class="ymcl-label">版本</label>
              <FaInput v-model="uploadForm.version" placeholder="如 1.0.0（不可覆盖）" />
            </div>
            <div class="ymcl-form-row">
              <label class="ymcl-label">zip 文件</label>
              <div class="ymcl-action-row">
                <FaButton size="sm" variant="outline" @click="pickFile">
                  <FaIcon name="i-ri:file-zip-line" />
                  选择文件
                </FaButton>
                <span v-if="selectedFile" class="ymcl-break">
                  {{ selectedFile.name }}（{{ formatSize(selectedFile.size) }}）
                </span>
                <span v-else class="ymcl-muted">未选择</span>
              </div>
            </div>
          </div>
          <input
            ref="fileInput"
            type="file"
            accept=".zip,application/zip"
            class="ymcl-hidden-input"
            @change="onFileChange"
          >
          <div class="ymcl-action-row">
            <FaButton :disabled="!model.canPublish" :loading="model.saving" @click="submitUpload">
              {{ model.canPublish ? '上传注册' : '需要 publish 权限' }}
            </FaButton>
          </div>
          <p class="ymcl-muted">
            上传即注册 {bundleId}@{version}；同一版本不可覆盖，更新内容请新增版本号。单包上限 20MB。
          </p>
        </FaCard>

        <FaCard title="已注册包" description="启动器按 manifest 中的 sha256 校验后加载" content-class="ymcl-card-content ymcl-card-content--wide">
          <FaTable
            table-root-class="max-w-full overflow-x-auto rounded-lg overflow-hidden"
            v-loading="model.loading"
            :columns="bundleColumns"
            :data="model.bundles"
            row-key="bundleId"
          >
            <template #empty>
              <span class="ymcl-muted">暂无扩展页面包。</span>
            </template>
            <template #cell-sha256="{ row }">
              <button type="button" class="ymcl-hash ymcl-hash--button" title="点击复制完整 sha256" @click="copyHash(row.original)">
                {{ String(row.original.sha256 || '').slice(0, 12) }}…
              </button>
            </template>
            <template #cell-size="{ row }">
              {{ formatSize(Number(row.original.size) || 0) }}
            </template>
            <template #cell-uploadedAt="{ row }">
              {{ formatTime(row.original.uploadedAt) }}
            </template>
            <template #cell-operations="{ row }">
              <FaButton size="sm" variant="outline" @click="copyDownloadUrl(row.original)">
                <FaIcon name="i-ri:links-line" />
                下载地址
              </FaButton>
            </template>
          </FaTable>
        </FaCard>
      </div>
    </FaPageMain>
  </div>
</template>
