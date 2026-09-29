<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { TableColumn } from '@yudream/components'
import type { AppReleaseSettingsView, AppReleaseView } from '../types'
import { Tag as ArcoTag } from '@arco-design/web-vue'
import { FaButton, FaCard, FaIcon, FaInput, FaModal, FaNumberField, FaPageHeader, FaPageMain, FaResponsiveTable, FaSwitch, FaTag, FaTextarea, useFaModal, useFaToast } from '@yudream/components'
import { computed, onMounted, ref } from 'vue'
import { createAppReleaseApi, errorMessage } from '../api/app-release-api'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = createAppReleaseApi(props.sdk)
const toast = useFaToast()
const confirm = useFaModal()

const loading = ref(false)
const rows = ref<AppReleaseView[]>([])
const settings = ref<AppReleaseSettingsView | null>(null)
const minVersionCode = ref(0)
const savingSettings = ref(false)
const publishingId = ref('')
const deletingId = ref('')

const uploadOpen = ref(false)
const uploading = ref(false)
const uploadFile = ref<File | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const uploadForm = ref({
  platform: 'android',
  versionCode: 0,
  versionName: '',
  changelog: '',
  forceUpdate: false,
})

const latestVersionCode = computed(() =>
  rows.value.filter(item => item.published).reduce((max, item) => Math.max(max, item.versionCode), 0))

const columns: TableColumn<AppReleaseView>[] = [
  { id: 'version', header: '版本', width: 170 },
  { accessorKey: 'platform', header: '平台', width: 90 },
  { id: 'state', header: '状态', width: 150 },
  { id: 'file', header: '更新包', width: 220 },
  { id: 'changelog', header: '更新日志', minWidth: 260 },
  { id: 'publishedAtLabel', header: '发布时间', width: 150 },
  { id: 'operation', header: '操作', width: 230, fixed: 'right' },
]

function fmtSize(size: number): string {
  if (!size) {
    return '-'
  }
  if (size >= 1024 * 1024) {
    return `${(size / 1024 / 1024).toFixed(1)} MB`
  }
  return `${Math.max(1, Math.round(size / 1024))} KB`
}

function fmtTime(ts: number): string {
  if (!ts) {
    return '-'
  }
  const d = new Date(ts)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

async function load() {
  loading.value = true
  try {
    const [list, setting] = await Promise.all([api.list(), api.settings()])
    rows.value = list
    settings.value = setting
    minVersionCode.value = Number(setting.minVersionCode ?? 0)
  }
  catch (error) {
    toast.error(errorMessage(error, '加载更新发布数据失败'))
  }
  finally {
    loading.value = false
  }
}

async function saveSettings() {
  const parsed = Math.max(0, Math.round(Number(minVersionCode.value) || 0))
  savingSettings.value = true
  try {
    settings.value = await api.saveSettings(parsed)
    toast.success(parsed > 0 ? `已保存：低于 versionCode ${parsed} 的客户端将强制更新` : '已关闭强制更新线')
  }
  catch (error) {
    toast.error(errorMessage(error, '保存设置失败'))
  }
  finally {
    savingSettings.value = false
  }
}

function openUpload() {
  uploadFile.value = null
  uploadForm.value = {
    platform: 'android',
    versionCode: latestVersionCode.value + 1,
    versionName: '',
    changelog: '',
    forceUpdate: false,
  }
  uploadOpen.value = true
}

function pickFile() {
  fileInput.value?.click()
}

const uploadVersionCode = computed({
  get: () => uploadForm.value.versionCode,
  set: (value: number | undefined) => { uploadForm.value.versionCode = Math.max(0, Math.round(Number(value) || 0)) },
})

function onFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0] ?? null
  uploadFile.value = file
  if (file && !uploadForm.value.versionName) {
    // 从文件名推断版本号（ydam-0.2.1.apk / app-0.2.1-release.apk）
    const match = /(\d+\.\d+(?:\.\d+)?)/.exec(file.name)
    if (match) {
      uploadForm.value.versionName = match[1]
    }
  }
}

async function submitUpload() {
  const versionCode = Number(uploadForm.value.versionCode)
  if (!uploadFile.value) {
    toast.error('请选择更新包文件（APK）')
    return
  }
  if (!Number.isInteger(versionCode) || versionCode <= 0) {
    toast.error('versionCode 必须为正整数（客户端比较依据）')
    return
  }
  if (!uploadForm.value.versionName.trim()) {
    toast.error('请填写版本名（如 0.2.1）')
    return
  }
  uploading.value = true
  try {
    await api.upload({
      platform: uploadForm.value.platform,
      versionCode,
      versionName: uploadForm.value.versionName.trim(),
      changelog: uploadForm.value.changelog,
      forceUpdate: uploadForm.value.forceUpdate,
      file: uploadFile.value,
    })
    toast.success('已上传，发布后客户端可见')
    uploadOpen.value = false
    await load()
  }
  catch (error) {
    toast.error(errorMessage(error, '上传失败'))
  }
  finally {
    uploading.value = false
  }
}

async function togglePublish(row: AppReleaseView) {
  publishingId.value = row.id
  try {
    await (row.published ? api.unpublish(row.id) : api.publish(row.id))
    toast.success(row.published ? '已下架，客户端不再提示该版本' : '已发布，客户端将收到更新提示')
    await load()
  }
  catch (error) {
    toast.error(errorMessage(error, '操作失败'))
  }
  finally {
    publishingId.value = ''
  }
}

function confirmDelete(row: AppReleaseView) {
  confirm.confirm({
    title: '删除版本包',
    content: `确定删除 ${row.versionName}（versionCode ${row.versionCode}）？更新包文件将一并删除，不可恢复。`,
    onConfirm: async () => {
      deletingId.value = row.id
      try {
        await api.remove(row.id)
        toast.success('已删除')
        await load()
      }
      catch (error) {
        toast.error(errorMessage(error, '删除失败'))
      }
      finally {
        deletingId.value = ''
      }
    },
  })
}

onMounted(load)
</script>

<template>
  <FaPageMain>
    <FaPageHeader title="更新发布" description="YDAM 更新包发布、更新日志与强制更新管理">
      <div class="app-release-toolbar">
        <span class="text-sm text-secondary-foreground/60">
          最新 versionCode {{ latestVersionCode }} · 强制更新线 {{ settings?.minVersionCode ?? 0 }}
        </span>
        <FaButton @click="openUpload">
          <FaIcon name="i-ri:upload-2-line" />发布新版本
        </FaButton>
      </div>
    </FaPageHeader>

    <FaCard class="mb-4">
      <div class="app-release-toolbar">
        <span class="text-sm font-medium">强制更新线（versionCode）</span>
        <FaNumberField v-model="minVersionCode" class="app-release-filter-input" :min="0" :max="2147483647" />
        <FaButton variant="outline" :loading="savingSettings" @click="saveSettings">
          保存
        </FaButton>
        <span class="text-xs text-secondary-foreground/60">
          低于该 versionCode 的客户端必须更新后才能继续使用；发布时也可单独勾选「强制更新」。
        </span>
      </div>
    </FaCard>

    <FaResponsiveTable
      v-loading="loading"
      :columns="columns"
      :data="rows"
      row-key="id"
      table-root-class="app-release-table-scroll"
      table-class="app-release-table-w900"
      border
      stripe
      empty-text="暂无版本包，点击右上角「发布新版本」上传 APK"
    >
      <template #cell-version="{ row }">
        <span class="app-release-title">{{ row.original.versionName }}</span>
        <span class="app-release-subtitle">versionCode {{ row.original.versionCode }}</span>
      </template>
      <template #cell-state="{ row }">
        <div class="flex flex-wrap gap-1">
          <FaTag v-if="row.original.published" color="green">已发布</FaTag>
          <FaTag v-else color="gray">未发布</FaTag>
          <FaTag v-if="row.original.forceUpdate" color="red">强制更新</FaTag>
        </div>
      </template>
      <template #cell-file="{ row }">
        <span class="break-all">{{ row.original.fileName }}</span>
        <span class="app-release-subtitle">{{ fmtSize(row.original.fileSize) }}</span>
      </template>
      <template #cell-changelog="{ row }">
        <span v-if="row.original.changelog" class="app-release-changelog line-clamp-3">{{ row.original.changelog }}</span>
        <span v-else class="app-release-subtitle">未填写</span>
      </template>
      <template #cell-operation="{ row }">
        <div class="flex-center gap-2">
          <FaButton
            size="sm"
            :variant="row.original.published ? 'outline' : 'default'"
            :loading="publishingId === row.original.id"
            @click="togglePublish(row.original)"
          >
            {{ row.original.published ? '下架' : '发布' }}
          </FaButton>
          <a :href="api.downloadUrl(row.original.id)" target="_blank" rel="noopener">
            <FaButton size="sm" variant="outline">
              下载
            </FaButton>
          </a>
          <FaButton size="sm" variant="destructive" :loading="deletingId === row.original.id" @click="confirmDelete(row.original)">
            删除
          </FaButton>
        </div>
      </template>
      <template #card="{ row }">
        <FaCard class="w-full">
          <div class="flex flex-col gap-3">
            <div class="flex items-center justify-between gap-2">
              <span class="min-w-0 break-words text-base font-semibold">{{ row.versionName }}</span>
              <div class="flex flex-wrap gap-1">
                <FaTag v-if="row.published" color="green">
                  已发布
                </FaTag>
                <FaTag v-else color="gray">
                  未发布
                </FaTag>
                <FaTag v-if="row.forceUpdate" color="red">
                  强制更新
                </FaTag>
              </div>
            </div>
            <div class="flex flex-col gap-1 text-sm">
              <div class="flex gap-2">
                <span class="shrink-0 text-secondary-foreground/60">版本线</span>
                <span>versionCode {{ row.versionCode }} · {{ row.platform }}</span>
              </div>
              <div class="flex gap-2">
                <span class="shrink-0 text-secondary-foreground/60">更新包</span>
                <span class="break-all">{{ row.fileName }}（{{ fmtSize(row.fileSize) }}）</span>
              </div>
              <div class="flex gap-2">
                <span class="shrink-0 text-secondary-foreground/60">发布时间</span>
                <span>{{ fmtTime(row.publishedAt) }}</span>
              </div>
              <div v-if="row.changelog" class="flex gap-2">
                <span class="shrink-0 text-secondary-foreground/60">更新日志</span>
                <span class="break-all">{{ row.changelog }}</span>
              </div>
            </div>
            <div class="flex flex-wrap gap-2 border-t pt-3">
              <FaButton size="sm" :variant="row.published ? 'outline' : 'default'" :loading="publishingId === row.id" @click="togglePublish(row)">
                {{ row.published ? '下架' : '发布' }}
              </FaButton>
              <a :href="api.downloadUrl(row.id)" target="_blank" rel="noopener">
                <FaButton size="sm" variant="outline">
                  下载
                </FaButton>
              </a>
              <FaButton size="sm" variant="destructive" :loading="deletingId === row.id" @click="confirmDelete(row)">
                删除
              </FaButton>
            </div>
          </div>
        </FaCard>
      </template>
    </FaResponsiveTable>

    <FaModal v-model:visible="uploadOpen" title="发布新版本" :width="560" :mask-closable="false" unmount-on-close>
      <div class="flex flex-col gap-4">
        <input ref="fileInput" type="file" accept=".apk,application/vnd.android.package-archive" class="hidden" @change="onFileChange">
        <div>
          <div class="mb-1 text-sm font-medium">
            更新包（APK）
          </div>
          <FaButton variant="outline" @click="pickFile">
            <FaIcon name="i-ri:file-upload-line" />{{ uploadFile ? uploadFile.name : '选择 APK 文件' }}
          </FaButton>
          <div v-if="uploadFile" class="app-release-subtitle mt-1">
            {{ fmtSize(uploadFile.size) }}
          </div>
        </div>
        <div class="flex gap-3">
          <div class="flex-1">
            <div class="mb-1 text-sm font-medium">
              versionCode
            </div>
            <FaNumberField v-model="uploadVersionCode" :min="1" :max="2147483647" />
          </div>
          <div class="flex-1">
            <div class="mb-1 text-sm font-medium">
              版本名
            </div>
            <FaInput v-model="uploadForm.versionName" placeholder="如 0.2.1" clearable />
          </div>
        </div>
        <div>
          <div class="mb-1 text-sm font-medium">
            更新日志
          </div>
          <FaTextarea v-model="uploadForm.changelog" placeholder="本次更新内容（将展示在 App 更新弹窗与「关于软件」）" :auto-size="{ minRows: 4, maxRows: 10 }" />
        </div>
        <div class="flex items-center gap-2">
          <FaSwitch v-model="uploadForm.forceUpdate" />
          <span class="text-sm">强制更新（低于该版本的客户端必须升级后才能继续使用）</span>
        </div>
        <ArcoTag v-if="uploadForm.forceUpdate" color="red">
          注意：发布后将阻断所有旧版本客户端
        </ArcoTag>
      </div>
      <template #footer>
        <div class="flex justify-end gap-2">
          <FaButton variant="outline" @click="uploadOpen = false">
            取消
          </FaButton>
          <FaButton :loading="uploading" @click="submitUpload">
            上传
          </FaButton>
        </div>
      </template>
    </FaModal>
  </FaPageMain>
</template>
